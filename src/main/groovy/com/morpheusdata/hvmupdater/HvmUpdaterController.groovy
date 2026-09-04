package com.morpheusdata.hvmupdater

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.Permission
import com.morpheusdata.views.HTMLResponse
import com.morpheusdata.views.JsonResponse
import com.morpheusdata.views.ViewModel
import com.morpheusdata.web.PluginController
import com.morpheusdata.web.Route
import groovy.util.logging.Slf4j

@Slf4j
class HvmUpdaterController implements PluginController {

    HvmUpdaterPlugin plugin
    MorpheusContext morpheus

    HvmUpdaterController(HvmUpdaterPlugin plugin, MorpheusContext morpheus) {
        this.plugin = plugin; this.morpheus = morpheus
    }

    @Override Plugin getPlugin() { plugin }
    void setPlugin(Plugin p) { this.plugin = p as HvmUpdaterPlugin }
    @Override MorpheusContext getMorpheus() { morpheus }
    Boolean isEnabled() { true }
    String getCode() { 'hvmUpdaterController' }
    String getName() { 'HVM Updater Controller' }

    @Override
    List<Route> getRoutes() {
        def p = { String path, String action -> Route.build(path, action, Permission.build('admin-cm', 'full')) }
        [
            p('/hvmUpdater',                    'page'),
            p('/hvmUpdater/api/overview',       'apiOverview'),
            p('/hvmUpdater/api/refresh',        'apiRefresh'),
            p('/hvmUpdater/api/download',       'apiDownload'),
            p('/hvmUpdater/api/downloads',      'apiDownloads'),
            p('/hvmUpdater/api/upgrade/start',  'apiUpgradeStart'),
            p('/hvmUpdater/api/upgrade/state',  'apiUpgradeState'),
            p('/hvmUpdater/api/servers',        'apiServers'),
            p('/hvmUpdater/api/backups',        'apiBackups'),
            p('/hvmUpdater/api/config',         'apiConfig'),
            p('/hvmUpdater/api/backup/run',     'apiBackupRun'),
            p('/hvmUpdater/api/backup/state',   'apiBackupState'),
            p('/hvmUpdater/api/file/delete',    'apiFileDelete'),
            p('/hvmUpdater/api/file/move',      'apiFileMove'),
            p('/hvmUpdater/api/storage',        'apiStorage'),
            p('/hvmUpdater/api/dircheck',       'apiDirCheck'),
            p('/hvmUpdater/api/upload',         'apiUpload'),
            p('/hvmUpdater/api/diag',           'apiDiag'),
            p('/hvmUpdater/api/hpeLogin',       'apiHpeLogin'),
            p('/hvmUpdater/api/hpeSignout',     'apiHpeSignout'),
            p('/hvmUpdater/api/fleet',          'apiFleet'),
            p('/hvmUpdater/api/fleet/preflight','apiFleetPreflight'),
            p('/hvmUpdater/api/fleet/start',    'apiFleetStart'),
            p('/hvmUpdater/api/fleet/state',    'apiFleetState'),
            p('/hvmUpdater/api/fleet/cancel',   'apiFleetCancel'),
            p('/hvmUpdater/api/fleet/probe',    'apiFleetProbe'),
        ]
    }

    // ── page ────────────────────────────────────────────────────────────────

    def page(ViewModel<Map> model) {
        HTMLResponse.success(html(safeNonce(model)))
    }

    // ── JSON API ────────────────────────────────────────────────────────────

    /** Cached catalog + current version + downloads + upgrade state in one call. */
    def apiOverview(ViewModel<Map> model) {
        JsonResponse.of([
            currentVersion: plugin.upgrades.currentVersion(),
            tokenOk       : plugin.catalog.tokenOk,
            catalog       : plugin.catalog.snapshot(),
            downloads     : plugin.agent.status(),
            upgrade       : plugin.upgrades.state(),
            config        : plugin.config(),
            backup        : plugin.upgrades.backupState(),
            apiConfigured : plugin.loadSettings().apiToken ? true : false,
        ])
    }

    def apiRefresh(ViewModel<Map> model) {
        Map r = plugin.catalog.refresh()
        JsonResponse.of(r)
    }

    def apiDownloads(ViewModel<Map> model) { JsonResponse.of(plugin.agent.status()) }

    /** POST fileName=..., product=..., sig=true|false — re-fetches the listing so the signed URL is fresh. */
    def apiDownload(ViewModel<Map> model) {
        String fileName = param(model, 'fileName')
        String productNumber = param(model, 'product')
        boolean sig = param(model, 'sig') in ['true', 'on', '1']
        if (!fileName || !productNumber) return JsonResponse.of([error: 'fileName and product are required'])
        try {
            Map f = plugin.catalog.freshFile(productNumber, fileName)
            if (!f) return JsonResponse.of([error: "file ${fileName} not found in current listing"])
            return JsonResponse.of([job: plugin.agent.startDownload(f, sig)])
        } catch (SwcClient.AuthException e) {
            return JsonResponse.of([error: e.message, auth: false])
        } catch (Exception e) {
            log.error("download start failed: ${e.message}", e)
            return JsonResponse.of([error: e.message])
        }
    }

