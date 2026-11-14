const fs=require('fs'),assert=require('assert');
const html=fs.readFileSync('app/src/main/assets/dashboard.html','utf8');
const measure=process.argv.includes('--measure');
const source=html.replace(/<script\b[^>]*>[\s\S]*?<\/script>/gi,'');
const probe=`<script>
const cards=document.querySelector('#all-providers-cards-container');
const result=document.createElement('pre');result.id='badge-verification';
if(!cards){result.textContent='ERROR missing cards';document.body.appendChild(result)}else{
 document.body.replaceChildren(cards,result);cards.hidden=false;cards.style.display='block';
 const target=[...cards.querySelectorAll('.badge')].filter(n=>n.textContent.trim()==='已移植');
 const preserved=['catpaw','github-copilot','minimax-code','cline','codex','antigravity'].every(id=>{
  const n=document.getElementById('badge-count-'+id);return n&&n.textContent.includes('账号')&&n.getAttribute('onclick').includes('manageProviderAccounts');
 });
 const emptyGreen=[...cards.querySelectorAll('.badge-success')].filter(n=>!n.textContent.trim()).length;
 result.textContent=JSON.stringify({portedBadges:target.length,accountCountersPreserved:preserved,emptyGreenBadges:emptyGreen,bodyContainsTarget:cards.innerText.includes('已移植')});
}
</script>`;
const output=require('./browser-fixture.cjs').dom(source.replace(/<\/body>/i,probe+'</body>'),1000);
const match=output.match(/<pre id="badge-verification">([^<]*)<\/pre>/);assert(match,'DOM verification missing');
const data=JSON.parse(match[1].replace(/&quot;/g,'"').replace(/&amp;/g,'&'));
if(!measure){assert.equal(data.portedBadges,0);assert.equal(data.bodyContainsTarget,false);assert.equal(data.emptyGreenBadges,0);assert(data.accountCountersPreserved);assert(!html.includes('已移植'));}
console.log(JSON.stringify(data));
