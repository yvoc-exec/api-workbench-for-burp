# API Workbench for Burp Suite - Complete Documentation

## Table of Contents

1. [Overview](#1-overview)
2. [Features](#2-features)
3. [Architecture](#3-architecture)
4. [Data Flow](#4-data-flow)
5. [Collection Format Support](#5-collection-format-support)
6. [Variable Resolution Engine](#6-variable-resolution-engine)
7. [Environment and OAuth2](#7-environment-and-oauth2)
8. [Collection Runner](#8-collection-runner)
9. [Script Runtime](#9-script-runtime)
10. [History Subsystem](#10-history-subsystem)
11. [Diagnostics Subsystem](#11-diagnostics-subsystem)
12. [State Synchronization](#12-state-synchronization)
13. [Security Considerations](#13-security-considerations)
14. [Known Limitations & Code-Level Behaviors](#14-known-limitations--code-level-behaviors)
15. [Error Handling](#15-error-handling)
16. [Performance](#16-performance)
17. [Extending the Extension](#17-extending-the-extension)
18. [Troubleshooting](#18-troubleshooting)
19. [Appendix A: Data Model Reference](#appendix-a-data-model-reference)
20. [Appendix B: Validation Profiles](#appendix-b-validation-profiles)

## 1. Overview

API Workbench is a Burp-native API workspace for importing or creating collections, editing and sending requests, managing environment profiles and OAuth2, running sequential workflows, recording History, and capturing Diagnostics.

### Key capabilities

| Capability | Notes |
| --- | --- |
| Sandboxed JavaScript runtime | JavaScript scripts run on a supported sandboxed runtime when Java 17+ is available |
| Legacy script compatibility | Legacy script paths remain bounded by the supported runtime behavior |
| Dialects | Postman, Bruno, Insomnia, API Workbench native, and legacy compatibility |
| History | Records Workbench and Runner executions, script output, assertions, extractions, and variable changes |
| Diagnostics | Capture passive events and generate or copy sanitized reports |
| Environment profiles | Environment tab manages the active profile, OAuth2 config, and output bindings |

## 2. Features

### 2.1 Multi-Format Import

Supported import formats include Postman Collection v2.0 and v2.1 JSON import with Postman Collection v2.1 JSON export, OpenAPI/Swagger 2.x/3.x, Insomnia, Bruno, HAR, and native API Workbench collections. Insomnia import preserves recognized pre/post script shapes. API Workbench native collections preserve script blocks most faithfully.

### 2.2 Multi-Collection Support

The workspace can load multiple collections at once. Request-tree state and per-collection runtime data remain isolated by collection identity.

### 2.3 Import Destinations

The same checked requests can be sent to Repeater, Sitemap, or Intruder. Workbench Send remains separate from collection-style control flow.

### 2.4 Collection Runner

The runner executes checked requests in order with retries, redirect handling, stop conditions, pause/resume, step, and cancellation. Runner redirect following is controlled separately from Workbench Send, and redirect hops are recorded as evidence under the logical execution rather than as queue items. Redirect following defaults to 10 hops and is configurable from 1 to 20. Pre-request scripts run once per logical request, post-response scripts run once against the final successful response, stop-on-status evaluates only that final response, fragments are stripped before the next request is built, and percent-encoded path/query octets stay encoded exactly. 307/308 preserve method, body, and entity metadata; 301/302 preserve body for non-POST methods; and POST-to-GET or non-HEAD 303 redirects drop body and entity headers.

### 2.5 OAuth2 Token Management

OAuth2 configuration belongs to the active environment profile. Tokens are acquired, refreshed, and written back to configured environment outputs.

### 2.6 Script Runtime

The runtime supports multiple script dialects and phases, mutates request state and variables, and records logs, warnings, errors, assertions, and extractions.

## 3. Architecture

### 3.1 Package Structure

Root support infrastructure lives in the repository root, `config/`, `.github/workflows/`, and `scripts/ci/`. The production Java tree is generated from the tracked sources below.

**Primary runtime and execution components**

- `burp.scripts.UnifiedScriptRuntime`
- `burp.scripts.SandboxedJavaScriptEngine`
- `burp.scripts.ScriptLifecycleExecutor`
- `burp.scripts.ScriptBindingsFactory`
- `burp.scripts.ScriptExecutionContext`
- `burp.scripts.VariableScopeStore`
- `burp.scripts.ScriptBlock`
- `burp.utils.ScriptEngine` as the legacy compatibility adapter

```text
burp/
|-- BurpExtender.java
|-- UniversalImporter.java
|-- auth/
|   |-- AuthorizationCodeHandler.java
|   |-- ClientCredentialsHandler.java
|   |-- OAuth2Config.java
|   |-- OAuth2Manager.java
|   |-- PasswordGrantHandler.java
|   |-- RefreshTokenHandler.java
|   `-- TokenStore.java
|-- diagnostics/
|   |-- DiagnosticEvent.java
|   |-- DiagnosticOperation.java
|   |-- DiagnosticSanitizer.java
|   |-- DiagnosticSeverity.java
|   |-- DiagnosticSink.java
|   `-- DiagnosticStore.java
|-- exporter/
|   |-- ApiWorkbenchCollectionExporter.java
|   |-- ApiWorkbenchEnvironmentExporter.java
|   |-- BrunoCollectionExporter.java
|   |-- BrunoEnvironmentExporter.java
|   |-- CollectionExportFormat.java
|   |-- CollectionExportOptions.java
|   |-- CollectionExportService.java
|   |-- CollectionExportSupport.java
|   |-- CollectionExportTree.java
|   |-- DotEnvEnvironmentExporter.java
|   |-- EnvironmentExportFormat.java
|   |-- EnvironmentExportOptions.java
|   |-- EnvironmentExportService.java
|   |-- ExportException.java
|   |-- ExportFileNamePolicy.java
|   |-- ExportIds.java
|   |-- ExportResult.java
|   |-- ExportSupport.java
|   |-- ExportVariableResolutionService.java
|   |-- GenericJsonEnvironmentExporter.java
|   |-- HarCollectionExporter.java
|   |-- InsomniaCollectionExporter.java
|   |-- InsomniaEnvironmentExporter.java
|   |-- OpenApiCollectionExporter.java
|   |-- PostmanCollectionExporter.java
|   `-- PostmanEnvironmentExporter.java
|-- history/
|   |-- HistoryAssertionResult.java
|   |-- HistoryCsvExportService.java
|   |-- HistoryDiffService.java
|   |-- HistoryEntry.java
|   |-- HistoryExportService.java
|   |-- HistoryExtractionResult.java
|   |-- HistoryFilterCriteria.java
|   |-- HistoryHarExportService.java
|   |-- HistoryHeader.java
|   |-- HistoryJsonExportService.java
|   |-- HistoryJsonSupport.java
|   |-- HistoryPersistenceService.java
|   |-- HistoryRequestSnapshot.java
|   |-- HistoryResponseSnapshot.java
|   |-- HistoryResult.java
|   |-- HistoryRetentionPolicy.java
|   |-- HistorySanitizer.java
|   |-- HistorySource.java
|   `-- HistoryStore.java
|-- models/
|   |-- ApiCollection.java
|   |-- ApiRequest.java
|   |-- BearerTokenAliasCandidate.java
|   |-- EnvironmentProfile.java
|   |-- ImportResult.java
|   |-- OAuth2EnvironmentState.java
|   |-- RunnerPreviewRow.java
|   |-- RunnerResult.java
|   |-- RunnerStopConditions.java
|   |-- RunnerTimelineRow.java
|   |-- UnresolvedVariableIssue.java
|   `-- WorkspaceState.java
|-- parser/
|   |-- ApiWorkbenchCollectionParser.java
|   |-- BrunoParser.java
|   |-- CollectionParser.java
|   |-- HarParser.java
|   |-- InsomniaParser.java
|   |-- OpenApiParser.java
|   |-- ParserRegistry.java
|   |-- PostmanParser.java
|   `-- VariableResolver.java
|-- runner/
|   `-- CollectionRunner.java
|-- scripts/
|   |-- ExecutionSource.java
|   |-- SandboxedJavaScriptEngine.java
|   |-- ScriptAdHocRequest.java
|   |-- ScriptAssertionResult.java
|   |-- ScriptBindingsFactory.java
|   |-- ScriptBlock.java
|   |-- ScriptDependentRequestExecutor.java
|   |-- ScriptDependentRequestResult.java
|   |-- ScriptDialect.java
|   |-- ScriptExecutionContext.java
|   |-- ScriptExecutionResult.java
|   |-- ScriptFlowControl.java
|   |-- ScriptLifecycleExecutor.java
|   |-- ScriptLogEntry.java
|   |-- ScriptPhase.java
|   |-- ScriptScope.java
|   |-- ScriptVariableMutation.java
|   |-- UnifiedScriptRuntime.java
|   `-- VariableScopeStore.java
|-- smoke/
|   `-- ScriptRuntimeProbe.java
|-- ui/
|   |-- AuthSettingsDialog.java
|   |-- BearerTokenAliasDialog.java
|   |-- ImporterPanel.java
|   |-- OAuth2Panel.java
|   |-- RequestEditorAuthSupport.java
|   |-- RequestEditorBodySupport.java
|   |-- RequestEditorPanel.java
|   |-- RequestEditorStateMapper.java
|   |-- RequestEditorTableSupport.java
|   |-- RequestPreviewTableModel.java
|   |-- ResponsePane.java
|   |-- RunnerExecutionTableModel.java
|   |-- RunnerPreviewTableModel.java
|   |-- RunnerResultTableModel.java
|   |-- RunnerTimelineTableModel.java
|   |-- SwingShortcutSupport.java
|   |-- UnresolvedVariablesDialog.java
|   |-- VariableHighlightStyler.java
|   |-- VariableResolutionStatus.java
|   |-- VariableStatusColors.java
|   |-- VariableTokenScanner.java
|   |-- dnd/
|   |   |-- ActiveEnvironmentDropTransferHandler.java
|   |   |-- EnvironmentDragPayload.java
|   |   |-- EnvironmentProfileDragSourceTransferHandler.java
|   |   |-- EnvironmentTransferHandler.java
|   |   |-- RunnerQueueDragPayload.java
|   |   `-- RunnerQueueTransferHandler.java
|   |-- history/
|   |   |-- HistoryActionsPanel.java
|   |   |-- HistoryCompareDialog.java
|   |   |-- HistoryDetailPanel.java
|   |   |-- HistoryFilterPanel.java
|   |   |-- HistoryLoadResultNotifier.java
|   |   |-- HistoryNativeHttpMessageFactory.java
|   |   |-- HistoryNativeMessageFormatter.java
|   |   |-- HistoryPanel.java
|   |   `-- HistoryTableModel.java
|   `-- tree/
|       |-- BurpLikeTreeCellRenderer.java
|       |-- CheckBoxTreeCellRenderer.java
|       |-- CollectionTreeNode.java
|       |-- RequestTreeDragPayload.java
|       |-- RequestTreeMutationService.java
|       |-- RequestTreeNamingPolicy.java
|       |-- RequestTreePathService.java
|       |-- RequestTreeTransferHandler.java
|       `-- TreeDropRequest.java
`-- utils/
    |-- AuthInheritanceResolver.java
    |-- DebouncedSwingAction.java
    |-- EnvironmentImportService.java
    |-- ExecutionResult.java
    |-- HttpUtils.java
    |-- OAuth2BearerAliasDetector.java
    |-- OAuth2PopulateHelper.java
    |-- OAuth2RuntimeMapper.java
    |-- RequestBuilder.java
    |-- RequestBuildPolicy.java
    |-- RequestDebugFormatter.java
    |-- RequestPathResolver.java
    |-- RuntimeResolverFactory.java
    |-- RuntimeVariablesJson.java
    |-- ScriptEngine.java
    |-- ScriptMode.java
    |-- ScriptModeDetector.java
    |-- SharedRequestPipeline.java
    |-- SwingEdt.java
    |-- UnresolvedVariableAnalyzer.java
    |-- VariableDebugFormatter.java
    |-- WorkspaceStateJson.java
    |-- WorkspaceStateMigrator.java
    `-- WorkspaceStateService.java

```

### 3.2 Class Diagram (Simplified)

```text
BurpExtender
`-- UniversalImporter
    |-- ImporterPanel
    |   |-- Workbench controls
    |   |-- Environment profile selection
    |   |-- OAuth2 acquisition
    |   |-- Runner execution table
    |   |-- History panel
    |   `-- Diagnostics panel
    |-- ParserRegistry
    |-- CollectionRunner
    |-- EnvironmentProfile / OAuth2EnvironmentState / WorkspaceState
    |-- HistoryStore / HistoryPersistenceService / HistoryExportService
    |-- DiagnosticStore / DiagnosticEvent / DiagnosticSanitizer
    |-- UnifiedScriptRuntime / ScriptLifecycleExecutor / ScriptBindingsFactory / SandboxedJavaScriptEngine
    `-- RequestBuilder / SharedRequestPipeline / RuntimeResolverFactory

```

### 3.3 Design Patterns Used

| Pattern | Application |
| --- | --- |
| Strategy | `CollectionParser` interface with one parser per supported collection family |
| Registry | `ParserRegistry` auto-detects format via parser capability checks |
| Observer | Runner listeners and UI models update execution views |
| Builder | `RequestBuilder` constructs HTTP messages step-by-step |
| Factory | `ScriptBindingsFactory`, `HttpService.httpService()`, and request helpers build runtime objects |

## 4. Data Flow

### 4.1 Import Flow

1. User selects a file or Bruno folder.
2. `ParserRegistry` selects a parser.
3. Parser builds `ApiCollection` and related models.
4. Environment profiles, scripts, auth, and request-tree state are stored in workspace state, along with any import-specific runtime mapping data required by the current model.
5. Checked requests can be sent to Workbench, Runner, Repeater, Sitemap, or Intruder destinations.

### 4.2 Workbench Send Flow

1. Create resolver and execution context.
2. Run collection, ancestor-folder, and request pre-request scripts.
3. Apply request and variable mutations.
4. Resolve variables, inherited auth, auth/runtime mappings, and required OAuth2 material.
5. Build the final HTTP request.
6. Send the request. Workbench Send's Follow redirects control lives in the Send dropdown and applies only to that send. Workbench redirect following defaults to 10 hops and is configurable from 1 to 20.
7. Run post-response and test scripts.
8. Merge supported persisted mutations.
9. Update Workbench details, History, and Diagnostics.

### 4.3 Runner Flow

1. Runner queue is created from checked requests.
2. Each request passes through the same shared pipeline as Workbench Send.
3. Runner-specific flow control can skip, stop, step, or advance to the next request.
4. Execution entries are recorded into the Runner execution table and History. Final stop-on-status evaluation uses the final response after redirect handling, and redirect hops never execute scripts on their own.

### 4.4 OAuth2 Flow

1. OAuth2 tab operates on the selected environment profile.
2. Tokens are acquired or refreshed through the configured grant flow or loopback callback path.
3. Configured token outputs are written to the profile and persist with workspace state.
4. The active environment overlay then participates in request resolution and later sends.

### 4.5 Script Lifecycle Flow

1. Collection scripts run.
2. Ancestor-folder scripts run from outermost to innermost.
3. Request scripts run.
4. Phases are pre-request, post-response, and test.
5. Script output, assertions, extractions, flow control, and variable mutations are merged into runtime state.

### 4.6 History Recording Flow

1. A request or runner step executes.
2. The request snapshot, response snapshot, and metadata are normalized.
3. `HistoryEntry` objects are appended to the history store.
4. Export services can later produce HAR, CSV, or native JSON. History entries may retain raw requests, responses, authorization material, tokens, cookies, and other sensitive payloads.

### 4.7 Diagnostics Recording Flow

1. Passive runtime events are captured only when Diagnostics capture is enabled.
2. Raw events are retained in the bounded in-memory store first; report and snapshot generation apply best-effort sanitization by operation.
3. The in-memory store keeps at most 1,000 events.
4. The operator can refresh, copy, or clear the snapshot.

## 5. Collection Format Support

### 5.1 Postman (v2.0 / v2.1)

Import: Postman Collection v2.0 and v2.1 JSON. Export: Postman Collection v2.1 JSON. Pre-request and test scripts are mapped into the runtime model.

### 5.2 Bruno

Bruno folders and ZIP packages import and export as supported collection shapes. Scripts and request metadata are preserved where Bruno can represent them.

### 5.3 OpenAPI / Swagger

OpenAPI and Swagger 2.x/3.x import is supported. Export is OpenAPI 3.0 JSON or YAML.

### 5.4 Insomnia (v4)

Insomnia import includes supported pre/post script field shapes. Environment imports and exports are supported where the schema allows them.

### 5.5 HAR

HAR files import as captured request collections and can export as evidence.

### 5.6 Native API Workbench

Native API Workbench collections preserve authored collection structure, auth, variables, folder metadata, requests, build mode, editor materialization, duplicate headers, header order, disabled header state, and native script blocks most faithfully. They do not automatically serialize `runtimeVars` or `runtimeOAuth2`; optional active-environment resolution can materialize values when selected. External export formats are lossy where their schemas cannot represent all metadata.

Workbench safe mode preserves ordinary authored headers, including duplicate ordinary headers and relative order where practical, but regenerates `Host` and `Content-Length`, removes unsafe transport and hop-by-hop headers, removes `Connection`-nominated headers, and keeps redirect follow-ups under redirect safety rules. Advanced per-request `Exact transport headers — Advanced` is available from the Workbench Send dropdown; it shows `⚠ Exact transport headers`, warns once per editor session on explicit enable, persists through workspace/native export/import, History, replay, Runner, and request duplication, and is intended for malformed-request, desynchronization, or request-smuggling testing. Burp, proxies, HTTP/2 conversion, and servers may normalize or reject exact requests, so exact mode is not guaranteed byte-for-byte wire transport.

## 6. Variable Resolution Engine

### 6.1 Syntax

Variables use `{{name}}` placeholders. `{{name|default}}` is only a fallback when a key remains unresolved.

### 6.2 Normal request resolution precedence

Lowest to highest:

1. Collection environment
2. Collection definition variables
3. Ancestor-folder variables
4. Collection runtime OAuth2 values
5. Collection runtime variables
6. Active Environment overlay
7. Explicit execution/runtime/script overlay
8. Request-level variables
9. Auth/runtime mapping when enabled

Later layers win. The active environment wins over collection variables and collection runtime layers. Request variables remain the strongest normal authored variable override. Default placeholders are used only when a key remains unresolved and are not a normal mutable scope.

### 6.3 Normal vs script scope

Script-local values and helper context are execution-time only. They are not the same as persisted collection/environment layers unless a script explicitly mutates a supported persisted scope or writes through an exposed environment/collection binding.

### 6.4 Implementation

`RuntimeResolverFactory` and `VariableResolver` resolve normal request values. `VariableScopeStore` and the script bindings coordinate transient and persisted script mutations.

## 7. Environment and OAuth2

`EnvironmentProfile` is the operator-facing workspace object for normal variables, OAuth2 configuration, and output bindings. `OAuth2EnvironmentState` keeps the per-environment OAuth2 configuration and returned token bindings. `TokenStore` is an in-memory token cache cleared when the extension unloads. Configured OAuth2 outputs written into an `EnvironmentProfile` can persist with workspace state. Burp project files containing persisted token outputs are sensitive.

The active environment applies to Workbench sends, Runner, previews, Repeater, Intruder, Sitemap construction, and exports where resolution is needed. Collection `runtimeVars` and `runtimeOAuth2` remain compatibility/runtime storage layers, not the primary operator model.

## 8. Collection Runner

### 8.1 Execution Model

The runner is sequential. Each request passes through the same shared pipeline used by Workbench Send, plus Runner-specific retries, stop conditions, and flow control.

### 8.2 Configuration

Delay, retries, redirect handling, stop conditions, and raw-request debug are persisted in workspace state. Retries are attempts after the initial attempt, and stop conditions can halt execution on error, assertion failure, status >= 400, unresolved variables, or a configured failure threshold.

### 8.3 Request Lifecycle

1. Runner selects the next enabled queued request and honors pause, step, delay, and retry state.
2. The shared pipeline creates the execution context and runs collection, ancestor-folder, and request pre-request scripts.
3. Pre-request scripts may mutate the request and supported variable scopes. `SKIP_REQUEST` can end the request before a raw HTTP request is built or sent.
4. The pipeline resolves variables, inherited auth, auth/runtime mappings, and required OAuth2 material.
5. The final HTTP request is built and sent.
6. Post-response and test scripts run, and supported persisted mutations are committed.
7. Runner records attempt and result details in the Runner Execution Table and History.
8. Runner evaluates retry and stop conditions, then applies Runner-specific next-request, stop, and dependent-request flow control.

### 8.4 Flow Control

Skip, stop, next-request, and dependent-request control are Runner-oriented. Workbench single Send does not behave like a collection-control engine.

### 8.5 Unified presentation

The current UI uses one Runner Execution Table and a shared detail viewer; there are not separate visible Results and Timeline tables in the current operator presentation.

## 9. Script Runtime

### 9.0 Script mode gating

Java 17+ is required. Full mode means a supported runtime is available. Limited mode means runtime probing failed and only legacy post-response regex extraction is available. Disabled mode means the Java runtime is below the supported requirement.

### 9.1 Sandboxed JavaScript runtime

`SandboxedJavaScriptEngine` creates a fresh sandbox context per execution, blocks general host-class lookup, direct I/O, and thread creation, and exposes only the binding APIs that the extension intentionally provides. The sandboxed JavaScript path does not grant unrestricted `Java.type()` access or full JVM control.

### 9.2 Legacy script compatibility

Legacy script paths remain bounded by the supported runtime behavior.

### 9.3 Bindings and dialects

`ScriptBindingsFactory` exposes dialect-aware bindings for Postman, Bruno, Insomnia, native API Workbench, and legacy compatibility scripts. Binding surfaces include `pm`, `bru / req / res`, `insomnia / request / response`, `awb`, and `console`. Runner-only controls such as skip, stop, next-request, and dependent-request are not generic Workbench Send features.

### 9.4 Script lifecycle and models

`ScriptLifecycleExecutor`, `ScriptExecutionContext`, `ScriptExecutionResult`, `ScriptAssertionResult`, `ScriptVariableMutation`, `ScriptFlowControl`, `ScriptLogEntry`, `ScriptPhase`, `ScriptScope`, `ScriptDialect`, `ScriptBlock`, and `ScriptDependentRequestResult` capture the script contract and its outcomes.

### 9.5 Security model

Scripts can mutate requests and runtime state. Operators must run only trusted scripts and treat no-timeout execution as a security and stability risk.

## 10. History Subsystem

History records Workbench and Runner executions, including request/response snapshots, script output, assertions, extracted values, and variable changes. The operator-facing tab is `History`. History request and response content remains unmasked. `HistorySanitizer` handles safe text normalization and CSV-cell/formula safety; it is not a secret-redaction engine.

`HistoryStore` retains the latest 1,000 entries by default, `HistoryPersistenceService` stores them with the workspace, and `HistoryExportService` and its format-specific services produce HAR/JSON/CSV output.

## 11. Diagnostics Subsystem

Diagnostics captures passive runtime events in a bounded in-memory store. Sanitized reports and snapshots are generated from those events. Reports group events by operation and include warning, error, and debug summaries. Capture is disabled by default and does not send traffic. Workspace state stores the capture-enabled flag. Sanitization is best-effort, so reports still need operator review before sharing them.

## 12. State Synchronization

### 12.1 Workspace persistence

`WorkspaceState` persists loaded collections, environment profiles, the active environment ID, request-tree checks, selection and expansion state, saved request paths, collection/folder/request auth and script blocks, collection environment/runtime variables/runtime OAuth2 values, History entries, Diagnostics capture setting, Workbench destination/debug/detail selections, and Runner settings/detail selection/queued request identities. Script mutations only become persisted state when they target a supported persisted scope.

### 12.2 Active-environment visibility

Environment and OAuth2 mutations become visible to Workbench, Runner, and export resolution through the active environment overlay. Script mutations that target supported persisted scopes update the visible environment state after execution. Collection-targeted mutation does not automatically become an Environment-profile mutation.

### 12.3 UI mirroring

`ImporterPanel`, `RequestEditorPanel`, `OAuth2Panel`, `HistoryPanel`, `RunnerExecutionTableModel`, and the diagnostics views mirror the current workspace state rather than keeping separate hidden copies.

## 13. Security Considerations

- Sandboxed JavaScript uses restricted host access and is not a full JVM shell.
- Legacy script compatibility remains bounded by supported runtime behavior.
- Scripts, uploads, exports, and project files may contain secrets.
- OAuth2 loopback callbacks use localhost and should be used only in controlled environments.
- There is no execution timeout for scripts.

## 14. Known Limitations & Code-Level Behaviors

- Sequential Runner execution is intentional.
- Script compatibility depends on the selected runtime mode.
- Parser support remains limited by the external format; lossy export is expected when the schema cannot express native metadata. Collection export only materializes values when explicitly requested and does not auto-serialize runtimeVars or runtimeOAuth2.
- Generic `sendRequest`-style compatibility helpers are not guaranteed beyond the implemented binding surfaces.
- Default placeholders are not a normal mutable scope.

## 15. Error Handling

Runtime failures, import errors, variable resolution problems, OAuth2 failures, and runner errors are surfaced in the UI and Diagnostics. Use Diagnostics capture when collecting evidence about runtime failures or degraded script mode.

## 16. Performance

Each execution gets a fresh sandbox context. History and Diagnostics stores are bounded. The pipeline is optimized for sequential request handling rather than parallel execution.

## 17. Extending the Extension

Add new script-binding behavior under `burp.scripts` runtime and binding classes, not in the legacy `ScriptEngine` compatibility layer. Collection-format work belongs with the parser and exporter packages. UI additions should preserve the current Workbench, Environment, OAuth2, Collection Runner, History, and Diagnostics model.

## 18. Troubleshooting

### 18.1 Extension won't load

- Confirm Java 17+.
- Confirm the fat JAR, not the source tree, is loaded.
- Check extension errors.

### 18.2 JavaScript runtime unavailable or limited

- Sandboxed JavaScript runtime is the supported path.
- Legacy script compatibility remains bounded by supported runtime behavior.
- Use Diagnostics to capture runtime evidence.

### 18.3 OAuth2 browser doesn't open

- Check callback/loopback settings.
- Confirm the selected environment profile.

### 18.4 Variables not resolving

- Check the active Environment tab selection.
- Inspect request-level overrides.
- Consult `ENVIRONMENT-VS-COLLECTION-PRECEDENCE.md`.

### 18.5 Import creates empty Repeater tabs

- Confirm checked requests are selected.
- Confirm destination settings.

## Appendix A: Data Model Reference

### Environment / workspace

- `EnvironmentProfile`
- `OAuth2EnvironmentState`
- `WorkspaceState`

### Request / collection

- `ApiCollection`
- `ApiRequest`
- `BearerTokenAliasCandidate`
- `ImportResult`

### Runner

- `RunnerResult`
- `RunnerPreviewRow`
- `RunnerTimelineRow`
- `RunnerStopConditions`

### History

- `HistoryEntry`
- `HistoryRequestSnapshot`
- `HistoryResponseSnapshot`
- `HistoryHeader`
- `HistoryAssertionResult`
- `HistoryExtractionResult`
- `HistoryDiffService`

### Diagnostics

- `DiagnosticEvent`
- `DiagnosticOperation`
- `DiagnosticSeverity`
- `DiagnosticStore`
- `DiagnosticSanitizer`

### Script runtime

- `UnifiedScriptRuntime`
- `SandboxedJavaScriptEngine`
- `ScriptLifecycleExecutor`
- `ScriptBindingsFactory`
- `ScriptExecutionContext`
- `VariableScopeStore`
- `ScriptBlock`
- `ScriptDialect`
- `ScriptPhase`
- `ScriptFlowControl`
- `ScriptExecutionResult`
- `ScriptVariableMutation`
- `ScriptAssertionResult`
- `ScriptDependentRequestResult`

## Appendix B: Validation Profiles

Run the standard Maven profiles:

```powershell
mvn test
mvn clean package
mvn -Pstatic-analysis verify

```

Push and pull-request runs use normal CI mode. Full manually dispatched validation adds the performance, mutation, and canonical package jobs.
