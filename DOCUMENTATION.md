# API Workbench for Burp Suite - Complete Documentation

**Version:** 2.0.0  
**Author:** yvoc-exec yvoc-exec  
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
10. [Security Considerations](#10-security-considerations)
11. [Known Limitations & Code-Level Behaviors](#11-known-limitations--code-level-behaviors)
12. [Error Handling](#12-error-handling)
13. [Performance](#13-performance)
14. [Extending the Extension](#14-extending-the-extension)
15. [Troubleshooting](#15-troubleshooting)

---

## 1. Overview

This Burp Suite extension bridges the gap between API development tools (Postman, Bruno, Insomnia) and security testing workflows. It imports API collections into Burp Suite for manual pentesting while providing a built-in Collection Runner for automated sequential execution with variable extraction.

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
- Import/Runner operates on selected requests across all collections

### 2.3 Import Destinations

**Repeater Mode**
- Creates Repeater tabs for each selected request
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
- **Variable extraction** from JSON responses via scripts or comments
- **Assertions** (status code, header presence, JSON property existence)
- **Auto-retry** with exponential backoff (delayMs x attempt number)
- **Stop on error** option halts execution on first failure
- **Real-time results** table with status, timing, size, assertion pass/fail
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
- **Postman API** compatibility: `pm.environment.set()`, `pm.expect()`
- **Bruno API** compatibility: `bru.setVar()`, `res.getBody()`
- **Regex fallback** when Nashorn is unavailable
- **Comment extraction**: `// extract: key = $.json.path`

---

## 3. Architecture

### 3.1 Package Structure

```
burp/
├── BurpExtender.java              # Extension entry point (Montoya API)
├── UniversalImporter.java         # Core orchestrator
├── auth/                          # OAuth2 token lifecycle
│   ├── OAuth2Config.java          # Configuration model
│   ├── OAuth2Manager.java         # Token acquisition/refresh
│   ├── TokenStore.java            # In-memory encrypted cache
│   ├── ClientCredentialsHandler.java
│   ├── PasswordGrantHandler.java
│   ├── RefreshTokenHandler.java
│   └── AuthorizationCodeHandler.java
├── models/                        # Unified data models
│   ├── ApiRequest.java            # Request (all formats normalize to this)
│   ├── ApiCollection.java         # Collection container
│   ├── ImportResult.java          # Import operation result
│   └── RunnerResult.java          # Runner operation result
├── parser/                        # Collection parsers
│   ├── CollectionParser.java      # Parser interface
│   ├── ParserRegistry.java        # Auto-detect registry
│   ├── PostmanParser.java
│   ├── BrunoParser.java
│   ├── OpenApiParser.java
│   ├── InsomniaParser.java
│   ├── HarParser.java
│   └── VariableResolver.java    # {{variable}} resolution engine
├── runner/                        # Execution engine
│   └── CollectionRunner.java      # Sequential request runner
├── ui/                            # Swing UI
│   ├── ImporterPanel.java         # Main 4-tab panel
│   ├── OAuth2Panel.java           # OAuth2 configuration
│   ├── RequestPreviewTableModel.java
│   └── RunnerResultTableModel.java
└── utils/                         # Utilities
    ├── HttpUtils.java             # URL parsing
    ├── RequestBuilder.java        # HTTP message construction
    └── ScriptEngine.java          # Nashorn JS execution
```

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
For each selected request:
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
CollectionRunner.runCollection(collection, requests, initialVars)
    |
    v
For each request in sequence:
    1. Execute pre-request scripts (ScriptEngine)
    2. VariableResolver.apply(request-level vars + extracted vars)
    3. OAuth2Manager.refreshIfNeeded() (if auth.type = oauth2)
    4. RequestBuilder.buildRequest() -> HTTP message
    5. api.http().sendRequest() -> HttpRequestResponse
    6. Measure response time
    7. Evaluate assertions (ScriptEngine)
    8. Execute post-response scripts -> extract variables
    9. Store extracted variables for next request
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
TokenStore.store(key, TokenEntry) -> in-memory only (static map, survives extension reloads in same JVM)
    |
    v
Token auto-injected as {{oauth2_access_token}} in VariableResolver
```

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

**Nested Variables:** Variables can reference other variables
- `{{api_url}}` = `{{base_url}}/api` -> resolves `base_url` first, then constructs `api_url`
- Max 10 resolution iterations to prevent infinite loops

### 6.2 Resolution Order (Precedence)

1. **Runner extracted variables** (highest priority)
   - Variables extracted from previous response scripts
   - Stored in `CollectionRunner.extractedVars`

2. **OAuth2 token variables**
   - `oauth2_access_token` injected by `OAuth2Manager`

3. **Environment file variables**
   - Loaded from Postman environment JSON (`values[].key`, `values[].value`)
   - Or manual Variables tab entries

4. **Collection-level variables**
   - Postman: `collection.variable[]`
   - Bruno: `vars` block (per-request)

5. **Request-level variables**
   - Bruno: request `vars` block

6. **Default values** (lowest priority)
   - `{{var|default}}` uses `default` if `var` undefined

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
    Extract variables from response -> add to resolver
    |
    v
Next request uses updated resolver (including extracted vars)
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
- **In-memory only** - never persisted to disk, but stored in a `static ConcurrentHashMap` so tokens survive extension reloads within the same Burp JVM session
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
     &redirect_uri=http://localhost:9876/callback
     &state=state_value
     &code_challenge=challenge_value
     &code_challenge_method=S256
     &scope=api:read
   ```
5. Start `ServerSocket` on `127.0.0.1:9876` with 5-minute timeout
6. Open browser via `Desktop.getDesktop().browse()`
7. User authenticates -> redirected to `http://localhost:9876/callback?code=abc&state=xyz`
8. Listener extracts `code`, validates `state`
9. Exchange code for token:
   ```
   POST /token
   grant_type=authorization_code
   &client_id=client_id
   &client_secret=client_secret
   &code=code
   &redirect_uri=http://localhost:9876/callback
   &code_verifier=verifier
   ```
10. Store token, shut down listener

**Error Handling**
- Non-JSON token response -> descriptive error with status code + preview
- `error` field in JSON response -> `OAuth2 error: <error> - <description>`
- Socket timeout -> `future.completeExceptionally(new Exception("timeout"))`
- Invalid state -> security exception

---

## 8. Collection Runner

### 8.1 Execution Model

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
| Delay | 200ms | 0–5000ms | Pause between requests |
| Max Retries | 1 | 1–5 | Retry count on failure |
| Stop on Error | false | boolean | Halt execution on first failure |
| Follow Redirects | true | boolean | Burp HTTP client setting |

### 8.3 Request Lifecycle

```
1. Check cancelled flag
2. Skip if req.disabled
3. Execute pre-request scripts (ScriptEngine)
4. Add request-level variables to resolver
5. Add previously extracted variables to resolver
6. OAuth2Manager.refreshIfNeeded() (if auth.type = oauth2)
7. RequestBuilder.buildRequest() -> HTTP bytes
8. api.http().sendRequest() -> HttpRequestResponse
9. Measure response time
10. Evaluate assertions (ScriptEngine)
11. Execute post-response scripts -> extract variables
12. Store extracted variables for next iteration
13. Add to results table (via invokeLater)
14. Add response to Site map
15. Delay (if not last request)
```

### 8.4 Retry Logic

```java
int attempts = 0;
while (attempts < maxRetries) {
    attempts++;
    try {
        // send request
        break; // success
    } catch (Exception e) {
        if (attempts >= maxRetries) throw e;
        Thread.sleep(delayMs * attempts); // exponential backoff
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
pm.environment.set("key", value);
pm.environment.get("key");
pm.environment.has("key");
pm.environment.unset("key");
pm.collectionVariables.set("key", value);
pm.collectionVariables.get("key");

// Assertions
pm.expect(pm.response.code).to.have.status(200);
pm.expect(pm.response).to.have.header("X-Frame-Options");
pm.expect(jsonData).to.have.property("token");
pm.expect(value).to.equal(expected);

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

## 10. Security Considerations

### 10.1 Token Storage
- **In-memory only** via `ConcurrentHashMap`
- **Never persisted** to disk, Burp project files, or logs
- **No encryption at rest** (not needed for transient memory)
- Cleared on extension unload or `OAuth2Manager.clearTokens()`

### 10.2 Client Secrets
- Passed as variables (`{{client_secret}}`) - never hardcoded
- `JPasswordField` used in UI for client secret and password fields
- Not logged to Burp output

### 10.3 Path Traversal Prevention
> **Not applicable.** File uploads are not fully implemented or tested in the current codebase, so path traversal via collection-defined file paths is not a current concern.

### 10.4 OAuth2 Security
- **PKCE** enforced for Authorization Code flow (S256 method)
- **State parameter** validated to prevent CSRF
- **Localhost only** (`127.0.0.1`) - no remote callback exposure
- **Random high port** (9876) with socket timeout
- **Auto-shutdown** of listener after callback or timeout

### 10.5 Script Execution
- Nashorn runs with **no sandbox** - scripts can access any Java class via `Java.type()`
- `console.log()` routed to Burp output (not system console)
- No `eval()` of user input outside script contexts
- **Warning**: Only run trusted collection scripts

---

## 11. Known Limitations & Code-Level Behaviors

### 11.1 What Is Actually Implemented vs Documented

| Claimed Feature | Actual Implementation | Status |
|-----------------|----------------------|--------|
| Path traversal prevention for file uploads | **Not applicable** - file uploads not fully implemented | N/A |
| `JPasswordField` for OAuth2 secrets | Uses `JPasswordField` (masked input) | ✅ Correct |
| Nashorn sandboxed execution | **No sandbox** - `Java.type()` gives full JVM access | ⚠️ Security risk |
| Token storage "never persisted" | Static `ConcurrentHashMap` - survives extension reloads in same JVM | ⚠️ Misleading |
| File upload MIME detection | `Files.probeContentType()` is called but end-to-end file reading is untested | ⚠️ Partial |
| Automated test suite | JUnit 5 + Mockito + AssertJ; `RequestBuilderTest` (37), `VariableResolverTest` (13) | ✅ Present |

### 11.2 Architectural Limitations

- **No script timeout**: Nashorn scripts run without timeout. An infinite loop will hang the Collection Runner thread permanently.
- **Single-threaded runner**: Only one request executes at a time. No parallel execution mode.
- **Static `TokenStore`**: Uses a `static ConcurrentHashMap`. Tokens are not isolated between Burp projects and survive extension reloads.
- **No DI/IoC**: All dependencies are manually wired in constructors, making unit testing difficult.
- **Test suite**: JUnit 5 Jupiter, Mockito, AssertJ in `pom.xml`. `mvn test` runs 50+ tests covering request building and variable resolution.
- **Hardcoded OAuth2 port**: Authorization Code callback is fixed at `localhost:9876`. If occupied, the flow fails.

### 11.3 Parser Limitations

- **Parser encoding**: All parsers use explicit UTF-8 (`InputStreamReader` with `StandardCharsets.UTF_8`). Non-ASCII characters are preserved correctly.
- **Bruno parser**: Regex-based parsing (not a full parser). Complex nested braces in body content may fail.
- **OpenAPI parser**: Generates examples for all schema types but casts header examples with `String.valueOf()`, which may produce `null` or unhelpful strings for complex objects.
- **Insomnia parser**: Only supports v4 exports. v3 or earlier are not detected.

---

## 12. Error Handling

### 11.1 Import Errors

| Error | Cause | User Action |
|-------|-------|-------------|
| "Unknown collection format" | File doesn't match any parser | Check file extension and structure |
| "Invalid Postman collection" | Missing `info` object | Verify Postman export version |
| "No requests selected" | Import attempted with 0 checkboxes | Select requests in preview table |
| "DNS failed" | Unknown host | Check network/VPN/proxy |
| "Connection refused" | Service down or wrong port | Verify target is running |
| "Connection timeout" | Target unresponsive | Check firewall or increase timeout |

### 11.2 Runner Errors

| Error | Cause | Behavior |
|-------|-------|----------|
| Request disabled | `req.disabled = true` | Skipped with log entry |
| Null URL | Missing or empty URL | `IllegalArgumentException` before send |
| OAuth2 auto-acquire failed | Invalid config or network issue | Logged, request proceeds without token (will 401) |
| Script error | Nashorn exception | Logged, regex fallback attempted |
| Non-JSON response | HTML error page, WAF | Preview logged, assertion fails |

### 11.3 OAuth2 Errors

| Error | Cause | Resolution |
|-------|-------|------------|
| "Invalid OAuth2 configuration" | Missing required fields | Check client_id, token_url |
| "Non-JSON response" | WAF, proxy, or wrong endpoint | Verify token_url is correct |
| "OAuth2 error: invalid_client" | Wrong client credentials | Check client_id and client_secret |
| "State mismatch" | CSRF or browser issue | Retry authorization flow |
| "Browser not supported" | Headless environment | Use Client Credentials or Password grant |

---

## 13. Performance

### 13.1 Memory Management

| Component | Strategy |
|-----------|----------|
| Response body preview | Truncated to 500 characters |
| OpenAPI example generation | Max 5 optional properties, depth 10 |
| Runner results | CopyOnWriteArrayList (snapshot semantics) |
| Token store | Static `ConcurrentHashMap` - no automatic eviction |
| Script engine | New engine per script execution (no pooling) |

### 13.2 Rate Limiting

- **Import delay**: 0–5000ms between Sitemap requests
- **Runner delay**: 0–5000ms between sequential requests
- **Concurrent requests**: 1 (runner is strictly sequential)
- **Retry backoff**: `delayMs x attempt_number`

### 13.3 Scalability Limits

| Metric | Limit | Rationale |
|--------|-------|-----------|
| Collection size | ~10,000 requests | UI table rendering |
| Response body | Preview: 500 chars | Memory conservation |
| OpenAPI depth | 10 levels | Stack overflow prevention |
| Script execution | No timeout | Nashorn limitation |
| OAuth2 listener | 5 minutes | Socket timeout |

---

## 14. Extending the Extension

### 14.1 Adding a New Collection Format

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

### 14.2 Adding a New OAuth2 Grant Type

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

### 14.3 Adding Script APIs

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

## 15. Troubleshooting

### 15.1 Extension Won't Load

**Symptom:** "Extension failed to load" in Burp Extensions tab

**Checks:**
1. Verify Java 17+ in Burp: `Help > Diagnostics`
2. Check JAR is fat JAR (with dependencies): `mvn clean package`
3. Verify Montoya API version compatibility
4. Check Burp output for stack trace

### 15.2 Nashorn Not Available

**Symptom:** "Nashorn engine not available" in logs

**Resolution:**
- Java 17: Works with bundled `nashorn-core` dependency
- Java 15+: Nashorn was **removed from the JDK**. The standalone `nashorn-core-15.4` dependency is bundled in the fat JAR
- Java 21+: Standalone Nashorn may have compatibility issues; regex fallback will be used
- Alternative: Scripts will use regex fallback (limited functionality - variable extraction only, no assertions)

### 15.3 OAuth2 Browser Doesn't Open

**Symptom:** Authorization Code flow hangs at "Opening browser"

**Resolution:**
1. Check if `Desktop.isDesktopSupported()` returns true
2. Manually copy auth URL from log and paste in browser
3. Ensure `localhost:9876` is not blocked by firewall
4. Use Client Credentials or Password grant as alternative

### 15.4 Variables Not Resolving

**Symptom:** `{{variable}}` appears literally in requests

**Checks:**
1. Verify variable is defined in Variables tab or environment file
2. Check spelling (case-sensitive)
3. Ensure no spaces in variable name
4. Try with default: `{{variable|default_value}}`

### 15.5 Import Creates Empty Repeater Tabs

**Symptom:** Repeater tabs created but no request content

**Checks:**
1. Verify collection has valid URLs (not empty)
2. Check if requests are disabled in preview table
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
