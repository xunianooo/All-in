const fs=require('fs'),vm=require('vm'),assert=require('assert');
const html=fs.readFileSync('app/src/main/assets/dashboard.html','utf8');
for(const m of html.matchAll(/<script>([\s\S]*?)<\/script>/g)) new vm.Script(m[1]);
const code=html.slice(html.indexOf('    // 网络设置与配置轮询隔离'),html.indexOf('    // 页面启动初始化'));
function harness(){
 const nodes=Object.fromEntries(['mode','host','port','proxy-fields','status','save'].map(x=>['network-'+x,{value:'',style:{},textContent:'',disabled:false}])),queue=[],calls=[];
 const ctx=vm.createContext({document:{getElementById:id=>nodes[id]},API_BASE:'',fetch:(url,opts)=>{calls.push({url,opts});return new Promise((resolve,reject)=>queue.push({resolve,reject}))}});
 vm.runInContext(code,ctx);return {nodes,queue,calls,run:x=>vm.runInContext(x,ctx),el:x=>nodes['network-'+x]};
}
const reply=(network,token='t1',status=200,extra={})=>({ok:status===200,status,json:async()=>status===200?{ok:true,network,stateToken:token,...extra}:{error:'bad'}});
const tick=()=>new Promise(r=>setImmediate(r));
(async()=>{
 const h=harness(),{run,queue,calls,el}=h;
 let p=run('loadNetwork()');queue.shift().reject(Error('offline'));await p;await run('saveNetwork()');assert.equal(calls.length,1);assert.match(el('status').textContent,/先成功读取/);
 p=run('loadNetwork()');queue.shift().resolve(reply({mode:'default'}));await p;
 el('mode').value='http_proxy';run('networkEdited()');assert.equal(el('proxy-fields').style.display,'block');el('host').value='[::1]';el('port').value='8080';run('networkEdited()');
 p=run('saveNetwork()');assert.deepEqual(JSON.parse(calls.at(-1).opts.body),{mode:'http_proxy',host:'::1',port:8080,expectedStateToken:'t1'});await run('saveNetwork()');assert.equal(queue.length,1);
 el('host').value='new';run('networkEdited()');queue.shift().resolve(reply({mode:'http_proxy',host:'[::1]',port:8080},'t2'));await p;assert.equal(el('host').value,'new');
 p=run('saveNetwork()');assert.equal(JSON.parse(calls.at(-1).opts.body).expectedStateToken,'t2');queue.shift().resolve(reply({mode:'http_proxy',host:'new',port:8080},'t3'));await p;
 el('mode').value='direct';run('networkEdited()');assert.equal(el('proxy-fields').style.display,'none');p=run('saveNetwork()');assert.deepEqual(JSON.parse(calls.at(-1).opts.body),{mode:'direct',expectedStateToken:'t3'});queue.shift().resolve(reply({mode:'direct'},'t4'));await p;
 let old=run('loadNetwork()'),newer=run('loadNetwork()'),a=queue.shift(),b=queue.shift();b.resolve(reply({mode:'default'},'t5'));await newer;a.resolve(reply({mode:'direct'},'stale'));await old;assert.equal(el('mode').value,'default');
 p=run('loadNetwork()');el('mode').value='direct';run('networkEdited()');queue.shift().resolve(reply({mode:'default'},'t6'));await p;assert.equal(el('mode').value,'direct');
 p=run('saveNetwork()');assert.equal(JSON.parse(calls.at(-1).opts.body).expectedStateToken,'t5');queue.shift().resolve(reply(null,'',409));await p;assert.match(el('status').textContent,/冲突/);let n=calls.length;await run('saveNetwork()');assert.equal(calls.length,n);
 p=run('loadNetwork()');queue.shift().resolve(reply({mode:'default'},'t7'));await p;await run('saveNetwork()');assert.equal(calls.length,n+1);run('networkEdited()');p=run('saveNetwork()');assert.equal(JSON.parse(calls.at(-1).opts.body).expectedStateToken,'t7');queue.shift().resolve(reply({mode:'direct'},'t8'));await p;
 el('mode').value='default';run('networkEdited()');p=run('saveNetwork()');queue.shift().reject(Error('lost'));await tick();queue.shift().resolve(reply({mode:'direct'},'t8'));await p;assert.match(el('status').textContent,/当前读取与提交不同/);assert.doesNotMatch(el('status').textContent,/未生效/);
 // The first timed-out POST can arrive after the readback. Its CAS changes t8 to t9.
 p=run('saveNetwork()');assert.equal(JSON.parse(calls.at(-1).opts.body).expectedStateToken,'t8');queue.shift().resolve(reply(null,'',409));await p;assert.match(el('status').textContent,/冲突/);
 p=run('loadNetwork()');queue.shift().resolve(reply({mode:'default'},'t9'));await p;run('networkEdited()');p=run('saveNetwork()');queue.shift().reject(Error('lost'));await tick();queue.shift().resolve(reply({mode:'default'},'t10'));await p;assert.match(el('status').textContent,/一致/);assert.equal(run('networkDirty'),false);
 p=run('loadNetwork()');queue.shift().resolve(reply({mode:'default'},'t11',200,{blocked:true}));await p;assert.match(el('status').textContent,/出站请求已阻止/);assert.doesNotMatch(el('status').textContent,/已读取当前设置$/);
 p=run('saveNetwork()');queue.shift().resolve(reply(null,'',500));await p;assert.match(el('status').textContent,/持久化失败/);
 p=run('saveNetwork()');queue.shift().reject(Error('lost'));await tick();queue.shift().reject(Error('offline'));await p;n=calls.length;await run('saveNetwork()');assert.equal(calls.length,n);
 assert(calls.every(c=>c.url==='/api/network'));console.log('dashboard-network passed');
})().catch(e=>{console.error(e);process.exitCode=1});
