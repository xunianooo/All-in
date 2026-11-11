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

  // ── Qoder 排队：实测回包既不是失败也不是登录失败 ──
  // 下面这条是真实抓到的 /api/test-model 返回（qoder_cn/fast、lmodel、qmodel_38max
  // 三个模型同一形状），网关因为「排队回包没有推理正文」把它记成 schema/inference。
  const qoderQueued = {model: 'qoder_cn/fast', latencyMs: 284, statusCode: 503, provider: 'qoder_cn',
    localRejection: false, executionUnknown: true, errorKind: 'schema', failureLayer: 'inference',
    failureLayerLabel: '实际推理', failureLabel: '回包结构认不出', affectsInference: null,
    canTryOtherModel: null,
    advice: '请求已经发出但没拿到可判读的结果：先别重放（可能已经计费），换一家确认。',
    retryAfter: '60', ok: false, error: 'Qoder queued the request; try again later'};
  const queued = ctx.modelPingQueueNotice(qoderQueued);
  assert(queued && queued.waitSeconds === 60, 'a real queue reply must be recognized with its wait time');
  response = qoderQueued;
  assert.equal(await ctx.executeModelPingTest('qoder_cn/fast', true), false);
  safe();
  let qStatus = elements['ping-model-status'].innerHTML;
  let qOut = elements['ping-test-output'].innerHTML;
  assert(qStatus.includes('排队中'), 'status must say the vendor queued it: ' + qStatus);
  assert(qStatus.includes('不是登录失败'), qStatus);
  assert(!/登录与凭据|认证或凭据被拒|需要重新登录/.test(qStatus + qOut),
    'a queue must never be blamed on credentials: ' + qStatus + qOut);
  assert(qOut.includes('Qoder queued the request; try again later'), 'the official text must stay: ' + qOut);
  assert(qOut.includes('HTTP 503') && qOut.includes('重试等待: 60'), 'the real facts must stay: ' + qOut);
  assert(qOut.includes('不能据此判定这个模型可用或不可用'), qOut);
  assert(qOut.includes('执行状态未知'), 'the gateway executionUnknown flag must still be shown: ' + qOut);

  // 没给等待秒数就说不知道，不替供应商编一个数字
  response = {model: 'qoder_cn/lmodel', provider: 'qoder_cn', statusCode: 503, ok: false,
    error: 'Qoder queued the request; try again later'};
  assert.equal(ctx.modelPingQueueNotice(response).waitSeconds, 0);
  await ctx.executeModelPingTest('qoder_cn/lmodel', true);
  assert(elements['ping-test-output'].innerHTML.includes('没给等待秒数'),
    elements['ping-test-output'].innerHTML);

  // 排队证据不齐，一律退回通用失败渲染：不许把别的故障说成排队
  assert.equal(ctx.modelPingQueueNotice(Object.assign({}, qoderQueued, {provider: 'workbuddy', model: 'workbuddy/hy3'})), null);
  assert.equal(ctx.modelPingQueueNotice(Object.assign({}, qoderQueued, {statusCode: 502})), null);
  assert.equal(ctx.modelPingQueueNotice(Object.assign({}, qoderQueued, {error: 'Qoder sign-in expired'})), null);
  assert.equal(ctx.modelPingQueueNotice(Object.assign({}, qoderQueued, {ok: true})), null);
  response = {model: 'workbuddy/hy3', provider: 'workbuddy', statusCode: 503, ok: false,
    error: 'Qoder queued the request; try again later'};
  await ctx.executeModelPingTest('workbuddy/hy3', true);
  assert(!elements['ping-test-output'].innerHTML.includes('排队'), 'another provider must not borrow the queue wording');

  // Qoder 真的登录失效时，仍然按登录层说，不许被排队文案吞掉
  response = {ok: false, model: 'qoder_cn/fast', provider: 'qoder_cn', statusCode: 401,
    errorKind: 'auth', failureLayer: 'login', failureLayerLabel: '登录与凭据',
    failureLabel: '认证或凭据被拒', error: 'Qoder sign-in expired',
    advice: '上游拒了凭据：这个账号或 Key 需要重新登录、刷新或更换。'};
  assert.equal(await ctx.executeModelPingTest('qoder_cn/fast', true), false);
  assert(elements['ping-model-status'].innerHTML.includes('登录与凭据'),
    elements['ping-model-status'].innerHTML);
  assert(!elements['ping-test-output'].innerHTML.includes('排队'), elements['ping-test-output'].innerHTML);

  // 排队文案里的上游/网关字段同样要转义
  response = Object.assign({}, qoderQueued, {error: bad + ' queued', failureLabel: bad, hint: bad});
  await ctx.executeModelPingTest('qoder_cn/fast', true);
  safe();
  assert(elements['ping-test-output'].innerHTML.includes('&lt;img'), elements['ping-test-output'].innerHTML);
  assert(elements['ping-model-status'].innerHTML.includes('排队中'));

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

  console.log('dashboard model diagnostics: layers, unknown reasons, Qoder queue wording, escaping and probe emptiness passed');
})().catch(e => {console.error(e); process.exitCode = 1;});
