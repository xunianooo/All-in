const fs=require('fs'),vm=require('vm'),assert=require('assert');
const html=fs.readFileSync('app/src/main/assets/dashboard.html','utf8');
for(const m of html.matchAll(/<script>([\s\S]*?)<\/script>/g)) new vm.Script(m[1]);
const cards=['workbuddy-ai','loomy','workbuddy','raccoon','catpaw','github-copilot','minimax-code','deepseek_web','zcode','qoder_cn','cline','codex','antigravity','trae-cn'];
const commercial=['deepseek','siliconflow','openai','anthropic','openrouter','tencent'];
for(const p of cards){const start=html.lastIndexOf('<div class="card"',html.indexOf('data-provider-logo="'+p+'"'));const end=html.indexOf('<!--',start+1);const card=html.slice(start,end<0?undefined:end);assert(card.includes('onclick="providerEndpointProbe(\''+p+'\')"'),p);assert(card.includes('id="endpoint-probe-status-'+p+'"'),p);}
for(const p of ['workbuddy','raccoon']) assert(html.includes('onclick="triggerPlatformLogin(\''+p+'\')"'));
assert(html.includes('onclick="antigravityProbe()">高级探测并切换端点'));
assert(html.includes("onclick=\"triggerPlatformLogin('workbuddy-ai')\""));
const loomyCard=html.slice(html.lastIndexOf('<div class="card"',html.indexOf('data-provider-logo="loomy"')),html.indexOf('<!--',html.indexOf('data-provider-logo="loomy"')));
assert(!/triggerPlatformLogin|syncUniversalModels|登录账号/.test(loomyCard));
const staticClicks={};
for(const p of cards){const logo=html.indexOf('data-provider-logo="'+p+'"'), start=html.lastIndexOf('<div class="card"',logo), end=html.indexOf('<!--',logo);const fragment=html.slice(start,end<0?undefined:end);const tag=fragment.match(new RegExp('<button\\b[^>]*id="endpoint-probe-button-'+p+'"[^>]*>'));assert(tag,p);const handler=tag[0].match(/onclick="([^"]+)"/);assert(handler,p);staticClicks[p]=handler[1];}
const elements={};
for(const p of [...cards,...commercial]) for(const part of ['button','status']) elements['endpoint-probe-'+part+'-'+p]={disabled:false,textContent:'',innerHTML:''};
let calls=[],response={ok:true,scope:'official-defaults',scopeNote:'官方范围',results:[]},release,fail=false,timers=[];
const context=vm.createContext({document:{getElementById:id=>elements[id]},API_BASE:'',AbortController,console,setTimeout:(fn,ms)=>{timers.push({fn,ms});return timers.length},clearTimeout:()=>{},fetch:async(url,options)=>{calls.push({url,options});if(release)await new Promise(r=>release.resolve=r);if(fail)throw Object.assign(new Error('failed'),{name:fail});return {ok:true,json:async()=>response}}});
vm.runInContext(html.slice(html.indexOf('    function esc(s)'),html.indexOf('    function escHtml(s)')),context);
vm.runInContext(html.slice(html.indexOf('    const endpointProbePending'),html.indexOf('    /**\n     * Antigravity (Google Code Assist) 登录：')),context);
const attack='<img src=x onerror=alert(1)>';
(async()=>{
 for(const p of [...cards,...commercial]){if(staticClicks[p]) await vm.runInContext(staticClicks[p],context); else await context.providerEndpointProbe(p);const c=calls.at(-1);assert.equal(c.url,'/api/providers/endpoint-probe');assert.equal(c.options.method,'POST');assert.deepEqual(JSON.parse(c.options.body),{provider:p});assert(elements['endpoint-probe-status-'+p].innerHTML.includes('官方固定地址'));assert(elements['endpoint-probe-status-'+p].innerHTML.includes('不改变路由'));}
 let before=calls.length;await context.providerEndpointProbe('placeholder');assert.equal(calls.length,before);
 response={ok:true,scopeNote:attack,summary:attack,results:[{reachable:true,label:attack,url:attack,method:'GET',httpStatus:403,kind:'restricted',note:attack,ms:1}]};await context.providerEndpointProbe('workbuddy');let box=elements['endpoint-probe-status-workbuddy'];assert(!box.innerHTML.includes('<img'));assert(box.innerHTML.includes('&lt;img'));assert(box.innerHTML.includes('HTTP 403'));
 release={};const first=context.providerEndpointProbe('openai');await Promise.resolve();assert(elements['endpoint-probe-button-openai'].disabled);before=calls.length;await context.providerEndpointProbe('openai');assert.equal(calls.length,before);
 // Recreated nodes after a commercial render recover the in-flight state.
 elements['endpoint-probe-button-openai']={};elements['endpoint-probe-status-openai']={};context.hydrateEndpointProbe('openai');assert(elements['endpoint-probe-button-openai'].disabled);assert(elements['endpoint-probe-status-openai'].textContent.includes('正在'));
 release.resolve();await first;release=null;assert(!elements['endpoint-probe-button-openai'].disabled);elements['endpoint-probe-status-openai']={};context.hydrateEndpointProbe('openai');assert(elements['endpoint-probe-status-openai'].innerHTML.includes('&lt;img'));
 response={ok:false,busy:true,error:attack};await context.providerEndpointProbe('raccoon');assert(elements['endpoint-probe-status-raccoon'].innerHTML.includes('繁忙'));assert(!elements['endpoint-probe-status-raccoon'].innerHTML.includes('<img'));
 fail='AbortError';await context.providerEndpointProbe('workbuddy');assert(elements['endpoint-probe-status-workbuddy'].innerHTML.includes('超时'));fail='Error';await context.providerEndpointProbe('workbuddy');assert(elements['endpoint-probe-status-workbuddy'].innerHTML.includes('请求失败'));assert(timers.every(t=>t.ms===15000));
 assert(html.includes("ENDPOINT_PROBE_PROVIDERS.has(p.id) ? endpointProbeControls(p.id)"));assert(html.includes('自定义地址暂未提供此探测'));assert(html.includes("['loomy','workbuddy-ai'].includes(p.id)"));
 await testCommercialRender();
 console.log('dashboard endpoint probe: 20 providers, controls, isolation, rerender hydration, busy, escaping and timeout PASS');
})().catch(e=>{console.error(e);process.exitCode=1});

