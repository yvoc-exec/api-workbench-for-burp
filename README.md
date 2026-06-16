# API Workbench for Burp Suite

API Workbench for Burp Suite is a structured API testing workspace inside Burp. It helps pentesters and AppSec engineers import or create API collections, organize requests, manage environments and OAuth2, edit and send requests, run chained workflows, replay execution history, and export collections or results for reporting and handoff.

## Why use API Workbench?

API testing often starts outside Burp: a Postman collection, an OpenAPI spec, an Insomnia export, a Bruno collection, or a HAR file. Burp is powerful for manual testing, but imported API workflows can quickly turn into scattered Repeater tabs, copied requests, and one-off edits that are hard to reuse.

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

- Bring Postman, OpenAPI, Insomnia, Bruno, HAR, and native Workbench collections into Burp.
- Create new API collections, folders, requests, and environments directly inside Burp.
- Organize requests in a collection/folder/request tree instead of scattered Repeater tabs.
- Use active environments and variables for base URLs, tokens, tenants, roles, and test data.
- Configure OAuth2-backed workflows for authenticated API testing.
- Edit and send requests from the Workbench, or send them to Repeater for deeper manual testing.
- Queue and run ordered API flows with the Collection Runner.
- Review, replay, compare, and export execution history from the History tab.
- Export collections, environments, and selected history for reporting or handoff.

## Main features

### Import or create API collections

API Workbench is not import-only. You can start from files your team already uses or create collections manually inside Burp.

- Import existing API collections.
- Create new collections, folders, and requests directly from the request tree.
- Create folders and requests from imported collections without starting over.
- File-drop import makes it easy to bring assets into the tree.
- Supported import formats:
  - API Workbench JSON
  - Postman Collection v2.1 JSON
  - OpenAPI 3.0 JSON
  - OpenAPI 3.0 YAML
  - Insomnia JSON
  - Bruno ZIP
  - HAR 1.2 JSON

Use this when you want to begin from the assets teams already have instead of rebuilding requests by hand.

### Organize requests in a Burp-native workflow

Keep API testing organized by feature, role, object type, or attack path.

- Work with a collection/folder/request tree.
- Drag and drop to reorder requests, folders, and collections.
- Move requests, folders, and collections within the tree.
- Preserve tree state across refreshes and rebuilds.
- Import by dropping files into the tree.

Manual naming rules are built for API testing, too:

