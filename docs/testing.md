# Testing Guide

This page summarizes the recommended validation flow for API Workbench for Burp.

## Extension build validation

Run the normal Maven checks in the API Workbench repo:

```powershell
mvn test
mvn clean package
```

Expected output:

- `mvn test` passes.
- `mvn clean package` passes.
- The shaded artifact is written to `target\api-workbench-for-burp-2.0.0-jar-with-dependencies.jar`.

## Focused tests

When present, focused tests for startup, request-tree state, drag/drop, runner behavior, or DataFlavor regressions can be run individually with Maven's `-Dtest=...` switch.

## Runtime smoke tester repo

The separate `Automated Tester for API Workbench` repository is the script-driven runtime smoke harness.

Primary command from the tester repo root:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\invoke-runtime-smoke.ps1
```

The tester produces JSON, Markdown, evidence-snapshot, and log-scan reports. Those reports are the source of truth for the smoke workflow.

## Community-only testing limits

The local smoke workflow is designed around Burp Suite Community Edition.

It intentionally skips Burp Pro/project restore validation and does not try to automate full mouse-driven drag/drop or other purely visual interactions.

## Manual checks still needed

Manual review remains useful for:

- Visual drag/drop confirmation.
- Confirmation of state persistence after complex UI changes.
- Checking that generated evidence and logs are safe to share.
- Following up on items listed in the smoke report's manual checklist.
