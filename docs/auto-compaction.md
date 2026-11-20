# Automatic conversation compaction (0.7.0)

## What it does
When the next engine round would pass a set share of the context (default 60%, choose 40–70% in Settings → Context),
the loaded model summarizes the oldest messages and the chat goes on from `summary + newest messages`. Without it the
engine's replay trimming (`ContextPlanner.prepare`) drops the oldest turns without a trace.

## Flow (`AgentController.runAgentLoop`)
1. `Compaction.resolve(request.history)`: the newest usable SUMMARY message decides what is sent. Messages up to the
   one it names are not sent; the summary text goes at the end of the system prompt.
2. Before every round (and once before the web-search query step) `compactIfNeeded()`:
   - size = chat's last measured context + estimate of the new prompt when known (`AgentRequest.contextUsedHint`, then
     the engine's `nPast` after each round), else a text-length estimate (`ContextPlanner.estimateTokens`, ~3 bytes/token,
     about a third high for English);
   - over the threshold, context >= 1,536 tokens, `Compaction.splitIndex` finds the cut: the newest turns that fit 25% of
     the context stay; the tail starts at a USER message so a message is never parted from its attached file text or tool
     result; only stored history is folded, never turns of the round that is running;
   - at least ~300 tokens must be folded, otherwise nothing is done.
3. `summarize()`: hidden generation (`think=false`, `max tokens = ctx/8` clamped 192..1024, each folded message clipped to
   4,000 characters). The previous summary is passed in so one summary always covers everything.
4. The result is saved as `MessageRole.SUMMARY` with content `bmoe-summary upto=<last covered message id>\n<text>`,
   `turns` and the system prompt are rebuilt, the engine replays once (its state key changed).

## Why it is stored this way
- No schema change: the role column is text; the covered message id is in the content. `Converters` already maps unknown
  roles to USER, so an older build would show the card text as a user bubble, nothing worse.
- The summary sits where it happened. Editing a user message removes everything after it (`ConversationEdit`), including
  summaries made later; if a summary names a message that no longer exists, `resolve` falls back to the older one, or to
  the whole chat.
- Exports show the summary text without the header (`ConversationExportFormatter`).

## Limits
- One summary pass per check; if the folded part alone does not fit the context, the engine's replay budget trims the
  oldest of it inside the summarizer call.
- Pictures from folded messages are not kept (a replay is text anyway).
- A model that spends all `max tokens` thinking gives no usable answer; compaction is then skipped for that turn.
- The HTTP API (`server/`) is stateless and untouched. "Choose" mode (scored options) still sends the full stored chat.
- Not compiled/run on a device yet; see CHANGELOG "How this was checked".
