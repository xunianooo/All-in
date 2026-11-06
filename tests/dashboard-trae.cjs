const assert = require('assert');
const fs = require('fs');
const vm = require('vm');
const html = fs.readFileSync('app/src/main/assets/dashboard.html','utf8');
const js = [...html.matchAll(/<script[^>]*>([\s\S]*?)<\/script>/g)].map(x=>x[1]).find(x=>x.includes('function renderTraeAccounts'));
new vm.Script(js);
const code = js.slice(js.indexOf('    function renderTraeAccounts()'), js.indexOf('    async function syncUniversalModels('));
class El {
 constructor(){this.children=[];this.dataset={};this.value='';this.textContent=''}
 replaceChildren(){this.children=[];this.value=''}
 appendChild(c){this.children.push(c);if(this.children.length===1)this.value=c.value}
}
(async()=>{
 const nodes={'trae-account-select':new El(),'trae-checkin-status':new El()};let handler;const calls=[];let resolve;
 const ctx=vm.createContext({document:{getElementById:id=>nodes[id],createElement:()=>new El(),addEventListener:(name,fn)=>handler=fn},appConfig:{providers:[{id:'trae-cn',accounts:[{id:'a',name:'A',token:'SECRET'}, {id:'b /',name:'B',devicePrivateKey:'PRIVATE'}]}]},API_BASE:'',encodeURIComponent,JSON,String,Array,errText:x=>String(x||'未知错误'),fetch:(url,opts)=>{calls.push({url,opts});return new Promise(r=>resolve=r)}});
 vm.runInContext(code,ctx);ctx.renderTraeAccounts();const select=nodes['trae-account-select'];assert.equal(select.children.length,2);assert(!JSON.stringify(select).includes('SECRET'));assert(!JSON.stringify(select).includes('PRIVATE'));select.value='b /';ctx.renderTraeAccounts();assert.equal(select.value,'b /');assert.equal(calls.length,0);
 handler({target:{closest:()=>({dataset:{traeAction:'status'}})}});assert.equal(calls[0].url,'/api/trae/checkin-status?accountId=b%20%2F');resolve({json:async()=>({ok:true,outcome:'status',status:{checked_in:false}})});await new Promise(r=>setImmediate(r));assert(nodes['trae-checkin-status'].textContent.includes('今日未签到'));
 handler({target:{closest:()=>({dataset:{traeAction:'checkin'}})}});handler({target:{closest:()=>({dataset:{traeAction:'checkin'}})}});assert.equal(calls.length,2);assert.equal(calls[1].opts.method,'POST');assert.deepStrictEqual(JSON.parse(calls[1].opts.body),{accountId:'b /'});resolve({json:async()=>({ok:true,outcome:'done',credits:12,token:'SECRET'})});await new Promise(r=>setImmediate(r));assert(nodes['trae-checkin-status'].textContent.includes('12'));assert(!nodes['trae-checkin-status'].textContent.includes('SECRET'));
 console.log('Trae account selection, explicit status/check-in, duplicate suppression and secret omission OK');
})().catch(e=>{console.error(e);process.exitCode=1});
