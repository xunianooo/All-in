#!/usr/bin/env node
// Legacy default auth key migration UI: old installs (setup.done=true) whose gateway key is
// still the published default "magpie" must get a prominent, actionable prompt that can
// generate / copy / apply a new key and says the computer client has to be updated too.
//
// Runs against the real dashboard.html slices in headless chromium with a synthetic
// gateway; no device, no Gradle, no real accounts.
const fs = require('fs'), path = require('path'), cp = require('child_process'), assert = require('assert');

const html = fs.readFileSync(path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'dashboard.html'), 'utf8');
function slice(a, b) {
  const start = html.indexOf(a), end = html.indexOf(b, start);
  assert(start >= 0 && end > start, 'slice missing: ' + a);
  return html.slice(start, end);
}
const copyCode = slice('    /** 统一的复制实现', '    function startEditAuthKey');
const setupCode = slice('    let setupState =', '    // 页面启动初始化');

// ---------------------------------------------------------------- static guard
const migration = setupCode.slice(
  setupCode.indexOf('    function showWeakKeyBanner()'),
  setupCode.indexOf('    function openSetupWizard('));
assert(migration.length > 500, 'migration block must exist');
assert(migration.includes('/api/server-key'), 'migration writes the gateway key through /api/server-key');
assert(/class="warn-bar"|warn-bar/.test(migration), 'migration prompt uses the warning banner style');
assert(migration.includes('legacy_default_auth_key'), 'prompt names the 403 code the gateway now returns');
assert(migration.includes('电脑端'), 'prompt tells the user the computer client must be updated');
assert(migration.includes('供应商账号'), 'prompt states provider accounts are left untouched');
for (const forbidden of ['/api/config', '/api/providers', '/api/accounts', '/api/setup/complete',
  '/api/backup', '/api/keys', '/account/', '/api/middleware', '/api/groups', '/api/routing']) {
  assert(!migration.includes(forbidden), 'migration must not touch ' + forbidden);
}
// Only /api/server-key may be touched. One POST is the single write path; the extra call is a
// plain GET used to read back an unknown result — never a second write, never an auto-retry.
const fetchUrls = [];
for (const m of migration.matchAll(/fetch\(API_BASE \+ '([^']*)'/g)) fetchUrls.push(m[1]);
assert.deepEqual(Array.from(new Set(fetchUrls)), ['/api/server-key'], 'migration only talks to /api/server-key: ' + fetchUrls);
assert.equal(fetchUrls.length, 2, 'one write + one read-back only: ' + fetchUrls.length);
assert.equal((migration.match(/method: 'POST'/g) || []).length, 1, 'exactly one write path in the migration prompt');
assert(!/method: 'GET'/.test(migration), 'the read-back goes out as a plain GET');
assert(!/✕/.test(migration), 'the prompt is not silently dismissible');
// A lost reply must not be dressed up as "nothing changed", and it must not invite a retry.
assert(migration.includes('保存结果未确认'), 'an unconfirmed write gets its own state');
assert(migration.includes('可能已经写入'), 'the unknown state admits the write may have landed');
assert(migration.includes('重复提交'), 'the unknown state forbids blind resubmission');
assert(migration.includes('重新读取'), 'the unknown state offers a read-back instead of a retry');
assert(migration.includes('if (weakKeyUnknown)'), 'apply refuses to run while the result is unknown');
assert.equal((migration.match(/网关口令没有变化/g) || []).length, 1, 'only one code path may claim the key did not change');
assert(migration.indexOf("verdict === 'refused'") < migration.indexOf('网关口令没有变化'),
  'the "did not change" claim sits inside the explicit-refusal branch');
assert(migration.includes('weakKeyUnknownLock(false)'), 'a definite refusal releases the unknown lock');
// Unknown means the submitted key is the only thing that can be compared: generation must not
// overwrite it, and copy must stay available so the user can save it first.
const regen = migration.slice(migration.indexOf('function weakKeyRegen()'), migration.indexOf('function weakKeyCopy()'));
assert(regen.includes('if (weakKeyUnknown)'), 'generation is refused while a result is unconfirmed');
assert(/覆盖/.test(regen), 'the refusal explains that generating would overwrite the value being checked');
const lock = migration.slice(migration.indexOf('function weakKeyUnknownLock('), migration.indexOf('function weakKeyRefusalText('));
assert(/weakkey-gen/.test(lock) && /weakkey-apply/.test(lock), 'the unknown lock covers generate and apply');
assert(!/weakkey-copy/.test(lock), 'copy is deliberately left available so the pending key can be saved');
// No request may hang forever: both the write and the read-back race a timeout.
assert(migration.includes('WEAK_KEY_TIMEOUT_MS'), 'the write path has an explicit timeout');
assert.equal((migration.match(/await weakKeyRace\(/g) || []).length, 2, 'write and read-back both run under the timeout race');
assert(/没有回音/.test(migration), 'a timeout is described as "no answer", i.e. unknown');
const applyBody = migration.slice(migration.indexOf('async function weakKeyApply()'), migration.indexOf('function weakKeyApplied('));
assert(applyBody.includes('out.timeout') && /未确认|weakKeyUncertain/.test(applyBody), 'a timeout ends in the unknown state');
assert(applyBody.indexOf('weakKeyBusy = false') > applyBody.indexOf('weakKeyRace('), 'the busy lock is released even when the write times out');
// 超时的写请求可能稍后才落地：只读复核在它还在路上时不能当结论，也不能解锁提交；
// 迟到的回包才是它的真正结局——确认成功就确认，失败也只允许“再读一次”，绝不重发。
assert(migration.includes('function weakKeyRace(work, ms, onLate)'), 'the timeout race keeps watching the late reply');
assert(applyBody.includes('token.watched = true'), 'a timeout marks the write as still in flight');
assert(/WEAK_KEY_TIMEOUT_MS, late => \{ token\.late =/.test(applyBody), 'the timed-out write records how it really ended');
assert(migration.includes('function weakKeyLateSettle('), 'a late reply settles the doubt on its own terms');
assert(migration.includes('function weakKeyAdopt('), 'adoption goes through one shared path');
const rereadBody = migration.slice(migration.indexOf('async function weakKeyReread()'), migration.indexOf('function weakKeyRegen()'));
assert(rereadBody.includes('watched') && rereadBody.includes('stillOpen'), 'the read-back knows whether the old write is still in flight');
assert(rereadBody.indexOf('stillOpen') < rereadBody.indexOf('weakKeyAdopt('), 'the in-flight check runs before anything is adopted');
assert(rereadBody.includes('不能当结论'), 'an in-flight read-back is explicitly not a verdict');
assert(rereadBody.indexOf('不能当结论') < rereadBody.indexOf('weakKeyAdopt('), 'an in-flight read-back returns before adoption');
assert(rereadBody.includes('weakKeyUnknown !== pending'), 'a late confirmation during the read wins over the read-back');
// A 4xx only counts as a refusal when the gateway itself answers with its own error envelope.
assert(migration.includes('function weakKeyRefusal('), 'refusals are classified, not assumed');
const refusal = migration.slice(migration.indexOf('function weakKeyRefusal('), migration.indexOf('let WEAK_KEY_TIMEOUT_MS'));
assert(refusal.includes('res.ok !== false'), 'a refusal needs an actual error response');
assert(/400/.test(refusal) && /401/.test(refusal) && /403/.test(refusal), 'only pre-handler statuses count');
assert(refusal.includes('typeof err.message') && refusal.includes('typeof err === ') , 'a structured gateway error is required');
assert(!/500|502|503/.test(refusal), '5xx is never treated as a definite refusal');
// 401 belongs to the management credential, which the panel proves is separate from server.authKey.
assert(migration.includes('管理授权'), '401 is described as a management-authorization problem');
assert(!/用刚复制的新口令重新打开面板/.test(migration), '401 no longer pretends the inference key must be live');
assert(!/已生效/.test(migration), 'no message claims the key is live based on a 401');
assert(!/说明这次没有写入/.test(migration), 'a differing read-back proves nothing about the earlier submission');
assert(/不能证明/.test(migration), 'a differing read-back says so explicitly');

// ---------------------------------------------------------------- DOM harness
const harness = `<!doctype html><html><body>
<div id="setup-anchor"></div>
<pre id="result">WAIT</pre>
<script>
let API_BASE='', AUTH_KEY='', currentAuthKey='', authKeyKnown=false, authKeyRevision=0;
let appConfig={providers:[{id:'raccoon',accounts:[{id:'acc-1',token:'keep'}],keys:[],models:[]}],server:{authKey:'magpie'}};
const sessionStore={}, toasts=[], requests=[];
try{Object.defineProperty(window,'sessionStorage',{configurable:true,value:{setItem:(k,v)=>{sessionStore[k]=v},getItem:k=>Object.prototype.hasOwnProperty.call(sessionStore,k)?sessionStore[k]:null}});}catch(e){}
function showToast(m){toasts.push(m)}
function renderCredentials(){}
let gw={done:true,key:'magpie',mode:'ok'}, held=null;
function response(data,ok=true,status=200){return {ok,status,json:async()=>JSON.parse(JSON.stringify(data))}}
async function fetch(url,opt){
  const body=opt&&opt.body?JSON.parse(opt.body):null;
  requests.push({url,method:(opt&&opt.method)||'GET',body});
  if(url==='/api/setup'){const def=gw.key==='magpie';
    return response({ok:true,needed:!gw.done,done:gw.done,authKeyIsWeak:def,authKeyIsDefault:def,authKeyIsLegacyDefault:def,authKeyMigrationRequired:def,authKeyMinLengthForRemote:8});}
  if(url==='/api/server-key'&&body){
    // A connection that dies before the request is even written: nothing landed.
    if(gw.mode==='netfail')throw new TypeError('Failed to fetch');
    // A connection that dies after the gateway applied the key: the write DID land.
    if(gw.mode==='lose'){gw.key=body.authKey;throw new TypeError('Failed to fetch');}
    // No answer at all: the panel has to give up on its own instead of hanging forever.
    if(gw.mode==='hang')return new Promise(()=>{});
    // A captive portal/proxy style 400 with an HTML body: not the gateway's own envelope.
    if(gw.mode==='html400')return {ok:false,status:400,json:async()=>{throw new SyntaxError('Unexpected token <');}};
    // The gateway's own error envelopes.
    if(gw.mode==='reject')return response({error:{message:'这个口令是随安装包发布的公开默认值，任何同网设备都能猜到；请生成新的随机口令'}},false,400);
    if(gw.mode==='auth401')return response({error:'需要网关口令',hint:'请求头带上 Authorization: Bearer <authKey>'},false,401);
    if(gw.mode==='err500')return response({error:{message:'保存网关口令失败',type:'gateway_error'}},false,500);
    if(gw.mode==='echo')return response({ok:true,authKey:'server-did-something-else'});
    // A request that outlives the panel's timeout and then dies without an answer: nothing landed.
    if(gw.mode==='latefail'){
      if(held)return held.promise;
      held={};
      held.promise=new Promise((resolve,reject)=>{held.release=()=>{reject(new TypeError('Failed to fetch'));};});
      return held.promise;
    }
    if(gw.mode==='delay'){
      if(held)return held.promise; // duplicate click: never answer twice
      held={};
      held.promise=new Promise(resolve=>{held.release=()=>{gw.key=body.authKey;resolve(response({ok:true,authKey:body.authKey}));}});
      return held.promise;
    }
    gw.key=body.authKey; return response({ok:true,authKey:gw.key});
  }
  if(url==='/api/server-key'){
    // Read-back channel. 401 here is the tell-tale of a write that landed while the panel
    // still held the old key.
    if(gw.read401)return response({error:{message:'鉴权失败'}},false,401);
    return response({ok:true,authKey:gw.key});
  }
  if(url==='/api/config'&&!opt)return response(JSON.parse(JSON.stringify(appConfig)));
  return response({ok:true});
}
${copyCode}
${setupCode}
function check(v,m){if(!v)throw Error(m)}
const $=id=>document.getElementById(id);
function bar(){return $('weakkey-banner')}
function posts(){return requests.filter(r=>r.url==='/api/server-key'&&r.method==='POST').length}
function gets(){return requests.filter(r=>r.url==='/api/server-key'&&r.method==='GET').length}
const tick=ms=>new Promise(r=>setTimeout(r,ms));
// Fresh page per scenario: the prompt, the pending-unknown marker and the session key all start empty.
function reset(){const b=bar(); if(b)b.remove(); toasts.length=0;
  weakKeyUnknown=null; AUTH_KEY=''; currentAuthKey=''; authKeyKnown=false;
  for(const k in sessionStore)delete sessionStore[k];}
(async()=>{
  // 1. old install (setup.done) + published default key -> prominent migration prompt
  await checkSetup();
  check(!!bar(),'migration prompt is shown for setup.done + default key');
  check($('setup-anchor').firstElementChild===bar(),'prompt sits first in the dashboard content');
  const text=bar().textContent;
  check(text.includes('magpie'),'prompt names the published default');
  check(text.includes('403')&&text.includes('legacy_default_auth_key'),'prompt explains the remote refusal code');
  check(text.includes('电脑端')&&text.includes('401'),'prompt says the computer client must be updated (or it still 401s)');
  check(text.includes('供应商账号'),'prompt promises provider accounts are untouched');
  check(bar().getAttribute('data-migration')==='legacy-default-auth-key','prompt is machine identifiable');
  check($('weakkey-new')&&$('weakkey-gen')&&$('weakkey-copy')&&$('weakkey-apply'),'generate/copy/apply controls exist');

  // 2. generate + copy + apply, and only the gateway key is written
  $('weakkey-gen').onclick();
  const generated=$('weakkey-new').value;
  check(/^mp-[A-Za-z0-9]{8,}$/.test(generated),'generated key is strong enough for LAN use');
  check($('weakkey-status').textContent.includes('已生成'),'status reports the generated key');
  const realCopy=window.copyText; let copiedText=null, copiedMsg='';
  window.copyText=(v,m)=>{copiedText=v;copiedMsg=m;};
  $('weakkey-copy').onclick();
  check(copiedText===generated&&copiedMsg.includes('电脑端'),'copy copies the generated key and reminds about the computer');
  window.copyText=realCopy;
  const configBefore=JSON.stringify(appConfig);
  const writesBefore=requests.filter(r=>r.url!=='/api/setup').length;
  await $('weakkey-apply').onclick();
  const writes=requests.filter(r=>r.url!=='/api/setup').slice(writesBefore);
  check(writes.length===1,'apply issues exactly one write ('+JSON.stringify(writes)+')');
  check(writes[0].url==='/api/server-key'&&writes[0].method==='POST','the only write is POST /api/server-key');
  check(writes[0].body.authKey===generated,'the generated key is what gets applied');
  check(gw.key===generated,'gateway now holds the new key');
  check(AUTH_KEY===generated&&currentAuthKey===generated,'panel switched to the new key');
  check(sessionStore.magpieAuthKey===generated,'session carries the new key');
  check(JSON.stringify(appConfig)===configBefore,'existing provider accounts are untouched');
  const applied=bar().textContent;
  check(applied.includes('已保存')&&applied.includes('电脑端')&&applied.includes(generated),'success state repeats the key and the computer instruction');
  check($('weakkey-applied-key')&&$('weakkey-applied-key').textContent===generated,'applied key is readable for manual copy');
  check($('weakkey-applied-copy'),'applied state still offers a copy button');

  // 3. rejection (published default / invalid) keeps the gateway key and says so
  gw={done:true,key:'magpie',mode:'reject'}; reset(); AUTH_KEY=''; currentAuthKey='';
  await checkSetup(); $('weakkey-gen').onclick(); const rejected=$('weakkey-new').value;
  await $('weakkey-apply').onclick();
  check(gw.key==='magpie','rejected write leaves the gateway key unchanged');
  check($('weakkey-status').textContent.includes('保存失败'),'rejection is visible');
  check(!$('weakkey-apply').disabled,'apply is re-enabled after a failure');
  check(AUTH_KEY!==rejected,'panel did not adopt a rejected key');
  check(!bar().textContent.includes('已保存'),'failure is not reported as success');
  check($('weakkey-status').textContent.includes('没有变化'),'a definite refusal may still say the key did not change');
  check(!$('weakkey-status').textContent.includes('未确认'),'a definite refusal is not dressed up as unknown');
  check($('weakkey-reread').style.display==='none','a definite refusal does not offer a read-back');

  // 4. mismatched echo is not treated as applied
  gw={done:true,key:'magpie',mode:'echo'}; reset();
  await checkSetup(); $('weakkey-gen').onclick();
  await $('weakkey-apply').onclick();
  check(gw.key==='magpie','mismatched echo leaves the gateway key unchanged');
  check($('weakkey-status').textContent.includes('不一致'),'mismatched echo is reported honestly');
  check($('weakkey-status').textContent.includes('未确认'),'a mismatched echo counts as an unknown result, not a refusal');
  check(!bar().textContent.includes('已保存'),'mismatched echo is not success');

  // 5. double click while the write is in flight cannot double-apply
  gw={done:true,key:'magpie',mode:'delay'}; reset(); held=null;
  await checkSetup(); $('weakkey-gen').onclick(); const once=$('weakkey-new').value;
  const writesBeforeHold=requests.filter(r=>r.url==='/api/server-key'&&r.method==='POST').length;
  const first=$('weakkey-apply').onclick();
  check($('weakkey-apply').disabled&&$('weakkey-gen').disabled&&$('weakkey-copy').disabled,'controls lock while saving');
  await $('weakkey-apply').onclick();
  check(requests.filter(r=>r.url==='/api/server-key'&&r.method==='POST').length===writesBeforeHold+1,'duplicate click while in flight cannot issue a second write');
  check(gw.key==='magpie','nothing is applied before the reply');
  held.release(); await first;
  check(gw.key===once,'in-flight save applies once');

  // 6. a connection that dies is an UNKNOWN result: the reply may have been lost after the
  //    gateway already wrote, so the panel must not claim the key is unchanged.
  gw={done:true,key:'magpie',mode:'netfail'}; reset();
  await checkSetup(); $('weakkey-gen').onclick(); const lost=$('weakkey-new').value;
  const postsBeforeLost=posts();
  await $('weakkey-apply').onclick();
  let unknownText=$('weakkey-status').textContent;
  check(unknownText.includes('未确认'),'a broken connection is reported as unconfirmed, not as failure ('+unknownText+')');
  check(unknownText.includes('可能已经写入'),'the unconfirmed state admits the write may have landed');
  check(!unknownText.includes('没有变化'),'a broken connection no longer claims the key did not change');
  check(unknownText.includes('重新读取')&&unknownText.includes('重复提交'),'the unconfirmed state says how to confirm and forbids retrying');
  check(!bar().textContent.includes('已保存'),'an unconfirmed write is never shown as applied');
  check(AUTH_KEY!==lost&&!sessionStore.magpieAuthKey,'the panel does not adopt an unconfirmed key');
  check($('weakkey-apply').disabled,'apply is locked while the result is unknown');
  check($('weakkey-reread').style.display!=='none','the read-back control appears exactly for an unknown result');
  check(posts()===postsBeforeLost+1,'the broken connection issued exactly one write');
  await $('weakkey-apply').onclick();
  check(posts()===postsBeforeLost+1,'a second click cannot resubmit an unknown write');
  check($('weakkey-status').textContent.includes('重新读取'),'the blocked resubmission points at the read-back');

  // 7. the write landed but the reply was lost: the read-back confirms it instead of guessing
  gw={done:true,key:'magpie',mode:'lose'}; reset();
  await checkSetup(); $('weakkey-gen').onclick(); const landed=$('weakkey-new').value;
  const postsBeforeLanded=posts();
  await $('weakkey-apply').onclick();
  check(gw.key===landed,'synthetic gateway did write before the connection dropped');
  check($('weakkey-status').textContent.includes('未确认'),'a lost reply is unknown, not a failure');
  check(!bar().textContent.includes('已保存'),'an unconfirmed write is not announced as applied');
  check($('weakkey-apply').disabled,'apply stays locked until the read-back confirms');
  const getsBeforeRead=gets();
  await $('weakkey-reread').onclick();
  check(gets()===getsBeforeRead+1,'the read-back is exactly one GET');
  check(posts()===postsBeforeLanded+1,'the read-back never writes again');
  check(currentAuthKey===landed&&AUTH_KEY===landed,'the read-back adopts the key the gateway really holds');
  check(sessionStore.magpieAuthKey===landed,'the session follows the confirmed key');
  check(bar().textContent.includes('已保存')&&bar().textContent.includes(landed),'a confirmed write is reported as applied');
  check(!$('weakkey-reread'),'the read-back control disappears once the result is confirmed');

  // 8. the read-back disagrees: it proves what is live NOW, never what the earlier POST did
  gw={done:true,key:'magpie',mode:'netfail'}; reset();
  await checkSetup(); $('weakkey-gen').onclick(); const firstTry=$('weakkey-new').value;
  await $('weakkey-apply').onclick();
  check($('weakkey-apply').disabled,'unknown before the read-back');
  const getsBeforeDisagree=gets();
  await $('weakkey-reread').onclick();
  const disagreed=$('weakkey-status').textContent;
  check(disagreed.includes('已读取'),'the read-back reports what the gateway holds ('+disagreed+')');
  check(!disagreed.includes('没有写入')&&!disagreed.includes('说明这次'),'a differing key is never proof the earlier write failed');
  check(disagreed.includes('不能证明'),'the panel says the differing value proves nothing about the earlier submission');
  check(!bar().textContent.includes('已保存'),'a differing read-back is not shown as applied');
  check(!$('weakkey-apply').disabled,'apply becomes available again once the current key is known');
  check($('weakkey-reread').style.display==='none','the read-back control is hidden again');
  check(gets()===getsBeforeDisagree+1,'one read-back per doubt');
  gw.mode='ok';
  await $('weakkey-apply').onclick();
  check(gw.key===firstTry,'a deliberate resubmission after a differing read is still possible');
  check(bar().textContent.includes('已保存'),'the deliberate resubmission is confirmed as applied');

  // 9. the read-back itself fails: the doubt stays open, and it is not read as "key is live"
  gw={done:true,key:'magpie',mode:'netfail'}; reset();
  await checkSetup(); $('weakkey-gen').onclick();
  await $('weakkey-apply').onclick();
  gw.read401=true;
  const postsBefore401=posts();
  await $('weakkey-reread').onclick();
  const unread=$('weakkey-status').textContent;
  check(unread.includes('重新读取失败'),'a failed read-back is visible ('+unread+')');
  check(unread.includes('401'),'the failed read-back names the status it got');
  check(unread.includes('管理授权'),'a 401 read-back is attributed to the management credential');
  check(!unread.includes('已生效')&&!unread.includes('已经生效'),'a 401 read-back is not read as "the new key is live"');
  check(unread.includes('重复提交'),'a failed read-back keeps forbidding blind resubmission');
  check($('weakkey-apply').disabled,'apply stays locked when even the read-back fails');
  await $('weakkey-apply').onclick();
  check(posts()===postsBefore401,'no blind resubmission after a failed read-back');

  // 10. no answer at all: the panel must give up on its own, stay unconfirmed, protect the
  //     pending value from being regenerated, and never resubmit.
  WEAK_KEY_TIMEOUT_MS=30;
  gw={done:true,key:'magpie',mode:'hang'}; reset();
  await checkSetup(); $('weakkey-gen').onclick(); const hanging=$('weakkey-new').value;
  const postsBeforeHang=posts();
  await $('weakkey-apply').onclick();
  const hangText=$('weakkey-status').textContent;
  check(hangText.includes('未确认'),'a timeout ends in the unconfirmed state, not in a hang ('+hangText+')');
  check(hangText.includes('没有回音'),'a timeout is described as no answer');
  check(!hangText.includes('没有变化'),'a timeout never claims the key did not change');
  check(!bar().textContent.includes('已保存'),'a timeout is not shown as applied');
  check($('weakkey-apply').disabled,'apply is locked after a timeout');
  check($('weakkey-gen').disabled,'generation is locked after a timeout');
  check(!$('weakkey-copy').disabled,'copy stays available so the pending key can be saved');
  check($('weakkey-new').value===hanging,'the pending key survives the timeout');
  $('weakkey-gen').onclick();
  check($('weakkey-new').value===hanging,'generating cannot overwrite the value being checked');
  check($('weakkey-status').textContent.includes('覆盖'),'the blocked generation says why it is blocked');
  await $('weakkey-apply').onclick();
  check(posts()===postsBeforeHang+1,'a timeout cannot be resubmitted blindly');
  WEAK_KEY_TIMEOUT_MS=20000;

  // 11. a 4xx that is not the gateway's own envelope (proxy/captive portal HTML) is unknown
  gw={done:true,key:'magpie',mode:'html400'}; reset();
  await checkSetup(); $('weakkey-gen').onclick();
  await $('weakkey-apply').onclick();
  const proxyText=$('weakkey-status').textContent;
  check(proxyText.includes('未确认'),'an unparseable 400 is unknown, not a refusal ('+proxyText+')');
  check(!proxyText.includes('没有变化'),'an unparseable 400 never claims the key did not change');
  check($('weakkey-apply').disabled,'an unparseable 400 locks apply');

  // 12. 5xx is unknown: the gateway may have failed after touching the file
  gw={done:true,key:'magpie',mode:'err500'}; reset();
  await checkSetup(); $('weakkey-gen').onclick();
  await $('weakkey-apply').onclick();
  const err500=$('weakkey-status').textContent;
  check(err500.includes('未确认'),'a 5xx is unknown, not a refusal ('+err500+')');
  check(!err500.includes('没有变化'),'a 5xx never claims the key did not change');

  // 13. a structured 401 IS a refusal (the gateway rejects before the handler), but it is about
  //     the management credential: the panel must not say anything about the inference key.
  gw={done:true,key:'magpie',mode:'auth401'}; reset();
  await checkSetup(); $('weakkey-gen').onclick(); const notAccepted=$('weakkey-new').value;
  await $('weakkey-apply').onclick();
  const auth401=$('weakkey-status').textContent;
  check(auth401.includes('保存失败'),'a structured 401 is a definite failure ('+auth401+')');
  check(auth401.includes('管理授权'),'a 401 is blamed on the management credential');
  check(!auth401.includes('已经生效')&&!auth401.includes('已生效'),'a 401 never claims the new key is live');
  check(!auth401.includes('需要网关口令'),'the gateway hint that mixes both credentials is not repeated');
  check($('weakkey-status').textContent.includes('没有变化'),'the request provably did not run, so nothing changed');
  check(!$('weakkey-apply').disabled,'apply is usable again after a definite refusal');
  check($('weakkey-reread').style.display==='none','a definite refusal needs no read-back');
  check(!bar().textContent.includes('已保存'),'a refused 401 is not success');
  check(currentAuthKey!==notAccepted,'the panel does not adopt a key that was never accepted');

  // 14. a strong key does not nag, and an unfinished setup uses the wizard instead
  gw={done:true,key:'mp-strongerkey1234',mode:'ok'}; reset();
  await checkSetup();
  check(!bar(),'no migration prompt once the key is not the published default');
  gw={done:false,key:'magpie',mode:'ok'}; reset(); setupState.data=null;
  await checkSetup();
  check(!bar(),'first-run wizard path does not double-render the migration prompt');

  // 15. the timed-out write is still on its way: a read-back taken now is only today's value,
  //     so it must not unlock a second write; the late reply is what settles the doubt.
  WEAK_KEY_TIMEOUT_MS=30;
  gw={done:true,key:'magpie',mode:'delay'}; reset(); held=null;
  await checkSetup(); $('weakkey-gen').onclick(); const lateKey=$('weakkey-new').value;
  const postsBeforeLate=posts();
  await $('weakkey-apply').onclick();
  check($('weakkey-status').textContent.includes('未确认'),'a timeout locks the card while the write is in flight');
  check(posts()===postsBeforeLate+1,'the timeout left exactly one write on the wire');
  check($('weakkey-apply').disabled,'apply is locked while the old write may still land');
  const getsWhileOpen=gets();
  await $('weakkey-reread').onclick();
  check(gets()===getsWhileOpen+1,'the read-back is a single GET');
  check(posts()===postsBeforeLate+1,'the read-back never writes');
  check(gw.key==='magpie','the old write really had not landed when the read-back ran');
  const openText=$('weakkey-status').textContent;
  check(openText.includes('仍可能在途'),'the read-back says the old write is still on its way ('+openText+')');
  check(openText.includes('不能当结论'),'an in-flight read-back is not a verdict');
  check(!openText.includes('已读取网关当前口令'),'a stale read-back is not dressed up as a final read');
  check(!bar().textContent.includes('已保存'),'an in-flight read-back never claims success');
  check($('weakkey-apply').disabled,'apply stays locked while the old write is in flight');
  check($('weakkey-gen').disabled,'generation stays locked as well');
  check(AUTH_KEY!==lateKey&&currentAuthKey!==lateKey,'a stale read-back does not adopt the pending key');
  await $('weakkey-apply').onclick();
  check(posts()===postsBeforeLate+1,'nothing is resubmitted while the old write is in flight');
  held.release(); await tick(80);
  check(gw.key===lateKey,'the late write landed only after the read-back');
  check(bar().textContent.includes('已保存')&&bar().textContent.includes(lateKey),'the late reply confirms the write instead of guessing');
  check(posts()===postsBeforeLate+1,'the late confirmation never adds a second write');
  check($('weakkey-reread')===null,'nothing is left to re-read once the late reply confirms');
  check(AUTH_KEY===lateKey&&currentAuthKey===lateKey,'the panel follows the key the gateway really holds');
  WEAK_KEY_TIMEOUT_MS=20000;

  // 16. the late request ends in a dead connection: it can no longer change the gateway, but the
  //     result is still unknown — only then does a read-back have the right to conclude anything.
  WEAK_KEY_TIMEOUT_MS=30;
  gw={done:true,key:'magpie',mode:'latefail'}; reset(); held=null;
  await checkSetup(); $('weakkey-gen').onclick(); const lostLate=$('weakkey-new').value;
  const postsBeforeDead=posts();
  await $('weakkey-apply').onclick();
  check(posts()===postsBeforeDead+1,'one write, and no automatic retry');
  await $('weakkey-reread').onclick();
  check($('weakkey-status').textContent.includes('仍可能在途'),'the early read-back still sees the write in flight');
  check($('weakkey-apply').disabled,'apply is still locked while the write is open');
  check(AUTH_KEY!==lostLate,'the early read-back does not adopt either key');
  held.release(); await tick(80);
  const deadText=$('weakkey-status').textContent;
  check(deadText.includes('现在才结束'),'the dead connection is reported when it actually happens ('+deadText+')');
  check(!bar().textContent.includes('已保存'),'a late failure is never success');
  check($('weakkey-apply').disabled,'apply stays locked after an unknown late ending');
  check(posts()===postsBeforeDead+1,'a late failure is never retried automatically');
  const getsAfterDead=gets();
  await $('weakkey-reread').onclick();
  check(gets()===getsAfterDead+1,'the read-back after the write closed is a single GET');
  check(posts()===postsBeforeDead+1,'even the conclusive read-back never writes');
  const closedRead=$('weakkey-status').textContent;
  check(closedRead.includes('已读取网关当前口令'),'the conclusive read-back reports what the gateway holds ('+closedRead+')');
  check(closedRead.includes('不能证明'),'it still does not claim the earlier write failed');
  check(!$('weakkey-apply').disabled,'apply unlocks only once the old write is really gone');
  check(!$('weakkey-reread').style||$('weakkey-reread').style.display==='none','the read-back control is hidden again');
  check(AUTH_KEY==='magpie','the panel adopts the key the gateway really holds');
  WEAK_KEY_TIMEOUT_MS=20000;

  $('result').textContent='PASS';
})().catch(e=>{$('result').textContent='FAIL '+(e&&e.message?e.message:e)});
<\/script></body></html>`;

const out=require('./browser-fixture.cjs').dom(harness,5000);
const verdict = out.match(/<pre id="result">([\s\S]*?)<\/pre>/);
assert(verdict, 'harness result element missing');
assert.equal(verdict[1], 'PASS', verdict[1]);
console.log('legacy default key migration prompt: visible, generate/copy/apply, computer update, no account writes — OK');
