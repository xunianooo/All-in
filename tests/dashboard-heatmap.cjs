const fs=require('fs'),vm=require('vm'),assert=require('assert'),path=require('path');
const html=fs.readFileSync(path.join(__dirname,'../app/src/main/assets/dashboard.html'),'utf8');
const source=html.slice(html.indexOf('    // ==================== 每日用量热力图'),html.indexOf('    // ==================== 用量看板'));
class Element {
 constructor(){this.children=[];this.dataset={};this.attrs={};this.scrollLeft=0;this.scrollWidth=1056;this.textContent='';}
 getClientRects(){return this.hidden ? [] : [{}];}
 appendChild(e){this.children.push(e);return e;}
 replaceChildren(){this.children=[];}
 setAttribute(k,v){this.attrs[k]=v;}
}
const ids=['heat-status','heat-total','heat-note','heat-grid','heat-months','heat-scroll','heat-detail','heat-tokens','heat-requests'];
function page(){const elements=Object.fromEntries(ids.map(id=>[id,new Element()]));const ctx={document:{getElementById:id=>elements[id],createElement:()=>new Element()},API_BASE:'',console};vm.createContext(ctx);vm.runInContext(source,ctx);return {ctx,e:elements};}
function fixture(){const p=page();const start='2025-11-03',end='2026-11-04';const days=Array.from({length:367},(_,i)=>({date:p.ctx.heatDateOffset(start,i),tokens:0,input:0,output:0,requests:0,failed:0}));Object.assign(days[366],{input:3000000000,output:999,tokens:3000000999,requests:79,failed:2});return {ok:true,weeks:53,start,end,timezone:'Asia/Shanghai',firstRecordedDate:end,days,totals:{input:3000000000,output:999,tokens:3000000999,requests:79,failed:2}};}
const response=data=>({ok:true,json:async()=>structuredClone(data)});
(async()=>{
 const {ctx:c,e}=page(),data=fixture();c.fetch=async()=>response(data);await c.loadHeatmap();
 assert.equal(e['heat-grid'].children.length,371);assert.equal(e['heat-grid'].children.filter(x=>x.type==='button').length,367);assert.equal(e['heat-months'].children.length,53);assert.equal(e['heat-scroll'].scrollLeft,1056);
 const today=e['heat-grid'].children[366];assert.equal(today.dataset.level,'4');assert.equal(today.attrs['aria-pressed'],'true');assert.match(today.attrs['aria-label'],/3,000,000,999 Token/);assert.equal(e['heat-grid'].children[367].attrs['aria-hidden'],'true');assert.match(e['heat-detail'].children[0].children[1].textContent,/3,000,000,999/);
 assert.equal(c.heatDateOffset('2024-02-28',1),'2024-02-29');assert.equal(c.heatDateOffset('2026-03-08',1),'2026-03-09');assert.equal(c.heatDateOffset('2026-12-31',1),'2027-01-01');
 for(const [n,want] of [[0,0],[1,1],[25,1],[26,2],[50,2],[51,3],[75,3],[76,4],[100,4]])assert.equal(c.heatLevel(n,100),want);
 e['heat-scroll'].scrollLeft=200;e['heat-grid'].children[0].onclick();c.setHeatMetric('requests');assert.equal(e['heat-scroll'].scrollLeft,200);assert.equal(e['heat-requests'].attrs['aria-pressed'],'true');assert.equal(e['heat-grid'].children[0].attrs['aria-pressed'],'true');assert.match(e['heat-detail'].children[1].textContent,/无已记录调用/);assert.match(e['heat-total'].textContent,/79 次请求/);
 // Request activity with no reported usage remains visible in request mode, not invented token mode.
 assert.equal(c.heatLevel(0,1),0);
 const before=e['heat-grid'].children[0];await c.loadHeatmap();assert.strictEqual(e['heat-grid'].children[0],before,'unchanged refresh must preserve DOM and scroll');
 const invalid=fixture();invalid.totals.tokens++;c.fetch=async()=>response(invalid);await c.loadHeatmap();assert.match(e['heat-status'].textContent,/刷新失败，保留上次数据/);assert.strictEqual(e['heat-grid'].children[0],before);
 for(const alter of [d=>d.days[1].date=d.days[0].date,d=>d.days[0].tokens=-1,d=>d.days[0].requests=Number.MAX_SAFE_INTEGER+1,d=>d.end='2026-11-05',d=>d.days[0].failed=1]){const bad=fixture();alter(bad);assert.throws(()=>c.validateHeatmap(bad));}
 const pending=[];c.fetch=()=>new Promise(resolve=>pending.push(resolve));const first=c.loadHeatmap(),second=c.loadHeatmap();const fresh=fixture();fresh.readErrors=1;pending[1](response(fresh));await second;pending[0]({ok:false,status:500});await first;assert.match(e['heat-status'].textContent,/部分账本异常/);assert.match(e['heat-note'].textContent,/读取 1/);
 const empty=fixture();empty.days=empty.days.map(d=>({...d,tokens:0,input:0,output:0,requests:0,failed:0}));empty.totals={tokens:0,input:0,output:0,requests:0,failed:0};empty.firstRecordedDate=null;c.fetch=async()=>response(empty);await c.loadHeatmap();assert.match(e['heat-note'].textContent,/尚无调用记录/);assert.match(e['heat-total'].textContent,/0 个活跃日/);
 const hidden=page();hidden.e['heat-grid'].hidden=true;hidden.ctx.fetch=async()=>response(data);await hidden.ctx.loadHeatmap();assert.equal(hidden.e['heat-grid'].children.length,0);hidden.e['heat-grid'].hidden=false;await hidden.ctx.loadHeatmap();assert.equal(hidden.e['heat-grid'].children.length,371);assert.equal(hidden.e['heat-scroll'].scrollLeft,1056,'hidden response must not consume initial latest positioning');
 const broken=page();broken.ctx.fetch=async()=>({ok:false,status:503});await broken.ctx.loadHeatmap();assert.match(broken.e['heat-total'].textContent,/暂无可用数据/);assert.equal(broken.e['heat-grid'].children.length,0);
 for(const m of html.matchAll(/<script\b[^>]*>([\s\S]*?)<\/script>/gi))new vm.Script(m[1]);
 assert(html.indexOf('id="usage-card"')<html.indexOf('id="usage-heatmap-card"'));assert(html.indexOf('id="usage-heatmap-card"')<html.indexOf('id="exposed-card"'));
 console.log('heatmap: exact totals, calendar, levels, selection, mobile scroll, races, errors, empty state and scripts OK');
})().catch(e=>{console.error(e);process.exitCode=1});
