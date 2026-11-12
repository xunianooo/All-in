const fs=require('fs'), vm=require('vm'), assert=require('assert');
const html=fs.readFileSync('app/src/main/assets/dashboard.html','utf8');
const slice=(a,b)=>html.slice(html.indexOf(a),html.indexOf(b,html.indexOf(a)));
const ctx=vm.createContext({Intl,Date,console,zcodeGiftModels:['gift'],document:{createElement:()=>({children:[],style:{},appendChild(x){this.children.push(x)}})},executeModelPingTest(){}});
vm.runInContext(slice('    function fmtMultiplier(v)','    /** 供应商是否'),ctx);
vm.runInContext(slice('    function billingSnapshot(', '    const billingRequests'),ctx);
const view=(pid,id,meta,now)=>ctx.modelBillingView(pid,id,meta,now);
for(const [id,price] of Object.entries({fast:.8,lmodel:1,qmodel:.5,qfmodel:0,dmodel:.5,dfmodel:.1,gmodel:.8,gfmodel:.1,kmodel:1,kmodel2:.8,jmodel:.7,mmodel:.1})) assert.equal(view('qoder_cn',id,{raw:{price_factor:price}}).text,price?'×'+price:'免费');
assert.equal(view('qoder_cn','m',{raw:{price_factor:0,promotion:{active:true,before_promotion_price_factor:1,discount_factor:.1}}}).text,'×0.1');
assert.equal(view('qoder_cn','m',{raw:{price_factor:0,promotion:{active:true,before_promotion_price_factor:1}}}).text,'—');
for (const pid of ['workbuddy','raccoon']) assert.equal(view(pid,'m',{raw:{billing:{kind:'multiplier',scope:'model',value:.25}}}).text,'×0.25');
assert.equal(view('codex','m',{}).text,'账户共享额度');
assert.equal(view('x','m',{raw:{billing:{kind:'multiplier',scope:'model',value:0}}}).text,'免费');
assert.equal(view('antigravity','m',{remaining:0}).text,'剩 0%');
assert.equal(view('x','m',{multiplier:NaN}).text,'—');
assert.equal(view('x','m',{input_price:1}).text,'—');
assert.equal(view('zcode','gift',{allowance:{kind:'allowance',scope:'model',remaining:0,unit:'次'}}).text,'剩 0 次');
const activity={enable:true,data:{off_peak:{before_consumption_rate:.12,after_consumption_rate:.06,time_zone:'Asia/Shanghai',time_windows:[{weekdays:[1],start_minute:1320,end_minute:120}]}}};
const meta={raw:{billingSchedule:{activity}}};
assert.equal(view('trae-cn','m',meta,Date.parse('2026-11-09T15:00:00Z')).text,'×0.06');
assert.equal(view('trae-cn','m',meta,Date.parse('2026-11-09T18:00:00Z')).text,'×0.12');
activity.data.off_peak.time_zone='bad/zone';assert.equal(view('trae-cn','m',meta,0).text,'活动倍率未知');
let snap=ctx.billingSnapshot(null,{ok:true,models:{},accounts:[{accountId:'a',allowance:{remaining:4}},{accountId:'b',allowance:{remaining:8}}]},1);
snap=ctx.billingSnapshot(snap,{ok:true,models:{},accounts:[{accountId:'a',errorKind:'failed'},{accountId:'b',allowance:{remaining:2}}]},2);
assert.equal(snap.accounts[0].allowance,undefined);assert.equal(snap.accounts[0].stale,true);assert.equal(snap.accounts[1].allowance.remaining,2);
snap=ctx.billingSnapshot(snap,null,3);assert.equal(snap.stale,true);assert.equal(snap.accounts[0].allowance,undefined);
console.log('dashboard model billing fixtures PASS');

