# Feature Test Coverage Inventory

## Coverage quality labels

- `NONE` — no meaningful automated coverage found for the feature area.
- `LIGHT` — a few focused assertions exist, but major workflows/branches remain open.
- `PARTIAL` — important happy paths are covered, but major edge cases or whole subflows are missing.
- `GOOD` — the feature has multiple targeted tests with meaningful assertions across common paths and several edge cases.
- `STRONG` — broad automated coverage exists across the feature's core behaviors, edge cases, and regressions.

## Feature-to-test mapping

| Feature area | Quality | Key automated tests inspected | What the tests actually assert | Major measured gaps |
|---|---|---|---|---|
| Startup / packaging | `PARTIAL` | `BurpExtenderStartupTest`, `ScriptRuntimeProbeTest` | Startup tests assert suite-tab registration and startup-failure logging. Runtime probe test asserts JavaScript evaluation succeeds and returns engine details. | No pre-pass repository coverage artifacts; no automated test directly validated shaded JAR contents before CI/runtime steps. |
| Parsers / import | `GOOD` | `PostmanParserTest`, `BrunoParserTest`, `InsomniaParserTest`, `OpenApiParserTest`, `EnvironmentImportServiceTest`, `UniversalImporterEnvFileTest` | Assertions cover folder hierarchy, auth inheritance, script dialect metadata, file-upload metadata, disabled headers, Bruno ZIP zip-slip rejection, OpenAPI path/query/body conversion, and environment-format import shapes. | No broad malformed-input matrix yet for every format; native collection parser and HAR parsing are not hardened to the level required by later passes. |
| Export | `GOOD` | `CollectionExportServiceTest`, `EnvironmentExportServiceTest`, `PostmanCollectionExporterTest`, `BrunoCollectionExporterTest`, `OpenApiCollectionExporterTest`, `HarCollectionExporterTest`, `InsomniaCollectionExporterTest` | Assertions cover native round-trip metadata, unresolved-variable counting, export-only overlays, runtime secret exclusion, parseable OpenAPI/Bruno/HAR/Insomnia/Postman output, and deterministic environment export formatting. | File-write failure/atomic replacement cases are not covered; semantic round-trip matrix is still incomplete. |
| Variables / request building | `STRONG` | `VariableResolverTest`, `RuntimeResolverFactoryTest`, `RequestBuilderTest`, `RequestBuilderPolicyTest`, `SharedRequestPipelineTest`, `UnresolvedVariableAnalyzerTest` | Assertions cover scope precedence, defaults/unresolved tokens, active-environment overlays, auth-mapped runtime vars, duplicate/case-insensitive headers, content-length recalculation, multipart/raw/form/graphql bodies, OAuth2/header injection, and runtime variable mutation behavior. | `RequestBuilder` and `SharedRequestPipeline` still carry high residual branch counts; explicit failure-classification suites are still missing. |
| Script engine | `PARTIAL` | `UnifiedScriptRuntimeTest`, `ScriptEnginePostmanApiTest`, `ScriptModeDetectorTest`, `ScriptRuntimeProbeTest`, runner dependent/ad-hoc tests | Assertions cover dialect-aware block ordering, pre-request mutation, post-response assertions/logs, runner-only warning behavior, host-class sandbox denial, and engine-name reporting. | `burp/scripts` is only **37.6% branch**; `ScriptEngine` and `ScriptBindingsFactory` remain major blind spots; there is no parameterized dialect contract suite yet. |
| Workbench | `GOOD` | `RequestEditorPanelTest`, `RequestEditorPanelAuthMetadataTest`, `ImporterPanelWorkbenchDetailPaneTest`, `UniversalImporterSingleSendResultTest`, `WorkbenchHistoryCaptureTest` | Assertions cover materialized headers, resolved preview text, auth metadata persistence, manual-preserve header suppression, variable hover popup behavior, no-dirty-state hover/copy behavior, and Workbench Send history capture. | `ImporterPanel` and `RequestEditorPanel` still sit below 50% branch coverage in important orchestration paths; no mouse-driven UI tests. |
| Runner | `GOOD` | `CollectionRunnerTest`, `CollectionRunnerControlTest`, `CollectionRunnerStopConditionsTest`, `CollectionRunnerPreviewTest`, `CollectionRunnerDependentRequestTest`, `CollectionRunnerDependentRequestGuardrailTest`, `CollectionRunnerAdHocRequestTest`, `CollectionRunnerFlowControlBlockerTest`, `ImporterPanelRunnerQueueTest` | Assertions cover retries, cancel, selection/sequence order, preview resolution, stop-on-missing-variable/status/assertion/failure-count behavior, dependent/ad-hoc requests, guardrails, flow-control labels, and queue UI actions. | No dedicated `RunnerExecutionTableModelTest`; state-matrix and step-mode coverage expected in Pass 3 is still absent. |
| History | `GOOD` | `WorkbenchHistoryCaptureTest`, `RunnerHistoryCaptureTest`, `HistoryNativeMessageFormatterTest`, `HistoryPanelTest`, `HistoryReplayActionTest`, `HistoryLoadInWorkbenchActionTest`, `HistoryReplaysCollectionTest`, `History*ExportServiceTest`, `HistoryFiltersTest`, `HistoryRetentionPolicyTest`, `HistoryStoreTest` | Assertions cover template-vs-raw request selection, retry attempt metadata, skipped/stopped result display names, load/replay fallback behavior, history export coordinators, filters/search, retention, and defensive-copy store behavior. | `HistoryRequestSnapshot` branch coverage is low, compare dialog has zero line coverage, and a one-off `HistorySendToRepeaterActionTest` Mockito stub failure was observed once before later passing reruns. |
| Environment | `GOOD` | `EnvironmentImportServiceTest`, `EnvironmentExportServiceTest`, `ImporterPanelEnvironmentTabTest`, `UnresolvedVariablesDialogTest`, `WorkspaceStateJsonTest`, `UniversalImporterWorkspaceSaveTest` | Assertions cover Postman/dotenv/Bruno/Insomnia/native environment imports, deterministic exports, active-environment persistence/restore, export-only quick entry, and runtime-vs-persisted environment state handling. | Environment switching/collection isolation across the full shared pipeline is not yet covered end-to-end. |
| OAuth2 / auth | `PARTIAL` | `AuthorizationCodeHandlerTest`, `OAuth2ConfigTest`, `OAuth2ManagerDiagnosticsTest`, `OAuth2PopulateHelperTest`, `OAuth2BearerAliasDetectorTest`, `AuthInheritanceResolverTest`, `ImporterPanelOAuth2AcquireTest`, `ImporterPanelOAuth2PopulateTest` | Assertions cover loopback redirect parsing, config extraction, diagnostic emission, token-url inference, alias detection/binding, auth inheritance precedence, and Populate-from-Request UI behavior. | `burp/auth` package coverage is only **24.4% line / 14.6% branch**; `ClientCredentialsHandler`, `PasswordGrantHandler`, and `RefreshTokenHandler` are effectively untested. |
| Diagnostics | `PARTIAL` | `DiagnosticSanitizerTest`, `DiagnosticStoreTest`, `OAuth2ManagerDiagnosticsTest`, `CollectionRunnerDependentRequestDiagnosticsTest`, `ImporterPanelDiagnosticsTabTest` | Assertions cover secret masking, debug filtering, store clearing, OAuth2 error logging, dependent-request diagnostics, and diagnostics-tab presence. | Package branch coverage is still only **44.2%**; diagnostic event/data-model shaping is mostly exercised indirectly. |
| Request tree / workspace | `GOOD` | `RequestTreeMutationServiceTest`, `RequestTreeNamingPolicyTest`, `ImporterPanelRequestTreeCreateFlowTest`, `ImporterPanelRequestTreeDragDropTest`, `ImporterPanelRequestTreeStateTest`, `ImporterPanelTreeRestoreTest`, `UniversalImporterWorkspaceSaveTest`, `WorkspaceStateJsonTest`, `WorkspaceStateServiceTest` | Assertions cover create/duplicate/rename/remove/move semantics, slash/backslash label safety, naming collisions, autosave collapse, workspace restore sequencing, normalized tree paths, and persisted request build-mode/header suppression state. | `RequestTreeTransferHandler` remains almost uncovered; renderer/table model coverage is still thin. |
| Security / data handling | `PARTIAL` | `DiagnosticSanitizerTest`, `HistorySanitizerTest`, `CollectionExportServiceTest`, `BrunoParserTest`, `UnifiedScriptRuntimeTest` | Assertions cover masking of auth/cookie/token secrets, CSV/text sanitization, export-time runtime secret exclusion, Bruno ZIP zip-slip rejection, and Graal host-class access denial. | No broad sandbox suite yet for file/thread/env access attempts; no export write-failure / partial-file safety coverage; raw-history masking rules are not deeply automated. |
| Non-functional behavior | `LIGHT` | `BurpExtenderStartupTest`, `SwingEdtTest`, `UniversalImporterWorkspaceSaveTest`, `HistoryRetentionPolicyTest`, `ScriptRuntimeProbeTest` | Assertions cover EDT dispatch, debounced workspace saves, retention clamping, startup failure logging, and runtime probe availability. | Performance, resource cleanup, headless UI rendering, and host-specific live Burp behavior remain mostly outside automation. |

