const fs=require('fs'),vm=require('vm'),assert=require('assert');
const html=fs.readFileSync('app/src/main/assets/dashboard.html','utf8');
const elements={};let sent;
function element(){return {children:[],style:{},appendChild(x){this.children.push(x)},innerText:'',innerHTML:''};}
const ctx=vm.createContext({document:{createElement:element,getElementById:id=>elements[id]||(elements[id]=element())},zcodeGiftModels:[],executeModelPingTest:x=>sent=x,appConfig:{providers:[{id:'qoder_cn',modelMeta:{qmodel_38max:{display_name:'Qwen3.8-Max'}}}]},pingModelsOf:()=>['qoder_cn/qmodel_38max'],escapeHtml:s=>String(s).replaceAll('&','&amp;').replaceAll('<','&lt;').replaceAll('"','&quot;')});
const slice=(a,b)=>html.slice(html.indexOf(a),html.indexOf(b,html.indexOf(a)));
vm.runInContext(slice('    function fmtMultiplier(v)','    /** 供应商是否'),ctx);
const pairs={fast:'Fast',lmodel:'QoDuck',qmodel_38max:'Qwen3.8-Max',qfmodel:'Qwen3.8-Flash',dmodel:'DeepSeek-V4-Pro',dfmodel:'DeepSeek-Flash',gmodel:'GLM-5.3',gfmodel:'GLM-5.3-Flash',kmodel_latest:'Kimi-K3.1-Pro',kmodel:'Kimi-K2.8-Preview',jmodel:'Step-5-Preview',mmodel:'MiniMax-M3.1-Flash'};
for(const [id,name]of Object.entries(pairs)){const tag=ctx.buildModelTag('qoder_cn',id,{display_name:name});assert.equal(tag.children[0].innerText,name);assert(tag.title.includes('qoder_cn/'+id));tag.onclick();assert.equal(sent,'qoder_cn/'+id);}
assert.equal(ctx.modelDisplayName('x',{}),'x');assert.equal(ctx.modelDisplayName('x',{display_name:7,name:'Readable'}),'Readable');
const bad='<img src=x onerror=alert(1)>';assert.equal(ctx.buildModelTag('p','x',{display_name:bad}).children[0].innerText,bad);
vm.runInContext(slice('    function onPingVendorChange(pid, silent)','    /** 测当前下拉'),ctx);
ctx.onPingVendorChange('qoder_cn',true);assert(elements['ping-model-select'].innerHTML.includes('Qwen3.8-Max · qmodel_38max'));assert(elements['ping-model-select'].innerHTML.includes('value="qoder_cn/qmodel_38max"'));
console.log('model names: 12 official labels, unchanged request IDs, safe text, fallback and selector PASS');
