const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const source=fs.readFileSync('app/src/main/assets/dashboard.html','utf8');
const between=(a,b)=>{const start=source.indexOf(a),end=source.indexOf(b,start);assert(start>=0&&end>start);return source.slice(start,end);};
const snapshot=between('    function billingSnapshot(', '    const billingRequests');
const refresh=between('    async function refreshProviderBilling(', '    function renderProviderBilling');
const render=between('    function renderProviderBilling(', '    function renderProviderList');
let checks=0;const eq=(a,b,label)=>{checks++;assert.deepEqual(a,b,label);};
const node=()=>({children:[],textContent:'',appendChild(x){this.children.push(x);}});
const text=n=>n.textContent+' '+n.children.map(text).join(' ');
async function run(id,status,previous,accounts){
 const calls=[];const context={API_BASE:'',Date,providerView:'list',providerDetail:'',billingRequests:{},providerBillingCache:previous?{[id]:previous}:{},renderAllModelsTags(){},applyProviderView(){},async loadConfig(){},document:{createElement:node},billingAllowanceText(){return '';},fetch:async url=>{calls.push(url);return url.includes('/sync-models')?{ok:status===200,status,json:async()=>status===200?{ok:true}:{error:{message:'sensitive-fixture-body'}}}:{ok:true,status:200,json:async()=>({ok:true,provider:id,accounts,models:{}})};}};
 vm.createContext(context);vm.runInContext(snapshot+refresh+render,context);await context.refreshProviderBilling(id);const parent=node();context.renderProviderBilling(parent,id);return {calls,cache:JSON.parse(JSON.stringify(context.providerBillingCache[id])),rendered:text(parent)};
}
(async()=>{
 const expired={accountId:'fixture-a',name:'Account A',errorKind:'sign_in_required'};
 let r=await run('catpaw',401,null,[expired]);
 eq(r.rendered.includes('登录已失效，请重新登录'),true,'actual renderer shows re-login label');
 eq(r.calls.length,2,'a real 401 must still reach per-account billing, not disappear as empty stale UI');
 eq(r.rendered.includes('sensitive-fixture-body'),false,'never render upstream raw error');
 eq(r.cache.accounts[0].errorKind,'sign_in_required','classification survives async flow');
 const previous={accounts:[{accountId:'fixture-a',allowance:{remaining:100,unit:'credits'}}],models:{},stale:false};
 r=await run('catpaw',500,previous,[expired]);eq(r.calls.length,1,'network/server failure remains fail-closed');eq(r.cache.stale,true,'retain stale snapshot');eq(r.cache.accounts[0].allowance.remaining,100,'do not replace old balance with zero');eq(r.rendered.includes('登录已失效'),false,'network failure is not guessed expired');
 r=await run('trae-cn',401,previous,[expired]);eq(r.calls.length,1,'CatPaw fix does not silently broaden other vendors');
 r=await run('catpaw',401,null,[expired,{accountId:'fixture-b',name:'Account B',allowance:{remaining:50,unit:'credits'}}]);eq(r.cache.accounts.length,2,'preserve full account pool');eq(r.cache.accounts[1].allowance.remaining,50,'one expired account does not expire another');eq(Boolean(r.cache.accounts[1].errorKind),false,'no blanket expiry');
 r=await run('catpaw',200,null,[{accountId:'fixture-a',allowance:{remaining:0,unit:'credits'}}]);eq(r.cache.accounts[0].allowance.remaining,0,'known zero survives normal refresh');
 console.log('dashboard-catpaw-auth-feedback PASS: '+checks+' checks');
})().catch(e=>{console.error(e);process.exit(1);});
