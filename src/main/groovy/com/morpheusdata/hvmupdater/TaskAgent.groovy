package com.morpheusdata.hvmupdater

import com.morpheusdata.core.MorpheusContext
import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Runs privileged appliance work by executing shell commands directly on the
 * appliance ComputeServer via morpheus.executeCommandOnServer(server, cmd) — the
 * clean path Travis suggested. No task object, no /api/tasks, no localScript
 * type-binding. sudo is inline in the commands. For the self-managed appliance
 * (server 25) this is local; a remote target would use its own server id.
 *
 * Each command block ends by echoing RESULT_JSON={...} which we parse from the
 * ServiceResponse output.
 */
@Slf4j
class TaskAgent {

    final MorpheusContext morpheus
    final Closure<Map> settings
    private final ExecutorService pool = Executors.newFixedThreadPool(2, { r ->
        Thread t = new Thread(r, 'hvm-updater-cmd'); t.daemon = true; t })
    private final Map<String, Map> jobs = new ConcurrentHashMap<>()
    private volatile Object cachedServer
    private volatile Long cachedServerId

    TaskAgent(MorpheusContext morpheus, Closure<Map> settings, Closure<Map> configProvider = null) {
        this.morpheus = morpheus
        this.settings = settings
        this.configProvider = configProvider
    }

    Closure<Map> configProvider  // returns plugin page-config (has applianceServerId)

    private Long serverId() {
        // applianceServerId is saved in the plugin's page config, not the OptionType settings
        String sid = null
        try { sid = configProvider ? (configProvider().applianceServerId?.toString()) : null } catch (Exception ignored) {}
        if (!sid) sid = settings().applianceServerId?.toString()
        return (sid && sid.isLong()) ? (sid as Long) : null
    }

    volatile String serverLookupError

    private Object server() {
        Long id = serverId()
        if (!id) { serverLookupError = 'no applianceServerId in config'; return null }
        if (cachedServer != null && cachedServerId == id) return cachedServer
        Object srv = null; List<String> errs = []
        List<Closure> tries = [
            { morpheus.services.computeServer.get(id) },
            { morpheus.async.computeServer.get(id).blockingGet() },
            { morpheus.services.computeServer.listById([id])?.getAt(0) },
            { morpheus.async.computeServer.listById([id]).toList().blockingGet()?.getAt(0) },
        ]
        for (Closure t : tries) {
            try { srv = t(); if (srv) break } catch (Exception e) { errs << (e.message?.take(80) ?: e.class.simpleName) }
        }
        if (!srv) serverLookupError = "computeServer.get(${id}) failed: ${errs.join('; ')}".toString()
        cachedServer = srv; cachedServerId = id
        return srv
    }

    /** Run a shell command on the appliance server; return its stdout (best-effort across ServiceResponse shapes). */
    String exec(String cmd) {
        Object srv = server()
        if (srv == null) throw new RuntimeException(serverLookupError ?: 'appliance server not set — pick the appliance VM in Setup')
        Object resp = morpheus.executeCommandOnServer(srv, cmd)
        resp = unwrapRx(resp)
        return extractOutput(resp)
    }

    /** executeCommandOnServer may return an rx Single/Observable, a TaskResult, or a ServiceResponse. */
    private static Object unwrapRx(Object o) {
        if (o == null) return null
        try {
            String cn = o.class.name
            if (cn.contains('rxjava') || cn.contains('Single') || cn.contains('Observable') || cn.contains('Maybe')) {
                try { return o.blockingGet() } catch (Exception e1) {
                    try { def l = o.toList().blockingGet(); return l ? l[0] : null } catch (Exception e2) { return o }
                }
            }
        } catch (Exception ignored) {}
        return o
    }

    private static String extractOutput(resp) {
        if (resp == null) return ''
        for (String prop : ['data', 'output', 'msg']) {
            try { if (resp.hasProperty(prop) && resp."$prop") { def v = resp."$prop"; if (v instanceof CharSequence) return v.toString() } } catch (Exception ignored) {}
        }
        try { if (resp instanceof Map) return (resp.output ?: resp.data ?: resp.msg ?: '') as String } catch (Exception ignored) {}
        return resp.toString()
    }

    static Map parseResult(String output) {
        if (!output) return [ok: false, error: 'no command output']
        String line = output.readLines().reverse().find { it.trim().startsWith('RESULT_JSON=') }
        if (!line) return [ok: false, error: "no RESULT_JSON (output: ${output.take(200)})".toString()]
        try { return new JsonSlurper().parseText(line.trim().substring('RESULT_JSON='.length())) as Map }
        catch (Exception e) { return [ok: false, error: "bad RESULT_JSON: ${e.message}".toString()] }
    }

