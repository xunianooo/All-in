// Listen-plan notice on the 接入 tab: /api/status listenAction/listenNote must be
// surfaced as clear Chinese with the real endpoint, and must never touch the
// configuration, the gateway key or the selected connection.
// Synthetic fixtures only; no Gradle, no real config, no network.
const fs = require('fs'), vm = require('vm'), assert = require('assert');
const html = fs.readFileSync('app/src/main/assets/dashboard.html', 'utf8');
for (const m of html.matchAll(/<script>([\s\S]*?)<\/script>/g)) new vm.Script(m[1]);
const part = (a, b) => html.slice(html.indexOf(a), html.indexOf(b, html.indexOf(a)));

const noticeCode = part('    function listenPlanPort()', '    /** 刷新卡片里的两项取值 */');
// Comments explain the contract; only executable lines are scanned for writes.
const noticeCodeOnly = noticeCode.split('\n').filter(line => {
  const t = line.trim();
  return !(t.startsWith('//') || t.startsWith('*') || t.startsWith('/*'));
}).join('\n');

function page() {
  const nodes = {}, toasts = [], copied = [];
  const mk = () => ({
    textContent: '', innerText: '', innerHTML: '', value: '', placeholder: '', type: '',
    style: {}, hidden: true, attrs: {},
    setAttribute(k, v) { this.attrs[k] = v; }
  });
  const document = {
    hidden: false,
    getElementById: id => nodes[id] || (nodes[id] = mk())
  };
  const context = {
    console, URL, API_BASE: '', gatewayRunning: true, gatewayPort: 3425,
    document, window: {}, sessionStorage: { getItem() { return ''; }, setItem() {} },
    AUTH_KEY: '', fetch: null, statsFailures: 0,
    applyGatewayUI() {}, setTimeout() {}, clearTimeout() {},
    renderEtaBase() {}, checkSetup() {},
    showToast: message => toasts.push(String(message)),
    copyText: (text, ok) => copied.push({ text: String(text), ok: String(ok) })
  };
  vm.createContext(context);
  vm.runInContext(part('    let authKeyVisible = false;', '    /** 一键复制：地址或密钥 */'), context);
  vm.runInContext(part('    async function loadStats() {', '    /**\n     * 配置没变'), context);
  vm.runInContext(part('    function copyManagementPanelLink() {', '    /** 统一的复制实现'), context);
  return { c: context, nodes, toasts, copied };
}
const response = x => ({ ok: true, json: async () => x });
const status = (extra) => Object.assign({ port: 3425, ip: '192.168.1.10' }, extra);
const watch = node => {
  let value = node.textContent, writes = 0;
  Object.defineProperty(node, 'textContent', {
    get: () => value, set: next => { writes++; value = next; }, configurable: true
  });
  return () => writes;
};

