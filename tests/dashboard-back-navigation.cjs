const fs=require('fs'),vm=require('vm'),assert=require('assert');
const html=fs.readFileSync('app/src/main/assets/dashboard.html','utf8');
for(const m of html.matchAll(/<script>([\s\S]*?)<\/script>/g))new vm.Script(m[1]);
const start=html.indexOf('    window.allInHandleBack = function () {');
const end=html.indexOf('    function applyProviderView()',start);
assert(start>=0&&end>start);
const nodes={};let tabActive=true,closed=[],navigations=[];
for(const id of ['dup-account-modal','inapp-login-modal','account-manage-modal'])nodes[id]={hidden:false,style:{display:'none'}};
nodes['tab-providers']={classList:{contains:name=>name==='active'&&tabActive}};
const ctx=vm.createContext({window:{},document:{getElementById:id=>nodes[id]},providerView:'list',
 closeDupModal:()=>{closed.push('dup');nodes['dup-account-modal'].style.display='none'},
 closeInAppLogin:()=>{closed.push('login');nodes['inapp-login-modal'].style.display='none'},
 closeAccountModal:()=>{closed.push('accounts');nodes['account-manage-modal'].style.display='none'},
 showProviderView:view=>{navigations.push(view);ctx.providerView=view},
 showGatewayAdvanced:open=>{nodes['gateway-advanced-view'].hidden=!open},
 fetch:()=>{throw Error('back must not make network requests')}});
vm.runInContext(html.slice(start,end),ctx);
assert.equal(ctx.window.allInHandleBack(),false,'root uses native fallback');
for(const view of ['detail','add']){ctx.providerView=view;assert.equal(ctx.window.allInHandleBack(),true);assert.equal(ctx.providerView,'list')}
ctx.providerView='detail';tabActive=false;assert.equal(ctx.window.allInHandleBack(),false,'hidden provider state is not popped');tabActive=true;
nodes['account-manage-modal'].style.display='flex';assert.equal(ctx.window.allInHandleBack(),true);assert.equal(ctx.providerView,'detail','close account dialog before leaving detail');assert.equal(closed.at(-1),'accounts');
nodes['account-manage-modal'].style.display='flex';nodes['dup-account-modal'].style.display='flex';assert.equal(ctx.window.allInHandleBack(),true);assert.equal(closed.at(-1),'dup');assert.equal(nodes['account-manage-modal'].style.display,'flex');
nodes['account-manage-modal'].style.display='none';nodes['inapp-login-modal'].style.display='flex';assert.equal(ctx.window.allInHandleBack(),true);assert.equal(closed.at(-1),'login');
assert.equal(ctx.window.allInHandleBack(),true);assert.equal(ctx.window.allInHandleBack(),false,'next back at root can leave app');
nodes['tab-overview']={classList:{contains:()=>true}};
nodes['gateway-advanced-view']={hidden:false};
ctx.providerView='list';
assert.equal(ctx.window.allInHandleBack(),true,'advanced page returns to overview');
assert.equal(nodes['gateway-advanced-view'].hidden,true);
assert.equal(ctx.window.allInHandleBack(),false,'overview root delegates to native');
// Exercise real secondary-page navigation without fetching or saving settings.
let scrolled=[],focused=0;
nodes['overview-main-view']={hidden:false};
nodes['gateway-advanced-heading']={focus:()=>focused++};
ctx.window.scrollY=420;ctx.window.scrollTo=(...args)=>scrolled.push(args);
const navStart=html.indexOf('    let gatewayAdvancedScroll = 0;');
const navEnd=html.indexOf('    function switchTab(',navStart);
vm.runInContext(html.slice(navStart,navEnd),ctx);
ctx.showGatewayAdvanced(true);
assert.equal(nodes['overview-main-view'].hidden,true);
assert.equal(nodes['gateway-advanced-view'].hidden,false);
assert.equal(focused,1);
ctx.showGatewayAdvanced(false);
assert.equal(nodes['overview-main-view'].hidden,false);
assert.equal(nodes['gateway-advanced-view'].hidden,true);
assert.deepEqual(scrolled.at(-1),[0,420],'return restores overview scroll');
const advancedStart=html.indexOf('<div id="gateway-advanced-view" hidden>');
const advancedEnd=html.indexOf('</section>',advancedStart);
for(const card of ['affinity','resilience','outbound']) {
 const at=html.indexOf('id="card-'+card+'"');
 assert(at>advancedStart&&at<advancedEnd,card+' is only on the secondary page');
 assert.equal(html.split('id="card-'+card+'"').length,2,'no duplicate controls');
}
nodes['tab-gateway']={classList:{contains:()=>true}};ctx.switchTab=tab=>{assert.equal(tab,'overview');nodes['tab-gateway'].classList.contains=()=>false};assert.equal(ctx.window.allInHandleBack(),true,'gateway page returns to overview');
const native=fs.readFileSync('app/src/main/java/com/gaeul/allin/MainActivity.java','utf8');
assert(native.includes('public void onBackPressed()'));assert(native.includes('window.allInHandleBack()===true'));assert(native.includes('if (panel == null || !bridgeAllowed())'));assert(native.includes('if (!"true".equals(handled)) finishBackNavigation();'));assert(!native.includes('keyCode == KeyEvent.KEYCODE_BACK && webView'));
console.log('dashboard back navigation: detail/add/root/dialog priorities, hidden tab, no-network and native delegation PASS');
