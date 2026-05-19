# API Workbench for Burp Suite

A Burp Suite Professional/Community extension that imports **Postman**, **Bruno**, **OpenAPI/Swagger**, **Insomnia**, and **HAR** collections into Burp Suite Repeater and/or Sitemap - with a built-in **Collection Runner** for sequential API execution, **OAuth2 token management**, **Nashorn JavaScript script execution**, and a **Workbench** for request editing and direct sending.

---

## Features

### Multi-Format Import
| Format | Extensions | Auto-Detect |
|--------|-----------|-------------|
| Postman | `.json` | Yes - v2.0 / v2.1 |
| Bruno | `.bru` (folder or single file) | Yes |
| OpenAPI / Swagger | `.json`, `.yaml`, `.yml` | Yes |
| Insomnia v4 | `.json` | Yes |
| HAR | `.har` | Yes |

### Workbench
- **Collection tree** - checkbox tree with Collection > Folder > Request hierarchy
- **Env binding** - bind environment files to specific collections (or all) explicitly
- **Request editor** - edit method, URL, headers, body, auth, and scripts inline
- **Direct send** - execute edited request immediately and inspect response (Pretty/Raw/Hex); pre/post scripts run automatically (respecting script mode)
- **Import destinations** - Repeater, Sitemap, Intruder

### Import Destinations
- **Repeater** - creates tabs for manual testing (no live requests)
- **Sitemap** - sends live requests, populates Target/Sitemap with real responses
- **Intruder** - sends raw request to Intruder for payload position configuration
- **Both** - Repeater tabs + live Sitemap entries

### Variable Resolution
- Collection-level / global variables (source depends on format; see Playbook 4)
- Environment files (Postman environment JSON)
- Request-level variables (Bruno `vars` block, Postman request vars)
- Custom manual variables (Variables tab + OAuth2 tab)
- Postman-style auth inheritance from collection and folder auth, including explicit no-auth overrides
- Unresolved-variable preflight modal before Workbench send, import, and runner start
- Full workspace state can be restored from Burp project data, including loaded collections, request tree checks/selections, runtime variables, and OAuth2 runtime values
- Variables and OAuth2 edits autosave to the selected collection; use Save Now for an explicit commit
- Runtime variable/OAuth2 export and import as JSON for repeat testing
- Default values: `{{var|default}}`
- Defaulted placeholders are treated as resolved by preview, preflight, and stop-on-missing-variable checks
- Nested variable resolution
- Unified precedence across Workbench, Import, and Runner flows (see Playbook 4)

### Collection Runner
- Execute checked requests **sequentially** like Postman Collection Runner
- Preview the final ordered run before sending, including collection, method, URL preview, unresolved vars, and auth status
- Configurable **delay between requests** (rate limiting)
- Configurable **retries** with visible attempt/debug logging
- Pause after current request, resume, or step exactly one request for chain debugging
- **Variable extraction** from responses (JSON path, script patterns, nested dotted paths like `jsonData.data.token`)
- **Assertions** (status code, headers, JSON properties)
- Stop conditions: error, assertion failure, HTTP status >= 400, missing variable, or after N failures
- Results table with status, timing, size, assertion pass/fail
- Runner timeline table with collection, request, status, time, retries, changed vars, and assertion summary
- Auto-populates Sitemap with runner responses
- Disabled headers, form-data fields, and URL-encoded fields are skipped when building requests
- Per-request OAuth2 auth scoping is resolved per send through the shared pipeline's fresh resolver
- **Execution model**: pre/post scripts and response extraction/assertions run in both Collection Runner and Workbench direct send via a shared pipeline

### Script Modes
| Mode | Java Requirement | Behavior |
|------|-----------------|----------|
| Full JS | Java 17+ with Nashorn | Pre/post scripts execute fully via Nashorn |
| Limited | Java 17+ but Nashorn probe failed | Post-response regex fallback only (no pre-script execution) |
| Disabled | Java < 17 | Scripts skipped entirely |

### JavaScript Script Engine (Nashorn)
- Executes **pre-request** and **post-response** scripts using Nashorn
- Supports **Postman** script API: `pm.environment.set()`, `pm.expect().to.have.status()`, etc.
- Supports **Bruno** script API: `bru.setVar()`, `res.getBody()`, etc.
- Regex fallback for environments where Nashorn is unavailable
- Variable extraction from JSON responses via script execution
- Script capability is probed at startup and shown in the UI status bar

