# API Workbench for Burp - Operator Guide

This guide is for operators using API Workbench during API testing, debugging, and assessment work. It focuses on what to click, what each option does, how state is saved, how variables and OAuth2 behave, and how to recover from common errors.

---

## Table of Contents

1. [Quick Start](#quick-start)
2. [Mental Model](#mental-model)
3. [Installation and Startup Checks](#installation-and-startup-checks)
4. [Workbench Tab](#workbench-tab)
5. [Variables Tab](#variables-tab)
6. [OAuth2 Tab](#oauth2-tab)
7. [Collection Runner Tab](#collection-runner-tab)
8. [Import Destinations](#import-destinations)
9. [Auth Inheritance](#auth-inheritance)
10. [Workspace Persistence](#workspace-persistence)
11. [Runtime JSON Export and Import](#runtime-json-export-and-import)
12. [Scripts and Assertions](#scripts-and-assertions)
13. [Supported Collection Formats](#supported-collection-formats)
14. [Common Testing Scenarios](#common-testing-scenarios)
15. [Error Handling and Troubleshooting](#error-handling-and-troubleshooting)
16. [Security and Safety Notes](#security-and-safety-notes)
17. [Operator Checklists](#operator-checklists)

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
   - **Run a chained flow**: switch to **Collection Runner**, click **Preview Run**, then start.

---

## Mental Model

API Workbench has four operator surfaces:

| Tab | Primary Job |
|-----|-------------|
| **Workbench** | Load collections, check requests, edit one request, send/import checked requests |
| **Variables** | Manage collection-scoped runtime variables and runtime JSON files |
| **OAuth2** | Configure/acquire OAuth2 tokens and bind bearer aliases |
| **Collection Runner** | Execute checked requests sequentially with preview, retries, stop conditions, and timeline |

The extension keeps runtime state scoped per collection. If two loaded collections both use `{{base_url}}`, they can have different values without leaking into each other.

### Variable Precedence

When a request is built, values are resolved in this order, highest first:

1. Request-level variables
2. Runtime variables from Variables tab, unresolved-var preflight, scripts, or runner extraction
3. Scoped OAuth2 runtime values
4. Collection variables
5. Collection environment values
6. Default value in `{{var|default}}`

Defaulted placeholders like `{{host|https://example.com}}` are treated as resolved.

---

## Installation and Startup Checks

After loading the extension, Burp output should show only API Workbench startup lines similar to:

```text
API Workbench for Burp v2.0.0
Supports: Postman, Bruno, OpenAPI, Insomnia, HAR
Features: Import + Collection Runner + Workbench
Java: ... | Script: ...
Extension loaded successfully!
```

Check the script mode line:

| Script Mode | Meaning | Operator Impact |
|-------------|---------|-----------------|
| Full JS | Nashorn is available | Pre-request scripts and post-response scripts can run |
| Limited | Java is usable but Nashorn probe failed | Regex extraction fallback only; pre-request scripts are skipped |
| Disabled | Script support unavailable | Scripts are skipped |

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
|---------|----------|
| **+ Add Collection** | Opens a file chooser and imports a collection or Bruno folder |
| **- Remove Selected** | Removes checked collection nodes from the current workspace |
| **Check All** | Checks all visible collection/request nodes |
| **Uncheck All** | Unchecks all visible collection/request nodes |

Notes:

- Duplicate collection names are rejected to avoid ambiguous variable binding.
- Removing a collection stops OAuth2 auto-refresh for that collection.
- Loaded collections can be restored from Burp project data when using a project on disk.

### Request Tree

The tree shows:

```text
Collection
  Folder
    Request
```

Use checkboxes for batch operations. Click a request row to load it into the request editor.
Right-click a collection, folder, or request node to open **Auth Settings...** for that scope.

### Request Editor

Editable request areas:

| Area | What You Can Change |
------|---------------------|
| Method / URL | HTTP method, URL, query parameters |
| Headers | Add, remove, enable, disable headers |
| Auth | Select inherit or an auth type, then edit auth properties |
| Body | Raw, URL-encoded, form-data, GraphQL, file-like modes where supported |
| Scripts | Pre-request and post-response scripts |

Buttons:

| Button | Behavior |
|--------|----------|
| **Send** | Sends the current edited request once |
| **Send + Repeater** | Sends the request and also creates a Repeater tab |
| Send dropdown | Switches between send modes |

Workbench send uses the same shared execution pipeline as the Collection Runner. That means variables, OAuth2 refresh, pre-request scripts, post-response scripts, and runtime variable updates behave consistently.

Table-based editor tabs always keep one blank starter row available when empty:

- **Params** can be edited immediately without pressing `+`
- **Headers** can be edited immediately without pressing `+`
- **form-data** and **x-www-form-urlencoded** body modes can be edited immediately without pressing `+`
- Blank starter rows are ignored when requests are built, so they do not serialize unless you enter a key

### Workbench Detail Tabs

| Tab | Purpose |
|-----|---------|
| **Request** | Final built request that was sent |
| **Response** | Response returned by Burp HTTP client |
| **Meta** | Request name, method, auth source, resolved URL, status, elapsed time, response size, unresolved tokens |

### Environment Binding

Controls:

| Control | Behavior |
|---------|----------|
| **Browse...** | Selects a Postman environment JSON file |
| **Apply to Checked Requests** | Loads environment values into the checked requests' local variables |
| **Apply to All Collections** | Loads environment values into all loaded collections |

Environment binding mutates runtime variables. It can override keys already present in the target runtime layer.

### Options Pane and Actions Button

The Workbench control strip is titled **Options** and contains the **Actions** button. Use it to choose import destinations and related send options.

Options:

| Option | Meaning |
|--------|---------|
| **Repeater** | Creates Repeater tabs without sending live traffic |
| **Sitemap (Live)** | Sends requests live and adds responses to Target > Sitemap |
| **Intruder** | Sends raw requests to Intruder |
| **Delay (ms)** | Delay used for live Sitemap sends |
| **Debug final raw request** | Logs final raw request and variable resolution details |

Buttons:

| Button | Behavior |
|--------|----------|
| **Import Checked** | Imports checked requests to selected destinations |
| **Run Checked** | Sends checked requests to the Collection Runner flow |

Before Workbench send/import/run, unresolved variables trigger a modal. You can cancel, continue intentionally, or apply values into collection runtime variables.

---

## Variables Tab

The Variables tab manages collection-scoped runtime variables.

### Target Collection

Select the collection you want to edit in the **Target** dropdown. All edits apply only to that collection.

### Views

| View | Best For |
|------|----------|
| **Raw** | Fast editing, paste JSON, paste key=value lines |
| **Table** | Small edits and safer key/value scanning |

Accepted raw formats:

```text
base_url=https://api.example.test
tenant=qa
token={{oauth2_access_token}}
```

or:

```json
{
  "base_url": "https://api.example.test",
  "tenant": "qa",
  "token": "{{oauth2_access_token}}"
}
```

### Base Layers and Runtime Overrides

The Variables tab can display read-only base sections:

- Collection environment
- Collection definition variables
- Scoped OAuth2 runtime

Only the `# Runtime overrides (edits apply here)` section is intended for normal operator edits.

### Autosave

Autosave behavior:

- Typing in Raw view autosaves after a short debounce.
- Table edits autosave.
- Add/remove row actions autosave.
- Switching target collection cancels pending autosave.
- Programmatic refreshes do not autosave.
- **Clear** only clears the editor UI and does not wipe runtime vars unless you later click **Save Now**.

Controls:

| Control | Behavior |
|---------|----------|
| **Save Now** | Immediately writes the current editor contents to selected collection runtime vars |
| **Bind to All Collections** | Writes current editor runtime vars to every loaded collection after confirmation |
| **Export Runtime JSON** | Saves runtime vars and OAuth2 runtime for selected collection to JSON |
| **Import Runtime JSON** | Loads runtime JSON into selected collection with Merge or Replace |
| **Clear** | Clears editor UI only; does not autosave an empty map |

### Unresolved Variables

Before Workbench send, import, or runner start, API Workbench scans checked requests for unresolved `{{vars}}`.

The modal shows:

- Request name
- Collection
- Variable name
- Location such as URL, header, body, auth, form field, GraphQL

Actions:

| Action | Behavior |
|--------|----------|
| **Apply to Runtime Vars** | Saves entered values into the affected collection runtime vars and continues |
| **Continue Without Applying** | Continues with unresolved placeholders intact |
| **Cancel** | Stops the operation |

---

## OAuth2 Tab

The OAuth2 tab manages collection-scoped OAuth2 settings and token acquisition.

### Target Collection

Select the collection in the top dropdown. OAuth2 form edits autosave to that collection.

### Grant Types

| Grant Type | Required Fields | Notes |
|------------|-----------------|-------|
| Client Credentials | Token URL, Client ID, Client Secret unless `oauth2_client_auth=none` | Common service-to-service flow |
| Password | Token URL, Client ID, Client Secret, Username, Password | Legacy/internal use only |
| Authorization Code | Token URL, Auth URL, Client ID, Redirect URI, optional secret, PKCE | Opens browser and listens on localhost |
| Refresh Token | Token URL, Client ID, optional secret, Refresh Token | Useful for renewing a known session |

### OAuth2 Controls

| Control | Behavior |
|---------|----------|
| **Populate from Checked Request** | Extracts OAuth2 fields from the checked request and resolves `{{variables}}` from the owning collection context |
| **Acquire Token** | Requests a token using current OAuth2 settings |
| **Start Auto Refresh** | Starts collection-scoped refresh loop |
| **Clear Tokens** | Clears in-memory OAuth2 tokens |
| **Save Now** | Immediately saves current OAuth2 form values to selected collection |
| **Bind OAuth2 to All** | Writes current OAuth2 settings to every loaded collection after confirmation |

### Populate from Checked Request

The populate button checks the currently checked request and extracts:

- Grant type
- Token URL from auth metadata
- Token URL inferred from request URL when it looks like a token request
- Client ID
- Client secret
- Scope
- Username/password
- Refresh token
- Authorization code
- Redirect URI
- Client auth mode

Placeholders inside extracted OAuth2 values are resolved using the checked request's owning collection context first. That includes collection environment values, collection variables, runtime OAuth2 values, runtime variables, and request variables.

Token URL inference is conservative. It uses signals such as:

- URL path contains `/token`, `/oauth/token`, `/oauth2/token`, `/connect/token`, or `/auth/token`
- Body mode is URL-encoded
- Content-Type is `application/x-www-form-urlencoded`
- Body contains OAuth field names such as `grant_type`, `client_id`, `client_secret`, `refresh_token`, `code`, or `code_verifier`

Explicit token URL metadata wins and is not overwritten by inference.

If any placeholders remain unresolved after populate, the Workbench log reports the missing variable names so you can bind or edit them before acquiring a token.

### Acquire Token

When **Acquire Token** succeeds:

1. Canonical OAuth2 vars are saved into the captured target collection.
2. The token preview is updated.
3. If requests in that same collection use bearer aliases such as `{{accessToken}}`, API Workbench shows a dialog:

   ```text
   Token acquired.
   These bearer variables are used by this collection:
   - accessToken
   - auth_token

   Bind them to the acquired access token?
   ```

4. Selected aliases are written to that collection's runtime variables.

The dialog only scans the captured collection from the moment **Acquire Token** was clicked. It does not scan or modify other loaded collections.

### Auto Refresh

Auto-refresh behavior:

- Uses selected collection OAuth2 runtime vars.
- Requires a valid refresh token for refresh-token mode.
- Runs on a background scheduler.
- Writes refreshed token values back into that collection.
- Stops when the collection is removed or the extension unloads.

---

## Collection Runner Tab

Use the Collection Runner for stateful, ordered API flows.

### Runner Configuration

| Option | Meaning |
|--------|---------|
| **Delay (ms)** | Wait between requests |
| **Retries** | Number of retries after the first attempt |
| **Stop on error** | Stop on build/send/transport failure |
| **Stop on assertion failure** | Stop if post-response assertion fails |
| **Stop on status >= 400** | Stop on HTTP 4xx/5xx |
| **Stop when variable missing** | Stop before sending requests with unresolved vars |
| **Stop after failures** | Stop after N failures; `0` disables |
| **Follow redirects** | Controls Burp HTTP redirection behavior |
| **Debug final raw request** | Logs final request and variable source details |

### Runner Controls

| Button | Behavior |
|--------|----------|
| **Preview Run** | Opens final ordered request list before sending |
| **Start Collection Runner** | Starts selected run |
| **Cancel** | Requests cancellation and waits until runner is idle |
| **Pause** | Pause after the current request completes |
| **Resume** | Continue a paused run |
| **Step** | Run one queued request, then pause again |

### Run Preview

Preview shows:

- Request order
- Collection
- Method
- URL preview
- Unresolved variables
- Auth status and source

Use preview to catch:

- Wrong collection context
- Missing runtime variables
- Requests using no auth
- Requests pointing to production accidentally
- Incorrect order after multi-collection selection

### Runner Results

The results table shows request-level results:

- Request
- Method
- Success/failure
- Status code
- Response time
- Response size
- Error message
- Extracted variables
- Assertion results

### Runner Timeline

The timeline is optimized for operator scanning:

```text
# | Collection | Request | Status | Time | Retries | Vars Changed | Assertions
```

Use it to answer:

- Which request failed first?
- Did retry recover?
- Which step changed variables?
- Did assertions fail before transport failed?

### Retry Logging

Retry logs are explicit:

```text
Attempt 1/3 failed: timeout
Retrying in 400ms
Attempt 2/3 passed
```

Retries means retries after the first attempt. For example, `Retries = 2` means up to 3 total attempts.

---

## Import Destinations

### Repeater

Use Repeater when:

- You want manual payload changes.
- You do not want live traffic during import.
- You need Burp's normal request editing workflow.

### Sitemap (Live)

Use Sitemap when:

- You want Burp Target > Sitemap populated.
- You want passive scanning visibility.
- You are ready to send real traffic.

Sitemap sends the request live once and stores the response.

### Intruder

Use Intruder when:

- You want to configure payload positions.
- You want to fuzz a checked request after import.

---

## Auth Inheritance

Postman collections can define auth at collection, folder, and request level. API Workbench preserves that behavior and lets you edit it from the Workbench tree.

Resolution order:

1. Request auth wins.
2. Nearest folder auth applies next.
3. Collection auth applies next.
4. Explicit `noauth` stops inheritance.

Auth settings are edited per scope:

- Collection nodes can be set to `none` or an explicit auth type.
- Folder nodes can be set to `inherit`, `none`, or an explicit auth type.
- Request nodes can be set to `inherit`, `none`, or an explicit auth type.

Metadata shown to operators:

| Field | Meaning |
|-------|---------|
| `authInherited` | Request inherited auth from folder/collection |
| `authExplicitlyDisabled` | Request or parent explicitly selected no-auth |
| `authSource` | Source label such as `request: Login`, `folder: Admin`, `collection: API` |
| `authOverrideMode` | Stored request override mode: `inherit`, `explicit`, or `none` |

Runner preview and Workbench Meta show auth source so you can see why a request is authenticated or unauthenticated.

---

## Workspace Persistence

API Workbench saves its full workspace state through Burp project extension data.

- Disk-backed Burp projects restore the saved workspace next time the project is opened.
- Temporary Burp projects keep the workspace only for the current in-memory Burp session.
- Workspace snapshots are coalesced after edits and unchanged snapshots are skipped to reduce unnecessary Burp project growth.
- Saved workspace state includes loaded collections, request tree checks/selections, runtime variables, OAuth2 runtime/config values, access tokens, refresh tokens, client secrets, passwords, and secret-like runtime keys.
- Treat Burp project files as sensitive because API Workbench may store secrets there.

---

## Runtime JSON Export and Import

Runtime JSON is explicit and operator-controlled.

Use it when:

- You want repeatable test setup.
- You want to move runtime values between Burp projects.
- You want to save OAuth2/runtime state intentionally.

Export shape:

```json
{
  "version": 1,
  "collection": "Collection Name",
  "runtimeVars": {
    "base_url": "https://api.example.test"
  },
  "runtimeOAuth2": {
    "oauth2_token_url": "https://auth.example.test/token"
  }
}
```

Import modes:

| Mode | Behavior |
|------|----------|
| Merge | Adds imported keys and overwrites matching keys, keeps unrelated existing keys |
| Replace | Clears current runtime vars and runtime OAuth2 values, then applies imported maps |

Treat exported runtime JSON as sensitive if it includes tokens, secrets, or credentials.

---

## Scripts and Assertions

API Workbench supports Postman-style and Bruno-style scripts when Nashorn is available.

### Common Variable Extraction

Postman style:

```javascript
const jsonData = pm.response.json();
pm.environment.set("auth_token", jsonData.access_token);
```

Bruno style:

```javascript
const body = res.getBody();
bru.setVar("auth_token", body.access_token);
```

Comment extraction:

```javascript
// extract: auth_token = $.data.token
// extract: user_id = $.user.id
```

### Assertions

Examples:

```javascript
pm.expect(pm.response.code).to.have.status(200);
pm.expect(pm.response).to.have.header("Content-Type");
pm.expect(jsonData).to.have.property("id");
```

Assertions are reflected in Runner results and timeline. Stop-on-assertion-failure can halt the run.

### Script Safety

Nashorn is not sandboxed. Only run trusted collection scripts.

---

## Supported Collection Formats

| Format | Supported Inputs | Notes |
|--------|------------------|-------|
| Postman | v2.0/v2.1 JSON | Variables, folders, auth inheritance, scripts, body modes |
| Bruno | `.bru` file or folder | Variables, scripts, assertions, sequence order |
| OpenAPI/Swagger | JSON/YAML | Generates requests and example bodies from schemas |
| Insomnia | v4 JSON export | Requests, groups, headers, auth, body |
| HAR | `.har` | Imports captured requests and bodies |

Disabled headers, URL-encoded fields, and form-data fields are skipped when building requests.

Multipart file uploads only read local files when the field is explicitly marked as a file upload with metadata. Plain path-looking values are sent as text.

---

## Common Testing Scenarios

### Scenario: Import Collection to Repeater

1. Load collection.
2. Check target requests.
3. Select **Repeater**.
4. Click **Import Checked**.
5. Edit requests in Repeater.

Use when you are exploring manually.

### Scenario: Baseline All Endpoints into Sitemap

1. Load collection.
2. Check endpoints.
3. Set delay to a safe value.
4. Select **Sitemap (Live)**.
5. Click **Import Checked**.

Use when you want Burp Target populated. This sends live traffic.

### Scenario: Chained Login Flow

1. Load collection.
2. Select login request and dependent API requests.
3. Ensure login response script extracts token/user IDs.
4. Open Runner.
5. Click **Preview Run**.
6. Apply missing variables if prompted.
7. Start runner.
8. Watch timeline for variable changes.

### Scenario: OAuth2 Token Request Is in the Collection

1. Check the token request in the Workbench tree.
2. Open OAuth2 tab.
3. Select the target collection.
4. Click **Populate from Checked Request**.
5. Confirm Token URL, Client ID, Grant Type.
6. Check the log for any unresolved variable names if the request used placeholders.
7. Click **Acquire Token**.
8. If prompted, bind bearer aliases such as `accessToken` or `auth_token`.

### Scenario: Requests Use Non-Standard Bearer Variable Names

If a request has:

```text
Authorization: Bearer {{accessToken}}
```

After acquiring OAuth2 token, API Workbench can detect `accessToken` and offer to bind it to the acquired token in runtime vars.

### Scenario: Runner Stops on Missing Variable

1. Check runner preview unresolved vars column.
2. Use preflight quick-entry modal to apply values.
3. Confirm values land in the correct collection in Variables tab.
4. Run again.

### Scenario: Save Test State for Later

Options:

- Use a Burp project on disk for automatic workspace restore.
- Use **Export Runtime JSON** for portable runtime values.
- Avoid exporting secrets unless needed and approved.

---

## Error Handling and Troubleshooting

### Import Errors

| Symptom | Likely Cause | Action |
|---------|--------------|--------|
| Unknown collection format | Unsupported file or wrong export shape | Re-export as supported format |
| Invalid Postman collection | Missing `info` object | Export as Postman collection v2.1 |
| Duplicate collection name | Same name already loaded | Rename one collection before loading |
| No checked requests | Nothing checked in tree | Check request/folder/collection nodes |

### Variable Errors

| Symptom | Likely Cause | Action |
|---------|--------------|--------|
| `{{var}}` appears in final request | Missing runtime value | Use unresolved-variable modal or Variables tab |
| Value from wrong collection | Target dropdown points to another collection | Select the correct collection before editing |
| Edited vars disappear after switching collection | Edits were in UI but target changed before autosave | Use Save Now before switching for critical edits |
| Clear wiped editor only | Expected behavior | Click Save Now if you intentionally want to persist empty overrides |

### OAuth2 Errors

| Error | Cause | Action |
|-------|-------|--------|
| Invalid configuration | Missing token URL/client ID/grant fields | Fill required fields |
| Non-JSON response | Wrong token URL, proxy, WAF, HTML error | Inspect token URL and response |
| `invalid_client` | Wrong client ID/secret or auth mode | Try `oauth2_client_auth=basic`, `body`, or `none` |
| Missing refresh token | Auto-refresh enabled without refresh token | Acquire token again or import refresh token |
| Browser not supported | Headless environment | Use non-browser grant if possible |
| Authorization code timeout | No callback received | Check localhost port `9876` and redirect URI |

### Runner Errors

| Symptom | Likely Cause | Action |
|---------|--------------|--------|
| Runner stops immediately | Stop condition matched | Check runner log and preview |
| Status >= 400 stop | Target returned client/server error | Inspect response and auth |
| Assertion failure | Script assertion failed | Check assertion summary |
| Retry never succeeds | Network, DNS, timeout, bad endpoint | Check Burp proxy/network/VPN |
| Step button disabled | Runner is not paused | Click Pause first, wait for current request to finish |

### Auth Errors

| Symptom | Likely Cause | Action |
|---------|--------------|--------|
| Request shows No auth in preview | No auth inherited/effective | Check collection/folder/request auth |
| Request should inherit but does not | Parent has explicit noauth | Inspect auth source in preview/meta |
| Token acquired but request still 401 | Request uses non-standard bearer variable | Use bearer alias binding or set runtime var manually |
| OAuth2 request body rejected | Server expects basic client auth or no secret in body | Set `oauth2_client_auth` accordingly |

### Burp/Build Errors

| Symptom | Likely Cause | Action |
|---------|--------------|--------|
| No JAR artifact | Build failed before package | Run `mvn test` then `mvn -DskipTests package` |
| Extension will not load | Wrong JAR or Java version | Use fat JAR and Java 17+ |
| No API Workbench tab | Extension startup failed | Check Burp Extensions error output |

---

## Security and Safety Notes

- **Sitemap and Runner send live traffic.** Use delay and stop conditions when testing production-like systems.
- **Scripts are not sandboxed.** Collection scripts can access Java classes through Nashorn. Only run trusted scripts.
- **Project snapshots can contain tokens and secrets.** Live token cache is memory-only, but Burp project data can store OAuth access/refresh tokens and other secret values.
- **Runtime JSON can contain secrets.** Treat exported files as sensitive.
- **Authorization Code callback uses localhost port 9876.** Ensure redirect URI matches.
- **File uploads are explicit only.** Path-like text values are not read as files unless the field is marked as a file upload.
- **Autosave follows selected collection.** Check the Target dropdown before editing Variables or OAuth2 settings.

---

## Operator Checklists

### Before Live Traffic

- [ ] Confirm target environment and base URL.
- [ ] Check Runner Preview URL column.
- [ ] Resolve missing variables.
- [ ] Confirm auth status/source.
- [ ] Set safe delay.
- [ ] Enable relevant stop conditions.
- [ ] Confirm Sitemap/Runner live traffic is authorized.

### Before OAuth2 Testing

- [ ] Select correct OAuth2 target collection.
- [ ] Populate from selected token request when available.
- [ ] Confirm token URL and grant type.
- [ ] Confirm client auth mode.
- [ ] Acquire token.
- [ ] Bind bearer aliases if prompted.
- [ ] Verify Variables tab shows alias values when expected.

### Before Saving/Sharing State

- [ ] Decide whether Burp project persistence is enough.
- [ ] Use Runtime JSON only when portable state is needed.
- [ ] Review exported JSON for secrets/tokens.
- [ ] Store exported runtime files securely.

### Before Reporting a Tool Issue

- [ ] Capture collection format and request name.
- [ ] Note selected target collection.
- [ ] Include runner preview row if relevant.
- [ ] Include Workbench Meta output if relevant.
- [ ] Include Burp extension log lines.
- [ ] State whether the Burp project is temporary or on disk.
