package com.morpheusdata.hvmupdater

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j

import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.cert.X509Certificate
import java.time.Duration
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Thin REST client against the appliance's own API (Bearer token from plugin
 * settings). Used for things the plugin-api doesn't expose cleanly: listing
 * servers/backups and executing a backup job.
 */
@Slf4j
class MorpheusApi {

    final String base
    final String token
    private final HttpClient http

    MorpheusApi(String base, String token) {
        this.base = (base ?: 'https://127.0.0.1').replaceAll('/+$', '')
        this.token = token
        SSLContext ctx = SSLContext.getInstance('TLS')
        ctx.init(null, [new X509TrustManager() {
            X509Certificate[] getAcceptedIssuers() { new X509Certificate[0] }
            void checkClientTrusted(X509Certificate[] c, String a) {}
            void checkServerTrusted(X509Certificate[] c, String a) {}
        }] as TrustManager[], new java.security.SecureRandom())
        http = HttpClient.newBuilder().sslContext(ctx).connectTimeout(Duration.ofSeconds(15)).build()
    }

    boolean configured() { token && base }

    Map get(String path) { call('GET', path, null) }
    Map post(String path, Map body) { call('POST', path, body ?: [:]) }

    private Map call(String method, String path, Map body) {
        if (!token) throw new IllegalStateException('Morpheus API token not configured (plugin settings)')
        def rb = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(60))
                .header('Authorization', "Bearer ${token}").header('Accept', 'application/json')
        if (method == 'POST') rb.header('Content-Type', 'application/json').POST(HttpRequest.BodyPublishers.ofString(JsonOutput.toJson(body)))
        else if (method == 'PUT') rb.header('Content-Type', 'application/json').PUT(HttpRequest.BodyPublishers.ofString(JsonOutput.toJson(body)))
        else if (method == 'DELETE') rb.DELETE()
        else rb.GET()
        HttpResponse<String> resp = http.send(rb.build(), HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() >= 400) throw new RuntimeException("${method} ${path} -> HTTP ${resp.statusCode()}: ${resp.body()?.take(300)}")
        return resp.body() ? (new JsonSlurper().parseText(resp.body()) as Map) : [:]
    }

    /** Managed VMs (hypervisor hosts filtered out where the type says so). */
    List<Map> servers() {
        List<Map> out = []
        int offset = 0
        while (true) {
            Map r = get("/api/servers?max=200&offset=${offset}&sort=name")
            List<Map> rows = (r.servers ?: []) as List<Map>
            rows.each { Map s ->
                Map t = (s.computeServerType ?: [:]) as Map
                if (t.vmHypervisor == true || t.managed == false) return
                out << [id: s.id, name: s.name, ip: s.internalIp ?: s.externalIp, type: t.name ?: t.code,
                        instanceId: (s.instance as Map)?.id, os: (s.serverOs as Map)?.name]
            }
            offset += rows.size()
            if (!rows || offset >= ((r.meta as Map)?.total ?: 0)) break
        }
        return out
    }

    /** All backups, tagged with whether they belong to the given server (matching is lenient across API versions). */
    List<Map> backups(Long serverId, Long instanceId) {
        Map r = get('/api/backups?max=500&sort=name')
        ((r.backups ?: []) as List<Map>).collect { Map b ->
            Long sid = ((b.server as Map)?.id ?: b.computeServerId ?: b.serverId) as Long
            Long iid = ((b.instance as Map)?.id ?: b.instanceId) as Long
            [id: b.id, name: b.name, type: (b.backupType as Map)?.name ?: b.backupType, job: (b.backupJob as Map)?.name,
             enabled: b.enabled, serverId: sid, instanceId: iid,
             lastStatus: (b.lastResult as Map)?.status ?: b.lastStatus,
             mine: (serverId && sid == serverId) || (instanceId && iid == instanceId)]
        }.sort { a, b -> (b.mine ? 1 : 0) <=> (a.mine ? 1 : 0) ?: (a.name as String) <=> (b.name as String) }
    }

    /** Kick off a backup; returns whatever the API says (usually {success:true}). */
    Map executeBackup(Long backupId) { post("/api/backups/${backupId}/execute", [:]) }

    /** Storage targets that might be writable from the appliance: storage buckets (local/NFS/CIFS) + datastores with a path. */
    List<Map> storageOptions() {
        List<Map> out = []
        try {
            Map r = get('/api/storage-buckets?max=200')
            ((r.storageBuckets ?: []) as List<Map>).each { Map b ->
                String type = (b.storageServer as Map)?.type?.code ?: b.providerType ?: b.bucketName
                String path = b.basePath ?: b.exportPath ?: (b.config as Map)?.exportFolder
                out << [kind: 'bucket', id: b.id, name: b.name, type: type, path: path,
                        label: "${b.name} (bucket${type ? ', ' + type : ''}${path ? ': ' + path : ''})".toString()]
            }
        } catch (Exception ignored) {}
        try {
            Map r = get('/api/datastores?max=200')
            ((r.datastores ?: []) as List<Map>).each { Map d ->
                String path = d.exportPath ?: (d.config as Map)?.exportFolder ?: d.volumePath
                out << [kind: 'datastore', id: d.id, name: d.name, type: d.storageType ?: d.type, path: path,
                        label: "${d.name} (datastore${path ? ': ' + path : ' — no appliance path'})".toString()]
            }
        } catch (Exception ignored) {}
        return out
    }

    // ── tasks ─────────────────────────────────────────────────────────────────

    /** Find a task by name; returns its id or null. */
    Long findTask(String name) {
        Map r = get("/api/tasks?name=${URLEncoder.encode(name, 'UTF-8')}&max=5")
        Map t = ((r.tasks ?: []) as List<Map>).find { it.name == name } as Map
        return t ? (t.id as Long) : null
    }

    /** Create (or replace) the Local Shell Script agent task. Returns its id.
     *  Verified payload shape from /api/tasks: file.content holds the script; PUT-updating an
     *  existing task can corrupt the structure, so we delete + recreate on change. */
    Long upsertLocalTask(String name, String scriptBody) {
        Map payload = [task: [
            name        : name,
            taskType    : [code: 'localScript'],
            executeTarget: 'local',
            resultType  : 'value',
            allowCustomConfig: true,
            file        : [sourceType: 'local', content: scriptBody]
        ]]
        Long existing = findTask(name)
        if (existing) {
            try { call('DELETE', "/api/tasks/${existing}", null) } catch (Exception ignored) {}
        }
        Map r = post('/api/tasks', payload)
        return ((r.task ?: [:]) as Map).id as Long
    }

    Map put(String path, Map body) { call('PUT', path, body ?: [:]) }

    /**
     * Execute a task locally with the given env passed as customOptions.
     * Morpheus exposes customOptions to a bash task as $morpheus_customOptions_<key>,
     * but a localScript can also read plain env we set via config. We pass them as
     * customOptions and the script reads them through a small prelude the plugin prepends.
     * Returns [success, output, error].
     */
    Map executeTask(Long taskId, Map envVars) {
        // config.customOptions become available to the task; we also inline them as exports
        Map body = [config: [customOptions: envVars]]
        Map r = post("/api/tasks/${taskId}/execute", body)
        return r
    }

    /**
     * Latest result for a backup. Tries /api/backup-results first, then the backup's lastResult.
     * Returns [status: 'SUCCEEDED'|'FAILED'|'START'|'IN_PROGRESS'|..., startDate:, id:] or null.
     */
    Map latestResult(Long backupId) {
        // Read the backup object's lastResult first — present on all builds, no missing endpoint.
        try {
            Map b = (get("/api/backups/${backupId}").backup ?: [:]) as Map
            Map lr = (b.lastResult ?: [:]) as Map
            if (lr && lr.status) return [id: lr.id, status: lr.status, startDate: lr.startDate ?: lr.dateCreated]
        } catch (Exception e) { log.debug("backups/${backupId} lookup failed: ${e.message}") }
        // Fallback to a results list endpoint if this build has one (older/newer variants).
        for (String path : ["/api/backups/${backupId}/history?max=1&sort=dateCreated&direction=desc",
                            "/api/backup-results?backupId=${backupId}&max=1&sort=dateCreated&direction=desc"]) {
            try {
                Map r = get(path as String)
                List<Map> rows = (r.results ?: r.backupResults ?: r.history ?: []) as List<Map>
                if (rows) return [id: rows[0].id, status: rows[0].status, startDate: rows[0].startDate ?: rows[0].dateCreated]
            } catch (Exception ignored) {}
        }
        return null
    }

    // ── fleet (hosts + VMs) ───────────────────────────────────────────────────

    /** Raw server list, all pages. */
    List<Map> rawServers(int max = 200) {
        List<Map> out = []
        int offset = 0
        while (true) {
            Map r = get("/api/servers?max=${max}&offset=${offset}&sort=name")
            List<Map> rows = (r.servers ?: []) as List<Map>
            out.addAll(rows)
            offset += rows.size()
            if (!rows || offset >= (((r.meta as Map)?.total ?: 0) as int)) break
        }
        return out
    }

    /** Normalised fleet view: hypervisor hosts and agent-bearing VMs, with what we need to decide upgrades. */
    /** serverId -> [id, name] of the cluster it belongs to, from /api/clusters/{id} (server.cluster isn't populated for HVM hosts on 9.0.x). */
    Map<Long, Map> clusterMembership() {
        Map<Long, Map> out = [:]
        try {
            List<Map> cls = (get('/api/clusters?max=100').clusters ?: []) as List<Map>
            cls.each { Map c ->
                List<Map> servers = (c.servers ?: []) as List<Map>
                if (!servers) { try { servers = ((get("/api/clusters/${c.id}").cluster ?: [:]) as Map).servers as List<Map> ?: [] } catch (Exception ignored) {} }
                servers.each { Map sv -> Long id = (sv instanceof Map ? sv.id : sv) as Long; if (id) out[id] = [id: c.id, name: c.name, type: (c.type as Map)?.code ?: (c.type as Map)?.name, layout: (c.layout as Map)?.name] }
            }
        } catch (Exception e) { log.debug("cluster membership lookup failed: ${e.message}") }
        return out
    }

    Map fleet() {
        List<Map> hosts = [], vms = []
        Map<Long, Map> membership = clusterMembership()
        rawServers().each { Map s ->
            Map t = (s.computeServerType ?: [:]) as Map
            String tcode = (t.code ?: '').toString().toLowerCase(), tname = (t.name ?: '').toString().toLowerCase()
            boolean isHost = t.vmHypervisor == true || tcode.contains('hypervisor') || tname.contains('hypervisor') || tcode.contains('hvm') || tcode.contains('mvmhost') || tcode.contains('kvmhost')
            // only HVM/KVM hosts carry a Morpheus agent to upgrade; ESXi/Hyper-V hypervisor records are shown but skipped
            boolean hvmHost = isHost && (tcode.contains('hvm') || tcode.contains('mvm') || tcode.contains('kvm') || tname.contains('hvm') || tname.contains('kvm') || membership[s.id as Long] != null)
            boolean managed = t.managed != false && s.managed != false
            Map parent = (s.parentServer ?: [:]) as Map
            Map cluster = (membership[s.id as Long] ?: s.cluster ?: s.resourcePool ?: [:]) as Map
            Map stats = (s.stats ?: [:]) as Map
            Map row = [id: s.id, name: s.name, ip: s.internalIp ?: s.externalIp, status: s.status, powerState: s.powerState,
                       agentInstalled: s.agentInstalled, agentVersion: s.agentVersion, type: t.name ?: t.code, typeCode: t.code,
                       os: (s.serverOs as Map)?.name, clusterId: cluster.id, clusterName: cluster.name,
                       parentId: parent.id, parentName: parent.name, instanceId: (s.instance as Map)?.id,
                       maxMemory: stats.maxMemory ?: s.maxMemory, usedMemory: stats.usedMemory, maxCores: s.maxCores ?: stats.maxCores,
                       zone: (s.zone as Map)?.name, lastAgentUpdate: s.lastAgentUpdate,
                       sshUsername: s.sshUsername, hasCredential: (s.credential ?: (s.server as Map)?.credential) != null,
                       platform: platformOf(s),
                       hvm: hvmHost]
            // route: how Morpheus will reach the guest for the upgrade — live agent, else SSH with saved creds, else nothing
            long lau = parseDate(s.lastAgentUpdate)
            boolean agentOnline = s.agentInstalled == true && lau > 0 && (System.currentTimeMillis() - lau) < 10 * 60000L
            boolean creds = (s.sshUsername ?: '').toString().trim() || row.hasCredential
            row.agentOnline = agentOnline
            row.route = agentOnline ? 'agent' : (creds ? 'ssh' : 'none')
            row.eligible = agentOnline || (creds && s.powerState == 'on')
            if (isHost) hosts << row
            else if (managed && (s.agentInstalled == true || s.agentVersion)) vms << row
        }
        // VMs per host (for maintenance/capacity decisions)
        hosts.each { Map h -> h.vms = vms.findAll { it.parentId == h.id }.collect { [id: it.id, name: it.name, powerState: it.powerState, usedMemory: it.usedMemory, maxMemory: it.maxMemory] } }
        // agent version lines differ per platform (linux 3.x vs windows 2.x); compare within the family only
        Map<String, String> latestBy = [:]
        (hosts + vms).each { Map r -> String v = r.agentVersion as String; String pf = (r.platform ?: 'linux') as String
            if (v && (!latestBy[pf] || cmpVer(v, latestBy[pf]) > 0)) latestBy[pf] = v }
        return [hosts: hosts, vms: vms, latestAgent: latestBy['linux'], latestAgentByPlatform: latestBy]
    }

    /** Agent version lines only differ windows vs everything else; distro names (ubuntu, centos…) fold into linux. */
    static String platformOf(Map s) {
        String p = (((s.serverOs as Map)?.platform ?: s.platform ?: (s.serverOs as Map)?.name ?: (s.serverOs as Map)?.code ?: '') as String).toLowerCase()
        return p.contains('windows') ? 'windows' : 'linux'
    }

    static int cmpVer(String a, String b) {
        List<Integer> x = (a ?: '').split('-')[0].tokenize('.').collect { it.isInteger() ? it as int : 0 }
        List<Integer> y = (b ?: '').split('-')[0].tokenize('.').collect { it.isInteger() ? it as int : 0 }
        for (int i = 0; i < Math.max(x.size(), y.size()); i++) { int c = (i < x.size() ? x[i] : 0) <=> (i < y.size() ? y[i] : 0); if (c) return c }
        return 0
    }

    Map server(Long id) { (get("/api/servers/${id}").server ?: [:]) as Map }

    /** Host Actions → Upgrade Agent. 202 on accept. */
    Map upgradeAgent(Long serverId) { put("/api/servers/${serverId}/upgrade", [:]) }

    Map enterMaintenance(Long serverId) { put("/api/servers/${serverId}/maintenance", [:]) }
    Map leaveMaintenance(Long serverId) { put("/api/servers/${serverId}/leave-maintenance", [:]) }

    /** Recent processes for a server, newest first. Filters client-side too because the ref filters vary across builds. */
    List<Map> processes(Long serverId, long sinceMs = 0) {
        List<Map> rows = []
        for (String path : ["/api/processes?refType=computeServer&refId=${serverId}&max=25&sort=startDate&direction=desc",
                            "/api/processes?serverId=${serverId}&max=25&sort=startDate&direction=desc",
                            "/api/processes?max=100&sort=startDate&direction=desc"]) {
            try { rows = (get(path as String).processes ?: []) as List<Map>; if (rows) break } catch (Exception ignored) {}
        }
        return rows.findAll { Map p ->
            Long ref = (p.refId ?: (p.server as Map)?.id ?: p.serverId) as Long
            boolean mine = ref == serverId || ((p.displayName ?: p.description ?: '') as String).contains("(${serverId})")
            long st = parseDate(p.startDate ?: p.dateCreated)
            mine && (sinceMs == 0 || st == 0 || st >= sinceMs - 60000)
        }.collect { Map p -> [id: p.id, type: (p.processType as Map)?.code ?: p.processType, status: p.status, name: p.displayName ?: p.description,
                              startDate: p.startDate, endDate: p.endDate, message: p.message ?: p.error ?: p.output?.toString()?.take(300),
                              events: ((p.events ?: []) as List<Map>).collect { [name: it.displayName ?: it.description, status: it.status, message: it.message ?: it.error] }] }
    }

    static long parseDate(Object d) {
        if (!d) return 0L
        try { return java.time.Instant.parse(d.toString()).toEpochMilli() } catch (Exception ignored) {}
        try { return java.time.OffsetDateTime.parse(d.toString()).toInstant().toEpochMilli() } catch (Exception ignored) { return 0L }
    }
}
