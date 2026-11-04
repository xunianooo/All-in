const fs = require('fs');
const vm = require('vm');
const assert = require('assert');
const html = fs.readFileSync('app/src/main/assets/dashboard.html', 'utf8');
function extract(name) {
  const start = html.indexOf('    function ' + name + '(');
  assert(start >= 0, 'missing function ' + name);
  const next = html.indexOf('\n    }', start);
  assert(next >= 0);
  return html.slice(start, next + 6);
}
const names = ['fmtAgo','fmtClock','backgroundBrandHint','measuredPermission','keepaliveInfo','watchdogInfo','serviceHealthInfo','serviceHealthState','freezeRecordInfo','infoRow','permRow','fgsDescription','ackRow','setupPermRow'];
const ctx = {};
vm.createContext(ctx);
vm.runInContext(names.map(extract).join('\n'), ctx);
assert.match(ctx.backgroundBrandHint('miui'), /MIUI/);
for (const brand of ['coloros','vivo','huawei','honor','samsung','other']) assert.match(ctx.backgroundBrandHint(brand), /后台/);
assert.strictEqual(ctx.measuredPermission({ok:true}), 'info');
assert.strictEqual(ctx.measuredPermission({known:false,ok:true}), 'info');
assert.strictEqual(ctx.measuredPermission({known:true,ok:true}), true);
assert.strictEqual(ctx.measuredPermission({known:true,ok:false}), false);
assert.match(ctx.ackRow('a','b','autostart',true,0,''), /手动确认（未验证）/);
assert.match(ctx.ackRow('a','b','autostart',false,0,''), /未知/);
assert.match(ctx.setupPermRow('info','a','b','autostart',true,0), /手动确认（未验证）/);
assert.strictEqual(ctx.keepaliveInfo({scheduled:true,lastCheckAt:1,lastCheckAgeSeconds:901,intervalSeconds:900}).tone, 'warn');
assert.match(ctx.keepaliveInfo({scheduled:true}).desc, /可能延迟/);
assert.strictEqual(ctx.watchdogInfo({alive:true}).tone, 'unknown');
assert.match(ctx.watchdogInfo({alive:true}).desc, /未验证/);
assert.strictEqual(ctx.watchdogInfo({alive:true,verifiedTarget:true}).tone, 'unknown');
assert.match(ctx.serviceHealthInfo({running:true}), /监听存活：未知/);
assert.match(ctx.serviceHealthInfo({listenerAlive:true, pid:42}), /监听存活：是/);
assert.match(ctx.serviceHealthInfo({health:{lastProbeAt:0,lastProbeCode:0}}), /探测码：未知/);
assert.match(ctx.serviceHealthInfo({health:{healthState:'<bad>'}}), /&lt;bad&gt;/);
for (const name of ['specialUse', 'dataSync', 'legacy']) {
  assert.match(ctx.fgsDescription({name, ok:true}), new RegExp('实际类型：' + name));
  assert.match(ctx.fgsDescription({name, ok:true}), /不保证免于冻结/);
}
assert.match(ctx.fgsDescription({name:'none', ok:false, expectedName:'specialUse'}), /实际类型：未知/);
assert.doesNotMatch(ctx.fgsDescription({name:'none', ok:false, expectedName:'specialUse'}), /实际类型：specialUse/);
assert.match(ctx.fgsDescription({}), /实际类型：未知/);
assert.match(ctx.fgsDescription({name:'<img>', ok:false}), /实际类型：未知/);
assert.match(ctx.fgsDescription({name:'legacy',ok:true,typed:false}), /已确认旧版前台服务启动/);
const healthyService = {running:true, port:3425, health:{enabled:true, foreground:true, listenerAlive:true,
  healthState:'healthy', lastProbeAt:1000, lastProbeAgeSeconds:29, lastProbeCode:200, consecutiveFailures:0}};
