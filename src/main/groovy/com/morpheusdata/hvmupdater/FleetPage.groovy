package com.morpheusdata.hvmupdater

/**
 * JS for the "Post-upgrade: hosts & VMs" card. Kept in a triple-single-quoted Groovy string so no
 * dollar-sign interpolation happens; the code below deliberately contains no backslashes and no
 * regex literals (the controller's GString would mangle them).
 */
class FleetPage {

    static final String HTML = '''
<div class="card" id="fleetCard"><h2>Post-upgrade — hosts &amp; VMs <button class="secondary" id="fleetReload">Reload</button></h2>
  <div id="fleet" class="muted">Loading…</div></div>
'''

    static final String JS = '''
var F={inv:null,run:null,mode:'parallel',sel:{}};
var NL=String.fromCharCode(10);
function fq(u,d){return q(base+u,d)}
function fleetLoad(){fq('/fleet').then(function(r){if(r.error){document.getElementById('fleet').innerHTML='<span style="color:var(--bad)">'+esc(r.error)+'</span>';return}F.inv=r;F.run=r.run;fleetRender();if(F.run&&F.run.status=='running')setTimeout(fleetPoll,6000)})}
function fleetPoll(){fq('/fleet/state').then(function(r){F.run=r;fleetRenderRun();if(r.status=='running')setTimeout(fleetPoll,6000);else fleetLoad()})}
function ver(v){return v?esc(v):'<span class="muted">none</span>'}
function fleetRender(){
  var inv=F.inv,el=document.getElementById('fleet');if(!inv){el.textContent='—';return}
  var run=F.run||{status:'idle'},busy=run.status=='running';
  var lb=inv.latestAgentByPlatform||{};var latest=Object.keys(lb).map(function(k){return k+' '+lb[k]}).join(', ')||'?';
  var h='<div class="muted" style="margin-bottom:8px">Newest agent seen: <b>'+esc(latest)+'</b> · '+esc(inv.note||'')+'</div>'+(inv.routeNote?'<div style="color:var(--warn);margin-bottom:8px">⚠ '+esc(inv.routeNote)+'</div>':'');
  h+='<div style="margin-bottom:10px"><label><input type="radio" name="fmode" value="parallel"'+(F.mode=='parallel'?' checked':'')+'> All at once</label>'+
     '<label><input type="radio" name="fmode" value="rolling"'+(F.mode=='rolling'?' checked':'')+'> Rolling with maintenance mode (one host at a time)</label>'+
     '<label><input type="radio" name="fmode" value="manual"'+(F.mode=='manual'?' checked':'')+'> Manual — just track what I do in Morpheus</label></div>';
  h+='<div id="fleetOpts" style="margin-bottom:10px'+(F.mode=='rolling'?'':';display:none')+'"><label><input type="checkbox" id="fRelocate"> also move powered-off VMs to another host</label>'+
     '<label><input type="checkbox" id="fIgnoreWarn"> proceed despite preflight warnings</label>'+
     '<label>wait per target <input type="text" id="fWait" value="30" style="width:50px"> min</label> <button class="secondary" id="fPreflight">Check HA preflight for selected hosts</button></div>';
  var hosts=inv.hosts||[],vms=inv.vms||[];
  var byCl={};hosts.forEach(function(x){var k=x.clusterName||'(no cluster)';(byCl[k]=byCl[k]||[]).push(x)});
  h+='<table><tr><th><input type="checkbox" id="fAllHosts"></th><th>Host</th><th>Cluster</th><th>Status</th><th>Agent</th><th>VMs on/off</th><th>Memory</th></tr>';
  Object.keys(byCl).sort().forEach(function(k){byCl[k].forEach(function(x){
    var mem=x.maxMemory?fmtB(x.usedMemory||0)+' / '+fmtB(x.maxMemory):'';
    var tag=x.hvm?'':' <span class="pill" title="not an HVM/KVM host — no Morpheus agent to upgrade">not HVM — skipped</span>';
    h+='<tr class="'+(x.eligible?'':'noroute')+'"><td><input type="checkbox" class="fh" value="'+x.id+'"'+(F.sel['h'+x.id]?' checked':'')+(x.eligible?'':' disabled')+'></td><td>'+esc(x.name)+tag+'<div class="muted">'+esc(x.ip||'')+' · '+esc(x.type||'')+' · id '+x.id+'</div></td><td>'+esc(k)+'</td><td>'+esc(x.status||'')+(x.powerState?' / '+esc(x.powerState):'')+'</td><td>'+ver(x.agentVersion)+(x.behind?' <span class="pill warn">behind</span>':x.agentVersion?' <span class="pill ok">current</span>':'')+'</td><td>'+(x.running||0)+' / '+(x.off||0)+'</td><td>'+mem+'</td></tr>'})});
  h+='</table>';
  h+='<div style="margin:12px 0 4px"><b>Managed VMs with an agent</b> <span class="muted">('+vms.length+')</span> <label style="margin-left:10px"><input type="checkbox" id="fOnlyBehind" checked> only show behind</label></div>';
  h+='<div style="max-height:320px;overflow:auto"><table><tr><th><input type="checkbox" id="fAllVms"></th><th>VM</th><th>Host</th><th>Power</th><th>Agent</th><th>Route</th></tr>';
  vms.forEach(function(x){var route=x.route=='agent'?'<span class="pill ok">agent</span>':x.route=='ssh'?'<span class="pill">ssh creds</span>':'<span class="pill bad" title="agent not reporting and no saved credentials — Morpheus will fail this one">no route</span>';
    h+='<tr class="fvrow'+(x.behind?' behind':'')+(x.eligible?'':' noroute')+'"><td><input type="checkbox" class="fv" value="'+x.id+'"'+(F.sel['v'+x.id]?' checked':'')+'></td><td>'+esc(x.name)+(x.isAppliance?' <span class="pill warn" title="this is the Morpheus appliance VM itself — upgrading its agent is fine, just be deliberate">this appliance</span>':'')+'<div class="muted">'+esc(x.os||'')+' · '+esc(x.ip||'')+'</div></td><td>'+esc(x.parentName||'')+'</td><td>'+esc(x.powerState||'')+'</td><td>'+ver(x.agentVersion)+(x.behind?' <span class="pill warn">behind</span>':'')+'</td><td>'+route+'</td></tr>'});
  h+='</table></div>';
  h+='<div style="margin-top:12px"><button id="fStart"'+(busy?' disabled':'')+'>Upgrade selected…</button> <span id="fMsg" class="muted"></span></div>';
  h+='<div id="fleetRun" style="margin-top:12px"></div>';
  el.innerHTML=h;
  document.querySelectorAll('input[name=fmode]').forEach(function(r){r.onchange=function(){F.mode=r.value;document.getElementById('fleetOpts').style.display=F.mode=='rolling'?'':'none'}});
  document.getElementById('fAllHosts').onchange=function(){var c=this.checked;document.querySelectorAll('.fh').forEach(function(b){if(b.disabled)return;b.checked=c;F.sel['h'+b.value]=c})};
  document.getElementById('fAllVms').onchange=function(){var c=this.checked;document.querySelectorAll('.fv').forEach(function(b){var tr=b.closest('tr');if(tr.style.display!='none'&&(!c||tr.className.indexOf('noroute')<0)){b.checked=c;F.sel['v'+b.value]=c}})};
  document.querySelectorAll('.fh').forEach(function(b){b.onchange=function(){F.sel['h'+b.value]=b.checked}});
  document.querySelectorAll('.fv').forEach(function(b){b.onchange=function(){F.sel['v'+b.value]=b.checked}});
  function filterVms(){var only=document.getElementById('fOnlyBehind').checked;document.querySelectorAll('.fvrow').forEach(function(tr){var hide=only&&tr.className.indexOf('behind')<0;tr.style.display=hide?'none':'';if(hide){var b=tr.querySelector('.fv');if(b){b.checked=false;F.sel['v'+b.value]=false}}})}
  document.getElementById('fOnlyBehind').onchange=filterVms;filterVms();
  document.getElementById('fPreflight').onclick=function(){var ids=selIds('.fh');if(!ids.length){alert('select at least one host');return}
    fq('/fleet/preflight',{hostIds:ids.join(',')}).then(function(r){if(r.error){alert(r.error);return}var out=[];Object.keys(r.preflight||{}).forEach(function(n){var p=r.preflight[n];out.push(n+': '+(p.ok?'OK':'BLOCKED')+' — running VMs '+p.detail.runningVms+', powered-off '+p.detail.poweredOffVms+', peers '+(p.detail.peers||[]).join(', ')+(p.blocks.length?NL+'  blocks: '+p.blocks.join('; '):'')+(p.warnings.length?NL+'  warnings: '+p.warnings.join('; '):''))});alert(out.join(NL+NL)||'nothing to check')})};
  document.getElementById('fStart').onclick=fleetStart;
  fleetRenderRun();
}
function selIds(cls){var a=[];document.querySelectorAll(cls).forEach(function(b){var tr=b.closest('tr');if(b.checked&&!b.disabled&&tr&&tr.style.display!='none')a.push(b.value)});return a}
function selNames(cls){var a=[];document.querySelectorAll(cls).forEach(function(b){var tr=b.closest('tr');if(b.checked&&!b.disabled&&tr&&tr.style.display!='none')a.push(tr.querySelector('td:nth-child(2)').firstChild.textContent)});return a}
function fleetStart(){
  var hs=selIds('.fh'),vs=selIds('.fv');if(!hs.length&&!vs.length){alert('select at least one host or VM');return}
  var mode=F.mode,msg;
  var selfSel=(F.inv.vms||[]).some(function(v){return v.isAppliance&&vs.indexOf(String(v.id))>=0});
  if(selfSel&&!confirm('The Morpheus appliance VM itself is selected. Its agent will restart (the UI is unaffected). Include it?'))return;
  if(mode=='parallel')msg='Upgrade the agent on '+hs.length+' host(s) and '+vs.length+' VM(s) ALL AT ONCE via PUT /api/servers/{id}/upgrade.'+NL+NL+'Hosts stay in service; each agent restarts. This is the same as select-all → Upgrade Agent on the Hosts page.';
  else if(mode=='rolling')msg='Rolling upgrade of '+hs.length+' host(s), one at a time:'+NL+'  preflight (HA capacity + peers) → maintenance mode (Morpheus migrates running VMs) → '+(document.getElementById('fRelocate').checked?'move powered-off VMs → ':'leave powered-off VMs → ')+'upgrade agent → wait → leave maintenance.'+NL+'Then '+vs.length+' VM(s) in parallel.'+NL+NL+'A failed host stops the run and is LEFT IN MAINTENANCE MODE for you to inspect.';
  else msg='Manual mode: nothing is executed. The card tracks '+hs.length+' host(s) and '+vs.length+' VM(s) while you upgrade them yourself in Morpheus (Host → Actions → Upgrade Agent).';
  var names=selNames('.fh').concat(selNames('.fv'));
  if(!confirm(msg+NL+NL+'Targets: '+names.join(', ')+NL+NL+'Proceed?'))return;
  var d={mode:mode,hostIds:hs.join(','),vmIds:vs.join(','),relocateOff:document.getElementById('fRelocate').checked?'1':'',ignoreWarnings:document.getElementById('fIgnoreWarn').checked?'1':'',waitMinutes:document.getElementById('fWait').value||'30'};
  document.getElementById('fMsg').textContent='starting…';
  fq('/fleet/start',d).then(function(r){document.getElementById('fMsg').textContent='';if(r.error){alert(r.error);return}F.sel={};fleetLoad()});
}
function fleetRenderRun(){
  var el=document.getElementById('fleetRun'),run=F.run;if(!el)return;if(!run||run.status=='idle'){el.innerHTML='';return}
  var pill=run.status=='running'?'<span class="pill warn">running</span>':run.status=='done'?'<span class="pill ok">done</span>':run.status=='manual'?'<span class="pill">manual tracking</span>':'<span class="pill bad">'+esc(run.status)+'</span>';
  var h='<div><b>Fleet run</b> · '+esc(run.mode)+' · started '+new Date(run.started).toLocaleString()+' '+pill+(run.error?' <span style="color:var(--bad)">'+esc(run.error)+'</span>':'')+(run.note?' <span class="muted">'+esc(run.note)+'</span>':'')+
    (run.status=='running'?' <button class="secondary" id="fCancel">Cancel after current</button>':'')+(run.mode=='manual'?' <button class="secondary" id="fManualRefresh">Refresh from API</button>':'')+'</div>';
  h+='<table><tr><th>Target</th><th>Kind</th><th>Status</th><th>Step</th><th>Agent</th><th>Detail</th></tr>';
  (run.targets||[]).forEach(function(t){
    var st=t.status=='done'?'<span class="pill ok">done</span>':t.status=='running'?'<span class="pill warn">running</span>':t.status=='failed'||t.status=='stalled'?'<span class="pill bad">'+esc(t.status)+'</span>':'<span class="pill">'+esc(t.status)+'</span>';
    var link='<a href="/infrastructure/servers/'+t.id+'" target="_blank">'+esc(t.name)+'</a>';
    var pf=t.preflight&&t.preflight.warnings&&t.preflight.warnings.length?'<div class="muted">⚠ '+esc(t.preflight.warnings.join('; '))+'</div>':'';
    h+='<tr><td>'+link+'</td><td>'+esc(t.kind)+'</td><td>'+st+'</td><td>'+esc(t.step||'')+'</td><td>'+ver(t.agentBefore)+(t.agentNow&&t.agentNow!=t.agentBefore?' → '+esc(t.agentNow):'')+'</td><td>'+esc(t.message||'')+(t.process?' <span class="muted">(process '+esc(t.process)+')</span>':'')+pf+'</td></tr>'});
  h+='</table>';
  if(run.mode=='manual')h+='<div class="muted" style="margin-top:6px">Manual: in Morpheus open each host → Actions → Upgrade Agent (or select-all on the Hosts list). API equivalent: <code>PUT /api/servers/{id}/upgrade</code>. Click Refresh from API to update the rows.</div>';
  el.innerHTML=h;
  var c=document.getElementById('fCancel');if(c)c.onclick=function(){c.disabled=true;fq('/fleet/cancel').then(function(){})};
  var m=document.getElementById('fManualRefresh');if(m)m.onclick=function(){m.disabled=true;fq('/fleet/state',{refresh:1}).then(function(r){F.run=r;fleetRenderRun()})};
}
document.getElementById('fleetReload').onclick=fleetLoad;
fleetLoad();
'''
}
