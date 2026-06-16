# Replay History

Replay History adds a History tab to API Workbench for Burp so you can inspect, compare, replay, and export prior executions without leaving the extension.

## What it records

History captures only these sources:

- Workbench direct Send
- Collection Runner executions, including each retry attempt

It does not capture:

- Repeater sends
- Burp Proxy traffic
- Import-to-Sitemap live sends
- Manual request draft edits
- Scanner, Collaborator, or OAST traffic

Each row stores the request in authored/template form, plus execution metadata such as result, status, duration, size, environment, unresolved variables, assertions, and extractions.

## Retention and persistence

- History keeps the latest 1000 entries.
- Older entries are automatically removed first.
- History is persisted in Burp project extension data and restores with the workspace state when the project is reopened.
- If the stored data is missing or malformed, API Workbench falls back to an empty history and keeps starting normally.

Treat Burp project files as sensitive because they may contain request and response data from your testing sessions.

## History tab actions

The History tab includes a toolbar, filter controls, a table, and a detail pane with request, response, variables, assertions, extraction, and metadata views.

Supported actions include:

- Load in Workbench
- Replay from History
- Send to Repeater
- Copy URL
- Copy as cURL
- Compare Selected
- Export selected entries as HAR, native History JSON, or CSV
- Delete selected entries
- Clear History

If the original request no longer exists, loading a row creates or reuses a `History Replays` collection and loads the snapshot there.

## Export notes

- HAR export uses templated values only.
- Native History JSON keeps the most complete fidelity for the stored snapshot and metadata.
- CSV export is summary oriented.
- Exported history may contain sensitive request or response data, so review it before sharing.

## Security and project persistence

History is a local, project-scoped record. It is not a secret store and it does not redact values by default.

- Avoid real secrets in requests, responses, and test fixtures when possible.
- Review generated evidence and exports before sharing them externally.
- Replay from History uses the normal unresolved-variable flow, so missing values still surface through the existing Workbench behavior.

## Testing notes

Replay History is covered by the unit and integration tests in the API Workbench repo, plus the usual manual UI spot checks in Burp.

For more detail, see [the testing guide](testing.md).
