# Universal API Collection Importer & Runner for Burp Suite

A Burp Suite Professional/Community extension that imports **Postman**, **Bruno**, **OpenAPI/Swagger**, **Insomnia**, and **HAR** collections into Burp Suite Repeater and/or Sitemap — with a built-in **Collection Runner** for sequential API execution, **OAuth2 token management**, and **Nashorn JavaScript script execution**.

---

## Features

### Multi-Format Import
| Format | Extensions | Auto-Detect |
|--------|-----------|-------------|
| Postman | `.json` | ✅ v2.0 / v2.1 |
| Bruno | `.bru` (folder or single file) | ✅ |
| OpenAPI / Swagger | `.json`, `.yaml`, `.yml` | ✅ |
| Insomnia v4 | `.json` | ✅ |
| HAR | `.har` | ✅ |

### Import Destinations
- **Repeater** — creates tabs for manual testing (no live requests)
- **Sitemap** — sends live requests, populates Target/Sitemap with real responses
- **Intruder** — sends raw request to Intruder for payload position configuration
- **Both** — Repeater tabs + live Sitemap entries

### Variable Resolution
- Collection-level variables
- Environment files (Postman environment JSON)
- Request-level variables (Bruno `vars` block)
- Custom manual variables
- Default values: `{{var|default}}`
- Nested variable resolution

### Collection Runner
- Execute selected requests **sequentially** like Postman Collection Runner
- Configurable **delay between requests** (rate limiting)
- **Variable extraction** from responses (JSON path, script patterns)
- **Assertions** (status code, headers, JSON properties)
- **Stop on error** option
- Results table with status, timing, size, assertion pass/fail
- Auto-populates Sitemap with runner responses

### JavaScript Script Engine (Nashorn)
- Executes **pre-request** and **post-response** scripts using Nashorn
- Supports **Postman** script API: `pm.environment.set()`, `pm.expect().to.have.status()`, etc.
- Supports **Bruno** script API: `bru.setVar()`, `res.getBody()`, etc.
- Regex fallback for environments where Nashorn is unavailable
- Variable extraction from JSON responses via script execution

### OAuth2 Token Management
- **Client Credentials** — fully automated, no browser
- **Password (ROPC)** — automated with username/password
- **Authorization Code + PKCE** — opens browser, localhost callback listener
- **Refresh Token** — auto-refresh before expiry
- Token storage in-memory only (never persisted to disk)
- Auto-injects `Authorization: Bearer <token>` into requests

### Real File Uploads
- Multipart form-data reads actual file contents from disk
- Auto-detects file paths in Bruno/Postman file upload fields
- Uses `Files.probeContentType()` for correct MIME types

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
cd universal-api-importer
mvn clean package
```
Load the fat JAR in Burp Suite:
```
Extensions → Add → Select: target/universal-api-importer-2.0.0-jar-with-dependencies.jar
```

### Requirements
- Burp Suite Professional or Community Edition
- Java 11+ (Nashorn standalone bundled for Java 15+)
- Maven 3.6+

---

## Quick Start

### 1. Import a Collection
1. Click **+ Add Collection** and select your collection file (or Bruno folder). Repeat for multiple collections.
2. (Optional) Select an environment JSON file (applies to all collections)
3. Requests from all loaded collections appear in the preview table with a **Source** column
4. Select requests in the table (checkboxes)
5. Choose destination: **Repeater**, **Sitemap**, **Intruder**, or **Both**
6. Click **Import Selected**

### 2. Use the Collection Runner
1. Import/preview a collection first
2. Select requests you want to run
3. Switch to the **Collection Runner** tab
4. Configure delay, stop-on-error, redirect following
5. (Optional) Add environment variables in the **Variables** tab
6. Click **▶ Start Collection Runner**
7. Watch results populate in real-time

### 3. Configure OAuth2 (if needed)
1. Switch to the **OAuth2** tab
2. Select grant type and fill in endpoints + credentials
3. Click **Acquire Token**
4. Token auto-injects into requests with `auth.type = "oauth2"`

### 4. Script Execution
The runner automatically executes pre-request and post-response scripts:

**Postman style:**
```javascript
pm.environment.set("auth_token", jsonData.access_token);
pm.expect(pm.response.code).to.have.status(200);
```

**Bruno style:**
```javascript
bru.setVar("auth_token", res.getBody().access_token);
```

**Comment-based extraction (works in any format):**
```javascript
// extract: auth_token = $.data.token
```

---

## Architecture

```
src/main/java/burp/
├── BurpExtender.java              # Extension entry point
├── UniversalImporter.java         # Core import orchestrator
├── auth/
│   ├── OAuth2Config.java          # OAuth2 configuration model
│   ├── OAuth2Manager.java         # Token lifecycle manager
│   ├── TokenStore.java            # In-memory encrypted token cache
│   ├── ClientCredentialsHandler.java
│   ├── PasswordGrantHandler.java
│   ├── RefreshTokenHandler.java
│   └── AuthorizationCodeHandler.java  # PKCE + localhost callback
├── models/
│   ├── ApiRequest.java            # Unified request model
│   ├── ApiCollection.java         # Unified collection model
│   ├── ImportResult.java          # Import operation result
│   └── RunnerResult.java          # Runner operation result
├── parser/
│   ├── CollectionParser.java      # Parser interface
│   ├── ParserRegistry.java        # Auto-detect registry
│   ├── PostmanParser.java         # Postman v2.0/v2.1
│   ├── BrunoParser.java           # Bruno .bru files + JS normalization
│   ├── OpenApiParser.java         # OpenAPI 2.x/3.x JSON+YAML
│   ├── InsomniaParser.java        # Insomnia v4 JSON
│   ├── HarParser.java             # HAR archives
│   └── VariableResolver.java      # {{variable}} resolution engine
├── runner/
│   └── CollectionRunner.java      # Sequential execution + JS engine
├── ui/
│   ├── ImporterPanel.java         # Main Swing UI (4-tab)
│   ├── OAuth2Panel.java           # OAuth2 configuration UI
│   ├── RequestPreviewTableModel.java
│   └── RunnerResultTableModel.java
└── utils/
    ├── HttpUtils.java             # URL parsing utilities
    ├── RequestBuilder.java        # HTTP message builder + OAuth2 + file uploads
    └── ScriptEngine.java          # Nashorn JS execution + Postman/Bruno APIs
```

---

## Security Notes

- **Tokens**: Stored in-memory only via `TokenStore`. Never written to disk.
- **Client secrets**: Passed as variables (`{{client_secret}}`), never hardcoded.
- **PKCE**: Always used for Authorization Code flow (OAuth 2.1 compliant).
- **Localhost listener**: Binds to `127.0.0.1` only, validates `state` parameter.
- **File uploads**: Reads from local filesystem paths specified in collections.

---

## License

MIT License — based on [nerdygenii/postman-burp-importer](https://github.com/nerdygenii/postman-burp-importer).

---

## Author

**Sachinico De Leon** — Cybersecurity Professional, Philippines

Original Postman-only extension by **Abdulrahman Oyekunle** (@nerdygenii).
