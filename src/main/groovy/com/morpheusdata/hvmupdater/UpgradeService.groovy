package com.morpheusdata.hvmupdater

import groovy.util.logging.Slf4j

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Orchestrates the appliance upgrade through the agent task and the detached runner.
 * No SSH here: privileged work goes through TaskAgent (a local Morpheus task in
 * appliance/user context). The reconfigure survives because the agent's `launch`
 * starts the runner as a transient systemd unit outside morpheus-runsvdir.
 */
@Slf4j
class UpgradeService {

    static final String WORK_DIR = '/var/opt/morpheus/hvm-updater'
    static final String RUNNER   = 'hvm-updater-upgrade.sh'
    static final String STATE    = 'upgrade.json'
    static final String ENV      = 'upgrade.env'
    static final String LOG      = 'upgrade.log'
    static final String UNIT     = 'hvm-updater-upgrade'

    final Closure<Map> settings
    final TaskAgent agent
    final CredentialResolver creds
    private final AtomicBoolean launching = new AtomicBoolean(false)
    private final AtomicBoolean backingUp = new AtomicBoolean(false)
    volatile Map backupStatus = [status: 'idle']
    /** Plugin-side phase before the runner exists (Morpheus backup + launch). Cleared once the runner owns upgrade.json. */
    volatile Map launchStatus = null
    static final List<String> STEPS = ['morpheus-backup', 'preflight', 'backup', 'stop-ui', 'install', 'reconfigure', 'wait-ui', 'done']

    UpgradeService(Closure<Map> settings, TaskAgent agent, CredentialResolver creds) {
        this.settings = settings
        this.agent = agent
        this.creds = creds
    }

    String currentVersion() {
        try { String v = agent.applianceVersion(); if (v) return v } catch (Exception ignored) {}
        try {
            Map ping = new MorpheusApi(settings().applianceUrl as String, 'ping').get('/api/ping')
            return (ping.buildVersion ?: ping.applianceVersion ?: ping.version) as String
        } catch (Exception e) { log.debug("version lookup failed: ${e.message}"); return null }
    }

    Map state() {
        Map ls = launchStatus
        Map rs = [status: 'idle']
        try { rs = (agent.upstatus().state ?: [status: 'idle']) as Map } catch (Exception ignored) {}
        if (ls) {
            // runner state wins once it's newer than our launch (it wrote upgrade.json after agent.launch())
            long rsStarted = (rs.started ?: 0L) as long
            if (rs.status != 'idle' && rsStarted >= ((ls.started ?: 0L) as long)) { launchStatus = null; return rs }
            return ls
        }
        return rs
    }
    List<String> logTail(int lines = 200) {
        try { List l = agent.upstatus().log as List; return l ? l.takeRight(Math.min(lines, l.size())) : [] }
        catch (Exception e) { return [] }
    }

    Map launch(String fileName, Map opts) {
        Map s = settings()
        Map staged = (agent.staged().find { it.fileName == fileName }) as Map
        if (!staged) return [error: "package not on the appliance: ${fileName} — download it first"]
        if (!staged.verified) return [error: 'package has not passed SHA-512 verification on the appliance']
        if (state().status == 'running' || launching.get()) return [error: 'an upgrade is already running']
        Long backupId = opts.backupId ? (opts.backupId as Long) : null
        if (backupId && !s.apiToken) return [error: 'API token (plugin settings) is required to run a Morpheus backup first']

        String pkgPath = staged.path as String
        String toVersion = SwcClient.versionOf(fileName)
        String fromVersion = currentVersion()

        launching.set(true)
        Thread t = new Thread({ runLaunch(s, fileName, pkgPath, fromVersion, toVersion, backupId) } as Runnable, 'hvm-updater-launch')
        t.daemon = true
        t.start()
        return [launched: true, fromVersion: fromVersion, toVersion: toVersion, backupId: backupId]
    }

