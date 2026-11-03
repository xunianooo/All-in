# Responses conversion work in progress

This work refers to Magpie by yetone and contributors, commit
`b045362a92e7d3b0b48c2b88e254ee91dfed9263` (MIT; its license is
included at `app/src/main/assets/licenses/magpie-deepseek-web-auth-MIT.txt`).
Thank you to the Magpie authors. The design references
`internal/gateway/ir.go`, `responses.go`, `chat.go`, and
`docs/subsystems/gateway-routing.md`: parse requests into common message
parts, build the upstream protocol, and render in the client's protocol;
native relay is selected only when safe. The requested `request.go` and
`stream.go` files are not present in that revision.

The new `ResponsesBridge` is a standalone, incomplete request parser and is
not connected to the gateway. It does not implement Responses output, SSE,
or Chat-to-Responses construction. It deliberately rejects unsupported
stateful requests such as `previous_response_id`. This branch is not a
complete implementation of issue #3.
