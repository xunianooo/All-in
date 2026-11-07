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
let jsonError;
const ctx = vm.createContext({document: {getElementById: id => elements[id]}, API_BASE: '',
  fetch: async () => { if (response instanceof Error) throw response; return {json: async () => { if (jsonError) throw jsonError; return response; }}; },
  showToast: () => {}, console});
vm.runInContext('Number.prototype.toLocaleString = function () { return String(this.valueOf()); }', ctx);
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
  // ── 原有契约：旧响应仍能读，并且不认 upstreamStatus ──
  assert.equal(ctx.modelPingFailureText({error: 'old error'}), 'old error');
  assert.equal(ctx.modelPingFailureText({error: {message: 'bad'}, statusCode: 429, retryAfter: '30'}), 'bad · HTTP 429 · 重试等待: 30');
  assert(!ctx.modelPingFailureText({error: 'x', upstreamStatus: 503}).includes('503'));

  // ── 未知不能说成确定的故障 ──
  const unknown = ctx.modelPingFailureText({});
  assert(!unknown.includes('未配置有效 Key'), 'a missing reason must not blame the key: ' + unknown);
  assert(unknown.includes('未知'), unknown);

  // ── 层与类别进文案 ──
  const layered = ctx.modelPingFailureText({error: 'x', failureLabel: '域名解析失败',
    failureLayerLabel: '本机到上游', statusCode: 502});
  assert(layered.includes('类别: 域名解析失败'), layered);
  assert(layered.includes('层: 本机到上游'), layered);

  // ── 转义：成功与失败两条路都不许把上游正文注入 ──
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

  // ── 失败状态栏要说清是哪一层，不能只有一个「失败」 ──
  response = {ok: false, error: 'HTTP 403: restricted', statusCode: 403, errorKind: 'restricted',
    failureLayer: 'login', failureLayerLabel: '登录与凭据', failureLabel: '账号被供应商限制 (403)',
    affectsInference: true, canTryOtherModel: false, advice: '供应商限制了这个账号：换该供应商的其他账号'};
  assert.equal(await ctx.executeModelPingTest('m', true), false);
  safe();
  assert(elements['ping-model-status'].innerHTML.includes('登录与凭据'),
    'status must name the layer: ' + elements['ping-model-status'].innerHTML);
  assert(elements['ping-test-output'].innerHTML.includes('该怎么做'),
    'advice must be shown: ' + elements['ping-test-output'].innerHTML);
  assert(!elements['ping-test-output'].innerHTML.includes('账号整体不可用'));

  response = {ok:false, errorKind:'timeout', affectsInference:'unknown', canTryOtherModel:false, executionUnknown:true};
  await ctx.executeModelPingTest('m', true);
  assert(elements['ping-test-output'].innerHTML.includes('对推理的影响未知'));
  assert(elements['ping-test-output'].innerHTML.includes('可能已经执行并产生费用'));
  assert(elements['ping-test-output'].innerHTML.includes('不能盲目重放'));
  response = {ok:true, billing:'gift', giftRemaining:0};
  await ctx.executeModelPingTest('m', true);
  assert(elements['ping-test-output'].innerHTML.includes('剩 0'), elements['ping-test-output'].innerHTML);
  response = {ok:true, billing:'gift'};
  await ctx.executeModelPingTest('m', true);
  assert(elements['ping-test-output'].innerHTML.includes('剩余未知'));

  // ── gift 没接手这次测试，要说清为什么 ──
  response = {ok: false, error: 'HTTP 403: x', statusCode: 403, giftFallbackReason: 'model_not_in_gift'};
  assert.equal(await ctx.executeModelPingTest('zcode/glm-5.3', true), false);
  assert(elements['ping-test-output'].innerHTML.includes('model_not_in_gift'),
    'the gift fallback reason must be shown: ' + elements['ping-test-output'].innerHTML);

  // ── 面板到网关这一跳失败，不能怪上游 ──
  response = new Error(bad);
  assert.equal(await ctx.executeModelPingTest(bad, true), false); safe();
  for (const mode of ['fetch', 'json']) {
    response = mode === 'fetch' ? new Error(bad) : {};
    jsonError = mode === 'json' ? new Error(bad) : null;
    await ctx.executeModelPingTest('m', true); safe();
    const output = elements['ping-test-output'].innerHTML;
    assert(output.includes('未取得可判读结果') && output.includes('执行状态未知') && output.includes('不能盲目重放'), output);
    assert(!/还没到上游|未发送/.test(output), output);
  }
  jsonError = null;

  // ── 端点探测：没有结果 = 未知，不是全不可达 ──
  const probeCtx = vm.createContext({esc: ctx.esc, endpointProbeScope: '官方默认地址'});
  vm.runInContext(extract('    function endpointProbeMarkup(result)', '    async function providerEndpointProbe(provider)'), probeCtx);
  const empty = probeCtx.endpointProbeMarkup({ok: true, results: [], summary: '探测完成'});
  assert(empty.includes('未返回任何端点结果'), 'empty results must say unknown: ' + empty);
  assert(!empty.includes('#f87171'), 'empty results must not paint everything unreachable: ' + empty);
  const one = probeCtx.endpointProbeMarkup({ok: true, results: [{reachable: true, kind: 'response', label: 'x'}]});
  assert(one.includes('官方默认地址'), one);

  console.log('dashboard model diagnostics: layers, unknown reasons, escaping and probe emptiness passed');
})().catch(e => {console.error(e); process.exitCode = 1;});