## Evidence notes by major subsystem

### Variables / request building

The strongest existing automated coverage is around the request-execution core:

- `RequestBuilderTest` asserts concrete HTTP artifacts such as `Host`, `Authorization`, `Cookie`, `Content-Type`, multipart boundaries, and recomputed `Content-Length` values.
- `SharedRequestPipelineTest` asserts pre-request script mutation, active-environment overlays, OAuth2 token injection, multipart byte preservation, and split header/body handling.
- `RuntimeResolverFactoryTest` asserts resolver precedence stays aligned with the shared pipeline.

### Script engine

Coverage breadth is present, but depth is not yet where the behavioral goals require it:

- `UnifiedScriptRuntimeTest` asserts multi-dialect block ordering, mutation, assertions/logs, and host-class sandbox denial.
- Runner tests assert `runRequest` behavior, dependent request metadata, and visible warnings when runner-only capabilities are unavailable.
- Residual risk remains high because `ScriptBindingsFactory` and `ScriptEngine` still have very low branch coverage.

### Workbench + Runner + History

These areas have many meaningful tests, but they also own much of the remaining risk budget:

- `RequestEditorPanelTest` contains strong assertions around materialized/suppressed headers and resolved previews.
- Runner tests cover retries, stop conditions, dependent flows, and preview behavior.
- History tests cover load/replay/fallback/export/filter behavior.
- The largest uncovered orchestration surfaces are still `ImporterPanel`, `RequestEditorPanel`, `HistoryRequestSnapshot`, and several UI table/dialog classes.