### OAuth2 Token Management
- **Client Credentials** - fully automated, no browser
- **Password (ROPC)** - automated with username/password
- **Authorization Code + PKCE** - opens browser, localhost callback listener
- **Refresh Token** - auto-refresh before expiry
- Token storage in-memory only (never persisted to disk)
- Auto-injects `Authorization: Bearer <token>` into requests
- Imported collection auth metadata is normalized at runtime into canonical `oauth2_*` variables
- **Token endpoint strict mode** (default): OAuth token requests automatically use `Content-Type: application/x-www-form-urlencoded` and a canonical form body built from `oauth2_*` vars, overriding imported multipart bodies. Disable with variable `oauth2_token_force_urlencoded=false`. Allow multipart passthrough with `oauth2_token_allow_multipart=true`

> **Security note:** API Workbench saves its full workspace state in Burp project extension data. On a disk-backed project, that state is restored with the project next session; on a temporary project, it lives only for the current in-memory session. The saved workspace can include secrets such as access tokens, refresh tokens, client secrets, passwords, and secret-like runtime keys, so treat Burp project files as sensitive. Use Export Runtime JSON only when you intentionally want a portable snapshot.

### OpenAPI Example Generation
- Recursive schema traversal with full type support
- Handles `$ref`, `oneOf`, `anyOf`, `allOf`, `enum`, `format`
- Generates realistic examples: emails, UUIDs, dates, URLs
- Respects `minLength`, `minimum`, `maximum`, `multipleOf` constraints

---

## Installation

### Build from Source
```bash
git clone <repo-url>
cd api-workbench-for-burp
mvn clean package
```
Load the fat JAR in Burp Suite:
```
Extensions -> Add -> Select: target/*-jar-with-dependencies.jar
```

### Requirements
- Burp Suite Professional or Community Edition
- **Java 17+** (montoya-api 2024.12 requires Java 17)
- Maven 3.6+

---

## Documentation

- [Operator Guide](OPERATOR_GUIDE.md) - field workflow guide for loading collections, variables, OAuth2, runner controls, errors, and common testing scenarios.
- [Complete Documentation](DOCUMENTATION.md) - architecture, parser behavior, data flow, security notes, and implementation details.

---

## Operational Playbooks

### Playbook 1: Fast Manual Testing in Repeater
1. Click **+ Add Collection** and select your collection file or Bruno folder.
2. Check requests in the **Workbench** tree (collection, folder, or individual request level).
3. Check **Repeater** as the destination.
4. Click **Import Checked**.
5. Find the created tabs in Burp Repeater, edit and send manually.

Repeater is best for manual tampering and iterative payload testing. No live traffic is sent during import.

### Playbook 2: Baseline Live Behavior via Sitemap
1. Check requests in the Workbench tree.
2. Check **Sitemap** as the destination.
3. Click **Import Checked**.
4. Imported requests are sent live and responses appear in Target > Sitemap.

Use the delay spinner to pace live traffic and avoid rate-limiting.

### Playbook 3: Stateful Flow Testing in Collection Runner
1. Load and select an ordered set of requests in the Workbench tree.
2. Switch to the **Collection Runner** tab.
3. Configure delay, retries, follow-redirects, and stop conditions.
4. Click **Preview Run** to inspect the final ordered request list, URL previews, unresolved variables, and auth status.
5. Click **Start Collection Runner**. If unresolved variables remain after defaulted placeholders are ignored, use the modal quick-entry fields to apply runtime values or continue intentionally.
6. Use **Pause**, **Resume**, or **Step** while debugging chained APIs.
7. Results and the runner timeline update in real time; extracted variables feed automatically into downstream requests.

### Playbook 3b: Direct Send from Workbench
1. Click a request in the tree to load it into the editor.
2. Edit method, URL, headers, body, or auth as needed.
3. Click **Send**.
4. Inspect the response in Pretty, Raw, or Hex view.

> **Note:** Pre/post scripts, variable extraction, and assertions run in both **Workbench Send** and **Collection Runner** via the shared pipeline.
>
> In the Collection Runner, use script syntax like:
> ```javascript
> pm.environment.set("auth_token", jsonData.access_token);
> ```
> or comment-based extraction:
> ```javascript
> // extract: auth_token = $.data.token
> ```

### Playbook 4: Variables Tab Usage
Open the **Variables** tab, select a target collection from the dropdown, and enter variables in either format:

**key=value lines:**
```
api_base=https://api.example.com
token={{oauth2_access_token}}
oauth2_client_id=my-client
```

