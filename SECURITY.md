# Security Policy

## Reporting security issues

Do not include real client secrets, tokens, cookies, private API data, or exploit details in public issues.

Use GitHub private vulnerability reporting if enabled. If unavailable, contact the maintainer through the repository before publishing exploit details.

## Supported versions

| Version | Supported |
| --- | --- |
| v2.0.0 release candidate | Yes |
| Older development snapshots | No |

## Sensitive data handling

API Workbench handles raw HTTP requests and responses and may store sensitive data in workspace state, History, diagnostics, exports, evidence bundles, and screenshots.

Review diagnostics, History exports, evidence bundles, screenshots, and workspace files before sharing them.

Imported scripts should be treated as untrusted until reviewed. Keep imported scripts disabled unless you understand their behavior and destination changes.

## Script safety

Imported scripts can mutate requests, variables, and runtime state within the supported sandbox. Only enable scripts after review.

Unsupported or hostile script behavior should fail closed or be reported as a security issue.

## Disclosure expectations

Provide reproduction steps, the affected version or commit, and a short impact summary when reporting a security issue.

Avoid public disclosure until the maintainer has had a reasonable opportunity to investigate.