    // ── command builders (inline bash, sudo where needed) ─────────────────────
    /** Persistent default. Under WORK_DIR (root-created) — ensureDir() below chowns it to the executing user. */
    static final String DEFAULT_DOWNLOAD_DIR = UpgradeService.WORK_DIR + '/downloads'

    private volatile String dirRaw, dirResolved, dirWarning
    private volatile String remoteHome

    /**
     * Resolve the configured download dir to an absolute path we can single-quote safely.
     * Every command runs via executeCommandOnServer with cwd=/tmp and no guarantee that $HOME is set,
     * and every path we emit is sq()-quoted, so a literal "~" or "$HOME" in the setting used to become a
     * directory NAMED "~" on /tmp (tmpfs). Now: blank -> default; ~ / $HOME / ${HOME} -> the executing user's
     * real home (looked up once); anything still relative -> default with a warning.
     */
    private String dir() {
        String raw = (settings().downloadDir ?: '').toString().trim()
        if (dirResolved != null && raw == dirRaw) return dirResolved
        String warn = null
        String d = raw
        if (!d) {
            d = DEFAULT_DOWNLOAD_DIR
        } else if (d == '~' || d.startsWith('~/') || d.startsWith('$HOME') || d.startsWith('${HOME}')) {
            String home = homeDir()
            if (!home || !home.startsWith('/') || home.startsWith('/tmp')) {
                warn = "could not resolve '~' for the command user (HOME='${home ?: ''}'); using default ${DEFAULT_DOWNLOAD_DIR}".toString()
                d = DEFAULT_DOWNLOAD_DIR
            } else {
                String rest = d.startsWith('${HOME}') ? d.substring(7) : (d.startsWith('$HOME') ? d.substring(5) : d.substring(1))
                d = home + rest
            }
        }
        if (!d.startsWith('/')) {
            warn = "download directory '${raw}' is not absolute; using default ${DEFAULT_DOWNLOAD_DIR}".toString()
            d = DEFAULT_DOWNLOAD_DIR
        }
        while (d.length() > 1 && d.endsWith('/')) d = d.substring(0, d.length() - 1)
        if (d == '/tmp' || d.startsWith('/tmp/') || d.startsWith('/dev/shm') || d.startsWith('/run/')) {
            warn = "${d} is on tmpfs — packages here vanish on reboot and eat RAM; set Download directory to a persistent path such as ${DEFAULT_DOWNLOAD_DIR}".toString()
        }
        if (warn) log.warn("hvm-updater downloadDir: {}", warn)
        dirRaw = raw; dirResolved = d; dirWarning = warn
        return d
    }

    /** Home of whatever user executeCommandOnServer runs as. Looked up on the appliance, cached. */
    private String homeDir() {
        if (remoteHome != null) return remoteHome
        String h = null
        try {
            Map r = parseResult(exec('H="${HOME:-}"; [ -n "$H" ] || H=$(getent passwd "$(id -un)" 2>/dev/null | cut -d: -f6); printf "RESULT_JSON={\\"ok\\":true,\\"home\\":\\"%s\\"}\\n" "$H"'))
            if (r.ok) h = (r.home ?: '').toString().trim()
        } catch (Exception e) { log.warn("hvm-updater: home lookup failed: {}", e.message) }
        if (h) remoteHome = h   // only cache a successful lookup so a later fix (server picked in Setup) takes effect
        return h ?: ''
    }

    /** bash snippet: create $D and make it writable by the current user (sudo for root-owned parents like WORK_DIR). */
    private static String ensureDir() {
        'mkdir -p "$D" 2>/dev/null || sudo mkdir -p "$D"; [ -w "$D" ] || sudo chown "$(id -un)" "$D" 2>/dev/null; '
    }


    private Map runAction(String script) {
        try { return parseResult(exec(script)) }
        catch (Exception e) { return [ok: false, error: e.message] }
    }

