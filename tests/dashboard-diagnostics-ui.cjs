'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const browser = require('./browser-fixture.cjs');

process.env.ALLIN_TEST_TMP ||= path.resolve('build');
const html = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/dashboard.html'), 'utf8');
const css = [...html.matchAll(/<style[^>]*>[\s\S]*?<\/style>/g)].map(m => m[0]).join('');
const opsMarkup = html.slice(
  html.indexOf('<div class="card" id="ops-logs-card">'),
  html.indexOf('<!-- 中间件沙盒')
);
const diagScript = html.slice(
  html.indexOf('    function diagnosticText('),
  html.indexOf('    function loadInsightAll()')
);
const escStart = html.indexOf('    function esc(s) {');
const escScript = html.slice(escStart, html.indexOf('\n    }', escStart) + 6);
const ehStart = html.indexOf('    function escapeHtml(s) {');
const escapeHtmlScript = html.slice(ehStart, html.indexOf('\n    }', ehStart) + 6);
const ruStart = html.indexOf('      // ---- 最近调用');
const recentUsageRenderScript = html.slice(
  ruStart,
  html.indexOf('      if (state) state.innerText', ruStart)
);

const fixture = `<!doctype html><html class="glass-ui"><head>${css}</head><body class="liquid-glass">
${opsMarkup}
<pre id="result">WAIT</pre>
<pre id="errlog"></pre>
<script>
window.addEventListener('error', e => { document.getElementById('errlog').textContent += 'ERR:' + e.message + '\\n'; });
const API_BASE = '';
let fmtTok = n => String(n);
let fmtFull = n => String(n);
const copiedList = [], toastList = [], fetchUrls = [];
window.AllInNative = { copyText: t => { copiedList.push(t); return true; } };
function showToast(m) { toastList.push(m); }
${escScript}
${escapeHtmlScript}
${diagScript}
let nextSessions = {
  sessions: [
    {
      timestamp: Date.now() - 1000,
      statusCode: 200,
      model: 'workbuddy/glm-5.3',
      provider: 'workbuddy',
      totalTokens: 120,
      durationMs: 420,
      requestId: '11111111-1111-4111-8111-111111111111',
      attempt: 2,
      terminal: true,
      usageSource: 'known'
    },
    {
      timestamp: Date.now() - 2000,
      statusCode: 400,
      model: 'deepseek-reasoner',
      provider: 'openai-test',
      accountId: 'acct-1234567890abcdef',
      failurePhase: 'read_response_body',
      transportKind: 'http_rejection',
      failureReason: 'upstream_explicit_error',
      upstreamStatus: 400,
      lastSseEvent: 'content_block_delta',
      terminalSeen: false,
      upstreamErrorCode: 'context_length_exceeded',
      upstreamRetryAfter: '12',
      error: '[context_length_exceeded] max_tokens 99999 exceeds 8192 <img src=x>',
      totalTokens: 0,
      durationMs: 185,
      requestId: '11111111-1111-4111-8111-111111111111',
      attempt: 1,
      terminal: false,
      usageSource: 'unknown',
      diagnostics: {
        schemaVersion: 1,
        requestedModel: 'group/smart-coding',
        target: { provider: 'openai-test', model: 'deepseek-reasoner' },
        failureKind: 'request',
        executionState: 'rejected',
        workbuddyCompatibility: { sends: 2, stage: 'comma' }
      },
      callEvidence: {
        schemaVersion: 1,
        path: '/v1/chat/completions',
        method: 'POST',
        entry: 'model-api',
        sentModel: 'deepseek-reasoner',
        accountRef: 'acct-abcdef012345678901234567',
        messageCount: 3,
        toolCount: 1,
        systemPresent: true,
        stream: true,
        effort: 'high',
        physicalSends: 1,
        source: 'upstream',
        upstreamStatus: 400
      }
    }
  ],
  gatewayEvents: [
    {
      t: Date.now() - 500,
      requestId: '22222222-2222-4222-8222-222222222222',
      path: '/v1/chat/completions',
      method: 'POST',
      status: 403,
      stage: 'routing',
      provider: 'openai-test',
      model: 'group/disabled',
      message: '路由组未启用 <script>alert(1)<' + '/script>'
    }
  ]
};
window.fetch = async url => {
  fetchUrls.push(url);
  return { ok: true, json: async () => nextSessions };
};

(async () => {
  const check = (ok, why) => { if (!ok) throw Error(why); };
  await loadRecentSessions();
  const rs = document.getElementById('recent-sessions');
  check(rs.textContent.includes('同请求 2 跳'), 'missing multi-hop sibling chip');
  check(rs.textContent.includes('读取/流式响应') || rs.textContent.includes('等待响应头'), 'missing Chinese failure phase');
  check(rs.textContent.includes('上游 HTTP 拒绝'), 'missing Chinese transport cause');
  check(rs.textContent.includes('上游显式错误') && rs.textContent.includes('未拿到终态') && rs.textContent.includes('content_block_delta') && rs.textContent.includes('HTTP 400'), 'missing new telemetry labels in card');
  check(rs.textContent.includes('[context_length_exceeded] max_tokens 99999 exceeds 8192'), 'missing error detail');
  check(!rs.querySelector('img') && !rs.querySelector('script'), 'XSS escaped');
  check(rs.textContent.includes('WorkBuddy 兼容重试'), 'missing compat stage info');
  check(document.getElementById('diag-reject-badge').textContent === '1' && !document.getElementById('diag-reject-badge').hidden, 'reject badge count');

  // Test copy diagnostic button on the failed row (2nd item)
  const copyBtns = rs.querySelectorAll('[data-diag-copy]');
  check(copyBtns.length >= 2, 'missing copy diagnostic button');
  copyBtns[1].click();
  check(copiedList.length === 1 && copiedList[0].includes('deepseek-reasoner'), 'copy failed: ' + copiedList[0]);

  const firstRows = [...nextSessions.sessions];
  const cardsFor = () => Array.from(rs.querySelectorAll('.ios-diag-card'));
  const openTarget = cardsFor()[1];
  openTarget.querySelector('summary').click();
  openTarget.querySelector('.diag-extra-details summary').click();
  check(openTarget.open && openTarget.querySelector('.diag-extra-details').open, 'both disclosures manually opened');
  nextSessions.sessions = [{...firstRows[0], requestId: 'newer', model: 'new row'},
    {...firstRows[1], statusCode: 499, terminal: true}, firstRows[0]];
  await loadRecentSessions();
  let moved = cardsFor()[1];
  check(moved.open && moved.querySelector('.diag-extra-details').open && moved.textContent.includes('HTTP 499'), 'manual open and nested state survive new rows, reorder and status update');
  check(!cardsFor()[0].open && !cardsFor()[2].open, 'new row and sibling attempt remain collapsed');
  moved.querySelector('summary').click();
  await loadRecentSessions();
  check(!cardsFor()[1].open, 'intentional closure survives refresh');
  nextSessions.sessions = firstRows;
  await loadRecentSessions();

  // Switch to failed-only mode and provider filter
  setDiagFilter('failed');
  await new Promise(r => setTimeout(r, 20));
  check(fetchUrls.at(-1).includes('failedOnly=1'), 'failedOnly param missing: ' + fetchUrls.at(-1));

  setDiagProviderFilter('openai-test');
  await new Promise(r => setTimeout(r, 20));
  check(fetchUrls.at(-1).includes('provider=openai-test'), 'provider param missing: ' + fetchUrls.at(-1));

  // Selecting one provider must NOT drop previously known providers from dropdown options
  nextSessions = {
    sessions: nextSessions.sessions.filter(s => s.provider === 'openai-test'),
    gatewayEvents: []
  };
  await loadRecentSessions();
  const optValues = Array.from(document.getElementById('diag-provider-filter').options).map(o => o.value);
  check(optValues.includes('workbuddy') && optValues.includes('openai-test'),
    'provider dropdown lost other providers after filtering: ' + JSON.stringify(optValues));

  // Switch back to 'all|' so DOM displays known 'workbuddy' + 'openai-test' rows
  diagFilterMode = 'all';
  diagProviderFilter = '';
  nextSessions = {
    sessions: [
      {
        timestamp: Date.now() - 1000,
        statusCode: 200,
        model: 'workbuddy/glm-5.3',
        provider: 'workbuddy',
        totalTokens: 120,
        durationMs: 420,
        requestId: 'req-ok-1',
        attempt: 1,
        terminal: true,
        usageSource: 'known'
      }
    ],
    gatewayEvents: []
  };
  await loadRecentSessions();
  check(rs.textContent.includes('workbuddy/glm-5.3'), 'expected all healthy row before 503 switch');

  // Now switch to failed-only when server returns 503 -> must NOT display stale healthy rows under '仅看失败'
  window.fetch = async url => {
    fetchUrls.push(url);
    return { ok: false, status: 503, json: async () => ({ error: 'unavailable' }) };
  };
  setDiagFilter('failed');
  await new Promise(r => setTimeout(r, 20));
  const statusText = document.getElementById('recent-sessions-status').textContent;
  check(!rs.textContent.includes('workbuddy/glm-5.3'),
    'stale healthy record must NOT remain visible under failed-only filter on 503');
  check(statusText.includes('仅看失败') && statusText.includes('全部'),
    'status text must name failed filter and origin filter: ' + statusText);
  window.fetch = async url => {
    fetchUrls.push(url);
    return { ok: true, json: async () => nextSessions };
  };
  nextSessions = {
    sessions: [{
      timestamp: Date.now() - 2000, statusCode: 400, model: 'deepseek-reasoner', provider: 'openai-test',
      requestId: 'req-fail-2', attempt: 1, terminal: true, usageSource: 'unknown'
    }],
    gatewayEvents: [{
      t: Date.now() - 500, requestId: '22222222-2222-4222-8222-222222222222',
      path: '/v1/chat/completions', method: 'POST', status: 403, stage: 'routing',
      provider: 'openai-test', model: 'group/disabled', message: '路由组未启用 <script>alert(1)<' + '/script>'
    }]
  };

  // Switch to rejected events mode
  setDiagFilter('rejected');
  await new Promise(r => setTimeout(r, 20));
  check(rs.textContent.includes('HTTP 403') && rs.textContent.includes('路由选路') && rs.textContent.includes('路由组未启用'), 'rejected event not rendered');
  check(!rs.querySelector('script'), 'XSS in gatewayEvents');
  cardsFor()[0].querySelector('summary').click();
  await loadRecentSessions();
  check(!cardsFor()[0].open, 'manually closed gateway event does not reopen on refresh');

  // Exercise usage-recent rendering on failed non-WorkBuddy rows + unconfirmed WorkBuddy guard
  const j = {
    recent: [
      { t: Date.now(), prov: 'openai-test', model: 'deepseek-reasoner', status: 400, terminal: true, failurePhase: 'await_response_headers', transportKind: 'http_rejection', upstreamErrorCode: 'context_length_exceeded', err: 'max_tokens too big' },
      { t: Date.now(), prov: 'workbuddy', model: 'glm-5.3', status: 429, terminal: false, upstreamErrorCode: 'workbuddy_model_quota_exhausted', err: 'hidden-unconfirmed' }
    ]
  };
  const state = null;
  ${recentUsageRenderScript}
  const ur = document.getElementById('usage-recent');
  check(ur.textContent.includes('[openai-test]') && ur.textContent.includes('max_tokens too big'), 'usage-recent missing error detail');
  check(!ur.textContent.includes('hidden-unconfirmed'), 'usage-recent leaked unconfirmed quota detail');

  // Check unknown / failed deliveryStatus rendering in diagnostics cards & copy summary
  diagFilterMode = 'all';
  diagProviderFilter = '';
  nextSessions = {
    sessions: [
      {
        timestamp: Date.now() - 500, statusCode: 200, model: 'gpt-4o', provider: 'openai',
        conversionStatus: 'ok', deliveryStatus: 'unknown', terminalSeen: true, lastSseEvent: 'response.completed',
        requestId: 'req-deliv-unk-1', attempt: 1, terminal: true, usageSource: 'known'
      },
      {
        timestamp: Date.now() - 1000, statusCode: 200, model: 'claude-sonnet', provider: 'anthropic',
        conversionStatus: 'native', deliveryStatus: 'failed', terminalSeen: true, lastSseEvent: 'message_stop',
        failurePhase: 'downstream_delivery', failureReason: 'downstream_write_failed',
        requestId: 'req-deliv-fail-1', attempt: 1, terminal: true, usageSource: 'known'
      },
      {
        timestamp: Date.now() - 1500, statusCode: 200, model: 'gpt-4o-ok', provider: 'openai',
        conversionStatus: 'ok', deliveryStatus: 'delivered', terminalSeen: true, lastSseEvent: 'response.completed',
        requestId: 'req-deliv-ok-1', attempt: 1, terminal: true, usageSource: 'known'
      }
    ],
    gatewayEvents: []
  };
  await loadRecentSessions();
  const cards = rs.querySelectorAll('.ios-diag-card');
  check(cards.length === 3, 'expected 3 delivery test cards');
  check(!cards[0].querySelector('.diag-chip.is-ok') && cards[0].querySelector('.diag-chip.is-warn'),
    'unknown delivery must show warn chip, NEVER green is-ok');
  check(cards[0].textContent.includes('交付未知') && cards[0].textContent.includes('结果未知') && cards[0].textContent.includes('转换成功 [ok]'),
    'unknown card missing conversion/delivery labels: ' + cards[0].textContent);
  check(!cards[1].querySelector('.diag-chip.is-ok') && cards[1].querySelector('.diag-chip.is-fail'),
    'failed downstream write must show fail chip');
  check(cards[1].textContent.includes('写回客户端失败') && cards[1].textContent.includes('原生直传 [native]'),
    'failed delivery card missing details: ' + cards[1].textContent);
  check(cards[2].querySelector('.diag-chip.is-ok') && cards[2].textContent.includes('网关侧按约定写完'),
    'delivered card must clarify delivered semantics');

  document.getElementById('result').textContent = 'PASS';
})().catch(e => {
  document.getElementById('result').textContent = 'FAIL ' + (e && e.stack ? e.stack : e.message);
});
</script></body></html>`;

const out = browser.dom(fixture, 2500);
const res = out.match(/id="result"[^>]*>([\s\S]*?)<\/pre>/)?.[1]?.trim() || 'NO_RESULT';
const errlog = out.match(/id="errlog"[^>]*>([\s\S]*?)<\/pre>/)?.[1]?.trim() || '';
assert.equal(res, 'PASS', res + ' | ' + errlog + ' | tail=' + out.slice(-400));
console.log('Governance diagnostics UI filtering, phase labels, copy & gatewayEvents OK');
