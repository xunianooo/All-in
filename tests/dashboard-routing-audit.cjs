const fs = require('fs'), vm = require('vm'), assert = require('assert');
const html = fs.readFileSync(require('path').join(__dirname, '../app/src/main/assets/dashboard.html'), 'utf8');
const text = html.slice(html.indexOf('    let routingRevision = 0;'), html.indexOf('    async function loadConfig()', html.indexOf('    let routingRevision = 0;')));
const elements = {'groups-enabled': {checked:false}, 'bare-fallback':{checked:false}, 'groups-disabled-hint':{style:{}}, 'groups-box':{style:{}}};
let pending = [], state = {enabled:false, bareModelFallback:false};
const context = { window:{}, document:{getElementById:id=>elements[id]||null}, API_BASE:'', showToast(){}, errText:String, loadConfig:async()=>{}, fetch:async(url,options)=>{
 if (options) {let patch=JSON.parse(options.body); if ('enabled' in patch) state.enabled=patch.enabled; if ('bareModelFallback' in patch) state.bareModelFallback=patch.bareModelFallback; return {json:async()=>({ok:true,...state})};}
 let snapshot={...state}; return new Promise(resolve=>pending.push(()=>resolve({json:async()=>snapshot})));
}};
vm.createContext(context); vm.runInContext(text,context);
(async()=>{const old=context.loadRoutingState(); const change=context.setBareFallback(true); await new Promise(r=>setImmediate(r)); assert.equal(pending.length,2); pending.shift()(); await old; assert.equal(elements['bare-fallback'].checked,false); pending.shift()(); await change; assert.equal(elements['bare-fallback'].checked,true); const exposed=html.slice(html.indexOf('    async function loadExposed()'),html.indexOf('    function onExposedToggle()',html.indexOf('    async function loadExposed()'))); assert(!exposed.includes('bare-fallback')); console.log('dashboard routing race and exposed isolation OK');})().catch(e=>{console.error(e);process.exitCode=1});
