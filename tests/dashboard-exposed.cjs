const fs = require('fs'), vm = require('vm'), assert = require('assert'), path = require('path');
const html = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/dashboard.html'), 'utf8');
const code = html.slice(html.indexOf('    let exposedData = null;'), html.indexOf('    // ==================== 主题', html.indexOf('    let exposedData = null;')));
class Element {
  constructor(tag='div') { this.tag=tag; this.children=[]; this.style={}; this.dataset={}; this.attrs={}; this.textContent=''; }
  appendChild(child) { this.children.push(child); return child; }
  replaceChildren() { this.children=[]; }
  setAttribute(k,v) { this.attrs[k]=v; }
  addEventListener(name, fn) { this[name]=fn; }
  click() { return this.clickHandler ? this.clickHandler() : this['click'](); }
  scrollIntoView() { this.scrolled=true; }
}
// A tiny DOM with real nodes/listeners: text is never parsed as HTML.
const elems = {'exposed-list':new Element(), 'exposed-enabled':new Element(), 'exposed-state':new Element(), 'commercial-keys-container':new Element()};
const weird = `x'"<>&`, model = `m'"<script>`, group = `group/'"<`;
let state={ok:true,enabled:true,items:[weird+'/'+model,group],providers:[{id:weird,name:'Very long '+weird,live:true,models:[model,'other']},{id:'empty',name:'Empty',live:false,models:['hidden']}],groups:[{id:group,name:'Group <&'}]};
let saved, tab, toast;
const ctx={document:{getElementById:id=>elems[id]||null,createElement:tag=>new Element(tag)},API_BASE:'',visibleProviders:a=>a||[],showToast:m=>toast=m,switchTab:t=>tab=t,loadConfig:async()=>{},fetch:async(url,opt)=>({json:async()=>{if(opt){saved=JSON.parse(opt.body);return {ok:true,count:saved.items.length}}return structuredClone(state)}})};
vm.createContext(ctx); vm.runInContext(code,ctx);
const all=(root,cls)=>[...(root.className||'').split(' ').includes(cls)?[root]:[], ...root.children.flatMap(x=>all(x,cls))];
const sections=()=>elems['exposed-list'].children;
const byText=(root,cls,text)=>all(root,cls).find(x=>x.textContent===text);
const click=el=>{assert(el);return el.click()};
(async()=>{
 await ctx.loadExposed();
 assert.equal(all(elems['exposed-list'],'exp-chip').length,0);
 assert(sections()[1].children[0].children[0].children[0].textContent.includes('1/2 · 已配置'));
 assert.equal(sections()[2].children[1].children[0].textContent,'请导入账户或添加 API Key');
 click(all(sections()[1],'exp-toggle')[0]);
 assert.equal(all(sections()[1],'exp-chip').length,2);
 assert(all(sections()[1],'exp-chip')[0].className.includes(' on'));
 click(byText(sections()[1],'exp-mini','全选'));
 assert.equal(all(sections()[1],'exp-chip').filter(x=>x.className.includes(' on')).length,2);
 click(byText(sections()[1],'exp-mini','清空'));
 assert.equal(all(sections()[1],'exp-chip').filter(x=>x.className.includes(' on')).length,0);
 click(byText(sections()[1],'exp-chip',model));
 await ctx.saveExposed(); assert(saved.items.includes(weird+'/'+model));
 state.items=saved.items; await ctx.loadExposed();
 assert.equal(all(sections()[1],'exp-chip').length,2);
 state.providers[0].live=false; await ctx.loadExposed();
 assert.equal(all(sections()[1],'exp-chip').length,0);
 await ctx.saveExposed(); assert(saved.items.includes(weird+'/'+model));
 state.providers[0].live=true; state.providers[1].live=true; await ctx.loadExposed();
 assert.equal(all(sections()[1],'exp-chip').length,2); assert.equal(all(sections()[2],'exp-chip').length,0);
 click(all(sections()[2],'exp-toggle')[0]); assert.equal(all(sections()[2],'exp-chip').length,1);
 click(all(sections()[0],'exp-toggle')[0]); assert.equal(all(sections()[0],'exp-chip')[0].textContent,'Group <&');
 click(byText(sections()[0],'exp-mini','清空')); await ctx.saveExposed(); assert(!saved.items.includes(group));
 click(byText(sections()[0],'exp-chip','Group <&')); await ctx.saveExposed(); assert(saved.items.includes(group));
 state.providers[1].live=false; await ctx.loadExposed();
 const row=new Element(); row.dataset.providerId='empty'; elems['commercial-keys-container'].appendChild(row);
 await click(byText(sections()[2],'exp-mini','去配置')); assert.equal(tab,'providers'); assert(row.scrolled);
 elems['commercial-keys-container'].replaceChildren(); await click(byText(sections()[2],'exp-mini','去配置')); assert.equal(toast,'未找到该供应商的配置入口');
 const target=new Element(); elems['badge-count-empty']={closest:()=>target}; await click(byText(sections()[2],'exp-mini','去配置')); assert(target.scrolled);
 console.log('dashboard exposed collapse, credentials, selection and navigation OK');
})().catch(e=>{console.error(e);process.exitCode=1});
