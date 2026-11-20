#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PASS=0
FAIL=0

need() {
    local file="$1" pattern="$2" description="$3"
    if grep -Fq -- "$pattern" "$ROOT/$file"; then
        printf 'PASS  %s\n' "$description"
        PASS=$((PASS + 1))
    else
        printf 'FAIL  %s  [%s: %s]\n' "$description" "$file" "$pattern" >&2
        FAIL=$((FAIL + 1))
    fi
}

reject() {
    local file="$1" pattern="$2" description="$3"
    if grep -Fq -- "$pattern" "$ROOT/$file"; then
        printf 'FAIL  %s  [unexpected: %s]\n' "$description" "$pattern" >&2
        FAIL=$((FAIL + 1))
    else
        printf 'PASS  %s\n' "$description"
        PASS=$((PASS + 1))
    fi
}

need app/src/main/java/com/bigmoe/onedge/ui/settings/AutoSettingsScreen.kt 'Text("Custom settings")' 'Auto Settings exposes Custom Settings'
need app/src/main/java/com/bigmoe/onedge/ui/settings/AutoSettingsScreen.kt 'viewModel::updateCustomTemperature' 'Custom Settings edits per-request sampling'
need app/src/main/java/com/bigmoe/onedge/ui/settings/AutoSettingsScreen.kt 'viewModel::updateCustomContext' 'Custom Settings edits context/session settings'
need app/src/main/java/com/bigmoe/onedge/ui/settings/SettingsScreen.kt 'AppVisualStyle.LIQUID_GLASS to "Glass"' 'Appearance exposes the Glass style'
need app/src/main/java/com/bigmoe/onedge/ui/theme/Theme.kt 'AppVisualStyle.GRADIENT -> Modifier.background' 'Gradient surfaces are implemented'
need app/src/main/java/com/bigmoe/onedge/ui/theme/Theme.kt 'AppVisualStyle.LIQUID_GLASS -> {' 'Glass surfaces are implemented'
need app/src/main/java/com/bigmoe/onedge/ui/settings/SettingsScreen.kt 'LaunchedEffect(reloadNeeded) { if (reloadNeeded) modelExpanded = true }' 'Model & loading opens when a reload is required'
need app/src/main/java/com/bigmoe/onedge/ui/chat/ChatScreen.kt 'onToggleHistoryPane' 'Wide-screen chat can hide the history pane'
need app/src/main/java/com/bigmoe/onedge/ui/chat/ChatScreen.kt 'Hide chat history pane' 'The history-pane button is labelled for accessibility'
need app/src/main/java/com/bigmoe/onedge/ui/chat/ChatScreen.kt 'Modifier.widthIn(max = 720.dp).fillMaxWidth()' 'Composer has a maximum width'
need app/src/main/java/com/bigmoe/onedge/ui/chat/ChatScreen.kt 'contentAlignment = Alignment.Center' 'Composer is centered in the chat pane'
reject app/src/main/java/com/bigmoe/onedge/ui/chat/ChatScreen.kt 'Text("Live stats")' 'Old live-statistics strip is absent from chat UI'
need app/src/main/java/com/bigmoe/onedge/ui/chat/ChatViewModel.kt 'is EngineState.Loading' 'Messages can be queued while a model loads'
need app/src/main/java/com/bigmoe/onedge/ui/chat/ChatViewModel.kt 'queuedInputs.removeFirst()' 'Queued messages are consumed FIFO'
need app/src/main/java/com/bigmoe/onedge/ui/chat/ChatViewModel.kt 'addLast(item)' 'User-submitted queued messages preserve FIFO order'
need app/src/main/java/com/bigmoe/onedge/core/AgentController.kt 'sampling = SamplingOverride(' 'Chat generation passes sampling per request'
need app/src/main/java/com/bigmoe/onedge/core/EngineBackend.kt 'overrideSampling = s != null' 'Native backend applies sampling without reloading the model'
need app/src/main/java/com/bigmoe/onedge/ui/chat/MessageBody.kt 'NativeUiParser.parse(block.code)' 'Native UI blocks are parsed by the chat renderer'
need app/src/main/java/com/bigmoe/onedge/ui/chat/NativeUiComponents.kt 'object NativeUiParser' 'Native UI has a bounded JSON parser'
need app/src/main/java/com/bigmoe/onedge/ui/settings/SettingsScreen.kt '"Native UI components"' 'Native UI has a user-facing setting'
need app/src/main/java/com/bigmoe/onedge/data/local/datastore/EngineSettingsDataStore.kt 'val interactiveUiEnabled: Boolean = false' 'Native UI is off by default'
need app/src/main/java/com/bigmoe/onedge/ui/settings/SettingsScreen.kt '"Allow image search"' 'Image search has an independent enable switch'
need app/src/main/java/com/bigmoe/onedge/ui/settings/SettingsScreen.kt '"Search images after web search"' 'Automatic image follow-up has its own switch'
need app/src/main/java/com/bigmoe/onedge/data/local/datastore/EngineSettingsDataStore.kt 'val autoImageSearchAfterWebSearch: Boolean = false' 'Automatic image follow-up is off by default'
need app/build.gradle.kts 'versionCode = 20' 'Rebuilt APK is distinguishable by version code 20'
need app/build.gradle.kts 'versionName = "0.7.0"' 'Rebuilt APK reports version 0.7.0'
reject README.md '0.5.9' 'README does not carry old 0.5.9 release notes'

printf '\nFeature source checks: %d passed, %d failed.\n' "$PASS" "$FAIL"
if (( FAIL > 0 )); then exit 1; fi
printf 'These are static source-integration checks; they do not replace Gradle compilation or device testing.\n'
