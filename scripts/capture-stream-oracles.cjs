// Frozen pure streaming oracle from the user's pinned local npm snapshot. Never activates auth or fetch.
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto');
const {pathToFileURL}=require('node:url');
(async()=>{
 const root=path.resolve(process.argv[2]||'../npm-plugins');global.fetch=()=>{throw Error('No vendor/network access in oracle');};
 const qfile=path.join(root,'opencode-qoder-auth/source/index.mjs'),mfile=path.join(root,'opencode-minimax-auth/source/index.mjs');
 const q=(await import(pathToFileURL(qfile)))._internal;
 const wrap=delta=>'data: '+JSON.stringify({statusCodeValue:200,body:JSON.stringify({choices:[{delta,finish_reason:null}]})})+'\n\n';
 const first=wrap({tool_calls:[{index:0,id:'source-call',type:'function',function:{name:'add_numbers',arguments:'{"a":'}}]});
 const second=wrap({tool_calls:[{index:0,function:{arguments:'2,"b":3}'}}]});
 let controller;const body=new ReadableStream({start(c){controller=c;}});const encoder=new TextEncoder();controller.enqueue(encoder.encode(first));
 const it=q.events(body,[]);let timer;const initial=await Promise.race([it.next(),new Promise((_,reject)=>{timer=setTimeout(()=>reject(Error('upstream tool header was buffered')),1000);})]);clearTimeout(timer);
 controller.enqueue(encoder.encode(second));const next=await it.next();
 controller.enqueue(encoder.encode('data: '+JSON.stringify({statusCodeValue:200,body:JSON.stringify({choices:[{delta:{},finish_reason:'tool_calls'}]})})+'\n\ndata: '+JSON.stringify({body:'[DONE]'})+'\n\n'));controller.close();const tail=[];for await(const event of it)tail.push(event);
 const minimax=fs.readFileSync(mfile,'utf8');const encoding=minimax.match(/headers\.set\("accept-encoding", "([^"]+)"\)/)?.[1];if(encoding!=='identity')throw Error('pinned MiniMax no longer declares identity');
 const result={snapshot:'2026-11-13',qoderSourceSha256:crypto.createHash('sha256').update(fs.readFileSync(qfile)).digest('hex'),miniMaxSourceSha256:crypto.createHash('sha256').update(fs.readFileSync(mfile)).digest('hex'),qoderFirstWire:first,qoderNextWire:second,qoderFirstEvent:initial.value,qoderNextEvent:next.value,qoderTail:tail,miniMaxAcceptEncoding:encoding,firstEventBeforeUpstreamClosed:true};
 fs.writeFileSync('tests/fixtures/provider-stream-oracles-20261113.json',JSON.stringify(result,null,2)+'\n');console.log(JSON.stringify({qoderFirstEvent:result.qoderFirstEvent,qoderNextEvent:result.qoderNextEvent,miniMaxAcceptEncoding:encoding,firstEventBeforeUpstreamClosed:true}));
})().catch(e=>{console.error(e.message);process.exit(1);});
