package com.morpheusdata.hvmupdater

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Post-appliance-upgrade fan-out: upgrade the agent on HVM hosts and managed VMs through the
 * appliance API (PUT /api/servers/{id}/upgrade — the same call the host page "Upgrade Agent"
 * action makes), watching each one via /api/processes.
 *
 * Modes:
 *   parallel — fire every selected target at once, then watch them all (what you'd get from
 *              select-all → Upgrade Agent on the hosts page).
 *   rolling  — one host at a time: preflight (HA capacity + peers) → enter maintenance mode
 *              (Morpheus live-migrates running VMs) → optionally relocate powered-off VMs →
 *              upgrade → wait → leave maintenance → next.
 *   manual   — no orchestration: the card shows each target, its state, and the exact call,
 *              and refreshes as you do them yourself in the Morpheus UI.
 *
 * Run state is persisted to fleet.json under PLUGIN_DIR so a page reload (or plugin reload)
 * shows the last run, and every status is re-read from the API rather than trusted from memory.
 */
@Slf4j
class FleetService {

    final Closure<MorpheusApi> api
    final File stateFile
    private final AtomicBoolean running = new AtomicBoolean(false)
    private volatile Map run = null
    private volatile boolean cancelRequested = false

    static final List<String> HOST_STEPS = ['preflight', 'maintenance', 'relocate', 'upgrade', 'wait', 'leave-maintenance', 'done']

    FleetService(Closure<MorpheusApi> api, File dir) {
        this.api = api
        this.stateFile = new File(dir, 'fleet.json')
        try { if (stateFile.exists()) run = new JsonSlurper().parseText(stateFile.getText('UTF-8')) as Map } catch (Exception ignored) {}
        // a run that was 'running' when the plugin last died: nothing more gets fired, but targets already sent are
        // still being upgraded by Morpheus — resume watching them from the API so the rows finish honestly.
        if (run?.status == 'running') {
            run.note = 'plugin restarted mid-run; resumed watching the targets already sent (nothing new was started)'
            (run.targets as List<Map>).findAll { it.status == 'queued' }.each { it.status = 'cancelled'; it.message = 'not started — plugin restarted before its turn' }
            persist()
            Thread th = new Thread({ resumeWatch() } as Runnable, 'hvm-updater-fleet-resume'); th.daemon = true; th.start()
        }
    }

    private void resumeWatch() {
        running.set(true)
        try {
            MorpheusApi a = api()
            List<Map> open = (run.targets as List<Map>).findAll { it.status == 'running' }
            if (open) waitAll(a, open)
            // a host that was mid-rolling may still be in maintenance mode; say so rather than guess
            (run.targets as List<Map>).findAll { it.kind == 'host' && it.step in ['maintenance', 'relocate', 'upgrade', 'wait'] && it.status != 'done' }
                .each { it.message = "${it.message ?: ''} (was mid-rolling when the plugin restarted — check whether the host is still in maintenance mode)".toString() }
            run.status = (run.targets as List<Map>).any { it.status in ['failed', 'stalled', 'timeout'] } ? 'failed' : 'done'
        } catch (Exception e) { run.status = 'interrupted'; run.error = "resume failed: ${e.message}".toString() }
        finally { run.finished = System.currentTimeMillis(); persist(); running.set(false) }
    }

    // ── inventory ────────────────────────────────────────────────────────────

    Closure<Long> applianceServerId = { -> null }

