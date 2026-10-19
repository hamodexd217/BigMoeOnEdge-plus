# Android Kotlin Build Repair — 2026-10-16

## Root cause from the supplied GitHub Actions log

The workflow reached `:app:compileDebugKotlin` and failed with unresolved references because application source files called APIs/classes that were absent from `app/src/main/java`:

- `tools/ImageSearchTool`, referenced by `di/AppContainer.kt`.
- `ui/chat/ImageSearchResultParser` and `ImageSearchResultsCard`, referenced by `ui/chat/ChatScreen.kt`.
- `ui/chat/MarkdownTable`, `MdLine`, `MathText` and `InlineMarkdown`, referenced by `ui/chat/MessageBody.kt`.

Errors for `first`, `second`, `level`, `marker`, `text`, `depth`, and several type/boolean conditions in `MessageBody.kt` were downstream type-resolution errors caused by the missing Markdown helper declarations. The log does not show a Gradle configuration failure: the Kotlin compilation task ran and reported source errors.

## Changes made

- Added `tools/ImageSearchTool.kt`, implementing a registered `image_search` tool. It can use the existing configured Brave API key or SearXNG instance and has a keyless DuckDuckGo Images fallback. Network requests use timeouts and bounded response reads; only HTTPS image/source URLs are surfaced.
- Connected image search to the existing Brave/SearXNG settings in `core/AgentController.kt`.
- Added `ui/chat/ImageSearchResultParser.kt` and `ImageSearchResultsCard.kt`. Tool result JSON is marked explicitly so `ChatScreen` can recognize it; the UI shows up to three scrollable previews, source labels, and opens source pages on tap. Preview downloads are HTTPS-only, byte-limited, dimension-checked and downsampled.
- Added `ui/chat/MarkdownSupport.kt`, providing the exact APIs expected by `MessageBody.kt`: inline Markdown spans, heading/list/quote/rule classification, pipe-table extraction, and dependency-free best-effort LaTeX-to-Unicode display.
- Added unit tests for Markdown helpers and image-result formatting/parsing.
- Preserved the existing app code and prior targeted native metrics/performance edits from this workspace; no engine math, quantization, model weights or thread/cache defaults were changed by this build repair.

## Checks performed

- Compiled `MarkdownSupport.kt`, `MarkdownLite.kt`, `CodeFences.kt`, and `FileKinds.kt` with the installed Kotlin compiler.
- Ran a standalone assertion harness covering heading/list/quote parsing, table extraction, inline styles, superscript handling, and fraction formatting; it passed.
- Compiled `ImageSearchTool.kt` and `ImageSearchResultParser.kt` with Kotlin against small local API stubs for `org.json` and coroutines. This checks Kotlin syntax and type flow for those files only; it is not a substitute for compiling against the real Android dependencies.
- `git diff --check` and patch-application checks are run during packaging.
- Attempted `./gradlew :app:compileDebugKotlin --no-daemon`. It stopped before Gradle could configure the project because this environment could not resolve `services.gradle.org` while downloading Gradle 8.9 (`UnknownHostException`). No APK was produced here, so full Android/Kotlin compilation and device runtime behavior remain unverified in this environment.

## Follow-up in Codespaces

After applying the patch, run:

```bash
./gradlew :app:testDebugUnitTest --no-daemon
./gradlew :app:assembleDebug --no-daemon
```

If Kotlin reports a new issue, attach the complete `:app:compileDebugKotlin` section; the previously missing source declarations are now present in the patch.
