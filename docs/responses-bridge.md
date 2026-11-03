# Responses protocol

`POST /v1/responses` accepts stateless Responses requests and uses the same
authentication, privacy masking, middleware, context limits, routing and
fallback as Chat Completions. Requests are converted to the gateway's Chat
representation before routing; the selected provider's existing Chat,
Anthropic or Codex adapter sends them upstream. JSON and SSE replies are
converted back to Responses. No loopback HTTP call is involved.

Supported inputs include string and message input, text and image URLs/data
URLs, instructions, function definitions, calls and string results, tool
choice, output limits, sampling, reasoning effort, and text/JSON response
formats. Responses output includes text, reasoning summaries, function
calls, usage, completion/incomplete status, and streaming lifecycle events.
For Codex the Chat-to-Responses builder preserves images, calls and results;
its SSE reader returns deltas and completed usage to the common pipeline.

Stateful Responses features are unavailable: `previous_response_id`, stored
responses (`store: true`), conversations, server-hosted tools, file IDs,
encrypted reasoning and unsupported item/field types return HTTP 400. Plain
reasoning summaries in input are represented as assistant text because the
common Chat format does not carry opaque Responses reasoning items. A
truncated upstream SSE stream produces `response.failed`; it is not reported
as a successful completion. Only one Chat choice is supported. Translated
APIs cannot retain provider-specific opaque reasoning signatures, citations,
or features that have no equivalent in the gateway's Chat representation.
Same-protocol Responses direct relay is not used by this endpoint; applying
context guard and middleware to the normalized request takes precedence.

Design reference: Magpie by yetone and contributors, commit
`b045362a92e7d3b0b48c2b88e254ee91dfed9263`, especially
`internal/gateway/ir.go`, `responses.go`, `chat.go`, and
`docs/subsystems/gateway-routing.md`. Magpie parses requests into common
message parts, builds the selected upstream protocol and renders the client
protocol, with native relay where safe. The requested `request.go` and
`stream.go` do not exist at that revision. Thanks to the Magpie authors;
its MIT license is preserved at `app/src/main/assets/licenses/magpie-MIT.txt`.
