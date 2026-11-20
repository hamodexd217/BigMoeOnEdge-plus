# Interface languages (0.7.0)

Settings → **Language** (top of the screen): *Same as the phone*, or one of 16 languages named in their own script:
English, العربية, Deutsch, Español, فارسی, Français, हिन्दी, Bahasa Indonesia, Italiano, 日本語, 한국어,
Português (Brasil), Русский, Türkçe, 简体中文, 繁體中文. Android 13+ also lists them under system *App languages*.
Arabic and Persian switch the whole layout to right-to-left.

## How it works
- Texts in the code are English and go through `tr("…")` (`i18n/I18n.kt`); the English text is the key.
  `tr("{0} tokens", n)` fills `{0}`, `{1}`… by number. A text with no translation is shown in English.
- Each language is one flat JSON file, `app/src/main/assets/i18n/<tag>.json`: `{ "English text": "translation" }`.
- The language is the one picked in Settings (kept in SharedPreferences, read before the first screen), else the first
  language on the phone's list that has a file (es-MX → es, any Portuguese → pt-BR, zh Hant/TW/HK/MO → zh-TW, other
  Chinese → zh-CN). `MainActivity.attachBaseContext` gives the activity that locale, so the layout direction follows.
- Changing the language recreates the activity. Texts that a ViewModel already put into its state keep the old language
  until that state is replaced.

## Right-to-left
Directional icons use `Icons.AutoMirrored` (back arrow, open-in-new); code blocks are forced left-to-right so they scroll
from the start of each line. Every text style's paragraph direction follows the text's first strong character
(`Typography.forDirection`: ContentOrRtl / ContentOrLtr), so an English explanation in an Arabic screen reads left to
right with its full stop at the end, and Arabic text in an English UI reads right to left. Values such as `64 tokens` or
`2000 MiB` that are filled into a right-to-left translation are wrapped in a left-to-right isolate (`I18n.isolateLtr`) so
number and unit keep their order. The "Next ›" / "‹ Previous" arrows are written
for the reading direction in each translation.

## What is translated, and how far
744 texts can be translated (those that are not code, prompts or bare numbers-with-units). Arabic has **738 (99%)**:
every text, including all the long explanations. Spanish and French have **579**, the other 12 languages **505**: every
label, button, title, notice and short explanation, in the chat, model, file, browser and settings screens. What those 14
languages still show in English is the 110 long engine-tuning and API explanations in Settings (expert streaming, I/O
lanes, sampling, the local API server…) and a few engine-option names (MTP, n-gram, O_DIRECT…); the app falls back per text.
Technical names stay as written: GGUF, mmproj, MoE, MTP, n-gram, NPU, KV, API, MCP, HTTP, and "token" where usual.

Not translated on purpose: prompts sent to the model ("Conversation so far:", the scored-choice instruction), the
User-Agent and CSP strings, code samples, HTML/JavaScript, number-and-unit labels (`512 MB`, `{0} MiB`), the date pattern.
Not reached yet: texts outside `ui/` (engine and agent notices stored with a chat, notification text, errors raised below
the UI), palette preset names (Paper/Pearl/Mint…), syntax-highlighter keywords and the model's own replies.

## Accuracy
The translations were written by Claude, not by translators: **no native speaker has reviewed them**. Terms were chosen to
be conventional for each language and checked by script (below), but wording, register and the choice of loanwords deserve
a native read, Arabic first. zh-TW is zh-CN converted with OpenCC (`s2twp`, Taiwan phrases) plus a manual pass on common
UI vocabulary and 「」 quotes. Corrections are one-line edits in the JSON file.

Checked by script for every file (`LocalizationTablesTest`, and the same rules were applied before the files were written):
- every language in `I18n.languages` has a file and the other way round, and `res/xml/locales_config.xml` lists the same tags;
- each translation has exactly the `{n}` placeholders and `%s/%d/%.1f` specifiers of its English text, the same leading and
  trailing spaces, and is not empty;
- each translated English text still appears verbatim in the source (so editing an English text without updating the
  files fails the test instead of silently dropping the translation);
- the script of each translation matches its language (Arabic letters in `ar`, Hangul in `ko`…).

## Adding or fixing a language
1. Add `Language("xx", "Own name")` to `I18n.languages` (`rtl = true` for right-to-left) and `<locale android:name="xx"/>`
   to `res/xml/locales_config.xml`.
2. Create `assets/i18n/xx.json` with any subset of the English texts; copy keys exactly (including spaces and `…`).
3. Run `LocalizationTablesTest`.
When you change an English text in the code, change the same key in every JSON file.
