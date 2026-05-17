# Variables Operator Experience Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Improve variable handling for operators by adding preflight unresolved-variable resolution and runtime variable import/export.

**Architecture:** Add a shared unresolved-variable analysis service that can be called by Workbench send, Import, and Collection Runner before execution. Present missing variables in a modal dialog with quick-entry fields that write values into the selected collection runtime variables, avoiding permanent layout changes. Add JSON import/export actions for collection runtime vars and OAuth runtime vars using existing collection-scoped maps.

**Tech Stack:** Java 17, Maven, JUnit 5, AssertJ, Mockito, Swing, Gson.

---

## Locked Scope

This plan covers only Variables operator experience.

### Required Capabilities

- Before Workbench send, Import, or Collection Runner run, detect unresolved `{{vars}}` and list them with request names.
- Show unresolved variables in a modal popup/preflight dialog, not a permanent panel.
- Provide quick-entry fields in the dialog.
- Apply entered values into the selected/source collection runtime vars.
- Export/import collection runtime variables and OAuth runtime variables as JSON.

### Non-Goals

- Do not redesign the Variables tab layout.
- Do not add a permanent warning panel.
- Do not change OAuth acquisition behavior.
- Do not change Runner preview/timeline controls from the Runner operator-experience plan.
- Do not persist runtime variables automatically without operator action.

---

## Proposed File Structure

**Create:**
- `src/main/java/burp/models/UnresolvedVariableIssue.java`
  Row model for request name, collection name, variable name, and location.
- `src/main/java/burp/utils/UnresolvedVariableAnalyzer.java`
  Shared analyzer for unresolved variables across selected requests.
- `src/main/java/burp/ui/UnresolvedVariablesDialog.java`
  Modal Swing dialog with issue table and quick-entry fields.
- `src/main/java/burp/utils/RuntimeVariablesJson.java`
  JSON serializer/deserializer for runtime variable maps.

**Modify:**
- `src/main/java/burp/ui/ImporterPanel.java`
  Hook preflight dialog before Workbench send, Import, and Runner start; add export/import buttons/actions for runtime vars and OAuth runtime vars.
- `src/main/java/burp/models/ApiCollection.java`
  No new fields expected; use existing `runtimeVars` and `runtimeOAuth2`.

**Test:**
- `src/test/java/burp/utils/UnresolvedVariableAnalyzerTest.java`
- `src/test/java/burp/utils/RuntimeVariablesJsonTest.java`

---

## Task 1: Unresolved Variable Issue Model

**Files:**
- Create: `src/main/java/burp/models/UnresolvedVariableIssue.java`

- [ ] Step 1: Create row model.

```java
package burp.models;

public class UnresolvedVariableIssue {
    public String collectionName;
    public String requestName;
    public String variableName;
    public String location;

    public UnresolvedVariableIssue(String collectionName, String requestName, String variableName, String location) {
        this.collectionName = collectionName;
        this.requestName = requestName;
        this.variableName = variableName;
        this.location = location;
    }
}
```

---

## Task 2: Shared Unresolved Variable Analyzer

**Files:**
- Create: `src/main/java/burp/utils/UnresolvedVariableAnalyzer.java`
- Test: `src/test/java/burp/utils/UnresolvedVariableAnalyzerTest.java`

- [ ] Step 1: Add analyzer API.

```java
public List<UnresolvedVariableIssue> analyze(ApiCollection collection, ApiRequest request)
```

Required behavior:

```text
Seed VariableResolver with:
1. collection.environment
2. collection.variables
3. collection.runtimeOAuth2
4. collection.runtimeVars
5. request.variables

Scan:
- request.url
- header keys
- header values
- raw body
- urlencoded keys/values
- formdata keys/values/filePath
- graphql query/variables
- auth properties

Return one issue per unique variable/request/location.
Ignore tokens that have defaults: {{name|default}}.
```

- [ ] Step 2: Add tests.

Test cases:

```text
url variable missing -> issue has location=url
runtime var present -> no issue
request-level var present -> no issue
header/body/auth variables are detected
default variable token is ignored
```

Run:

```bash
mvn -q -Dtest=UnresolvedVariableAnalyzerTest test
```

Expected: PASS.

---

## Task 3: Modal Preflight Dialog

**Files:**
- Create: `src/main/java/burp/ui/UnresolvedVariablesDialog.java`
- Modify: `src/main/java/burp/ui/ImporterPanel.java`

- [ ] Step 1: Create modal dialog.

Dialog requirements:

