# API Workbench for Burp Suite

API Workbench for Burp Suite is a structured API testing workspace inside Burp. It helps pentesters and AppSec engineers import or create API collections, organize requests, manage environments and OAuth2, edit and send requests, run chained workflows, replay execution history, and export collections or results for reporting and handoff.

## Why use API Workbench?

API testing often starts outside Burp: a Postman collection, an OpenAPI spec, an Insomnia export, a Bruno collection, or a HAR file. Burp is powerful for manual testing, but imported API workflows can quickly turn into scattered Repeater tabs, copied requests, and one-off edits that are hard to reuse.

| Question | Answer |
| --- | --- |
| Who is it for? | Pentesters, AppSec engineers, and API testers working in Burp. |
| What problem does it solve? | It keeps API collections, environments, request edits, Runner runs, and history in one Burp-native workspace instead of scattering them across Repeater tabs or manual rebuilds. |
| Why not only Burp Repeater? | Repeater is great for tampering with one request, but API Workbench keeps the surrounding workflow organized and reusable. |
| Why not rebuild requests by hand? | You can start from the assets your team already has and keep testing instead of recreating everything manually. |

API Workbench keeps that work in one Burp-native workspace so you can:

- avoid rebuilding API requests by hand;
- keep collections, folders, requests, variables, environments, OAuth2 config, and runner state together;
- switch targets, tenants, or roles with active environments instead of rewriting the collection;
- test authenticated APIs with OAuth2-aware workflows;
- run ordered API sequences with the Collection Runner;
- send interesting requests to Repeater for deeper manual tampering;
- replay, compare, and export previous executions from History;
- share cleaned-up collections, environments, or execution history with teammates, clients, or reports.

## What it gives you

| Capability | What users can do | Why it matters |
| --- | --- | --- |
| Import or create collections | Import Postman, OpenAPI, Insomnia, Bruno, or HAR assets, or create API collections manually | Start from real team assets or build custom test sets |
| Request tree | Organize by collection, folder, and request | Avoid scattered Repeater tabs |
| Workbench | Edit and send one request | Test and refine APIs inside Burp |
| Environments | Create, import, edit, set active, duplicate, delete, and export environments | Switch targets, tenants, roles, and tokens |
| OAuth2 | Configure token flows and reuse tokens | Reduce manual auth setup |
| Collection Runner | Queue, preview, reorder, and run requests | Test chained API workflows |
| Replay History | Replay, compare, export, and load prior executions | Reproduce findings and create evidence |
| Export / handoff | Export collections, environments, and selected history | Share work with teammates, clients, or reports |

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