(async () => {
  // The notice is declared inside the gateway tab's credentials card, hidden by default.
  const cardAt = html.indexOf('id="card-credentials"');
  const noticeAt = html.indexOf('id="listen-plan-notice"');
  assert(cardAt > 0 && noticeAt > cardAt, 'notice lives in the credentials card');
  assert(noticeAt < html.indexOf('id="tab-providers"'), 'notice stays on the gateway tab');
  assert(html.includes('id="listen-plan-notice" role="status" aria-live="polite" hidden'));
  const noticeHtml = html.slice(noticeAt, noticeAt + 260);
  assert(noticeHtml.includes('id="listen-plan-address"'), 'notice markup is complete');
  assert(!/<(?:button|input|select|a)\b/i.test(noticeHtml), 'no switch, field or link is added');
  assert(!/onclick/i.test(noticeHtml), 'the notice cannot trigger writes');
  // Read-only code: no request, no key handling, no HTML injection, no config write.
  for (const banned of ['fetch(', 'XMLHttpRequest', 'innerHTML', "'/api", 'server-key', 'authKey', 'AUTH_KEY', 'method:', 'POST', 'loopbackOnly =']) {
    assert(!noticeCodeOnly.includes(banned), 'notice code must not contain ' + banned);
  }

  // 1. A status without listen fields (older server) is not guessed at.
  let p = page();
  p.c.fetch = async () => response(status({}));
  await p.c.loadStats();
  assert.equal(p.nodes['listen-plan-notice'].hidden, true, 'nothing to say, nothing shown');
  assert.equal((p.nodes['listen-plan-headline'] || {}).textContent || '', '');
  assert.equal(p.nodes['cred-lan-url'].value, 'http://192.168.1.10:3425/v1', 'LAN address unchanged');

  // 2. The shipped wildcard listener needs no notice at all.
  p.c.fetch = async () => response(status({
    listenAddress: '0.0.0.0', configuredHost: '0.0.0.0', configuredHostApplied: true,
    configuredLoopbackOnly: false, configuredPort: 3425, configuredPortApplied: true,
    listenActionRequired: false, listenAction: ''
  }));
  await p.c.loadStats();
  assert.equal(p.nodes['listen-plan-notice'].hidden, true, 'applied wildcard stays quiet');
  assert.equal(p.nodes['listen-plan-notice'].attrs['data-tone'], undefined);

  // 3. A plan that only takes effect after a restart names the real endpoint.
  p.c.fetch = async () => response(status({
    listenAddress: '127.0.0.1', configuredHost: '', configuredHostApplied: true,
    configuredLoopbackOnly: false, configuredPort: 3425, configuredPortApplied: true,
    listenActionRequired: true, listenAction: 'restart',
    listenNote: 'takes effect after the gateway restarts'
  }));
  await p.c.loadStats();
  let box = p.nodes['listen-plan-notice'];
  assert.equal(box.hidden, false);
  assert.equal(box.attrs['data-tone'], 'warn');
  assert.equal(p.nodes['listen-plan-headline'].textContent, '监听设置尚未生效');
  let detail = p.nodes['listen-plan-detail'].textContent;
  assert(detail.includes('重启网关后生效'), detail);
  assert(detail.includes('不会替你切换连接或口令'), detail);
  assert(!detail.includes('takes effect after'), 'raw English note is not the message: ' + detail);
  let addr = p.nodes['listen-plan-address'].textContent;
  assert(addr.includes('仅本机（127.0.0.1:3425）'), addr);
  assert(addr.includes('http://127.0.0.1:3425/v1'), addr);
  assert(addr.includes('局域网不可用（只监听本机）'), addr);
  assert.equal(p.nodes['cred-lan-url'].value, '', 'no fabricated LAN address while on loopback');
  assert(p.nodes['cred-lan-url'].placeholder.includes('只监听本机'), 'LAN field explains why it is empty');

  // 4. Damaged configuration: loopback is fail-closed, and recovery is named.
  p.c.fetch = async () => response(status({
    listenAddress: '127.0.0.1', configuredHost: '', configuredHostApplied: false,
    configuredLoopbackOnly: false, configuredPort: 3425, configuredPortApplied: true,
    listenActionRequired: true, listenAction: 'recover-config',
    listenNote: 'configuration unreadable: loopback only until it is recovered',
    configRecovery: { required: true, writesBlocked: true }
  }));
  await p.c.loadStats();
  assert.equal(p.nodes['listen-plan-headline'].textContent, '配置文件损坏，网关已临时只监听本机');
  detail = p.nodes['listen-plan-detail'].textContent;
  assert(detail.includes('恢复配置'), detail);
  assert(detail.includes('保存都会被拒绝'), detail);
  assert(detail.includes('恢复完成后再重启网关'), detail);
  assert(!detail.includes('configuration unreadable'), 'raw English note is not the message');
  assert(p.nodes['listen-plan-address'].textContent.includes('仅本机（127.0.0.1:3425）'));

  // 5. A loopback request in the config is reported, never silently applied.
  p.c.fetch = async () => response(status({
    listenAddress: '0.0.0.0', configuredHost: '127.0.0.1', configuredHostApplied: false,
    configuredLoopbackOnly: false, configuredPort: 3425, configuredPortApplied: true,
    listenActionRequired: true, listenAction: 'enable-loopback',
    listenNote: 'server.host=127.0.0.1 is not honoured'
  }));
  await p.c.loadStats();
  assert.equal(p.nodes['listen-plan-headline'].textContent, '配置里的「仅本机」尚未生效');
  detail = p.nodes['listen-plan-detail'].textContent;
  assert(detail.includes('127.0.0.1'), detail);
  assert(detail.includes('server.loopbackOnly=true'), detail);
  assert(detail.includes('没有擅自收紧'), detail);
  assert(detail.includes('不会自动改动'), detail);
  assert.equal(p.nodes['cred-lan-url'].value, 'http://192.168.1.10:3425/v1', 'still on the LAN, as before');

  // 6. Hosts that cannot be honoured are named verbatim, as text.
  p.c.fetch = async () => response(status({
    listenAddress: '0.0.0.0', configuredHost: '::1', configuredHostApplied: false,
    configuredPort: 3425, configuredPortApplied: true,
    listenActionRequired: true, listenAction: 'unsupported-host', listenNote: 'ipv6 loopback would strand the bridge'
  }));
  await p.c.loadStats();
  assert.equal(p.nodes['listen-plan-headline'].textContent, '配置里的监听地址不会被采用');
  detail = p.nodes['listen-plan-detail'].textContent;
  assert(detail.includes('::1'), detail);
  assert(detail.includes('然后重启网关'), detail);
  // Hostile config values stay inert text; innerHTML is never used.
  p.c.fetch = async () => response(status({
    listenAddress: '0.0.0.0', configuredHost: '<img src=x onerror=alert(1)>',
    configuredHostApplied: false, configuredPort: 3425, configuredPortApplied: true,
    listenActionRequired: true, listenAction: 'unsupported-host'
  }));
  await p.c.loadStats();
  assert(p.nodes['listen-plan-detail'].textContent.includes('<img src=x onerror=alert(1)>'));
  assert.equal(p.nodes['listen-plan-detail'].innerHTML, '', 'hostile host stays text-only: innerHTML is never written');

  // 7. A configured port is reported as fixed, not switched.
  p.c.fetch = async () => response(status({
    listenAddress: '0.0.0.0', configuredHost: '0.0.0.0', configuredHostApplied: true,
    configuredPort: 8080, configuredPortApplied: false,
    listenActionRequired: true, listenAction: 'port-fixed', listenNote: 'port is fixed at 3425'
  }));
  await p.c.loadStats();
  assert.equal(p.nodes['listen-plan-headline'].textContent, '配置里的端口不会生效');
  detail = p.nodes['listen-plan-detail'].textContent;
  assert(detail.includes('8080') && detail.includes('3425'), detail);
  assert(detail.includes('失联'), detail);
  assert(p.nodes['listen-plan-address'].textContent.includes('所有网卡（0.0.0.0:3425）'));

  // 8. An unrecognised action still tells the truth and quotes the server note.
  p.c.fetch = async () => response(status({
    listenAddress: '0.0.0.0', configuredHost: '10.0.0.5', configuredHostApplied: false,
    configuredPort: 3425, configuredPortApplied: true,
    listenActionRequired: true, listenAction: 'future-action', listenNote: 'only server.loopbackOnly can narrow the listener'
  }));
  await p.c.loadStats();
  assert.equal(p.nodes['listen-plan-headline'].textContent, '监听设置与当前实际监听不一致');
  detail = p.nodes['listen-plan-detail'].textContent;
  assert(detail.includes('future-action'), detail);
  assert(detail.includes('服务端说明：only server.loopbackOnly can narrow the listener'), detail);

  // 9. Explicit loopback-only is applied: informational, with the way back.
  p.c.fetch = async () => response(status({
    listenAddress: '127.0.0.1', configuredHost: '', configuredHostApplied: true,
    configuredLoopbackOnly: true, configuredPort: 3425, configuredPortApplied: true,
    listenActionRequired: false, listenAction: ''
  }));
  await p.c.loadStats();
  box = p.nodes['listen-plan-notice'];
  assert.equal(box.hidden, false);
  assert.equal(box.attrs['data-tone'], 'info');
  assert.equal(p.nodes['listen-plan-headline'].textContent, '当前只监听本机（127.0.0.1）');
  detail = p.nodes['listen-plan-detail'].textContent;
  assert(detail.includes('局域网其他设备无法访问'), detail);
  assert(detail.includes('server.loopbackOnly 改回 false'), detail);
  assert(detail.includes('然后重启网关'), detail);
  assert.equal(p.nodes['cred-lan-url'].value, '');

  // 10. The same snapshot does not rewrite the DOM on every 3s poll.
  let writes = watch(p.nodes['listen-plan-headline']);
  await p.c.loadStats();
  await p.c.loadStats();
  assert.equal(writes(), 0, 'unchanged listen plan is not re-rendered');

  // 11. Losing the gateway hides the notice instead of leaving a stale claim.
  p.c.fetch = async () => { throw Error('offline'); };
  await p.c.loadStats();
  assert.equal(p.nodes['listen-plan-notice'].hidden, true);
  assert.equal(p.c.listenPlanNoticeState, null);

  // 12. listenActionRequired absent but the host is not applied still speaks up.
  p = page();
  p.c.fetch = async () => response(status({
    listenAddress: '0.0.0.0', configuredHost: '192.168.1.10', configuredHostApplied: false,
    configuredPort: 3425, configuredPortApplied: true, listenAction: 'unsupported-host'
  }));
  await p.c.loadStats();
  assert.equal(p.nodes['listen-plan-notice'].hidden, false, 'unapplied host is surfaced without the flag');
  assert(p.nodes['listen-plan-detail'].textContent.includes('192.168.1.10'));

  // 13. Unknown port means no fabricated endpoint at all.
  p = page();
  p.c.fetch = async () => response(status({
    port: 0, listenAddress: '127.0.0.1', listenActionRequired: true, listenAction: 'recover-config'
  }));
  await p.c.loadStats();
  assert.equal(p.nodes['listen-plan-notice'].hidden, true, 'no endpoint, no claim');
  assert.equal(p.c.listenPlanNoticeState, null);

  // 14. Reading the page never touches the gateway key or the chosen address.
  assert(!noticeCodeOnly.includes('fetch('));
  assert(!/\.value\s*=/.test(noticeCodeOnly), 'the notice writes no field value');
  assert(html.includes('id="listen-plan-notice" data-tone') === false);
  // ---- 回环模式下「未设口令却显示局域网可用」与「复制 LAN 地址说没检测到 IP」的矛盾。
  // 服务端契约：listenAddress=127.0.0.1 时局域网根本不可达；缺少地址时也要说清原因。
  const LOOPBACK_STATUS = {
    listenAddress: '127.0.0.1', configuredHost: '', configuredHostApplied: true,
    configuredLoopbackOnly: true, configuredPort: 3425, configuredPortApplied: true,
    listenActionRequired: false, listenAction: ''
  };
  const WILDCARD_STATUS = {
    listenAddress: '0.0.0.0', configuredHost: '0.0.0.0', configuredHostApplied: true,
    configuredLoopbackOnly: false, configuredPort: 3425, configuredPortApplied: true,
    listenActionRequired: false, listenAction: ''
  };

  // 正例 1：只监听本机 + 未设口令 → 不能说「局域网内任何人可用」，复制 LAN 地址要说清原因。
  p = page();
  p.c.window.AllInNative = { copyManagementPanelLink: () => false };
  p.c.fetch = async () => response(status(LOOPBACK_STATUS));
  await p.c.loadStats();
  let keyState = p.nodes['cred-key-state'].innerHTML;
  assert(keyState.includes('未设口令'), keyState);
  assert(!keyState.includes('局域网内任何人可用'), 'loopback listener is not reachable from the LAN: ' + keyState);
  assert(keyState.includes('当前只监听本机') && keyState.includes('局域网设备无法连接'), keyState);
  assert.equal(p.nodes['cred-lan-url'].value, '', 'no LAN address while on loopback');
  assert.equal(p.nodes['cred-lan-url'].placeholder,
    '当前只监听本机（127.0.0.1），局域网设备无法连接；要恢复局域网访问请把 server.loopbackOnly 改回 false 并重启网关');
  p.c.copyCredential('lan');
  assert.equal(p.copied.length, 0, 'no address is copied while on loopback');
  assert.equal(p.toasts.length, 1);
  assert(p.toasts[0].includes('当前只监听本机'), p.toasts[0]);
  assert(!p.toasts[0].includes('未检测到可用的局域网 IPv4 地址'),
    'the copy toast must not blame a missing IP: ' + p.toasts[0]);
  p.c.copyManagementPanelLink();
  assert.equal(p.toasts.length, 2);
  assert(p.toasts[1].includes('只能在手机上打开'), p.toasts[1]);
  assert(!p.toasts[1].includes('检查网关和局域网连接'),
    'a loopback listener is not a network fault: ' + p.toasts[1]);
  assert(p.nodes['listen-plan-address'].textContent.includes('局域网不可用（只监听本机）'));

  // 正例 2：回环 + 有口令 → 仍是「已启用鉴权」，不因回环改写口令状态。
  p = page();
  p.c.fetch = async () => response(status(LOOPBACK_STATUS));
  vm.runInContext("currentAuthKey='mp-synthetic'", p.c);
  await p.c.loadStats();
  p.c.renderCredentials();
  assert(p.nodes['cred-key-state'].innerHTML.includes('已启用鉴权'), p.nodes['cred-key-state'].innerHTML);

  // 正例 3：重启还没生效、socket 仍可能已是回环 —— 以实际监听为准，同样不吹局域网。
  p = page();
  p.c.fetch = async () => response(status({
    listenAddress: '127.0.0.1', configuredHost: '127.0.0.1', configuredHostApplied: false,
    configuredLoopbackOnly: false, configuredPort: 3425, configuredPortApplied: true,
    listenActionRequired: true, listenAction: 'enable-loopback'
  }));
  await p.c.loadStats();
  assert(p.nodes['cred-key-state'].innerHTML.includes('当前只监听本机'), p.nodes['cred-key-state'].innerHTML);
  assert(!p.nodes['cred-key-state'].innerHTML.includes('局域网内任何人可用'));

  // 反例 1：通配监听 + 未设口令 → 保留原有告警文案，不被回环分支误伤。
  p = page();
  p.c.fetch = async () => response(status(WILDCARD_STATUS));
  await p.c.loadStats();
  keyState = p.nodes['cred-key-state'].innerHTML;
  assert(keyState.includes('未设口令（局域网内任何人可用）'), keyState);
  assert(!keyState.includes('只监听本机'), keyState);

  // 反例 2：通配监听 + 有局域网地址 → 复制 LAN 地址照旧成功，提示为「已复制」。
  p = page();
  p.c.fetch = async () => response(status(WILDCARD_STATUS));
  await p.c.loadStats();
  assert.equal(p.nodes['cred-lan-url'].value, 'http://192.168.1.10:3425/v1');
  assert.equal(p.nodes['cred-lan-url'].placeholder, '未检测到可用的局域网 IPv4 地址');
  p.c.copyCredential('lan');
  assert.deepEqual(p.copied, [{ text: 'http://192.168.1.10:3425/v1', ok: '✅ API 地址已复制' }]);
  assert.equal(p.toasts.length, 0);

  // 反例 3：通配监听但确实没有可用局域网 IPv4 → 仍说「未检测到」，不冒充回环原因。
  p = page();
  p.c.window.AllInNative = { copyManagementPanelLink: () => false };
  p.c.fetch = async () => response(status({ ...WILDCARD_STATUS, ip: '100.64.0.1', lanBaseUrl: '' }));
  await p.c.loadStats();
  assert.equal(p.nodes['cred-lan-url'].value, '');
  assert.equal(p.nodes['cred-lan-url'].placeholder, '未检测到可用的局域网 IPv4 地址');
  p.c.copyCredential('lan');
  assert.equal(p.toasts[0], '未检测到可用的局域网 IPv4 地址');
  p.c.copyManagementPanelLink();
  assert(p.toasts[1].includes('请检查网关和局域网连接'), p.toasts[1]);

  // 反例 4：状态读取失败 → 不保留回环结论，回到中性文案，且不报错。
  p = page();
  p.c.fetch = async () => response(status(LOOPBACK_STATUS));
  await p.c.loadStats();
  p.c.fetch = async () => { throw Error('offline'); };
  await p.c.loadStats();
  assert.equal(p.c.listenPlanNoticeState, null);
  assert(p.nodes['cred-lan-url'].placeholder === '' || p.nodes['cred-lan-url'].placeholder === '未检测到可用的局域网 IPv4 地址');
  assert(p.nodes['cred-key-state'].innerHTML.includes('未设口令（局域网内任何人可用）'),
    'unknown listener state must not claim it is loopback-only');

  console.log('listen plan notice: real endpoint, restart/recovery guidance, read-only, no switches PASS');
})().catch(e => { console.error(e); process.exitCode = 1; });
