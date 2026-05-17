# Runner Operator Experience Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Upgrade the Collection Runner into a more operator-friendly execution console with preview, control, stop conditions, timeline visibility, and retry transparency.

**Architecture:** Keep `CollectionRunner` as the execution engine and `ImporterPanel` as the Swing UI owner, but introduce small focused model types for run preview, timeline rows, stop-condition settings, and run control state. Preserve existing sequential execution semantics and collection-scoped variable behavior while adding pause/resume/step controls around the existing worker loop.

**Tech Stack:** Java 17, Maven, JUnit 5, AssertJ, Mockito, Swing, Burp Montoya API.

---

## Locked Scope

This plan intentionally covers only Collection Runner operator experience. OAuth, Variables, and Workbench improvements are out of scope until separately approved.

### Required Runner Capabilities

- Run preview before start:
  `# | Collection | Method | URL preview | Unresolved vars | Auth status`
- Pause/resume/step:
  pause after current request, resume, run next request only.
- Stop conditions:
  stop on assertion failure, stop on status >= 400, stop when variable missing, stop after N failures.
- Runner timeline:
  `# | Collection | Request | Status | Time | Retries | Vars changed | Assertions`
- Retry visibility:
  log `Attempt 1/3 failed: timeout`, `Retrying in 400ms`, `Attempt 2/3 passed`.

---

## Proposed File Structure

**Modify:**
- `src/main/java/burp/runner/CollectionRunner.java`
  Owns execution loop, pause/resume/step state, stop-condition enforcement, retry event emission, and timeline event data.
- `src/main/java/burp/ui/ImporterPanel.java`
  Owns Runner tab controls, preview dialog/table, timeline table wiring, and status/log presentation.
- `src/main/java/burp/ui/RunnerResultTableModel.java`
  Either extend for timeline columns or leave as-is and add a separate timeline model.
- `src/main/java/burp/models/RunnerResult.java`
  Add fields for retry count, vars changed count/summary, assertion summary, source collection, and stop reason if needed.

**Create:**
- `src/main/java/burp/models/RunnerPreviewRow.java`
  Immutable-ish row model for preview table.
- `src/main/java/burp/models/RunnerStopConditions.java`
  Settings for assertion/status/missing-variable/failure-count stop rules.
- `src/main/java/burp/models/RunnerTimelineRow.java`
  Display model for operator timeline.
- `src/main/java/burp/ui/RunnerPreviewTableModel.java`
  Swing table model for pre-run preview.
- `src/main/java/burp/ui/RunnerTimelineTableModel.java`
  Swing table model for run timeline if `RunnerResultTableModel` is not extended.

**Test:**
- `src/test/java/burp/runner/CollectionRunnerControlTest.java`
- `src/test/java/burp/runner/CollectionRunnerStopConditionsTest.java`
- `src/test/java/burp/runner/CollectionRunnerPreviewTest.java`
- Extend `src/test/java/burp/runner/CollectionRunnerTest.java` only for shared helpers/regression coverage.

---

## Task 1: Run Preview Model And Builder

**Files:**
- Create: `src/main/java/burp/models/RunnerPreviewRow.java`
- Modify: `src/main/java/burp/runner/CollectionRunner.java`
- Test: `src/test/java/burp/runner/CollectionRunnerPreviewTest.java`

- [ ] Step 1: Create `RunnerPreviewRow`.

```java
package burp.models;

import java.util.ArrayList;
import java.util.List;

public class RunnerPreviewRow {
    public int order;
    public String collectionName;
    public String requestName;
    public String method;
    public String urlPreview;
    public List<String> unresolvedVariables = new ArrayList<>();
    public String authStatus;
}
```

- [ ] Step 2: Add preview builder API to `CollectionRunner`.

```java
public List<RunnerPreviewRow> buildRunPreview(List<ApiCollection> sourceCollections, List<ApiRequest> selectedRequests) {
    // Sort with the same ordering used by runCollections().
    // Resolve collection by identity map first, sourceCollection name fallback second.
    // Use VariableResolver seeded with collection environment, collection variables,
    // runtimeOAuth2, runtimeVars, and request variables.
    // Do not execute scripts and do not send requests.
}
```

- [ ] Step 3: Add tests.

Run:

```bash
mvn -q -Dtest=CollectionRunnerPreviewTest test
```

Expected: preview rows include collection name, sorted order, method, resolved URL preview where possible, unresolved variable names, and auth status.

---

## Task 2: Run Preview UI

**Files:**
- Create: `src/main/java/burp/ui/RunnerPreviewTableModel.java`
- Modify: `src/main/java/burp/ui/ImporterPanel.java`

- [ ] Step 1: Add preview table model with columns:

```text
# | Collection | Method | URL Preview | Unresolved Vars | Auth
```

- [ ] Step 2: Add a `Preview Run` button near `Start Collection Runner`.

- [ ] Step 3: Before `startRunner()`, build preview rows and show a modal dialog when unresolved variables or missing auth are present.

- [ ] Step 4: Allow the operator to continue or cancel from the preview dialog.

Manual smoke:

```text
Load collection with missing {{baseUrl}} -> Preview Run -> unresolved var visible -> Cancel prevents run.
```

---

## Task 3: Pause, Resume, And Step Control

**Files:**
- Modify: `src/main/java/burp/runner/CollectionRunner.java`
- Modify: `src/main/java/burp/ui/ImporterPanel.java`
- Test: `src/test/java/burp/runner/CollectionRunnerControlTest.java`

- [ ] Step 1: Add runner control state.

