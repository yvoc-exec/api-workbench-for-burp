# API Workbench for Burp Suite

API Workbench for Burp Suite is a Burp-native API workspace. It helps you import or create collections, edit and send requests, manage environment profiles and OAuth2, run chained workflows, inspect History and Diagnostics, and export evidence or handoff artifacts.

## Extension metadata

Name: API Workbench for Burp

Summary: A Burp-native API workspace for importing, editing, running, scripting, and evidencing API collections from Postman, OpenAPI, Insomnia, Bruno, HAR, and native Workbench formats.

Requirements:

- Java 17+
- Burp Suite with Montoya extension API support
- Load the validated JAR as a Java extension

## Top-level tabs

| Tab | What it does |
| --- | --- |
| Workbench | Load collections, edit one request, send live traffic, or import checked requests to Burp destinations |
| Environment | Manage the active environment profile and its variables, runtime values, and OAuth2 configuration |
| OAuth2 | Acquire and refresh OAuth2 tokens into the selected environment profile |
| Collection Runner | Queue and execute ordered request sequences with retries, stop conditions, pause/resume, and step |
| History | Review prior Workbench and Runner executions, compare them, replay them, and export evidence |
| Diagnostics | Capture passive events and generate or copy sanitized reports |

## What it gives you

| Capability | What users can do | Why it matters |
| --- | --- | --- |
| Import or create collections | Import Postman, OpenAPI/Swagger, Insomnia, Bruno, HAR, or native API Workbench assets, or build collections manually | Start from the format your team already has |
| Workbench | Edit and send one request at a time | Keep focused request work inside Burp |
| Environment | Create, import, duplicate, export, delete, and activate environment profiles | Switch targets, tenants, or roles without rewriting the collection |
| OAuth2 | Bind token acquisition to the active environment profile | Reduce manual token copying and refresh drift |
| Collection Runner | Queue, preview, reorder, step, and run requests | Test chained API workflows repeatably |
| History | Replay, compare, export, copy, or re-open previous executions | Reproduce findings and create evidence |
| Diagnostics | Capture passive events and generate sanitized reports, or copy them | Diagnose runtime issues without sending traffic |
| Export / handoff | Export collections, environments, and execution history | Share work cleanly with teammates or clients |

## Main features

### Import or create API collections

API Workbench is not import-only. You can start from files your team already uses or create collections manually inside Burp.

| Create/edit action | Supported | Notes |
| --- | --- | --- |
| New Collection | Yes | Build a collection manually inside Burp |
| New Folder | Yes | Organize requests by feature, role, or attack path |
| New Request | Yes | Starts as GET with a blank URL |
| Rename | Yes | Rename collections, folders, and requests |
| Duplicate | Yes | Duplicate requests or tree items where supported |
| Delete | Yes | Remove collections, folders, or requests |

Request labels can contain `/` safely. Folder names reject `/` and `\\` to avoid ambiguous tree paths.

