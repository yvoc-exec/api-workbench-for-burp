# API Workbench for Burp Suite

API Workbench for Burp Suite is a Burp Suite extension for importing, organizing, editing, replaying, and running API collections directly inside Burp. It is built for pentesters and AppSec engineers who work with OpenAPI, Postman, Insomnia, Bruno, and HAR exports and want more workflow structure than manual Repeater-only testing.

## What it gives you

- Import API collections instead of rebuilding requests by hand.
- Organize work by collection, folder, and request.
- Edit requests in place, then send them to Burp or Repeater.
- Manage active environments and variables without leaving Burp.
- Queue and run multiple requests with predictable sequencing.
- Drag and drop requests, folders, collections, and environments.
- Export back to familiar formats for sharing, handoff, and reporting.

## Common workflows

1. Import or drop a collection into the request tree.
2. Bind an active environment and resolve variables.
3. Edit one request in the Workbench, then send it or send it to Repeater.
4. Check requests into the Collection Runner, preview them, reorder them, and run them.
5. Reorganize the tree with drag and drop while keeping collapse and selection state intact across refreshes.

## Key supported import formats

| Format | Notes |
| --- | --- |
| API Workbench JSON | Native Workbench collection format. |
| Postman Collection v2.1 JSON | Imported directly from exported Postman collections. |
| OpenAPI 3.0 JSON | Supported for structured API definitions. |
| OpenAPI 3.0 YAML | Supported alongside OpenAPI JSON. |
| Insomnia JSON | Supports Insomnia export payloads. |
| Bruno ZIP | Supports Bruno collection packages. |
| HAR 1.2 JSON | Supports captured request collections. |

## Key supported export formats

| Format | Notes |
| --- | --- |
| API Workbench JSON | Native Workbench collection format. |
| Postman Collection v2.1 JSON | Export for Postman-compatible sharing. |
| OpenAPI 3.0 JSON | Export for downstream tooling. |
| OpenAPI 3.0 YAML | Export for downstream tooling. |
| Insomnia JSON | Export for Insomnia-compatible sharing. |
| Bruno ZIP | Export for Bruno-compatible sharing. |
| HAR 1.2 JSON | Export for captured-request handoff. |

## Environment support

API Workbench can load and export several environment styles:

| Format | Notes |
| --- | --- |
| API Workbench Environment JSON | Native environment format. |
| Postman Environment JSON | Import/export for Postman-style environments. |
| dotenv `.env` | Simple `key=value` pairs. |
| Generic flat JSON | Flat JSON object support. |
| Insomnia Environment JSON | Supports Insomnia environment exports. |
| Bruno environment `.bru` | Supports Bruno variable blocks. |

Environment behavior includes:

- An active environment dropdown for quick switching.
- Variable resolution across collection, environment, and request scopes.
- Variable shadowing, so higher-priority values override lower-priority ones with the same name.
- Active-environment switching without rewriting the collection itself.
- Unresolved-variable handling before send, runner start, import, and export when resolution is enabled.

## Why pentesters will like it

- Import collections instead of rebuilding requests manually.
- Organize API targets by collection, folder, and request.
- Create manual requests directly in the tree.
- Duplicate, rename, and delete requests and folders in place.
- Drag and drop requests, folders, and collections to keep the tree tidy.
- Preserve collapsed and expanded tree state during refresh.
- Send individual requests from the Workbench.
- Send requests to Burp Repeater for manual tampering.
- Queue multiple requests in the runner.
- Reorder the runner queue before execution.
- Clear or remove queued requests quickly.
- Use active environments for base URLs, tokens, and other runtime values.
- Export back to common formats for sharing and reporting.
- Run local smoke validation for confidence before handoff.

## Workbench and request editor

The Workbench is the place to inspect and edit a single request:

- Method and URL editing.
- Header editing, including per-request overrides.
- Body editing.
- Auth settings at collection, folder, and request scope, including OAuth2-backed flows.
- Send and Send to Repeater actions.
- Response and result display for quick inspection.
- Per-request state where applicable, so edits stay attached to the request you are working on.

The tree also supports create, rename, duplicate, and delete actions for collections, folders, and requests, which makes it easy to reshape imported collections without recreating them from scratch.

## Collection Runner

The Collection Runner is built for sequential API testing:

