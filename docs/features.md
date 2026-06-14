# Feature Guide

This page groups the main capability areas in API Workbench for Burp.

## Import and export

- API Workbench JSON, the native collection format.
- Postman Collection v2.1 JSON.
- OpenAPI 3.0 JSON and YAML.
- Insomnia JSON.
- Bruno ZIP.
- HAR 1.2 JSON.

## Environments

- API Workbench Environment JSON.
- Postman Environment JSON.
- dotenv `.env`.
- Generic flat JSON.
- Insomnia Environment JSON.
- Bruno environment `.bru`.

Environment handling includes the active environment dropdown, variable resolution, variable shadowing, active-environment switching, and unresolved-variable prompts when resolution is enabled.

## Request workbench

- Edit method, URL, headers, body, and auth in place.
- Send one request directly from the Workbench.
- Send to Burp Repeater for manual tampering.
- Inspect request/response state in the editor and result panes.
- Create manual requests directly in the tree.

## Request tree management

- Create, rename, duplicate, and delete collections, folders, and requests.
- Keep the tree organized without recreating imported collections.
- Preserve collapsed and expanded state across refreshes.

## Drag and drop

- Drop supported collection files onto the request tree.
- Move or reorder request, folder, and collection nodes.
- Drop environment files onto the environment area.
- Drop active environment payloads when switching runtime context.
- Reorder runner queue items.
- Use classloader-safe `DataFlavor` handling for Burp-safe drag/drop payloads.

## Runner

- Queue checked requests.
- Preview the run before execution.
- Reorder the queue.
- Remove selected items.
- Clear the queue.
- Run queued requests sequentially.
- Capture results and failures in the runner view.
- Resolve variables and environments during execution.

## Auth and OAuth2

- Collection, folder, and request auth settings.
- Auth inheritance across scopes.
- OAuth2-backed workflows for token-driven APIs.
- Support for common auth styles used in API testing.

## Variables

- Collection and environment variables.
- Request-level variables where the source format supports them.
- Default values for unresolved placeholders.
- Shadowing and active-environment precedence.
- Clear unresolved-variable handling before send, export, or runner execution.

## Smoke and testing

- Opt-in runtime smoke mode for local QA only.
- Evidence snapshots for startup, tree, environment, and runner phases.
- Log scan reporting for unexpected issues.
- Local tester repo workflow for repeatable validation.
- Manual checks still needed for visual drag/drop confirmation and other UI-focused review steps.
