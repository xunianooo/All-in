const assert = require('assert');
const fs = require('fs');
const vm = require('vm');
const html = fs.readFileSync('app/src/main/assets/dashboard.html', 'utf8');
const js = [...html.matchAll(/<script[^>]*>([\s\S]*?)<\/script>/g)].map(x => x[1]).find(x => x.includes('let providerConfigRequest'));
new vm.Script(js);
const start = js.indexOf('    let providerConfigRequest');
const end = js.indexOf('    async function loadConfig()', start);
const code = js.slice(start, end);
const context = vm.createContext({ Set, JSON, Array, String, document: {}, window: {}, fetch:()=>{}, showToast:()=>{}, visibleProviders: x => Array.isArray(x) ? x : [], appConfig: {providers: []} });
vm.runInContext(code, context);
function counts(p) { return JSON.parse(JSON.stringify(context.providerCounts(p))); }
assert.deepStrictEqual(counts({keys:[' abc ','abc',{key:'abc',enabled:false},{key:'x',off:true},{}],accounts:[{}, {jwt:'j'}, {refresh:'r'}, {token:''}, 'x'],models:['x']}), {keys:2,accounts:2,models:1});
assert(context.providerConfigured({accounts:[{jwt:'j',enabled:false}]}));
assert(context.providerConfigured({accounts:[{refreshToken:'r'}]}));
assert(!context.providerConfigured({accounts:[{},'secret'],keys:[{}]}));
assert(!context.providerEnabled({enabled:false}));
assert(html.includes('id="provider-catalog" hidden'));
assert(html.includes('id="provider-list-view"'));
assert(!js.includes('const tokenPreview ='));
assert(!js.includes('keys.join(\',\')'));
console.log('provider schemas, deduplication, disabled presence, default view and script syntax OK');
class Element {
 constructor(tag, doc) { this.tagName=tag; this.doc=doc; this.children=[]; this.dataset={}; this.attrs={}; this.style={}; this.hidden=false; this.value=''; this._text=''; this.open=false; }
 appendChild(e){this.children.push(e);e.parent=this;return e}
 replaceChildren(){this.children=[];this._text=''}
 set textContent(s){this._text=String(s);this.children=[]} get textContent(){return this._text+this.children.map(x=>x.textContent).join('')}
 setAttribute(k,v){this.attrs[k]=v} getAttribute(k){return this.attrs[k]}
 set innerHTML(v){this._html=v;this._text=''} get innerHTML(){return this._html||''}
 closest(q){let x=this;while(x){if(q==='details' && x.tagName==='details')return x;x=x.parent}return null}
 querySelector(q){return this.querySelectorAll(q)[0]||null}
 querySelectorAll(q){const out=[];function visit(x){for(const c of x.children){if((q==='details[open]'&&c.tagName==='details'&&c.open)||(q==='[data-provider-focus]'&&c.dataset.providerFocus))out.push(c);visit(c)}}visit(this);return out}
 focus(){this.doc.activeElement=this}
}
(async()=>{
 const elements={}, doc={activeElement:null,createElement(tag){return new Element(tag,this)},getElementById(id){return elements[id]||null}};
 for(const id of ['provider-list-view','provider-subview','provider-catalog','provider-catalog-heading','provider-list','provider-search','provider-detail-info','all-providers-cards-container','commercial-keys-container'])elements[id]=new Element('div',doc);
 const details=new Element('details',doc);details.appendChild(elements['commercial-keys-container']);
 const card=new Element('div',doc);card.dataset.providerId='workbuddy';elements['all-providers-cards-container'].appendChild(card);
 const server={providers:[{id:'workbuddy',name:'WorkBuddy',accounts:[{jwt:'TOPSECRET',name:'PRIVATE@example.com'}],models:['model']},{id:'evil\"<img>',name:'Other',keys:[{key:'SECRETKEY',off:true}],enabled:false}]};
 const pending=[];let requests=[];
 const ctx=vm.createContext({Set,Map,JSON,Array,String,document:doc,window:{scrollY:73,scrollTo(x,y){this.scrollY=y}},API_BASE:'',appConfig:JSON.parse(JSON.stringify(server)), visibleProviders:x=>Array.isArray(x)?x.filter(Boolean):[], providerLogo:(id)=>'<safe-logo>',showToast:()=>{},manageProviderAccounts:()=>{},fetch:(url,opt)=>{requests.push({url,opt});return new Promise((resolve,reject)=>pending.push({url,opt,resolve,reject}))},loadConfig:async()=>{requests.push({url:'/api/config'});ctx.appConfig=JSON.parse(JSON.stringify(server));ctx.renderProviderList();ctx.applyProviderView()}});
 vm.runInContext(code,ctx);ctx.applyProviderView();ctx.renderProviderList();
 assert(!elements['provider-list-view'].hidden);assert(elements['provider-catalog'].hidden);
 assert(!elements['provider-list'].textContent.includes('TOPSECRET'));assert(!elements['provider-list'].textContent.includes('PRIVATE'));assert(!elements['provider-list'].textContent.includes('SECRETKEY'));
 let list=elements['provider-list'];let row=list.children[0];row.children[0].focus();row.children[0].onclick();assert(!card.hidden);assert(elements['provider-catalog-heading'].hidden);assert.equal(requests.length,0);
 ctx.showProviderView('list'); assert.equal(ctx.window.scrollY,73);elements['provider-search'].value='other';ctx.renderProviderList();assert(list.textContent.includes('Other'));assert(!list.textContent.includes('WorkBuddy'));
 ctx.showProviderView('add');assert(card.hidden);assert.equal(requests.length,0);ctx.showProviderView('list');elements['provider-search'].value='';ctx.renderProviderList();
 let sw=list.children[0].children[1];sw.checked=false;const action=sw.onchange();assert(list.children[0].children[1].checked);assert(list.children[0].children[1].disabled);
 pending.shift().resolve({ok:true,status:200,json:async()=>({ok:true,providerId:'workbuddy',enabled:false})});server.providers[0].enabled=false;await action;assert(list.children[0].open);assert.equal(requests.filter(x=>x.url==='/api/providers/enabled').length,1);
 const second=list.children[0].children[1].children[1];second.checked=true;const failure=second.onchange();pending.shift().resolve({ok:false,status:409,json:async()=>({ok:false,error:{message:'SECRETKEY'}})});await failure;assert(!server.providers[0].enabled);
 const third=list.children[0].children[1].children[1];third.checked=true;const unknown=third.onchange();pending.shift().reject(new Error('timeout'));await unknown;assert(requests.filter(x=>x.url==='/api/config').length>=3);assert.equal(requests.filter(x=>x.url==='/api/providers/enabled').length,3);
 console.log('provider DOM views, static card mapping, secret masking and toggle outcomes OK');
})().catch(e=>{console.error(e);process.exitCode=1});
(async()=>{
 const elements={},doc={activeElement:null,createElement(tag){return new Element(tag,this)},getElementById(id){return elements[id]||null}};
 for(const id of ['provider-list-view','provider-subview','provider-catalog','provider-catalog-heading','provider-list','provider-search','provider-detail-info','all-providers-cards-container','commercial-keys-container','provider-status'])elements[id]=new Element('div',doc);
 new Element('details',doc).appendChild(elements['commercial-keys-container']);
 const initial={providers:[{id:'a',name:'<img onerror=alert(1)>',keys:[{key:'one',off:true}]},{id:'b',name:'B',keys:['two']}]};
 let calls=[]; const fetch=(url,opt)=>new Promise((resolve,reject)=>calls.push({url,opt,resolve,reject}));
 const response=data=>({ok:true,status:200,json:async()=>JSON.parse(JSON.stringify(data))});
 const ctx=vm.createContext({Set,Map,JSON,Array,String,document:doc,window:{scrollY:0,scrollTo(){},zcodeGiftLoaded:true},API_BASE:'',appConfig:JSON.parse(JSON.stringify(initial)),lastConfigSig:'',renderDirty:false,visibleProviders:x=>x||[],providerLogo:()=>'<logo>',fetch,showToast:()=>{},alert:()=>{},console,autoSyncAfterLogin(){},renderCommercialKeys(){},renderGroups(){},bindGroupEvents(){},renderWbAccounts(){},renderWbModelTags(){},renderAllProvidersCards(){},refreshProviderBadges(){},applyPlaceholderGuard(){},renderCredentials(){},renderMiddleware(){},renderPingProviders(){},renderAllModelsTags(){}});
 const load=js.slice(js.indexOf('    async function loadConfig()'),js.indexOf('    // 记住上一次',js.indexOf('    async function loadConfig()')));
 const update=js.slice(js.indexOf('    function updateProviderKeys('),js.indexOf('    // 隐藏已取消',js.indexOf('    function updateProviderKeys(')));
 const save=js.slice(js.indexOf('    async function saveProviders()'),js.indexOf('    function testMaskSandbox()',js.indexOf('    async function saveProviders()')));
 vm.runInContext(code+load+update+save,ctx);ctx.renderProviderList();
 assert(elements['provider-list'].textContent.includes('<img onerror=alert(1)>'));
 const old=ctx.loadConfig(); const oldReq=calls.shift();
 const pa=ctx.setProviderEnabled('a',false),pb=ctx.setProviderEnabled('b',false); const ra=calls.shift(),rb=calls.shift();
 rb.resolve(response({ok:true,providerId:'b',enabled:false}));await Promise.resolve();await Promise.resolve();
 ra.resolve(response({ok:true,providerId:'a',enabled:false}));
 await new Promise(resolve=>setTimeout(resolve,0)); const refresh=calls.shift();assert.equal(refresh.url,'/api/config');
 oldReq.resolve(response(initial));await old;assert.equal(ctx.appConfig.providers.find(x=>x.id==='a').enabled,false);
 refresh.resolve(response({providers:initial.providers.map(p=>({...p,enabled:false}))}));await Promise.all([pa,pb]);
 const unknown=ctx.setProviderEnabled('a',true);calls.shift().reject(new Error('timeout'));await new Promise(resolve=>setTimeout(resolve,0));calls.shift().resolve({ok:false,status:503});await unknown;
 assert(elements['provider-status'].textContent.includes('待确认')); assert(vm.runInContext("providerUnknown.has('a')",ctx));
 const retry=ctx.loadConfig();calls.shift().resolve(response({providers:[{...initial.providers[0],enabled:true},{...initial.providers[1],enabled:false}]}));await retry;assert(!vm.runInContext("providerUnknown.has('a')",ctx));assert(ctx.appConfig.providers[0].enabled);
 ctx.appConfig.providers.reverse();ctx.updateProviderKeys('a','first');assert.deepEqual(JSON.parse(JSON.stringify(ctx.appConfig.providers.find(p=>p.id==='a').keys)),[{key:'one',off:true}]);assert.deepEqual(JSON.parse(JSON.stringify(vm.runInContext("dirtyKeysMap.get('a')",ctx))),[{key:'one',off:true},'first']);
 const saving=ctx.saveProviders();const keyReq=calls.shift();assert.equal(JSON.parse(keyReq.opt.body).providerId,'a');ctx.updateProviderKeys('a','second');await ctx.saveProviders();assert.equal(calls.length,0);
 keyReq.resolve(response({ok:true,providerId:'a',keyCount:2}));await new Promise(resolve=>setTimeout(resolve,0));calls.shift().resolve(response({providers:initial.providers}));await saving;
 assert.deepEqual(JSON.parse(JSON.stringify(vm.runInContext("dirtyKeysMap.get('a')",ctx))),[{key:'one',off:true},'second']);
 ctx.appConfig.providers.push({id:'new',name:'New Provider',keys:[]});ctx.updateProviderKeys('new','draft-secret');ctx.renderProviderList();assert(!elements['provider-list'].textContent.includes('New Provider'));assert.deepEqual(JSON.parse(JSON.stringify(ctx.appConfig.providers.find(p=>p.id==='new').keys)),[]);
 // Save the outstanding drafts. Only the readback turns the new provider into a configured row.
 const finish=ctx.saveProviders();let aSave=calls.shift();assert.equal(JSON.parse(aSave.opt.body).providerId,'a');aSave.resolve(response({ok:true,providerId:'a',keyCount:2}));await new Promise(resolve=>setTimeout(resolve,0));let newSave=calls.shift();assert.equal(JSON.parse(newSave.opt.body).providerId,'new');assert(!elements['provider-list'].textContent.includes('New Provider'));newSave.resolve(response({ok:true,providerId:'new',keyCount:1}));await new Promise(resolve=>setTimeout(resolve,0));let readback=calls.shift();readback.resolve(response({providers:[...initial.providers,{id:'new',name:'New Provider',keys:['draft-secret']}]}));await finish;assert(elements['provider-list'].textContent.includes('New Provider'));
 console.log('provider concurrent writes, stale reads, failed readback, retry and draft during save OK');
})().catch(e=>{console.error(e);process.exitCode=1});
(async()=>{
 const calls=[],toasts=[];const fetch=(url,opt)=>new Promise((resolve,reject)=>calls.push({url,opt,resolve,reject}));
 const initial={providers:[{id:'a',keys:[{key:'old',off:true}]},{id:'b',keys:['b']}],providerStateTokens:{a:'t0',b:'b0'}};
 const ctx=vm.createContext({Set,Map,JSON,Array,String,document:{activeElement:null,getElementById(){return null}},window:{zcodeGiftLoaded:true},API_BASE:'',appConfig:JSON.parse(JSON.stringify(initial)),lastConfigSig:'',renderDirty:false,visibleProviders:x=>x||[],fetch,showToast:x=>toasts.push(x),console,autoSyncAfterLogin(){},renderCommercialKeys(){},renderGroups(){},bindGroupEvents(){},renderWbAccounts(){},renderWbModelTags(){},renderAllProvidersCards(){},refreshProviderBadges(){},applyPlaceholderGuard(){},renderCredentials(){},renderMiddleware(){},renderPingProviders(){},renderAllModelsTags(){}});
 const load=js.slice(js.indexOf('    async function loadConfig()'),js.indexOf('    // 记住上一次',js.indexOf('    async function loadConfig()')));
 const update=js.slice(js.indexOf('    function updateProviderKeys('),js.indexOf('    // 隐藏已取消',js.indexOf('    function updateProviderKeys(')));
 const save=js.slice(js.indexOf('    async function saveProviders()'),js.indexOf('    function testMaskSandbox()',js.indexOf('    async function saveProviders()')));
 vm.runInContext(code+load+update+save,ctx);
 const response=data=>({ok:true,status:200,json:async()=>JSON.parse(JSON.stringify(data))});
 const tick=()=>new Promise(r=>setTimeout(r,0));
 ctx.updateProviderKeys('a','draft');
 const poll=ctx.loadConfig();calls.shift().resolve(response({...initial,providerStateTokens:{a:'external',b:'b0'},providers:[{id:'a',keys:[{key:'external',off:false}]},initial.providers[1]]}));await poll;
 let saving=ctx.saveProviders();let req=calls.shift();assert.equal(JSON.parse(req.opt.body).expectedStateToken,'t0');
 req.resolve({ok:false,status:409,json:async()=>({ok:false})});await tick();calls.shift().resolve(response({...initial,providerStateTokens:{a:'external',b:'b0'}}));await saving;
 assert(toasts.some(x=>x.includes('冲突')));assert(vm.runInContext("commercialEditedIds.has('a')",ctx));
 ctx.updateProviderKeys('a','fresh');saving=ctx.saveProviders();req=calls.shift();assert.equal(JSON.parse(req.opt.body).expectedStateToken,'external');ctx.updateProviderKeys('a','later');req.resolve(response({ok:true,providerId:'a',keyCount:2,stateToken:'t1'}));await tick();calls.shift().resolve(response({...initial,providerStateTokens:{a:'t1',b:'b0'}}));await saving;
 assert.equal(vm.runInContext("providerDraftTokens.get('a')",ctx),'t1');
 saving=ctx.saveProviders();req=calls.shift();assert.equal(JSON.parse(req.opt.body).expectedStateToken,'t1');req.reject(Error('timeout'));await tick();let read=calls.shift();assert(vm.runInContext("providerUnknown.has('a')",ctx));
 await ctx.saveProviders();assert.equal(calls.length,0);read.resolve(response({...initial,providerStateTokens:{a:'t1',b:'b0'}}));await saving;
 assert(vm.runInContext("providerDraftConflicts.has('a')",ctx));assert(toasts.some(x=>x.includes('核对')));
 // A readback can precede the timed-out POST: both use t1, so CAS permits only one.
 ctx.updateProviderKeys('a','late-retry');saving=ctx.saveProviders();req=calls.shift();assert.equal(JSON.parse(req.opt.body).expectedStateToken,'t1');
 req.resolve({ok:false,status:409,json:async()=>({ok:false})});await tick();calls.shift().resolve(response({...initial,providerStateTokens:{a:'late-committed',b:'b0'}}));await saving;assert(toasts.filter(x=>x.includes('冲突')).length>=2);
 // Uncertain response with matching readback preserves later input and advances baseline.
 ctx.updateProviderKeys('a','submitted');saving=ctx.saveProviders();req=calls.shift();const sent=JSON.parse(req.opt.body);ctx.updateProviderKeys('a','after');req.resolve({ok:true,status:200,json:async()=>{throw Error('truncated')}});await tick();calls.shift().resolve(response({providers:[{id:'a',keys:sent.keys},initial.providers[1]],providerStateTokens:{a:'ack',b:'b0'}}));await saving;
 assert.equal(vm.runInContext("providerDraftTokens.get('a')",ctx),'ack');assert(vm.runInContext("commercialEditedIds.has('a')",ctx));
 // An in-flight toggle blocks only its own provider and reports the retained draft.
 const toggle=ctx.setProviderEnabled('a',false);const toggleReq=calls.shift();ctx.updateProviderKeys('b','new-b');saving=ctx.saveProviders();const bReq=calls.shift();assert.equal(JSON.parse(bReq.opt.body).providerId,'b');assert(toasts.some(x=>x.includes('操作进行中')));bReq.resolve(response({ok:true,providerId:'b',keyCount:1,stateToken:'b1'}));await saving;toggleReq.resolve(response({ok:true,providerId:'a',enabled:false,stateToken:'a2'}));await tick();calls.shift().resolve(response({...initial,providerStateTokens:{a:'a2',b:'b1'}}));await toggle;
 // 409 readback has external structured metadata and disabled credentials; a new edit rebases on those objects.
 const extObjects=[{key:'external-object',enabled:false,metadata:{nested:{version:2},label:'latest'}},{key:'disabled-object',off:true,metadata:{source:'external'}}];
 ctx.updateProviderKeys('a','before-conflict');saving=ctx.saveProviders();req=calls.shift();req.resolve({ok:false,status:409,json:async()=>({ok:false})});await tick();calls.shift().resolve(response({providers:[{id:'a',keys:extObjects},initial.providers[1]],providerStateTokens:{a:'external-new',b:'b1'}}));await saving;
 ctx.updateProviderKeys('a','after-conflict');saving=ctx.saveProviders();req=calls.shift();const rebased=JSON.parse(req.opt.body);assert.equal(rebased.expectedStateToken,'external-new');assert.deepStrictEqual(rebased.keys,[...extObjects,'after-conflict']);
 // Readback changes nested object property order while preserving semantic content.
 req.reject(Error('timeout'));await tick();calls.shift().resolve(response({providers:[{id:'a',keys:[{metadata:{label:'latest',nested:{version:2}},enabled:false,key:'external-object'},{metadata:{source:'external'},off:true,key:'disabled-object'},'after-conflict']},initial.providers[1]],providerStateTokens:{a:'confirmed',b:'b1'}}));await saving;
 assert(!vm.runInContext("commercialEditedIds.has('a') || providerDraftConflicts.has('a') || providerUnknown.has('a')",ctx));assert(toasts.some(x=>x.includes('保存结果已确认')));
 assert(ctx.providerKeysEqual([{a:1,b:{x:2,y:[1,2]}}],[{b:{y:[1,2],x:2},a:1}]));assert(!ctx.providerKeysEqual([{a:[1,2]}],[{a:[2,1]}]));assert(!ctx.providerKeysEqual([{a:1}],[{a:1,b:null}]));
 console.log('provider CAS baselines, conflicts, uncertain readback and independent writes OK');
})().catch(e=>{console.error(e);process.exitCode=1});
