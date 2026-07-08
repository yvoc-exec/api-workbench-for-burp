# API Workbench for Burp - Operator Guide

This guide is for operators using API Workbench during API testing, debugging, and assessment work. It focuses on what to click, what each option does, how state is saved, how variables and OAuth2 behave, and how to recover from common errors.

---

## Table of Contents

1. [Quick Start](#quick-start)
2. [Mental Model](#mental-model)
3. [Installation and Startup Checks](#installation-and-startup-checks)
4. [Workbench Tab](#workbench-tab)
5. [Environment Tab](#environment-tab)
6. [OAuth2 Tab](#oauth2-tab)
7. [Collection Runner Tab](#collection-runner-tab)
8. [History Tab](#history-tab)
9. [Diagnostics Tab](#diagnostics-tab)
10. [Import Destinations](#import-destinations)
11. [Workspace Persistence](#workspace-persistence)
12. [Collection and Environment Export](#collection-and-environment-export)
13. [Scripts and Assertions](#scripts-and-assertions)
14. [Supported Collection Formats](#supported-collection-formats)
15. [Common Testing Scenarios](#common-testing-scenarios)
16. [Error Handling and Troubleshooting](#error-handling-and-troubleshooting)
17. [Security and Safety Notes](#security-and-safety-notes)
18. [Operator Checklists](#operator-checklists)

---

## Quick Start

1. Build or download the fat JAR:

   ```bash
   mvn clean package
   ```

2. In Burp, load:

   ```text
   Extensions -> Add -> Select target/api-workbench-for-burp-2.0.0-jar-with-dependencies.jar
   ```

3. Open the **API Workbench** tab.

4. Click **+ Add Collection**.

5. Select a supported API file or Bruno folder:

   - Postman `.json`
   - Bruno `.bru` file or folder
   - OpenAPI/Swagger `.json`, `.yaml`, `.yml`
   - Insomnia `.json`
   - HAR `.har`

6. Select requests in the Workbench tree.

7. Choose a workflow:

   - **Edit/send one request**: click a request, edit it, then click **Send**.
   - **Create Repeater tabs**: check **Repeater**, then click **Import Checked**.
   - **Send live baseline traffic**: check **Sitemap (Live)**, then click **Import Checked**.
   - **Run a chained flow**: switch to **Collection Runner**, review the queue, then confirm.

---

## Mental Model

API Workbench has six operator tabs:

| Tab | Primary Job |
| --- | --- |
| **Workbench** | Load collections, check requests, edit one request, send/import checked requests |
| **Environment** | Manage the active environment profile and its variables, runtime values, and OAuth2 configuration |
| **OAuth2** | Configure/acquire OAuth2 tokens and bind them to the active environment |
| **Collection Runner** | Execute checked requests sequentially with preview, retries, stop conditions, and flow control |
| **History** | Review prior Workbench and Runner executions, compare them, replay them, and export evidence |
| **Diagnostics** | Capture passive runtime evidence, inspect sanitized reports, and copy snapshots |

The extension keeps runtime state scoped per collection. If two loaded collections both use `{{base_url}}`, they can have different values without leaking into each other.

### Variable Precedence

When a request is built, values are resolved from lowest to highest in this order:

1. Collection environment
2. Collection definition variables
3. Ancestor-folder variables
4. Collection runtime OAuth2 values
5. Collection runtime variables
6. Active Environment overlay
   - OAuth2 environment config is added first
   - normal environment variables can override same-named config keys
7. Explicit execution/runtime/script overlay
8. Request-level variables
9. Auth/runtime mapping when enabled
10. `{{name|default}}` placeholder fallback, used only when the key remains unresolved

Later layers win. The active environment wins over collection variables and collection runtime layers. Request variables remain the strongest normal authored variable override.

Script-local values and global helper context are separate from the normal request-build precedence above.

---

## Installation and Startup Checks

API Workbench requires Java 17 or later.

After loading the extension, Burp output should show startup lines similar to the following. This is representative startup output, not a literal promise that the startup log enumerates every in-app tab:

```text
API Workbench for Burp v2.0.0
Supports: Postman, Bruno, OpenAPI, Insomnia, HAR
Features: Import + Collection Runner + Workbench
Java: <version> | Script: <mode>
Script runtime: <runtime status>
Script reason: <reason, when present>
Extension core initialized; scheduling API Workbench UI registration...
API Workbench suite tab registered successfully.
```

Check the script mode line:

| Script Mode | Meaning | Operator Impact |
| --- | --- | --- |
| Full | A supported JavaScript runtime is available | Pre-request, post-response, and test scripts can run |
| Limited | Runtime probing failed | Legacy post-response regex extraction remains available; JavaScript scripts are reduced |
| Disabled | Java is below the supported requirement | JavaScript scripting is unavailable |

API Workbench uses a bundled sandboxed JavaScript runtime. Runtime implementation details are intentionally not surfaced in the operator UI.

If the API Workbench tab does not appear:

1. Confirm Burp is using Java 17+.
2. Confirm the fat JAR was loaded, not the plain project JAR.
3. Check Burp extension errors for dependency or class-loading failures.

---

## Workbench Tab

The Workbench tab is the main control surface for loading, editing, and importing requests.

### Collections Panel

Controls:

| Control | Behavior |
| --- | --- |
| **+ Add Collection** | Opens a file chooser and imports a collection or Bruno folder |
| **- Remove Collection** | Opens the collection-removal flow so one or more loaded collections can be selected and removed from the workspace. |
| **Check All** | Checks all visible collection/request nodes |
| **Uncheck All** | Unchecks all visible collection/request nodes |

Notes:

- Duplicate collection names are rejected to avoid ambiguous variable binding.
- Removing a collection does not change the Active Environment.
- Loaded collections can be restored from Burp project data when using a project on disk.

### Request Tree Actions

The request-tree context menu includes **New Collection**, **New Folder**, **New Request**, **Rename**, **Duplicate**, **Delete**, **Export**, and **Auth Settings**.

### Request Tree Auth Inheritance

Auth can be inherited from the request itself, the nearest folder, the collection, or an explicit no-auth setting.

### Request Tree

The tree shows collections, folders, and requests. Checked requests are the ones sent to Runner, Repeater, Sitemap, or Intruder import destinations.

### Request Editor

The request editor lets you edit one request at a time.

| Control | Behavior |
| --- | --- |
| Method | Change request method |
| URL | Edit the destination URL |
| Headers | Add, remove, or edit headers |
| Body | Edit request body content |
| Auth | Override or inherit request auth |
| Send | Send the current request |
| Follow redirects | Follow HTTP redirects for this Workbench send from the Send dropdown; redirects default to 10 hops and allow 1 to 20 |
| Send to Repeater | Open the request in Repeater |
| Raw request debug | Show the raw request sent by the pipeline |

The Send dropdown also contains `Exact transport headers — Advanced`. Normal requests preserve ordinary authored headers, duplicate ordinary headers, and relative order where practical, while safe transport policy regenerates `Host` and `Content-Length`, removes unsafe transport/hop-by-hop headers, and removes `Connection`-nominated headers. Exact transport mode is per request, shows the amber `⚠ Exact transport headers` indicator while active, and warns once per editor session on explicit enable. Use it only for advanced malformed-request, desynchronization, or request-smuggling testing; Burp, proxies, HTTP/2 conversion, and servers can still normalize or reject requests, so it is not guaranteed byte-for-byte wire transport. Exact mode persists through workspace/native export/import, History, replay, Runner, and request duplication. Redirect follow-ups remain governed by redirect safety rules.

### Workbench Detail Tabs

| Tab label | What it shows |
| --- | --- |
| Request | The request being sent or previewed |
| Response | The received response |
| Metadata | Request/response metadata and execution details |
| Variables / Environment | Resolved variables and active-environment context |
| Script Output | Logs, warnings, errors, and script mutations |
| Assertions / Extractions | Assertion results and captured values |

---

## Environment Tab

The Environment tab selects an environment profile, not a collection.

Controls:

| Control | Behavior |
| --- | --- |
| Import | Load an environment profile from file |
| New | Create a new environment profile |
| Duplicate | Copy the selected profile |
| Delete | Remove the selected profile |
| Set Active | Make this profile the active environment |
| Export | Write the selected profile to disk |
| Save | Persist edits to the workspace |

The active environment applies to previews, Workbench sends, Runner, Repeater, Intruder, and Sitemap request construction. An environment profile contains normal variables plus OAuth2 configuration and output bindings.

### Environment Views

| View | Behavior |
| --- | --- |
| Raw | Edit environment variables as key=value lines. |
| Table | Edit environment variable names and values in a tabular view. |
| Save | Persist the edited profile to workspace state. |

OAuth2 configuration and outputs are managed through OAuth2 controls, not the Raw or Table variable views.

### Variable behavior

Use the Environment tab to manage the active environment overlay. Collection variables and collection runtime values still exist, but the environment profile is the operator-facing place to change targets, tenants, roles, and tokens.

### Autosave

Edits are captured into workspace state. Save when you want to make the profile explicit for the current Burp project or export.

### Unresolved Variables

API Workbench can warn on unresolved variables before send or runner execution. Fix the Environment tab or collection inputs rather than relying on defaults unless the placeholder is intentionally optional.

---

## OAuth2 Tab

The OAuth2 tab selects and uses an environment profile.

### Grant types

| Grant type | Required fields |
| --- | --- |
| Client Credentials | Token URL, client ID, client secret, and any required scope |
| Password Grant | Token URL, username, password, client ID, client secret when required, and any required scope |
| Refresh Token | Token URL, refresh token, client ID, client secret when required, and any required scope |
| Authorization Code / PKCE where supported | Authorization URL, token URL, client ID, redirect URI or callback, and PKCE verifier data when enabled |

### OAuth2 controls

| Control | Behavior |
| --- | --- |
| Populate from Request | Pre-fills OAuth2 fields from the selected request |
| Acquire Token | Requests a token and writes outputs to the active environment |
| Bind Token | Binds the current token outputs to the selected environment profile |
| Clear Tokens | Clears configured token outputs from the selected environment profile |
| Auto-bind token to Active Environment | Controls whether a newly acquired token is automatically bound to configured outputs in the active environment |

Successful token acquisition writes the configured token outputs to the selected environment profile. Token refresh can also happen through runtime/pipeline behavior; it is not a visible OAuth2-tab button. `TokenStore` is an in-memory token cache that is cleared when the extension unloads. Configured OAuth2 outputs written into an `EnvironmentProfile` can persist with Burp project workspace state. Collection `runtimeVars` and `runtimeOAuth2` remain compatibility/runtime storage layers, but they are not the primary operator-facing OAuth2 model. Burp project files containing persisted token outputs are sensitive.

---

## Collection Runner Tab

The current presentation is a Runner Queue on the left, one consolidated Runner Execution Table, and a shared detail viewer on the right.

### Runner configuration

| Setting | Behavior |
| --- | --- |
| Delay | Time between requests |
| Retries | Attempts after the initial attempt |
| Stop on error | Stop when a request-level error occurs |
| Stop on assertion failure | Stop when an assertion fails |
| Stop on status >= 400 | Stop when the response status is 400 or higher |
| Stop when variable missing | Stop when required variables are unresolved |
| Stop after failures | Stop after the configured failure threshold |
| Follow redirects | Follow HTTP redirects for runner execution only; redirects default to 10 hops and allow 1 to 20 |
| Debug final raw request | Emit the final built request for debugging |

### Runner controls

- Start
- Pause
- Resume
- Step
- Cancel
- Clear queue

### Queue and execution behavior

- Selecting queue or execution entries updates the detail view.
- The execution table can represent request start, completion, skipped requests, debug/error events, and run completion.
- Flow-control outcomes such as skip and stop are represented explicitly.
- Step advances the next runnable item in the current run state.

---

## History Tab

History records Workbench and Runner execution information, including script details and variable changes.

History replay supports three redirect behaviors: use recorded behavior, always follow, or never follow. Redirect hops are stored as nested evidence rows under the logical execution, not as queue items.
Fragments are stripped before the next request is built; redirect loops, invalid targets, and limit exhaustion are treated as execution errors. Same-origin credentials can be preserved except `Proxy-Authorization`, and cross-origin sensitive headers are stripped by default.
Trusted origins require exact scheme + host + effective-port matches, and dangerous preserve mode is HTTPS-only. `Proxy-Authorization` is never forwarded.
Pre-request scripts run once per logical request, post-response scripts run once against the final successful response, and stop-on-status evaluates that final response only. Percent-encoded path/query octets are preserved exactly. 307/308 preserve method, body, and entity metadata; 301/302 preserve body for non-POST methods; and POST-to-GET or non-HEAD 303 redirects drop body and entity headers.

The detail tabs are **Request**, **Response**, **Metadata**, **Variables / Environment**, **Script Output**, and **Assertions / Extractions**.

Actions:

- replay with current environment
- replay original snapshot
- compare
- send to Repeater
- copy as cURL
- JSON, CSV, and HAR export
- clear

History may contain raw requests, responses, authorization material, tokens, cookies, and sensitive payloads. Review before sharing.

Replay uses the original snapshot when possible and can fall back to a `History Replays` collection when the original request no longer exists. `HistorySanitizer` handles safe text normalization and CSV-cell/formula safety; it is not a secret-redaction engine. The History store retains at most 1,000 entries and persists with the workspace.

---

## Diagnostics Tab

Diagnostics capture is disabled by default and is passive; it does not itself send requests.

Controls:

- capture enablement
- debug inclusion
- Refresh Snapshot
- Clear
- Copy

Diagnostics captures passive runtime events in a bounded in-memory store. Sanitized reports and snapshots are generated from those events. Reports group events by operation and include warning, error, and debug summaries. Sanitization masks common Authorization, Cookie, Set-Cookie, bearer/basic credentials, tokens, secrets, passwords, and API-key patterns, but reports still require operator review before sharing.

Workspace persistence stores the diagnostics-capture-enabled setting.

---

## Import Destinations

- **Repeater**: Open checked requests in Repeater for manual tampering.
- **Sitemap (Live)**: Send checked requests as live traffic into Burp Site map.
- **Intruder**: Open checked requests in Intruder.

---

## Workspace Persistence

Burp project state can persist loaded collections, environment profiles, the active environment ID, request-tree checks, selection, expanded paths, and saved request paths, collection/folder/request auth and script blocks, collection environment/runtime variables/runtime OAuth2 values, History entries, Diagnostics capture-enabled state, Workbench destination/debug/detail selections, and Runner settings, detail selection, and queued request identities. `TokenStore` is separate, in-memory only, and is cleared when the extension unloads. Configured OAuth2 outputs written into an EnvironmentProfile can persist with workspace state. Burp project files containing persisted token outputs are sensitive.

---

## Collection and Environment Export

| Export area | Formats | Notes |
| --- | --- | --- |
| Collections | API Workbench JSON, Postman Collection v2.1 JSON, OpenAPI 3.0 JSON, OpenAPI 3.0 YAML, Insomnia JSON, Bruno ZIP, HAR 1.2 JSON | Native collection export is the most faithful representation of authored structure, auth, variables, folder metadata, requests, and native script blocks. Optional active-environment resolution can materialize values, but `runtimeVars` and `runtimeOAuth2` are not auto-serialized. |
| Environments | API Workbench Environment JSON, Postman Environment JSON, dotenv `.env`, Generic flat JSON, Insomnia Environment JSON, Bruno environment `.bru` | The active environment is the operator context and the export preserves its variables, OAuth2 configuration, and output bindings |
| History | HAR, Native History JSON, CSV summary | Useful for evidence and reporting |

Collection export uses the active environment only when the operator chooses to resolve values; unchecked export preserves placeholders, checked export resolves through the active environment, quick values used only for export do not mutate the active environment, and cancel aborts the export. Environment export preserves the selected profile's variable table, OAuth2 configuration, and output bindings.

External export formats are lossy where their schemas cannot represent all Workbench metadata.

---

## Scripts and Assertions

Supported dialects:

- Postman
- Bruno
- Insomnia
- API Workbench native
- Legacy compatibility

Lifecycle order:

1. collection scripts
2. ancestor-folder scripts from outermost to innermost
3. request scripts

Phases:

- pre-request
- post-response
- test

Shared runtime bindings include `pm`, `bru / req / res`, `insomnia / request / response`, `awb`, and `console`.

Scripts can:

- read and mutate supported variable scopes
- mutate method, URL, headers, body, and auth through the exposed request binding
- read response status, headers, text, parsed JSON, and timing
- produce logs, warnings, errors, assertions, extractions, and variable-mutation records

Runner-only controls such as skip, stop, next-request, and dependent-request flows belong to the Runner. Workbench single Send is not a collection-control engine. Do not assume generic network-helper compatibility that is not actually implemented.

Always run only trusted scripts. Scripts can mutate requests and runtime state, and there is no execution timeout.

---

## Supported Collection Formats

- Postman Collection v2.0 and v2.1 JSON import; Postman Collection v2.1 JSON export
- OpenAPI / Swagger 2.x / 3.x import support
- OpenAPI 3.0 JSON or YAML export
- Insomnia import with supported pre/post script field shapes
- API Workbench native collection format with the most faithful script preservation
- Bruno collection and environment formats
- HAR request collection import/export

---

## Common Testing Scenarios

1. Import a collection and send a single request from Workbench.
2. Build a dev, staging, or tenant-specific environment profile and switch contexts with the active environment dropdown.
3. Queue a login -> create -> update -> delete sequence in the Collection Runner, then inspect results in History.
4. Send an interesting request to Repeater when you want to tamper with one case.
5. Export a cleaned-up collection, environment, or History record for handoff and reporting.

---

## Error Handling and Troubleshooting

### JavaScript Runtime Unavailable or Limited Script Mode

- Full mode means a supported JavaScript runtime is available.
- Limited mode means runtime probing failed and only legacy post-response regex extraction is available.
- Disabled mode means Java is below the supported runtime requirement.
- Runtime implementation details are intentionally not surfaced in the operator UI.
- Use Diagnostics capture when collecting runtime evidence.

### Other common issues

| Area | What to check |
| --- | --- |
| Import | Confirm the file type and import format are supported. |
| Variables | Verify the active environment, collection values, and request overrides. |
| OAuth2 | Confirm the selected environment profile, grant fields, and callback settings. |
| Runner | Check stop conditions, redirects, retry counts, and queue contents. |
| Auth | Confirm inheritance from request, folder, collection, or explicit no-auth. |
| Build / load | Confirm the JAR was built with Java 17+ and that Burp loaded the fat JAR. |

---

## Security and Safety Notes

- Treat live traffic carefully before importing to Sitemap or Intruder.
- Treat scripts as trusted code only.
- Store secrets carefully; Burp project files may contain them.
- Be careful with file uploads and exported archives.
- OAuth2 loopback callbacks use localhost and should only be used in controlled environments.

---

## Operator Checklists

### Before Live Traffic

- Confirm the active environment profile.
- Confirm the destination tab.
- Confirm the checked requests.

### Before OAuth2 Testing

- Confirm the selected environment profile.
- Confirm the redirect / callback settings.
- Confirm any token outputs that will be written back.

### Before Saving or Sharing State

- Review History and Diagnostics for sensitive data.
- Review exported collections and environment files for secrets.
- Confirm the correct active environment.

### Before Reporting a Tool Issue

- Capture Diagnostics.
- Note the Java version.
- Note whether script mode was Full, Limited, or Disabled.
