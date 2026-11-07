const fs = require('fs');
const vm = require('vm');
const assert = require('assert');
const html = fs.readFileSync('app/src/main/assets/dashboard.html', 'utf8');
for (const m of html.matchAll(/<script>([\s\S]*?)<\/script>/g)) new vm.Script(m[1]);
const extract = (start, end) => html.slice(html.indexOf(start), html.indexOf(end, html.indexOf(start)));
const elements = {
  'ping-model-status': {style: {}, innerHTML: ''},
  'ping-test-output': {style: {}, innerHTML: ''}
};
let response;
const ctx = vm.createContext({document: {getElementById: id => elements[id]}, API_BASE: '',
  fetch: async () => { if (response instanceof Error) throw response; return {json: async () => response}; },
  showToast: () => {}, console});
vm.runInContext(extract('    function esc(s)', '    function escHtml(s)'), ctx);
vm.runInContext(extract('    function errText(err, fallback)', '    function getProviderName(pid)'), ctx);
vm.runInContext(extract('    function escapeHtml(s)', '    const WB_17_MODELS'), ctx);
const bad = '<img src=x onerror="alert(1)">';
function safe() {
  for (const node of Object.values(elements)) {
    assert(!node.innerHTML.includes(bad), node.innerHTML);
    assert(!node.innerHTML.includes('<img'), node.innerHTML);
  }
}
(async () => {
  assert.equal(ctx.modelPingFailureText({error: 'old error'}), 'old error');
  assert.equal(ctx.modelPingFailureText({error: {message: 'bad'}, statusCode: 429, retryAfter: '30'}), 'bad · HTTP 429 · 重试等待: 30');
  assert(!ctx.modelPingFailureText({error: 'x', upstreamStatus: 503}).includes('503'));
  response = {ok: true, reply: bad, latencyMs: 15};
  const pending = ctx.executeModelPingTest(bad, true);
  safe();
  assert(await pending); safe();
  assert(elements['ping-test-output'].innerHTML.includes('&lt;img'));
  response = {ok: false, error: {message: bad}, hint: bad, statusCode: 429, retryAfter: '30', errorKind: 'rate_limit', localRejection: false};
  assert.equal(await ctx.executeModelPingTest(bad, true), false); safe();
  assert(elements['ping-test-output'].innerHTML.includes('HTTP 429'));
  assert(elements['ping-test-output'].innerHTML.includes('重试等待: 30'));
  response = {ok: false, error: bad};
  assert.equal(await ctx.executeModelPingTest(bad, true), false); safe();
  assert(!elements['ping-test-output'].innerHTML.includes('HTTP '));
  response = new Error(bad);
  assert.equal(await ctx.executeModelPingTest(bad, true), false); safe();
  console.log('dashboard model diagnostics: old/new responses and escaping passed');
})().catch(e => {console.error(e); process.exitCode = 1;});