- request labels can contain `/` safely;
- folder names reject `/` and `\` to avoid ambiguous tree paths;
- duplicate, rename, and delete actions are available for collections, folders, and requests.

### Edit and send requests from the Workbench

The Workbench is where you inspect and modify a single API request without leaving Burp.

- Edit method, URL, headers, body, and auth settings.
- Work with per-request state where applicable.
- Send the current request from Workbench.
- Send a request to Burp Repeater for deeper tampering.
- Inspect response and result details in the editor panes.
- Create manual GET requests with a blank URL when you want to assemble an endpoint step by step.

This makes it easier to refine a request, test it, and then hand it off to Repeater when you need more control.

### Manage environments and variables

Switch between environments without rewriting the collection.

- Create, import, edit, save, duplicate, delete, set active, and export environments.
- Supported environment formats:
  - API Workbench Environment JSON
  - Postman Environment JSON
  - dotenv `.env`
  - Generic flat JSON
  - Insomnia Environment JSON
  - Bruno environment `.bru`
- Use the active environment dropdown to swap contexts quickly.
- Resolve variables across collection, environment, and request scopes.
- Benefit from variable shadowing when the same variable name exists in multiple scopes.
- Keep templates like `{{base_url}}` and `{{token}}` intact until runtime resolution.
- Handle unresolved variables before send, runner execution, import, and export when resolution is enabled.

Use this to move between dev, staging, production, tenant, role, or token sets without duplicating the collection.

### Test authenticated APIs with OAuth2 support

API Workbench includes OAuth2-aware request handling for common API testing flows.

- Configure OAuth2 at collection, folder, or request scope.
- Acquire tokens from the Workbench workflow.
- Refresh tokens when they expire.
- Store tokens for reuse during the current workspace session.
- Supported grant flows include:
  - Client Credentials
  - Password Grant
  - Refresh Token
  - Authorization Code / PKCE where supported

This reduces the amount of manual token copying needed for authenticated API testing.

### Run chained API workflows with Collection Runner

Use the runner for ordered, repeatable API flows.

- Queue checked requests.
- Preview the run before execution.
- Reorder the runner queue.
- Remove selected queued requests or clear the queue.
- Run sequential API workflows.
- Capture success, failure, status, and duration.
- Support extraction and assertion workflows where available.

Use it for flows like login → create object → update object → delete object → verify authorization behavior.

### Revisit and replay testing activity with Replay History

Replay History keeps a Burp-native record of what you actually ran from API Workbench.

- Captures Workbench Send.
- Captures Collection Runner attempts.
- Captures retry attempts.
- Does not capture Burp Proxy traffic.
- Does not capture Repeater sends.
- Does not capture Import-to-Sitemap live sends.
- Load in Workbench.
- Replay from History.
- Send to Repeater.
- Copy URL.
- Copy as cURL.
- Compare Selected.
- Export as HAR, native History JSON, or CSV.
- Delete selected entries.
- Clear History.
- Retains the latest 1000 entries.
- Warns that history may contain sensitive request and response data.

If the original request still exists, Load in Workbench applies the history snapshot back into that original request. If it no longer exists, API Workbench creates or reuses a `History Replays` collection.

Use this to revisit what was tested, compare different runs, replay a previous request state, or export evidence for reporting.

### Export collections, environments, and history for handoff

Move cleaned-up work out of Burp when you need to share or archive it.

- Collection export formats:
  - API Workbench JSON
  - Postman Collection v2.1 JSON
  - OpenAPI 3.0 JSON
  - OpenAPI 3.0 YAML
  - Insomnia JSON
  - Bruno ZIP
  - HAR 1.2 JSON
- Environment export formats:
  - API Workbench Environment JSON
  - Postman Environment JSON
  - dotenv `.env`
  - Generic flat JSON
  - Insomnia Environment JSON
  - Bruno environment `.bru`
- History export formats:
  - HAR
  - native History JSON
  - CSV summary

This makes it easier to hand off collections, environment profiles, or selected execution history to teammates, clients, or reports.

### Ease-of-use features for daily testing

The small workflow details add up when you are testing APIs all day.

- Drag/drop imports.
- Drag/drop request tree organization.
- Runner queue reorder.
- Active environment switching.
- Unresolved variable prompts.
- Copy as cURL.
- Send to Repeater.
- Clear/remove queue actions.

These features reduce repetitive setup work and keep testing moving inside Burp.

## Common workflows

1. Import a Postman collection, OpenAPI spec, Insomnia export, Bruno collection, or HAR file and start testing immediately in Burp.
2. Create a new collection manually for undocumented endpoints, then add folders and requests as you discover them.
3. Build a dev, staging, or tenant-specific environment profile and switch contexts with the active environment dropdown.
4. Queue a login → create → update → delete sequence in the Collection Runner, then inspect results and compare runs in History.
5. Send an interesting request to Repeater when you want to tamper with one case, then replay the result later from History.
6. Export a cleaned-up collection, a reusable environment file, or selected history entries for handoff and reporting.

## Supported formats

### Collections

| Format | Direction | Notes |
| --- | --- | --- |
| API Workbench JSON | Import / export | Native collection format. |
| Postman Collection v2.1 JSON | Import / export | Supports shared Postman collections. |
| OpenAPI 3.0 JSON | Import / export | Structured API definition support. |
| OpenAPI 3.0 YAML | Import / export | YAML variant of OpenAPI 3.0. |
| Insomnia JSON | Import / export | Supports Insomnia exports. |
| Bruno ZIP | Import / export | Supports Bruno collection packages. |
| HAR 1.2 JSON | Import / export | Supports captured request collections. |

### Environments

| Format | Direction | Notes |
| --- | --- | --- |
| API Workbench Environment JSON | Import / export | Native environment format. |
| Postman Environment JSON | Import / export | Supports Postman-style environments. |
| dotenv `.env` | Import / export | Simple `key=value` variables. |
| Generic flat JSON | Import / export | Flat JSON object support. |
| Insomnia Environment JSON | Import / export | Supports Insomnia environments. |
| Bruno environment `.bru` | Import / export | Supports Bruno variable blocks. |

### History exports

| Format | Notes |
| --- | --- |
| HAR | Best for portable network-style evidence. |
| Native History JSON | Best for fidelity inside API Workbench. |
| CSV summary | Best for lightweight review and reporting. |

## Replay History

Replay History is a focused reference for the History tab.

What it records:

- Workbench direct Send
- Collection Runner executions, including each retry attempt

What it does not record:

- Repeater sends
- Burp Proxy traffic
- Import-to-Sitemap live sends
- Manual request draft edits
- Scanner, Collaborator, or OAST traffic

History stores the authored/template request snapshot plus execution metadata such as result, status, duration, size, environment, unresolved variables, assertions, and extractions.

Available actions:

- Load in Workbench
- Replay from History
- Send to Repeater
- Copy URL
- Copy as cURL
- Compare Selected
- Export selected entries as HAR, native History JSON, or CSV
- Delete selected entries
- Clear History

Retention and loading behavior:

- The latest 1000 entries are retained.
- Older entries are removed automatically.
- Burp project files and exported history may contain sensitive request and response data.
- If the original request still exists, Load in Workbench restores the snapshot into that request.
- If the original request no longer exists, API Workbench creates or reuses `History Replays`.

For details, see [Replay History Guide](docs/replay-history.md).

## Project Architecture

- `src/main/java/burp/` contains the extension entry point and core feature packages.
- `src/test/java/burp/` mirrors the production code with focused tests.

```text
burp/
|-- auth/      OAuth2 token acquisition, refresh, and storage
|-- exporter/  Collection, environment, and history export
|-- history/   Replay history capture, compare, and export
|-- parser/    Postman, OpenAPI, Insomnia, Bruno, and HAR importers
|-- runner/    Sequential collection runner
|-- ui/        Workbench, environments, OAuth2, runner, and history panels
`-- utils/     Shared request-building, variable-resolution, and workspace helpers
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
