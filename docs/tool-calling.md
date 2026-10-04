# Web search & tool calling

## Web search modes (Settings → Web search; globe button in the chat)
* **Every message (default, `ALWAYS`)**: the app searches the user's message itself, stores the results as a
  `TOOL` message, and sends `message + <tool_response>…` to the model in the same turn. The model never has to
  emit a tool call, so it works with every model. The system prompt tells it the results are attached.
* **Only when the model asks (`AUTO`)**: the tool protocol below; small models often never call the tool.
* Provider order: Brave (if a key is set) → SearXNG (if an https URL is set) → **DuckDuckGo HTML (no account)**
  → Wikipedia search API. The DuckDuckGo path scrapes `html.duckduckgo.com/html/`; it can be rate-limited or
  change shape, which is why Wikipedia is the fallback and a failure is reported to the model as text.
* The UI shows "Searching the web for “…”", then a collapsible "Tool result" card with the sources.

# Tool calling (Phase 7)

## Protocol (prompt-only; `GenerateRequest` has no grammar field)
System prompt (when "web search" is on) lists the tools and asks for exactly
`<tool_call>{"name": "...", "arguments": {...}}</tool_call>`. The app answers with a user turn
`<tool_response>\n[tool]\nresult\n</tool_response>`.

## Robustness
* `StreamOutputParser` hides tool-call blocks while streaming, including tags split across tokens.
* `ToolProtocol.parseCall` accepts code fences, surrounding prose, trailing commas, smart quotes, single quotes,
  `parameters`/`args` aliases, arguments encoded as a JSON string, and a truncated object.
* Invalid call → visible notice + a corrective `<tool_response>` telling the model to retry (`malformedFeedback`).
* At most 3 tool rounds and 3 calls per round; then a visible "limit reached" message.
* Tool errors, timeouts (20 s) and oversized results (6000 chars) are returned to the model as text; the app never
  throws because of a tool.
* Stop button ends the loop even while a tool is running.

## Grammar patch: evaluated, NOT applied
A grammar string on `GenerateRequest` wired to `llama_sampler_init_grammar` would make invalid JSON impossible.
It was not done because: (1) `Session` builds its sampler chain inside `open()` from `SessionConfig`; a per-request
grammar means rebuilding or extending that chain inside `generate()`, touching the speculative-decoding paths
(MTP/n-gram verify and rollback assume the chain state), (2) it cannot be compiled or tested in this environment
(no NDK, no device, no model), and a sampler bug corrupts every generation, (3) the tolerant parser + retry covers
the failure mode in practice. If wanted later, add it as `patches/0002-…` and test on a device first.
