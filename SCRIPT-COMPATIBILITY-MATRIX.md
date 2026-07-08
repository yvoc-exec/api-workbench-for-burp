# Script Compatibility Matrix

API Workbench supports a compatible JavaScript scripting layer for common Postman, Insomnia, Bruno, and Workbench pre-request/post-response workflows, including request mutation, variables, assertions, extraction, and Runner flow control. It is not a byte-for-byte clone of each tool's full sandbox API.

This matrix is documentation-only. Compatibility should be expanded through golden scripted fixtures and regression tests, not broad claims.

## 1. Status legend

- **Supported**: Implemented and covered by current behavior or fixtures.
- **Partially supported**: Common subset works; edge cases or helper APIs may be missing.
- **Preserved but not executed**: Imported/exported where feasible, but not run.
- **Not supported**: Recognized as unsupported.
- **Planned**: Candidate for future fixture-backed work.
- **Out of scope**: Not planned for API Workbench's supported subset.

## 2. Postman compatibility

| Area | API / behavior | Status | Notes |
| --- | --- | --- | --- |
| Variables | `pm.environment.get/set/unset` | Supported | Common persisted environment workflows. |
| Variables | `pm.collectionVariables.get/set/unset` | Supported | Collection-scoped compatibility. |
| Variables | `pm.variables.get/set` | Partially supported | Common runtime variable access; edge precedence cases need fixtures. |
| Variables | `pm.globals.get/set/unset` | Partially supported | Supported subset maps to Workbench variable scopes. |
| Variables | request-level variables | Partially supported | Request authored variables and runtime overlays participate in resolution. |
| Variables | variable precedence edge cases | Planned | Track through precedence fixtures. |
| Request mutation | `pm.request.url` mutation | Supported | Common URL mutation path. |
| Request mutation | `pm.request.method` mutation | Supported | Common method mutation path. |
| Request mutation | `pm.request.headers` add/upsert/remove | Supported | Common header mutation path. |
| Request mutation | `pm.request.body` mutation | Supported | Common body mutation path. |
| Request mutation | auth/header mutation | Partially supported | Header/auth effects should be validated per fixture. |
| Response/test API | `pm.response.code` | Supported | Response status access. |
| Response/test API | `pm.response.status` | Partially supported | Common status access; exact phrase behavior needs fixtures. |
| Response/test API | `pm.response.headers` | Supported | Header access subset. |
| Response/test API | `pm.response.text()` | Supported | Text body access. |
| Response/test API | `pm.response.json()` | Supported | Parsed JSON access for common bodies. |
| Response/test API | `pm.test()` | Supported | Assertion recording. |
| Response/test API | `pm.expect()` | Partially supported | Common assertion forms; broad Chai surface not claimed. |
| Response/test API | Chai-style assertions | Partially supported | Fixture-backed subset only. |
| Runner/flow | `postman.setNextRequest` | Supported | Runner flow-control mapping. |
| Runner/flow | skip/stop behavior if mapped | Supported | Runner-specific skip/stop outcomes. |
| Runner/flow | `pm.sendRequest` | Partially supported | Supported dependent/ad-hoc request patterns; arbitrary helper parity not claimed. |
| Runner/flow | async callback behavior | Partially supported | Common callback paths only. |
| Other | cookie jar APIs | Planned | Do not assume execution unless fixture-backed. |
| Other | auth helper APIs | Planned | Preserve or warn when unsupported. |
| Other | module/library behavior | Not supported | No generic package/module loader. |
| Other | sandbox timing behavior | Partially supported | Runtime timeout/cancellation safeguards apply. |

## 3. Insomnia compatibility

| Area | API / behavior | Status | Notes |
| --- | --- | --- | --- |
| Variables/environment | Insomnia environment variable resolution | Partially supported | Common imported environment behavior. |
| Variables/environment | base environment/sub-environment behavior | Planned | Needs golden fixtures before stronger claims. |
| Variables/environment | template/tag-style variable preservation | Partially supported | Preserved where parser/exporter can represent it. |
| Variables/environment | secret/sensitive variable handling | Partially supported | Review imported/exported secrets before sharing. |
| Request scripting | pre-request script import | Partially supported | Recognized field shapes are imported. |
| Request scripting | pre-request execution | Partially supported | Supported subset only. |
| Request scripting | request URL/header/body mutation | Partially supported | Common mutations through exposed bindings. |
| Request scripting | auth/header mutation | Partially supported | Validate per collection. |
| Response/test scripting | post-response/test script import | Partially supported | Recognized field shapes are imported. |
| Response/test scripting | response body/status/header access | Partially supported | Common subset. |
| Response/test scripting | assertions/extractions | Partially supported | Supported subset only. |
| Import/export | Insomnia collection import | Supported | Supported collection schema paths. |
| Import/export | Insomnia environment import | Supported | Supported environment schema paths. |
| Import/export | script preservation during import | Partially supported | Recognized scripts are preserved. |
| Import/export | script preservation during export | Planned | Verify per exported schema. |
| Import/export | unsupported metadata preservation | Planned | Preserve where feasible. |
| Limitations | Insomnia plugin/helper APIs | Not supported | Marked unsupported unless implemented and tested. |
| Limitations | exact Insomnia runtime behavior | Out of scope | Common workflow compatibility only. |

## 4. Bruno compatibility