    Map checkDir() {
        String d = dir()
        Map r = runAction("""D=${sq(d)}; ${ensureDir()}if [ ! -w "\$D" ]; then echo "RESULT_JSON={\\"ok\\":false,\\"error\\":\\"not writable: \$D\\"}"; exit 0; fi; RD=\$(readlink -f "\$D"); FS=\$(df --output=fstype "\$D" 2>/dev/null | tail -1 | tr -d ' '); A=\$(df -BG --output=avail "\$D" 2>/dev/null | tail -1 | tr -dc '0-9'); U=\$(du -sm "\$D" 2>/dev/null | cut -f1); echo "RESULT_JSON={\\"ok\\":true,\\"dir\\":\\"\$RD\\",\\"fstype\\":\\"\$FS\\",\\"freeGb\\":\\"\${A:-?}\\",\\"usedMb\\":\${U:-0}}" """)
        String w = dirWarning
        if (r.ok && !w && (r.fstype in ['tmpfs', 'ramfs'])) w = "${r.dir} is on ${r.fstype} — packages here vanish on reboot; set Download directory to a persistent path".toString()
        if (w) r.warning = w
        if (dirRaw) r.configured = dirRaw
        return r
    }

    /** Delete a staged file (+ its sidecars). */
    Map deleteFile(String fileName) {
        String d = dir()
        String base = "${d}/${fileName}"
        return runAction("""cd ${sq(d)} 2>/dev/null || { echo 'RESULT_JSON={"ok":false,"error":"dir missing"}'; exit 0; }; rm -f ${sq(fileName)} ${sq(fileName)}.sha512ok ${sq(fileName)}.sig ${sq(fileName)}.err ${sq(fileName)}.part 2>/dev/null && echo 'RESULT_JSON={"ok":true}' || echo 'RESULT_JSON={"ok":false,"error":"delete failed"}'""")
    }

    /** Move a staged file (+ sidecars) to another path on the appliance. */
    Map moveFile(String fileName, String destDir) {
        String d = dir()
        return runAction("""SRC=${sq(d)}; DST=${sq(destDir)}; mkdir -p "\$DST" 2>/dev/null; if [ ! -w "\$DST" ]; then echo "RESULT_JSON={\\"ok\\":false,\\"error\\":\\"destination not writable: \$DST\\"}"; exit 0; fi; cd "\$SRC" 2>/dev/null || { echo 'RESULT_JSON={"ok":false,"error":"source missing"}'; exit 0; }; for x in ${sq(fileName)} ${sq(fileName)}.sha512ok ${sq(fileName)}.sig; do [ -e "\$x" ] && mv -f "\$x" "\$DST/"; done && echo "RESULT_JSON={\\"ok\\":true,\\"dest\\":\\"\$DST\\"}" || echo 'RESULT_JSON={"ok":false,"error":"move failed"}'""")
    }

    String applianceVersion() {
        Map r = runAction("""V=\$(awk '/^(morpheus-appliance|morpheus-vm-essentials|hpe)/ {print \$2; exit}' /opt/morpheus/version-manifest.txt 2>/dev/null); echo "RESULT_JSON={\\"ok\\":true,\\"version\\":\\"\${V:-}\\"}" """)
        return (r.ok && r.version) ? (r.version as String) : null
    }

    List<Map> staged() {
        String d = dir()
        Map r = runAction("""cd ${sq(d)} 2>/dev/null || { echo 'RESULT_JSON={"ok":true,"files":[]}'; exit 0; }; OUT='['; F=1; for f in *.deb *.rpm *.iso *.zip *.qcow2* *.tar.gz; do [ -e "\$f" ] || continue; SZ=\$(stat -c%s "\$f" 2>/dev/null); V=false; [ -e "\$f.sha512ok" ] && V=true; [ \$F -eq 1 ] || OUT="\$OUT,"; F=0; OUT="\$OUT{\\"fileName\\":\\"\$f\\",\\"bytes\\":\${SZ:-0},\\"verified\\":\$V}"; done; OUT="\$OUT]"; echo "RESULT_JSON={\\"ok\\":true,\\"dir\\":\\"${d}\\",\\"files\\":\$OUT}" """)
        (r.ok && r.files instanceof List) ? (r.files as List<Map>).collect {
            [fileName: it.fileName, bytes: it.bytes, verified: it.verified, version: SwcClient.versionOf(it.fileName as String),
             path: "${d}/${it.fileName}".toString()]
        }.sort { -(it.bytes as long) } : []
    }

    Map status() { [jobs: jobs.values().collect { new LinkedHashMap(it) }, files: staged()] }

