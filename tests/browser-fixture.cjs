const fs=require('fs'),os=require('os'),path=require('path'),cp=require('child_process');
const {pathToFileURL}=require('url');
function browser(){
 const candidates=[process.env.ALLIN_CHROME,process.env.CHROME_BIN,'C:/Program Files/Google/Chrome/Application/chrome.exe','C:/Program Files (x86)/Google/Chrome/Application/chrome.exe','C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe','chromium','chromium-browser','google-chrome','google-chrome-stable'].filter(Boolean);
 for(const command of candidates){
  if(fs.existsSync(command)&&fs.statSync(command).isFile())return command;
  for(const dir of (process.env.PATH||'').split(path.delimiter))for(const suffix of process.platform==='win32'?['','.exe','.cmd']:['']){
   const file=path.join(dir,command+suffix);if(fs.existsSync(file)&&fs.statSync(file).isFile())return file;
  }
 }
 throw new Error('No headless Chromium browser found. Set ALLIN_CHROME or CHROME_BIN.');
}
function dom(html,virtualTimeBudget){
 const base=process.env.ALLIN_TEST_TMP||os.tmpdir();fs.mkdirSync(base,{recursive:true});
 const dir=fs.mkdtempSync(path.join(base,'allin-browser-test-'));
 try{
  const file=path.join(dir,'test.html');fs.writeFileSync(file,html);
  const args=['--headless','--no-sandbox','--disable-gpu','--disable-dev-shm-usage','--no-first-run','--no-default-browser-check','--user-data-dir='+path.join(dir,'profile'),'--dump-dom'];
  if(virtualTimeBudget)args.push('--virtual-time-budget='+virtualTimeBudget);
  args.push(pathToFileURL(file).href);
  const r=cp.spawnSync(browser(),args,{encoding:'utf8',timeout:60000});
  if(r.error || r.status!==0)throw new Error('Browser regression failed: '+(r.error?r.error.message:r.stderr));
  return r.stdout;
 }finally{fs.rmSync(dir,{recursive:true,force:true});}
}
module.exports={dom};
