const fs=require('fs'),vm=require('vm'),cp=require('child_process'),assert=require('assert');
const html=fs.readFileSync('app/src/main/assets/dashboard.html','utf8');
for(const m of html.matchAll(/<script>([\s\S]*?)<\/script>/g))new vm.Script(m[1]);
const loomyCard=html.slice(html.lastIndexOf('<div class="card"',html.indexOf('data-provider-logo="loomy"')),html.indexOf('<!--',html.indexOf('data-provider-logo="loomy"')));
assert(loomyCard.includes('data-provider-id="loomy"'), 'Loomy card must be identifiable before logo initialization');
const viewCode=html.slice(html.indexOf('    function applyProviderView()'),html.indexOf('    const providerBillingCache'));
const code=html.slice(html.indexOf('    // Account operations:'),html.indexOf('    function closeAccountModal()'));
const harness=`<!doctype html><div id="item1"></div><div id="item2"></div><div id="provider-list-view"></div><div id="provider-subview"></div><div id="provider-catalog"><div id="provider-catalog-heading"></div><div id="all-providers-cards-container">${loomyCard}</div></div><div id="provider-detail-info"></div><pre id="result">WAIT</pre><script>
const API_BASE='', calls=[], responses=[]; let providerView='add', providerDetail='', appConfig={providers:[{id:'loomy',accounts:[],keys:[],models:[]}]};
function providerConfigured(p){return !!(p.accounts.length||p.keys.length)} function providerCounts(p){return {accounts:p.accounts.length,keys:p.keys.length,models:p.models.length}} function renderProviderBilling(){} function endpointProbeControls(){return ''} function hydrateEndpointProbe(){}
${viewCode} let currentModalProviderId=null;
let order=[]; async function loadConfig(){ order.push('load'); await Promise.resolve(); appConfig.providers[0].accounts.push({id:'new'}); order.push('loaded'); } function refreshProviderBadges(){order.push('badges')} function closeAccountModal(){order.push('close-account')} function showProviderView(view){order.push('view:'+view); providerView=view; check(appConfig.providers[0].accounts.length>0,'account visible before navigation')} function renderAccountModalList(){} function confirm(){return true}
async function fetch(url,opts){calls.push({url,body:JSON.parse(opts.body)});return responses.shift()}
${code}
function response(x,ok=true,status=200){return {ok,status,json:async()=>x}}
function check(b,m){if(!b)throw Error(m)}
(async()=>{
 applyProviderView(); check(!document.querySelector('[data-provider-id=loomy]').hidden && !document.querySelector('#provider-catalog').hidden,'unconfigured Loomy appears in add view before logo init');
 addAccountOperations(document.querySelector('#item1'),'raccoon','a1'); addAccountOperations(document.querySelector('#item2'),'raccoon','a2');
 let one=document.querySelector('#item1'),two=document.querySelector('#item2');
 check(one.textContent.includes('可能触发每日赠送'),'fallback notice');
 responses.push(response({ok:true,accountId:'a1',balance:12})); await one.querySelectorAll('button')[0].onclick();
 check(calls[0].body.accountId==='a1' && one.textContent.includes('余额：12') && !two.textContent.includes('余额：12'),'account isolation');
 responses.push(response({ok:true,accountId:'a1',result:{outcome:'unknown'}})); await one.querySelectorAll('button')[1].onclick();check(one.textContent.includes('待官方核实'),'unknown is not reported as success');
 responses.push(response({ok:false},false,409)); await one.querySelectorAll('button')[2].onclick();check(one.textContent.includes('可能未知'),'onboarding conflict');
 showLoomyLogin();let inputs=document.querySelectorAll('input'), buttons=[...document.body.querySelectorAll('button')].slice(-3);
 inputs[0].value='13812345678';responses.push(response({ok:true,flowId:'flow'}));await buttons[0].onclick();check(inputs[0].value==='13812345678' && buttons[1].disabled,'phone retained after send');
 inputs[1].value='123456';inputs[1].dispatchEvent(new Event('input'));check(!buttons[1].disabled,'code enables login');responses.push(response({ok:true,accountId:'new'}));await buttons[1].onclick();check(inputs[0].value==='' && inputs[1].value==='' && !inputs[0].isConnected && order.join(',')==='close-account,load,loaded,badges,view:list','success clears and closes before refresh and navigation');
 check(calls[3].url.endsWith('/loomy/send-code') && calls[4].body.flowId==='flow' && calls[4].body.code==='123456','sms contract');
 check(!document.body.innerText.includes('13812345678') && !document.body.innerText.includes('123456'),'no secrets in DOM');
 showLoomyLogin(); inputs=[...document.querySelectorAll('input')].slice(-2); buttons=[...document.body.querySelectorAll('button')].slice(-3);
 inputs[0].value='13812345678'; responses.push(response({},false,403)); await buttons[0].onclick(); check(document.body.textContent.includes('仅限手机本机') && buttons[1].disabled,'remote send denied');
 responses.push(response({ok:true,flowId:'flow2'})); await buttons[0].onclick(); inputs[1].value='123456'; inputs[1].dispatchEvent(new Event('input')); responses.push(response({},false,403)); await buttons[1].onclick(); check(!buttons[1].disabled && inputs[1].value==='123456','403 does not consume flow');
 responses.push(response({ok:false,error:'Loomy 请求失败'})); await buttons[1].onclick(); check(!buttons[1].disabled && inputs[0].value==='13812345678' && inputs[1].value==='123456' && document.body.textContent.includes('Loomy 请求失败'),'provider failure shown and credentials retained');
 responses.push(response({error:{message:'登录流程正在处理',type:'gateway_error'}},false,409)); await buttons[1].onclick(); check(!buttons[1].disabled && document.body.textContent.includes('登录流程正在处理'),'nested error shown and flow retained');
 inputs[0].value='13912345678'; responses.push(response({error:{message:'Too many pending requests'}},false,429)); await buttons[0].onclick(); check(document.body.textContent.includes('Too many pending requests') && !buttons[1].disabled,'failed resend preserves flow');
 responses.push(response({ok:false,stage:'verify',flowExpired:false,retryable:true,error:'登录流程已失效，请重新发送验证码'})); await buttons[1].onclick(); check(!buttons[1].disabled,'structured retryable overrides legacy wording');
 responses.push(response({ok:false,stage:'save',flowExpired:true,retryable:false,error:'账号保存结果未确认，请检查账号列表后重新登录'})); await buttons[1].onclick(); check(buttons[1].disabled && document.body.textContent.includes('账号保存结果未确认'),'save failure consumes flow');
 let noRetry=calls.length; await buttons[1].onclick(); check(calls.length===noRetry,'consumed save flow cannot be retried');
 responses.push(response({ok:true,flowId:'flow-session'})); await buttons[0].onclick(); inputs[1].value='123456'; inputs[1].dispatchEvent(new Event('input'));
 responses.push(response({ok:false,stage:'session',flowExpired:true,retryable:false,error:'登录结果无效，请重新发送验证码'})); await buttons[1].onclick(); check(buttons[1].disabled,'session failure consumes flow');
 responses.push(response({ok:true,flowId:'flow-legacy'})); await buttons[0].onclick(); inputs[1].value='123456'; inputs[1].dispatchEvent(new Event('input'));
 responses.push(response({error:{message:'登录流程已失效，请重新发送验证码'}},false,400)); await buttons[1].onclick(); check(buttons[1].disabled && document.body.textContent.includes('登录流程已失效'),'expired flow cleared');
 const before=calls.length; await buttons[1].onclick(); check(calls.length===before,'expired flow cannot be retried');
 responses.push(response({ok:true,flowId:'flow3'})); await buttons[0].onclick(); inputs[1].value='654321'; inputs[1].dispatchEvent(new Event('input'));
 responses.push({ok:true,status:200,json:async()=>{throw Error('invalid JSON')}}); await buttons[1].onclick(); check(!buttons[1].disabled && document.body.textContent.includes('登录结果未确认'),'invalid JSON remains uncertain');
 responses.push(response({ok:true,accountId:'new2'})); await buttons[1].onclick(); check(calls[calls.length-1].body.flowId==='flow3' && !inputs[0].isConnected && inputs[0].value==='' && inputs[1].value==='','retry uses original flow and success clears');
 showLoomyLogin(); inputs=[...document.querySelectorAll('input')].slice(-2); buttons=[...document.body.querySelectorAll('button')].slice(-3); inputs[0].value='13812345678'; inputs[1].value='123456'; buttons[2].onclick(); check(!inputs[0].isConnected && inputs[0].value==='' && inputs[1].value==='','close clears sensitive fields');
 responses.push(response({},false,403)); await one.querySelectorAll('button')[0].onclick(); check(one.textContent.includes('仅限手机本机'),'account operation local restriction');
 let loomy=document.createElement('div');document.body.appendChild(loomy);addAccountOperations(loomy,'loomy','l1');responses.push(response({ok:true,accountId:'l1',wallet:{dailyRemaining:5}}));await loomy.querySelectorAll('button')[0].onclick();check(loomy.textContent.includes('每日剩余：5'),'wallet');
 check(loomy.querySelectorAll('button')[1].textContent==='刷新每日额度','Loomy button names the actual operation');
 responses.push(response({ok:true,accountId:'l1',result:{outcome:'available',dailyQuota:5000,credit:999}}));await loomy.querySelectorAll('button')[1].onclick();check(loomy.textContent.includes('不代表本次新奖励')&&!loomy.textContent.includes('：999'),'wallet information is not shown as a reward');
 responses.push(response({ok:true,accountId:'a1',result:{outcome:'claimed',credit:999}}));await one.querySelectorAll('button')[1].onclick();check(one.textContent.includes('待官方核实')&&!one.textContent.includes('：999'),'unverified claimed marker is not accepted');
 responses.push(response({ok:false,expired:true}));await loomy.querySelectorAll('button')[1].onclick();check(loomy.textContent.includes('登录已失效'),'expired');
 document.querySelector('#result').textContent='PASS';
})().catch(e=>document.querySelector('#result').textContent='FAIL '+e.message);
</script>`;
const out=require('./browser-fixture.cjs').dom(harness,3000);
assert(out.includes('<pre id="result">PASS</pre>'),out.match(/<pre id="result">.*?<\/pre>/)?.[0]);console.log('dashboard account operations DOM/API passed');
