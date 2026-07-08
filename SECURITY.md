# Security Policy

## Reporting security issues

Do not include real client secrets, tokens, cookies, private API data, or exploit details in public issues.

Use GitHub private vulnerability reporting if enabled. If unavailable, contact the maintainer through the repository before publishing exploit details.

## Sensitive data handling

API Workbench handles raw HTTP requests and responses and may store sensitive data in workspace state, History, diagnostics, exports, and evidence bundles.

Review diagnostics, History exports, evidence bundles, screenshots, and workspace files before sharing them.

Imported scripts should be treated as untrusted until reviewed. Keep imported scripts disabled unless you understand their behavior and destination changes.
