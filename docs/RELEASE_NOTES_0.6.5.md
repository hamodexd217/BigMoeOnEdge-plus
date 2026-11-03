# BigMoeOnEdge+ 0.6.5

Everything since 0.6.0 in one release: LFM3 and Flint models, more sampling controls, external tools, editor-style file tools with undo, no tool-round limit, a live status line with timings, an in-app browser, and a fix for NPU memory crashes.

## Highlights

- **LFM3 and LFM3-VL** (text and vision) are supported, together with **Flint** and the LFM2 / LFM2.5 families.
- **More sampling controls:** Min P, repetition penalty, repeat window, frequency penalty, presence penalty.
- **No more "tool call limit reached".** The model can use as many tools as it needs in a turn.
- **External tools:** add your own HTTP tools and MCP servers.
- **Editor-style file tools:** edit by line number, project-wide find & replace, undo.
- **Live status line:** see whether the model is reading the prompt, thinking or using a tool, how long each took, and the prefill / generation speed.
- **NPU memory failures no longer crash the app.**

## New

### Models
- LFM3 (text) and LFM3-VL (vision, needs the matching `mmproj` file), ported from llama.cpp PR #31252.
- **Flint** support: Flint models load and run (MoE with leading dense blocks and a shared expert, with expert streaming). LFM2 / LFM2.5, including the MoE variants, are recognised too.
- Models with an architecture the engine doesn't know now give a plain-language error instead of a raw one.
- Loading a model that needs more NPU memory than the device can map (seen with Qwen3.5-4B Q4_K_M on a Xiaomi / Hexagon device) now shows an error with a hint instead of closing the app.

### Sampling
- Settings → Engine performance, under Top K: **Min P, Repetition penalty, Repeat window, Frequency penalty, Presence penalty**. All start off. They apply to the next message without reloading the model and also work at temperature 0. They are switched off while Guess ahead (MTP / n-gram) is on.

### Tools
- **No tool-round limit** (it used to stop after 6 rounds), up to 16 calls per round (was 4). A turn still ends when you press Stop, when the context is full, or if the model repeats the exact same call nine times in a row.
- **External tools** (Settings → Tools → External tools, plus an **External** switch in the chat's wrench menu):
  - *HTTP tools* — an address with `{argument}` placeholders, a method, headers and arguments.
  - *MCP servers* — Streamable HTTP; fetch the server's tools once and pick which ones the model may use.
  - Every call asks for approval by default; results are marked untrusted for the model.
- **File tools that work like an editor:**
  - `edit_lines` — replace, insert or delete by line number, several edits per call.
  - `replace_in_files` — find & replace across files (plain or regex) with one diff to approve.
  - `undo_edit` — restore a file to how it was before the last AI edit.
  - `rename_file` can now copy (files and folders); `search_files` can show context lines.

### Chat and interface
- **Live status line** under the answer: `Reading prompt 12 s`, `Thinking 8 s`, `Using web_search 2 s`, `Answering`, plus `Thought for 18 s`, `Tools took 3 s`, `Prefill 42 tok/s` and `Generation 6.7 tok/s`. Timings are saved with each message, so the stats line, the Thinking block and tool-result cards show them later too.
- **In-app browser** for links and image previews instead of opening Chrome.
- **Native UI cards** are now chosen by the model (comparisons, pros/cons, timelines, key numbers…) without being asked to "use the tool", and it's more forgiving about badly formed output. There's a **Native UI** switch in the wrench menu.
- "Liquid glass" is now simply **Glass**.

## Fixed
- Opening another chat while a model is answering no longer shows an empty spinner or makes the list jump.
- NPU prefill memory-mapping failures end in an error message, not a crash.
- "This interactive view could not be rendered" for slightly malformed Native UI output.

## Upgrade notes
- **Database:** this release upgrades the chat database to version 5 (three empty columns are added to messages). Existing chats are kept. As with any database upgrade, back up first if your chats matter, because a bad upgrade cannot be undone.
- **Version code:** `versionCode` is 19, so the APK installs over any of the intermediate test builds. If you sign with a different key than the one used before, Android will refuse to update — see `docs/SIGNING.md`.
- Native UI cards add about 450 tokens to the prompt; the setting is still off by default.
- Penalty sliders are off by default, so nothing changes until you move them.

## Known limitations
- **Flint:** runs on the CPU path; the NPU (Hexagon) path has not been verified for Flint.
- **LFM3:** audio, omni, decision models and video are not supported. Images are resized with a plain bilinear/bicubic resize rather than the reference's torchvision-style resize, so image embeddings differ slightly.
- **NPU:** a dense model can need more NPU memory than the device can map. The crash is gone, but you may still need to turn NPU prefill off, lower the context size, or use a smaller quantisation for such models.
- **MCP:** only the Streamable HTTP transport. No legacy HTTP+SSE, no local (stdio) servers, no OAuth sign-in (send a token header instead), no resources or prompts.
- **Prefill speed** appears after each generation round, because the engine reports the prompt size only when a round ends. While the prompt is first being read you see the elapsed time.
- Undo history is kept in memory and is lost when the app closes.

## Source checks
The changes were checked while writing them: edited C++ files pass `g++ -fsyntax-only`, the LFM3 chat template renders correctly with the engine's Jinja interpreter, and `scripts/verify-requested-features.sh` passes 27/27. Everything else comes from building and running the app.

Full details: [`CHANGELOG.md`](../CHANGELOG.md).
