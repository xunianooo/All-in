const fs=require('fs'),vm=require('vm'),assert=require('assert');
const html=fs.readFileSync('app/src/main/assets/dashboard.html','utf8');for(const m of html.matchAll(/<script>([\s\S]*?)<\/script>/g))new vm.Script(m[1]);
const code=html.slice(html.indexOf('    let resilienceToken='),html.indexOf('    // 页面启动初始化'));
const nodes=Object.fromEntries(['patience','heartbeat','status','save'].map(x=>['resilience-'+x,{value:'0',checked:false,textContent:'',disabled:false}]));const queue=[],calls=[];
const c=vm.createContext({document:{getElementById:id=>nodes[id]},API_BASE:'',fetch:(url,opts)=>{calls.push({url,opts});return new Promise((resolve,reject)=>queue.push({resolve,reject}));}});vm.runInContext(code,c);const run=s=>vm.runInContext(s,c),el=s=>nodes['resilience-'+s];
const reply=(settings={patienceMillis:0,heartbeat:false},token='t1',status=200)=>({ok:status===200,status,json:async()=>({ok:status===200,settings,stateToken:token})});
(async()=>{
 await run('saveResilience()');assert.equal(calls.length,0);
 let p=run('loadResilience()');queue.shift().resolve(reply());await p;
 el('patience').value='20000';el('heartbeat').checked=true;run('resilienceEdited()');p=run('saveResilience()');const sent=JSON.parse(calls.at(-1).opts.body);assert.deepEqual(sent,{settings:{patienceMillis:20000,heartbeat:true},expectedStateToken:'t1'});
 el('patience').value='10000';run('resilienceEdited()');queue.shift().resolve(reply(sent.settings,'t2'));await p;assert.equal(el('patience').value,'10000');assert(run('resilienceDirty'));
 p=run('saveResilience()');queue.shift().resolve(reply({},'t3',409));await p;assert(run('resilienceConflict'));let count=calls.length;await run('saveResilience()');assert.equal(calls.length,count);
 p=run('loadResilience()');queue.shift().resolve(reply({patienceMillis:5000,heartbeat:true},'t3'));await p;assert.equal(el('patience').value,'10000');
 p=run('saveResilience()');assert.equal(JSON.parse(calls.at(-1).opts.body).expectedStateToken,'t3');queue.shift().reject(Error('lost'));await p;count=calls.length;await run('saveResilience()');assert.equal(calls.length,count);
 p=run('loadResilience()');queue.shift().resolve(reply({patienceMillis:10000,heartbeat:true},'t4'));await p;
 el('patience').value='60001';await run('saveResilience()');assert.equal(queue.length,0);
 let old=run('loadResilience()'),newer=run('loadResilience()'),a=queue.shift(),b=queue.shift();b.resolve(reply(undefined,'t6'));await newer;a.resolve(reply(undefined,'stale'));await old;assert.equal(run('resilienceToken'),'t6');
 assert(calls.every(x=>x.url==='/api/resilience'));console.log('dashboard-resilience passed');
})().catch(e=>{console.error(e);process.exitCode=1});
