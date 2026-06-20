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

## Replay history validation

Replay History is covered by focused tests for the store, retention policy, persistence, Workbench capture, Runner capture, replay/load actions, filters, compare, and export formatting.

Useful targeted cases include:

- `HistoryStoreTest`
- `HistoryRetentionPolicyTest`
- `HistorySanitizerTest`
- `HistoryPersistenceServiceTest`
- `WorkbenchHistoryCaptureTest`
- `RunnerHistoryCaptureTest`
- `HistoryReplayActionTest`
- `HistoryLoadInWorkbenchActionTest`
- `HistoryReplaysCollectionTest`
- `HistorySendToRepeaterActionTest`
- `HistoryFiltersTest`
- `HistoryDiffServiceTest`
- `HistoryExportServiceTest`
- `HistoryJsonExportServiceTest`
- `HistoryCsvExportServiceTest`
- `HistoryHarExportServiceTest`
- `HistoryPanelTest`

When your Burp setup supports saving and reopening a project, confirm that the History tab restores from workspace data as part of manual validation.

## Live Burp validation boundary

Live Burp validation is external/manual. The production extension no longer embeds an Automated Tester runtime hook.

If you use the separate `Automated Tester for API Workbench` repository, treat it as external tooling with its own instructions and reports rather than as a built-in extension startup path.

## Community-only testing limits

Manual live-Burp validation can still be done with Burp Suite Community Edition.

It intentionally does not try to automate full mouse-driven drag/drop or other purely visual interactions.

## Manual checks still needed

Manual review remains useful for:

- Visual drag/drop confirmation.
- Confirmation of state persistence after complex UI changes.
- Checking that generated evidence and logs are safe to share.
- Following up on findings from manual live-Burp validation or external harness runs.
