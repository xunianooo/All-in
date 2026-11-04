const fs = require('fs'), vm = require('vm'), assert = require('assert'), path = require('path');
const html = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/dashboard.html'), 'utf8');
function slice(a,b) { const start=html.indexOf(a), end=html.indexOf(b,start); assert(start>=0 && end>start); return html.slice(start,end); }
const source = [slice('    let authKeyRevision = 0;', '    function toggleKeyVisible()'), slice('    let gatewayIp =', '    /** 从服务端拉当前口令 */'), slice('    async function loadConfig()', '    // 记住上一次'), slice('    async function saveProviders()', '    function testMaskSandbox()'), slice('    let setupState =', '    // 页面启动初始化')].join('\n');
const response = data => ({ok:true,json:async()=>JSON.parse(JSON.stringify(data))});
function createServer() {
 const server={key:'magpie',disk:'magpie',done:false,requests:[],delayed:{},fail:false};
 server.fetch=async (url,opt={})=>{
  const body=opt.body ? JSON.parse(opt.body) : null; server.requests.push({url,body});
  let value;
  if(url==='/api/setup') value={ok:true,needed:!server.done,authKeyIsDefault:server.key==='magpie'};
  else if(url==='/api/server-key' && body) { if(server.fail) return response({ok:false,error:{message:'保存失败'}}); server.key=server.disk=body.authKey; value={ok:true,authKey:server.key}; }
  else if(url==='/api/server-key') value={ok:true,authKey:server.key};
  else if(url==='/api/config' && body) { if(body.server) server.key=server.disk=body.server.authKey; value={ok:true}; }
  else if(url==='/api/config') value={providers:[],server:{authKey:server.key}};
  else if(url==='/api/setup/complete') { if(body.authKey) server.key=server.disk=body.authKey; server.done=true; value={ok:true,authKey:server.key,needed:false}; }
  else value={ok:true,permissions:{},keepalive:{}};
  if(server.delayed[url]) return new Promise(resolve=>server.delayed[url].push(()=>resolve(response(value))));
  return response(value);
 };
 return server;
}
function page(server) {
 const elements={}; const document={getElementById(id){return elements[id] || (elements[id]={value:'',innerText:'',innerHTML:'',style:{},focus(){},select(){},setSelectionRange(){}})},createElement(){return {style:{},appendChild(){}}},body:{appendChild(){}}};
 const context={document,window:{crypto:{getRandomValues(buf){buf.fill(9)}},zcodeGiftLoaded:true},Uint8Array,Math,JSON,location:{hostname:'127.0.0.1',port:'3425'},API_BASE:'',AUTH_KEY:'magpie',currentAuthKey:'',authKeyVisible:false,appConfig:null,lastConfigSig:'',renderDirty:false,fetch:server.fetch,sessionStorage:{setItem(){}},navigator:{},console,setTimeout,showToast(message){context.toasts.push(message)},toasts:[],alerts:[],gatewayBaseUrl:()=> 'http://host:3425',renderCredentials(){},autoSyncAfterLogin(){},renderCommercialKeys(){},renderGroups(){},bindGroupEvents(){},renderWbAccounts(){},renderWbModelTags(){},renderAllProvidersCards(){},refreshProviderBadges(){},applyPlaceholderGuard(){},renderMiddleware(){},renderPingProviders(){},renderAllModelsTags(){},loadZCodeGift(){},alert(message){context.alerts.push(message)}};
 vm.createContext(context); vm.runInContext(source,context); return {c:context,e:elements};
}
(async()=>{
 const s=createServer(), p=page(s); await p.c.checkSetup(); const chosen=p.e['setup-key'].value;
 assert(chosen.startsWith('mp-')); await p.c.setupNext();
 assert.equal(s.disk,chosen,'first step must persist generated key'); assert.equal(s.key,chosen); assert.equal(s.done,false,'setup is only completed at step 3');
 assert.equal(vm.runInContext('AUTH_KEY',p.c),chosen); assert.equal(p.c.currentAuthKey,chosen);
 // An Activity/WebView recreation must see the applied key even before completing setup.
 const p2=page(s); await p2.c.loadAuthKey(); await p2.c.checkSetup(); assert.equal(p2.c.currentAuthKey,chosen); assert.equal(p2.e['setup-key'].value,chosen); assert.equal(s.done,false);
 // A stale auth response must not overwrite the newly applied key.
 const race=createServer(), r=page(race); const delayed=[]; race.delayed['/api/server-key']=delayed; const old=r.c.loadAuthKey(); delete race.delayed['/api/server-key']; await r.c.checkSetup(); const next=r.e['setup-key'].value; await r.c.setupNext(); delayed[0](); await old;
 assert.equal(r.c.currentAuthKey,next); assert.equal(vm.runInContext('AUTH_KEY',r.c),next); assert.equal(r.e['cred-api-key'].value,next);
 // A startup config response can arrive after the write; saving providers must not post its old server.
 const stale=createServer(), q=page(stale), delayedConfig=[]; stale.delayed['/api/config']=delayedConfig; const oldConfig=q.c.loadConfig(); delete stale.delayed['/api/config']; await q.c.checkSetup(); const newKey=q.e['setup-key'].value; await q.c.setupNext(); delayedConfig[0](); await oldConfig; await q.c.saveProviders(); assert.equal(stale.key,newKey); assert(!('server' in stale.requests.find(x=>x.url==='/api/config' && x.body).body));
 // Keep-current is cleared by actual user input.
 const k=createServer(), kp=page(k); await kp.c.checkSetup(); kp.c.setupKeepCurrent(); assert(html.includes('oninput="setupKeyInput()"')); kp.e['setup-key'].value='typed-new-key'; kp.c.setupKeyInput(); await kp.c.setupNext(); assert.equal(k.key,'typed-new-key');
 // Failed writes do not advance or claim success.
 const f=createServer(), fp=page(f); await fp.c.checkSetup(); f.fail=true; await fp.c.setupNext(); assert.equal(vm.runInContext('setupState.step',fp.c),1); assert.equal(f.disk,'magpie');
 // Finish writes only the completion flag; reboot reads the previously persisted key.
 await p.c.setupNext(); await p.c.setupNext(); assert(s.done); assert.deepEqual(s.requests.find(x=>x.url==='/api/setup/complete').body,{keepCurrent:true}); s.key=s.disk; const boot=page(s); await boot.c.loadAuthKey(); await boot.c.checkSetup(); assert.equal(boot.c.currentAuthKey,chosen); assert.equal(boot.e['setup-wizard']?.style.display,undefined);
 // Delayed key write locks controls and rejects repeated or stale actions.
 const lock=createServer(), lp=page(lock); await lp.c.checkSetup(); const key=lp.e['setup-key'].value, held=[];
 lock.delayed['/api/server-key']=held; const pending=lp.c.setupNext();
 for (const id of ['setup-key','setup-btn-copy','setup-btn-regen','setup-btn-keep','setup-btn-back','setup-btn-next']) assert.equal(lp.e[id].disabled,true,id);
 await lp.c.setupNext(); lp.c.setupRegen(); lp.c.setupKeepCurrent(); lp.c.setupCopyKey();
 lp.e['setup-key'].value='unapplied'; lp.c.setupKeyInput(); assert.equal(lp.e['setup-key'].value,key);
 assert.equal(lock.requests.filter(x=>x.url==='/api/server-key' && x.body).length,1); held[0](); await pending;
 assert.equal(lock.key,key); assert.equal(lp.e['setup-key'].value,key); assert.equal(lp.e['setup-btn-next'].disabled,false);
 // checkSetup's newer read invalidates an earlier loadAuthKey read after WebView reconstruction.
 const re=createServer(); re.key=re.disk='restored-key'; const rp=page(re), oldRead=[]; re.delayed['/api/server-key']=oldRead;
 const staleRead=rp.c.loadAuthKey(); re.key=re.disk='new-restored-key'; delete re.delayed['/api/server-key']; await rp.c.checkSetup(); oldRead[0](); await staleRead;
 assert.equal(rp.c.currentAuthKey,'new-restored-key'); assert.equal(vm.runInContext('AUTH_KEY',rp.c),'new-restored-key'); assert.equal(rp.e['cred-api-key'].value,'new-restored-key');
 // Explicit keep does not apply the generated candidate.
 const keep=createServer(), kept=page(keep); await kept.c.checkSetup(); kept.c.setupKeepCurrent(); await kept.c.setupNext(); await kept.c.setupNext(); await kept.c.setupNext(); assert.equal(keep.key,'magpie'); assert(keep.done); assert.equal(kept.e['setup-btn-next'].disabled,false);
 // A changed server key cannot be reported as a successful applied-key completion.
 const mismatch=createServer(), mp=page(mismatch); await mp.c.checkSetup(); await mp.c.setupNext(); await mp.c.setupNext(); mismatch.key='external-change'; await mp.c.setupNext(); assert(mp.c.toasts.some(x=>x.includes('不一致'))); assert.notEqual(mp.e['setup-wizard'].style.display,'none'); assert.equal(mp.e['setup-btn-next'].disabled,false);
 // Failed configuration writes cannot report success.
 const bad=createServer(), bp=page(bad); await bp.c.loadConfig(); const original=bp.c.fetch; bp.c.fetch=async (url,opt)=>url==='/api/config' && opt ? {ok:false,status:500} : original(url,opt); await bp.c.saveProviders(); assert(!bp.c.toasts.includes('配置已保存！')); assert(bp.c.alerts.some(x=>x.includes('500')));
 console.log('setup first-step persistence, races, reconstruction, failure and completion OK');
})().catch(e=>{console.error(e);process.exitCode=1});