```text
Title: Unresolved Variables
Table columns: Collection | Request | Variable | Location
Quick entry area: one text field per unique variable name
Buttons:
- Apply to Runtime Vars
- Continue Without Applying
- Cancel
```

- [ ] Step 2: Apply entered values.

Behavior:

```text
For each variable with a non-empty entered value:
- Write it into the matching source collection runtimeVars.
- If the same variable appears in multiple selected collections, apply the entered value to each affected collection.
- Use ApiCollection.putRuntimeVar(...) or putAllRuntimeVars(...) so change listeners fire.
```

- [ ] Step 3: Return dialog action.

```java
public enum Action {
    APPLY_AND_CONTINUE,
    CONTINUE_WITHOUT_APPLYING,
    CANCEL
}
```

---

## Task 4: Preflight Hooks For Workbench, Import, And Runner

**Files:**
- Modify: `src/main/java/burp/ui/ImporterPanel.java`
- Test: prefer analyzer tests; UI verified manually.

- [ ] Step 1: Before Workbench send, analyze the edited request against its current collection.

Behavior:

```text
If no unresolved variables: send immediately.
If unresolved variables exist: show modal dialog.
Cancel: do not send.
Apply and continue: update runtime vars and send.
Continue without applying: send as-is.
```

- [ ] Step 2: Before Import, analyze selected queued requests grouped by source collection.

Behavior:

```text
If unresolved variables exist: show modal dialog before importRequestsSequential(...).
Cancel prevents import.
Apply and continue updates affected collection runtime vars, then rebuilds/imports.
```

- [ ] Step 3: Before Collection Runner start, analyze selected requests grouped by source collection.

Behavior:

```text
If unresolved variables exist: show modal dialog before runner.runCollections(...).
Cancel prevents run.
Apply and continue updates affected collection runtime vars, then starts runner.
```

Manual smoke:

```text
Load request with {{baseUrl}} unresolved.
Click Send in Workbench.
Dialog appears.
Enter baseUrl=https://example.com.
Apply and continue.
Request sends with resolved baseUrl and collection runtimeVars contains baseUrl.
```

---

## Task 5: Runtime Variables JSON Format

**Files:**
- Create: `src/main/java/burp/utils/RuntimeVariablesJson.java`
- Test: `src/test/java/burp/utils/RuntimeVariablesJsonTest.java`

- [ ] Step 1: Define JSON shape.

```json
{
  "version": 1,
  "collection": "Collection Name",
  "runtimeVars": {
    "baseUrl": "https://api.example.com"
  },
  "runtimeOAuth2": {
    "oauth2_client_id": "client-id"
  }
}
```

- [ ] Step 2: Add serializer/deserializer.

```java
public static String toJson(ApiCollection collection)
public static RuntimeVariableBundle fromJson(String json)
public static class RuntimeVariableBundle {
    public Map<String, String> runtimeVars = new LinkedHashMap<>();
    public Map<String, String> runtimeOAuth2 = new LinkedHashMap<>();
}
```

- [ ] Step 3: Tests.

Run:

```bash
mvn -q -Dtest=RuntimeVariablesJsonTest test
```

Expected: JSON round trip preserves runtime vars and OAuth runtime vars; missing maps deserialize to empty maps.

---

## Task 6: Export/Import Runtime Variables UI

**Files:**
- Modify: `src/main/java/burp/ui/ImporterPanel.java`

- [ ] Step 1: Add buttons/actions to the Variables or OAuth/Variables area:

```text
Export Runtime JSON
Import Runtime JSON
```

- [ ] Step 2: Export selected collection.

Behavior:

```text
Require selected collection.
Use JFileChooser save dialog.
Write RuntimeVariablesJson.toJson(collection) as UTF-8.
Log: Exported runtime variables for <collection>.
```

- [ ] Step 3: Import into selected collection.

Behavior:

```text
Require selected collection.
Use JFileChooser open dialog.
Parse JSON.
Show confirmation before replacing/merging.
Default behavior: merge imported runtimeVars and runtimeOAuth2 into selected collection.
Use putAllRuntimeVars(...) and putAllRuntimeOAuth2(...).
Refresh variable/OAuth UI after import.
```

Manual smoke:

```text
Set runtime var baseUrl.
Export JSON.
Clear runtime vars.
Import JSON.
baseUrl reappears and request resolution uses it.
```

---

## Final Verification

- [ ] Run targeted tests:

```bash
mvn -q -Dtest=UnresolvedVariableAnalyzerTest,RuntimeVariablesJsonTest test
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
