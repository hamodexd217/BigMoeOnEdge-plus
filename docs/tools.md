# Tool extension guide

The tool system is intentionally registry-driven. A tool implements `com.bigmoe.onedge.tools.Tool` with:

* `name`: stable machine-readable tool name.
* `description`: concise model-facing capability text.
* `parametersSchema`: JSON Schema-style object describing arguments and required fields.
* `category`: `FILES`, `CODE`, `WEB` or `UTILITIES` for UI/settings grouping.
* `describe(arguments)`: short live UI label while the tool runs.
* `confirmation(arguments)`: optional approval request. Return a `ConfirmationRequest` for edits, replacements,
  deletes, or other destructive actions; return `null` for safe operations.
* `execute(arguments, ctx)`: the real operation. `ToolContext` identifies the chat/session so created artifacts can be linked.

Register the tool in `AppContainer.toolManager`. `ToolManager` then supplies it to the prompt, parses its calls, runs it
off the main thread with a timeout/result-size cap, emits a tool-running event, and routes confirmation through the chat UI.

For a switchable tool, add its name to the appropriate `*_TOOL_NAMES` set in `AgentController` and expose the setting in
`EngineSettings`/Settings UI. For file-producing tools, update `ArtifactRegistry` so the chat's artifact list stays in sync.

Current groups:

* Files: list/read/search/create/edit/rename/delete/create_folder/create_project/export_zip. `create_folder` also lists the folder in the chat's files.
* Code: search_code/analyze_code/run_javascript.
* Utilities: get_datetime/calculate/convert_units/random_number/device_info (offline, read-only; `UTILITY_TOOL_NAMES`).
* Web: web_search/read_webpage/extract_webpage.

Security rules are deliberate: every file path is resolved inside the private workspace, destructive changes require user
approval, JavaScript runs in an offline WebView sandbox with no JS bridge, and artifact HTML uses a restrictive CSP.

Tool access: Settings -> Tool access and the chat's wrench menu hold the same three switches (Files, Code, Utilities); the
web tools follow the globe button. The model uses an enabled tool on its own when it decides it needs one.