**JSON object:**
```json
{
  "api_base": "https://api.example.com",
  "token": "{{oauth2_access_token}}",
  "oauth2_client_id": "my-client"
}
```

Precedence during runtime (highest to lowest):
1. Request-level variables (`req.variables`)
2. Extracted / runtime variables (Variables tab + script extraction + runner-extracted vars)
3. Scoped OAuth2 runtime vars (`runtimeOAuth2`, incl. `oauth2_access_token`)
4. Collection-level variables (`collection.variables`)
5. Collection environment map (`collection.environment`)
6. Default values in `{{var|default}}` syntax

> **Note:** Because runtime vars (level 2) take precedence over OAuth2 vars (level 3), a script can intentionally override `oauth2_access_token` if needed.

**Collection-scoped isolation:**
Each collection resolves variables in its own context. Collection1 and Collection2 can both define `base_url` or `client_id` without collision.

**Unresolved-variable preflight:**
Before Workbench send, import, or runner start, unresolved `{{vars}}` are shown in a modal grouped by request and collection. Entered values are applied into the selected collection runtime variables before continuing.

**Runtime JSON portability:**
Use **Export Runtime JSON** and **Import Runtime JSON** in the Variables tab to save and reload a collection's runtime variables and OAuth2 runtime values. Import supports merge or replace.

**Autosave behavior:**
- Typing in the Variables editor autosaves to the selected collection after a short debounce.
- Table edits and add/remove row actions also autosave.
- Use **Save Now** to explicitly persist the current editor contents immediately.
- Use **Clear** to clear the editor UI only; it does not autosave until you click **Save Now**.

**Format-specific collection/global variable sources:**
- **Postman** - `collection.variable` array (objects/arrays serialized to JSON string)
- **Insomnia** - `environment` resources in export (`resources[].data` key-value map)
- **Bruno** - `bruno.json` top-level `vars` / `variables` / `env` / `presets` objects
- **OpenAPI** - `servers[].variables` default values (populated as `collection.environment`)
- **HAR** - no collection variable model; request values only

### Playbook 5: OAuth2 Tab Workflow
1. Switch to the **OAuth2** tab.
2. Select the grant type: Client Credentials, Password, Authorization Code, or Refresh Token.
3. Fill the Token URL, Auth URL (for Authorization Code), Client ID, and Client Secret.
4. For Password grant, add Username and Password.
5. Click **Acquire Token**.
6. The acquired token is injected into requests where `auth.type = "oauth2"`. The runtime manager can refresh it automatically before expiry.
7. OAuth2 edits autosave to the selected collection, and **Save Now** writes the current OAuth2 form state immediately.

### Playbook 6: Imported OAuth2 Auth Compatibility
When collections define OAuth2 metadata (endpoints, client ID, grant type, etc.), the extension normalizes parser-specific property names into canonical `oauth2_*` runtime variables at execution time.

Supported mappings include:
- `grantType` / `grant_type` -> `oauth2_grant`
- `accessTokenUrl` / `access_token_url` -> `oauth2_token_url`
- `clientId` / `client_id` -> `oauth2_client_id`
- `clientSecret` / `client_secret` -> `oauth2_client_secret`
- and others

This applies across Postman, Bruno, OpenAPI, and Insomnia imports, and prevents missing-parameter body issues in token requests.

### Playbook 7: Quick Triage for 400/401
If a request returns 400 or 401, check in order:

- **Token URL and grant type** - verify `oauth2_token_url` resolves to the correct endpoint and `oauth2_grant` matches the server expectation.
- **Required body params** - Password grant needs `oauth2_username` + `oauth2_password`; Refresh Token needs `oauth2_refresh_token`; Authorization Code needs `oauth2_code`.
- **Client auth mode** - `oauth2_client_auth` can be `body`, `basic`, or `prefer_basic`. Try switching if the server rejects client credentials placement.
- **Unresolved variables** - check that `{{variable}}` placeholders have values in the Variables tab or OAuth2 tab.
- **Nested extraction paths** - ensure script paths like `jsonData.data.token` use dot notation correctly; the runner and script engine support nested dotted paths.

### Mode Selection Guide

| Goal | Use | Why |
|------|-----|-----|
| Manual manipulation | Repeater | fastest iterative request editing |
| Real endpoint behavior + history | Sitemap | live send + target visibility |
| Multi-step automated flow | Collection Runner | sequencing + extraction + assertions |

---

## Architecture

