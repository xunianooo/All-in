#!/usr/bin/env node
'use strict';
/**
 * scripts/beta-dashboard-xss.cjs — 面板 XSS 定向验证（隔离合成 payload，不联网、不读真实配置、不跑 Gradle）
 *
 * 目标：证明 dashboard.html 里「不可信字符串 → innerHTML」的几处出口按上下文做了转义，
 * 且可见文本/可点击元素/模型 ID 回写没有被改坏。
 *
 * 做法：
 *   1) 从 dashboard.html 里按名字抽取真实函数（esc / jsAttr / renderGroups / showDupAccountModal /
 *      Codex 四个函数），在 vm 里配最小 DOM + fetch 桩执行，拿到真实的 innerHTML 产物；
 *   2) 用一个严格的 HTML 分词器把产物拆成标签/属性/文本，断言：
 *      - 只出现白名单标签，payload 里的 <img>/<svg>/<script> 一个都没变成元素；
 *      - 带 on* 属性的元素数量与静态模板一致，且 on* 属性值里不含 payload 原文；
 *      - 文本节点的解码值里能看到 payload 原文（说明只是转义，不是丢掉/改写）；
 *      - 属性上下文（title / value / optgroup label / option value）解码后与原文逐字相等；
 *      - 注入的 canary() 一次都没被调用；管理标记（window.__adminWrite / globalThis.adminMark）
 *        没有被写入。
 *   3) 静态兜底：这些函数体内不允许再出现未转义的拼接写法。
 *
 * 用法：
 *   node scripts/beta-dashboard-xss.cjs [dashboard.html 路径]
 * 退出码：0 = 全部通过；1 = 发现违例（会把每一条打出来）。
 */
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const args = process.argv.slice(2);
const dump = args.includes('--dump');
const htmlPath = args.filter(a => a !== '--dump')[0] || path.join(process.cwd(), 'app/src/main/assets/dashboard.html');
const html = fs.readFileSync(htmlPath, 'utf8');

const violations = [];
function bad(where, detail) {
  violations.push(where + ' :: ' + detail);
}
function ok(cond, where, detail) {
  if (!cond) bad(where, detail);
}

/* ------------------------------------------------------------------ *
 * 1. 抽取真实函数源码（用花括号配平，跳过字符串 / 注释 / 正则字面量）
 * ------------------------------------------------------------------ */
