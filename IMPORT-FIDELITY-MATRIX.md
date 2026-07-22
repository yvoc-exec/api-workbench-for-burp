# API Workbench Collection Import and Export Fidelity Matrix

Automated baseline: the repository commit containing this document.
Manual Burp smoke status: PENDING.

## Status definitions

- **Preserved** — represented without semantic loss in the canonical model and supported target.
- **Normalized** — represented equivalently using the target format's canonical shape.
- **Retained as metadata** — kept for native persistence or same-format recovery but not executed.
- **Warned and omitted** — excluded safely with a sanitized warning.
- **Unsupported** — not represented by the current importer or exporter.
- **Manual verification pending** — requires interactive Burp validation outside automated tests.

## Automated lifecycle

Each checked-in fixture is imported through `ParserRegistry`, exported to `API_WORKBENCH_JSON`,
reloaded, exported to its target format, re-imported, and built through `RequestBuilder`.
Native JSON is compared as an exact JSON tree across two exports. Source-import and native-reload
request bytes must match. Target bytes match when the target can represent the canonical state;
documented target normalization is asserted together with its warning.

Exporter coverage: `API_WORKBENCH_JSON`, `POSTMAN_JSON`, `OPENAPI_JSON`, `OPENAPI_YAML`,
`INSOMNIA_JSON`, `BRUNO_ZIP`, and `HAR_JSON`.

## Per-format fidelity matrix

| Format family | Import versions | Export target | Automated fixture | Preserved | Normalized | Unsupported or retained-only | Warning behavior |
| --- | --- | --- | --- | --- | --- | --- | --- |
| API Workbench JSON | Schema 1 and 2 | `API_WORKBENCH_JSON` schema 2 | `NATIVE_V2` | Canonical parameters, metadata, auth, hierarchy, variables, scripts, exact snapshots, disabled state, raw encoding, body metadata | Legacy schema migrates to schema 2 | Runtime-only maps are not persisted | Migration warnings are sanitized |
| Postman v2.0 / v2.1 | v2.0 and v2.1 | `POSTMAN_JSON` v2.1 | `POSTMAN_V20`, `POSTMAN_V21` | Hierarchy, variables, auth, headers, bodies, disabled state, recognized scripts | v2.0 exports as v2.1; header/cookie structures may use Postman-native forms | Tool-specific script APIs remain source text | Non-representable metadata is warned without payload disclosure |
| OpenAPI 3.x | OpenAPI 3.x | `OPENAPI_JSON`, `OPENAPI_YAML` | `OPENAPI_31_JSON`, `OPENAPI_31_YAML` | Parameter locations and serialization metadata; local references; body required state | Preferred media type is selected deterministically; local external references become internal | Responses, callbacks, extensions, and unsupported structures may be retained as metadata | Unsafe or unresolved references produce sanitized warnings |
| Swagger 2.0 | Swagger 2.0 | `OPENAPI_JSON` 3.x | `SWAGGER_20_JSON` | Host/base path, parameters, and recognized form fields | Swagger operations export as OpenAPI 3 | Swagger-only source structures may be retained as metadata | Approximations are reported safely |
| Insomnia | Export format 4/5 shapes | `INSOMNIA_JSON` | `INSOMNIA_V4` | Workspace, folders, base environment, auth, parameters, bodies, recognized scripts | IDs/version markers may regenerate; bare query keys export as explicit empty | Unsupported child environments and unknown script metadata are not executable | Ignored or approximated structures produce sanitized warnings |
| Bruno | Folder and ZIP | `BRUNO_ZIP` | `BRUNO_FOLDER` | Hierarchy, variables, auth, parameters, bodies, file metadata, recognized scripts | Structured query rows precede unmatched raw URL rows; non-representable bare states may become explicit empty | Bruno runtime APIs remain source text and are not translated | Non-representable states warn instead of failing silently |
| HAR 1.2 | HAR 1.2 | `HAR_JSON` | `HAR_12` | Query, headers, cookies, HTTP version, postData, MIME type, fileName, vendor metadata, valid exact snapshots | Edited requests receive a neutral response | Local file paths are not exported; binary body text may be omitted | Unsafe headers and stale responses produce sanitized warnings |

## Preserved structures

Native schema 2 is the highest-fidelity representation. It preserves canonical parameter order,
raw keys and values, bare-versus-empty state, source metadata, body-field metadata, hierarchy,
variables, authentication state, disabled rows, script source, and valid exact snapshots.
Postman, Insomnia, Bruno, OpenAPI, Swagger, and HAR preserve the subset their formats represent.

## Normalized structures

Postman v2.0 becomes v2.1 on export. Swagger 2.0 becomes OpenAPI 3. Local OpenAPI references
become portable internal references. Header and cookie representations may use target-native forms.
Insomnia bare query keys become explicit-empty values with a warning. Bruno reconciles structured
query rows before unmatched raw URL segments and warns for bare states its syntax cannot retain.

## Unsupported or retained-only structures

Tool-specific script APIs are preserved as disabled source where recognized, not translated into
another tool's runtime API. OpenAPI responses, callbacks, extensions, and other unsupported shapes
may be retained as metadata. Unknown script metadata is not executable. HAR local file paths are not
exported, and unsafe header material is retained only as source metadata.

## Warning behavior

Warnings are one-line, deduplicated, bounded, and sanitized. They describe the affected structure
without including credentials, cookies, source payloads, unsafe header contents, or absolute paths.
Unsafe or unresolved OpenAPI references, ignored Insomnia environments, Bruno approximations, HAR
unsafe headers, stale HAR responses, and omitted binary text are reported where applicable.

## Exact transport boundary

Valid exact snapshots preserve captured or authored request bytes inside API Workbench. Burp,
Montoya, proxies, protocol conversion, and target handling may normalize or reject a request.
Automated preservation therefore does not guarantee identical bytes on the network.

## Manual Burp closure remaining

Automated closure does not replace loading the JAR in Burp, checking real Swing rendering and
user-visible dialogs, manually sending requests, Repeater handoff, Runner execution, History
load/replay, screenshots, or PortSwigger submission review. Those checks remain **Manual
verification pending**.