- Queue checked requests and preview the run before execution.
- Reorder the queue to test one path after another.
- Remove selected requests or clear the queue when plans change.
- Run queued requests with variable and environment resolution.
- Capture results and failures clearly, including 404s and other request-level errors.
- Keep auth, variables, and scripts on the same pipeline used by the Workbench send flow.
- Support extraction and follow-on workflow steps when you need chained API testing.

## Drag/drop and tree behavior

API Workbench includes drag/drop support for common workflows:

- Request tree file-drop import.
- Request, folder, and collection move/reorder inside the tree.
- Environment file-drop import.
- Active environment drop handling.
- Runner queue reorder.
- Classloader-safe `DataFlavor` handling so payloads survive Burp's extension classloader boundaries.
- Request tree state preservation across refresh and rebuild operations.

Real mouse-driven drag/drop can still benefit from manual spot checks, especially when you want visual confirmation of the exact UI behavior.

## Project Architecture

The main source lives under `src/main/java/burp/`. `src/test/java/burp/` mirrors the same areas with tests.

```text
burp/
|-- BurpExtender.java                 # Extension entry point, startup diagnostics, suite-tab registration
|-- UniversalImporter.java            # Top-level importer/workbench coordinator
|-- auth/
|   |-- OAuth2Config.java             # OAuth2 configuration model
|   |-- OAuth2Manager.java            # Token acquisition and refresh coordinator
|   |-- TokenStore.java               # Runtime token cache
|   |-- ClientCredentialsHandler.java # Client Credentials grant handler
|   |-- PasswordGrantHandler.java     # Resource Owner Password Credentials grant handler
|   |-- RefreshTokenHandler.java      # Refresh Token grant handler
|   `-- AuthorizationCodeHandler.java # PKCE + loopback callback grant handler
|-- exporter/
|   |-- CollectionExportService.java       # Collection export orchestration
|   |-- EnvironmentExportService.java      # Environment export orchestration
|   |-- ApiWorkbenchCollectionExporter.java # Native Workbench collection exporter
|   |-- ApiWorkbenchEnvironmentExporter.java # Native Workbench environment exporter
|   |-- PostmanCollectionExporter.java     # Postman v2.1 collection exporter
|   |-- PostmanEnvironmentExporter.java    # Postman environment exporter
|   |-- OpenApiCollectionExporter.java     # OpenAPI exporter
|   |-- InsomniaCollectionExporter.java    # Insomnia collection exporter
|   |-- InsomniaEnvironmentExporter.java   # Insomnia environment exporter
|   |-- BrunoCollectionExporter.java       # Bruno collection exporter
|   |-- BrunoEnvironmentExporter.java      # Bruno environment exporter
|   |-- HarCollectionExporter.java         # HAR exporter
|   |-- DotEnvEnvironmentExporter.java     # dotenv environment exporter
|   |-- GenericJsonEnvironmentExporter.java # Flat JSON environment exporter
|   |-- CollectionExportOptions.java       # Collection export options
|   |-- EnvironmentExportOptions.java      # Environment export options
|   |-- CollectionExportFormat.java        # Collection export format enum
|   |-- EnvironmentExportFormat.java       # Environment export format enum
|   |-- CollectionExportTree.java          # Collection-tree snapshot used by export flows
|   |-- CollectionExportSupport.java       # Shared collection export helpers
|   |-- ExportVariableResolutionService.java # Variable resolution during export
|   |-- ExportSupport.java                 # Shared export helpers
|   |-- ExportResult.java                  # Export result model
|   |-- ExportFileNamePolicy.java          # Export filename policy
|   |-- ExportIds.java                     # Stable export identifier helper
|   `-- ExportException.java               # Export error type
|-- models/
|   |-- ApiCollection.java                # Unified collection model
|   |-- ApiRequest.java                   # Unified request model
|   |-- BearerTokenAliasCandidate.java    # OAuth2 alias candidate
|   |-- EnvironmentProfile.java          # Active environment profile model
|   |-- OAuth2EnvironmentState.java      # Runtime OAuth2/environment state
|   |-- WorkspaceState.java              # Workspace persistence model
|   |-- ImportResult.java                # Import operation result
|   |-- RunnerPreviewRow.java            # Runner preview row model
|   |-- RunnerResult.java                # Runner operation result
|   |-- RunnerStopConditions.java        # Runner stop-condition config
|   |-- RunnerTimelineRow.java           # Runner timeline row model
|   `-- UnresolvedVariableIssue.java      # Unresolved-variable preflight issue
|-- parser/
|   |-- CollectionParser.java             # Parser interface
|   |-- ParserRegistry.java               # Auto-detect parser registry
|   |-- ApiWorkbenchCollectionParser.java # Native Workbench collection parser
|   |-- PostmanParser.java                # Postman parser
|   |-- OpenApiParser.java                # OpenAPI parser
|   |-- InsomniaParser.java               # Insomnia parser
|   |-- BrunoParser.java                  # Bruno parser
|   |-- HarParser.java                    # HAR parser
|   `-- VariableResolver.java             # Variable resolution engine
|-- runner/
|   `-- CollectionRunner.java             # Sequential request runner
|-- smoke/
|   |-- SmokeRuntimeConfig.java           # Opt-in runtime smoke configuration
|   |-- SmokeRuntimeResult.java           # Runtime smoke result/report model
|   |-- SmokeRuntimeRunner.java           # Local runtime smoke execution harness
|   `-- SmokeUiEvidenceSnapshot.java      # UI evidence snapshot helper
|-- ui/
|   |-- ImporterPanel.java                # Main Swing UI for Workbench, Environment, OAuth2, and Runner
|   |-- OAuth2Panel.java                  # OAuth2 configuration UI
|   |-- RequestEditorPanel.java           # Workbench request editor
|   |-- RequestEditorAuthSupport.java     # Auth-field orchestration helper
|   |-- RequestEditorBodySupport.java     # Body-mode UI helper
|   |-- RequestEditorStateMapper.java     # Request model <-> editor state mapper
|   |-- RequestEditorTableSupport.java    # Request editor table helper
|   |-- RequestPreviewTableModel.java     # Request preview table model
|   |-- ResponsePane.java                 # Response display pane
|   |-- RunnerPreviewTableModel.java      # Runner preview table model
|   |-- RunnerResultTableModel.java       # Runner results table model
|   |-- RunnerTimelineTableModel.java     # Runner timeline table model
|   |-- UnresolvedVariablesDialog.java    # Variable preflight dialog
|   |-- AuthSettingsDialog.java           # Auth settings dialog
|   |-- BearerTokenAliasDialog.java       # Bearer alias selection dialog
|   |-- dnd/
|   |   |-- ActiveEnvironmentDropTransferHandler.java # Active environment drop support
|   |   |-- EnvironmentDragPayload.java    # Environment drag payload
|   |   |-- EnvironmentProfileDragSourceTransferHandler.java # Environment drag source support
|   |   |-- EnvironmentTransferHandler.java # Environment file/profile drag/drop
|   |   |-- RunnerQueueDragPayload.java     # Runner queue drag payload
|   |   `-- RunnerQueueTransferHandler.java # Runner queue reorder support
|   `-- tree/
|       |-- BurpLikeTreeCellRenderer.java  # Request tree renderer
|       |-- CheckBoxTreeCellRenderer.java  # Checkbox tree renderer
|       |-- CollectionTreeNode.java        # Tree node wrapper
|       |-- RequestTreeDragPayload.java    # Request tree drag payload
|       |-- RequestTreeMutationService.java # Tree create/move/rename/delete operations
|       |-- RequestTreeNamingPolicy.java   # Tree naming rules
|       |-- RequestTreePathService.java    # Tree path/scope helper
|       |-- RequestTreeTransferHandler.java # Request tree drag/drop support
|       `-- TreeDropRequest.java           # Drag/drop request model
|-- utils/
|   |-- AuthInheritanceResolver.java      # Auth inheritance resolution
|   |-- DebouncedSwingAction.java         # Debounced Swing action helper
|   |-- EnvironmentImportService.java     # Environment import handling
|   |-- ExecutionResult.java              # Execution result wrapper
|   |-- HttpUtils.java                    # HTTP/URL utilities
|   |-- OAuth2BearerAliasDetector.java    # OAuth2 bearer alias detection
|   |-- OAuth2PopulateHelper.java         # OAuth2 form/population helper
|   |-- OAuth2RuntimeMapper.java          # Imported auth -> oauth2_* runtime mapping
|   |-- RequestBuilder.java               # HTTP message construction
|   |-- RequestBuildPolicy.java           # Request build policy
|   |-- RequestDebugFormatter.java        # Request debug formatting
|   |-- RequestPathResolver.java          # Request path resolution helper
|   |-- RuntimeResolverFactory.java       # Runtime variable resolver factory
|   |-- RuntimeVariablesJson.java         # Runtime vars/OAuth2 JSON helper
|   |-- ScriptEngine.java                # Nashorn/Postman/Bruno script support
|   |-- ScriptMode.java                  # Script execution mode
|   |-- ScriptModeDetector.java          # Java/Nashorn mode detection
|   |-- SharedRequestPipeline.java       # Shared build/send/script/OAuth pipeline
|   |-- SwingEdt.java                    # EDT helper
|   |-- UnresolvedVariableAnalyzer.java  # Unresolved-variable scanner
|   |-- VariableDebugFormatter.java      # Variable debug formatting
|   |-- WorkspaceStateJson.java          # Workspace state JSON helper
|   |-- WorkspaceStateMigrator.java      # Workspace state migration helper
|   `-- WorkspaceStateService.java       # Workspace persistence via extension data
```

