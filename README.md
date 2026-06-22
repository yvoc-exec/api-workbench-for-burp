# API Workbench for Burp Suite

API Workbench for Burp Suite is a Burp-native API workspace. It helps you import or create collections, edit and send requests, manage environment profiles and OAuth2, run chained workflows, inspect History and Diagnostics, and export evidence or handoff artifacts.

## Top-level tabs

| Tab | What it does |
| --- | --- |
| Workbench | Load collections, edit one request, send live traffic, or import checked requests to Burp destinations |
| Environment | Manage the active environment profile and its variables, runtime values, and OAuth2 configuration |
| OAuth2 | Acquire and refresh OAuth2 tokens into the selected environment profile |
| Collection Runner | Queue and execute ordered request sequences with retries, stop conditions, pause/resume, and step |
| History | Review prior Workbench and Runner executions, compare them, replay them, and export evidence |
| Diagnostics | Capture passive runtime diagnostics, inspect sanitized reports, and copy snapshots |

## What it gives you

| Capability | What users can do | Why it matters |
| --- | --- | --- |
| Import or create collections | Import Postman, OpenAPI/Swagger, Insomnia, Bruno, HAR, or native API Workbench assets, or build collections manually | Start from the format your team already has |
| Workbench | Edit and send one request at a time | Keep focused request work inside Burp |
| Environment | Create, import, duplicate, export, delete, and activate environment profiles | Switch targets, tenants, or roles without rewriting the collection |
| OAuth2 | Bind token acquisition to the active environment profile | Reduce manual token copying and refresh drift |
| Collection Runner | Queue, preview, reorder, step, and run requests | Test chained API workflows repeatably |
| History | Replay, compare, export, copy, or re-open previous executions | Reproduce findings and create evidence |
| Diagnostics | Capture passive events, sanitize them, and copy reports | Diagnose runtime issues without sending traffic |
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

Workbench is where you inspect and modify a single API request without leaving Burp.

| Editor capability | What users can do |
| --- | --- |
| Method / URL / headers / body | Edit requests in place |
| Auth settings | Adjust auth at collection, folder, or request scope |
| Send / Send to Repeater | Test or hand off to Repeater |
| Response display | Inspect results without leaving Burp |
| Blank URL request creation | Create manual GET requests and fill endpoints step by step |

Per-request state stays attached to the request you are working on, which makes it easier to refine a request, test it, and then hand it off to Repeater when you need more control.

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

The primary runtime is GraalJS. Nashorn is a compatibility fallback, not the main architecture. GraalJS blocks general host-class lookup, direct I/O, and thread creation; scripts only see annotated binding APIs. The direct Nashorn-factory fallback uses a deny-all class filter.

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

Always run only trusted scripts. Scripts can mutate requests and runtime state, and there is no execution timeout.

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

The current presentation is a Runner Queue on the left, one consolidated Runner Execution Table, and a shared detail viewer on the right. Selecting queue or execution entries updates the detail view. Delay, retries, stop conditions, redirects, pause, resume, step, cancel, and raw-request debug behavior remain available.

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

History may contain raw requests, responses, authorization material, tokens, cookies, and sensitive payloads. Review before sharing.

Replay uses the original snapshot when possible and can fall back to a `History Replays` collection when the original request no longer exists. The History store retains at most 1,000 entries and persists with the workspace.

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
| Collections | API Workbench JSON, Postman Collection v2.1 JSON, OpenAPI 3.0 JSON, OpenAPI 3.0 YAML, Insomnia JSON, Bruno ZIP, HAR 1.2 JSON | Share a cleaned-up collection in the format your team prefers |
| Environments | API Workbench Environment JSON, Postman Environment JSON, dotenv `.env`, Generic flat JSON, Insomnia Environment JSON, Bruno environment `.bru` | Reuse the same targets, tenants, roles, and tokens elsewhere |
| History | HAR, Native History JSON, CSV summary | Export evidence or produce lightweight reports |

API Workbench native format preserves native script blocks most faithfully. External export formats are lossy where their schemas cannot represent all Workbench metadata.

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
| Postman Collection v2.0 / v2.1 JSON | Yes | Yes | Postman-compatible sharing |
| OpenAPI / Swagger 2.x / 3.x | Yes | Export as 3.0 JSON or YAML | Structured API definitions |
| Insomnia JSON | Yes | Yes | Includes recognized pre/post script shapes |
| Bruno ZIP / folder | Yes | Yes | Bruno collection packages |
| HAR 1.2 JSON | Yes | Yes | Captured request collections |

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
target\api-workbench-for-burp-2.0.0-jar-with-dependencies.jar
```

### Validation

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

## Project architecture

The main source lives under `src/main/java/burp/`. Tests live under `src/test/java/burp/`.

Key current components:

- `UnifiedScriptRuntime`, `GraalJsSandboxEngine`, `ScriptLifecycleExecutor`, and `ScriptBindingsFactory` are the primary script-runtime components.
- `ScriptEngine` is the legacy compatibility adapter.
- `RunnerExecutionTableModel` backs the current runner execution table.
- `History*` classes back the History tab, and `Diagnostic*` classes back Diagnostics.

The architecture tree below is generated from the tracked production sources and lists every production Java file exactly once.

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
|   |-- GraalJsSandboxEngine.java
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

### Repository support files

| Path | Why it must stay tracked |
| --- | --- |
| `.github/workflows/build.yml` | Active CI workflow for normal and full validation modes |
| `config/spotbugs-exclude.xml` | Active SpotBugs exclusion file referenced by Maven |
| `scripts/ci/check_junit_reports.py` | CI helper for validating test reports |

## More documentation

- [Operator Guide](OPERATOR_GUIDE.md)
- [Complete Documentation](DOCUMENTATION.md)
- [Environment vs Collection Precedence](ENVIRONMENT-VS-COLLECTION-PRECEDENCE.md)

## Summary

API Workbench brings imported collections, a structured request tree, request editing, runner execution, history, diagnostics, environment management, and drag/drop reorganization into Burp Suite so you can spend less time rebuilding requests and more time testing.