```
src/main/java/burp/
|-- BurpExtender.java              # Extension entry point
|-- UniversalImporter.java         # Core import orchestrator
|-- auth/
|   |-- OAuth2Config.java          # OAuth2 configuration model
|   |-- OAuth2Manager.java         # Token lifecycle manager
|   |-- TokenStore.java            # In-memory token cache
|   |-- ClientCredentialsHandler.java
|   |-- PasswordGrantHandler.java
|   |-- RefreshTokenHandler.java
|   `-- AuthorizationCodeHandler.java  # PKCE + localhost callback
|-- models/
|   |-- ApiRequest.java            # Unified request model
|   |-- ApiCollection.java         # Unified collection model
|   |-- ImportResult.java          # Import operation result
|   |-- RunnerPreviewRow.java      # Runner preview row model
|   |-- RunnerResult.java          # Runner operation result
|   |-- RunnerStopConditions.java  # Runner stop-condition config
|   |-- RunnerTimelineRow.java     # Runner timeline row model
|   `-- UnresolvedVariableIssue.java # Variable preflight issue model
|-- parser/
|   |-- CollectionParser.java      # Parser interface
|   |-- ParserRegistry.java        # Auto-detect registry
|   |-- PostmanParser.java         # Postman v2.0/v2.1
|   |-- BrunoParser.java           # Bruno .bru files + JS normalization
|   |-- OpenApiParser.java         # OpenAPI 2.x/3.x JSON+YAML
|   |-- InsomniaParser.java        # Insomnia v4 JSON
|   |-- HarParser.java             # HAR archives
|   `-- VariableResolver.java      # {{variable}} resolution engine
|-- runner/
|   `-- CollectionRunner.java      # Sequential execution + JS engine
|-- ui/
|   |-- ImporterPanel.java         # Main Swing UI (Workbench + Variables + OAuth2 + Runner)
|   |-- OAuth2Panel.java           # OAuth2 configuration UI
|   |-- RequestEditorPanel.java    # Workbench request editor (method, url, headers, body, auth, scripts)
|   |-- ResponsePane.java          # Workbench response display (Pretty/Raw/Hex)
|   |-- RunnerPreviewTableModel.java
|   |-- tree/
|   |   |-- CollectionTreeNode.java
|   |   `-- CheckBoxTreeCellRenderer.java
|   |-- RequestPreviewTableModel.java
|   |-- RunnerResultTableModel.java
|   |-- RunnerTimelineTableModel.java
|   `-- UnresolvedVariablesDialog.java
`-- utils/
    |-- HttpUtils.java             # URL parsing utilities
    |-- RequestBuilder.java        # HTTP message builder + OAuth2 + file uploads
    |-- ScriptEngine.java          # Nashorn JS execution + Postman/Bruno APIs
    |-- OAuth2RuntimeMapper.java   # Normalizes imported auth to canonical oauth2_* vars
    |-- RuntimeVariablesJson.java  # Runtime vars/OAuth2 JSON import/export
    |-- SharedRequestPipeline.java # Shared build/send/script/OAuth pipeline
    `-- UnresolvedVariableAnalyzer.java # Preflight unresolved-variable scanner
```

---

## Security Notes

- **Tokens**: Stored in-memory via `TokenStore` (static `ConcurrentHashMap`). Cleared on extension unload or via OAuth2 panel. Survives extension reloads within the same Burp JVM session.
- **Runtime JSON exports**: Manual exports can include runtime OAuth2 values such as access/refresh tokens. Treat exported JSON files as sensitive.
- **Client secrets**: Passed as variables (`{{client_secret}}`), never hardcoded. UI uses `JPasswordField` for secret fields.
- **PKCE**: Used for Authorization Code flow (S256 method).
- **Localhost listener**: Binds to `127.0.0.1:9876`, validates `state` parameter.

## Known Limitations

- **No sandbox**: Nashorn scripts can access any Java class via `Java.type()`. Only run trusted collection scripts.
- **No script timeout**: Infinite loops in pre/post-request scripts will hang the runner thread.
- **File uploads**: Multipart file reading is supported only when a field is explicitly marked as a file upload with file metadata. Plain path-looking values are treated as text.
- **Hardcoded OAuth2 port**: Authorization Code callback listens on fixed port `9876`.
- **Test suite**: Automated tests cover parsers, request building, shared pipeline behavior, runner controls/stop conditions/timeline, variable preflight, and runtime JSON import/export. Run with `mvn test`.
- **Parser encoding**: All JSON/YAML/HAR parsers and Bruno request decoding use explicit UTF-8.

---

## License

MIT License.

---

## Author

**Sachinico De Leon** - Cybersecurity Professional, Philippines
