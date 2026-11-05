const fs=require('fs'),vm=require('vm'),assert=require('assert');
const html=fs.readFileSync('app/src/main/assets/dashboard.html','utf8');
for(const m of html.matchAll(/<script>([\s\S]*?)<\/script>/g)) new vm.Script(m[1]);
const code=html.slice(html.indexOf('    let outboundToken='),html.indexOf('    // 页面启动初始化'));
function harness(){
 const nodes=Object.fromEntries(['provider','domain','interval','rpm','flight','queue','wait','save','status'].map(x=>['outbound-'+x,{value:'',disabled:false,textContent:''}]));
 nodes['outbound-domain'].value='credential';const queue=[],calls=[];
 const ctx=vm.createContext({document:{getElementById:id=>nodes[id]},API_BASE:'',fetch:(url,opts)=>{calls.push({url,opts});return new Promise((resolve,reject)=>queue.push({resolve,reject}));}});
 vm.runInContext(code,ctx);return {nodes,queue,calls,run:x=>vm.runInContext(x,ctx),el:x=>nodes['outbound-'+x]};
}
const reply=(settings,token='t1',status=200)=>({ok:status===200,status,json:async()=>({ok:status===200,settings,stateToken:token})});
(async()=>{
 const {run,queue,calls,el}=harness();
 await run('saveOutbound()');assert.equal(calls.length,0);
 let p=run('loadOutbound()');queue.shift().resolve(reply({other:{shared:{rpm:30}}}));await p;
 el('provider').value='raccoon';run('outboundSelect()');assert.equal(el('queue').value,32);el('interval').value='500';run('outboundEdit()');
 p=run('saveOutbound()');const sent=JSON.parse(calls.at(-1).opts.body);assert.equal(sent.settings.other.shared.rpm,30);assert.equal(sent.settings.raccoon.credential.minIntervalMillis,500);assert.equal(sent.expectedStateToken,'t1');
 await run('saveOutbound()');assert.equal(queue.length,1);
 el('interval').value='800';run('outboundEdit()');queue.shift().resolve(reply(sent.settings,'t2'));await p;assert.equal(el('interval').value,'800');assert.match(el('status').textContent,/草稿/);
 await run('saveOutbound()');assert.equal(queue.length,0);
 run('outboundSelect()');el('rpm').value='1.5';run('outboundEdit()');await run('saveOutbound()');assert.equal(queue.length,0);
 el('rpm').value='15';run('outboundEdit()');p=run('saveOutbound()');queue.shift().resolve(reply({},'t2',409));await p;assert.match(el('status').textContent,/冲突/);
 p=run('loadOutbound()');queue.shift().resolve(reply(sent.settings,'t3'));await p;assert.equal(el('rpm').value,'15');run('outboundSelect()');
 p=run('saveOutbound()');queue.shift().reject(Error('lost'));await p;let n=calls.length;await run('saveOutbound()');assert.equal(calls.length,n);assert.match(el('status').textContent,/未确认/);
 p=run('loadOutbound()');queue.shift().resolve(reply(sent.settings,'t4'));await p;run('outboundSelect()');
 const old=run('loadOutbound()'),newer=run('loadOutbound()'),a=queue.shift(),b=queue.shift();b.resolve(reply(sent.settings,'t5'));await newer;a.resolve(reply({},'stale'));await old;assert.equal(run('outboundToken'),'t5');
 assert(calls.every(c=>c.url==='/api/outbound'));console.log('dashboard-outbound passed');
})().catch(e=>{console.error(e);process.exitCode=1});
