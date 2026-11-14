const fs=require('fs'),vm=require('vm'),cp=require('child_process'),assert=require('assert');
const html=fs.readFileSync('app/src/main/assets/dashboard.html','utf8');
const fileTransferCode=html.slice(html.indexOf('    const ACCOUNT_FILE_PROVIDERS'),html.indexOf('    let currentModalProviderId'));
const start=html.lastIndexOf('<div class="card"',html.indexOf('data-provider-logo="loomy"'));
const card=html.slice(start,html.indexOf('<!--',start+1));
const view=html.slice(html.indexOf('    function applyProviderView()'),html.indexOf('    const providerBillingCache'));
const sync=html.slice(html.indexOf('    async function syncUniversalModels('),html.indexOf('    // 把倍率数字'));
const models=html.slice(html.indexOf('    // 把倍率数字'),html.indexOf('    function updateProviderKeys('));
const ping=html.slice(html.indexOf('    async function executeModelPingTest('),html.indexOf('    const WB_17_MODELS'));
const ids=html.slice(html.indexOf('    const PROVIDER_CARD_IDS ='),html.indexOf('    function permRow('));
const page=`<!doctype html><div id="provider-list-view"></div><div id="provider-subview"></div><div id="provider-catalog"><div id="provider-catalog-heading"></div><div id="all-providers-cards-container">${card}</div></div><div id="provider-detail-info"></div><pre id="result">WAIT</pre><script>
const API_BASE='', calls=[], alerts=[], events=[]; let providerView='detail', providerDetail='loomy', appConfig={providers:[{id:'loomy',name:'Loomy',accounts:[{token:'fixture'}],keys:[],models:['old']}]}, result={ok:true,provider:'loomy',count:1,models:['new'],message:'已同步'};
function providerConfigured(){return true} function providerCounts(p){return {accounts:p.accounts.length,keys:0,models:p.models.length}} function renderProviderBilling(){} function endpointProbeControls(){return ''} function hydrateEndpointProbe(){} function showLoomyLogin(){} function manageProviderAccounts(){} function getProviderName(){return 'Loomy'} function showToast(){} function errText(e,f){return e||f} function alert(x){alerts.push(x)}
const providerBillingCache={}, zcodeGiftModels=[]; function escapeHtml(x){return String(x)} function modelPingFailureText(){return 'failed'}
async function fetch(url,opts){calls.push({url,opts});return {json:async()=>url==='/api/test-model'?{ok:true,latencyMs:1,reply:'fixture'}:result}} async function loadConfig(){events.push('load');appConfig.providers[0].models=['new'];appConfig.providers[0].modelMeta={new:{name:'New Model',billing:{kind:'multiplier',scope:'model',value:3}}}}
${ids}
${models}
${ping}
${fileTransferCode}
${view}
${sync}
function check(x,m){if(!x)throw Error(m)}
async function flush(){await new Promise(r=>setTimeout(r,0))}
(async()=>{applyProviderView(); const staticButton=[...document.querySelector('[data-provider-id=loomy]').querySelectorAll('button')].find(b=>b.textContent.includes('同步官方模型')); check(staticButton,'card sync button'); staticButton.click();await flush();check(calls.length===1&&calls[0].url==='/api/auth/loomy/sync-models'&&calls[0].opts===undefined,'card GET sync');check(events.join(',')==='load'&&appConfig.providers[0].models[0]==='new','success refreshes config');const chip=document.querySelector('#models-tags-loomy .model-tag');check(chip&&chip.innerText.includes('New Model')&&chip.innerText.includes('×3'),'actual chip and multiplier');check(document.querySelector('#model-pool-summary-loomy').innerText.includes('共 1 个'),'model summary');chip.click();await flush();check(calls.length===2&&calls[1].url==='/api/test-model'&&calls[1].opts.method==='POST'&&JSON.parse(calls[1].opts.body).model==='loomy/new','actual probe API');
 result={ok:false,error:'目录暂不可用'};events.length=0;staticButton.click();await flush();check(events.length===0&&document.querySelector('#models-tags-loomy .model-tag').innerText.includes('×3'),'failed sync preserves rendered chips');
 document.querySelector('[data-provider-id=loomy]').remove();applyProviderView();const detailButton=[...document.querySelector('#provider-detail-info').querySelectorAll('button')].find(b=>b.textContent.includes('同步官方模型'));check(detailButton,'fallback detail sync button');result={ok:false,error:'目录暂不可用'};events.length=0;detailButton.click();await flush();check(calls.length===4&&calls[3].url==='/api/auth/loomy/sync-models','detail GET sync');check(events.length===0&&appConfig.providers[0].models[0]==='new'&&alerts.some(x=>x.includes('目录暂不可用')),'failure preserves catalog and reports error');document.querySelector('#result').textContent='PASS';})().catch(e=>document.querySelector('#result').textContent='FAIL '+e.message);
</script>`;
const out=require('./browser-fixture.cjs').dom(page,3000);
assert(out.includes('<pre id="result">PASS</pre>'),out.match(/<pre id="result">.*?<\/pre>/)?.[0]);console.log('Loomy sync DOM: card and detail GET, success refresh, failure preservation PASS');
