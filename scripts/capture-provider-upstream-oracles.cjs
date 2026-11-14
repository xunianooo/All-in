// Read-only upstream oracles. Never activates a plugin or contacts a vendor.
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto');
const {pathToFileURL}=require('node:url');
(async()=>{
  const root=path.resolve(process.argv[2]||'../npm-plugins');
  global.fetch=()=>{throw new Error('Oracle generation must not access the network');};
  const sources={};
  const load=async(name)=>{
    const file=path.join(root,`opencode-${name}-auth/source/index.mjs`);
    const bytes=fs.readFileSync(file),pkg=JSON.parse(fs.readFileSync(path.join(path.dirname(file),'package.json'),'utf8'));
    sources[name]={version:pkg.version,sha256:crypto.createHash('sha256').update(bytes).digest('hex')};
    return (await import(pathToFileURL(file)))._internal;
  };
  const mini=await load('minimax'),qoder=await load('qoder'),cat=await load('catpaw'),work=await load('workbuddy');
  const catalog={providers:[{providerId:'minimax',config:{model_order:['MiniMax-M3','unknown-zero'],models:{
    'MiniMax-M3':{name:'M3',limit:{context:524288,output:128000},modalities:{input:['text','video','image']},attachment:false,thinking_config:{mode:'switchable'}},
    'unknown-zero':{limit:{context:0,output:0}},
    'attachment-only':{attachment:true,limit:{context:200000,output:128000},modalities:{input:['text']}}
  }}}]};
  const fixture={sources,miniCatalog:catalog,miniParsed:mini.parseModels(catalog),miniFallback:mini.MODELS,
    qoderCatalog:{displayMode:'qoder',qoderUsage:{expiresAt:'2026-11-15T00:00:00Z',userQuota:{total:2000,used:100},addOnQuota:{total:50,used:0},orgResourcePackage:{total:30,used:0}}}};
  fixture.qoderUsage=qoder.parseUsage(fixture.qoderCatalog);
  const tc=(index,id,name,args)=>({index,id,function:{...(name?{name}:{}),arguments:args}});
  const wrap=(delta,finish)=>'data: '+JSON.stringify({statusCodeValue:200,body:JSON.stringify({choices:[{delta,finish_reason:finish??null}]})})+'\n\n';
  fixture.qoderOrderWire=wrap({tool_calls:[tc(1,'order-b',null,'{}'),tc(0,'order-a','f','{}')]})+wrap({tool_calls:[tc(1,'order-b','f','')]})+wrap({},'tool_calls')+'data: '+JSON.stringify({body:'[DONE]'})+'\n\n';
  fixture.qoderNamedOrder=[];
  for await(const e of qoder.events(new Response(fixture.qoderOrderWire).body,[]))if(e.tool?.id)fixture.qoderNamedOrder.push(e.tool.id);
  const chat={model:'gemini-3.8-flash',max_tokens:524288,messages:[{role:'user',content:'abc'}]};
  work.withGrowth(work.SITES['workbuddy-ai']||Object.values(work.SITES).find(x=>x.ai),chat,'fixture-conversation');
  fixture.workbuddyGrowth=chat;
  const catSource=fs.readFileSync(path.join(root,'opencode-catpaw-auth/source/index.mjs'),'utf8');
  fixture.catRate={status:429,retryAfter:Number(catSource.match(/"retry-after": "(\d+)"/)[1])};
  fixture.catQuotaGate=catSource.match(/const QUOTA_GATE = "([^"]+)"/)[1];
  const vm=require('node:vm');
  const constant=(vendor,name,end)=>{
    const file=path.join(root,`opencode-${vendor}-auth/source/index.mjs`),text=fs.readFileSync(file,'utf8');
    const start=text.indexOf(`const ${name} = `); if(start<0)throw new Error(name+' missing');
    const at=text.indexOf('\n'+end,start);if(at<0)throw new Error(name+' end missing');
    return vm.runInNewContext(text.slice(start,at+end.length+1)+`; ${name}`,{}, {timeout:1000});
  };
  fixture.loomyFallback=constant('loomy','STATIC_MODELS','}');
  fixture.workbuddyCN=constant('workbuddy','CN_MODELS',']');
  fixture.workbuddyAI=constant('workbuddy','AI_MODELS',']');
  fixture.raccoonFallback=constant('raccoon','FALLBACK_MODELS',']');
  const ag=fs.readFileSync(path.join(root,'opencode-antigravity-auth/source/index.mjs'),'utf8');
  fixture.antigravityVersion=ag.match(/const DEFAULT_VERSION = "([^"]+)"/)[1];
  const out=path.resolve('tests/fixtures/provider-upstream-20261113.json');fs.writeFileSync(out,JSON.stringify(fixture,null,2)+'\n');
  console.log(JSON.stringify({fixture:out,sources,miniRows:fixture.miniParsed.length,qoderWindows:fixture.qoderUsage.windows?.length,growthAdded:!!chat.extra_vars},null,2));
})().catch(e=>{console.error(e.name+': '+e.message);process.exit(1);});
