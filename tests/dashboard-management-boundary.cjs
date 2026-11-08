const fs = require('fs');
const vm = require('vm');
const assert = require('assert');
const html = fs.readFileSync('app/src/main/assets/dashboard.html', 'utf8');
const script = html.match(/<script>\s*\/\/ 基础主机前缀([\s\S]*?)<\/script>/)[1];
const prefix = '// 基础主机前缀' + script.slice(0, script.indexOf('    // 从电脑浏览器直接打开'));
async function run() {
  const calls = [];
  const location = {protocol:'http:',origin:'http://127.0.0.1:3425',search:'?key=inference',hash:'#manage=management'};
  const sessionStorage = {getItem(){return ''},setItem(){}};
  const context = {location,sessionStorage,URL,URLSearchParams,Headers,Response,Promise,console,setTimeout,clearTimeout};
  context.window = {fetch:(u,i)=>{calls.push([u,i]);return Promise.resolve(new Response('{}'))}};
  vm.runInNewContext(prefix, context);
  await context.window.fetch('/api/config');
  await context.window.fetch('/v1/models');
  await context.window.fetch('http://127.0.0.1:3425.evil/api/config');
  await context.window.fetch('//evil/api/config');
  assert.equal(calls[0][1].headers.get('authorization'), 'Bearer management');
  assert.equal(calls[1][1].headers.get('authorization'), 'Bearer inference');
  assert.equal(calls[1][1].headers.get('x-all-in-management'), 'management');
  assert.equal(calls[2][1], undefined);
  assert.equal(calls[3][1], undefined);
  assert(html.includes("fetch(API_BASE + '/api/providers/accounts/export'"));
  assert(html.includes("!res.ok || !d.ok || !Array.isArray(d.accounts)"));
  assert(!html.includes("esc(keys.filter(k => typeof k === 'string').join(','))"));
  const reconcile = html.slice(html.indexOf('    async function reconcileProviderReadback('), html.indexOf('    // 兼容旧脚本', html.indexOf('    async function reconcileProviderReadback(')));
  const source = `let commercialKeysDirty = true;
    const providerUnknown = new Set(['p']), providerPendingKeys = new Map([['p', {snapshot: JSON.stringify(keys)}]]),
      dirtyKeysMap = new Map([['p', keys]]), commercialEditedIds = new Set(['p']),
      providerDraftTokens = new Map(), providerDraftConflicts = new Set();
    const API_BASE = ''; const showToast = () => {};
    ${reconcile}
    globalThis.execute = reconcileProviderReadback;
    globalThis.state = () => ({unknown:providerUnknown.has('p'), pending:providerPendingKeys.has('p'), dirty:dirtyKeysMap.has('p'), conflict:providerDraftConflicts.has('p')});`;
  async function check(keys, fetchImpl, expected, current = () => true) {
    const ctx = {keys, fetch:fetchImpl, JSON, Promise}; vm.runInNewContext(source, ctx);
    await ctx.execute({providers:[{id:'p',keys:['redacted-ref']}],providerStateTokens:{p:'v1'}}, current);
    assert.deepEqual(JSON.parse(JSON.stringify(ctx.state())), expected);
  }
  const waiting = {unknown:true,pending:true,dirty:true,conflict:false};
  const matched = {unknown:false,pending:false,dirty:false,conflict:false};
  const conflict = {unknown:false,pending:false,dirty:true,conflict:true};
  const reply = data => async (url, options) => {
    assert.equal(url, '/api/providers/keys/verify'); assert.equal(options.method, 'POST');
    assert.equal(JSON.parse(options.body).providerId, 'p');
    return {ok:true,json:async()=>data};
  };
  const valid = matched => ({ok:true,providerId:'p',matched,stateToken:'v1'});
  for (const keys of [['raw-secret'], [{ref:'opaque-reference'}]]) {
    await check(keys, reply(valid(true)), matched);
    await check(keys, reply(valid(false)), conflict);
    await check(keys, async()=>{throw Error('offline')}, waiting);
    await check(keys, async()=>({ok:false}), waiting);
    await check(keys, reply({ok:true,providerId:'p',matched:true}), waiting);
    await check(keys, reply({...valid(true),stateToken:'v2'}), waiting);
    await check(keys, reply({...valid(false),stateToken:'v2'}), waiting);
    await check(keys, reply(valid(true)), waiting, () => false);
  }
  assert(html.includes('await reconcileProviderReadback(incoming, () => configRequestId === providerConfigRequest && providerWriteRevision === requestWriteRevision && !providerWrites.size)'));
  assert(html.includes('if (configRequestId !== providerConfigRequest || providerWriteRevision !== requestWriteRevision || providerWrites.size) return;\n        providerConfigError = false;')); 
  assert(html.includes('providerConfigError = false; renderProviderStatus();')); 
  console.log('dashboard management boundary passed');
}
run().catch(e => { console.error(e); process.exitCode=1; });