    Map startDownload(Map f, boolean withSig) {
        String name = f.fileName as String
        Map ex = jobs[name]
        if (ex && ex.status in ['queued', 'running']) return ex
        Map job = [fileName: name, status: 'queued', bytes: 0L, total: 0L, pct: 0, error: null,
                   started: System.currentTimeMillis(), updated: System.currentTimeMillis()]
        jobs[name] = job
        pool.submit({
            job.status = 'running'; job.updated = System.currentTimeMillis()
            String d = dir()
            String dest = "${d}/${name}"
            String sig = (withSig && f.sigUrl) ? "curl -fL -o ${sq(dest)}.sig ${sq(f.sigUrl as String)} 2>/dev/null || true;" : ''
            Map r = runAction("""D=${sq(d)}; ${ensureDir()}if [ ! -w "\$D" ]; then echo "RESULT_JSON={\\"ok\\":false,\\"error\\":\\"download dir not writable: \$D\\"}"; exit 0; fi; if [ -e ${sq(dest)}.sha512ok ]; then echo 'RESULT_JSON={"ok":true,"status":"already"}'; exit 0; fi; if ! curl -fL --retry 3 -C - -o ${sq(dest)}.part ${sq(f.url as String)} 2>${sq(dest)}.err; then echo "RESULT_JSON={\\"ok\\":false,\\"error\\":\\"curl failed\\"}"; exit 0; fi; mv -f ${sq(dest)}.part ${sq(dest)}; A=\$(sha512sum ${sq(dest)} | cut -d' ' -f1); if [ "\$A" != ${sq(((f.sha512 ?: '') as String).toLowerCase())} ]; then rm -f ${sq(dest)}; echo 'RESULT_JSON={"ok":false,"error":"sha512 mismatch"}'; exit 0; fi; touch ${sq(dest)}.sha512ok; ${sig} SZ=\$(stat -c%s ${sq(dest)}); echo "RESULT_JSON={\\"ok\\":true,\\"status\\":\\"done\\",\\"bytes\\":\${SZ:-0}}" """)
            if (r.ok) { job.status = 'done'; job.pct = 100; job.bytes = (r.bytes ?: 0L) }
            else { job.status = 'failed'; job.error = r.error }
            job.updated = System.currentTimeMillis()
        } as Runnable)
        return job
    }

    Map upstatus() {
        Map r = runAction("""W=${sq(UpgradeService.WORK_DIR)}; ST='{}'; [ -e "\$W/upgrade.json" ] && ST=\$(sudo cat "\$W/upgrade.json" 2>/dev/null || cat "\$W/upgrade.json" 2>/dev/null); [ -z "\$ST" ] && ST='{}'; LG=\$(sudo tail -n 200 "\$W/upgrade.log" 2>/dev/null || tail -n 200 "\$W/upgrade.log" 2>/dev/null); echo "RESULT_JSON={\\"ok\\":true,\\"state\\":\$ST,\\"logB64\\":\\"\$(printf '%s' "\$LG" | base64 -w0)\\"}" """)
        if (!r.ok) return [state: [status: 'idle'], log: []]
        String logText = ''
        try { if (r.logB64) logText = new String(r.logB64.toString().decodeBase64()) } catch (Exception ignored) {}
        return [state: (r.state ?: [status: 'idle']), log: logText ? logText.readLines() : []]
    }

    Map launch(String packagePath, String envContent) {
        String w = UpgradeService.WORK_DIR
        String runnerB64 = getClass().getResourceAsStream('/runner/hvm-updater-upgrade.sh').text.bytes.encodeBase64().toString()
        String envB64 = envContent.bytes.encodeBase64().toString()
        return runAction("""W=${sq(w)}; sudo mkdir -p "\$W" && sudo chmod 755 "\$W"; printf '%s' ${sq(runnerB64)} | base64 -d | sudo tee "\$W/hvm-updater-upgrade.sh" >/dev/null; printf '%s' ${sq(envB64)} | base64 -d | sudo tee "\$W/upgrade.env" >/dev/null; sudo chmod 755 "\$W/hvm-updater-upgrade.sh"; sudo sh -c 'printf "{\\"status\\":\\"running\\",\\"step\\":\\"starting\\",\\"stepIndex\\":0,\\"started\\":\$(date +%s%3N),\\"updated\\":\$(date +%s%3N)}" > '"\$W"'/upgrade.json'; if sudo sh -c "systemctl reset-failed ${UpgradeService.UNIT} 2>/dev/null; systemd-run --unit=${UpgradeService.UNIT} --collect --property=WorkingDirectory=\$W \$W/hvm-updater-upgrade.sh \$W/upgrade.env"; then echo 'RESULT_JSON={"ok":true,"launched":true}'; else echo 'RESULT_JSON={"ok":false,"error":"systemd-run failed"}'; fi""")
    }

    static String sq(String v) { "'" + (v ?: '').replace("'", "'\\''") + "'" }
    void shutdown() { pool.shutdownNow() }
}