async function testCommercialRender(){
  const nodes={}; let queued=[], requests=[], pending, response={ok:true,summary:'done',results:[]}, flushes=0;
  const decode=s=>s.replace(/&quot;/g,'"').replace(/&#39;/g,"'").replace(/&lt;/g,'<').replace(/&gt;/g,'>').replace(/&amp;/g,'&');
  class Row {
    constructor(){this.dataset={};this.children=[];this.input=null;this.markup='';}
    set innerHTML(v){this.markup=v; const input=v.match(/<input[^>]*value="([^"]*)"/);this.input=input?{value:decode(input[1])}:null;
      this.controls=[];for(const m of v.matchAll(/<(button|div)\b([^>]*\bid="endpoint-probe-(?:button|status)-[^"]+"[^>]*)>/g)){
        const id=m[2].match(/\bid="([^"]+)"/)[1], click=m[2].match(/onclick="([^"]+)"/);
        const node={id,disabled:false,textContent:'',innerHTML:'',onclick:click?()=>vm.runInContext(decode(click[1]),ctx):null};this.controls.push(node);
      }}
    get innerHTML(){return this.markup}
    querySelector(q){return q==='input'?this.input:null}
  }
  const container={children:[],set innerHTML(v){for(const row of this.children)for(const n of row.controls||[])delete nodes[n.id];this.children=[];this.markup=v},appendChild(row){this.children.push(row);for(const n of row.controls)nodes[n.id]=n}};
  nodes['commercial-keys-container']=container;
  const providers=commercial.map((id,i)=>({id,name:id,protocol:'openai',keys:['key-'+i],accounts:[],baseUrl:'https://custom.example'}));
  providers.push({id:'custom',protocol:'openai',keys:['custom-key'],accounts:[]},{id:'placeholder',protocol:'openai',status:'placeholder',keys:[],accounts:[]});
  const snapshot=JSON.stringify(providers);
  const ctx=vm.createContext({document:{getElementById:id=>nodes[id],createElement:()=>new Row()},API_BASE:'',AbortController,console,
    setTimeout:(fn,ms)=>{if(ms===0)queued.push(fn);return 1},clearTimeout:()=>{},
    fetch:async(url,options)=>{requests.push({url,options});if(pending)await new Promise(r=>pending.resolve=r);return {ok:true,json:async()=>response}},
    appConfig:{providers},dirtyKeysMap:new Map([['openai',['draft-key',{type:'structured'}]]]),
    providerCounts:p=>({keys:p.keys.length}),providerLogo:()=>'',providerDraftConflicts:new Set(),providerUnknown:new Set(),commercialEditedIds:new Set(),providerDraftTokens:new Map(),providerToken:id=>'token-'+id,commercialKeysDirty:false,
    flushCommercialRender:()=>flushes++,triggerPlatformLogin:()=>{throw Error('login')},syncUniversalModels:()=>{throw Error('sync')},saveConfig:()=>{throw Error('save')}});
  vm.runInContext(html.slice(html.indexOf('    function esc(s)'),html.indexOf('    function escHtml(s)')),ctx);
  vm.runInContext(html.slice(html.indexOf('    function jsAttr(v)'),html.indexOf('    /** 供应商卡片的统一清单')),ctx);
  vm.runInContext(html.slice(html.indexOf('    const endpointProbePending'),html.indexOf('    /**\n     * Antigravity (Google Code Assist) 登录：')),ctx);
  vm.runInContext(html.slice(html.indexOf('    function updateProviderKeys('),html.indexOf('    // 隐藏已取消的供应商')),ctx);
  vm.runInContext(html.slice(html.indexOf('    function renderCommercialKeys()'),html.indexOf('    // 赠送额度覆盖的模型名')),ctx);
  ctx.renderCommercialKeys();assert.equal(container.children.length,7);assert.equal(JSON.stringify(providers),snapshot);
  for(const p of commercial){const b=nodes['endpoint-probe-button-'+p];assert(b&&typeof b.onclick==='function',p);assert(nodes['endpoint-probe-status-'+p]);await b.onclick();const request=requests.at(-1);assert.equal(request.url,'/api/providers/endpoint-probe');assert.deepEqual(JSON.parse(request.options.body),{provider:p});}
  assert(container.children.find(r=>r.dataset.providerId==='custom').innerHTML.includes('自定义地址暂未提供此探测'));assert(!nodes['endpoint-probe-button-custom']);assert(!nodes['endpoint-probe-button-placeholder']);
  let row=container.children.find(r=>r.dataset.providerId==='openai');assert.equal(row.input.value,'draft-key');assert(row.innerHTML.includes('结构化 Key 保持原样'));
  const dirtyBefore=JSON.stringify([...ctx.dirtyKeysMap]);pending={};const promise=nodes['endpoint-probe-button-openai'].onclick();await Promise.resolve();ctx.renderCommercialKeys();
  assert(nodes['endpoint-probe-button-openai'].disabled);assert(nodes['endpoint-probe-status-openai'].textContent.includes('正在'));assert.equal(JSON.stringify([...ctx.dirtyKeysMap]),dirtyBefore);assert.equal(JSON.stringify(providers),snapshot);
  const count=requests.length;await nodes['endpoint-probe-button-openai'].onclick();assert.equal(requests.length,count);pending.resolve();await promise;pending=null;
  ctx.renderCommercialKeys();assert(!nodes['endpoint-probe-button-openai'].disabled);assert(nodes['endpoint-probe-status-openai'].innerHTML.includes('done'));
  row=container.children.find(r=>r.dataset.providerId==='openai');assert.equal(row.input.value,'draft-key');row.input.value='edited, second';row.input.oninput();assert.deepEqual(JSON.parse(JSON.stringify(ctx.dirtyKeysMap.get('openai'))),[{type:'structured'},'edited','second']);assert.equal(JSON.stringify(providers),snapshot);
  row.input.onblur();assert.equal(queued.length,1);queued.shift()();assert.equal(flushes,1);assert(requests.every(r=>r.url==='/api/providers/endpoint-probe'));assert.equal(JSON.stringify(providers),snapshot);
}
