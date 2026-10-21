# v0.5.5 UI and model-management follow-up

This patch keeps the Android app's version at `0.5.5` (`versionCode` 6) and builds on the existing model manager, chat renderer, artifact preview, and navigation layout.

## Separate MTP sidecar import

- The per-model **MTP draft → Attach → Choose separate MTP GGUF…** action opens Android's document picker.
- The selected file is copied as a stream into app-private model storage (no full-file RAM buffering); the original SAF document is not deleted or modified.
- The copy must have a GGUF header, must not be an `mmproj`, must not be a later shard or numbered split set, and must fit in app storage with the existing working-space margin.
- The imported file receives an `mtp-` prefix if needed so normal model listing treats it as a sidecar, then attaches to the exact selected target. Copy/validation failures clean up the partial file.
- Split MTP sidecar GGUFs are explicitly unsupported in this path rather than misclassified as target models.

## Links and source chips

- Markdown `[label](https://...)` links retain their destination and have click annotations; bare HTTP(S) URLs are annotated as well.
- Clicks use Compose's URI handler to open the device's default browser. Only HTTP(S) is permitted; unsupported schemes do not launch.
- The assistant message source chip is shown below the message action row when links are present either in the answer or in tool-result messages following the current user's turn. It deduplicates URLs, displays the host/label, and caps the list at twelve.
- Tool-result cards expand via the header, not the whole card body, so tapping a URL inside expanded results is no longer swallowed by the card's expand click.

## Artifacts

- HTML/SVG/Mermaid maintain their existing preview paths. Explicit HTTP(S) navigation from a sandboxed preview is handed to the system browser while the WebView itself remains on the local artifact document.
- HTML/SVG/Mermaid remain previewable. General supported code artifacts remain highlighted and copyable. Python is deliberately removed from artifact-chip creation and its code-fence preview action because the app has no Python runtime; it remains a highlighted, copyable code block in chat with an explanation instead of a misleading Artifact button. Existing/saved Python source files can still be opened as text source, but are never executed by this app.
- Offline/network restrictions on previews are retained; this patch does not add a JavaScript-to-native bridge.

## Chat pane

On wide screens (the current 840 dp threshold), a menu/close control in the chat app bar toggles the persistent chat-history side pane. The conversation then uses the remaining/full row width. On narrow screens the existing history drawer remains the control.

## Validation status

The source edits and focused unit tests are included. Local Kotlin smoke checks passed for Markdown-link destination preservation, URL-source extraction/deduplication/HTTP(S)-only validation, and artifact detection (HTML/SVG/Mermaid/code remain available; Python fences are no longer emitted as artifact chips). The stream-parser smoke harness stubbed the unrelated tool-protocol parser, so this is not a substitute for the project's Gradle test task. This environment cannot resolve `services.gradle.org`, so `./gradlew testDebugUnitTest` could not get past the Gradle 8.9 wrapper download. The MTP attach picker, browser navigation, artifact previews, full build, and pane toggle must still be tested on a real Android build/device. No successful Android build or device-runtime test is claimed by this note.