// Exercise actual tag and account DOM render paths, including untrusted content.
function node(){return {children:[],style:{},textContent:'',innerText:'',appendChild(c){this.children.push(c)}};}
ctx.document.createElement=node;
vm.runInContext(slice('    const providerBillingCache =', '    function renderProviderList()'),ctx);
const cache=vm.runInContext('providerBillingCache',ctx);
cache.zcode=ctx.billingSnapshot(null,{ok:true,models:{},accounts:[{accountId:'a',name:'<img>',allowance:{kind:'allowance',scope:'account',windows:[{name:'gift',scope:'model',remaining:0,unit:'gift_units'}]}}]},1000);
let parent=node();ctx.renderProviderBilling(parent,'zcode');
assert(parent.children[1].children[0].textContent.includes('账户共享额度'));
assert(parent.children[1].children[1].textContent.includes('gift（模型额度） 剩 0 赠送单位'));
assert(parent.children[1].children[1].textContent.includes('<img>'));
assert(!parent.children[1].children[1].textContent.includes('0 次'));
cache.antigravity=ctx.billingSnapshot(null,{ok:true,accounts:[],models:{m:{allowance:{kind:'allowance',scope:'model',remainingPercent:0,source:'saved_catalog',staleKnown:true}}}},1000);
let tag=ctx.buildModelTag('antigravity','m',{});
assert(tag.children.at(-1).innerText.includes('剩 0% · 上次'));assert(tag.title.includes('时间未知'));
cache.workbuddy=ctx.billingSnapshot(null,{ok:true,accounts:[],models:{m:{billing:{kind:'multiplier',scope:'model',value:1},billingVariants:[{accountId:'a',name:'<script>',billing:{kind:'multiplier',scope:'model',value:0}},{accountId:'b',name:'B',billing:{kind:'multiplier',scope:'model',value:.5}}]}}},1000);
tag=ctx.buildModelTag('workbuddy','m',{});assert.equal(tag.children.at(-1).innerText,'因账号而异');assert(tag.title.includes('<script>：免费'));assert(tag.title.includes('B：×0.5'));
assert.equal(ctx.billingAllowanceText({}), '');assert.equal(ctx.billingAllowanceText({remainingPercent:0}), '0%');
assert.equal(ctx.billingAllowanceText({remaining:1,unit:'USD'}),'1 美元');assert.equal(ctx.billingAllowanceText({remaining:2,unit:'credits'}),'2 积分');assert.equal(ctx.billingAllowanceText({remaining:3,unit:'request'}),'3 次数');
cache.workbuddy=ctx.billingSnapshot(cache.workbuddy,{ok:true,accounts:[],models:{}},2000);assert.equal(Object.keys(cache.workbuddy.models).length,0);
console.log('billing DOM and freshness fixtures PASS');

assert.equal(view('minimax-code','m',{context:1048576}).text,'1M'); assert(!view('minimax-code','m',{}).text.includes('×'));
assert(!html.includes("('🎉 ' + data.message)"));

assert.equal(view('minimax-code','m',{contextWindow:512000}).text,'500K');

// WorkBuddy has no invented stale snapshot and no raw implementation error on the page.
cache.workbuddy=ctx.billingSnapshot(null,{ok:true,models:{},accounts:[{accountId:'a',name:'one',errorKind:'not_provided'},{accountId:'b',name:'two',errorKind:'key_not_supported'}]},1000);
parent=node();ctx.renderProviderBilling(parent,'workbuddy');
const wbText=parent.children[1].children.map(n=>n.textContent).join(' ');
assert(!wbText.includes('not_provided'));assert(!wbText.includes('上次快照'));assert(wbText.includes('静态 Key'));
cache.workbuddy=ctx.billingSnapshot(null,{ok:true,models:{},accounts:[{accountId:'a',name:'one',allowance:{kind:'allowance',scope:'account',unit:'credits',remaining:0,windows:[]}}]},1000);
parent=node();ctx.renderProviderBilling(parent,'workbuddy');assert(parent.children[1].children.some(n=>n.textContent.includes('剩余 0 积分')));
console.log('WorkBuddy allowance UI regression PASS');
