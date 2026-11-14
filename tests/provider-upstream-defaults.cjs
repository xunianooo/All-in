const fs=require('node:fs'),assert=require('node:assert/strict');
const config=JSON.parse(fs.readFileSync('app/src/main/assets/default_config.json','utf8'));
const oracle=JSON.parse(fs.readFileSync('tests/fixtures/provider-upstream-20261113.json','utf8'));
let checks=0;const eq=(actual,expected,label)=>{checks++;assert.deepEqual(actual,expected,label);};
const provider=id=>config.providers.find(p=>p.id===id);
eq(provider('workbuddy-ai').models,oracle.workbuddyAI.map(x=>x[0]),'AI fallback is not copied from CN');
for(const [id,table] of [['workbuddy',oracle.workbuddyCN],['workbuddy-ai',oracle.workbuddyAI]])for(const row of table){
 const meta=provider(id).modelMeta?.[row[0]];if(!meta)continue;
 eq(meta.contextWindow,row[2],`${id}/${row[0]} original context`);
 if(row[4]!=null)eq(meta.maxOutput,row[4],`${id}/${row[0]} original output`);
 else eq(Object.hasOwn(meta,'maxOutput'),false,'no invented output ceiling');
}
for(const [id,table] of [['loomy',Object.entries(oracle.loomyFallback).map(([id,x])=>({id,...x.limit}))],['raccoon',oracle.raccoonFallback],['minimax-code',oracle.miniFallback]]){
 for(const row of table){const meta=provider(id).modelMeta?.[row.id];if(!meta)continue;
  if(row.output!=null)eq(meta.maxOutput,row.output,`${id}/${row.id} exact large upstream output`);
  if(row.context!=null)eq(meta.contextWindow,row.context,`${id}/${row.id} exact upstream context`);
 }
}
eq(provider('loomy').modelMeta['MiniMax-M3'].maxOutput,512000,'Loomy declared 512000, not the direct MiniMax 524288');
eq(provider('minimax-code').modelMeta['MiniMax-M3'].maxOutput,524288,'MiniMax declared verified 524288 intact');
eq(config.guard.maxOutputTokens,0,'no implicit Guard output ceiling');
const licenses=fs.readFileSync('app/src/main/assets/licenses.html','utf8');
for(const name of ['catpaw','cline','copilot','loomy','minimax','qoder','raccoon','trae','workbuddy','zcode','antigravity']){
 const versions={catpaw:'0.2.2',cline:'0.1.15',copilot:'0.1.2',loomy:'0.1.5',minimax:'0.2.9',qoder:'0.2.67',raccoon:'0.1.11',trae:'0.2.34',workbuddy:'0.2.34',zcode:'0.1.61',antigravity:'0.1.9'};
 eq(licenses.includes(`opencode-${name}-auth</td><td>${versions[name]}</td>`),true,'pinned reference version '+name);
}
console.log('provider-upstream-defaults PASS: '+checks+' checks');