Supported collection formats are summarized in [Supported formats](#supported-formats).

### Workbench

Workbench is where you inspect and modify a single API request without leaving Burp. In the Headers table, the Enabled checkbox controls whether each preserved header is sent; unchecked headers remain in the request but are omitted from transport.

| Editor capability | What users can do |
| --- | --- |
| Method / URL / headers / body | Edit requests in place |
| Auth settings | Adjust auth at collection, folder, or request scope |
| Send / Send to Repeater | Test or hand off to Repeater |
| Response display | Inspect results without leaving Burp |
| Blank URL request creation | Create manual GET requests and fill endpoints step by step |

Per-request state stays attached to the request you are working on, which makes it easier to refine a request, test it, and then hand it off to Repeater when you need more control.

#### Exact transport headers

Ordinary authored headers are preserved by default, including duplicate ordinary headers and their relative order where practical. Safe mode still regenerates transport framing such as `Host` and `Content-Length`, removes unsafe transport and hop-by-hop headers, and removes headers named by `Connection`.

- `Exact transport headers — Advanced` is a per-request option in the Workbench Send dropdown.
- The amber `⚠ Exact transport headers` indicator appears only while it is active.
- The first explicit enable in an editor session shows a warning.
- Exact mode persists through workspace save/restore, native API Workbench export/import, History, replay, Runner, and request copying.
- Exact mode is for malformed-request, desynchronization, and request-smuggling testing. It preserves authored transport/framing headers and does not synthesize defaults.
- Arbitrary malformed raw lines and exact whitespace are not supported by the structured editor.
- Burp, Montoya, proxies, HTTP/2 conversion, and targets may still normalize or reject exact requests; it is not guaranteed byte-for-byte wire transport.
- Redirect follow-up requests remain governed by redirect safety rules.

### Environment and OAuth2

Environment profiles are independent workspace objects. The Environment tab selects an environment profile, not a collection.

| Environment action | Supported | Notes |
| --- | --- | --- |
| Import | Yes | Bring in Postman, dotenv, JSON, Insomnia, or Bruno environments |
| New | Yes | Create a profile inside Burp |
| Duplicate | Yes | Quickly create role, tenant, or target variants |
| Delete | Yes | Remove unused profiles |
| Set Active | Yes | Make the profile used by Workbench, Runner, Repeater, Intruder, Sitemap, and previews |
| Export | Yes | Share or archive reusable environment profiles |
| Save | Yes | Persist edits to the workspace |

An environment profile contains normal variables plus OAuth2 configuration and output bindings. The OAuth2 tab selects and uses an environment profile; successful token acquisition writes configured token outputs to that profile.

### Scripts and JavaScript runtime

Java 17 or later is required.

| Runtime mode | Meaning | Operator impact |
| --- | --- | --- |
| Full | A supported JavaScript runtime is available | Pre-request, post-response, and test scripts can run |
| Limited | Runtime probing failed | Legacy post-response regex extraction is available; JavaScript script execution is reduced |
| Disabled | Java is below the supported runtime requirement | JavaScript scripting is unavailable |

API Workbench uses a bundled sandboxed JavaScript runtime. The runtime blocks general host-class lookup, direct I/O, and thread creation; scripts only see the binding APIs exposed by API Workbench.

API Workbench supports a compatible JavaScript scripting layer for common Postman, Insomnia, Bruno, and Workbench pre-request/post-response workflows, including request mutation, variables, assertions, extraction, and Runner flow control. It is not a byte-for-byte clone of each tool's full sandbox API. See [Script Compatibility Matrix](SCRIPT-COMPATIBILITY-MATRIX.md) for the supported subset and planned validation areas.

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

Shared bindings are dialect-aware and appear as `pm`, `bru / req / res`, `insomnia / request / response`, `awb`, and `console`.

Scripts can read and mutate supported variable scopes, mutate method/URL/headers/body/auth through the request binding, read response status/headers/text/parsed JSON/timing, and emit logs, warnings, errors, assertions, extractions, and variable-mutation records.

Runner-oriented control such as skip, stop, next-request, and dependent-request flows belongs to the Runner. Workbench single Send is not a collection-control engine. Do not assume generic `sendRequest` compatibility helpers are available for arbitrary network helper behavior.

Scripts are bounded by the runtime timeout and cancellation safeguards, but they can still mutate requests and runtime state, so only trusted scripts should be run.

Compatibility note:

- Common scripting workflows are supported.
- Unsupported or partially supported sandbox APIs should fail closed, warn clearly, or be preserved without pretending to execute.
- Exact behavior parity with Postman, Insomnia, and Bruno should be tracked through a compatibility matrix and regression fixtures.
- Operators should validate imported third-party scripts before trusting them in security testing.

### Collection Runner

Use the runner for ordered, repeatable API flows.

| Runner capability | What users can do |
| --- | --- |
| Queue checked requests | Build a run list |
| Preview before execution | Confirm order and scope |
| Reorder queue | Test paths in the right sequence |
| Remove selected / clear queue | Adapt quickly when plans change |
| Sequential runs | Execute login -> create -> update -> delete flows |
| Capture results | Track success, failure, status, duration, skips, debug, and errors |
| Flow-control handling | Represent skip and stop outcomes explicitly |

The current presentation is a Runner Queue on the left, one consolidated Runner Execution Table, and a shared detail viewer on the right. Selecting queue or execution entries updates the detail view. Delay, retries, stop conditions, redirects, pause, resume, step, cancel, and raw-request debug behavior remain available. Workbench Send has its own Follow redirects toggle in the Send dropdown, and Runner has a separate Follow redirects setting. Workbench Send supports redirect following with a Redirect Security Policy to control cross-origin credential and sensitive-header handling. Redirect following defaults to 10 hops and is configurable from 1 to 20. Pre-request scripts run once per logical request, post-response scripts run once against the final successful response, and stop-on-status evaluates only that final response.

### History

History keeps a Burp-native record of Workbench and Runner execution activity, including scripts and variable changes.

| Recorded? | Sources |
| --- | --- |
| Captured | Workbench Send, Collection Runner attempts, retry attempts, script output, and variable mutations |
| Not captured | Burp Proxy traffic, Repeater sends, Import-to-Sitemap live sends, manual draft edits, Scanner, Collaborator, or OAST traffic |

The top-level tab label is `History`. Detail tabs are `Request`, `Response`, `Metadata`, `Variables / Environment`, `Script Output`, and `Assertions / Extractions`.

Preserved actions:

- replay with current environment
- replay original snapshot
- compare
- send to Repeater
- copy as cURL
- JSON, CSV, and HAR export
- clear
- Clear Unpinned

The Evidence tab stores analyst metadata for a History entry: pinned status, tags, and analyst notes. Workbench and Runner detail viewers edit the same HistoryStore metadata once a result is captured in History. Pin important entries before using Clear Unpinned so noisy unpinned records can be removed while keep-worthy evidence remains.

History may contain raw requests, responses, authorization material, tokens, cookies, and sensitive payloads. Review before sharing.

Replay uses the original snapshot when possible and can fall back to a `History Replays` collection when the original request no longer exists. History replay can use recorded redirect behavior, always follow, or never follow redirects. Redirect hops are stored as nested evidence under the logical execution. Fragments are stripped before the next request is sent; percent-encoded path and query octets stay encoded exactly; 307/308 preserve method, body, and entity metadata; 301/302 preserve body for non-POST methods; and POST-to-GET or non-HEAD 303 redirects drop body and entity headers. The History store retains at most 1,000 entries and persists with the workspace.

### Diagnostics

Diagnostics is a first-class tab for passive runtime evidence.

| Diagnostics control | What it does |
| --- | --- |
| Capture enablement | Enables or disables passive event capture |
| Debug inclusion | Includes debug events in the snapshot/report |
| Refresh Snapshot | Rebuilds the current report view |
| Clear | Clears the in-memory event store |
| Copy | Copies the current sanitized snapshot |

Capture is disabled by default. It is passive and does not itself send requests. The in-memory event store retains at most 1,000 events. Reports group events by operation and include warning, error, and debug summaries. Sanitization masks common Authorization, Cookie, Set-Cookie, bearer/basic credentials, tokens, secrets, passwords, and API-key patterns, but it is best-effort, so operators must review reports before sharing them. Workspace persistence stores the diagnostics-capture-enabled setting.

### Export and handoff

Move cleaned-up work out of Burp when you need to share or archive it.

| Export area | Formats | Why it matters |
| --- | --- | --- |
| Collections | API Workbench JSON, Postman Collection v2.1 JSON, OpenAPI 3.0 JSON, OpenAPI 3.0 YAML, Insomnia JSON, Bruno ZIP, HAR 1.2 JSON | Native collection export is the most faithful representation of authored structure, auth, variables, folder metadata, requests, and native script blocks. Optional active-environment resolution can materialize values, but `runtimeVars` and `runtimeOAuth2` are not auto-serialized. |
| Environments | API Workbench Environment JSON, Postman Environment JSON, dotenv `.env`, Generic flat JSON, Insomnia Environment JSON, Bruno environment `.bru` | Reuse the same targets, tenants, roles, and tokens elsewhere; the export preserves the selected profile's variables, OAuth2 configuration, and output bindings |
| History | HAR, Native History JSON, CSV summary | Export evidence or produce lightweight reports |

External export formats are lossy where their schemas cannot represent all Workbench metadata. Cross-format export preserves scripts and maps script phase where possible. API Workbench does not currently guarantee automatic translation of tool-specific scripting APIs between Postman, Insomnia, and Bruno.

### Ease-of-use features for daily testing

The small workflow details add up when you are testing APIs all day.

| Ease-of-use feature | Why it matters |
| --- | --- |
| Drag/drop imports | Reduce setup overhead |
| Tree organization drag/drop | Keep structure tidy |
| Runner queue reorder | Adjust runs quickly |
| Active environment switching | Move between targets fast |
| Unresolved variable prompts | Catch missing values earlier |
| Copy as cURL | Share with others or move to another tool |
| Send to Repeater | Escalate interesting cases |
| Clear/remove queue actions | Clean up as plans change |

These features reduce repetitive setup work and keep testing moving inside Burp.

## Supported formats

### Collections

| Format | Import | Export | Notes |
| --- | --- | --- | --- |
| API Workbench JSON | Yes | Yes | Native collection format |
| Postman Collection v2.0 / v2.1 JSON | Yes | v2.1 only | Imports v2.0 and v2.1; exports Postman Collection v2.1 JSON |
| OpenAPI / Swagger 2.x / 3.x | Yes | Export as 3.0 JSON or YAML | Structured API definitions |
| Insomnia JSON | Yes | Yes | Includes recognized pre/post script shapes |
| Bruno ZIP / folder | Yes | Yes | Bruno collection packages |
| HAR 1.2 JSON | Yes | Yes | Captured request collections |

For field-level preservation, normalization, retained-only structures, warning behavior, and the automated lifecycle fixtures, see the [Collection Import and Export Fidelity Matrix](IMPORT-FIDELITY-MATRIX.md).

### Environments

| Format | Import | Export | Notes |
| --- | --- | --- | --- |
| API Workbench Environment JSON | Yes | Yes | Native environment format |
| Postman Environment JSON | Yes | Yes | Postman-style variables |
| dotenv `.env` | Yes | Yes | Simple key=value variables |
| Generic flat JSON | Yes | Yes | Flat JSON object |
| Insomnia Environment JSON | Yes | Yes | Insomnia environment exports |
| Bruno environment `.bru` | Yes | Yes | Bruno variable blocks |

### History exports

| Format | Purpose |
| --- | --- |
| HAR | Portable network-style evidence |
| Native History JSON | Full-fidelity API Workbench history |
| CSV summary | Lightweight review and reporting |

## Installation and build

### Build from source

```powershell
mvn clean package
```

The shaded artifact is written to:

```text
target\api-workbench-for-burp-2.0.1-jar-with-dependencies.jar
```

### Validation

The checked-in Wave 6 fixtures exercise Postman, Bruno, Insomnia, OpenAPI, Swagger, HAR, and native API Workbench assets through import, native persistence, export, re-import, and final request construction. Manual Burp interaction remains a separate release gate.

Run the standard validation commands:

```powershell
mvn test
mvn clean package
mvn -Pstatic-analysis verify
```

Push and pull-request runs use normal CI mode. Full manually dispatched validation adds the performance, mutation, and canonical package jobs. Skipped full-only jobs in normal CI are expected.

### Requirements

- Burp Suite Community Edition or Professional.
- Java 17+.
- Maven 3.6+ if you are building from source.

## Architecture and detailed docs

The complete architecture tree, package layout, data flow, model references, validation profiles, and code-level behavior notes are maintained in [Complete Documentation](DOCUMENTATION.md).

For day-to-day use, see:

- [Operator Guide](OPERATOR_GUIDE.md)
- [Environment vs Collection Precedence](ENVIRONMENT-VS-COLLECTION-PRECEDENCE.md)
- [Collection Import and Export Fidelity Matrix](IMPORT-FIDELITY-MATRIX.md)
- [Wave 6 Manual Burp Fidelity Smoke](WAVE6-MANUAL-BURP-SMOKE.md)

## More documentation

- [Operator Guide](OPERATOR_GUIDE.md)
- [Complete Documentation](DOCUMENTATION.md)
- [Environment vs Collection Precedence](ENVIRONMENT-VS-COLLECTION-PRECEDENCE.md)

## Summary

API Workbench brings imported collections, a structured request tree, request editing, runner execution, history, diagnostics, environment management, and drag/drop reorganization into Burp Suite so you can spend less time rebuilding requests and more time testing.
