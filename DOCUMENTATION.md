# API Workbench for Burp Suite - Complete Documentation

**Version:** 2.0.0  
**Author:** Sachinico De Leon  
**License:** MIT  
**Target Platform:** Burp Suite Professional / Community Edition  
**Java Version:** 17+  
**Montoya API:** 2024.12+

---

## Table of Contents

1. [Overview](#1-overview)
2. [Features](#2-features)
3. [Architecture](#3-architecture)
4. [Data Flow](#4-data-flow)
5. [Collection Format Support](#5-collection-format-support)
6. [Variable Resolution Engine](#6-variable-resolution-engine)
7. [Authentication & OAuth2](#7-authentication--oauth2)
8. [Collection Runner](#8-collection-runner)
9. [Script Engine](#9-script-engine)
10. [Live Sync / UI Mirroring](#10-live-sync--ui-mirroring)
11. [Security Considerations](#11-security-considerations)
12. [Known Limitations & Code-Level Behaviors](#12-known-limitations--code-level-behaviors)
13. [Error Handling](#13-error-handling)
14. [Performance](#14-performance)
15. [Extending the Extension](#15-extending-the-extension)
16. [Troubleshooting](#16-troubleshooting)

---

## 1. Overview

This Burp Suite extension bridges the gap between API development tools (Postman, Bruno, Insomnia) and security testing workflows. It imports API collections into Burp Suite for manual pentesting while providing a built-in Collection Runner for automated sequential execution with variable extraction.

For day-to-day usage, see the [Operator Guide](OPERATOR_GUIDE.md). This document focuses on architecture, implementation details, and reference behavior.

### Key Capabilities
- **Import** collections from 5 formats into Burp Repeater, Sitemap, or both
- **Run** collections sequentially with delays, retries, and assertions
- **Extract** variables from responses for use in subsequent requests
- **Manage** OAuth2 tokens across multiple grant types
- **Execute** JavaScript pre/post request scripts via Nashorn engine

---

## 2. Features

### 2.1 Multi-Format Import

| Format | File Extension | Auto-Detect Key | Auth Support | Scripts |
|--------|---------------|-----------------|--------------|---------|
| Postman v2.0/v2.1 | `.json` | `info.schema` or `info._postman_id` | Bearer, Basic, API Key, OAuth2 | Pre-request, Tests | ✅ |
| Bruno | `.bru` (folder or file) | `.bru` file extension | Basic (native), Bearer (header) | Pre-request, Post-response | ✅ |
| OpenAPI 2.x/3.x | `.json`, `.yaml`, `.yml` | `openapi` or `swagger` key | Bearer (auto-detected) | No | ✅ |
| Insomnia v4 | `.json` | `__type` = export or `_type` = request | Bearer, Basic, API Key | No | ✅ |
| HAR | `.har` | `log.entries` array | Headers only | No | ✅ |

### 2.2 Multi-Collection Support

Load multiple collections simultaneously:
- Click **+ Add Collection** to load additional collections
- Collection list shows name, format, and request count
- Remove individual collections with **- Remove**
- Checkbox tree shows requests from ALL loaded collections
- **Source** column identifies which collection each request belongs to
- Variables are collection-scoped (no cross-collection leakage)
- Import/Runner operates on checked requests across all collections

### 2.3 Import Destinations

**Repeater Mode**
- Creates Repeater tabs for each checked request
- No live HTTP requests made during import
- Best for: Manual tampering, one-off testing

**Sitemap Mode**
- Sends live HTTP requests to populate Target > Site map
- One request per endpoint (no parameter permutations)
- Configurable delay between requests (default 200ms)
- Best for: Attack surface mapping, passive scanning

**Both Mode**
- Repeater tabs + live Sitemap entries
- Delay applied between Sitemap requests only

### 2.4 Collection Runner

- **Sequential execution** with configurable inter-request delay
- **Run preview** shown automatically before sending, with collection, method, URL preview, unresolved variables, and auth status
- **Pause / resume / step** controls for debugging chained APIs
- **Variable extraction** from JSON responses via scripts or comments
- **Assertions** (status code, header presence, JSON property existence)
- **Configurable retry** with visible attempt logs and retry delay messages
- **Stop conditions** for error, assertion failure, status >= 400, missing variable, or after N failures
- **Real-time results** and **timeline** tables with status, timing, retries, variable changes, and assertion summary
- **Sitemap integration** - runner responses auto-populate Site map

### 2.5 OAuth2 Token Management

| Grant Type | Browser Required | Auto-Refresh | Use Case |
|-----------|-----------------|--------------|----------|
| Client Credentials | No | Yes | Service-to-service APIs |
| Password (ROPC) | No | Yes | Legacy/internal APIs |
| Authorization Code + PKCE | Yes | Yes | Modern web/mobile APIs |
| Refresh Token | No | Yes | Session maintenance |

### 2.6 Script Engine

- **Nashorn JavaScript** execution for pre/post request scripts
- **Postman API** compatibility: `pm.test()`, `pm.environment.get/set/unset()`, `pm.collectionVariables.get/set/unset()`, `pm.expect(...).to.have.status()`, `pm.expect(...).to.have.header()`, `pm.expect(...).to.have.property()`, `pm.expect(...).to.equal()`, `pm.expect(...).to.eql()`
- **Bruno API** compatibility: `bru.setVar()`, `res.getBody()`
- **Regex fallback** when Nashorn is unavailable
- **Comment extraction**: `// extract: key = $.json.path`

---

## 3. Architecture

### 3.1 Package Structure

```
burp/
|-- BurpExtender.java              # Extension entry point (Montoya API)
|-- UniversalImporter.java         # Core import orchestrator
|-- auth/
|   |-- OAuth2Config.java          # OAuth2 configuration model
|   |-- OAuth2Manager.java         # Token acquisition and refresh coordinator
|   |-- TokenStore.java            # In-memory token cache
|   |-- ClientCredentialsHandler.java # Client Credentials grant handler
|   |-- PasswordGrantHandler.java  # Resource Owner Password Credentials grant handler
|   |-- RefreshTokenHandler.java   # Refresh Token grant handler
|   `-- AuthorizationCodeHandler.java # PKCE + localhost callback grant handler
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
|   |-- PostmanParser.java         # Postman v2.0/v2.1 parser
|   |-- BrunoParser.java           # Bruno parser and script normalization
|   |-- OpenApiParser.java         # OpenAPI 2.x/3.x parser
|   |-- InsomniaParser.java        # Insomnia v4 parser
|   |-- HarParser.java             # HAR parser
|   `-- VariableResolver.java      # {{variable}} resolution engine
|-- runner/
|   `-- CollectionRunner.java      # Sequential request runner
|-- ui/
|   |-- ImporterPanel.java         # Main Swing UI (Workbench + Variables + OAuth2 + Runner)
|   |-- OAuth2Panel.java           # OAuth2 configuration UI
|   |-- RequestEditorAuthSupport.java # Auth-field orchestration helper
|   |-- RequestEditorBodySupport.java # Body-mode UI and form table helper
|   |-- RequestEditorPanel.java    # Workbench request editor
|   |-- RequestEditorStateMapper.java # Request model <-> editor state mapping helper
|   |-- RequestEditorTableSupport.java # Shared request-editor table behavior
|   |-- RequestPreviewTableModel.java # Import preview table model
|   |-- ResponsePane.java          # Workbench response display
|   |-- RunnerPreviewTableModel.java # Runner preview table model
|   |-- RunnerResultTableModel.java # Runner results table model
|   |-- RunnerTimelineTableModel.java # Runner timeline table model
|   |-- UnresolvedVariablesDialog.java # Variable preflight modal dialog
|   `-- tree/
|       |-- BurpLikeTreeCellRenderer.java # Shared request-tree renderer with explicit hierarchy cues
|       |-- CheckBoxTreeCellRenderer.java # Legacy checkbox tree renderer
|       `-- CollectionTreeNode.java # Tree node wrapper for collections, folders, and requests
`-- utils/
    |-- HttpUtils.java             # URL parsing utilities
    |-- OAuth2RuntimeMapper.java   # Normalizes imported auth to canonical oauth2_* vars
    |-- RequestBuilder.java        # HTTP message construction + OAuth2 + file uploads
    |-- RuntimeVariablesJson.java  # Runtime vars/OAuth2 JSON import-export helper
    |-- ScriptEngine.java          # Nashorn JS execution + Postman/Bruno APIs
    |-- SharedRequestPipeline.java # Shared build/send/script/OAuth pipeline
    `-- UnresolvedVariableAnalyzer.java # Preflight unresolved-variable scanner
```

Additional current models/UI utilities include `RequestEditorPanel`, `RequestEditorAuthSupport`, `RequestEditorStateMapper`, `RequestEditorBodySupport`, `RequestEditorTableSupport`, `ResponsePane`, `BurpLikeTreeCellRenderer`, `CollectionTreeNode`, `RunnerPreviewRow`, `RunnerStopConditions`, `RunnerTimelineRow`, `UnresolvedVariableIssue`, `RunnerPreviewTableModel`, `RunnerTimelineTableModel`, `UnresolvedVariablesDialog`, `SharedRequestPipeline`, `RuntimeVariablesJson`, and `UnresolvedVariableAnalyzer`.


### 3.2 Class Diagram (Simplified)

```
BurpExtender
    └─> UniversalImporter
        ├─> ImporterPanel (UI)
        │   ├─> CollectionRunner
        │   ├─> OAuth2Panel
        │   └─> VariableResolver
        ├─> ParserRegistry
        │   ├─> PostmanParser
        │   ├─> BrunoParser
        │   ├─> OpenApiParser
        │   ├─> InsomniaParser
        │   └─> HarParser
        └─> RequestBuilder
            └─> HttpUtils
```

### 3.3 Design Patterns Used

| Pattern | Application |
|---------|-------------|
| **Strategy** | `CollectionParser` interface with 5 implementations |
| **Registry** | `ParserRegistry` auto-detects format via `canParse()` |
| **Observer** | `CollectionRunner.RunnerListener` for UI updates |
| **Builder** | `RequestBuilder` constructs HTTP messages step-by-step |
| **Factory** | `HttpService.httpService()` and `HttpRequest.httpRequest()` |

---

## 4. Data Flow

### 4.1 Import Flow

```
User selects collection file
    |
    v
ParserRegistry.detectParser(File) -> returns appropriate parser
    |
    v
Parser.parse(File) -> returns ApiCollection (unified model)
    |
    v
VariableResolver loads environment variables
    |
    v
User selects requests + destination (Repeater/Sitemap/Both)
    |
    v
UnresolvedVariableAnalyzer scans checked requests
    |
    v
If missing variables exist: modal offers cancel, continue, or apply runtime values
    |
    v
For each checked request:
    VariableResolver.resolve(request) -> substitutes {{variables}}
    RequestBuilder.buildRequest(request) -> raw HTTP bytes
    |
    v
    If Repeater: api.repeater().sendToRepeater(name, HttpRequest)
    If Intruder: api.intruder().sendToIntruder(HttpRequest)
    If Sitemap: api.http().sendRequest(HttpRequest) -> add response to Site map
    |
    v
ImportResult returned with success/failure counts
```

### 4.2 Runner Flow

```
User selects requests + configures runner settings
    |
    v
Build run preview -> ordered list, URL preview, unresolved vars, auth status
    |
    v
Optional unresolved-variable modal before live execution
    |
    v
CollectionRunner.runCollection(collection, requests, initialVars)
    |
    v
For each request in sequence:
    1. Honor pause/resume/step gate
    2. Evaluate missing-variable stop condition
    3. Execute through SharedRequestPipeline
    4. Retry failed attempts according to configured retries
    5. Measure response time and shape RunnerResult
    6. Execute post-response scripts -> extract variables
    7. Store extracted variables for the same collection only
    8. Evaluate assertions/status/failure stop conditions
    9. Emit result row and timeline row
    10. Delay (if configured and not last request)
    |
    v
RunnerResult added to results table
fireOnRequestComplete() -> UI update via SwingUtilities.invokeLater()
    |
    v
All complete -> fireOnComplete() -> final statistics
```

### 4.3 OAuth2 Flow

```
User configures OAuth2 in UI tab
    |
    v
OAuth2Manager.acquireToken(config)
    |
    v
Based on grant type:
    Client Credentials -> POST token_url with client_id + client_secret
    Password -> POST with username + password + client credentials
    Auth Code + PKCE:
        1. Generate code_verifier + code_challenge (SHA256)
        2. Build auth URL with PKCE params
        3. Start ServerSocket on 127.0.0.1:9876
        4. Open browser via Desktop.browse()
        5. User authenticates, redirected to localhost callback
        6. Extract code, exchange for tokens
    Refresh Token -> POST with refresh_token
    |
    v
TokenStore.store(key, TokenEntry) -> live in-memory cache (static map, survives extension reloads in same JVM)
    |
    v
Token auto-injected as {{oauth2_access_token}} in VariableResolver
```

If the active collection/runtime state contains OAuth2 values, those values can also be mirrored into workspace snapshots saved through Burp project extension data.

---

## 5. Collection Format Support

### 5.1 Postman (v2.0 / v2.1)

**Detection:** JSON object with `info.schema` or `info._postman_id`

**Parsed Elements:**
- `info.name` -> collection name
- `info.description` -> collection description
- `variable[]` -> collection-level variables (`key`, `value`, `type`)
- `item[]` -> requests (recursive folder support)
  - `item.request.method` -> HTTP method
  - `item.request.url` (string or object with `raw`) -> URL
  - `item.request.header[]` -> headers (`key`, `value`, `disabled`)
  - `item.request.body` -> body (raw, urlencoded, formdata, graphql, file)
  - `item.request.auth` -> authentication
  - `item.event[]` -> scripts (`listen: prerequest` or `test`)

**Auth Parsing:**
Postman auth uses array-of-objects or object format:
```json
{
  "type": "bearer",
  "bearer": [{"key": "token", "value": "abc", "type": "string"}]
}
```
Parser normalizes both formats into `ApiRequest.Auth.properties` map.

**Body Modes:**
| Mode | Parsed To | Content-Type |
|------|-----------|--------------|
| `raw` | `body.raw` + `body.contentType` | From `options.raw.language` |
| `urlencoded` | `body.urlencoded[]` | `application/x-www-form-urlencoded` |
| `formdata` | `body.formdata[]` | `multipart/form-data` |
| `graphql` | `body.graphql.query` + `body.graphql.variables` | `application/json` |
| `file` | Not fully supported (placeholder) | - |

### 5.2 Bruno

**Detection:** Directory containing `.bru` files, or single `.bru` file

**File Format:** Plain text with block-based syntax:
```
meta {
  name: Get User
  type: http
  seq: 1
}

get {
  url: {{base_url}}/api/users
  body: none
  auth: none
}

headers {
  content-type: application/json
}

vars {
  base_url: http://localhost:8080
}

script:pre-request {
  bru.setVar("timestamp", new Date().toISOString());
}

assert {
  res.status: eq 200
}
```

**Parsing Strategy:**
- Regex-based extraction (not a full parser)
- Each block matched independently
- `meta` block: name, type, seq -> `ApiRequest.name`, `ApiRequest.sequenceOrder`
- Method line: `get {` -> `ApiRequest.method = "GET"`
- `url:` line inside block -> `ApiRequest.url`
- `headers` block: line-by-line `key: value` parsing
- `body` block: supports `{content}` and `:none`/`:json` formats
- `vars` block: request-level variables
- `auth` block: currently supports basic auth only
- `script:pre-request` / `script:post-response`: stored as JS scripts
- `assert` block: stored as post-response script

**Limitations:**
- Regex parsing may fail on complex nested braces in body content
- File upload syntax not yet parsed
- `bru.req.setHeader()` and other advanced APIs not supported

### 5.3 OpenAPI / Swagger

**Detection:** JSON/YAML with `openapi` or `swagger` key

**Parsed Elements:**
- `info.title` -> collection name
- `info.description` -> description
- `servers[]` or `host` + `basePath` -> base URL
- `paths` -> requests (one per HTTP method per path)

**Request Generation:**
- Method extracted from path key (`get`, `post`, etc.)
- URL = `baseUrl + path`
- Headers extracted from `parameters` with `in: header`
- Body generated from `requestBody.content` schema:
  - `application/json` -> generates example JSON from schema
  - `application/x-www-form-urlencoded` -> form fields
  - `multipart/form-data` -> form fields

**Schema Example Generation:**
Recursive traversal with type-aware defaults:
| Type | Format | Example |
|------|--------|---------|
| string | email | `user@example.com` |
| string | uuid | `550e8400-e29b-41d4-a716-446655440000` |
| string | date | `2024-01-01` |
| string | date-time | `2024-01-01T00:00:00Z` |
| string | uri/url | `https://example.com` |
| string | password | `SecureP@ss123` |
| integer/number | - | `1` or `1.0` (respects minimum) |
| boolean | - | `true` |
| array | - | `[singleItemFromItemsSchema]` |
| object | - | `{requiredProps + first 5 optional}` |

**Cycle Protection:**
- `visited` Set tracks `$ref` references
- Max depth: 10 levels
- Circular refs return `{"$ref": "... (recursive)"}`

### 5.4 Insomnia (v4)

**Detection:** JSON with `__type` = export or `resources[]` with `_type` = request

**Parsed Elements:**
- `__export_format` or `resources[]._type` -> format validation
- `resources[]` array iterated for requests
- `_type: request_group` -> folder names for path hierarchy
- `_type: request` -> actual requests
  - `_id`, `name`, `method`, `url`
  - `headers[]` (`name`, `value`, `disabled`)
  - `body` (`mimeType`, `text`, `params[]`)
  - `authentication` (`type`, properties)

### 5.5 HAR

**Detection:** `.har` file with `log.entries` array

**Parsed Elements:**
- Each `entry.request` becomes one `ApiRequest`
- `request.method`, `request.url`
- `request.headers[]` (`name`, `value`)
- `request.postData` -> body (`text`, `mimeType`, `params[]`)

---

## 6. Variable Resolution Engine

### 6.1 Syntax

Variables use double-brace syntax: `{{variable_name}}`

**Default Values:** `{{variable|default_value}}`
- If `variable` is not defined, uses `default_value`
- Example: `{{base_url|http://localhost:8080}}`
- Defaulted placeholders are treated as resolved by preview, preflight, and stop-on-missing-variable checks.

**Nested Variables:** Variables can reference other variables
- `{{api_url}}` = `{{base_url}}/api` -> resolves `base_url` first, then constructs `api_url`
- Max 10 resolution iterations to prevent infinite loops

### 6.2 Resolution Order (Precedence)

Unified precedence across Workbench Send, Import, and Collection Runner (highest to lowest):

1. **Request-level variables**
   - Bruno: request `vars` block
   - Postman: request-level variables

2. **Extracted / runtime variables**
   - Variables extracted from post-response scripts
   - Variables tab runtime overrides
   - Runner-extracted variables from previous responses

3. **Scoped OAuth2 runtime vars**
   - `oauth2_access_token` and other canonical `oauth2_*` vars from the collection's `runtimeOAuth2`

4. **Collection-level variables**
   - Postman: `collection.variable[]`
   - Bruno: collection `vars` block

5. **Collection environment**
   - Loaded from Postman environment JSON (`values[].key`, `values[].value`)
   - Or bound environment file

6. **Default values** (lowest priority)
   - `{{var|default}}` uses `default` if `var` undefined

Before Workbench send, import, and runner start, `UnresolvedVariableAnalyzer` scans URL, headers, bodies, GraphQL payloads, form fields, and auth properties. Missing variables are shown in a modal with quick-entry fields; applied values are stored in the selected collection's runtime variables.

### Effective Headers and Operator Suppressions

`RequestBuilder` synthesizes headers in four layers:

1. **Compatibility defaults** — `Accept`, `User-Agent`, `Cache-Control`
2. **Explicit request headers** — from the request definition or editor
3. **Auth headers** — `Authorization`, `Cookie`, or custom API-key headers (uses `putDefault` so explicit headers win)
4. **Computed headers** — `Host` (from parsed URL), `Content-Type` (from body mode), `Content-Length` (recomputed from body bytes)

The **Workbench Resolved tab** shows the effective header set after all layers are applied, plus a separate list of disabled (suppressed) headers.

**Operator suppressions**: When an operator unchecks a header row in the editor, `RequestBuilder` suppresses any synthesized header with the same name. For example, unchecking an `Accept` row disables both the explicit value and the compatibility default. The suppression is persisted as a disabled header in the request.

Runtime variables and OAuth2 runtime values can be exported/imported as JSON from the Variables tab. Import supports merge or replace, which makes repeat testing easier without persisting token data automatically.

### 6.3 Implementation

```java
public class VariableResolver {
    private final Map<String, String> variables = new HashMap<>();
    private static final Pattern VARIABLE_PATTERN = 
        Pattern.compile("\{\{([^}|]+)(?:\|([^}]+))?\}\}");

    public String resolve(String input) {
        // Iteratively substitute up to 10 times
        // Handles nested variables and default values
    }
}
```

### 6.4 Scope Lifecycle

```
Import/Runner Start
    |
    v
Load environment file -> add to resolver
    |
    v
Load collection variables -> add to resolver
    |
    v
For each request:
    Load request variables -> add to resolver
    Resolve all {{variables}} in URL, headers, body
    Send request
    Extract variables from response -> update collection runtime vars
    |
    v
Next request in the same collection uses updated runtime vars
```

---

## 7. Authentication & OAuth2

### 7.1 Static Auth (From Collections)

**Bearer Token**
```java
headers.add("Authorization: Bearer " + token);
```
- Postman: `auth.bearer[].key=token`
- Bruno: `authorization: Bearer {{token}}` header
- OpenAPI: auto-detected from `security` section

**Basic Auth**
```java
String credentials = username + ":" + password;
String encoded = Base64.getEncoder().encodeToString(credentials.getBytes());
headers.add("Authorization: Basic " + encoded);
```
- Postman: `auth.basic[].key=username/password`
- Bruno: `auth { basic { username: x password: y } }`
- Insomnia: `authentication.type = "basic"`

**API Key**
```java
headers.add(keyName + ": " + keyValue);
```
- Postman: `auth.apikey[].key=key/value`
- Insomnia: `authentication.type = "apikey"`

**OAuth2 (Static Token)**
```java
headers.add("Authorization: Bearer " + accessToken);
```
- Postman: `auth.oauth2[].key=accessToken`
- If token is `null`, empty, or contains `{{`, triggers auto-acquire

### 7.2 Dynamic OAuth2 (Extension-Managed)

**Configuration Variables**
All OAuth2 settings use `oauth2_` prefix:
```
oauth2_grant=client_credentials
oauth2_token_url=https://auth.example.com/oauth/token
oauth2_auth_url=https://auth.example.com/authorize
oauth2_client_id=client_id
oauth2_client_secret=client_secret
oauth2_username=user
oauth2_password=pass
oauth2_scope=api:read api:write
oauth2_use_pkce=true
```

**Auto-Acquire Trigger**
When `RequestBuilder` encounters `auth.type = "oauth2"`:
1. Check if `oauth2_access_token` exists and is valid
2. If missing/unresolved, call `OAuth2Manager.getValidToken(config)`
3. If token near expiry, auto-refresh via refresh token
4. Inject `Authorization: Bearer <token>` header

**Token Storage**
```java
public class TokenStore {
    private static final Map<String, TokenEntry> store = new ConcurrentHashMap<>();

    public static class TokenEntry {
        String accessToken;
        String refreshToken;
        long expiresAt;        // epoch millis

        boolean isValid(int bufferSeconds) {
            return accessToken != null && 
                   System.currentTimeMillis() + (bufferSeconds * 1000L) < expiresAt;
        }
    }
}
```
- **Live cache is in-memory** - `TokenStore` uses a `static ConcurrentHashMap`, so live tokens survive extension reloads within the same Burp JVM session
- **Workspace persistence is separate** - runtime OAuth2 values can also be saved into Burp project extension data when they are present in the captured workspace snapshot
- Key = `grantType|tokenUrl|clientId`
- Buffer: 60 seconds before expiry for proactive refresh

**Authorization Code + PKCE Flow**
1. Generate `code_verifier` (32 random bytes, base64url)
2. Generate `code_challenge` = SHA256(code_verifier), base64url
3. Generate `state` (16 random bytes, base64url)
4. Build auth URL:
   ```
   https://auth.example.com/authorize?
     response_type=code
     &client_id=client_id
     &redirect_uri=<configured oauth2_redirect_uri>
     &state=state_value
     &code_challenge=challenge_value
     &code_challenge_method=S256
     &scope=api:read
   ```
5. Parse `oauth2_redirect_uri`, require an HTTP loopback host (`localhost`, `127.0.0.1`, or `::1`), and bind `ServerSocket` to that host/port/path with a 5-minute timeout
6. Open browser via `Desktop.getDesktop().browse()`
7. User authenticates -> redirected to the configured loopback callback, for example `http://localhost:9876/callback?code=abc&state=xyz`
8. Listener extracts `code`, validates `state`
9. Exchange code for token:
   ```
   POST /token
   grant_type=authorization_code
   &client_id=client_id
   &client_secret=client_secret
   &code=code
   &redirect_uri=<configured oauth2_redirect_uri>
   &code_verifier=verifier
   ```
10. Store token, shut down listener

The default redirect URI remains `http://localhost:9876/callback`.

**Error Handling**
- Non-JSON token response -> descriptive error with status code + preview
- `error` field in JSON response -> `OAuth2 error: <error> - <description>`
- Socket timeout -> `future.completeExceptionally(new Exception("timeout"))`
- Invalid state -> security exception

### 7.3 Auth Inheritance

Postman imports preserve the effective auth on each request while also recording where it came from.
API Workbench also stores editable override metadata so collection, folder, and request auth can be changed from the tree and restored later.

Request metadata:

- `authInherited = true` when the request inherited auth from a folder or collection
- `authExplicitlyDisabled = true` when the request or parent explicitly selected `noauth`
- `authSource` records the source label, such as `request: Get Me`, `folder: Admin`, or `collection: Auth Demo`
- `authOverrideMode` stores the request override mode: `inherit`, `explicit`, or `none`
- `explicitAuth` stores the request's explicit auth override when the mode is not inherit

Collection metadata:

- `collection.auth` stores the collection-level effective auth
- `folderAuthModes` stores folder override modes keyed by normalized folder path
- `folderAuth` stores folder-level explicit auth objects keyed by normalized folder path

Auth resolution follows nearest-parent semantics:

1. Explicit request auth wins.
2. Otherwise the nearest folder auth applies.
3. Otherwise collection auth applies.
4. Explicit `noauth` stops inheritance and keeps the effective auth as none.

Runner preview and Workbench metadata use `authSource` so operators can see whether a request is using request, folder, collection, or no-auth provenance. `RequestBuilder` still consumes the effective `ApiRequest.auth` value only; the extra metadata is for UI, tests, and workspace persistence.

---

## 8. Collection Runner

### 8.1 Execution Model

Both Workbench direct send and Collection Runner use a **shared request execution pipeline** (`SharedRequestPipeline`) to guarantee consistent behavior.

```java
public void runCollection(ApiCollection collection, 
                          List<ApiRequest> selectedRequests,
                          Map<String, String> initialVars) {
    // Single background thread (ExecutorService)
    // Sequential execution - one request at a time
    // UI updates via SwingUtilities.invokeLater()
}
```

### 8.2 Configuration

| Setting | Default | Range | Description |
|---------|---------|-------|-------------|
| Delay | 200ms | 0-5000ms | Pause between requests |
| Retries | UI-configured | 0+ | Retries after the first attempt |
| Stop on Error | false | boolean | Halt execution on transport/build failure |
| Stop on Assertion Failure | false | boolean | Halt after any failed script assertion |
| Stop on Status >= 400 | false | boolean | Halt after HTTP client/server errors |
| Stop When Variable Missing | false | boolean | Halt before sending requests with unresolved variables, excluding `{{var|default}}` placeholders |
| Stop After Failures | 0 | 0+ | Halt after N total failures; 0 disables this stop condition |
| Follow Redirects | true | boolean | Burp HTTP client setting |
| Pause/Resume/Step | n/a | controls | Pause after current request, resume, or run one queued request |

### 8.3 Request Lifecycle

Shared pipeline steps (used by both Workbench Send and Collection Runner):
```
1. Create fresh VariableResolver (no cross-request leakage)
2. Seed resolver in unified precedence order
   - collection.environment -> collection.variables -> runtimeOAuth2 -> runtimeVars -> request.vars
3. Execute pre-request scripts (ScriptEngine, gated by ScriptMode)
4. OAuth2Manager.refreshIfNeeded() (if auth.type = oauth2)
5. RequestBuilder.buildRequest() -> HTTP bytes
   - Skips disabled headers, form-data fields, and URL-encoded fields
   - Disabling `Content-Type` suppresses synthesized body-mode `Content-Type` headers too
   - Applies operator suppressions: disabled explicit headers suppress synthesized defaults, auth, and body-derived headers of the same name
   - Header precedence: explicit request headers > auth headers > compatibility defaults
   - Computed headers (Host, Content-Type, Content-Length) are applied last and cannot be overridden by defaults
6. api.http().sendRequest() -> HttpRequestResponse
7. Execute post-response scripts -> extract variables
8. Apply script/runtime variable delta into collection.runtimeVars
9. Fire collection change listeners -> UI refresh
```

Collection Runner specific (around the shared pipeline):
```
10. Pause/step gate before each request
11. Missing-variable stop condition before send
12. Retry loop with delay-scaled backoff
13. RunnerResult shaping (timing, headers, assertions)
14. Add response to Site map
15. Add to results and timeline tables (via invokeLater)
16. Delay (if not last request)
```

### 8.4 Retry Logic

```java
int maxAttempts = maxRetries + 1;
for (int attempt = 1; attempt <= maxAttempts; attempt++) {
    try {
        // send request
        // logs: Attempt X/Y passed after a retry succeeds
        break; // success
    } catch (Exception e) {
        // logs: Attempt X/Y failed: message
        if (attempt >= maxAttempts) throw e;
        // logs: Retrying in Nms
        Thread.sleep(delayMs * attempt);
    }
}
```

### 8.5 Results Model

```java
public class RunnerResult {
    String requestName;
    String requestId;
    boolean success;
    int statusCode;
    long responseTimeMs;
    int responseSize;
    String errorMessage;
    String responseBodyPreview;  // first 500 chars
    Map<String, String> extractedVariables;
    List<AssertionResult> assertions;
}

public class AssertionResult {
    String name;
    boolean passed;
    String expected;
    String actual;
}

public class RunnerTimelineRow {
    int index;
    String collectionName;
    String requestName;
    int statusCode;
    long timeMs;
    int retries;
    int varsChanged;
    String assertions;
}
```

### 8.6 Thread Safety

| Component | Type | Rationale |
|-----------|------|-----------|
| `results` | `CopyOnWriteArrayList<RunnerResult>` | Background thread writes, EDT reads |
| `extractedVars` | `ConcurrentHashMap<String, String>` | Shared mutable state across requests |
| `cancelled` | `volatile boolean` | Visibility across threads |
| `running` | `volatile boolean` | Visibility across threads |
| UI callbacks | `SwingUtilities.invokeLater()` | All Swing mutations on EDT |

---

## 9. Script Engine

### 9.0 Script Mode Gating

At startup, the extension probes the JVM to determine script execution capability:

| Mode | Condition | Pre-request | Post-response |
|------|-----------|-------------|---------------|
| **FULL_JS** | Java >= 17 and Nashorn eval succeeds | Full Nashorn execution | Full Nashorn + assertions |
| **LIMITED** | Java >= 17 but Nashorn probe failed | Skipped | Regex fallback extraction only |
| **DISABLED** | Java < 17 | Skipped | Skipped |

The detected mode is logged at startup and displayed in the UI status bar.

### 9.1 Nashorn Integration

**Engine Discovery (3-tier fallback):**
```java
ScriptEngine engine = engineManager.getEngineByName("nashorn");
if (engine == null) engine = engineManager.getEngineByName("javascript");
if (engine == null) {
    // Direct factory via reflection
    Class<?> factoryClass = Class.forName(
        "org.openjdk.nashorn.api.scripting.NashornScriptEngineFactory");
    Object factory = factoryClass.getDeclaredConstructor().newInstance();
    engine = (ScriptEngine) factoryClass.getMethod("getScriptEngine").invoke(factory);
}
```

**Dependencies:**
- `org.openjdk.nashorn:nashorn-core:15.4`
- `org.ow2.asm:asm:9.6` (and commons, tree, util)

### 9.2 Postman API Bindings

```javascript
// Variable management
pm.test("name", function () { /* assertions */ });
pm.environment.set("key", value);
pm.environment.get("key");
pm.environment.has("key");
pm.environment.unset("key");
pm.collectionVariables.set("key", value);
pm.collectionVariables.get("key");
pm.collectionVariables.unset("key");

// Assertions
pm.expect(pm.response.code()).to.have.status(200);
pm.expect(pm.response).to.have.header("X-Frame-Options");
pm.expect(jsonData).to.have.property("token");
pm.expect(value).to.equal(expected);
pm.expect(value).to.eql(expected);

// Response access
pm.response.code();      // status code
pm.response.text();        // body string
pm.response.json();        // parsed JSON object
pm.response.hasHeader("name");
```

### 9.3 Bruno API Bindings

```javascript
// Variable management
bru.setVar("key", value);
bru.getVar("key");
bru.setEnvVar("key", value);
bru.getEnvVar("key");

// Response access
res.getBody();           // parsed JSON
res.getStatus();         // status code
res.getBodyAsString();   // raw body string
```

### 9.4 Comment-Based Extraction

Works in any format (Postman, Bruno, OpenAPI, etc.):
```javascript
// extract: auth_token = $.data.token
// extract: user_id = $.users[0].id
// extract: count = $.total
```

Parsed by regex: `//\s*extract:\s*(\w+)\s*=\s*(.+?)$`

### 9.5 Fallback Mode

When Nashorn is unavailable:
- Pre-request scripts: skipped (no regex fallback)
- Post-response scripts: regex extracts `pm.environment.set()` and `bru.setVar()` patterns
- Assertions: default status < 400 only

---

## 10. Live Sync / UI Mirroring

All per-collection runtime mutations (variables, OAuth2, extracted vars) flow through `ApiCollection` helper methods that fire change listeners. This enables live UI refresh across tabs:

- **Variables tab** automatically refreshes when a script (Workbench or Runner) extracts new variables into `collection.runtimeVars`.
- **OAuth2 tab** refreshes when a token is acquired during pipeline execution and persisted into `collection.runtimeOAuth2`.
- **Workbench** uses the same shared pipeline as the Runner, so variable extraction behavior is identical.
- **Unresolved-variable preflight** can populate collection runtime vars before Workbench send, import, or runner execution.
- **Runtime JSON import/export** uses the same collection helper methods so merge/replace operations refresh dependent UI.

### 10.1 Workspace State Persistence

API Workbench saves its full workspace state through Burp project extension data.

- On a disk-backed Burp project, the workspace is stored with the project and restored the next time that project is opened.
- On a temporary Burp project, the workspace stays in memory for the current Burp session only.
- The saved workspace includes loaded collections, request tree checks/selections, runtime variables, and OAuth2 runtime/config values, including access tokens, refresh tokens, client secrets, passwords, and secret-like runtime keys.
- Workspace saves are coalesced through a debounced final save path, and unchanged serialized snapshots are skipped.
- Treat Burp project files as sensitive because API Workbench may store secrets there.

### 10.2 Autosave

Variables and OAuth2 edits autosave to the selected collection using debounced UI listeners.

- Normal typing and table edits schedule autosave.
- Programmatic refreshes suppress autosave so UI rebuilds do not overwrite runtime state.
- **Save Now** performs an explicit immediate write.
- **Clear** only clears the editor UI and does not write an empty runtime map until the operator explicitly saves.

### 10.3 Mutation Helpers

All write paths must use these methods to guarantee listener coverage:
- `putRuntimeVar(String, String)`
- `putAllRuntimeVars(Map)`
- `putRuntimeOAuth2(String, String)`
- `putAllRuntimeOAuth2(Map)`
- `replaceRuntimeVars(Map)`
- `replaceRuntimeOAuth2(Map)`

Direct map mutation (`col.runtimeVars.put(...)`) bypasses listeners and is prohibited in new code.

---

## 11. Security Considerations

### 11.1 Token Storage
- **Live `TokenStore` cache** uses `ConcurrentHashMap` and remains in-memory only.
- **Workspace snapshots are persistent**: API Workbench stores runtime OAuth2 and runtime variable state in Burp project extension data.
- **Manual Runtime JSON export** can write runtime OAuth2 values, including access/refresh tokens, to a user-selected file.
- **No encryption at rest** in the workspace layer; Burp project files should be treated as sensitive.
- Cleared on extension unload or `OAuth2Manager.clearTokens()`.

### 11.2 Client Secrets
- Passed as variables (`{{client_secret}}`) - never hardcoded
- `JPasswordField` used in UI for client secret and password fields
- Not logged to Burp output

### 11.3 Path Traversal Prevention
Multipart file reading is only attempted when a form field is explicitly marked as a file upload and includes file metadata. Plain values that look like local paths are sent as text. File upload paths are constrained to the current working directory or the user's home directory.

### 11.4 OAuth2 Security
- **PKCE** enforced for Authorization Code flow (S256 method)
- **State parameter** validated to prevent CSRF
- **Localhost only** (`127.0.0.1`) - no remote callback exposure
- **Loopback-only callback** using the configured `oauth2_redirect_uri`, with `http://localhost:9876/callback` as the default
- **Auto-shutdown** of listener after callback or timeout

### 11.5 Script Execution
- Nashorn runs with **no sandbox** - scripts can access any Java class via `Java.type()`
- `console.log()` routed to Burp output (not system console)
- No `eval()` of user input outside script contexts
- **Warning**: Only run trusted collection scripts

---

## 12. Known Limitations & Code-Level Behaviors

### 12.1 What Is Actually Implemented vs Documented

| Claimed Feature | Actual Implementation | Status |
|-----------------|----------------------|--------|
| Path traversal prevention for file uploads | File reading requires explicit upload metadata and restricts paths to cwd/home | Implemented |
| `JPasswordField` for OAuth2 secrets | Uses `JPasswordField` (masked input) | Correct |
| Nashorn sandboxed execution | **No sandbox** - `Java.type()` gives full JVM access | Security risk |
| Token storage "never persisted" | Live `ConcurrentHashMap` cache is in-memory only, but workspace snapshots can mirror OAuth2/runtime secrets into Burp project data | Accurate with caveat |
| File upload MIME detection | `Files.probeContentType()` is called for explicit file uploads | Implemented |
| Automated test suite | JUnit 5 + Mockito + AssertJ across parsers, request building, runner behavior, variables, and runtime JSON | Present |

### 12.2 Architectural Limitations

- **No script timeout**: Nashorn scripts run without timeout. An infinite loop will hang the Collection Runner thread permanently.
- **Single-threaded runner**: Only one request executes at a time. No parallel execution mode.
- **Static `TokenStore`**: Uses a `static ConcurrentHashMap`. Tokens are not isolated between Burp projects and survive extension reloads.
- **No DI/IoC**: All dependencies are manually wired in constructors, making unit testing difficult.
- **Test suite**: JUnit 5 Jupiter, Mockito, AssertJ in `pom.xml`. `mvn test` covers parsers, request building, shared pipeline behavior, runner controls, variables, and runtime JSON.
- **Loopback callback requirement**: Authorization Code callback must use an HTTP loopback redirect URI. If the configured loopback port is occupied, the flow fails.
- **Project-scoped state**: Montoya extension data is scoped to the Burp project/session. Disk-backed projects carry the saved workspace into the next session; temporary projects do not.

### 12.3 Request Editor Empty-State Behavior

- `RequestEditorPanel` seeds a single blank starter row into empty **Params** and shared body form tables so operators can type immediately without pressing `+`.
- **Headers** table shows effective headers (Accept, User-Agent, Cache-Control, Host, auth, body-derived Content-Type) synthesized from `RequestBuilder`, plus a trailing blank row for quick entry.
- The same starter-row behavior is restored after loading a request with no entries, clearing the editor, deleting the last remaining row, or switching into `form-data` / `x-www-form-urlencoded` body modes.
- Blank starter rows and unmodified synthesized headers are UI affordances only. Request building skips blank-key rows and unmodified synthesized rows, so they do not serialize into explicit headers unless edited or disabled.

### 12.4 Parser Limitations

- **Parser encoding**: All parsers use explicit UTF-8 (`InputStreamReader` with `StandardCharsets.UTF_8`). Non-ASCII characters are preserved correctly.
- **Bruno parser**: Uses block extraction for known blocks but is not a full Bruno grammar parser. New Bruno syntax may still need parser updates.
- **OpenAPI parser**: Generates examples for all schema types, resolves local `#/components/schemas/...` and `#/definitions/...` refs for example bodies, and casts header examples with `String.valueOf()`, which may produce `null` or unhelpful strings for complex objects.
- **Insomnia parser**: Only supports v4 exports. v3 or earlier are not detected.

---

## 13. Error Handling

### 13.1 Import Errors

| Error | Cause | User Action |
|-------|-------|-------------|
| "Unknown collection format" | File doesn't match any parser | Check file extension and structure |
| "Invalid Postman collection" | Missing `info` object | Verify Postman export version |
| "No checked requests" | Import attempted with 0 checkboxes | Check requests in the Workbench Request Tree checkboxes |
| "DNS failed" | Unknown host | Check network/VPN/proxy |
| "Connection refused" | Service down or wrong port | Verify target is running |
| "Connection timeout" | Target unresponsive | Check firewall or increase timeout |

### 13.2 Runner Errors

| Error | Cause | Behavior |
|-------|-------|----------|
| Request disabled | `req.disabled = true` | Skipped with log entry |
| Null URL | Missing or empty URL | `IllegalArgumentException` before send |
| OAuth2 auto-acquire failed | Invalid config or network issue | Logged, request proceeds without token (will 401) |
| Script error | Nashorn exception | Logged, regex fallback attempted |
| Non-JSON response | HTML error page, WAF | Preview logged, assertion fails |

### 13.3 OAuth2 Errors

| Error | Cause | Resolution |
|-------|-------|------------|
| "Invalid OAuth2 configuration" | Missing required fields | Check client_id, token_url |
| "Non-JSON response" | WAF, proxy, or wrong endpoint | Verify token_url is correct |
| "OAuth2 error: invalid_client" | Wrong client credentials | Check client_id and client_secret |
| "State mismatch" | CSRF or browser issue | Retry authorization flow |
| "Browser not supported" | Headless environment | Use Client Credentials or Password grant |

---

## 14. Performance

### 14.1 Memory Management

| Component | Strategy |
|-----------|----------|
| Response body preview | Truncated to 500 characters |
| OpenAPI example generation | Max 5 optional properties, depth 10 |
| Runner results | CopyOnWriteArrayList (snapshot semantics) |
| Token store | Static `ConcurrentHashMap` - no automatic eviction |
| Script engine | New engine per script execution (no pooling) |

### 14.2 Rate Limiting

- **Import delay**: 0–5000ms between Sitemap requests
- **Runner delay**: 0–5000ms between sequential requests
- **Concurrent requests**: 1 (runner is strictly sequential)
- **Retry backoff**: `delayMs x attempt_number`

### 14.3 Workspace Persistence Write Behavior

- Workspace snapshots are serialized from the EDT-facing UI state through a debounced coordinator.
- Repeated change notifications within the debounce window collapse into one final save.
- If the serialized workspace JSON is unchanged from the last successful save, persistence is skipped.
- This reduces unnecessary disk churn for Burp project files without changing request-editor or runtime behavior.

### 14.4 Scalability Limits

| Metric | Limit | Rationale |
|--------|-------|-----------|
| Collection size | ~10,000 requests | UI table rendering |
| Response body | Preview: 500 chars | Memory conservation |
| OpenAPI depth | 10 levels | Stack overflow prevention |
| Script execution | No timeout | Nashorn limitation |
| OAuth2 listener | 5 minutes | Socket timeout |

---

## 15. Extending the Extension

### 15.1 Adding a New Collection Format

1. Implement `CollectionParser` interface:
```java
public class MyFormatParser implements CollectionParser {
    public boolean canParse(File file) { /* detect format */ }
    public ApiCollection parse(File file) throws Exception { /* convert to unified model */ }
    public String getFormatName() { return "MyFormat"; }
    public String[] getSupportedExtensions() { return new String[]{"ext"}; }
}
```

2. Register in `ParserRegistry`:
```java
public ParserRegistry() {
    parsers.add(new PostmanParser());
    parsers.add(new BrunoParser());
    parsers.add(new OpenApiParser());
    parsers.add(new InsomniaParser());
    parsers.add(new HarParser());
    parsers.add(new MyFormatParser());  // <-- add here
}
```

3. Normalize to `ApiRequest` / `ApiCollection` model

### 15.2 Adding a New OAuth2 Grant Type

1. Create handler class:
```java
public class MyGrantHandler {
    public TokenStore.TokenEntry execute(OAuth2Config config, MontoyaApi api) {
        // implement grant flow
        return ClientCredentialsHandler.executeTokenRequest(config, body, api);
    }
}
```

2. Add to `OAuth2Config.GrantType` enum
3. Add case in `OAuth2Manager.acquireToken()`

### 15.3 Adding Script APIs

Extend `ScriptEngine.PostmanApi` or `ScriptEngine.BrunoApi`:
```java
public class PostmanApi {
    public MyApi my = new MyApi();
    public class MyApi {
        public void doSomething(String arg) { /* ... */ }
    }
}
```

---

## 16. Troubleshooting

### 16.1 Extension Won't Load

**Symptom:** "Extension failed to load" in Burp Extensions tab

**Checks:**
1. Verify Java 17+ in Burp: `Help > Diagnostics`
2. Check JAR is fat JAR (with dependencies): `mvn clean package`
3. Verify Montoya API version compatibility
4. Check Burp output for stack trace

### 16.2 Nashorn Not Available

**Symptom:** "Nashorn engine not available" in logs

**Resolution:**
- Java 17: Works with bundled `nashorn-core` dependency
- Java 15+: Nashorn was **removed from the JDK**. The standalone `nashorn-core-15.4` dependency is bundled in the fat JAR
- Java 21+: Standalone Nashorn may have compatibility issues; regex fallback will be used
- Alternative: Scripts will use regex fallback (limited functionality - variable extraction only, no assertions)

### 16.3 OAuth2 Browser Doesn't Open

**Symptom:** Authorization Code flow hangs at "Opening browser"

**Resolution:**
1. Check if `Desktop.isDesktopSupported()` returns true
2. Manually copy auth URL from log and paste in browser
3. Ensure `localhost:9876` is not blocked by firewall
4. Use Client Credentials or Password grant as alternative

### 16.4 Variables Not Resolving

**Symptom:** `{{variable}}` appears literally in requests

**Checks:**
1. Verify variable is defined in Variables tab or environment file
2. Check spelling (case-sensitive)
3. Ensure no spaces in variable name
4. Try with default: `{{variable|default_value}}`

### 16.5 Import Creates Empty Repeater Tabs

**Symptom:** Repeater tabs created but no request content

**Checks:**
1. Verify collection has valid URLs (not empty)
2. Check if requests are checked in the Request Tree
3. Look for errors in Import Log panel
4. Try Sitemap mode to verify request is buildable

---

## Appendix A: Data Model Reference

### ApiRequest
```java
public class ApiRequest {
    String id;                    // Unique identifier
    String name;                  // Display name
    String path;                  // Folder/path hierarchy
    String method;                // HTTP method (GET, POST, etc.)
    String url;                   // Full URL with {{variables}}
    String description;
    List<Header> headers;         // Request headers
    Body body;                    // Request body
    Auth auth;                    // Authentication config
    List<Variable> variables;     // Request-level variables
    List<Script> preRequestScripts;
    List<Script> postResponseScripts;
    boolean disabled;
    int sequenceOrder;
}
```

### ApiCollection
```java
public class ApiCollection {
    String name;
    String description;
    String format;                // postman, bruno, openapi, insomnia, har
    String version;
    List<ApiRequest> requests;
    List<Variable> variables;     // Collection-level variables
    Map<String, String> environment;
}
```

### ImportResult
```java
public class ImportResult {
    String collectionName;
    int totalRequests;
    int successCount;
    List<String> failedRequests;
    List<FailedRequestInfo> failedRequestDetails;
    String error;                 // Fatal error message
}
```

### RunnerResult
```java
public class RunnerResult {
    String requestName;
    String requestId;
    boolean success;
    int statusCode;
    long responseTimeMs;
    int responseSize;
    String errorMessage;
    String responseBodyPreview;   // First 500 chars
    Map<String, String> extractedVariables;
    List<AssertionResult> assertions;
}
```

---

## Appendix B: Montoya API Method Reference

| Method | Class | Usage |
|--------|-------|-------|
| `api.repeater().sendToRepeater(HttpRequest, String)` | Repeater | Create Repeater tab |
| `api.siteMap().add(HttpRequestResponse)` | SiteMap | Add to Target/Sitemap |
| `api.http().sendRequest(HttpRequest)` | Http | Send HTTP request |
| `HttpService.httpService(String, int, boolean)` | HttpService | Create service (host, port, https) |
| `HttpRequest.httpRequest(HttpService, ByteArray)` | HttpRequest | Create request |
| `ByteArray.byteArray(byte[])` | ByteArray | Wrap raw bytes |
| `api.userInterface().registerSuiteTab(String, Component)` | UserInterface | Register tab |
| `api.logging().logToOutput(String)` | Logging | Log to output |
| `api.logging().logToError(String)` | Logging | Log to errors |

---

*End of Documentation*