`src/test/java/burp/` contains the unit and integration tests that mirror these same areas, including startup, import/export, runner, drag/drop, tree-state, smoke, and utility coverage.

### How the pieces fit together

- `BurpExtender` starts the extension, registers the tab, and restores workspace state.
- `UniversalImporter` and `ImporterPanel` coordinate the Workbench user flow.
- Parsers normalize external formats into the shared collection and request models.
- The request editor and runner share the same request-building, variable-resolution, and auth pipeline.
- Drag/drop support is split between `ui/dnd` and `ui/tree`.
- Workspace persistence is handled through the extension-data services and workspace-state models.
- `smoke/` is local opt-in QA support and does not run during normal loading.

## Smoke and local QA

Runtime smoke mode is for local QA only.

- It is opt-in only through `API_WORKBENCH_SMOKE_CONFIG` or `-DapiWorkbench.smoke.config=...`.
- Normal user startup does not enable smoke mode.
- The smoke run writes JSON, Markdown, evidence, and log-scan reports.
- Do not use real secrets in smoke fixtures or configs.
- Keep generated evidence and logs out of public shares if they may contain sensitive testing data.

## Security and local smoke mode

- Smoke mode is disabled by default.
- Smoke mode is meant for local validation, not normal end-user operation.
- Avoid real secrets in smoke configs and fixtures.
- Review generated evidence and logs before sharing them.
- Tokens are cleared on extension unload where supported.
- Optional live endpoint checks should only target systems you are authorized to test.

