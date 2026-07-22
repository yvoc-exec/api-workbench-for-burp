# Changelog

## v2.0.1 release candidate

### Added

- Multi-format import/export for Postman, OpenAPI, Insomnia, Bruno, HAR, and native API Workbench collections.
- Workbench request editing, variable resolution, auth inheritance, exact/raw request handling, and collection export.
- Environment and OAuth2 support, including client credentials token acquisition and output binding.
- Collection Runner execution with retries, preflight checks, redirect policy handling, cancellation, and result capture.
- Imported script preservation and bounded sandboxed execution with unsupported capability reporting.
- History, evidence metadata, evidence bundle export, retention controls, and request/response comparison.
- Diagnostics for import, runner, OAuth2, redirect, script, and history workflows.
- Redirect policy controls for cross-origin credentials and trusted redirects.
- Deterministic OAuth2/request-response smoke validation coverage.
- Checked-in multi-format fidelity fixtures for Postman v2.0/v2.1, Bruno, Insomnia, OpenAPI 3.1, Swagger 2.0, HAR 1.2, and native API Workbench collections.
- Automated import → native save/reload → export → re-import → final request construction verification.
- A public collection import/export fidelity matrix and pending manual Burp smoke checklist.

### Changed

- Runner responses are added to Burp Site map only when the independent **Add responses to Burp Site map** option is enabled; the safe default is off because retries and large responses can substantially increase project size. Disabling it does not compact existing projects.
- OAuth2 token acquisition in production uses Burp/Montoya networking.
- Dialog parenting improved for Burp UI integration.
- Header Enabled checkbox exposes preserved-but-not-sent header state.
- Cross-format script export warning clarifies scripts are preserved but tool-specific APIs are not translated.
- Public repo cleanup removed maintainer-only submission drafts and repo-local manual QA fixtures.
- Collection fidelity guarantees now distinguish preserved, normalized, retained-only, warned-and-omitted, unsupported, and manually pending behavior.

### Security

- Imported scripts require review before trust.
- Bounded sandbox behavior and adversarial script tests cover unsupported and hostile script behavior.
- Diagnostics, History, exports, evidence bundles, and screenshots may contain sensitive data and must be reviewed before sharing.
- Redirect handling includes a sensitive-header policy.
- OAuth2 token acquisition uses Burp networking in production extension use.

### Known limitations

- No byte-for-byte runtime parity with Postman, Insomnia, or Bruno.
- Cross-format script export does not translate tool-specific scripting APIs.
- Public smoke endpoints are optional; deterministic local validation is preferred.
- History, workspace state, diagnostics, exports, and evidence bundles may contain sensitive data and must be reviewed before sharing.
- Automated lifecycle validation does not replace live Burp UI, Send, Repeater, Runner, or History smoke testing.
