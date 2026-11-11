# WorkBuddy 流式过滤拒绝文案抑制：类、接线方式与对照证据

## 类

`app/src/main/java/com/gaeul/allin/WorkBuddyFilterRefusalStream.java`（包内可见，独立 `InputStream` 包装）

只做一件事：把 WorkBuddy 安全过滤在同一句话上的「拒绝」识别出来，并把那句话从 SSE 里摘掉，
保留 finish 与 usage，好让宿主认出这是拒绝、去问路由组的下一位成员，而不是把一句道歉当答案。

判定逐条照抄上游插件 `opencode-workbuddy-auth/package/index.mjs` 的 `unsaid`（`:1653–1745`）：

| 情况 | 上游 | 本类 |
|---|---|---|
| 正文到目前为止仍是 `Sorry, I can't respond to this question.` 的前缀（忽略两侧空白、弯引号） | 压住（hold） | 同 |
| 出现工具调用 | 立即放开压住的 + 本帧 | 同 |
| 正文偏离前缀 | 立即放开压住的 + 本帧 | 同 |
| 那句话 + `finish_reason=content_filter` | 摘掉 `delta.content`，保留 finish/usage/其他 delta 字段；摘空的事件丢弃 | 同 |
| 那句话 + 其他 finish | 原样放行（那句话就是它的回答） | 同 |
| 正文为空的事件（role / reasoning / usage / 心跳 / 注释）且还没压住任何正文 | 立即放行 | 同 |
| 流在没有 finish 的情况下结束 | 压住的原样吐出 | 同 |

### 两处有意的偏离

1. **压住的缓冲有上限**（`MAX_HELD_EVENTS=1024` / `MAX_HELD_CHARS=4 MiB`）。上游没有上限；超限本类
   放开并停止抑制。宁可在这种病态情况下漏一句拒绝文案，也不把内存吃光，也不把正常流判死。
2. **前缀比较用 Java 的 `trim()`**（≤U+0020），JS 的 `trim()` 还含 `\u00a0`、`\ufeff`。真实载荷是
   换行包裹的句子，二者一致。

## 接线方式（2026-11-09 已接线）

`GatewayServer` 的共享流式分支（2026-11-09 08:04 快照为 `:9600–9613`；该文件正被并行修改，行号会漂）已经有同类包装器的先例
（`ThinkingTruncationStream`、`RaccoonModelStream`）。在该处、交给 `streamForwardResponse` 之前加一层：

```java
if (clientWantsStream) {
    if (thinkingCompat(target.providerId, target.protocol, target.upstreamModel)) is=new ThinkingTruncationStream(is);
    if("raccoon".equals(target.providerId) && !wantsAnthropicOut()) {
        String streamModel=raccoonVisibleModel(requestedModel);
        if(streamModel!=null && !streamModel.isEmpty()) is=new RaccoonModelStream(is,streamModel);
    }
    // 新增：WorkBuddy 的过滤拒绝文案
    if (("workbuddy".equals(target.providerId) || "workbuddy-ai".equals(target.providerId))
            && !wantsAnthropicOut())
        is = new WorkBuddyFilterRefusalStream(is);
    out.response = wantsAnthropicOut()
            ? streamForwardAnthropicResponse(is, requestedModel, maskSession, guardResult, conn)
            : streamForwardResponse(is, maskSession, guardResult, conn,
                    out.modelRef == null ? target.providerId : out.modelRef);
    handedOff = true;
    return out;
}
```

接线要点：

1. **必须在 `streamForwardResponse` 之前**：它按行解析 `data:` 并且对「没有 finish」的 EOF 报错，
   包装器要站在它前面。
2. **同时覆盖 Anthropic 翻译形态**：包装器放在三元表达式之前，`streamForwardAnthropicResponse`
   读到的是已经抑制过的帧；`content_filter` 经 `AnthropicBridge.stopReasonOf` 变成 `refusal` stop_reason，
   空正文也仍是合法 Messages 流（`message_start` → `message_delta` → `message_stop`）。夹具已补
   （见下）。Responses 客户端同样被覆盖：它读的也是 `streamForwardResponse` 的 Chat 帧，投影在
   更外层（`chatStreamToResponses`）才发生，`content_filter` 投影为 `incomplete` +
   `incomplete_details.reason=content_filter`。
3. **不影响账本**：`streamForwardResponse` 的 usage 记账读的是它自己收到的帧，抑制只摘
   `delta.content`，usage 帧逐字节保留（测试覆盖），所以记账数值不变。