assert.strictEqual(ctx.serviceHealthState(healthyService).tone, 'ok');
assert.match(ctx.serviceHealthState(healthyService).badge, /当前正常/);
for (const patch of [{lastProbeAgeSeconds:91}, {lastProbeCode:502}, {listenerAlive:false},
  {consecutiveFailures:1}, {lastProbeAgeSeconds:-1}, {lastProbeAgeSeconds:NaN}, {lastProbeAt:0},
  {lastProbeAgeSeconds:undefined}, {foreground:undefined}, {healthState:undefined}]) {
  assert.notStrictEqual(ctx.serviceHealthState({...healthyService,health:{...healthyService.health,...patch}}).tone, 'ok', JSON.stringify(patch));
}
assert.strictEqual(ctx.serviceHealthState({running:true}).tone, 'unknown');
assert.strictEqual(ctx.serviceHealthState({running:false,health:{enabled:false}}).badge, '· 已关闭');
assert.strictEqual(ctx.serviceHealthState({running:false,health:{healthState:'starting',listenerAlive:false}}).badge, '· 检测／恢复中');
assert.strictEqual(ctx.serviceHealthState({running:false,health:{healthState:'unhealthy'}}).tone, 'warn');
const goodCheck = {scheduled:true,lastCheckAt:1000,lastCheckAgeSeconds:29,lastAlive:true,lastCode:200,intervalSeconds:900};
assert.strictEqual(ctx.keepaliveInfo(goodCheck).tone, 'ok');
assert.match(ctx.keepaliveInfo(goodCheck).desc, /HTTP 200，成功/);
assert.strictEqual(ctx.keepaliveInfo({...goodCheck,lastCheckAgeSeconds:90}).tone, 'ok');
assert.notStrictEqual(ctx.keepaliveInfo({...goodCheck,lastCheckAgeSeconds:91}).tone, 'ok');
assert.strictEqual(ctx.keepaliveInfo({...goodCheck,lastAlive:false}).tone, 'warn');
assert.strictEqual(ctx.keepaliveInfo({...goodCheck,lastCode:401}).tone, 'warn');
for (const patch of [{lastCheckAt:0}, {lastCheckAgeSeconds:-1}, {lastCheckAgeSeconds:NaN},
  {scheduled:undefined}, {lastAlive:undefined}, {scheduled:false}])
  assert.notStrictEqual(ctx.keepaliveInfo({...goodCheck,...patch}).tone, 'ok');
assert.match(ctx.keepaliveInfo({scheduled:true}).badge, /已请求排程/);
assert.strictEqual(ctx.freezeRecordInfo({}).badge, '· 暂无记录');
assert.match(ctx.freezeRecordInfo({unfreezeAgeSeconds:10}).desc, /不代表从未/);
assert.strictEqual(ctx.freezeRecordInfo({verifiedTarget:true,unfreezeAgeSeconds:10}).tone, 'warn');
const box = {innerHTML:''};
const payload = {ok:true, permissions:{notifications:{known:true,ok:true}, battery:{known:true,ignored:true,ok:true},
  foregroundService:{name:'specialUse',ok:true}, autostart:{acked:true},powersave:{acked:true}},
  service:healthyService, keepalive:goodCheck, watchdog:{alive:true,verifiedTarget:false}};
ctx.document = {getElementById: id => id === 'perm-list' ? box : null};
ctx.API_BASE='';
ctx.fetch=async()=>({json:async()=>payload});
const start=html.indexOf('    async function loadPermissions()');
const end=html.indexOf('    async function loadGuard()',start);
assert(start>=0 && end>start);
vm.runInContext(html.slice(start,end),ctx);
(async()=>{
  await ctx.loadPermissions();
  assert.match(box.innerHTML, /最近自检成功/);
  assert.match(box.innerHTML, /当前正常/);
  assert.match(box.innerHTML, /暂无记录/);
  assert.match(box.innerHTML, /未验证/);
  assert.match(box.innerHTML, /不代表从未被冻结/);
  assert.doesNotMatch(box.innerHTML, /保证永不/);
  payload.service={running:true,health:{healthState:'healthy'}};
  payload.keepalive={scheduled:true};
  await ctx.loadPermissions();
  assert.doesNotMatch(box.innerHTML, /当前正常|最近自检成功/);
  assert.match(box.innerHTML, /尚未确认/);
  assert.match(box.innerHTML, /已请求排程/);
  payload.keepalive={...goodCheck,lastCheckAgeSeconds:901};
  payload.service={...healthyService,health:{...healthyService.health,lastProbeAgeSeconds:91}};
  await ctx.loadPermissions();
  assert.match(box.innerHTML, /自检逾期/);
  assert.match(box.innerHTML, /健康记录过期/);
  console.log('dashboard background states and rendered permission rows passed');
})().catch(error=>{console.error(error);process.exitCode=1});