```java
private final Object pauseLock = new Object();
private volatile boolean pauseRequested = false;
private volatile boolean singleStepRequested = false;

public void pauseAfterCurrent() { pauseRequested = true; }
public void resume() {
    synchronized (pauseLock) {
        pauseRequested = false;
        singleStepRequested = false;
        pauseLock.notifyAll();
    }
}
public void runNextOnly() {
    synchronized (pauseLock) {
        singleStepRequested = true;
        pauseRequested = false;
        pauseLock.notifyAll();
    }
}
public boolean isPaused() { return pauseRequested; }
```

- [ ] Step 2: Gate the execution loop between requests.

```java
private boolean waitIfPausedOrStepConsumed() throws InterruptedException {
    synchronized (pauseLock) {
        while (!cancelled && pauseRequested && !singleStepRequested) {
            pauseLock.wait();
        }
        if (cancelled) return false;
        if (singleStepRequested) {
            singleStepRequested = false;
            pauseRequested = true;
        }
        return true;
    }
}
```

- [ ] Step 3: Add UI buttons:

```text
Pause | Resume | Step
```

- [ ] Step 4: Add tests proving pause occurs after the current request, resume continues, and step runs exactly one queued request then pauses again.

Run:

```bash
mvn -q -Dtest=CollectionRunnerControlTest test
```

Expected: PASS.

---

## Task 4: Stop Condition Settings

**Files:**
- Create: `src/main/java/burp/models/RunnerStopConditions.java`
- Modify: `src/main/java/burp/runner/CollectionRunner.java`
- Modify: `src/main/java/burp/ui/ImporterPanel.java`
- Test: `src/test/java/burp/runner/CollectionRunnerStopConditionsTest.java`

- [ ] Step 1: Add stop condition model.

```java
package burp.models;

public class RunnerStopConditions {
    public boolean stopOnError;
    public boolean stopOnAssertionFailure;
    public boolean stopOnStatusAtLeast400;
    public boolean stopOnMissingVariable;
    public int stopAfterFailureCount;
}
```

- [ ] Step 2: Replace/bridge existing `setStopOnError(boolean)` into `RunnerStopConditions.stopOnError`.

- [ ] Step 3: Enforce conditions after each request result.

Rules:

```text
Assertion failure: any assertion with passed == false.
Status >= 400: result.success == true and result.statusCode >= 400.
Missing variable: preview/build stage detects unresolved variables for the current request.
Failure count: failed result count reaches configured threshold where threshold > 0.
```

- [ ] Step 4: Add UI controls:

```text
[ ] Stop on error
[ ] Stop on assertion failure
[ ] Stop on status >= 400
[ ] Stop when variable missing
Stop after failures: [spinner 0..100]
```

- [ ] Step 5: Add tests for each stop condition.

Run:

```bash
mvn -q -Dtest=CollectionRunnerStopConditionsTest test
```

Expected: PASS.

---

## Task 5: Runner Timeline

**Files:**
- Create: `src/main/java/burp/models/RunnerTimelineRow.java`
- Create: `src/main/java/burp/ui/RunnerTimelineTableModel.java`
- Modify: `src/main/java/burp/runner/CollectionRunner.java`
- Modify: `src/main/java/burp/ui/ImporterPanel.java`
- Test: `src/test/java/burp/runner/CollectionRunnerTest.java`

- [ ] Step 1: Create timeline row.

```java
package burp.models;

public class RunnerTimelineRow {
    public int order;
    public String collectionName;
    public String requestName;
    public String status;
    public long timeMs;
    public int retries;
    public int varsChanged;
    public String assertions;
}
```

- [ ] Step 2: Add listener callback.

```java
default void onTimeline(RunnerTimelineRow row) { }
```

- [ ] Step 3: Populate row after each completed request.

Use:

```text
varsChanged = result.extractedVariables.size()
assertions = passedCount + "/" + totalCount
retries = attempts - 1
```

- [ ] Step 4: Add timeline table to Runner tab.

Columns:

```text
# | Collection | Request | Status | Time | Retries | Vars changed | Assertions
```

- [ ] Step 5: Tests verify timeline rows include retry count, variable count, and assertion summary.

Run:

```bash
mvn -q -Dtest=CollectionRunnerTest test
```

Expected: PASS.

---

## Task 6: Retry Visibility

**Files:**
- Modify: `src/main/java/burp/runner/CollectionRunner.java`
- Modify: `src/main/java/burp/ui/ImporterPanel.java`
- Test: `src/test/java/burp/runner/CollectionRunnerTest.java`

- [ ] Step 1: Add retry debug messages from `executeRequest()`.

Required message format:

```text
Attempt 1/3 failed: timeout
Retrying in 400ms
Attempt 2/3 passed
```

- [ ] Step 2: Make retry delay calculation explicit.

```java
private int retryDelayMs(int attemptNumber) {
    return Math.max(0, delayMs * attemptNumber);
}
```

- [ ] Step 3: Emit passed message after final success when attempts > 1.

- [ ] Step 4: Add tests that attach a listener and assert retry messages are emitted in order.

Run:

```bash
mvn -q -Dtest=CollectionRunnerTest test
```

Expected: PASS.

---

## Final Verification

- [ ] Run targeted tests:

```bash
mvn -q -Dtest=CollectionRunnerTest,CollectionRunnerControlTest,CollectionRunnerStopConditionsTest,CollectionRunnerPreviewTest test
```

Expected: PASS.

- [ ] Run full tests:

```bash
mvn -q test
```

Expected: PASS.

- [ ] Run package build:

```bash
mvn -q -DskipTests package
```

Expected: `target/universal-api-importer-2.0.0-jar-with-dependencies.jar` is produced.

---

## Non-Goals

- Do not change OAuth token acquisition UX in this plan.
- Do not change the Variables tab in this plan.
- Do not change Workbench send/history UX in this plan.
- Do not add parallel request execution.
- Do not persist runner history to disk unless separately approved.