## Follow-up priorities suggested by this inventory

1. **Pass 2 is justified directly by measured risk**: the request pipeline classes already have strong tests but still large remaining branch surfaces.
2. **Pass 4 should be substantial, not cosmetic**: multi-format script behavior is present but still only `PARTIAL` from a coverage standpoint.
3. **Auth/OAuth2 deserves a focused pass or sub-pass** because low package coverage is concentrated in live grant handlers, not just helper classes.
4. **Pass 3 will need model-level UI hardening** for Runner tables/state machines because several renderer/table classes are still untested or near-untested.

## Passes 8-13 update

The inventory above captures the earlier baseline pass. The following addendum reflects the latest hardening run and the new tests added during Passes 8-13.

### New high-value test areas

| Feature area | Updated quality | Key new tests added or strengthened | Main behavior now covered | Remaining gaps |
|---|---|---|---|---|
| OAuth2 / auth | `GOOD` | `OAuth2GrantHandlerIntegrationTest`, `OAuth2ManagerTest`, `TokenStoreTest`, `AuthorizationCodeHandlerTest`, `OAuth2ConfigTest`, `ImporterPanelOAuth2AcquireTest`, `ImporterPanelOAuth2PopulateTest`, `EnvironmentRuntimeMutationIntegrationTest`, `EnvironmentWorkspacePersistenceTest` | Deterministic localhost grant flows, token lifecycle, refresh coordination, runtime overlays, secret-safe binding, and environment-switch behavior. | Full browser-mediated authorization-code cancellation and a few redirect/timeout variants remain thin. |
| History | `GOOD` | `HistoryRequestSnapshotTest`, `HistoryEntryCompatibilityTest`, `HistoryPersistenceCompatibilityTest`, `HistoryStoreConcurrencyTest`, `HistoryCompareDialogUiIT`, `HistorySendToRepeaterActionTest` | Raw/authored request preservation, compatibility fixtures, concurrent store operations, compare dialog behavior, and replay/Repeater handoff safety. | Some compare/replay edge cases and larger persistence matrices still deserve follow-on attention. |
| Environment / diagnostics | `GOOD` | `DiagnosticPassiveBehaviorTest`, `DiagnosticStoreConcurrencyTest`, `DiagnosticWorkspacePersistenceTest`, `SecretLeakageSurfaceTest`, `EnvironmentRuntimeMutationIntegrationTest`, `EnvironmentWorkspacePersistenceTest` | Runtime vs persisted environment state, passive diagnostics, bounded retention, secret redaction, and export safety. | Additional long-running UI-driven diagnostics flows are still mostly manual. |
| Workspace / request tree | `GOOD` | `WorkspaceCompatibilityFixtureTest`, `WorkspaceConcurrentSaveTest`, `WorkspaceFailureRecoveryTest`, `RequestTreeTransferHandlerTest`, `EnvironmentEditorShortcutUiIT` | Save/load safety, concurrent save behavior, drag/drop payloads, and environment editor keyboard save behavior. | More real-display tree interactions and stale-editor edge cases remain desirable. |
| UI interaction | `GOOD` | `HistoryCompareDialogUiIT`, `EnvironmentEditorShortcutUiIT` | Small stable Swing interaction coverage behind `-Pui-tests`. | Full manual UX polish, complex multi-window flows, and host-specific visual checks remain manual. |
| Security / non-functional | `GOOD` | `BrunoParserZipSafetyTest`, `YamlResourceLimitTest`, `RequestBuilderSecurityTest`, `ExportWriteFailureTest`, `WorkspacePerformanceIT` | ZIP slip/traversal safety, recursive YAML bounds, header/URL sanitization, export failure safety, and large workspace performance coverage. | More negative-input matrices and resource-leak checks remain useful, but the current suite is materially stronger. |
| Governance / CI | `GOOD` | `ui-tests`, `performance-tests`, `static-analysis`, `mutation-tests` profiles, the consolidated `Build & Validate API Workbench JAR` workflow, and the tag-triggered `Release JAR` workflow | Normal CI covers JaCoCo-gated core tests, the Linux/Windows compatibility matrix, Xvfb Swing UI tests, and SpotBugs. Full validation adds blocking package-sharded PIT runs and uploads exactly one canonical validated Java 17 shaded JAR, which `Release JAR` republishes without rebuilding. | Hosted CI is still the final proof for Windows/Xvfb/shared-runner behavior, and PIT remains a blocking non-regression floor rather than the aspirational target. |

### Current measured coverage snapshot

- Overall: 62.8% line / 45.2% branch
- `burp.auth`: 74.1% line / 57.7% branch
- `burp.history`: 87.2% line / 64.0% branch
- `burp.diagnostics`: 83.8% line / 55.8% branch
- `burp.parser`: 74.1% line / 52.7% branch
- `burp.exporter`: 81.5% line / 56.9% branch
- `burp.runner`: 81.0% line / 58.3% branch
- `burp.ui`: 62.6% line / 43.3% branch
- `burp.ui.history`: 67.6% line / 41.6% branch
- `burp.ui.tree`: 74.6% line / 57.2% branch
- `burp.utils`: 72.6% line / 55.0% branch