    def apiUpgradeStart(ViewModel<Map> model) {
        String fileName = param(model, 'fileName')
        if (!fileName) return JsonResponse.of([error: 'fileName required'])
        String backupId = param(model, 'backupId')
        Map opts = [backupId: (backupId && backupId.isLong()) ? (backupId as Long) : null]
        JsonResponse.of(plugin.upgrades.launch(fileName, opts))
    }

    def apiUpgradeState(ViewModel<Map> model) {
        int lines = (param(model, 'lines') ?: '200').isInteger() ? (param(model, 'lines') as int) : 200
        JsonResponse.of([state: plugin.upgrades.state(), log: plugin.upgrades.logTail(lines),
                         currentVersion: plugin.upgrades.currentVersion()])
    }

    def apiServers(ViewModel<Map> model) {
        try { return JsonResponse.of([servers: plugin.api().servers()]) }
        catch (Exception e) { return JsonResponse.of([error: e.message]) }
    }

    def apiBackups(ViewModel<Map> model) {
        String sid = param(model, 'serverId'), iid = param(model, 'instanceId')
        try {
            return JsonResponse.of([backups: plugin.api().backups(sid?.isLong() ? sid as Long : null, iid?.isLong() ? iid as Long : null)])
        } catch (Exception e) { return JsonResponse.of([error: e.message]) }
    }

    /** Settings diagnostics: key names, types, lengths — never values. */
    def apiDiag(ViewModel<Map> model) {
        String raw = null
        try { raw = morpheus.getSettings(plugin).blockingGet() } catch (Exception e) { raw = "ERROR: ${e.message}" }
        Map parsed = plugin.loadSettings()
        JsonResponse.of([
            rawLength   : raw?.length(),
            rawPreview  : raw?.replaceAll(/"(hpeToken|sshSecret|apiToken|sshCredential|hpeCredential)"\s*:\s*"[^"]*"/, '"\$1":"<redacted>"')?.take(1500),
            keys        : parsed.collectEntries { k, v -> [(k): [type: v?.getClass()?.simpleName, length: v?.toString()?.length()]] },
            tokenLooksValid: new SwcClient(parsed.hpeToken as String).hasToken(),
            tokenRawLength : parsed.hpeToken?.toString()?.length(),
            tokenUsedLength: SwcClient.normalize(parsed.hpeToken as String)?.length(),
            credential  : plugin.creds.describe(),
            token       : plugin.tokens.describe(),
            hpeTrace    : plugin.tokens.lastTrace,
            cypherProbe : param(model, 'cypher') ? plugin.creds.cypherProbe(param(model, 'cypher')) : 'add ?cypher=secret/<key> to probe',
            login       : param(model, 'login') ? plugin.tokens.refresh() ? [ok: true, source: plugin.tokens.lastSource] : [ok: false, error: plugin.tokens.lastError] : 'add ?login=1 to test HPE auto-login',
            ssh         : param(model, 'probe') ? plugin.upgrades.probe() : 'add ?probe=1 to test ssh + sudo',
        ])
    }

    /** GET returns saved config; POST with applianceServerId/applianceName/backupId/backupName saves it. */
    def apiConfig(ViewModel<Map> model) {
        if (param(model, 'applianceServerId') != null || param(model, 'backupId') != null) {
            Map c = plugin.config()
            ['applianceServerId', 'applianceName', 'applianceInstanceId', 'backupId', 'backupName'].each { k ->
                String v = param(model, k)
                if (v != null) c[k] = v ?: null
            }
            Map r = plugin.saveConfig(c)
            if (r.error) return JsonResponse.of(r)
        }
        return JsonResponse.of([config: plugin.config()])
    }

    /** Interactive HPE login: username + password in the query of a same-origin GET from the page; not stored. */
    def apiHpeLogin(ViewModel<Map> model) {
        String u = header(model, 'X-HPE-User'), p = header(model, 'X-HPE-Pass')
        if (!u || !p) return JsonResponse.of([error: 'username and password required (sent as headers)'])
        String tok = plugin.tokens.loginWith(u, p)
        if (tok) { plugin.catalog.refresh(); return JsonResponse.of([ok: true, source: plugin.tokens.lastSource]) }
        return JsonResponse.of([ok: false, error: plugin.tokens.lastError, trace: plugin.tokens.lastTrace])
    }

    def apiBackupRun(ViewModel<Map> model) { JsonResponse.of(plugin.upgrades.startBackup()) }
    def apiBackupState(ViewModel<Map> model) { JsonResponse.of(plugin.upgrades.backupState()) }

    def apiFileDelete(ViewModel<Map> model) {
        String f = param(model, 'fileName')
        if (!f) return JsonResponse.of([error: 'fileName required'])
        JsonResponse.of(plugin.agent.deleteFile(f))
    }
    def apiFileMove(ViewModel<Map> model) {
        String f = param(model, 'fileName'), dest = param(model, 'dest')
        if (!f || !dest) return JsonResponse.of([error: 'fileName and dest required'])
        JsonResponse.of(plugin.agent.moveFile(f, dest))
    }
    def apiStorage(ViewModel<Map> model) {
        try { return JsonResponse.of([storage: plugin.api().storageOptions()]) }
        catch (Exception e) { return JsonResponse.of([error: e.message, storage: []]) }
    }