function skipString(src, i) {
  const q = src[i];
  i++;
  while (i < src.length) {
    const c = src[i];
    if (c === '\\') { i += 2; continue; }
    if (c === q) return i + 1;
    i++;
  }
  return i;
}
function skipRegex(src, i) {
  i++; // 跳过开头的 /
  let inClass = false;
  while (i < src.length) {
    const c = src[i];
    if (c === '\\') { i += 2; continue; }
    if (c === '[') inClass = true;
    else if (c === ']') inClass = false;
    else if (c === '/' && !inClass) { i++; break; }
    i++;
  }
  while (i < src.length && /[a-z]/i.test(src[i])) i++; // flags
  return i;
}
function readFunction(src, name) {
  const decl = 'function ' + name + '(';
  const start = src.indexOf(decl);
  if (start < 0) throw new Error('找不到函数 ' + name);
  // async 函数要把 async 一起带出来，否则体里的 await 会编译失败
  const before = src.slice(Math.max(0, start - 8), start);
  const asyncMatch = /async\s*$/.exec(before);
  const headStart = asyncMatch ? start - asyncMatch[0].length : start;
  let i = src.indexOf('{', start);
  if (i < 0) throw new Error('函数 ' + name + ' 缺少函数体');
  let depth = 0;
  let prev = '(';
  while (i < src.length) {
    const c = src[i];
    if (c === '/' && src[i + 1] === '/') { const n = src.indexOf('\n', i); i = n < 0 ? src.length : n; continue; }
    if (c === '/' && src[i + 1] === '*') { const n = src.indexOf('*/', i + 2); i = n < 0 ? src.length : n + 2; continue; }
    if (c === '"' || c === "'" || c === '`') { i = skipString(src, i); prev = 'str'; continue; }
    if (c === '/' && /[=(,:;[!&|?{};+\-*%<>~^]/.test(prev)) { i = skipRegex(src, i); prev = 're'; continue; }
    if (c === '{') depth++;
    else if (c === '}') { depth--; if (depth === 0) return src.slice(headStart, i + 1); }
    if (!/\s/.test(c)) prev = c;
    i++;
  }
  throw new Error('函数 ' + name + ' 花括号不配平');
}

// groupEsc 是 renderGroups 自带实现的转义入口：修复前的版本没有它，缺失时只记提示，
// 这样同一份 harness 也能跑在修复前的基线上（基线会因为没有它而报结构性问题）。
const WANTED = ['esc', 'jsAttr', 'fmtDupTime', 'visibleProviders', 'providerHasCredential',
  'selectableModels', 'groupEsc', 'renderGroups', 'showDupAccountModal',
  'codexOAuthLogin', 'pollCodexFlow', 'submitCodexCode', 'checkCodexUsage'];
const OPTIONAL = new Set(['groupEsc']);
const fnSources = {};
for (const name of WANTED) {
  try { fnSources[name] = readFunction(html, name); }
  catch (e) { if (!OPTIONAL.has(name)) bad('抽取', e.message); }
}

/* ------------------------------------------------------------------ *
 * 2. 最小 DOM / 网络桩
 * ------------------------------------------------------------------ */
function FakeEl(tag) {
  this.tagName = String(tag || 'div').toUpperCase();
  this.style = { cssText: '' };
  this.dataset = {};
  this.children = [];
  this._html = '';
  this.innerText = '';
  this.textContent = '';
  this.className = '';
  this.hidden = false;
  this.disabled = false;
}
Object.defineProperty(FakeEl.prototype, 'innerHTML', {
  get() { return this._html; },
  set(v) { this._html = String(v); }
});
FakeEl.prototype.appendChild = function (child) {
  this.children.push(child);
  this._html += (child && child._html) || '';
  return child;
};
FakeEl.prototype.setAttribute = function (k, v) { this['attr_' + k] = v; };
FakeEl.prototype.getAttribute = function (k) { return this['attr_' + k]; };
FakeEl.prototype.remove = function () {};

function makeDocument(ids) {
  const map = new Map();
  const doc = {
    ids: map,
    getElementById(id) { return map.has(id) ? map.get(id) : null; },
    createElement(tag) { return new FakeEl(tag); },
    querySelectorAll() { return []; },
    addEventListener() {},
    body: new FakeEl('body'),
  };
  for (const id of Object.keys(ids)) map.set(id, ids[id]);
  return doc;
}
function el(extra) {
  const e = new FakeEl('div');
  if (extra) Object.assign(e, extra);
  return e;
}

/* ------------------------------------------------------------------ *
 * 3. 严格 HTML 分词器 + 实体解码
 * ------------------------------------------------------------------ */
const ENTITIES = { amp: '&', lt: '<', gt: '>', quot: '"', apos: "'", nbsp: '\u00a0', '#39': "'" };
function decodeEntities(s) {
  return String(s).replace(/&(#x?[0-9a-fA-F]+|[a-zA-Z]+);/g, (m, body) => {
    if (body[0] === '#') {
      const hex = body[1] === 'x' || body[1] === 'X';
      const code = parseInt(hex ? body.slice(2) : body.slice(1), hex ? 16 : 10);
      return Number.isFinite(code) ? String.fromCodePoint(code) : m;
    }
    return Object.prototype.hasOwnProperty.call(ENTITIES, body) ? ENTITIES[body] : m;
  });
}

function tokenize(markup) {
  const out = [];
  let i = 0;
  let buf = '';
  const flush = () => { if (buf) { out.push({ type: 'text', raw: buf }); buf = ''; } };
  while (i < markup.length) {
    const c = markup[i];
    if (c === '<') {
      if (markup.startsWith('<!--', i)) { const e = markup.indexOf('-->', i + 4); i = e < 0 ? markup.length : e + 3; continue; }
      if (markup[i + 1] === '/') {
        const e = markup.indexOf('>', i);
        if (e < 0) { buf += c; i++; continue; }
        flush();
        out.push({ type: 'tag', closing: true, name: markup.slice(i + 2, e).trim().toLowerCase(), attrs: [] });
        i = e + 1;
        continue;
      }
      if (/[a-zA-Z]/.test(markup[i + 1] || '')) {
        let j = i + 1;
        let name = '';
        while (j < markup.length && /[a-zA-Z0-9:_-]/.test(markup[j])) { name += markup[j]; j++; }
        const attrs = [];
        let selfClosing = false;
        let closed = false;
        while (j < markup.length) {
          while (j < markup.length && /\s/.test(markup[j])) j++;
          if (markup[j] === '>') { j++; closed = true; break; }
          if (markup[j] === '/' && markup[j + 1] === '>') { j += 2; selfClosing = true; closed = true; break; }
          let an = '';
          while (j < markup.length && !/[\s=>]/.test(markup[j])) { an += markup[j]; j++; }
          while (j < markup.length && /\s/.test(markup[j])) j++;
          let av = null;
          if (markup[j] === '=') {
            j++;
            while (j < markup.length && /\s/.test(markup[j])) j++;
            const q = markup[j];
            if (q === '"' || q === "'") {
              j++;
              let v = '';
              while (j < markup.length && markup[j] !== q) { v += markup[j]; j++; }
              j++;
              av = v;
            } else {
              let v = '';
              while (j < markup.length && !/[\s>]/.test(markup[j])) { v += markup[j]; j++; }
              av = v;
            }
          }
          if (an) attrs.push({ name: an.toLowerCase(), raw: av });
        }
        flush();
        out.push({ type: 'tag', closing: false, name: name.toLowerCase(), attrs, selfClosing, closed });
        i = j;
        continue;
      }
      buf += c;
      i++;
      continue;
    }
    buf += c;
    i++;
  }
  flush();
  return out;
}

const SAFE_TAGS = new Set(['div', 'span', 'b', 'i', 'code', 'small', 'select', 'option', 'optgroup',
  'input', 'button', 'details', 'summary', 'br', 'strong', 'p', 'label']);
const HOSTILE_TAGS = new Set(['script', 'svg', 'iframe', 'object', 'embed', 'math', 'style', 'link',
  'meta', 'base', 'form', 'textarea', 'template', 'noscript', 'body', 'html', 'video', 'audio', 'a']);

/**
 * 通用产物审计：返回 tokens，并把结构性问题记进 violations。
 * opts.inlineHandlers = 允许出现的 onclick 值前缀（静态模板自带的那几个）
 */
function auditMarkup(where, markup, payloads, opts) {
  const o = opts || {};
  const allowHandlers = o.allowHandlers || [];
  const tokens = tokenize(markup);
  const handlers = [];
  for (const tk of tokens) {
    if (tk.type === 'text') {
      if (tk.raw.indexOf('<') >= 0) bad(where, '文本节点里出现裸 < ：' + tk.raw.slice(0, 60));
      continue;
    }
    if (tk.name && HOSTILE_TAGS.has(tk.name)) bad(where, '出现危险标签 <' + tk.name + '>（payload 变成了元素）');
    if (!SAFE_TAGS.has(tk.name)) bad(where, '出现白名单外的标签 <' + tk.name + '>');
    for (const a of tk.attrs) {
      if (/^on/i.test(a.name)) {
        const val = decodeEntities(a.raw == null ? '' : a.raw);
        handlers.push({ name: a.name, value: val });
        const allowed = allowHandlers.some(p => val.indexOf(p) === 0);
        if (!allowed) bad(where, '出现了预期外的内联事件属性 ' + a.name + '=' + val.slice(0, 80));
        for (const p of payloads) {
          if (p.indexOf("'") >= 0 && val.indexOf(p) >= 0) bad(where, '事件属性值里出现 payload 原文：' + a.name);
        }
      }
    }
  }
  for (const p of payloads) {
    if (/[<>"']/.test(p) && markup.indexOf(p) >= 0) {
      bad(where, '产物里出现了未转义的 payload 原文：' + p);
    }
  }
  return { tokens, handlers };
}
function textOf(tokens) {
  return tokens.filter(t => t.type === 'text').map(t => decodeEntities(t.raw)).join('');
}
function findTags(tokens, name, attrName, attrValue) {
  return tokens.filter(t => t.type === 'tag' && !t.closing && t.name === name).filter(t => {
    if (!attrName) return true;
    const a = t.attrs.find(x => x.name === attrName);
    return a && decodeEntities(a.raw == null ? '' : a.raw) === attrValue;
  });
}
function attrValue(tag, name) {
  const a = tag.attrs.find(x => x.name === name);
  return a ? decodeEntities(a.raw == null ? '' : a.raw) : null;
}

/* ------------------------------------------------------------------ *
 * 4. 合成 payload（隔离数据，不含任何真实账号/凭据/配置）
 * ------------------------------------------------------------------ */
const MARK = 'AIBETAMARK';
const P = {
  img: '<img src=x onerror=canary()>',
  dq: '" onerror="canary()" x="',
  sq: "' onmouseover='canary()",
  close: '</span><script>canary()<' + '/script>',
  entity: '&lt;img src=x onerror=canary()&gt;',
  svg: '<svg/onload=canary()>',
};
const ALL_PAYLOADS = Object.keys(P).map(k => P[k]);

/* ------------------------------------------------------------------ *
 * 5. 场景
 * ------------------------------------------------------------------ */
const canaryCalls = [];
const sandboxes = [];
const log = { alerts: [], toasts: [], opened: [], fetchCalls: [] };

function buildContext(extra) {
  const ids = Object.assign({
    'groups-container': el(),
    'dup-account-body': el(),
    'dup-account-modal': el({ style: { display: 'none' } }),
    'dup-btn-cancel': el({ focus() {} }),
    'codex-status': el(),
    'codex-manual-code': el({ value: 'synthetic-code' }),
  }, (extra && extra.ids) || {});
  const doc = makeDocument(ids);
  const intervals = [];
  let fetchQueue = [];
  const win = {
    location: { href: '' },
    open(url, target) { log.opened.push([url, target]); return { closed: false }; },
    addEventListener() {},
    AllInNative: null,
  };
  const sandbox = {
    document: doc,
    window: win,
    history: { pushState() {}, back() {} },
    console: { log() {}, warn() {}, error() {} },
    alert: m => log.alerts.push(String(m)),
    showToast: m => log.toasts.push(String(m)),
    loadStatus: () => {},
    loadConfig: () => {},
    loadRoutingState: () => {},
    syncGroupDraft: () => {},
    groupsSig: '',
    groupDraft: null,
    appConfig: null,
    groupsFingerprint: () => 'synthetic-sig',
    dupHistoryPushed: false,
    pendingDupRetry: null,
    getProviderName: (id) => 'provider:' + id,
    API_BASE: '',
    canary: () => { canaryCalls.push(1); throw new Error('payload 被执行了'); },
    fetch: (url, init) => {
      log.fetchCalls.push(String(url));
      const next = fetchQueue.shift();
      if (!next) return Promise.reject(new Error('测试桩：没有更多响应'));
      return Promise.resolve(next);
    },
    setInterval: (fn) => { intervals.push(fn); return intervals.length; },
    clearInterval: () => {},
    setTimeout: (fn) => { return 1; },
    __intervals: intervals,
    __ids: ids,
    __queue: (list) => { fetchQueue = list.slice(); },
  };
  sandboxes.push(sandbox);
  const ctx = vm.createContext(sandbox);
  const present = WANTED.filter(n => fnSources[n]);
  const program = present.map(n => fnSources[n]).join('\n\n')
    + '\n;globalThis.__api = {' + present.join(',') + '};';
  vm.runInContext(program, ctx, { filename: 'dashboard-extract.js' });
  return { ctx, sandbox, ids, doc, win, intervals };
}

function freshSandbox() {
  log.alerts.length = 0; log.toasts.length = 0; log.opened.length = 0; log.fetchCalls.length = 0;
  return buildContext();
}

/* ---- 场景 A：renderGroups（targets / 组名 / 组 ID / optgroup / option） ---- */
function scenarioRenderGroups() {
  const s = freshSandbox();
  const { ctx, sandbox } = s;
  const providerId = 'pv1';
  const payloadModel = providerId + '/' + P.svg;
  const targetPayload = 'pv1/' + P.img;
  const ghostTarget = 'ghost/' + P.dq;
  sandbox.appConfig = {
    providers: [{
      id: providerId,
      name: P.img,
      status: 'ok',
      accounts: [{ token: 'synthetic' }],
      models: [P.svg],
      __models: [P.svg],
    }],
    groups: [],
  };
  sandbox.pingModelsOf = p => (Array.isArray(p.__models) ? p.__models : []);
  sandbox.groupDraft = [{
    id: P.close,
    name: P.sq,
    strategy: 'order',
    targets: [targetPayload, ghostTarget],
    pick: '',
  }];

  ctx.__api.renderGroups(true, true);
  const container = s.ids['groups-container'];
  const markup = container.innerHTML;
  ok(markup.length > 0, 'renderGroups', '没有产出任何 HTML（函数被改坏了？）');
  const { tokens } = auditMarkup('renderGroups', markup, ALL_PAYLOADS, { allowHandlers: [] });

  // 组名：input value 属性解码后必须等于原文（转义不是改写）
  const rename = findTags(tokens, 'input', 'data-action', 'rename');
  ok(rename.length === 1, 'renderGroups', 'rename 输入框数量=' + rename.length);
  if (rename.length) ok(attrValue(rename[0], 'value') === P.sq, 'renderGroups', '组名 value 被改写：' + attrValue(rename[0], 'value'));

  // 目标行：title 属性 + 文本都要还原
  const spans = tokens.filter(t => t.type === 'tag' && !t.closing && t.name === 'span' && attrValue(t, 'title') !== null);
  const titles = spans.map(t => attrValue(t, 'title'));
  ok(titles.some(x => x && x.indexOf(P.img) >= 0), 'renderGroups', '目标 title 属性丢失/被改写：' + JSON.stringify(titles));
  ok(textOf(tokens).indexOf(P.img) >= 0, 'renderGroups', '目标文本里看不到 payload 原文');
  ok(textOf(tokens).indexOf(P.dq) >= 0, 'renderGroups', '第二个目标文本里看不到 payload 原文');

  // 组 ID 文本
  ok(textOf(tokens).indexOf('group/' + P.close) >= 0, 'renderGroups', 'group/<id> 文本被改写');

  // 下拉：option value/文本 + optgroup label
  const opts = tokens.filter(t => t.type === 'tag' && !t.closing && t.name === 'option');
  const optVals = opts.map(t => attrValue(t, 'value'));
  ok(optVals.indexOf(payloadModel) >= 0, 'renderGroups', 'option value 被改写：' + JSON.stringify(optVals));
  ok(textOf(tokens).indexOf(payloadModel) >= 0, 'renderGroups', 'option 文本里看不到模型 ID 原文');
  const groups = tokens.filter(t => t.type === 'tag' && !t.closing && t.name === 'optgroup');
  ok(groups.length === 1, 'renderGroups', 'optgroup 数量=' + groups.length);
  if (groups.length) ok(attrValue(groups[0], 'label') === P.img, 'renderGroups', 'optgroup label 被改写：' + attrValue(groups[0], 'label'));

  // 纯渲染不应触发任何请求：不可信文本不能驱动管理写入
  ok(log.fetchCalls.length === 0, 'renderGroups', '渲染期间发起了请求：' + JSON.stringify(log.fetchCalls));

  // 交互结构没被破坏：排序/删除按钮仍在
  const actions = tokens.filter(t => t.type === 'tag').map(t => attrValue(t, 'data-action')).filter(Boolean);
  for (const want of ['up', 'down', 'del', 'delgroup', 'strategy', 'add']) {
    ok(actions.indexOf(want) >= 0, 'renderGroups', '缺少交互节点 data-action=' + want);
  }
  return markup;
}

/* ---- 场景 B：重复账号弹窗 ---- */
function scenarioDupModal() {
  const s = freshSandbox();
  const { ctx } = s;
  ctx.__api.showDupAccountModal('pv1', {
    providerName: P.img,
    existing: { name: P.dq, addedAt: 1700000000000 },
  }, () => {});
  const body = s.ids['dup-account-body'];
  const markup = body.innerHTML;
  ok(markup.length > 0, 'dupModal', '弹窗正文没有渲染');
  const { tokens } = auditMarkup('dupModal', markup, ALL_PAYLOADS, { allowHandlers: [] });
  const text = textOf(tokens);
  ok(text.indexOf(P.img) >= 0, 'dupModal', '供应商名没显示出来');
  ok(text.indexOf(P.dq) >= 0, 'dupModal', '账号名没显示出来');
  ok(/池内已经有同名同账号/.test(text), 'dupModal', '提示文案被改坏了');
  ok(/入池时间/.test(text), 'dupModal', '入池时间丢了');
  ok(s.ids['dup-account-modal'].style.display === 'flex', 'dupModal', '弹窗没有被打开');
  ok(log.fetchCalls.length === 0, 'dupModal', '弹窗渲染期间发起了请求：' + JSON.stringify(log.fetchCalls));
  return markup;
}

/* ---- 场景 C：Codex 授权状态（地区提示 + flowId 内联属性） ---- */
async function scenarioCodex() {
  const out = {};
  // C1 地区受限分支（regionWarning 直接进 innerHTML）
  {
    const s = freshSandbox();
    const { ctx, sandbox } = s;
    sandbox.__queue([{ ok: true, json: async () => ({ ok: true, authUrl: 'https://example.invalid/oauth', flowId: 'flow-1', regionBlocked: true, regionWarning: P.img }) }]);
    await ctx.__api.codexOAuthLogin();
    const markup = s.ids['codex-status'].innerHTML;
    out.c1 = markup;
    ok(markup.length > 0, 'codex.regionBlocked', '状态区没有渲染');
    const { tokens } = auditMarkup('codex.regionBlocked', markup, ALL_PAYLOADS, { allowHandlers: ['codexOAuthLogin()'] });
    ok(textOf(tokens).indexOf(P.img) >= 0, 'codex.regionBlocked', '地区提示原文没显示出来');
    ok(log.opened.length === 0, 'codex.regionBlocked', '地区受限时不该打开浏览器');
    ok(/当前网络无法完成 Codex 授权/.test(textOf(tokens)), 'codex.regionBlocked', '提示文案被改坏了');
  }
  // C2 正常分支：flowId 进内联 onclick，regionWarning 进 tip
  {
    const s = freshSandbox();
    const { ctx, sandbox } = s;
    const flowId = P.sq + '\\tail';
    sandbox.__queue([{ ok: true, json: async () => ({ ok: true, authUrl: 'https://example.invalid/oauth', flowId, regionWarning: P.entity }) }]);
    await ctx.__api.codexOAuthLogin();
    const markup = s.ids['codex-status'].innerHTML;
    out.c2 = markup;
    const { tokens, handlers } = auditMarkup('codex.flow', markup, ALL_PAYLOADS, { allowHandlers: ['submitCodexCode('] });
    ok(log.opened.length === 1 && log.opened[0][0] === 'https://example.invalid/oauth', 'codex.flow', '授权链接没有原样打开（功能被改坏）');
    ok(textOf(tokens).indexOf(P.entity) >= 0, 'codex.flow', 'regionWarning 原文没显示出来');
    const btn = handlers.find(h => h.value.indexOf('submitCodexCode(') === 0);
    ok(!!btn, 'codex.flow', '找不到“提交完成”按钮的内联事件');
    if (btn) {
      const arg = parseSubmitCodeArg(btn.value);
      ok(arg === flowId, 'codex.flow', 'flowId 经过属性转义后取回的值不一致：' + JSON.stringify(arg));
    }
  }
  return out;
}

function parseSubmitCodeArg(value) {
  const m = /^submitCodexCode\(([\s\S]*)\)$/.exec(value);
  if (!m) return null;
  const arg = m[1];
  if (arg.length < 2) return null;
  const q = arg[0];
  if (q !== "'" && q !== '"') return null;
  if (arg[arg.length - 1] !== q) return null;
  let acc = '';
  for (let i = 1; i < arg.length - 1; i++) {
    const c = arg[i];
    if (c === '\\') { acc += arg[++i]; continue; }
    acc += c;
  }
  return acc;
}

/* ---- 场景 D：轮询与手工提交的账号名 ---- */
async function scenarioCodexPollAndSubmit() {
  // D1 pollCodexFlow 完成态
  {
    const s = freshSandbox();
    const { ctx, sandbox } = s;
    sandbox.__queue([{ ok: true, json: async () => ({ ok: true, status: 'done', account: P.img, plan: P.dq }) }]);
    ctx.__api.pollCodexFlow('flow-2');
    ok(s.intervals.length === 1, 'codex.poll', '没有启动轮询定时器');
    if (s.intervals.length) await s.intervals[0]();
    const markup = s.ids['codex-status'].innerHTML;
    const { tokens } = auditMarkup('codex.poll', markup, ALL_PAYLOADS, { allowHandlers: [] });
    const text = textOf(tokens);
    ok(text.indexOf(P.img) >= 0, 'codex.poll', '账号名没显示出来');
    ok(text.indexOf(P.dq) >= 0, 'codex.poll', '套餐名没显示出来');
    ok(log.toasts.length === 1, 'codex.poll', '成功提示没有被触发');
  }
  // D2 submitCodexCode 成功态
  {
    const s = freshSandbox();
    const { ctx, sandbox } = s;
    sandbox.__queue([{ ok: true, json: async () => ({ ok: true, account: P.sq, plan: P.entity }) }]);
    await ctx.__api.submitCodexCode('flow-3');
    ok(log.fetchCalls.length === 1 && log.fetchCalls[0].indexOf('/api/auth/codex/submit-code') >= 0, 'codex.submit', '提交请求没有发出');
    const markup = s.ids['codex-status'].innerHTML;
    const { tokens } = auditMarkup('codex.submit', markup, ALL_PAYLOADS, { allowHandlers: [] });
    const text = textOf(tokens);
    ok(text.indexOf(P.sq) >= 0, 'codex.submit', '账号名没显示出来');
    ok(text.indexOf(P.entity) >= 0, 'codex.submit', '套餐名没显示出来');
  }
  // D3 checkCodexUsage 官方额度
  {
    const s = freshSandbox();
    const { ctx, sandbox } = s;
    sandbox.__queue([{
      ok: true,
      json: async () => ({
        ok: true,
        plan_type: P.img,
        credits: { balance: P.dq },
        rate_limit: { primary_window: { used_percent: P.sq }, secondary_window: { used_percent: '5' } },
      }),
    }]);
    await ctx.__api.checkCodexUsage();
    const markup = s.ids['codex-status'].innerHTML;
    const { tokens } = auditMarkup('codex.usage', markup, ALL_PAYLOADS, { allowHandlers: [] });
    const text = textOf(tokens);
    ok(text.indexOf(P.img) >= 0, 'codex.usage', 'plan 没显示出来');
    ok(text.indexOf(P.dq) >= 0, 'codex.usage', 'credits 没显示出来');
    ok(text.indexOf(P.sq) >= 0, 'codex.usage', 'primary 百分比没显示出来');
    ok(/Secondary 窗口已用/.test(text), 'codex.usage', '次要窗口那一行被改坏了');
  }
}

/* ---- 场景 F：renderGroups 自带转义表必须与全局 esc() 逐字一致 ---- */
function scenarioEscaperParity() {
  const s = freshSandbox();
  const api = s.ctx.__api;
  if (typeof api.groupEsc !== 'function') {
    bad('escaper', 'renderGroups 用的 groupEsc() 不存在（转义入口缺失）');
    return;
  }
  const samples = ALL_PAYLOADS.concat(['plain-model-id', 'a&b<c>d"e\'f', '', null, 0]);
  for (const v of samples) {
    const a = api.groupEsc(v);
    const b = api.esc(v);
    if (a !== b) bad('escaper', 'groupEsc 与 esc 输出不一致：' + JSON.stringify(v) + ' → ' + JSON.stringify(a) + ' vs ' + JSON.stringify(b));
  }
}

/* ---- 场景 E：静态兜底（源码层面不允许再出现未转义拼接） ---- */
function scenarioStaticSource() {
  const checks = [
    ['renderGroups', /title="' \+ t \+ '"'?>' \+ t \+/],
    ['renderGroups', /'<option value="' \+ m \+ '">' \+ m \+ '<\/option>'/],
    ['renderGroups', /<optgroup label="' \+ o\.name \+ '"/],
    ['renderGroups', /value="' \+ \(g\.name \|\| ''\)\.replace/],
    ['dupModal', /【<b style="color:#fff;">' \+ pName \+/],
    ['dupModal', /同名同账号 <b style="color:#fbbf24;">' \+ who \+/],
    ['codex', /\+ data\.regionWarning \+/],
    ['codex', /\+ d\.account \+/],
    ['codex', /submitCodexCode\(\\'' \+ flowId/],
    ['sinks', /innerHTML = '[^']*' \+ e\.message \+/],
  ];
  for (const [where, re] of checks) {
    if (re.test(html)) bad('source.' + where, '源码里仍存在未转义的拼接：' + re);
  }
  // 转义入口必须存在且引号也转（防止有人把 esc 换回只转 &<> 的旧版本）
  ok(/function esc\(/.test(html), 'source', 'esc() 不见了');
  ok(/&quot;/.test(fnSources.esc || '') && /&#39;/.test(fnSources.esc || ''), 'source', 'esc() 不再转义引号');
  ok(/function jsAttr\(/.test(html), 'source', 'jsAttr() 不见了');
}

/* ------------------------------------------------------------------ *
 * 6. 执行
 * ------------------------------------------------------------------ */
(async () => {
  const results = {};
  try { results.renderGroups = scenarioRenderGroups(); } catch (e) { bad('renderGroups', '执行异常：' + (e && e.message)); }
  try { results.dupModal = scenarioDupModal(); } catch (e) { bad('dupModal', '执行异常：' + (e && e.message)); }
  try {
    const codex = await scenarioCodex();
    if (codex && codex.c1) results.c1 = codex.c1;
    if (codex && codex.c2) results.c2 = codex.c2;
  } catch (e) { bad('codex', '执行异常：' + (e && e.message)); }
  try { await scenarioCodexPollAndSubmit(); } catch (e) { bad('codex.poll', '执行异常：' + (e && e.message)); }
  try { scenarioEscaperParity(); } catch (e) { bad('escaper', '执行异常：' + (e && e.message)); }
  try { scenarioStaticSource(); } catch (e) { bad('source', '执行异常：' + (e && e.message)); }

  // canary 与管理标记
  ok(canaryCalls.length === 0, 'canary', 'payload 中的 canary() 被调用了 ' + canaryCalls.length + ' 次');
  for (const sb of sandboxes) {
    const marks = Object.keys(sb).filter(k => /^(__admin|adminMark|__manage)/i.test(k));
    if (marks.length) bad('canary', '渲染过程创建了管理标记：' + marks.join(','));
  }

  console.log('目标文件：' + htmlPath);
  console.log('产物长度：renderGroups=' + String(results.renderGroups || '').length
    + ' dupModal=' + String(results.dupModal || '').length
    + ' codex=' + String(results.c1 || '').length);
  if (dump) {
    for (const [k, v] of Object.entries(results)) {
      console.log('\n----- ' + k + ' -----\n' + v);
    }
  }
  if (violations.length === 0) {
    console.log('✅ dashboard XSS 定向验证通过（' + (
      WANTED.length) + ' 个真实函数 × ' + ALL_PAYLOADS.length + ' 个合成 payload）');
    process.exit(0);
  }
  console.log('❌ 发现 ' + violations.length + ' 条违例：');
  for (const v of violations) console.log('  - ' + v);
  process.exit(1);
})().catch(e => {
  console.error('harness 自身异常：', e && e.stack || e);
  process.exit(1);
});
