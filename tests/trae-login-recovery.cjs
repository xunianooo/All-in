const fs = require('fs');
const assert = require('assert');
const source = fs.readFileSync('app/src/main/java/com/gaeul/allin/LoginActivity.java', 'utf8');
const block = source.slice(source.indexOf('private boolean traeCurrent'), source.indexOf(' * Antigravity (Google Code Assist)'));
for (const text of ['重新打开浏览器', '重新发起授权', '检查状态', 'if ("trae-cn".equals(currentProvider)) checkTraeStatus()', 'traeActive = false;', 'traeGeneration++;']) assert(source.includes(text), text);
for (const text of ['?fresh=1', '5 * 60 * 1000L', 'result.optLong("deadline", 0)', '"www.trae.cn".equalsIgnoreCase(uri.getHost())', '"/authorization".equals(uri.getEncodedPath())', 'uri.getUserInfo() == null', 'uri.getPort() == -1', '++failures >= 3', '"ready".equals(status)', '!r.optBoolean("ok", false)', 'traeCurrent(generation)', 'fresh || (!restoring && !resumed)']) assert(block.includes(text), text);
assert(!block.includes('15 * 60 * 1000L'));
assert(source.indexOf('initTraeAuth(false);\n            return;') < source.indexOf('// 原生 WebView'));
console.log('Trae login recovery static checks passed');

// Copied from the observed public authorization-page script; this is a fixed offline fixture.
const knownPattern = /^http:\/\/127\.0\.0\.1:(\d+)\/authorize$/;
assert(knownPattern.test('http://127.0.0.1:32123/authorize'));
assert(!knownPattern.test('http://127.0.0.1:32123/authorize?source=fixture'));
assert(source.includes('if (fresh || (!restoring && !resumed)) openTraeBrowser(url);'));
assert(block.includes('"missing".equals(status)'));
assert(source.includes('fresh.setOnClickListener(v -> initTraeAuth(true))'));
assert(source.includes('openTraeBrowser(traeUrl)'));
