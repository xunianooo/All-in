const fs=require('fs'),vm=require('vm'),assert=require('assert'),path=require('path');
const html=fs.readFileSync(path.join(__dirname,'../app/src/main/assets/dashboard.html'),'utf8');
const start=html.indexOf('    async function saveFailover()');
const code=html.slice(start,html.indexOf('    async function loadMiddleware()',start));
const state={enabled:true,maxTargets:7,maxAttempts:4,attemptBudgetMs:150000,requestBudgetMs:1200000,future:{x:1},targets:[]};
let payload,reloads=0,fail=false,alerts=0;
const ctx={API_BASE:'',document:{getElementById:id=>id==='failover-enabled'?{checked:false}:{value:' alpha/m \n\n beta/long-id '}},
 fetch:async(url,opt)=>{assert.equal(url,'/api/guard');payload=JSON.parse(opt.body);if(!fail)Object.assign(state,payload.failover);return{json:async()=>({ok:!fail})};},
 showToast:()=>{},loadGuard:async()=>{reloads++},alert:()=>{alerts++}};
vm.createContext(ctx);vm.runInContext(code,ctx);
(async()=>{await ctx.saveFailover();assert.deepStrictEqual(payload,{failover:{enabled:false,targets:['alpha/m','beta/long-id']}});
 assert.equal(state.maxTargets,7);assert.equal(state.maxAttempts,4);assert.equal(state.attemptBudgetMs,150000);assert.equal(state.requestBudgetMs,1200000);assert.deepStrictEqual(state.future,{x:1});assert.equal(reloads,1);
 fail=true;await ctx.saveFailover();assert.equal(alerts,1);assert.equal(reloads,1);console.log('dashboard failover partial save PASS');})().catch(e=>{console.error(e);process.exitCode=1});