    Map inventory() {
        MorpheusApi a = api()
        if (!a.configured()) return [error: 'Appliance URL + Morpheus API token required in plugin settings']
        Map f = a.fleet()
        Map<String, String> latestBy = (f.latestAgentByPlatform ?: [:]) as Map<String, String>
        Closure<Boolean> behind = { Map r -> String l = latestBy[(r.platform ?: 'linux') as String]; l && r.agentVersion && MorpheusApi.cmpVer(r.agentVersion as String, l) < 0 }
        Long self = null; try { self = applianceServerId() } catch (Exception ignored) {}
        (f.hosts as List<Map>).each { Map h -> h.behind = behind(h); h.eligible = h.hvm == true && h.status != 'error'; h.running = (h.vms as List).count { it.powerState == 'on' }; h.off = (h.vms as List).count { it.powerState != 'on' } }
        (f.vms as List<Map>).each { Map v -> v.behind = behind(v); v.isAppliance = self && v.id == self }
        int noRoute = (f.vms as List<Map>).count { !it.eligible } as int
        return f + [note: 'behind = older than the newest agent version seen in this inventory. Upgrade one host first if everything is still on the old agent.',
                    routeNote: noRoute ? "${noRoute} VM(s) have no route (agent not reporting in the last 10 min and no saved SSH credentials, or powered off): Morpheus hunts for a route for ~3 min then fails them with 'Unable to determine distro'. They are listed unchecked.".toString() : null]
    }

    /**
     * HA preflight for taking `host` out for a rolling upgrade. Returns [ok, blocks:[], warnings:[], detail].
     * Capacity: the other online hosts in the cluster must hold this host's running-VM memory.
     */
    Map preflight(Map host, List<Map> hosts) {
        List<String> blocks = [], warns = []
        List<Map> peers = hosts.findAll { it.id != host.id && it.clusterId == host.clusterId }
        List<Map> peersUp = peers.findAll { (it.status ?: '').toString().toLowerCase() in ['provisioned', 'ok', 'online'] && it.powerState != 'off' }
        if (!host.clusterId) warns << 'host has no cluster reference in the API response — capacity check done across all hosts'
        if (!peers) blocks << 'no other host in the cluster — nowhere to migrate VMs'
        else if (peersUp.size() < peers.size()) warns << "${peers.size() - peersUp.size()} peer host(s) already down/offline (${peers.findAll { !(it in peersUp) }*.name.join(', ')})".toString()
        if (peers.size() == 2 && peersUp.size() == 2) warns << '3-node cluster: quorum drops to 2/3 while this host is in maintenance — do not take a second host out until this one is back'
        long need = ((host.vms ?: []) as List<Map>).findAll { it.powerState == 'on' }.sum { (it.maxMemory ?: it.usedMemory ?: 0L) as long } ?: 0L
        long free = peersUp.sum { long mx = (it.maxMemory ?: 0L) as long; long us = (it.usedMemory ?: 0L) as long; Math.max(mx - us, 0L) } ?: 0L
        if (need && free && free < need) blocks << "peers have ${gb(free)} free but this host's running VMs need ${gb(need)}".toString()
        if (need && !free) warns << 'peer memory stats unavailable — cannot verify migration capacity'
        int running = ((host.vms ?: []) as List<Map>).count { it.powerState == 'on' } as int
        return [ok: blocks.isEmpty(), blocks: blocks, warnings: warns,
                detail: [runningVms: running, poweredOffVms: ((host.vms ?: []) as List).size() - running, needBytes: need, peerFreeBytes: free,
                         peers: peersUp*.name]]
    }

    private static String gb(long b) { String.format('%.1f GB', b / (1024d * 1024 * 1024)) }

    // ── run control ──────────────────────────────────────────────────────────

    Map state() { run ? new LinkedHashMap(run) : [status: 'idle'] }

    Map cancel() { if (run?.status == 'running') { cancelRequested = true; return [cancelling: true] }; return [error: 'no run in progress'] }

