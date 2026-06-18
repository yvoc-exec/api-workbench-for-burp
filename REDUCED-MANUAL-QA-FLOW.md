# Reduced Manual QA Flow v3

This is the condensed manual QA path for API Workbench stabilization checks.

## 1) Startup / GraalJS
- Launch the extension on Java 25.
- Confirm Script mode is Full and the engine is GraalJS.

## 2) Environment precedence
- Load a collection variable: `base_url = https://jsonplaceholder.typicode.com`
- Load an active environment variable: `base_url = https://httpbin.org`
- Confirm the active environment wins in Workbench Send, Runner, hover, and preview.

## 3) Variable UX
- Verify variable coloring and hover in URL, Headers, and Body fields.
- Confirm hover actions stay clickable long enough to use Edit/Create/View.

## 4) Multi-format import sanity
- Import Native, Postman, Insomnia, Bruno folder, Bruno ZIP, OpenAPI, and HAR.
- Confirm imports retain scripts, headers, and auth metadata.

## 5) Workbench send scripts
- Run one representative pre-request mutation per supported dialect.
- Confirm the final sent request is resolved, not template-only.

## 6) History evidence
- Confirm raw request capture, script output, assertions, and extracted variables.

## 7) Runner flow
- Run a dependent request and confirm child execution, flow control, and captured history.
- Treat remote 5xx as external instability if the request was built and sent correctly.

## 8) Export / round-trip
- Export one collection and one environment.
- Confirm runtime tokens are excluded unless explicitly supported.

## 9) Keyboard shortcuts and diagnostics
- Ctrl+C / Ctrl+V / Ctrl+A works in text areas and relevant tables.
- Ctrl+S saves Environment edits.
- Diagnostics capture defaults OFF and can be toggled ON/OFF per workspace.

## 10) Workbench detail panel
- Confirm Request / Response / Meta / Script Output / Assertions / Extractions tabs are visible.
- Confirm Meta aligns with History metadata.

## Notes
- If a test depends on a public endpoint, prefer a stable 200/204 endpoint or validate raw request/flow evidence instead of a fragile body assertion.
- Bruno ZIP import is supported and should parse like a Bruno folder import.