## Installation and build

### Build from source

```powershell
mvn clean package
```

The shaded artifact is written to:

```text
target\api-workbench-for-burp-2.0.0-jar-with-dependencies.jar
```

### Load in Burp

1. Open Burp Suite.
2. Go to `Extensions` -> `Add`.
3. Choose `Java` as the extension type.
4. Select the shaded JAR above.

### Requirements

- Burp Suite Community Edition or Professional.
- Java 17+.
- Maven 3.6+ if you are building from source.

This extension is Community-compatible for normal use, and the separate runtime smoke harness is also designed for Community Edition validation.

## Testing

- `mvn test` for the unit and integration-style test suite.
- `mvn clean package` to produce the shaded JAR.
- The separate runtime smoke tester repo can be used for local validation:

  ```powershell
  powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\invoke-runtime-smoke.ps1
  ```

Runtime smoke reports are the source of truth for the external smoke workflow. They include evidence snapshots, log scans, and a generated checklist of manual follow-up items.

For focused operator guidance and deeper reference material, see:

- [Operator Guide](OPERATOR_GUIDE.md)
- [Complete Documentation](DOCUMENTATION.md)
- [Feature Guide](docs/features.md)
- [Testing Guide](docs/testing.md)
- [Smoke Mode Security Notes](docs/security-smoke-mode.md)
- [Draft v2 Release Notes](docs/release-notes-v2-draft.md)

## Summary

API Workbench brings imported collections, a structured request tree, request editing, runner execution, environment management, and drag/drop reorganization into Burp Suite so you can spend less time rebuilding requests and more time testing them.
