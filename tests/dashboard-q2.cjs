const fs=require('fs'),vm=require('vm'),assert=require('assert');
const html=fs.readFileSync('app/src/main/assets/dashboard.html','utf8');
const nodes={'quirks-status':{textContent:''},'quirks-check':{disabled:true}};let calls=[],pending=[];
const ctx=vm.createContext({document:{getElementById:id=>nodes[id]},API_BASE:'',fetch:(url,opts)=>{calls.push({url,opts});return new Promise((resolve,reject)=>pending.push({resolve,reject}));}});
vm.runInContext(html.slice(html.indexOf('    let quirksReadRevision='),html.indexOf('    function openLocalBackup(')),ctx);
const run=s=>vm.runInContext(s,ctx),response=d=>({ok:true,json:async()=>d}),state=(enabled,result='ready',source='builtin')=>({version:'1.0.0',ruleCount:3,source,loadError:null,update:{enabled,result}});
(async()=>{
 let p=run('loadQuirks()');pending.shift().resolve(response(state(false,'disabled')));await p;assert(nodes['quirks-check'].disabled);assert.match(nodes['quirks-status'].textContent,/未激活/);
 p=run('loadQuirks()');pending.shift().resolve(response(state(true)));await p;assert(!nodes['quirks-check'].disabled);
 p=run('checkQuirks()');assert(nodes['quirks-check'].disabled);let count=calls.length;await run('checkQuirks()');assert.equal(calls.length,count);pending.shift().reject(Error('unknown'));await p;assert.match(nodes['quirks-status'].textContent,/未确认/);assert(nodes['quirks-check'].disabled);
 p=run('loadQuirks()');pending.shift().resolve(response(state(true,'cache_restored','verified-cache')));await p;assert.match(nodes['quirks-status'].textContent,/已验证缓存/);
 p=run('checkQuirks()');pending.shift().resolve(response({result:'updated'}));await new Promise(r=>setImmediate(r));pending.shift().resolve(response(state(true,'updated','verified-update')));await p;assert.match(nodes['quirks-status'].textContent,/更新成功/);
 console.log('dashboard q2 PASS');
})().catch(e=>{console.error(e);process.exitCode=1});