    def apiDirCheck(ViewModel<Map> model) { JsonResponse.of(plugin.agent.checkDir()) }

    /** Manual upload isn't available in task mode; user copies the file to the download dir and refreshes. */
    def apiUpload(ViewModel<Map> model) {
        JsonResponse.of([error: 'Copy the package into the download directory shown under Staged, then click Refresh. (Direct upload is disabled in task mode.)'])
    }

    def apiHpeSignout(ViewModel<Map> model) {
        plugin.tokens.store(null)
        JsonResponse.of([ok: true])
    }

    // ── fleet (post-upgrade hosts + VMs) ───────────────────────────────────

    def apiFleet(ViewModel<Map> model) {
        try { return JsonResponse.of(plugin.fleet.inventory() + [run: plugin.fleet.state()]) }
        catch (Exception e) { return JsonResponse.of([error: e.message]) }
    }

    /** ?hostIds=1,2,3 → preflight for each (rolling mode check without starting anything). */
    def apiFleetPreflight(ViewModel<Map> model) {
        try {
            Map inv = plugin.fleet.inventory(); if (inv.error) return JsonResponse.of(inv)
            List<Map> hosts = inv.hosts as List<Map>
            List<Long> ids = (param(model, 'hostIds') ?: '').split(',').findAll { it.trim().isLong() }.collect { it.trim() as Long }
            Map out = [:]
            ids.each { id -> Map h = hosts.find { it.id == id }; if (h) out[h.name as String] = plugin.fleet.preflight(h, hosts) }
            return JsonResponse.of([preflight: out])
        } catch (Exception e) { return JsonResponse.of([error: e.message]) }
    }

    /** mode=parallel|rolling|manual&hostIds=..&vmIds=..&relocateOff=1&ignoreWarnings=1&waitMinutes=30 */
    def apiFleetStart(ViewModel<Map> model) {
        Map opts = [mode: param(model, 'mode'), relocateOff: param(model, 'relocateOff'), ignoreWarnings: param(model, 'ignoreWarnings'), waitMinutes: param(model, 'waitMinutes'),
                    hostIds: (param(model, 'hostIds') ?: '').split(',').findAll { it.trim().isLong() }.collect { it.trim() as Long },
                    vmIds  : (param(model, 'vmIds') ?: '').split(',').findAll { it.trim().isLong() }.collect { it.trim() as Long }]
        JsonResponse.of(plugin.fleet.start(opts))
    }

    def apiFleetState(ViewModel<Map> model) {
        Map st = plugin.fleet.state()
        if (st.mode == 'manual' && param(model, 'refresh')) st = plugin.fleet.refreshManual()
        JsonResponse.of(st)
    }

    def apiFleetCancel(ViewModel<Map> model) { JsonResponse.of(plugin.fleet.cancel()) }

