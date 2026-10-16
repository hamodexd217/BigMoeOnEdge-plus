You are continuing an Android project (BigMoeOnEdge+, repo name BigMoeOnEdgeExtended) from a ZIP I attach. Work like an
engineer with file/terminal access, not a chat assistant. Report to me in Arabic (short, plain); keep code, comments and
commit messages in English. Do not stop between phases to ask me to test: finish everything, audit it, then give me ONE
consolidated test checklist.

FIRST: unzip the project, `git log --oneline`, read docs/HANDOFF.md completely (it lists what is done, what is missing and
the exact order), then docs/STATUS.md, docs/settings-parity.md, docs/prompt-and-kv-design.md, docs/tool-calling.md.
Verify the claims in HANDOFF yourself (read the files; never recreate a file from memory; make minimal edits; commit after
every step; never claim something compiled or ran unless you ran it and saw the output).

ENVIRONMENT TRUTH: the sandbox has no network, no Android SDK/NDK, no Gradle, no Kotlin compiler, no device, so no APK can
be built there. Use `python3 scripts/kt_lint.py app/src` (not a compiler), `g++ -fsyntax-only` for C++ (recreate a stub
jni.h for native-bridge.cpp), and Python ports to sanity-check tricky algorithms. Say plainly what is unverified.

THE PRODUCT: Android chat app (Kotlin, Compose, Room, DataStore) running big MoE LLMs on-device through BigMoeOnEdge
(vendored in app/src/main/cpp/core + third_party/llama.cpp with my patches in patches/). The app already works on my
Xiaomi tablet for text chat (Phase 1). Keep that working: do not rewrite stable parts, do not remove functionality.

REMAINING REQUIREMENTS (my original spec, summarised; implement all, wired to the real backend, no fake buttons):
1. Web search + Thinking as two clear icon toggles in the input bar (done; keep).
2. Attachments: real attach button (menu: File / Image / Video), chips for pending items, files saved to the workspace.
3. File reading: many text/code types (.txt .md .json .csv .xml .yaml .log .java .kt .cpp .h .hpp .c .py .js .ts .html
   .css and any text file); big files are chunked/ranked (already implemented in workspace/FileContext), the model must
   know the file name and the relevant parts.
4. Tools (extensible architecture, done in core): file tools (read, search, create, edit with diff and accept/reject,
   rename, delete with confirmation, create folder, list, create_project, export_zip), code tools (run_javascript sandbox,
   analyze, search, apply edits + diff preview), web tools (search, read page, extract relevant info). Every tool: schema,
   output, error handling, UI indication when used.
5. Artifacts: AI-made files shown apart from the chat; view, copy, edit, save, download/share, reopen later; persisted per chat.
6. Editing files: read → modify → show diff → user accepts/rejects → applied. Never silent for destructive/large changes.
7. Code blocks: Copy button (copies only the code), language detection, syntax highlighting, "Open as artifact" for big code.
8. Chat persistence (history, rename, delete, new chat, attachments/artifacts linked) — DB v2 exists.
9. Images and videos: attachment pipeline + engine support (mtmd patch is in the vendored core; needs a vision model + its
   mmproj file; UI must enable image/video only when the loaded model has a projector).
10. UX: modern, clean, few buttons; secondary things in attachment/tools/overflow menus; Thinking, Web, Send, Attach easy to reach.
11. Final polish: error handling, performance, persistence, edge cases, full audit.

DO THIS: follow HANDOFF "What is NOT done" items 1-9 in order. Then run every check you can, fix what you find, generate
patches/0003-multimodal.patch, update docs, and build the final ZIP in /mnt/user-data/outputs and present it.

FINAL ANSWER FORMAT (Arabic): what was implemented; what was actually tested (and how); what could not be tested; known
issues; one consolidated device test checklist (load model + speed vs original app, overlap "active", streaming, stop,
multi-turn, chat history rename/delete, web search both modes, thinking toggle, tools: create/edit with diff approval/
delete/rename/zip, attachments of text files small+large, code blocks copy/highlight/open as artifact, artifact viewer
edit/save/share/download, palettes + custom palette, images/video with a vision model + mmproj, app icon/name,
Room upgrade wipe notice). If I need to send you a file or compile-error text, tell me exactly which.
