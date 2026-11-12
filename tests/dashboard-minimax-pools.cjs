// Render the production functions in a real headless browser, with hostile upstream data.
const fs = require('fs'), os = require('os'), path = require('path');
const {spawnSync} = require('child_process');
const html = fs.readFileSync('app/src/main/assets/dashboard.html', 'utf8');
function slice(a,b) { const start=html.indexOf(a), end=html.indexOf(b,start); if(start<0||end<0) throw Error('dashboard functions missing'); return html.slice(start,end); }
const functions = slice('    const providerBillingCache =', '    function renderProviderList()') + slice('    function billingNumber(v)', '    function billingFreshness(item)');
const test = `
const root=document.getElementById('root');
const poison='<img src=x onerror="window.injected=1">';
providerBillingCache['minimax-code']=billingSnapshot(null,{ok:true,models:{},accounts:[
 {accountId:'a',name:poison,allowance:{windows:[{name:'general',remainingPercent:40}],modelPools:[
  {name:poison,windows:[{name:'interval',remaining:0,total:100,unit:'count',remainingPercent:0,resetAt:1700000000000},{name:'weekly',remainingPercent:25,resetAt:'invalid'}]},
  {name:'second',windows:[{name:'weekly',remaining:3,total:10,unit:'count'}]},
  {name:'empty',windows:[]}
 ]}},
 {accountId:'b',name:'B',allowance:{modelPools:[{name:'other',windows:[{name:'interval',remainingPercent:60}]}]}}
]},1000);
renderProviderBilling(root,'minimax-code');
const lines=Array.from(root.querySelectorAll('p')).map(x=>x.textContent);
function check(c,m){if(!c)throw Error(m+' '+JSON.stringify(lines));}
check(lines.some(x=>x.includes('general 剩 40%')),'general');
check(lines.some(x=>x.includes(poison+' · 模型专属额度 · '+poison)&&x.includes('interval 剩 0 个 / 100 个（0%）')&&x.includes('weekly 剩 25%')&&x.includes('重置')),'model details');
check(lines.some(x=>x.includes('second')&&x.includes('3 个 / 10 个')),'second pool');
check(lines.some(x=>x.includes('B · 模型专属额度 · other')&&x.includes('60%')),'second account');
check(!lines.some(x=>x.includes('Invalid Date')||x.includes('模型专属额度 · empty')),'invalid or empty');
check(!root.querySelector('img,script')&&!window.injected,'injection');
providerBillingCache['minimax-code']=billingSnapshot(providerBillingCache['minimax-code'],null,2000);
root.replaceChildren(); renderProviderBilling(root,'minimax-code');
check(root.textContent.includes('刷新失败，显示上次快照')&&root.textContent.includes('上次快照')&&root.textContent.includes('other'),'stale snapshot');
document.body.dataset.result='PASS';`;
const fixtureHtml=`<!doctype html><html><body><main id="root"></main><script>${functions}\ntry {${test}} catch(e) {document.body.dataset.result='FAIL';document.body.dataset.error=e.message}</script></body></html>`;
const out=require('./browser-fixture.cjs').dom(fixtureHtml);
if(!out.includes('data-result="PASS"'))throw Error('MiniMax DOM regression failed: '+out);
console.log('MiniMax model pools Chromium DOM PASS');
