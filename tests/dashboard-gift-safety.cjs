const fs = require('fs'), vm = require('vm'), assert = require('assert');
const html = fs.readFileSync('app/src/main/assets/dashboard.html', 'utf8');
const extract = (a, b) => html.slice(html.indexOf(a), html.indexOf(b, html.indexOf(a)));
const box = {innerHTML: ''}, probe = {innerHTML: ''};
let response, rejection;
const ctx = vm.createContext({document:{getElementById: id => id === 'zcode-gift-body' ? box : probe}, API_BASE:'',
  fetch: async () => {if (rejection) throw rejection; return {json: async () => response};},
  renderAllModelsTags(){}, renderPingProviders(){}, setTimeout(){}, console});
vm.runInContext('Number.prototype.toLocaleString = function () { return String(this.valueOf()); }', ctx);
vm.runInContext(extract('    function esc(s)', '    function escHtml(s)'), ctx);
vm.runInContext(extract('    function errText(err, fallback)', '    function getProviderName(pid)'), ctx);
vm.runInContext(extract('    let zcodeGiftModels = []', '    // placeholder 供应商'), ctx);
vm.runInContext(extract('    async function antigravityProbe()', '    function antigravityLogin()'), ctx);
const bad = '<img src=x onerror=alert(1)>';
const safe = node => {assert(!node.innerHTML.includes('<img'), node.innerHTML); assert(node.innerHTML.includes('&lt;img'), node.innerHTML);};
(async () => {
  response = {ok:true, remainingUnits:0, buckets:[{name:bad, capabilities:[null, 42, {}, 'model:'+bad]}], claimable:[{name:bad}]};
  await ctx.loadZCodeGift(); safe(box); assert(box.innerHTML.includes('>0</b>'));
  response = {ok:true, buckets:[]}; await ctx.loadZCodeGift(); assert(box.innerHTML.includes('未知'));
  response = {ok:false,error:bad}; await ctx.loadZCodeGift(); safe(box); await ctx.claimZCodeGift(); safe(box);
  response = {ok:true,failed:[{error:bad}]}; await ctx.claimZCodeGift(); safe(box);
  rejection = new Error(bad); await ctx.loadZCodeGift(); safe(box); await ctx.claimZCodeGift(); safe(box); rejection = null;
  response = {ok:true, results:[{name:bad,alive:true,status:bad,note:bad},{name:bad,tries:bad,reason:bad}]};
  await ctx.antigravityProbe(); safe(probe); assert(!probe.innerHTML.includes(bad));
  console.log('dashboard gift and probe escaping passed');
})().catch(e=>{console.error(e);process.exitCode=1});