    private void runLaunch(Map s, String fileName, String pkgPath, String fromVersion, String toVersion, Long backupId) {
        long t0 = System.currentTimeMillis()
        Map base = [status: 'running', step: 'morpheus-backup', stepIndex: 0, steps: STEPS, packageName: fileName,
                    fromVersion: fromVersion ?: '', toVersion: toVersion ?: '', started: t0, updated: t0]
        launchStatus = new LinkedHashMap(base)
        try {
            String backupNote = 'not requested'
            if (backupId) {
                launchStatus = new LinkedHashMap(base) + [morpheusBackup: "running backup ${backupId}…".toString(), updated: System.currentTimeMillis()]
                Map r = runBackup(s, backupId)
                if (r.error) {
                    log.error("hvm-updater: backup failed: ${r.error}")
                    launchStatus = new LinkedHashMap(base) + [status: 'failed', error: "Morpheus backup failed: ${r.error}".toString(), updated: System.currentTimeMillis()]
                    return
                }
                backupNote = r.note as String
            }
            launchStatus = new LinkedHashMap(base) + [morpheusBackup: backupNote, step: 'preflight', stepIndex: 1, launching: true, updated: System.currentTimeMillis()]
            String env = [
                    PACKAGE      : pkgPath,
                    FROM_VERSION : fromVersion ?: '',
                    TO_VERSION   : toVersion ?: '',
                    WORK_DIR     : WORK_DIR,
                    BACKUP_DB    : '1',
                    BACKUP_UI_DIR: Util.asBool(s.backupUiDir) ? '1' : '0',
                    BACKUP_KEEP  : (s.backupKeep ?: '3').toString(),
                    BACKUP_DIR   : (s.backupDir ?: "${WORK_DIR}/backups").toString(),
                    APT_UPDATE   : '0',
                    WAIT_MINUTES : (s.waitMinutes ?: '25').toString(),
                    MORPHEUS_BACKUP_NOTE: backupNote,
                    WEBHOOK_URL  : (s.webhookUrl ?: '').toString(),
                    MIRROR_PATH  : mirrorPath(s),
                    NOTIFY_URL   : (Util.asBool(s.notifyEnabled) ? (s.applianceUrl ?: '') : '').toString(),
                    NOTIFY_TOKEN : (Util.asBool(s.notifyEnabled) ? (s.apiToken ?: '') : '').toString(),
            ].collect { k, v -> "${k}=${shq(v as String)}" }.join('\n') + '\n'

            Map r = agent.launch(pkgPath, env)
            if (r.ok) log.info("hvm-updater upgrade launched: ${fileName} (${fromVersion} -> ${toVersion})")
            else {
                log.error("hvm-updater: launch failed: ${r.error}")
                launchStatus = new LinkedHashMap(base) + [status: 'failed', step: 'preflight', stepIndex: 1, error: "launch failed: ${r.error}".toString(), updated: System.currentTimeMillis()]
            }
        } catch (Exception e) {
            log.error("hvm-updater: launch error: ${e.message}", e)
            launchStatus = new LinkedHashMap(base) + [status: 'failed', error: "launch error: ${e.message}".toString(), updated: System.currentTimeMillis()]
        } finally { launching.set(false) }
    }

    /** Standalone async backup run for the Setup "Run backup now" button. Non-blocking. */
    Map startBackup() {
        Map s = settings()
        Map cfg = null
        try { cfg = (settings().class ? null : null) } catch (Exception ignored) {}
        Long backupId = backupIdFromConfig()
        if (!backupId) return [error: 'no backup selected in Setup']
        if (!s.apiToken) return [error: 'API token (plugin settings) required to run a backup']
        if (backingUp.get()) return [error: 'a backup is already running']
        backingUp.set(true)
        backupStatus = [status: 'running', backupId: backupId, started: System.currentTimeMillis()]
        Thread t = new Thread({
            try {
                Map r = runBackup(s, backupId)
                backupStatus = r.error ? [status: 'failed', error: r.error, backupId: backupId, finished: System.currentTimeMillis()]
                                       : [status: 'succeeded', note: r.note, backupId: backupId, finished: System.currentTimeMillis()]
            } catch (Exception e) {
                backupStatus = [status: 'failed', error: e.message, backupId: backupId]
            } finally { backingUp.set(false) }
        } as Runnable, 'hvm-updater-backup'); t.daemon = true; t.start()
        return [started: true, backupId: backupId]
    }

    Map backupState() { backupStatus }

    private Long backupIdFromConfig() {
        // the backup id lives in the page config, injected via the closure by the plugin
        try { Map c = configProvider ? configProvider() : [:]; String b = c.backupId?.toString(); return (b && b.isLong()) ? (b as Long) : null }
        catch (Exception e) { return null }
    }

    Closure<Map> configProvider

    private Map runBackup(Map s, Long backupId) {
        MorpheusApi api = new MorpheusApi(s.applianceUrl as String, s.apiToken as String)
        Map before = null
        try { before = api.latestResult(backupId) } catch (Exception ignored) {}
        Map exec = api.executeBackup(backupId)
        if (exec.success == false) return [error: exec.msg ?: 'backup execute returned success=false']
        int waitMin = Util.asInt(s.backupWaitMinutes, 90)
        long deadline = System.currentTimeMillis() + waitMin * 60_000L
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(15_000)
            Map cur = null
            try { cur = api.latestResult(backupId) } catch (Exception ignored) { continue }
            boolean isNew = cur && (!before || cur.id != before.id)
            String status = (cur?.status ?: '').toString().toUpperCase()
            if (!isNew) continue
            if (status in ['SUCCEEDED', 'SUCCESS', 'COMPLETE', 'COMPLETED']) return [note: "backup ${backupId} result ${cur.id} ${status}"]
            if (status in ['FAILED', 'FAILURE', 'ERROR', 'CANCELLED']) return [error: "result ${cur.id} ${status}"]
        }
        return [error: "timed out after ${waitMin} min waiting for backup ${backupId}"]
    }

    /** Resolve the mirror status-file path: explicit path wins, else under the mirror dir, else empty. */
    private static String mirrorPath(Map s) {
        String p = (s.mirrorPath ?: '').toString().trim()
        if (p) return p.endsWith('.json') ? p : "${p.replaceAll('/+\$','')}/hvm-updater-status.json".toString()
        return ''
    }

    static String shq(String v) { "'" + (v ?: '').replace("'", "'\\''") + "'" }
}