4. **非流式不需要它**：`GatewayServer` 聚合路径已有等价抑制（`WORKBUDDY_FILTER_SAYS` +
   `workbuddyFilterSaid`，`:9845–9852`，聚合后 `:9513`）。
5. **原生 Messages 分支（`forwardWorkBuddyNative`）不受影响**：它在 `forwardAttempt` 里提前
   return，接线处另有 `!WorkBuddyNativePolicy.useNative(...)` 守卫，所以即使将来调整调用顺序也不会
   把包装器套到原生交易上——上游 `unsaid` 也只用于非原生路径（`native ? res : unsaid(...)`）。
   测试用同一句话断言原生流原样保留。

## 与上游逐帧对照（已实跑，非推断）

`audit-provider-parity/workbuddy-unsaid-parity/` 下的 `unsaid-parity.mjs` 直接切片上游
`package/index.mjs` 的 `unsaid`/`filterSaid` 原文运行（不重写），把 10 个夹具分别整块喂入与
每 3 字节喂入，输出「每帧签名」；Java 侧 `WorkBuddyFilterRefusalSig`（同一签名规则）跑同一批
夹具。结果：

- 10/10 夹具、`whole` 与 `drip3` 两种喂法，**逐帧签名完全一致**（0 处差异）；
- `filterSaid` 在 13 个前缀样本上**完全一致**。

签名包含每帧的 `content`、`reasoning_content`、`role`、`tool_calls` 数量、`finish_reason`、
`usage` 键集合，以及 `[DONE]` / 非 data 行；不比较 JSON 键顺序（抑制时上游与 org.json 各自
重排，属序列化差异，不属语义差异）。

## 测试

`app/src/test/java/com/gaeul/allin/WorkBuddyFilterRefusalStreamOfflineTest.java`（已登记进
`app/build.gradle` 的 `offlineTestMains`），16 组断言：

1. 拒绝文案跨帧被抑制，finish/usage/`[DONE]` 保留，finish 帧 delta 清空；
2. 帧在任意字节处切开（1/2/3/5/17 字节喂入，含 3 字节弯引号被切开）仍然抑制；
3. 普通回复的首帧正文**立即**放出（不等 EOF）；
4. 只是"像"那句话的回复在第一个不同字符处整体放出；
5. `content_filter` 下的其他正文保留；
6. 那句话配 `stop` 时保留（那是它真的回答）；
7. 工具调用立即放出压住的内容；
8. 没有 finish 就结束：压住的原样吐出；
9. CRLF 分帧；
10. 弯引号 + 两侧换行；
11. `data:` 后无空格；
12. reasoning / 独立 usage 帧不被拖到结束；
13. 压住缓冲超限时放开而不是无限缓冲；
14. 空事件与注释行；
15. 单字节 `read()` 路径。

## 端到端验证（已实跑）

`app/src/test/java/com/gaeul/allin/WorkBuddyFilterRefusalGatewayHttpTest.java`（已登记进
`app/build.gradle`），双环回 HTTP，上游一律给出厂商真实形态（role 帧 + 拒绝文案帧 + 带 usage 的
`content_filter` 帧 + `[DONE]`）：

| 路径 | 拒绝文案 | 正常文本 |
|---|---|---|
| Chat 流式（国内+国际） | 文案消失、`content_filter` 与真实 usage 保留、`[DONE]` 正常 | 两段文本逐字到达、`stop` 保留 |
| Responses 流式 | `response.incomplete` + `reason=content_filter`，不开文本 item | `response.completed` + 全文 |
| Messages 翻译 | `stop_reason=refusal`，无文案，无 `event: error`，usage 保留 | `text_delta` 全文、`end_turn` |
| 原生 Messages | **文案原样保留**、仍只发 1 次 `/v1/messages` | — |
| 非流式聚合 | 正文为空 + `content_filter`（既有行为不变） | — |

精准性另有两例：同一句话配 `stop` 时整句保留（那是它真的回答）；句子之后还有内容时不被当成拒绝。

反向对照：把 `GatewayServer` 换回接线前的版本，同一用例在第一处断言即失败（文案被客户端读到），
证明断言不是空过。

## 仍缺

- 原生 Messages 分支的等价处理（上游本来就没有，属有意不做）；
- 真实上游验证：这句话与 `content_filter` 的组合来自上游注释记载的实测（deepseek-v4.1-flash @
  copilot.tencent.com），本轮没有复测。
