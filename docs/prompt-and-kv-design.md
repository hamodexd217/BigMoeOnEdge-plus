# Prompt / KV design (Phase 5)

## Facts (from `core/src/engine/session.cpp`, verified by reading)
* `SessionConfig::chatml = true` makes `Session` own the conversation: it keeps `chat_history`, renders the
  model's own chat template over the WHOLE history every turn (`common_chat_templates_apply`) and reuses the
  common KV prefix. `GenerateRequest::prompt` is therefore **only the newest user message**.
* Upstream `Session` has no system prompt and no way to load earlier turns. `clear_kv = true` empties the
  history; `clear_kv = false` continues the engine's own history.
* A cancelled `generate()` rolls the whole turn back (KV and the pushed user message).

Because of that the old `AgentController` (raw `"system\nUser: x\nAssistant:"` string) was wrong twice:
the template was applied on top of a hand-made template, and the history was never restored.

## Patch `patches/0001-session-chat-context.patch`
`GenerateRequest` gains `system_prompt` and `history` (`ChatTurn{role,content}`), honoured only in chat mode on a
`clear_kv = true` request: the fresh conversation is seeded with `system` + `history`, then `prompt` is appended
as the newest user turn, and the normal prefill covers all of it. Also: `n_predict <= 0` = fill the remaining
context ("unlimited"), a failed n_ctx check no longer leaves the pushed user message in `chat_history`, and a
failed `seq_rm` rollback no longer leaves `kv_tokens` claiming a valid prefix.
Callers that set none of the new fields get upstream behaviour.

## App side
`EngineController` remembers a **state key** for what the engine currently holds:
`stateKey(sessionId, id of last message the engine has seen, hash(system prompt))`.

| situation | key matches? | call |
|---|---|---|
| next message in the open chat | yes | `clear_kv=false`, prompt only (KV reused) |
| tool result follow-up | yes (key of the assistant message) | `clear_kv=false`, prompt = `<tool_response>…` |
| open another chat / History | no | `clear_kv=true` + system + saved history replay |
| app restart, model reload | no (key reset) | replay |
| turn was stopped / failed | no | replay (partial assistant text is saved, engine rolled back) |
| system prompt or tool set changed | no (hash differs) | replay |
| deleted/edited message | no | replay |

`ContextPlanner.prepare` normalises the replay (drops empty turns, merges same-role neighbours, folds a trailing
unanswered user turn into the prompt, drops the oldest whole pairs to fit `historyBudget`). If the engine still
answers "exceeds n_ctx" the controller retries up to twice with half the history. "Unlimited" answers reserve a
quarter of the context.

Only the engine's `Finished` event commits a key, and only after the assistant message is saved
(`AgentController` calls `commitState`). A crash between the two just causes one extra replay.

## Regeneration
There is no regenerate button (not requested). Nothing prevents adding one later: delete the last assistant
message → the key no longer matches → replay.

## ASSUMPTIONS (need a real model to confirm)
* The template renders the replayed history the same way it rendered the live turns (true for standard Jinja
  templates; tool-call text is stored as plain assistant text, which is what the model produced).
* Reasoning text is not replayed (the engine keeps it out of `chat_history`).
