// The same original 34 regressions on Windows and Unix, with isolated browser profiles.
const fs=require('fs'),path=require('path'),cp=require('child_process');
const root=path.resolve(__dirname,'..');
const scripts=[
  "tests/dashboard-setup-key.cjs",
  "tests/dashboard-back-navigation.cjs",
  "tests/dashboard-endpoint-probe.cjs",
  "tests/dashboard-model-diagnostics.cjs",
  "tests/dashboard-gift-safety.cjs",
  "tests/dashboard-model-names.cjs",
  "tests/dashboard-model-billing.cjs",
  "tests/dashboard-routing-audit.cjs",
  "tests/dashboard-exposed.cjs",
  "tests/dashboard-background.cjs",
  "tests/dashboard-background-experiment.cjs",
  "tests/dashboard-heatmap.cjs",
  "tests/dashboard-live-status.cjs",
  "tests/dashboard-provider-management.cjs",
  "tests/dashboard-network.cjs",
  "tests/dashboard-usage-routing.cjs",
  "tests/dashboard-outbound.cjs",
  "tests/dashboard-resilience.cjs",
  "tests/dashboard-diagnostics.cjs",
  "tests/dashboard-combined.cjs",
  "tests/dashboard-q2.cjs",
  "tests/dashboard-image-body.cjs",
  "tests/dashboard-trae.cjs",
  "tests/dashboard-account-ops.cjs",
  "tests/dashboard-loomy-sync.cjs",
  "tests/dashboard-failover-save.cjs",
  "tests/dashboard-gateway-access.cjs",
  "tests/dashboard-listen-plan.cjs",
  "tests/dashboard-management-boundary.cjs",
  "tests/dashboard-minimax-pools.cjs",
  "tests/dashboard-workbuddy-harness.cjs",
  "tests/trae-login-recovery.cjs",
  "scripts/beta-dashboard-xss.cjs",
  "tests/dashboard-legacy-key-migration.cjs"
];
const build=path.join(root,'build');fs.mkdirSync(build,{recursive:true});
const temporary=fs.mkdtempSync(path.join(build,'dashboard-regression-'));
let passed=0,failures=[];
try {
  for(const script of scripts){
    const result=cp.spawnSync(process.execPath,[script],{cwd:root,encoding:'utf8',timeout:90000,maxBuffer:16*1024*1024,
      env:{...process.env,ALLIN_TEST_TMP:temporary}});
    if(!result.error && result.status===0){passed++;console.log('PASS '+script);}
    else {failures.push(script);console.error('FAIL '+script,String(result.error||result.stderr||result.stdout).slice(-4000));}
  }
} finally {fs.rmSync(temporary,{recursive:true,force:true,maxRetries:20,retryDelay:100});}
console.log(JSON.stringify({total:scripts.length,passed,failed:failures.length,failures}));
process.exitCode=failures.length?1:0;
