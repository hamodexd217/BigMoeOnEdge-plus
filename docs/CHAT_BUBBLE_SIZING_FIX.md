# Chat bubble sizing and streaming growth fix

## Project and files reviewed

The UI is Jetpack Compose. The assistant response path is `ChatScreen.kt` -> `ChatMessageBubble.kt` -> `MessageBody.kt`, displayed as the final item in a `LazyColumn` while generation is in progress.

Files changed:

- `app/src/main/java/com/bigmoe/onedge/ui/chat/ChatMessageBubble.kt`
- `app/src/main/java/com/bigmoe/onedge/ui/chat/MessageBody.kt`

## Findings

- The bubble did not contain a fixed `height()`, `fillMaxHeight()`, or `weight()` modifier. The message list also does not use `fillParentMaxHeight()` for message items, and items are measured by content. So source inspection did not support claiming that one fixed-height modifier was the root cause.
- The AI bubble had no content-size transition, so its shape/space could jump when text crossed a line-wrap boundary during streaming.
- The entire `ThinkingBlock` column used `animateContentSize()` while its reasoning text changed continuously. That animates the panel's full size on repeated text updates, rather than reserving animation for the user's explicit expand/collapse action.
- The scroll-follow logic in `ChatScreen.kt` already stops when the user scrolls away from the bottom and resumes when they return or select the jump-to-latest control. It was intentionally left unchanged.

## Changes

1. Added `wrapContentHeight(Alignment.Top)` to the message-row container, response surface, surface content column, and `MessageBody` root. This keeps the intended top-aligned, content-height contract explicit without changing widths, theme, horizontal margins, or the existing 12 dp vertical / 16 dp horizontal bubble padding.
2. Added a short 70 ms `animateContentSize(tween(...))` to assistant bubbles only. It is placed before the width/height sizing modifiers so the size modifier can report the animated content height to the list. User bubbles keep their existing sizing behavior.
3. Reworked the thinking text's visibility: expanding/collapsing is animated with `AnimatedVisibility`, but new reasoning text is measured naturally while visible. No `height`, `heightIn`, or max-lines cap was introduced.
4. Left Markdown/code rendering, horizontal scrolling for code and tables, copy/artifact actions, stats, cursor animation, message alignment, and list auto-scroll policy intact.

## Verification performed

- Source review confirmed no fixed-height, `fillMaxHeight`, or weighted-height modifier on the assistant bubble itself.
- Patch generated from the original 0.4.0 source and checked with `patch --dry-run`.
- Whitespace/diff checks performed on both changed Kotlin files.
- `./gradlew :app:compileDebugKotlin --no-daemon` was attempted. It failed before project configuration or Kotlin compilation because the wrapper could not resolve `services.gradle.org` (`UnknownHostException`) while downloading Gradle 8.9.

## Not verified in this environment

There is no Android SDK/device available here and Gradle 8.9 could not be downloaded. Therefore I did not claim that the APK compiled or that visual tests passed. One-line, two-line, long multiline, Markdown/code, thinking-panel, portrait/landscape, and streaming tests still need a buildable Android environment and device/emulator.