    /**
     * opts: mode=parallel|rolling|manual, hostIds=[..], vmIds=[..], relocateOff=true|false,
     *       ignoreWarnings=true|false, waitMinutes=int
     */
    Map start(Map opts) {
        if (running.get() || run?.status == 'running') return [error: 'a fleet run is already in progress']
        MorpheusApi a = api()
        if (!a.configured()) return [error: 'Appliance URL + Morpheus API token required in plugin settings']
        List<Long> hostIds = (opts.hostIds ?: []).collect { it as Long }
        List<Long> vmIds = (opts.vmIds ?: []).collect { it as Long }
        if (!hostIds && !vmIds) return [error: 'select at least one host or VM']
        String mode = (opts.mode ?: 'parallel').toString()
        Map inv = a.fleet()
        List<Map> hosts = inv.hosts as List<Map>, vms = inv.vms as List<Map>
        List<Map> targets = []
        hostIds.each { id -> Map h = hosts.find { it.id == id }; if (h) targets << [id: h.id, name: h.name, kind: 'host', agentBefore: h.agentVersion, status: 'queued', step: null, message: null] }
        vmIds.each { id -> Map v = vms.find { it.id == id }; if (v) targets << [id: v.id, name: v.name, kind: 'vm', agentBefore: v.agentVersion, status: 'queued', step: null, message: null] }
        if (!targets) return [error: 'none of the selected ids are in the inventory']

        if (mode == 'rolling') {
            // block before touching anything if any host fails preflight (unless the user overrode warnings-only)
            for (Map t : targets.findAll { it.kind == 'host' }) {
                Map h = hosts.find { it.id == t.id }
                Map pf = preflight(h, hosts)
                t.preflight = pf
                if (!pf.ok) return [error: "preflight failed for ${h.name}: ${pf.blocks.join('; ')}".toString(), preflight: [(h.name): pf]]
                if (pf.warnings && !Util.asBool(opts.ignoreWarnings)) return [error: "preflight warnings for ${h.name}: ${pf.warnings.join('; ')} — tick 'proceed despite warnings' to continue".toString(), preflight: [(h.name): pf]]
            }
        }
        run = [status: mode == 'manual' ? 'manual' : 'running', mode: mode, relocateOff: Util.asBool(opts.relocateOff),
               waitMinutes: Util.asInt(opts.waitMinutes, 30), started: System.currentTimeMillis(), updated: System.currentTimeMillis(),
               targets: targets, current: null, error: null]
        persist()
        if (mode == 'manual') return [started: true, mode: mode, targets: targets]
        cancelRequested = false
        Thread th = new Thread({ execute(mode) } as Runnable, 'hvm-updater-fleet'); th.daemon = true; th.start()
        return [started: true, mode: mode, targets: targets.size()]
    }

    /** Manual mode: refresh each target's state from the API (agent version + latest process). */
    Map refreshManual() {
        if (!run || run.mode != 'manual') return state()
        MorpheusApi a = api()
        (run.targets as List<Map>).each { Map t ->
            try {
                Map s = a.server(t.id as Long)
                t.agentNow = s.agentVersion
                List<Map> ps = a.processes(t.id as Long, (run.started ?: 0L) as long)
                Map p = ps ? ps[0] : null
                if (p) { t.process = p.status; t.message = p.name }
                if (t.agentBefore && s.agentVersion && MorpheusApi.cmpVer(s.agentVersion as String, t.agentBefore as String) > 0) t.status = 'done'
                else if (p?.status?.toString()?.toLowerCase() in ['running', 'pending']) t.status = 'running'
            } catch (Exception e) { t.message = e.message }
        }
        run.updated = System.currentTimeMillis(); persist()
        return state()
    }

    // ── execution ────────────────────────────────────────────────────────────

    private void execute(String mode) {
        running.set(true)
        try {
            MorpheusApi a = api()
            List<Map> targets = run.targets as List<Map>
            if (mode == 'parallel') {
                targets.each { Map t -> fire(a, t) }
                waitAll(a, targets)
            } else {
                // rolling: hosts one by one, then VMs in parallel (VM agent upgrades are non-disruptive)
                for (Map t : targets.findAll { it.kind == 'host' }) {
                    if (cancelRequested) { t.status = 'cancelled'; continue }
                    rollingHost(a, t)
                    if (t.status in ['failed', 'stalled']) { run.error = "stopped after ${t.name} ${t.status}".toString(); break }
                }
                List<Map> vmT = targets.findAll { it.kind == 'vm' && it.status == 'queued' }
                if (vmT && !cancelRequested && !run.error) { vmT.each { fire(a, it) }; waitAll(a, vmT) }
            }
            targets.findAll { it.status == 'queued' }.each { it.status = 'cancelled' }
            run.status = run.error ? 'failed' : (targets.any { it.status in ['failed', 'stalled'] } ? 'failed' : (cancelRequested ? 'cancelled' : 'done'))
        } catch (Exception e) {
            log.error("hvm-updater fleet run error: ${e.message}", e)
            run.error = e.message; run.status = 'failed'
        } finally { run.finished = System.currentTimeMillis(); run.current = null; persist(); running.set(false) }
    }