Request labels can contain `/` safely. Folder names reject `/` and `\` to avoid ambiguous tree paths.

Supported collection formats are summarized in [Supported formats](#supported-formats).

### Organize requests in a Burp-native workflow

Keep API testing organized by feature, role, object type, or attack path.

| Tree behavior | Supported | Notes |
| --- | --- | --- |
| Collection / folder / request tree | Yes | Keep API work organized by feature, role, object type, or attack path |
| Drag/drop reorder | Yes | Move requests, folders, and collections |
| Tree state preservation | Yes | Keep selection and collapse state across refreshes |
| File-drop import | Yes | Drag supported files into the tree |
| Safe request labels | Yes | Request labels can contain `/` safely |
| Folder path protection | Yes | Folder names reject `/` and `\` to avoid ambiguous paths |

### Edit and send requests from the Workbench

The Workbench is where you inspect and modify a single API request without leaving Burp.

| Editor capability | What users can do |
| --- | --- |
| Method / URL / headers / body | Edit requests in place |
| Auth settings | Adjust auth at collection, folder, or request scope |
| Send / Send to Repeater | Test or hand off to Repeater |
| Response display | Inspect results without leaving Burp |
| Blank URL request creation | Create manual GET requests and fill endpoints step by step |

Per-request state stays attached to the request you are working on, which makes it easier to refine a request, test it, and then hand it off to Repeater when you need more control.

### Manage environments and variables

Switch between environments without rewriting the collection.

| Environment action | Supported | Notes |
| --- | --- | --- |
| New Environment | Yes | Create variables directly inside Burp |
| Import Environment | Yes | Bring in Postman, dotenv, JSON, Insomnia, or Bruno environments |
| Edit / Save | Yes | Manage values used by runtime resolution |
| Duplicate | Yes | Quickly create role, tenant, or target variants |
| Delete | Yes | Remove unused profiles |
| Set Active | Yes | Select the environment used by Workbench, Runner, and exports |
| Export | Yes | Share or archive reusable environment profiles |

Supported environment formats are summarized in [Supported formats](#supported-formats).

Use the active environment dropdown to swap contexts quickly. Collection, environment, and request scopes all participate in variable resolution, with higher-priority values shadowing lower-priority ones when names overlap. Unresolved variables are surfaced before send, runner execution, import, and export when resolution is enabled.

### Preserve and run collection scripts

API Workbench preserves script-capable imports instead of flattening them into one generic runtime.

| Script source | Support | Notes |
| --- | --- | --- |
| Postman | Yes | Keeps prerequest/test scripts in their original dialect |
| Insomnia | Yes | Preserves request-level scripts and environment-aware mutations |
| Bruno | Yes | Preserves bru/req/res-style scripts and tests |
| API Workbench native | Yes | Round-trips script blocks losslessly |

Scripts run through the active Workbench runtime so they can mutate request state, resolve variables, log output, raise assertions, and participate in Runner sequencing.

### Test authenticated APIs with OAuth2 support

API Workbench includes OAuth2-aware request handling for common API testing flows.

| OAuth2 flow | Support | Notes |
| --- | --- | --- |
| Client Credentials | Yes | Common machine-to-machine flow |
| Password Grant | Yes | Supported for legacy or controlled environments |
| Refresh Token | Yes | Reuses existing refresh tokens when available |
| Authorization Code / PKCE | Where supported | Uses a localhost callback flow |

The Workbench can acquire tokens, refresh them when they expire, and store them for reuse during the current workspace session. That reduces the amount of manual token copying needed for authenticated API testing.

### Run chained API workflows with Collection Runner

Use the runner for ordered, repeatable API flows.

| Runner capability | What users can do |
| --- | --- |
| Queue checked requests | Build a run list |
| Preview before execution | Confirm order and scope |
| Reorder queue | Test paths in the right sequence |
| Remove selected / clear queue | Adapt quickly when plans change |
| Sequential runs | Execute login -> create -> update -> delete flows |
| Capture results | Track success, failure, status, and duration |
| Extraction / assertion workflows | Support chained testing where available |

Use it for flows like login -> create object -> update object -> delete object -> verify authorization behavior.

### Revisit and replay testing activity with Replay History

Replay History keeps a Burp-native record of what you actually ran from API Workbench.

| Recorded? | Sources |
| --- | --- |
| Captured | Workbench Send, Collection Runner attempts, and retry attempts |
| Not captured | Burp Proxy traffic, Repeater sends, Import-to-Sitemap live sends, manual request draft edits, Scanner, Collaborator, or OAST traffic |

| Action | Why it matters |
| --- | --- |
| Load in Workbench | Apply the history snapshot back into the original request when it still exists |
| Replay from History | Re-run a previous request state |
| Send to Repeater | Continue manual tampering |
| Copy URL / Copy as cURL | Share quickly or move outside Burp |
| Compare Selected | Diff two runs |
| Export HAR / JSON / CSV | Build evidence and handoff artifacts |
| Delete selected / Clear History | Manage stored history |

Replay History retains the latest 1000 entries. If the original request still exists, Load in Workbench applies the snapshot back into that original request. If it no longer exists, API Workbench creates or reuses a `History Replays` collection. Burp project files and exported history may contain sensitive request and response data, so review them before sharing.

The History request viewer now prefers the actual raw HTTP request that was sent, while still retaining the authored/template request for replay and editing.

For the full feature reference, see [Replay History Guide](docs/replay-history.md).

### Export collections, environments, and history for handoff

Move cleaned-up work out of Burp when you need to share or archive it.

| Export area | Formats | Why it matters |
| --- | --- | --- |
| Collections | API Workbench JSON, Postman Collection v2.1 JSON, OpenAPI 3.0 JSON, OpenAPI 3.0 YAML, Insomnia JSON, Bruno ZIP, HAR 1.2 JSON | Share a cleaned-up collection in the format your team prefers |
| Environments | API Workbench Environment JSON, Postman Environment JSON, dotenv `.env`, Generic flat JSON, Insomnia Environment JSON, Bruno environment `.bru` | Reuse the same targets, tenants, roles, and tokens elsewhere |
| History | HAR, Native History JSON, CSV summary | Export evidence or produce lightweight reports |

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

## Common workflows

1. Import a Postman collection, OpenAPI spec, Insomnia export, Bruno collection, or HAR file and start testing immediately in Burp.
2. Create a new collection manually for undocumented endpoints, then add folders and requests as you discover them.
3. Build a dev, staging, or tenant-specific environment profile and switch contexts with the active environment dropdown.
4. Queue a login -> create -> update -> delete sequence in the Collection Runner, then inspect results and compare runs in History.
5. Send an interesting request to Repeater when you want to tamper with one case, then replay the result later from History.
6. Export a cleaned-up collection, a reusable environment file, or selected history entries for handoff and reporting.

## Supported formats

### Collections

| Format | Import | Export | Notes |
| --- | --- | --- | --- |
| API Workbench JSON | Yes | Yes | Native collection format |
| Postman Collection v2.1 JSON | Yes | Yes | Postman-compatible sharing |
| OpenAPI 3.0 JSON | Yes | Yes | Structured API definitions |
| OpenAPI 3.0 YAML | Yes | Yes | YAML OpenAPI support |
| Insomnia JSON | Yes | Yes | Insomnia export/import |
| Bruno ZIP | Yes | Yes | Bruno collection packages |
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

## Project architecture

The main source lives under `src/main/java/burp/`. Tests live under `src/test/java/burp/`.

```text
burp/
|-- BurpExtender.java                 # Extension entry point, startup diagnostics, suite-tab registration
|-- UniversalImporter.java            # Top-level importer/workbench coordinator
|-- auth/
|   |-- OAuth2Config.java             # OAuth2 configuration model
|   |-- OAuth2Manager.java            # Token acquisition and refresh coordinator
|   |-- TokenStore.java               # Runtime token cache
|   |-- ClientCredentialsHandler.java  # Client Credentials grant handler
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
|-- history/
|   |-- HistoryEntry.java                  # Persisted replay-history row model
|   |-- HistorySource.java                 # Workbench vs Runner source enum
|   |-- HistoryResult.java                 # Normalized execution result classification
|   |-- HistoryRequestSnapshot.java        # Authored/template request snapshot
|   |-- HistoryResponseSnapshot.java       # Captured response snapshot
|   |-- HistoryHeader.java                # Request/response header snapshot model
|   |-- HistoryAssertionResult.java        # Assertion outcome model
|   |-- HistoryExtractionResult.java       # Extraction outcome model
|   |-- HistoryFilterCriteria.java         # Table filter model
|   |-- HistoryRetentionPolicy.java        # Latest-1000 retention policy
|   |-- HistoryStore.java                  # In-memory retained history store
|   |-- HistoryPersistenceService.java     # Workspace/project-data persistence bridge
|   |-- HistoryExportService.java          # HAR/JSON/CSV export coordinator
|   |-- HistoryJsonExportService.java      # Native history JSON export
|   |-- HistoryCsvExportService.java       # Summary CSV export
|   |-- HistoryHarExportService.java       # HAR export using templated values
|   |-- HistoryDiffService.java            # Compare / diff generator
|   |-- HistoryJsonSupport.java            # Shared JSON adapters and normalization
|   `-- HistorySanitizer.java              # Display/export helpers
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
|   |-- RunnerTimelineRow.java            # Runner timeline row model
|   `-- UnresolvedVariableIssue.java      # Unresolved-variable preflight issue
|-- parser/
|   |-- CollectionParser.java             # Parser interface
|   |-- ParserRegistry.java               # Auto-detect parser registry
|   |-- ApiWorkbenchCollectionParser.java # Native Workbench collection parser
|   |-- PostmanParser.java                # Postman parser
|   |-- OpenApiParser.java                # OpenAPI parser
|   |-- InsomniaParser.java                # Insomnia parser
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
|   |-- ImporterPanel.java                # Main Swing UI for Workbench, Environment, OAuth2, Runner, and History
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
|   |-- history/
|   |   |-- HistoryPanel.java               # History tab shell, toolbar, filters, table, and detail split pane
|   |   |-- HistoryTableModel.java          # History row table model
|   |   |-- HistoryFilterPanel.java         # Full history filter controls
|   |   |-- HistoryDetailPanel.java         # Request/response/variables/assertions detail tabs
|   |   |-- HistoryActionsPanel.java        # Load/replay/export/compare/delete actions
|   |   |-- HistoryCompareDialog.java       # Two-entry diff viewer
|   |   |-- HistoryLoadResultNotifier.java  # Confirmation and notification popups
|   |   |-- HistoryNativeHttpMessageFactory.java # History response/body message adapter
|   |   `-- HistoryNativeMessageFormatter.java   # Native history formatting helpers
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

## Testing / validation

Run the normal Maven checks in the API Workbench repo:

```powershell
mvn test
mvn clean package
```

Expected output:

- `mvn test` passes.
- `mvn clean package` passes.
- The shaded artifact is written to `target\api-workbench-for-burp-2.0.0-jar-with-dependencies.jar`.

For deeper validation notes and targeted test references, see [Testing Guide](docs/testing.md).

## More documentation

- [Operator Guide](OPERATOR_GUIDE.md)
- [Complete Documentation](DOCUMENTATION.md)
- [Feature Guide](docs/features.md)
- [Replay History Guide](docs/replay-history.md)
- [Testing Guide](docs/testing.md)

## Summary

API Workbench brings imported collections, a structured request tree, request editing, runner execution, replay history, environment management, and drag/drop reorganization into Burp Suite so you can spend less time rebuilding requests and more time testing.
