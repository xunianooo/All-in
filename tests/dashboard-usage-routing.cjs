const fs = require('fs'), vm = require('vm'), assert = require('assert');
const html = fs.readFileSync(require('path').join(__dirname, '../app/src/main/assets/dashboard.html'), 'utf8');
const text = html.slice(html.indexOf('    let groupDraft = null;'), html.indexOf('    async function saveProviders()', html.indexOf('    let groupDraft = null;')));
const handlers = {}, container = {dataset:{}, children:[], innerHTML:'', appendChild(x){this.children.push(x)}, addEventListener(k,v){handlers[k]=v}};
let payload, release;
const group = strategy => ({id:'g',name:'Group',strategy,targets:['p/a','p/b'],pick:'p/b'});
const context = {appConfig:{groups:[group('usage')],providers:[]}, document:{getElementById:()=>container,createElement:()=>({style:{},innerHTML:''})}, loadRoutingState(){}, visibleProviders:()=>[], providerHasCredential:()=>true, pingModelsOf:()=>[], showToast(){}, confirm:()=>true, API_BASE:'', fetch:async (url,opts)=>{payload=JSON.parse(opts.body); await new Promise(r=>release=r); return {json:async()=>({ok:true})}}, loadConfig:async()=>{context.appConfig.groups=JSON.parse(JSON.stringify(payload.groups))}};
vm.createContext(context); vm.runInContext(text,context);
const draft=()=>vm.runInContext('groupDraft',context);
const event=(action,value,index=0)=>({target:{closest:()=>({value,getAttribute:k=>({'data-action':action,'data-g':'0','data-i':String(index)})[k]})}});
(async()=>{
 context.renderGroups(true); context.bindGroupEvents();
 assert.equal(draft()[0].strategy,'usage');
 let card=container.children[0].innerHTML;
 assert(card.includes('<option value="usage" selected>近期用量优先</option>'));
 assert.equal((card.match(/<option value="(?:order|rotate|manual|usage)"/g)||[]).length,4);
 assert(card.includes('可信上报用量计分') && card.includes('无可信正数时按 1 计') && card.includes('估算值不计入'));
 assert(card.includes('缓存按协议归一') && card.includes('推理 token 不重复叠加'));
 assert(card.includes('一小时半衰') && card.includes('1024') && card.includes('失败不增加') && card.includes('重启清空') && card.includes('跨组共享'));
 assert(!card.includes('未知、估算或零用量按 1 计'));
 for (const strategy of ['order','rotate','manual','future']) { context.appConfig.groups=[group(strategy)]; context.syncGroupDraft(); assert.equal(draft()[0].strategy,strategy==='future'?'order':strategy); assert.equal(draft()[0].pick,'p/b'); }
 context.appConfig.groups=[group('order')]; context.renderGroups(true);
 const saving=handlers.change(event('strategy','usage')); assert.equal(payload.groups[0].strategy,'usage');
 context.renderGroups(); assert.equal(draft()[0].strategy,'usage');
 release(); await saving; assert.equal(draft()[0].strategy,'usage'); assert.equal(context.appConfig.groups[0].strategy,'usage');
 const manual=handlers.change(event('strategy','manual')); assert.equal(payload.groups[0].pick,'p/b'); release(); await manual;
 const pick=handlers.click(event('pick','',0)); assert.equal(payload.groups[0].pick,'p/a'); release(); await pick;
 context.addNewGroup(); assert.equal(draft()[1].strategy,'order');
 console.log('dashboard usage routing OK');
})().catch(e=>{console.error(e);process.exitCode=1});

assert(html.includes('min-width:0;max-width:100%;width:0;box-sizing:border-box'));