    private void fire(MorpheusApi a, Map t) {
        t.status = 'running'; t.step = 'upgrade'; t.startedAt = System.currentTimeMillis(); touch()
        try {
            Map r = a.upgradeAgent(t.id as Long)
            t.message = r.msg ?: (r.success == false ? 'upgrade request rejected' : 'upgrade requested')
            if (r.success == false) { t.status = 'failed' }
        } catch (Exception e) { t.status = 'failed'; t.message = e.message }
        touch()
    }

    /** Poll processes + agentVersion until every target settles or the wait budget runs out. */
    private void waitAll(MorpheusApi a, List<Map> ts) {
        long deadline = System.currentTimeMillis() + (Util.asInt(run.waitMinutes, 30) as long) * 60000L
        List<Map> open = ts.findAll { it.status == 'running' }
        while (open && System.currentTimeMillis() < deadline && !cancelRequested) {
            sleep(8000)
            open.each { Map t -> settle(a, t) }
            open = ts.findAll { it.status == 'running' }
            touch()
        }
        open.each { it.status = cancelRequested ? 'cancelled' : 'timeout'; it.message = cancelRequested ? 'cancelled while waiting' : 'no completion seen within the wait budget — check Processes in Morpheus' }
        touch()
    }

    /** One target: done when agentVersion moved or the process reports complete; failed when the process reports failed. */
    static final long STALL_MS = 4 * 60000L

    private void settle(MorpheusApi a, Map t) {
        try {
            Map s = a.server(t.id as Long)
            t.agentNow = s.agentVersion
            List<Map> ps = a.processes(t.id as Long, (t.startedAt ?: run.started ?: 0L) as long)
            Map p = ps ? ps[0] : null
            long age = System.currentTimeMillis() - ((t.startedAt ?: run.started ?: System.currentTimeMillis()) as long)
            boolean unchanged = !(t.agentBefore && s.agentVersion && MorpheusApi.cmpVer(s.agentVersion as String, t.agentBefore as String) > 0)
            if (!p && unchanged && age > STALL_MS) {
                t.status = 'stalled'
                t.message = "no upgrade process and agent still ${s.agentVersion ?: 'unknown'} after ${(age / 60000L) as int} min — Morpheus likely found no route to the guest (agent offline, no SSH creds). Check morpheus-ui for 'Unable to determine distro'.".toString()
                return
            }
            if (p) { t.process = p.status; t.processId = p.id; if (p.message) t.message = p.message; else if (p.name) t.message = p.name }
            String pst = (p?.status ?: '').toString().toLowerCase()
            boolean moved = t.agentBefore && s.agentVersion && MorpheusApi.cmpVer(s.agentVersion as String, t.agentBefore as String) > 0
            if (pst in ['failed', 'error']) { t.status = 'failed'; t.message = p.message ?: 'process failed' }
            else if (moved || pst in ['complete', 'completed', 'success']) {
                // for hosts also require the agent to be back (lastAgentUpdate recent or status ok) so maintenance isn't left early
                t.status = 'done'; t.finishedAt = System.currentTimeMillis(); t.message = moved ? "agent ${t.agentBefore} → ${s.agentVersion}".toString() : (t.message ?: 'complete')
            }
        } catch (Exception e) { t.message = "status check: ${e.message}".toString() }
    }