| Area | API / behavior | Status | Notes |
| --- | --- | --- | --- |
| Variables/environment | Bruno environment variables | Supported | Common environment import/use. |
| Variables/environment | collection variables | Supported | Collection-scoped variables. |
| Variables/environment | request variables | Supported | Request-scoped values. |
| Variables/environment | folder/collection inheritance | Partially supported | Supported where mapped by parser/runtime fixtures. |
| Variables/environment | runtime variable mutation | Supported | Supported persisted/runtime mutation paths. |
| Request scripting | pre-request scripts | Partially supported | Supported subset. |
| Request scripting | request URL/header/body mutation | Partially supported | Common mutations through exposed bindings. |
| Request scripting | auth/header mutation | Partially supported | Validate per fixture. |
| Response/test scripting | post-response scripts | Partially supported | Supported subset. |
| Response/test scripting | test/assertion behavior | Partially supported | Fixture-backed subset only. |
| Response/test scripting | response JSON/text/header access | Partially supported | Common subset. |
| Response/test scripting | variable extraction | Supported | Common extraction workflows. |
| Import/export | Bruno folder import | Supported | Supported folder shape. |
| Import/export | Bruno ZIP import | Supported | Non-password-protected ZIPs. |
| Import/export | Bruno environment import | Supported | Supported environment files. |
| Import/export | Bruno export preservation | Partially supported | Native metadata may be lossy. |
| Import/export | unsupported metadata preservation | Planned | Preserve where feasible. |
| Limitations | password-protected Bruno ZIPs | Not supported | Unless later implemented and tested. |
| Limitations | exact Bruno runtime behavior | Out of scope | Common workflow compatibility only. |

## 5. API Workbench native scripting compatibility

| Area | Behavior | Status | Notes |
| --- | --- | --- | --- |
| Native behavior | Workbench-native script block format | Supported | Primary controlled format. |
| Native behavior | pre-request scripts | Supported | Lifecycle-supported. |
| Native behavior | post-response scripts | Supported | Lifecycle-supported. |
| Native behavior | request mutation | Supported | Method, URL, headers, body, and auth through bindings. |
| Native behavior | variable mutation | Supported | Supported scope mutations. |
| Native behavior | assertions | Supported | Assertion recording. |
| Native behavior | extractions | Supported | Extraction recording. |
| Native behavior | Runner flow control | Supported | Skip, stop, next-request, dependent/ad-hoc flows. |
| Native behavior | dependent request execution | Supported | Runner-oriented. |
| Native behavior | ad-hoc request execution | Supported | Runner-oriented. |
| Native behavior | History evidence capture | Supported | History entries, metadata, evidence fields. |
| Native behavior | raw request capture | Supported | Captured when a request is sent. |
| Native import/export | Workbench collection import/export | Supported | Most faithful collection round trip. |
| Native import/export | Workbench environment import/export | Supported | Variables, OAuth2 config, and output bindings. |
| Native import/export | script preservation | Supported | Native script blocks are preserved. |
| Native import/export | runtime metadata preservation | Partially supported | Runtime state is not always exported automatically. |

## 6. Cross-format import/export preservation

| Behavior | Status | Notes |
| --- | --- | --- |
| Postman import script preservation | Supported | Recognized events become script blocks. |
| Postman export script preservation | Partially supported | Supported subset maps back to Postman v2.1. |
| Insomnia import script preservation | Partially supported | Recognized shapes only. |
| Insomnia export script preservation | Planned | Verify per schema. |
| Bruno import script preservation | Supported | Supported files/ZIPs preserve script blocks. |
| Bruno export script preservation | Partially supported | Schema differences may be lossy. |
| Workbench native import/export round-trip | Supported | Baseline format. |
| unsupported script metadata preservation | Planned | Preserve where feasible and mark clearly. |
| disabled/untrusted script preservation | Supported | Imported executable scripts stay reviewable. |
| trusted/untrusted script state handling | Supported | Trust state is per script block. |

Unsupported scripts should be preserved where feasible and clearly marked, not silently dropped.

## 7. Runner behavior compatibility

| Behavior | Status | Notes |
| --- | --- | --- |
| sequential execution | Supported | Ordered run model. |
| start paused | Supported | Requires explicit step/resume. |
| step execution | Supported | Only between runnable requests. |
| pause/resume/cancel | Supported | Runner controls. |
| skip request | Supported | Script flow-control path. |
| stop run | Supported | Script flow-control and stop conditions. |
| set next request | Supported | Runner flow-control mapping. |
| dependent request execution | Supported | Runner script helper path. |
| ad-hoc request execution | Supported | Runner script helper path. |
| retries | Supported | Configured retry policy. |
| timeout handling | Supported | Request/runtime safeguards. |
| assertion failure handling | Supported | Stop conditions and result evidence. |
| missing variable handling | Supported | Policy-controlled. |
| script error handling | Supported | Policy-controlled. |
| history/result/evidence capture | Supported | History and Runner result records. |

## 8. Unsupported/partial behavior policy

Unsupported or partially supported script APIs must not silently pretend to work.

Expected behavior:

- Execute the supported subset.
- Warn clearly for recognized but unsupported APIs.
- Fail closed for unsafe behavior.
- Preserve unsupported script content where feasible.
- Show the review/trust dialog for imported executable scripts.
- Record warnings/errors in Script Output, Runner Results, or Diagnostics where applicable.

## 9. Future validation approach

Compatibility should be expanded through golden scripted fixtures:

- Postman golden fixtures
- Insomnia golden fixtures
- Bruno golden fixtures
- Workbench native golden fixtures
- import -> execute -> export -> re-import fixtures
- runner flow-control fixtures
- script trust/review fixtures
- variable precedence fixtures
