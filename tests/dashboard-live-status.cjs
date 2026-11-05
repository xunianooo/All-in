const fs=require('fs'),vm=require('vm'),assert=require('assert');
const html=fs.readFileSync('app/src/main/assets/dashboard.html','utf8');
const part=(a,b)=>html.slice(html.indexOf(a),html.indexOf(b,html.indexOf(a)));
const elements={};
for(const id of ['tab-insight','usage-state','uk-tokens','stat-total-req','stat-total-tokens','stat-total-masked','cred-base-url','cred-lan-url','cred-api-key','cred-key-state','eta-key','eta-model','cred-new-key']) elements[id]={value:'',innerText:'',style:{},classList:{contains:()=>active}};
let active=true, hidden=false, handler, copied='';
const context={console,URL,API_BASE:'',location:{hostname:'192.168.1.8',port:'9999'},gatewayPort:3425,document:{get hidden(){return hidden},getElementById:id=>elements[id]||null,addEventListener:(n,f)=>{if(n==='visibilitychange')handler=f}},window:{AllInNative:{copyText:s=>{copied=s;return true}}},sessionStorage:{setItem(){}},AUTH_KEY:'',fetch:null,statsFailures:0,gatewayRunning:true,applyGatewayUI(){},setTimeout(){},clearTimeout(){}};
context.AllInNative=context.window.AllInNative;
vm.createContext(context);
vm.runInContext("Number.prototype.toLocaleString = function(){return String(this.valueOf())}",context);
vm.runInContext(part('    // ==================== 用量看板', '    function moveNavPill('),context);
vm.runInContext(part('    let authKeyVisible = false;', '    /** 一键复制：地址或密钥 */'),context);
vm.runInContext(part('    function copyCredential(', '    /** 统一的复制实现'),context);
vm.runInContext(part('    function startEditAuthKey()', '    async function loadStats()'),context);
vm.runInContext(part('    async function loadEtaCard()', '    function etaOpen()'),context);
vm.runInContext(part('    async function loadStats()', '    /**\n     * 配置没变'),context);
context.copyText=s=>copied=s;context.showToast=()=>{};
const response=x=>({ok:true,json:async()=>x});
(async()=>{
 let calls=[],pending=[];context.loadHeatmap=()=>{calls.push('heat');return Promise.resolve()};context.fetch=url=>{calls.push(url);return new Promise(r=>pending.push(r))};
 context.loadRecentSessions=async()=>{};let p=context.refreshInsightData();context.refreshInsightData();assert.equal(calls.length,2);pending.shift()(response({ok:true,totals:{tokens:1},rows:1}));await p;
 hidden=true;await context.refreshInsightData();assert.equal(calls.length,2);hidden=false;active=false;await context.refreshInsightData();assert.equal(calls.length,2);active=true;handler();assert.equal(calls.length,4);pending.shift()(response({ok:true,totals:{tokens:2},rows:1}));await new Promise(setImmediate);
 const a=context.loadUsage(1,'model'),b=context.loadUsage(30,'provider');pending.pop()(response({ok:true,totals:{tokens:30},rows:30}));await b;pending.pop()(response({ok:true,totals:{tokens:1},rows:1}));await a;assert.equal(elements['uk-tokens'].innerText,'30');
 context.fetch=async()=>({ok:false,status:500});await context.loadUsage();assert.match(elements['usage-state'].innerText,/刷新失败/);
 context.fetch=async()=>response({port:4567,ip:'192.168.1.10'});await context.loadStats();assert.equal(elements['cred-base-url'].value,'http://127.0.0.1:4567/v1');assert.equal(elements['cred-lan-url'].value,'http://192.168.1.10:4567/v1');context.copyCredential('lan');assert.equal(copied,elements['cred-lan-url'].value);context.copyCredential('base');assert.equal(copied,elements['cred-base-url'].value);
 for(const ip of ['', '0.0.0.0','127.0.0.1','::1','192.168.1.999','8.8.8.8','100.64.0.1','172.15.1.1','172.32.1.1']){context.fetch=async()=>response({port:9876,ip});await context.loadStats();assert.equal(elements['cred-lan-url'].value,'');}
 pending=[];context.fetch=()=>new Promise(r=>pending.push(r));const x=context.loadStats(),y=context.loadStats();pending[1](response({port:8888,ip:'10.0.0.1'}));await y;pending[0](response({port:1111,ip:'10.0.0.2'}));await x;assert.equal(elements['cred-lan-url'].value,'http://10.0.0.1:8888/v1');

 // Explicit endpoint fields are authoritative, including an empty LAN address.
 context.fetch=async()=>response({port:4567,ip:'192.168.1.10',localBaseUrl:'http://127.0.0.1:4567/v1',lanBaseUrl:''});await context.loadStats();assert.equal(elements['cred-lan-url'].value,'');
 for(const ip of ['169.254.1.1','192.168.01.1']) { context.fetch=async()=>response({port:4567,ip});await context.loadStats();assert.equal(elements['cred-lan-url'].value,''); }
 for(const port of [0,-1]) {context.fetch=async()=>response({port,ip:'192.168.1.10'});await context.loadStats();assert.equal(elements['cred-base-url'].value,'');copied='';context.copyCredential('base');assert.equal(copied,'');}
 context.fetch=async()=>response({port:4567,ip:'192.168.1.10',localBaseUrl:'javascript:alert(1)'});await context.loadStats();assert.equal(elements['cred-base-url'].value,'');
 context.fetch=async()=>response({port:4567,ip:'192.168.1.10'});await context.loadStats();context.fetch=async()=>{throw Error('offline')};await context.loadStats();assert.equal(elements['cred-lan-url'].value,'');assert.equal(elements['cred-base-url'].value,'');
 // Explicit LAN URLs and legacy fallback obey the same private IPv4 boundaries.
 for(const ip of ['8.8.8.8','100.64.0.1','172.15.0.1','172.32.0.1']) {context.fetch=async()=>response({port:4567,ip,localBaseUrl:'http://127.0.0.1:4567/v1',lanBaseUrl:'http://'+ip+':4567/v1'});await context.loadStats();assert.equal(elements['cred-lan-url'].value,'');}
 for(const ip of ['10.0.0.1','172.16.0.1','172.31.255.255','192.168.0.1']) {context.fetch=async()=>response({port:4567,ip,localBaseUrl:'http://127.0.0.1:4567/v1',lanBaseUrl:'http://'+ip+':4567/v1'});await context.loadStats();assert.equal(elements['cred-lan-url'].value,'http://'+ip+':4567/v1');}
 // ETA responses never set the key; a delayed key read cannot undo a successful save.
 context.fetch=async()=>response({port:4567,ip:'10.0.0.1'});await context.loadStats();
 let etaResolve,keyResolve;context.fetch=url=>new Promise(r=>{if(url.endsWith('/import'))etaResolve=r;else keyResolve=r});
 const eta=context.loadEtaCard(),oldKey=context.loadAuthKey();
 etaResolve(response({apiKey:'synthetic-old',recommendedModel:'fixture/model'}));await eta;
 assert.notEqual(elements['eta-key'].textContent,'synthetic-old');
 elements['cred-new-key'].value='synthetic-new';context.fetch=async()=>response({ok:true,authKey:'synthetic-new'});await context.saveAuthKey();
 keyResolve(response({ok:true,authKey:'synthetic-old'}));await oldKey;
 assert.equal(elements['eta-key'].textContent,'synthetic-new');context.etaCopyField('key');assert.equal(copied,'synthetic-new');context.etaCopyAll();assert(copied.includes('API Key: synthetic-new'));
 let slowEta;context.fetch=()=>new Promise(r=>slowEta=r);const etaLate=context.loadEtaCard();
 context.fetch=async()=>response({ok:true,authKey:''});await context.loadAuthKey();slowEta(response({apiKey:'synthetic-old',recommendedModel:'fixture/model'}));await etaLate;
 assert.equal(elements['eta-key'].textContent,'(未设置)');copied='unchanged';assert.equal(context.etaCopyField('key'),false);assert.equal(copied,'unchanged');context.etaCopyAll();assert(copied.includes('API Key: \n'));assert(!copied.includes('synthetic-old'));
 // The normal entry path invokes the shared refresh once, with no second sessions fetch.
 let sessionCalls=0;context.loadQuota=context.loadGuard=context.loadPermissions=context.loadMiddleware=()=>{};
 vm.runInContext(part('    function loadInsightAll()', '    /**\n     * 底部导航'),context);
 context.loadRecentSessions=async()=>{sessionCalls++};context.loadUsage=async()=>{};context.loadHeatmap=async()=>{};
 await context.refreshInsightData(true);context.loadInsightAll();assert.equal(sessionCalls,1);
 // Exercise the real recent-session renderer and revision guard.
 const recentEl={innerHTML:''},recentStatus={textContent:''};elements['recent-sessions']=recentEl;elements['recent-sessions-status']=recentStatus;
 vm.runInContext(part('    let recentSessionsRevision = 0;', '    function loadInsightAll()'),context);
 pending=[];context.fetch=()=>new Promise(r=>pending.push(r));const r1=context.loadRecentSessions(),r2=context.loadRecentSessions();
 pending[1](response({sessions:[]}));await r2;pending[0]({ok:false,status:500});await r1;assert.equal(recentStatus.textContent,'');
 context.fetch=async()=>({ok:false,status:503});await context.loadRecentSessions();assert.match(recentStatus.textContent,/保留上次记录/);assert.match(recentEl.innerHTML,/暂无请求记录/);
 assert(html.includes('onclick="refreshInsightData(true)"'));console.log('dashboard live status: visibility, overlap, races, errors, addresses and copying OK');
})().catch(e=>{console.error(e);process.exitCode=1});
