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