    /** ?serverId=N → trimmed raw server + recent processes so field names can be checked against this build. */
    def apiFleetProbe(ViewModel<Map> model) {
        String sid = param(model, 'serverId')
        if (!sid?.isLong()) return JsonResponse.of([error: 'serverId required'])
        try {
            Map s = plugin.api().server(sid as Long)
            Map trimmed = s.findAll { k, v -> !(v instanceof List && ((List) v).size() > 20) }
            return JsonResponse.of([serverKeys: s.keySet().sort(), server: trimmed, processes: plugin.api().processes(sid as Long, 0)])
        } catch (Exception e) { return JsonResponse.of([error: e.message]) }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static String header(ViewModel<Map> model, String name) {
        try { return model?.request?.getHeader(name) } catch (Exception e) { return null }
    }

    private static Map parseBody(ViewModel<Map> model) {
        try {
            def req = model?.request
            String ct = req?.getContentType()
            if (ct?.contains('json')) {
                String raw = req.inputStream.text
                return raw ? (new groovy.json.JsonSlurper().parseText(raw) as Map) : [:]
            }
            // form-encoded
            Map out = [:]
            ['u', 'p'].each { k -> def v = req?.getParameter(k); if (v != null) out[k] = v }
            return out
        } catch (Exception e) { return [:] }
    }

    private static String param(ViewModel<Map> model, String key) {
        def v = model?.object?.get(key)
        if (v == null) v = model?.request?.getParameter(key)
        return fixLatin1(v?.toString())
    }

    /** Tomcat decodes query params as ISO-8859-1 unless URIEncoding is set; the page sends UTF-8. Re-decode when it looks like that happened. */
    private static String fixLatin1(String v) {
        if (!v || !v.chars.any { it > 0x7f && it <= 0xff } || v.chars.any { it > 0xff }) return v
        try { String u = new String(v.getBytes('ISO-8859-1'), 'UTF-8'); return u.contains('\uFFFD') ? v : u } catch (Exception ignored) { return v }
    }

    private static String safeNonce(ViewModel model) {
        try { return (model?.request?.getAttribute('js-nonce') ?: '') as String } catch (Exception ignored) { return '' }
    }

    private static String html(String nonce) {
        String n = nonce ? " nonce=\"${nonce}\"" : ''
        return """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>HVM Updater</title>
<style${n}>
:root{--bg:#f6f7f9;--card:#fff;--fg:#1f2328;--muted:#6b7280;--line:#e5e7eb;--accent:#01a982;--warn:#b45309;--bad:#b91c1c;--ok:#15803d}
@media (prefers-color-scheme:dark){:root{--bg:#141618;--card:#1c1f23;--fg:#e6e8eb;--muted:#9aa2ad;--line:#2b3138}}
*{box-sizing:border-box}body{margin:0;font:14px/1.45 -apple-system,Segoe UI,Roboto,sans-serif;background:var(--bg);color:var(--fg)}
.wrap{max-width:1180px;margin:0 auto;padding:20px}h1{font-size:20px;margin:0 0 4px}h2{font-size:15px;margin:0 0 10px}
.card{background:var(--card);border:1px solid var(--line);border-radius:8px;padding:16px;margin:0 0 16px}
.row{display:flex;gap:16px;flex-wrap:wrap}.row>.card{flex:1 1 320px}
.muted{color:var(--muted)}table{width:100%;border-collapse:collapse}th,td{text-align:left;padding:6px 8px;border-bottom:1px solid var(--line);vertical-align:top}
th{font-weight:600;color:var(--muted);font-size:12px;text-transform:uppercase}
button{background:var(--accent);color:#fff;border:0;border-radius:6px;padding:6px 12px;cursor:pointer;font:inherit}button.secondary{background:transparent;color:var(--fg);border:1px solid var(--line)}
button:disabled{opacity:.5;cursor:default}.pill{display:inline-block;padding:1px 8px;border-radius:999px;font-size:12px;border:1px solid var(--line)}
.pill.ok{color:var(--ok);border-color:var(--ok)}.pill.warn{color:var(--warn);border-color:var(--warn)}.pill.bad{color:var(--bad);border-color:var(--bad)}
.bar{height:6px;background:var(--line);border-radius:3px;overflow:hidden}.bar>i{display:block;height:100%;background:var(--accent)}
code{background:rgba(127,127,127,.15);padding:1px 5px;border-radius:4px;font-size:12px}
pre{background:#0b0e12;color:#d7dde5;padding:12px;border-radius:6px;max-height:360px;overflow:auto;font-size:12px}
.steps{display:flex;gap:6px;flex-wrap:wrap;margin:8px 0}.step{padding:4px 10px;border-radius:6px;border:1px solid var(--line);font-size:12px}
.step.done{border-color:var(--ok);color:var(--ok)}.step.cur{border-color:var(--accent);background:var(--accent);color:#fff}.step.fail{border-color:var(--bad);color:var(--bad)}
label{margin-right:14px}input[type=text]{font:inherit;padding:4px 6px;border:1px solid var(--line);border-radius:4px;background:var(--bg);color:var(--fg)}
</style></head><body><div class="wrap">
<h1>HVM Updater</h1>
<div class="muted" id="hdr">Loading…</div>
<div id="hpeAuth" style="margin:6px 0 14px"></div>
<div class="row">
  <div class="card"><h2>Appliance</h2><div id="appliance"></div></div>
  <div class="card"><h2>Catalog <button class="secondary" id="refresh">Refresh from HPE</button></h2><div id="catalogMeta" class="muted"></div></div>
</div>
<div class="card" id="upgradeCard" style="display:none"><h2>Upgrade</h2><div id="upgrade"></div></div>
${FleetPage.HTML}
<div class="card"><h2>Setup — pre-upgrade Morpheus backup</h2>
  <div id="setup" class="muted">Loading…</div></div>
<div class="card"><h2>Available files</h2>
  <div style="margin-bottom:8px"><input type="text" id="filter" placeholder="filter (e.g. debian, el8, FIPS, HVM)"> <label><input type="checkbox" id="sig"> also fetch .sig</label></div>
  <div id="files"></div></div>
<div class="card"><h2>Staged on appliance</h2>
  <div id="dirline" class="muted" style="margin-bottom:8px"></div>
  <div style="margin-bottom:10px"><label class="secondary" style="border:1px solid var(--line);border-radius:6px;padding:6px 12px;cursor:pointer">Upload a package… <input type="file" id="upl" style="display:none"></label> <span id="uplMsg" class="muted"></span></div>
  <div id="staged"></div></div>
<div class="card"><h2>Runner log</h2>
  <div class="muted" style="margin-bottom:8px">Watch live from an SSH shell: <code>sudo journalctl -u hvm-updater-upgrade -f</code> &nbsp;·&nbsp; or <code>sudo tail -f /var/opt/morpheus/hvm-updater/upgrade.log</code> &nbsp;·&nbsp; after reconfigure: <code>sudo morpheus-ctl tail morpheus-ui</code></div>
  <pre id="log">—</pre></div>
</div>
<script${n}>
(function(){
var base='/plugin/hvmUpdater/api', S={};
function q(u,d){if(d){var qs=Object.keys(d).map(function(k){return encodeURIComponent(k)+'='+encodeURIComponent(d[k])}).join('&');if(qs)u+=(u.indexOf('?')<0?'?':'&')+qs}return fetch(u,{credentials:'same-origin',cache:'no-store'}).then(function(r){if(!r.ok)throw new Error('HTTP '+r.status+' from '+u.split('?')[0]);return r.json()}).catch(function(e){return {error:e.message}})}
function esc(s){return String(s==null?'':s).replace(/[&<>"]/g,function(c){return{'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]})}
function fmtB(b){b=+b||0;var u=['B','KB','MB','GB'],i=0;while(b>1024&&i<3){b/=1024;i++}return b.toFixed(i?1:0)+' '+u[i]}
function cmpVer(a,b){a=(a||'').split('-')[0].split('.').map(Number);b=(b||'').split('-')[0].split('.').map(Number);for(var i=0;i<Math.max(a.length,b.length);i++){var x=a[i]||0,y=b[i]||0;if(x!=y)return x-y}return 0}
function render(){
  var cur=S.currentVersion||'unknown';
  var latest=null;(S.catalog.products||[]).forEach(function(p){(p.files||[]).forEach(function(f){if(f.version&&(!latest||cmpVer(f.version,latest)>0))latest=f.version})});
  var newer=latest&&cur!='unknown'&&cmpVer(latest,cur)>0;
  document.getElementById('hdr').textContent=S.tokenOk?'HPE token OK':'HPE token missing or expired — set it in plugin settings';
  document.getElementById('appliance').innerHTML='Running <b>'+esc(cur)+'</b>'+(latest?' · latest in catalog <b>'+esc(latest)+'</b> '+(newer?'<span class="pill warn">update available</span>':'<span class="pill ok">up to date</span>'):'');
  document.getElementById('catalogMeta').textContent=(S.catalog.products||[]).length+' product(s), last refreshed '+(S.catalog.refreshedAt?new Date(S.catalog.refreshedAt).toLocaleString():'never')+(S.catalog.error?' — '+S.catalog.error:'');
  var filt=(document.getElementById('filter').value||'').toLowerCase(),rows='';
  (S.catalog.products||[]).forEach(function(p){(p.files||[]).forEach(function(f){
    if(filt&&f.fileName.toLowerCase().indexOf(filt)<0)return;
    var job=(S.downloads.jobs||[]).filter(function(j){return j.fileName==f.fileName})[0];
    var staged=(S.downloads.files||[]).filter(function(x){return x.fileName==f.fileName&&x.verified})[0];
    var act=staged?'<span class="pill ok">staged</span>':job&&job.status!='failed'&&job.status!='done'?'<div class="bar"><i style="width:'+(job.pct||0)+'%"></i></div><span class="muted">'+esc(job.status)+' '+(job.pct||0)+'% '+fmtB(job.bytes)+'</span>':'<button data-dl="'+esc(f.fileName)+'" data-p="'+esc(p.productNumber)+'">Download</button>'+(job&&job.status=='failed'?' <span class="pill bad" title="'+esc(job.error)+'">failed</span>':'');
    rows+='<tr><td>'+esc(f.fileName)+'<div class="muted">'+esc(p.productName)+'</div></td><td>'+esc(f.version||'')+'</td><td>'+esc(f.size)+'</td><td>'+act+'</td></tr>'})});
  document.getElementById('files').innerHTML=rows?'<table><tr><th>File</th><th>Version</th><th>Size</th><th></th></tr>'+rows+'</table>':'<span class="muted">Nothing listed — refresh the catalog.</span>';
  var st='';(S.downloads.files||[]).forEach(function(x){
    var can=x.verified&&x.fileName.endsWith('.deb')&&!/sdn|supplemental|HVM_Install/i.test(x.fileName);
    st+='<tr><td>'+esc(x.fileName)+'</td><td>'+esc(x.version||'')+'</td><td>'+fmtB(x.bytes)+'</td><td>'+(x.verified?'<span class="pill ok">sha512 ok</span>':'<span class="pill warn">unverified</span>')+'</td><td>'+(can?'<button data-up="'+esc(x.fileName)+'"'+(S.upgrade.status=='running'?' disabled':'')+'>Upgrade appliance…</button> ':'')+'<button class="secondary" data-mv="'+esc(x.fileName)+'">Move…</button> <button class="secondary" data-del="'+esc(x.fileName)+'">Delete</button></td></tr>'});
  document.getElementById('staged').innerHTML=st?'<table><tr><th>File</th><th>Version</th><th>Size</th><th>Checksum</th><th></th></tr>'+st+'</table>':'<span class="muted">No packages staged yet.</span>';
  document.querySelectorAll('[data-del]').forEach(function(b){b.onclick=function(){if(!confirm('Delete '+b.dataset.del+' from the appliance? This removes the package and its checksum/signature files.'))return;b.disabled=true;q(base+'/file/delete',{fileName:b.dataset.del}).then(function(r){if(r.error)alert(r.error);load()})}});
  document.querySelectorAll('[data-mv]').forEach(function(b){b.onclick=function(){moveFile(b.dataset.mv)}});
  renderUpgrade(S.upgrade);renderSetup();renderHpeAuth();
  document.querySelectorAll('[data-dl]').forEach(function(b){b.onclick=function(){b.disabled=true;q(base+'/download',{fileName:b.dataset.dl,product:b.dataset.p,sig:document.getElementById('sig').checked}).then(function(r){if(r.error)alert(r.error);load()})}});
  document.querySelectorAll('[data-up]').forEach(function(b){b.onclick=function(){startUpgrade(b.dataset.up)}});
  renderDir();
}
function renderUpgrade(u){
  var card=document.getElementById('upgradeCard');if(!u||u.status=='idle'){card.style.display='none';return}
  card.style.display='';
  var steps='';(u.steps||[]).forEach(function(s,i){var c=u.status=='failed'&&i==u.stepIndex?'fail':i<u.stepIndex||u.status=='done'?'done':i==u.stepIndex?'cur':'';steps+='<span class="step '+c+'">'+esc(s)+'</span>'});
  var pill=u.status=='running'?'<span class="pill warn">running — '+(u.step=='morpheus-backup'?'waiting for the Morpheus backup to finish':'the UI will go away during stop-ui/reconfigure; this page keeps polling and picks up when it returns')+'</span>':u.status=='done'?'<span class="pill ok">completed</span>':u.status=='failed'?'<span class="pill bad">failed</span>':'<span class="pill">'+esc(u.status)+'</span>';
  document.getElementById('upgrade').innerHTML='<div>'+esc(u.packageName||'')+' · '+esc(u.fromVersion||'?')+' → '+esc(u.toVersion||'?')+' '+pill+'</div><div class="steps">'+steps+'</div>'+(u.error?'<div style="color:var(--bad)">'+esc(u.error)+'</div>':'')+(u.backupPath?'<div class="muted">backup: '+esc(u.backupPath)+'</div>':'')+(u.morpheusBackup?'<div class="muted">morpheus backup: '+esc(u.morpheusBackup)+'</div>':'')+'<div class="muted">started '+(u.started?new Date(u.started).toLocaleString():'')+' · updated '+(u.updated?new Date(u.updated).toLocaleTimeString():'')+'</div>';
}
function sshHost(){return (S.config&&S.config.sshHost)||location.hostname}
function moveFile(fn){
  q(base+'/storage').then(function(r){
    var opts=(r.storage||[]);
    var pick;
    var choices=opts.map(function(o,i){return (i+1)+'. '+o.label}).join('\\n');
    var sel=prompt('Move '+fn+' to:\\n\\n'+(choices?choices+'\\n\\n':'')+'Enter a number from the list, or type a full destination path on the appliance:');
    if(sel===null)return;
    var dest=sel.trim();
    var n=parseInt(dest,10);
    if(!isNaN(n)&&n>=1&&n<=opts.length){var o=opts[n-1];if(!o.path){alert('That target has no appliance-writable path — type a path instead.');return}dest=o.path}
    if(!dest){alert('No destination given');return}
    q(base+'/file/move',{fileName:fn,dest:dest}).then(function(r){if(r.error){alert(r.error)}else{alert('Moved to '+(r.dest||dest))}load()})
  });
}
function startUpgrade(f){
  var c=S.config||{};
  if(!c.backupId){if(!confirm('No Morpheus backup is selected in Setup. Only the runner own DB dump + morpheus.rb copy will be taken.\\n\\nContinue without a Morpheus VM backup?'))return;}
  var lb=S.backup||{};if(c.backupId&&lb.status=='succeeded'){/* recent backup ok */}
  // staged, explicit confirmation
  var steps=['1. Morpheus backup'+(c.backupId?' ("'+(c.backupName||c.backupId)+'") — must succeed':' — skipped'),
             '2. Runner backup (DB dump + morpheus.rb)','3. Stop morpheus-ui','4. dpkg -i '+f,'5. morpheus-ctl reconfigure','6. Wait for UI to return'];
  var msg='About to upgrade this appliance:\\n\\n'+f+'\\n\\n'+steps.join('\\n')+'\\n\\nThe Manager UI will be DOWN 10-15 min during steps 3-6 (hypervisors + running VMs unaffected).\\n\\nAfter you confirm, SSH watch commands appear on the page.\\n\\nProceed?';
  if(!confirm(msg))return;
  q(base+'/upgrade/start',{fileName:f,backupId:c.backupId||''}).then(function(r){if(r.error){alert(r.error);return}
    // pop the watch commands so they can copy before the UI drops
    showWatchPanel();pollLog()})
}
function showWatchPanel(){
  var card=document.getElementById('upgradeCard');if(!card)return;
  var w=document.getElementById('watchPanel');
  if(!w){w=document.createElement('div');w.id='watchPanel';w.style.cssText='margin-top:10px;padding:10px;border:1px solid var(--accent);border-radius:6px';card.appendChild(w);}
  var h=sshHost();var SQ=String.fromCharCode(39);
  function line(label,pre,cmd){var d=document.createElement('div');d.style.marginTop='6px';
    var t=document.createTextNode(label);d.appendChild(t);d.appendChild(document.createElement('br'));
    var code=document.createElement('code');code.textContent=pre+SQ+cmd+SQ;d.appendChild(code);return d;}
  w.innerHTML='';
  var head=document.createElement('b');head.textContent='Watch the upgrade from outside the browser';w.appendChild(head);
  w.appendChild(line('Live runner console (survives the outage):','ssh admin@'+h+' ','sudo journalctl -u hvm-updater-upgrade -f'));
  w.appendChild(line('Or tail the log file:','ssh admin@'+h+' ','sudo tail -f /var/opt/morpheus/hvm-updater/upgrade.log'));
  w.appendChild(line('After it returns, watch the UI service:','ssh admin@'+h+' ','sudo morpheus-ctl tail morpheus-ui'));
  if(S.config&&S.config.mirrorPath){var m=document.createElement('div');m.style.marginTop='6px';m.textContent='Mirrored status file: '+S.config.mirrorPath;w.appendChild(m);}
}
function renderDir(){
  var el=document.getElementById('dirline');if(!el)return;
  q(base+'/dircheck').then(function(r){
    if(r.error){el.innerHTML='<span style="color:var(--bad)">download dir: '+esc(r.error)+'</span>';return}
    if(r.ok){el.textContent='Download directory: '+r.dir+' · '+r.freeGb+' GB free'+(r.usedMb!=null?' · '+(r.usedMb>=1024?(r.usedMb/1024).toFixed(1)+' GB':r.usedMb+' MB')+' used here':'');
      if(r.warning){var wn=document.createElement('div');wn.style.color='var(--bad)';wn.style.marginTop='4px';wn.textContent='⚠ '+r.warning;el.appendChild(wn)}}
  });
  var f=document.getElementById('upl');
  if(f&&!f.__wired){f.__wired=1;f.onchange=function(){
    if(!f.files||!f.files[0])return;var file=f.files[0];var fd=new FormData();fd.append('file',file);
    document.getElementById('uplMsg').textContent='uploading '+file.name+'…';
    fetch(base+'/upload',{method:'POST',credentials:'same-origin',body:fd}).then(function(r){return r.json()}).then(function(r){
      if(r.error){document.getElementById('uplMsg').textContent='upload failed: '+r.error}
      else if(r.verified){document.getElementById('uplMsg').textContent='';load()}
      else{document.getElementById('uplMsg').textContent=(r.warning||'uploaded, unverified');load()}
      f.value='';
    }).catch(function(e){document.getElementById('uplMsg').innerHTML='upload blocked ('+esc(e.message)+'). SFTP the file to the download directory shown above, then click Refresh.';f.value=''});
  }}
}
function renderHpeAuth(){
  var el=document.getElementById('hpeAuth');if(!el)return;
  var ok=S.token&&S.token.hasCached;
  if(ok){el.innerHTML='HPE session active ('+esc(S.token.source||'')+') <button class="secondary" id="hpeOut">Sign out</button>';
    document.getElementById('hpeOut').onclick=function(){q(base+'/hpeSignout').then(function(){load()})};return;}
  el.innerHTML='<b>Sign in to HPE</b> — not stored, kept only as a session token.<div style="margin-top:6px">'+
    '<input type="text" id="hpeU" placeholder="HPE email" autocomplete="username" style="min-width:220px"> '+
    '<input type="password" id="hpeP" placeholder="password" autocomplete="current-password" style="min-width:180px"> '+
    '<button id="hpeIn">Sign in</button> <span id="hpeMsg" class="muted"></span></div>';
  document.getElementById('hpeIn').onclick=function(){
    var u=document.getElementById('hpeU').value,p=document.getElementById('hpeP').value,btn=this;
    if(!u||!p){document.getElementById('hpeMsg').textContent='enter email and password';return}
    btn.disabled=true;document.getElementById('hpeMsg').textContent='signing in…';
    fetch(base+'/hpeLogin',{credentials:'same-origin',cache:'no-store',headers:{'X-HPE-User':u,'X-HPE-Pass':p}})
      .then(function(r){return r.json()}).then(function(r){
        document.getElementById('hpeP').value='';btn.disabled=false;
        if(r.ok){document.getElementById('hpeMsg').textContent='';load()}
        else{document.getElementById('hpeMsg').textContent=(r.error||'login failed')+(r.trace?(' ['+r.trace.join(' > ')+']'):'')}
      }).catch(function(e){btn.disabled=false;document.getElementById('hpeMsg').textContent=e.message});
  };
}
function renderSetup(){
  var c=S.config||{},el=document.getElementById('setup');
  if(!S.apiConfigured){el.innerHTML='Set <b>Appliance URL</b> and <b>Morpheus API token</b> in the plugin settings to pick the appliance VM and its backup job.';return}
  var bk=S.backup||{status:'idle'};
  var bkLine='';
  if(c.backupId){
    if(bk.status=='running')bkLine='<span class="pill warn">backup running…</span>';
    else if(bk.status=='succeeded')bkLine='<span class="pill ok">backup succeeded</span>';
    else if(bk.status=='failed')bkLine='<span class="pill bad" title="'+esc(bk.error||'')+'">backup failed</span>';
    bkLine=' &nbsp; <button id="bkRun"'+(bk.status=='running'?' disabled':'')+'>Run backup now</button> '+bkLine;
  }
  el.innerHTML='Appliance VM: <b>'+esc(c.applianceName||'not set')+'</b>'+(c.applianceServerId?' (server '+esc(c.applianceServerId)+')':'')+' · Backup: <b>'+esc(c.backupName||'none')+'</b>'+(c.backupId?' (id '+esc(c.backupId)+')':'')+bkLine+' &nbsp; <button class="secondary" id="setupEdit">Change…</button><div id="setupForm"></div>';
  var br=document.getElementById('bkRun');
  if(br)br.onclick=function(){br.disabled=true;q(base+'/backup/run').then(function(r){if(r.error){alert(r.error);br.disabled=false;return}pollBackup()})};
  document.getElementById('setupEdit').onclick=function(){
    var form=document.getElementById('setupForm');form.innerHTML='<span class="muted">Loading servers…</span>';
    q(base+'/servers').then(function(r){
      if(r.error){form.innerHTML='<span style="color:var(--bad)">'+esc(r.error)+'</span>';return}
      var opts='<option value="">— choose the appliance VM —</option>';r.servers.forEach(function(s){opts+='<option value="'+s.id+'" data-inst="'+(s.instanceId||'')+'"'+(String(s.id)==String(c.applianceServerId)?' selected':'')+'>'+esc(s.name)+' — '+esc(s.type||'')+' '+esc(s.ip||'')+' (id '+s.id+')</option>'});
      form.innerHTML='<div style="margin-top:8px">Appliance VM <select id="srv">'+opts+'</select></div><div id="bkRow" style="margin-top:8px"></div>';
      var srv=document.getElementById('srv');
      function loadBackups(){var o=srv.options[srv.selectedIndex];if(!srv.value){document.getElementById('bkRow').innerHTML='';return}
        document.getElementById('bkRow').innerHTML='<span class="muted">Loading backups…</span>';
        q(base+'/backups?serverId='+srv.value+'&instanceId='+(o.dataset.inst||'')).then(function(b){
          if(b.error){document.getElementById('bkRow').innerHTML='<span style="color:var(--bad)">'+esc(b.error)+'</span>';return}
          var mine=b.backups.filter(function(x){return x.mine}),others=b.backups.filter(function(x){return !x.mine});
          var bo='<option value="">— none (runner DB dump only) —</option>';
          function opt(x){return '<option value="'+x.id+'"'+(String(x.id)==String(c.backupId)?' selected':'')+'>'+esc(x.name)+' — '+esc(x.type||'')+(x.job?' / job '+esc(x.job):'')+(x.lastStatus?' · last '+esc(x.lastStatus):'')+' (id '+x.id+')</option>'}
          if(mine.length)bo+='<optgroup label="Backups for this VM">'+mine.map(opt).join('')+'</optgroup>';
          if(others.length)bo+='<optgroup label="Other backups">'+others.map(opt).join('')+'</optgroup>';
          document.getElementById('bkRow').innerHTML='Backup <select id="bk">'+bo+'</select> <button id="setupSave">Save</button>';
          document.getElementById('setupSave').onclick=function(){var bs=document.getElementById('bk'),bt=bs.options[bs.selectedIndex].text;
            q(base+'/config',{applianceServerId:srv.value,applianceName:o.text.split(' — ')[0],applianceInstanceId:o.dataset.inst||'',backupId:bs.value,backupName:bs.value?bt.split(' (id ')[0]:''}).then(function(r){if(r.error)alert(r.error);load()})}
        })}
      srv.onchange=loadBackups;if(srv.value)loadBackups();
    })}
}
function pollBackup(){q(base+'/backup/state').then(function(r){S.backup=r;renderSetup();if(r&&r.status=='running')setTimeout(pollBackup,5000);else load()})}
function pollLog(){q(base+'/upgrade/state?lines=200').then(function(r){S.upgrade=r.state;if(r.currentVersion)S.currentVersion=r.currentVersion;document.getElementById('log').textContent=(r.log||[]).join('\\n')||'—';var p=document.getElementById('log');p.scrollTop=p.scrollHeight;renderUpgrade(r.state);if(r.state&&r.state.status=='running')setTimeout(pollLog,5000);else load()}).catch(function(){document.getElementById('hdr').textContent='Appliance UI unreachable (expected during upgrade) — retrying…';setTimeout(pollLog,10000)})}
function load(){q(base+'/overview').then(function(d){S=d;render();if(d.upgrade&&d.upgrade.status=='running')pollLog();if(d.backup&&d.backup.status=='running')pollBackup();else q(base+'/upgrade/state?lines=60').then(function(r){document.getElementById('log').textContent=(r.log||[]).join('\\n')||'—'});var busy=(d.downloads.jobs||[]).some(function(j){return j.status=='queued'||j.status=='running'||j.status=='verifying'});if(busy)setTimeout(load,2000)})}
document.getElementById('refresh').onclick=function(){this.disabled=true;var b=this;q(base+'/refresh',{}).then(function(r){b.disabled=false;if(r.error)alert(r.error);load()})};
document.getElementById('filter').oninput=render;
load();
${FleetPage.JS}
})();
</script></body></html>"""
    }
}
