const fs=require('fs'),path=require('path'),vm=require('vm'),assert=require('assert');
const root=path.resolve(__dirname,'..'),html=fs.readFileSync(path.join(root,'app/src/main/assets/dashboard.html'),'utf8');
for(const script of html.matchAll(/<script[^>]*>([\s\S]*?)<\/script>/g))new vm.Script(script[1]);
function part(a,b){const x=html.indexOf(a),y=html.indexOf(b,x);assert(x>=0&&y>x,'missing '+a);return html.slice(x,y);}
const script=part('    // Check-in view owns no supplier credentials','    let gatewayAdvancedScroll = 0;');
assert(!script.includes('innerHTML'),'untrusted names/messages only use textContent');
assert(script.includes('/api/checkin/status')&&script.includes('/api/checkin/run')&&script.includes('/api/checkin/settings'));
const page=part('      <section id="checkin-center-view"','      <div id="gateway-advanced-view" hidden>');
const entry=part('      <div class="card checkin-entry"','      <!-- 一键连接到 ETA');
const back=part('    window.allInHandleBack = function () {','    function applyProviderView()');
const css=html.match(/<style>([\s\S]*?)<\/style>/)[1];
const logos=part('    const PROVIDER_ICON_FILES = Object.freeze({','    document.querySelectorAll(\'[data-provider-logo]\')');
const assetsBase=require('url').pathToFileURL(path.join(root,'app/src/main/assets')+path.sep).href;
const width=Number(process.env.ALLIN_CHECKIN_WIDTH)||390;
const theme=process.env.ALLIN_CHECKIN_THEME==='dark'?'dark':'light';
const harness=`<!doctype html><html data-theme="${theme}"><head><meta charset="UTF-8"><base href="${assetsBase}"><meta name="viewport" content="width=device-width,initial-scale=1"><style>${css}</style></head><body style="padding:14px;margin:0;font-family:Arial,sans-serif;max-width:${width-28}px;box-sizing:content-box">
<section id="tab-overview" class="active"><div id="overview-main-view">${entry}</div>${page}<div id="gateway-advanced-view" hidden></div></section><pre id="result" hidden>WAIT</pre>
<script>
const API_BASE='',nodes=id=>document.getElementById(id),requests=[],tick=ms=>new Promise(r=>setTimeout(r,ms));let tests=0,mode='ok',held=[],providerView='list';
function check(v,m){tests++;if(!v)throw Error(m)}
function copy(x){return JSON.parse(JSON.stringify(x))}
function esc(x){const n=document.createElement('div');n.textContent=String(x??'');return n.innerHTML.replaceAll('"','&quot;').replaceAll("'",'&#39;');}
${logos}
let state={ok:true,autoEnabled:true,day:'2026-11-13',supported:3,completed:1,running:false,accounts:[
 {provider:'workbuddy',providerName:'WorkBuddy',accountId:'a',name:'主账号',supported:true,outcome:'claimed',verified:true,credit:30,streak:3,message:'签到成功，连续签到3天',at:1794564000000,automatic:true},
 {provider:'raccoon',providerName:'小浣熊',accountId:'b',name:'<img src=x onerror=window.INJECTED=1>',supported:true,outcome:'idle',message:'等待签到'},
 {provider:'trae-cn',providerName:'Trae CN',accountId:'c',name:'工作账号',supported:true,outcome:'unknown',message:'领取结果未确认，今天不重复发送'},
 {provider:'workbuddy-ai',providerName:'WorkBuddy AI',accountId:'d',name:'国际账号',supported:false,outcome:'unsupported',message:'此版本没有签到接口'}]};
async function fetch(url,opts={}) {
 const body=opts.body?JSON.parse(opts.body):undefined,method=opts.method||'GET';requests.push({url,method,body});
 if(mode==='hold'&&url.endsWith('/status'))return new Promise(resolve=>held.push(resolve));
 if(mode==='lost'&&url.endsWith('/run'))throw Error('lost response');
 if(url.endsWith('/settings'))state.autoEnabled=body.autoEnabled;
 if(url.endsWith('/run')){state.running=true;state.accounts[1].outcome='running';}
 const data=copy(state);if(url.endsWith('/run'))data.accepted=true;
 return {ok:true,json:async()=>data};
}
${script}
${back}
(async()=>{
 await checkinLoad();check(nodes('checkin-auto-toggle').checked,'real default shown enabled');check(nodes('checkin-supported').textContent==='3','supported count');check(nodes('checkin-completed').textContent==='1','only confirmed success counted');check(nodes('checkin-remaining').textContent==='2','unknown is not completion');check(nodes('checkin-center-view').hidden,'secondary view initially hidden');check(requests.every(x=>x.method==='GET'),'reads and initial page never claim');
 check(nodes('checkin-accounts').querySelectorAll('.checkin-row').length===3,'three supported rows');check(nodes('checkin-unsupported-accounts').querySelectorAll('.checkin-row').length===1,'unsupported separate');check(nodes('checkin-accounts').textContent.includes('<img src=x'),'account label rendered literally');check(!nodes('checkin-accounts').querySelector('img[src="x"]')&&!window.INJECTED,'no name HTML execution');check(nodes('checkin-accounts').querySelectorAll('.checkin-credit').length===1,'no invented reward for unknown');
 const rows=[...nodes('checkin-accounts').querySelectorAll('.checkin-row')];
 check(rows.every((r,i)=>r.querySelector('.provider-logo')?.dataset.providerLogoId===state.accounts[i].provider),'each row uses its actual supplier icon');
 check(!nodes('checkin-accounts').textContent.includes('结果未确认'),'opaque unknown badge no longer appears');
 check(rows[2].querySelector('.checkin-status').textContent==='等待官方确认','unconfirmed claim stays honest');
 const cases=[
  {provider:'raccoon',phase:'status',outcome:'unknown',message:'已读取最近账单，但未找到今日签到正证；这不等于领取失败，请在官方客户端核对',badge:'暂未查到今日记录',phrase:'今天的签到奖励记录'},
  {provider:'loomy',phase:'status',outcome:'unknown',message:'已成功读取积分余额，但供应商没有独立的今日签到标记；余额不能当作签到成功证明',badge:'需到官方确认',phrase:'暂不提供今日签到状态'},
  {provider:'raccoon',phase:'status',outcome:'unknown',reasonCode:'no_today_record',message:'新查询说明',badge:'暂未查到今日记录',phrase:'今天的签到奖励记录'},
  {provider:'loomy',phase:'status',outcome:'unknown',reasonCode:'no_daily_status',message:'新查询说明',badge:'需到官方确认',phrase:'暂不提供今日签到状态'}];
 for(const c of cases){const row=checkinRow({...c,name:'状态测试',supported:true,retryBlocked:true,claimPossible:true},false);check(row.querySelector('.checkin-status').textContent===c.badge,'specific badge '+c.provider);check(row.querySelector('.checkin-result').textContent.includes(c.phrase),'plain explanation '+c.provider);check(row.querySelector('.checkin-status').dataset.tone==='neutral'&&!row.querySelector('.checkin-credit'),'missing status is neither failure nor success');check(row.querySelector('button').textContent==='查询状态','status gaps cannot send a new claim');}
 const failure=checkinRow({provider:'loomy',name:'网络测试',outcome:'failed',reasonCode:'dns',message:'查询失败：域名解析失败；未发送领取请求'},false);check(failure.querySelector('.checkin-result').textContent.includes('域名解析失败')&&failure.querySelector('.checkin-status').dataset.tone==='warn','real network error retains cause and warning');
 const malicious=checkinRow({provider:'bad\" onmouseover=window.INJECTED=1',providerName:'<img src=x onerror=window.INJECTED=1>',name:'<svg onload=window.INJECTED=1>',outcome:'unknown'},false);check(!malicious.querySelector('img')&&!malicious.querySelector('svg')&&!window.INJECTED&&!malicious.querySelector('[onmouseover]'),'unknown supplier fallback is escaped');
 const gallery=document.createElement('div');gallery.hidden=true;document.body.appendChild(gallery);
 for(const id of ['workbuddy','raccoon','trae-cn','loomy','minimax-code','zcode','workbuddy-ai']){const row=checkinRow({provider:id,providerName:id,name:'图标测试',outcome:'idle'},false);const icon=row.querySelector('.provider-logo'),ref=document.createElement('template');ref.innerHTML=providerLogo(id,id,true);check(icon.outerHTML===ref.content.firstElementChild.outerHTML,'same shared supplier logo '+id);gallery.appendChild(row);}
 await tick(200);check([...gallery.querySelectorAll('img')].length===7&&[...gallery.querySelectorAll('img')].every(i=>i.complete&&i.naturalWidth>0),'all seven bundled supplier images really load');gallery.remove();
 const saved=copy(state),postsBeforeProof=requests.filter(x=>x.method==='POST').length;
 state.accounts.push({provider:'loomy',providerName:'Loomy',accountId:'e',name:'额度账号',supported:true,outcome:'available',verified:false,dailyQuota:5000,credit:999,message:'余额可用不代表本次新奖励'});state.completed=999;
 state.operation={visible:true,id:'op-test',day:state.day,startedAt:1794564000000,running:false,newRewards:0,reusedConfirmed:1,confirmedWithoutNewReward:0,available:1,pending:2,failed:0,results:[{provider:'workbuddy',accountId:'a',message:'本次无需重复领取，沿用此前已确认结果。'}]};checkinRender(state);
 check(nodes('checkin-completed').textContent==='1'&&nodes('checkin-available').textContent==='1'&&nodes('checkin-remaining').textContent==='2','official confirmation, quota and pending counts are separate, ignoring an inflated server counter');
 const quotaRow=[...nodes('checkin-accounts').querySelectorAll('.checkin-row')].at(-1);check(!quotaRow.querySelector('.checkin-credit')&&quotaRow.querySelector('.checkin-quota').textContent.includes('5,000'),'quota is not invented reward');check(quotaRow.querySelector('button').textContent==='查询额度','available row reads quota rather than reclaiming');
 check(!nodes('checkin-operation').hidden&&nodes('checkin-operation-summary').textContent.includes('新增确认领取 0 个')&&nodes('checkin-operation-summary').textContent.includes('沿用已确认 1 个'),'manual operation summary does not advertise reused rewards as new');check(nodes('checkin-accounts').querySelector('.checkin-action').textContent.includes('本次无需重复'),'account distinguishes this click from daily state');
 state.operation.day='2026-11-12';checkinRender(state);check(nodes('checkin-operation').hidden&&!nodes('checkin-accounts').querySelector('.checkin-action'),'past operation is not shown as this day');
 const unverified=checkinRow({provider:'workbuddy',name:'旧记录',supported:true,outcome:'claimed',verified:'true',credit:100},false);check(unverified.querySelector('.checkin-status').textContent==='待官方核实'&&!unverified.querySelector('.checkin-credit')&&unverified.querySelector('button').textContent==='查询状态','string marker and old claimed state cannot become a success');
 check(requests.filter(x=>x.method==='POST').length===postsBeforeProof,'rendering summaries sends no mutation');state=saved;checkinRender(state);
 showCheckinCenter(true);await tick(30);check(!nodes('checkin-center-view').hidden&&nodes('overview-main-view').hidden,'opens secondary view');check(requests.every(x=>x.method==='GET'),'opening view has no claim');check(!nodes('checkin-accounts').querySelectorAll('button')[2].disabled&&nodes('checkin-accounts').querySelectorAll('button')[2].textContent==='查询状态','unknown account can query without blindly repeating claim');
 let before=requests.filter(x=>x.method==='POST').length;const work=checkinRun();checkinRun();await work;check(requests.filter(x=>x.method==='POST').length===before+1,'duplicate run clicks suppressed');check(nodes('checkin-run-all').disabled,'batch wait button disabled');check(requests.at(-1).url.endsWith('/run')&&Object.keys(requests.at(-1).body).length===0,'one explicit batch action');
 state.running=false;state.completed=2;state.accounts[1].outcome='done';state.accounts[1].verified=true;state.accounts[1].message='今日已签到';await checkinLoad();check(nodes('checkin-completed').textContent==='2','refresh adopts actual batch results');
 before=requests.filter(x=>x.url.endsWith('/run')).length;await checkinSetAuto(false);check(!nodes('checkin-auto-toggle').checked,'off persisted from server');check(requests.filter(x=>x.url.endsWith('/run')).length===before,'auto setting does not claim');check(requests.at(-1).body.autoEnabled===false,'exact boolean saved');
 mode='lost';before=requests.filter(x=>x.method==='POST').length;await checkinRun();check(nodes('checkin-error').textContent.includes('尚未确认'),'lost submission honest');await tick(20);check(requests.filter(x=>x.method==='POST').length===before+1,'no automatic mutation retry');mode='ok';await checkinLoad();
 mode='hold';const oldRead=checkinLoad(),newRead=checkinLoad();const fresh=copy(state);fresh.autoEnabled=false;held[1]({ok:true,json:async()=>fresh});await newRead;const stale=copy(state);stale.autoEnabled=true;held[0]({ok:true,json:async()=>stale});await oldRead;check(!nodes('checkin-auto-toggle').checked,'stale reads cannot resurrect auto flag');mode='ok';
 before=requests.filter(x=>x.method==='POST').length;check(window.allInHandleBack()===true,'Android back consumes secondary navigation');await tick(100);check(nodes('checkin-center-view').hidden&&!nodes('overview-main-view').hidden,'back restores overview');check(requests.filter(x=>x.method==='POST').length===before,'back makes no claims');
 state.autoEnabled=true;state.accounts[1].name='备用账号';state.accounts[1].outcome='idle';state.accounts[1].message='等待签到';state.completed=1;checkinRender(state);showCheckinCenter(true);await tick(30);check(document.documentElement.scrollWidth<=window.innerWidth,'no horizontal overflow at phone width');
 nodes('result').textContent=JSON.stringify({result:'PASS',checks:tests,width:window.innerWidth,contentWidth:document.body.getBoundingClientRect().width,theme:document.documentElement.dataset.theme});
})().catch(e=>{nodes('result').textContent='FAIL '+e.message});
<\/script></body></html>`;
const temp=path.join(root,'build','checkin-ui-test');const owned=!fs.existsSync(temp);fs.mkdirSync(temp,{recursive:true});
const previous=process.env.ALLIN_TEST_TMP;process.env.ALLIN_TEST_TMP=temp;
try{
 const out=require('./browser-fixture.cjs').dom(harness,3500,{windowSize:[width,1150],screenshot:process.env.ALLIN_CHECKIN_SCREENSHOT});
 const result=out.match(/<pre id="result" hidden(?:="")?>([\s\S]*?)<\/pre>/);assert(result,'DOM result absent');assert(!result[1].startsWith('FAIL'),result[1]);const data=JSON.parse(result[1]);assert.equal(data.result,'PASS');console.log(JSON.stringify(data));
}finally{if(previous===undefined)delete process.env.ALLIN_TEST_TMP;else process.env.ALLIN_TEST_TMP=previous;if(owned&&fs.readdirSync(temp).length===0)fs.rmdirSync(temp);}
