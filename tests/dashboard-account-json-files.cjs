const fs=require('fs'),assert=require('assert'),vm=require('vm');
const html=fs.readFileSync('app/src/main/assets/dashboard.html','utf8');
const code=html.slice(html.indexOf('    const ACCOUNT_FILE_PROVIDERS'),html.indexOf('    let currentModalProviderId'));
assert(code.includes('openAccountFileTransfer')&&code.includes('ensureAccountFileButtons'));
new vm.Script(code);
const page=`<!doctype html><div id="cards"></div><div id="legacy"><div id="legacy-actions"><button onclick="exportAccount('antigravity')">导出账号</button><button>移除账号</button></div></div><div id="trae"><button disabled>设备绑定登录</button><button disabled>加密备份迁移</button></div><pre id="result">WAIT</pre><script>
let checks=0, calls=[], toasts=[], fetches=0, copies=0, loads=0;
function check(ok,why){checks++;if(!ok)throw Error(why)}
function showToast(text){toasts.push(text)} async function loadConfig(){loads++}
async function fetch(){fetches++;throw Error('secret fetch forbidden')}
Object.defineProperty(navigator,'clipboard',{value:{writeText(){copies++;throw Error('clipboard forbidden')}}});
window.AllInNative={openAccountTransfer(action,id){calls.push({action,id});return true}};
${code}
(async()=>{
 for(const id of ACCOUNT_FILE_PROVIDERS){
  const card=document.createElement('div');document.querySelector('#cards').appendChild(card);
  ensureAccountFileButtons(card,id);ensureAccountFileButtons(card,id);
  const buttons=card.querySelectorAll('button');check(buttons.length===2,'exactly one pair for '+id);
  buttons[0].click();buttons[1].click();check(calls[calls.length-2].action==='import'&&calls[calls.length-1].action==='export','native actions '+id);
  check(calls[calls.length-1].id===id,'correct provider '+id);
 }
 check(calls.length===40,'twenty implemented suppliers have both actions');
 const card=document.createElement('div');ensureAccountFileButtons(card,'agentmore');check(card.children.length===0,'placeholder not pretending support');
 ensureAccountFileButtons(document.querySelector('#legacy'),'antigravity');
 const legacy=document.querySelectorAll('#legacy button');check(legacy.length===3,'missing import added without duplicate export');
 const imp=document.querySelector('#legacy [data-account-file-action=import]'),exp=document.querySelector('#legacy [data-account-file-action=export]');
 check(imp.parentElement===exp.parentElement,'import adjacent to existing export');check(imp.compareDocumentPosition(exp)&Node.DOCUMENT_POSITION_FOLLOWING,'import before export');
 ensureAccountFileButtons(document.querySelector('#trae'),'trae-cn');check([...document.querySelectorAll('#trae button')].every(b=>!b.disabled),'Trae placeholder actions become native file actions');
 const prior=calls.length;check(!openAccountFileTransfer('invalid-provider','import')&&calls.length===prior,'unknown provider not sent');check(!openAccountFileTransfer('workbuddy','delete')&&calls.length===prior,'unknown action blocked');
 check(fetches===0&&copies===0,'no plaintext credential response or clipboard transport');
 window.AllInNative.openAccountTransfer=()=>false;check(!openAccountFileTransfer('workbuddy','export'),'native false not success');
 window.AllInNative.openAccountTransfer=()=>'true';check(!openAccountFileTransfer('workbuddy','export'),'native string is not boolean acknowledgement');
 window.AllInNative.openAccountTransfer=()=>{throw Error('fixture-secret-should-not-show')};check(!openAccountFileTransfer('workbuddy','export'),'native failure caught');check(!toasts.join(' ').includes('fixture-secret'),'no raw failure detail leaked');
 delete window.AllInNative;check(!openAccountFileTransfer('workbuddy','export'),'browser unsupported is explicit, not fake download');
 check(fetches===0&&copies===0,'fallback does not fetch or expose credentials');
 for(const summary of [null,{ok:false,action:'import',saved:1},{ok:true,action:'export',saved:1},{ok:true,action:'import',saved:'1'},{ok:true,action:'import',saved:-1}])await window.onAccountTransferFinished(summary);
 check(loads===0,'invalid or export callback cannot claim import');await window.onAccountTransferFinished({ok:true,action:'import',saved:2});check(loads===1,'successful saved import refreshes real config');
 document.querySelector('#result').textContent='PASS '+checks;
})().catch(e=>document.querySelector('#result').textContent='FAIL '+e.message);
</script>`;
const dom=require('./browser-fixture.cjs').dom(page,3000);
const result=dom.match(/<pre id="result">([^<]*)<\/pre>/)?.[1];assert(result&&result.startsWith('PASS '),result);
const native=fs.readFileSync('app/src/main/java/com/gaeul/allin/AccountTransferController.java','utf8');
for(const marker of ['ACTION_CREATE_DOCUMENT','ACTION_OPEN_DOCUMENT','openOutputStream(uri,"wt")','Arrays.equals(bytes,readback)','expectedHash','onActivityResult','FLAG_SECURE'])assert(native.includes(marker),marker);
assert(!native.includes('ClipboardManager')&&!native.includes('Log.'));
const activity=fs.readFileSync('app/src/main/java/com/gaeul/allin/MainActivity.java','utf8');assert(activity.includes('public boolean openAccountTransfer'));assert(activity.includes('!bridgeAllowed()'));
console.log('dashboard account JSON files '+result+'; native SAF/privacy wiring verified');
