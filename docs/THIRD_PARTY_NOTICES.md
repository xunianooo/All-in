# 第三方来源与许可

All-in 1.2.24-test，2026-11-12。

本应用的供应商适配参考/移植固定的 magpie-community/plugins 源码，保留原作者版权与许可。
参考源码提交：`573f26760d608616b2db9da6c1eb5b743c3f57a4`。
仓库根 MIT 原文来源：https://raw.githubusercontent.com/magpie-community/plugins/573f26760d608616b2db9da6c1eb5b743c3f57a4/LICENSE 。

| 来源包 | 对照版本 | 包声明 |
|---|---|---|
| @magpie-community/opencode-catpaw-auth | 0.2.1 | MIT |
| @magpie-community/opencode-cline-auth | 0.1.14 | MIT |
| @magpie-community/opencode-copilot-auth | 0.1.2 | MIT |
| @magpie-community/opencode-loomy-auth | 0.1.4 | MIT |
| @magpie-community/opencode-minimax-auth | 0.2.3 | MIT |
| @magpie-community/opencode-qoder-auth | 0.2.64 | MIT |
| @magpie-community/opencode-raccoon-auth | 0.1.8 | MIT |
| @magpie-community/opencode-trae-auth | 0.2.33 | MIT |
| @magpie-community/opencode-workbuddy-auth | 0.2.26 | MIT |
| @magpie-community/opencode-zcode-auth | 0.1.57 | MIT |

根 MIT 许可涵盖上表社区源码；Copilot 的 SST 许可、Qoder 的 CLIProxyAPI 许可另附原文，未修改版权年份（包括原文年份写法）。

原 magpie、deepseek-web-auth、Primer Octicons 许可保留；nanohttpd 2.3.1 的 BSD 许可从实际依赖 JAR 的 LICENSE.txt 原样提取。私有包中的 JSON-java 源码声明 Public Domain，源文件原声明保留；包名迁移不改变其许可。

Windows 测试使用 JNA 5.17.0 调用系统耐久重命名 API，仅属于 offlineTest 依赖，**不打包进 APK**。

许可证全文位于 `app/src/main/assets/licenses/`；应用首页“第三方开源许可”可离线查看合并页面 `licenses.html`，此页面没有管理桥权限。

## 随包许可文件 SHA-256

- `copilot-SST-MIT.txt`: `d498457387e941105622efe452e3884615c160d69867b7aa08ab24e72e52f5af`
- `magpie-community-plugins-MIT.txt`: `5e8730b694ba31696dd68d693f8bf5e2c75f7e40fd5f6fbc3f067cf9490b24fb`
- `magpie-deepseek-web-auth-MIT.txt`: `c2b15bed0a5b13cee3c55e8c29906b9b50310a024f900b765e43067deca1fc35`
- `magpie-MIT.txt`: `79d2c8444715d4bc453ec4f8a0aaf2051a4c1ee5ac08f5bd2e5848aef87c7572`
- `nanohttpd-2.3.1-BSD.txt`: `3825e0f8f684b3b89ecd56ea6b2faa2dda2b7a9c4127b2baf302d6d2b0cea765`
- `primer-octicons-MIT.txt`: `da259c8bd0de62713ccdcf88910aebca810644f98c2c912bad814fc79ea778df`
- `qoder-CLIProxyAPI-MIT.txt`: `879792e89cf1bdd6a8d446033ec87e30496f97dcafc4656dc53f641509b346a6`
