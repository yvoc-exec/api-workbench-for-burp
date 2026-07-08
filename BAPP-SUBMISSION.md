# BApp Submission Notes

## Proposed BApp name

API Workbench for Burp

## One-line summary

A Burp-native API workspace for importing, editing, running, scripting, and evidencing API collections from Postman, OpenAPI, Insomnia, Bruno, HAR, and native Workbench formats.

## Description

API Workbench for Burp adds an API-focused workspace inside Burp Suite. It imports common API collection formats, lets testers inspect and edit requests, resolve variables and auth, execute requests through Burp networking, capture History evidence, and export collections or evidence for reporting.

## Key features

- Import Postman, OpenAPI, Insomnia, Bruno, HAR, and native API Workbench files.
- Edit requests, headers, bodies, auth, variables, and collection structure.
- Manage environments and OAuth2 client-credentials token acquisition.
- Run selected requests with preflight checks, retry policy, cancellation, and result capture.
- Preserve imported scripts and execute supported script behavior in a bounded sandbox.
- Review unsupported script capabilities before execution.
- Store History evidence with notes, tags, retention limits, compare view, and export.
- Configure redirect policy for cross-origin credential handling.
- Use a deterministic local HTTP/OAuth2 fixture for manual QA.

## Setup requirements

- Java 17+.
- Burp Suite with the Montoya extension API available.
- The built JAR from `release-artifacts/current/api-workbench-for-burp-local-validated.jar`.
- Optional: local manual QA fixture in `manual-qa/local-httpbin/` for deterministic smoke testing.

## How to use

1. Load the JAR as a Java extension in Burp Suite.
2. Open the **API Workbench** tab.
3. Import a supported collection/specification or use the local fixture collection.
4. Select or create an environment; configure OAuth2 if needed.
5. Review imported scripts before enabling or trusting them.
6. Send individual requests from Workbench or queue selected requests in Runner.
7. Review History, diagnostics, and evidence exports before sharing.

## Security and privacy notes

- API Workbench handles raw requests/responses and may store secrets, tokens, cookies, and client data.
- History, diagnostics, workspace files, and exports may contain secrets, tokens, cookies, and client data.
- Imported scripts should be reviewed before execution.
- OAuth2 token requests use Burp/Montoya networking during production extension use.
- Diagnostics and evidence bundles are not guaranteed secret-safe; review before sharing.

## Limitations

- API Workbench does not claim byte-for-byte runtime parity with Postman, Insomnia, or Bruno.
- Cross-format script export preserves scripts and maps phase where possible, but does not translate tool-specific scripting APIs.
- History and exports may contain secrets, tokens, cookies, and client data.
- Imported scripts should be reviewed before execution.
- Public endpoint smoke tests are optional; deterministic local smoke fixture is preferred.

## Validation status

- Local Maven verification is required before publishing artifacts.
- Canonical local artifact: `release-artifacts/current/api-workbench-for-burp-local-validated.jar`.
- Validation report: `release-artifacts/current/validation-report.txt`.
- Local OAuth2 fixture documents `POST /oauth/token`, `GET /oauth/protected`, and `AWB OAuth Protected Resource`.

## Screenshot checklist

- API Workbench import/workspace view.
- Environment and OAuth2 configuration.
- Runner preview/results.
- History evidence details and compare view.
- Diagnostics view.
- Script trust review dialog for imported scripts.

## BApp readiness checklist

- [ ] Java 17+ requirement documented.
- [ ] Burp/Montoya dependency documented.
- [ ] Security reporting guidance present in `SECURITY.md`.
- [ ] Current release candidate notes present in `CHANGELOG.md`.
- [ ] Local validation report and artifact checksum updated.
- [ ] Manual Burp UI smoke completed.
- [ ] Screenshots captured for BApp submission.
- [ ] Maintainer reviewed History/diagnostic/export privacy notes.
- [ ] PortSwigger submission performed manually by maintainer.

## License

License: MIT. See `LICENSE`.