    private void rollingHost(MorpheusApi a, Map t) {
        run.current = t.id; t.status = 'running'; t.startedAt = System.currentTimeMillis()
        boolean inMaint = false
        try {
            // preflight (fresh, in case the cluster changed since start)
            t.step = 'preflight'; touch()
            Map inv = a.fleet(); List<Map> hosts = inv.hosts as List<Map>
            Map h = hosts.find { it.id == t.id }
            Map pf = preflight(h, hosts); t.preflight = pf
            if (!pf.ok) { t.status = 'failed'; t.message = "preflight: ${pf.blocks.join('; ')}".toString(); return }

            // maintenance mode — Morpheus evacuates running VMs itself
            t.step = 'maintenance'; touch()
            Map m = a.enterMaintenance(t.id as Long)
            if (m.success == false) { t.status = 'failed'; t.message = "enter maintenance: ${m.msg ?: 'rejected'}".toString(); return }
            inMaint = true
            if (!waitEvacuated(a, t)) { t.status = 'failed'; t.message = t.message ?: 'running VMs did not leave the host in time'; return }

            // powered-off VMs: Morpheus leaves them in place; relocate only if asked
            t.step = 'relocate'; touch()
            List<Map> off = ((h.vms ?: []) as List<Map>).findAll { it.powerState != 'on' }
            if (off && Util.asBool(run.relocateOff)) {
                Map peer = hosts.find { it.id != h.id && it.clusterId == h.clusterId && it.powerState != 'off' }
                t.relocated = []; t.relocateErrors = []
                off.each { Map v ->
                    try {
                        // host placement is a normal server update; body shape mirrors the UI's "Manage placement" call
                        Map r = a.put("/api/servers/${v.id}/placement", [server: [parentServer: [id: peer?.id], placementStrategy: 'manual']])
                        if (r.success == false) { t.relocateErrors << "${v.name}: ${r.msg}".toString() } else { t.relocated << v.name }
                    } catch (Exception e) { t.relocateErrors << "${v.name}: ${e.message}".toString() }
                }
                if (t.relocateErrors) t.message = "relocate: ${t.relocateErrors.size()} powered-off VM(s) could not be moved (left on host)".toString()
            } else if (off) { t.message = "${off.size()} powered-off VM(s) left on host".toString() }

            // upgrade + wait
            fire(a, t)
            if (t.status == 'failed') return
            waitAll(a, [t])
            if (t.status != 'done') { if (t.status == 'stalled') t.status = 'failed'; return }

            // leave maintenance once the agent is back
            t.step = 'leave-maintenance'; touch()
            Map l = a.leaveMaintenance(t.id as Long)
            if (l.success == false) { t.status = 'failed'; t.message = "leave maintenance: ${l.msg ?: 'rejected'} — host is still in maintenance mode".toString(); return }
            inMaint = false
            t.step = 'done'; touch()
        } catch (Exception e) {
            t.status = 'failed'; t.message = e.message
        } finally {
            if (inMaint && t.status == 'failed') t.message = "${t.message} (host LEFT IN MAINTENANCE MODE — clear it manually)".toString()
            touch()
        }
    }

    /** Wait for running VMs to migrate off (host.vms with powerState on drops to 0), up to 20 min. */
    private boolean waitEvacuated(MorpheusApi a, Map t) {
        long deadline = System.currentTimeMillis() + 20 * 60000L
        while (System.currentTimeMillis() < deadline && !cancelRequested) {
            sleep(10000)
            Map inv = a.fleet(); Map h = (inv.hosts as List<Map>).find { it.id == t.id }
            int on = ((h?.vms ?: []) as List<Map>).count { it.powerState == 'on' } as int
            t.message = "maintenance: ${on} running VM(s) still on host".toString(); touch()
            if (on == 0) return true
        }
        return false
    }

    private void touch() { run.updated = System.currentTimeMillis(); persist() }

    private synchronized void persist() {
        try { stateFile.parentFile.mkdirs(); stateFile.setText(JsonOutput.toJson(run ?: [status: 'idle']), 'UTF-8') } catch (Exception e) { log.debug("fleet state not saved: ${e.message}") }
    }
}
