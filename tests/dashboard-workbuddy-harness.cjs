const assert = require('assert');
const fs = require('fs');
const vm = require('vm');
const html = fs.readFileSync('app/src/main/assets/dashboard.html', 'utf8');
const fileTransferCode=html.slice(html.indexOf('    const ACCOUNT_FILE_PROVIDERS'),html.indexOf('    let currentModalProviderId'));
const js = [...html.matchAll(/<script[^>]*>([\s\S]*?)<\/script>/g)].map(x => x[1]).find(x => x.includes('let providerConfigRequest'));
const start = js.indexOf('    let providerConfigRequest');
const end = js.indexOf('    // 记住上一次', js.indexOf('    async function loadConfig()', start));
assert(start >= 0 && end > start);
class Element {
  constructor(tag, doc) { Object.assign(this, {tagName:tag, doc, children:[], dataset:{}, style:{}, hidden:false, value:'', _text:'', open:false}); }
  appendChild(el) { el.remove(); this.children.push(el); el.parent = this; return el; }
  remove() { if (this.parent) { this.parent.children = this.parent.children.filter(x => x !== this); this.parent = null; } }
  replaceChildren() { for (const el of this.children) el.parent = null; this.children = []; this._text = ''; }
  set textContent(s) { this.replaceChildren(); this._text = String(s); }
  get textContent() { return this._text + this.children.map(x => x.textContent).join(''); }
  setAttribute(k,v) { (this.attrs ||= {})[k]=v; }
  getAttribute(k) { return (this.attrs||{})[k]; }
  removeAttribute(k) { if(this.attrs)delete this.attrs[k]; }
  set innerHTML(s) { this._html = s; }
  closest(q) { for (let p = this; p; p = p.parent) if (q === 'details' && p.tagName === 'details') return p; return null; }
  querySelector(q) { return this.querySelectorAll(q)[0] || null; }
  querySelectorAll(q) { const found=[]; const visit = x => { for (const c of x.children) { if ((q === 'button' && c.tagName === 'button') || (q === '.account-json-file-actions' && c.className === 'account-json-file-actions') || (q === 'input' && c.tagName === 'input') || (q === '[data-harness-control]' && c.dataset.harnessControl) || (q === 'details[open]' && c.tagName === 'details' && c.open) || (q === '[data-provider-focus]' && c.dataset.providerFocus)) found.push(c); visit(c); } }; visit(this); return found; }
  focus() { this.doc.activeElement = this; }
}
function setup(hideDsh, token = 't0', id = 'workbuddy') {
  const elements = {}, calls = [], toasts = [];
  const doc = {activeElement:null, createElement(tag) { return new Element(tag, this); }, getElementById(id) { return elements[id] || null; }};
  for (const id of ['provider-list-view','provider-subview','provider-catalog','provider-catalog-heading','provider-list','provider-search','provider-detail-info','all-providers-cards-container','commercial-keys-container','provider-status']) elements[id] = new Element('div',doc);
  new Element('details',doc).appendChild(elements['commercial-keys-container']);
  const card = new Element('div',doc); card.dataset.providerId=id; elements['all-providers-cards-container'].appendChild(card);
  const provider = {id,name:'WorkBuddy',accounts:[{jwt:'configured'}],models:[]};
  if (hideDsh !== undefined) provider.hideDsh = hideDsh;
  const cfg = {providers:[provider],providerStateTokens:token === null ? {} : {[id]:token}};
  const noop = () => {};
  const ctx = vm.createContext({Set,Map,JSON,Array,String,document:doc,window:{scrollY:0,scrollTo:noop,zcodeGiftLoaded:true},API_BASE:'',appConfig:cfg,lastConfigSig:'',renderDirty:false,visibleProviders:x=>x||[],providerLogo:()=>'',fetch:(url,opt)=>new Promise((resolve,reject)=>calls.push({url,opt,resolve,reject})),showToast:x=>toasts.push(x),console:{error:noop},autoSyncAfterLogin:noop,renderCommercialKeys:noop,renderGroups:noop,bindGroupEvents:noop,renderWbAccounts:noop,renderWbModelTags:noop,renderAllProvidersCards:noop,renderTraeAccounts:noop,refreshProviderBadges:noop,applyPlaceholderGuard:noop,renderCredentials:noop,renderMiddleware:noop,renderPingProviders:noop,renderAllModelsTags:noop});
  vm.runInContext(fileTransferCode+js.slice(start,end),ctx);
  ctx.showProviderView('detail',id);
  return {ctx,calls,toasts,elements,id,sw:()=>elements['provider-detail-info'].querySelector('input'), unknown:()=>vm.runInContext(`providerUnknown.has(${JSON.stringify(id)})`,ctx), config:(value,t='t1')=>({providers:[{...provider,hideDsh:value}],providerStateTokens:{[id]:t}})};
}
const response = data => ({ok:true,status:200,json:async()=>data});
(async()=>{
  for (const forbidden of ['DSH 文本兼容', 'setWorkBuddyHarness', 'fencePendingHarness', 'providerPendingHarness', '/api/providers/workbuddy-harness', 'hideDsh']) assert(!html.includes(forbidden), forbidden);
  for (const id of ['workbuddy','workbuddy-ai','other']) {
    for (const legacy of [undefined,false,true]) {
      const s=setup(legacy,'t0',id);
      const info=s.elements['provider-detail-info'];
      assert(info.textContent.includes('WorkBuddy'));
      assert(!info.textContent.includes('DSH'));
      assert.equal(info.querySelector('input'),null);
      assert.equal(s.calls.length,0);
      const reading=s.ctx.loadConfig();
      const req=s.calls.shift(); assert.equal(req.url,'/api/config');
      req.resolve(response(s.config(legacy,'t0'))); await reading;
      assert.equal(s.calls.length,0);
      assert.equal(info.querySelector('input'),null);
      assert(info.textContent.includes('WorkBuddy'));
    }
  }
  console.log('WorkBuddy automatic UI, legacy fields and config readback OK');
})().catch(e=>{console.error(e);process.exitCode=1});
