# Code Review Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the highest-risk architecture, flow logic, parser, and code-level gaps found in the May 17, 2026 code review.

**Architecture:** Keep `SharedRequestPipeline` as the single live execution path for Workbench and Runner. Make import request-building share the same resolver/script/OAuth behavior where live execution is involved, keep parser fixes scoped to their parser classes, and add focused regression tests for every behavior changed.

**Tech Stack:** Java 17, Maven, JUnit 5, AssertJ, Mockito, Burp Montoya API, Gson, SnakeYAML, Swing.

---

## Finding Summary By Criticality

### Critical

- [ ] Multipart file uploads can read and transmit arbitrary files under the user's home directory when an imported collection supplies a local path. See `src/main/java/burp/utils/RequestBuilder.java:431-446`.
- [ ] Runner variable extraction leaks across collections through a global `extractedVars` map. See `src/main/java/burp/runner/CollectionRunner.java:167-170`.

### High

- [ ] Import-to-Repeater/Intruder/Sitemap bypasses `SharedRequestPipeline`, so script behavior and runtime persistence differ from Workbench/Runner. See `src/main/java/burp/UniversalImporter.java:300-355`.
- [ ] Runner retry behavior is effectively "attempt count", not "retry count"; default `maxRetries = 1` means no retry despite advertised retry logic. See `src/main/java/burp/runner/CollectionRunner.java:172-245`.
- [ ] Runner "Debug final raw request" UI setting is wired but unused. See `src/main/java/burp/ui/ImporterPanel.java:1438-1441` and `src/main/java/burp/runner/CollectionRunner.java:41-73`.
- [ ] OpenAPI parser ignores query/path parameters and leaves `{id}` style path templates unresolved. See `src/main/java/burp/parser/OpenApiParser.java:159-170`.
- [ ] Bruno parser uses single-brace regexes for bodies, vars, asserts, and scripts, truncating JSON and nested JavaScript. See `src/main/java/burp/parser/BrunoParser.java:161-232`.

### Medium

- [ ] OpenAPI parser claims every `.yaml`/`.yml` file without validating `openapi` or `swagger`. See `src/main/java/burp/parser/OpenApiParser.java:19-21`.
- [ ] Runner cancellation cannot interrupt a sleeping or in-flight request because the executor is local and `cancel()` only flips a flag. See `src/main/java/burp/runner/CollectionRunner.java:118-158`.
- [ ] Runner detail panes store the full raw request as `requestHeaders` and only set `requestBody` for raw bodies, so urlencoded/formdata/OAuth2 generated bodies are missing from structured result data. See `src/main/java/burp/utils/SharedRequestPipeline.java:102-104`.

### Low

- [ ] `RequestBuilder.buildRequest(..., null)` can NPE for header API key auth. See `src/main/java/burp/utils/RequestBuilder.java:219-236`.
- [ ] `BrunoParser.canParse()` opens a `Files.walk` stream without closing it. See `src/main/java/burp/parser/BrunoParser.java:20-23`.

---

## Task 1: Block Unsafe Implicit Local File Reads

**Files:**
- Modify: `src/main/java/burp/models/ApiRequest.java`
- Modify: `src/main/java/burp/utils/RequestBuilder.java`
- Test: `src/test/java/burp/utils/RequestBuilderTest.java`

- [ ] Step 1: Add explicit file metadata to `ApiRequest.Body.FormField`.

Add fields:

```java
public boolean fileUpload;
public String filePath;
```

- [ ] Step 2: Update multipart building to read local files only when `field.fileUpload == true && field.filePath != null`.

Required behavior:

```java
String uploadPath = field.fileUpload ? field.filePath : null;
File file = uploadPath != null ? new File(resolver != null ? resolver.resolve(uploadPath) : uploadPath) : null;
```

Plain text values like `C:\Users\name\.ssh\id_rsa` must be sent as text unless the parser explicitly marked the field as a file upload.

- [ ] Step 3: Add a regression test that a form field value shaped like an absolute path is not read as a file unless `fileUpload` is true.

Run:

```bash
mvn -Dtest=RequestBuilderTest test
```

Expected: PASS.

---

## Task 2: Scope Runner Extracted Variables Per Collection

**Files:**
- Modify: `src/main/java/burp/runner/CollectionRunner.java`
- Test: `src/test/java/burp/runner/CollectionRunnerTest.java`

- [ ] Step 1: Replace global cross-collection propagation with an identity map.

Use:

```java
private final Map<ApiCollection, Map<String, String>> extractedVarsByCollection =
        Collections.synchronizedMap(new IdentityHashMap<>());
```

Keep `getExtractedVariables()` returning an aggregate copy for UI compatibility.

- [ ] Step 2: In `executeRequest`, merge only variables for the current collection.

Required logic:

```java
Map<String, String> scoped = col != null
        ? extractedVarsByCollection.computeIfAbsent(col, c -> new ConcurrentHashMap<>())
        : extractedVars;
if (col != null && !scoped.isEmpty()) {
    col.putAllRuntimeVars(scoped);
}
```

- [ ] Step 3: Add a unit test proving a variable extracted from collection A is not injected into collection B.

Run:

```bash
mvn -Dtest=CollectionRunnerTest test
```

Expected: PASS.

---

## Task 3: Make Import Use Shared Runtime Semantics

**Files:**
- Modify: `src/main/java/burp/UniversalImporter.java`
- Test: add or extend `src/test/java/burp/UniversalImporterSingleSendResultTest.java`

- [ ] Step 1: Extract request preparation into a shared helper that returns raw request, built request, resolved URL, and debug variable sources.

Prefer calling `pipeline.execute(...)` for Sitemap/live send. For Repeater/Intruder, add a non-sending build method to `SharedRequestPipeline` if needed, so pre-request scripts and OAuth mapping are still applied without making a network request.

- [ ] Step 2: Preserve existing behavior for Repeater and Intruder: they should build once and not send.

- [ ] Step 3: Preserve existing behavior for Sitemap: it should send once and annotate successful responses.

- [ ] Step 4: Add tests covering pre-request script variables being present in imported Repeater requests.

Run:

```bash
mvn test
```

Expected: 65+ tests PASS.

---

## Task 4: Fix Runner Retry, Debug, And Cancellation

**Files:**
- Modify: `src/main/java/burp/runner/CollectionRunner.java`
- Modify: `src/main/java/burp/ui/ImporterPanel.java`
- Test: `src/test/java/burp/runner/CollectionRunnerTest.java`

- [ ] Step 1: Rename internal meaning to `maxRetries` as retries after the first attempt.

Loop condition should be:

```java
int maxAttempts = Math.max(1, maxRetries + 1);
while (attempts < maxAttempts && !cancelled) {
```

- [ ] Step 2: Add a "Retries" spinner in `ImporterPanel.createRunnerTab()` and call `runner.setMaxRetries(...)` in `startRunner()`.

- [ ] Step 3: Store the executor in a field and interrupt it in `cancel()`.

Required behavior:

```java
private volatile Future<?> activeFuture;
private volatile ExecutorService activeExecutor;
```

`cancel()` should set `cancelled = true`, cancel the active future, and call `shutdownNow()` on the active executor.

- [ ] Step 4: Emit debug messages when `debugRawRequest` is true.

Include raw request text from `ExecutionResult.requestHeaders` and call `fireOnDebug(...)`.

- [ ] Step 5: Add tests for retry count and cancellation state where possible without live Burp networking.

Run:

```bash
mvn -Dtest=CollectionRunnerTest test
```

Expected: PASS.

---

## Task 5: Fix OpenAPI Parser Parameter Handling And YAML Detection

**Files:**
- Modify: `src/main/java/burp/parser/OpenApiParser.java`
- Test: add `src/test/java/burp/parser/OpenApiParserTest.java`

- [ ] Step 1: Make `canParse()` inspect YAML content and return true only if the top-level map contains `openapi` or `swagger`.

- [ ] Step 2: Convert OpenAPI path variables from `{id}` to `{{id}}` and create request variables with generated example values.

- [ ] Step 3: Append query parameters to `req.url` using generated example values or `{{name}}`.

Example expected URL:

```text
https://api.example.com/users/{{id}}?includeInactive=true
```

- [ ] Step 4: Add tests for YAML non-OpenAPI rejection, path variable conversion, and query parameter generation.

Run:

```bash
mvn -Dtest=OpenApiParserTest test
```

Expected: PASS.

---

## Task 6: Replace Fragile Bruno Regex Block Parsing

**Files:**
- Modify: `src/main/java/burp/parser/BrunoParser.java`
- Test: add `src/test/java/burp/parser/BrunoParserTest.java`

- [ ] Step 1: Reuse `extractBlock(content, blockName)` for `body`, `vars`, `assert`, `script:pre-request`, and `script:post-response`.

- [ ] Step 2: Teach block extraction to support block names containing `:` by quoting the pattern with `Pattern.quote(blockName)`.

- [ ] Step 3: Add a test with a JSON body containing nested braces and a post-response script containing an `if (...) { ... }` block.

Run:

```bash
mvn -Dtest=BrunoParserTest test
```

Expected: PASS.

---

## Task 7: Improve Result Body Metadata And Null Safety

**Files:**
- Modify: `src/main/java/burp/utils/SharedRequestPipeline.java`
- Modify: `src/main/java/burp/utils/RequestBuilder.java`
- Test: `src/test/java/burp/utils/RequestBuilderTest.java`

- [ ] Step 1: Split built raw request into headers and body bytes in `SharedRequestPipeline`.

Set:

```java
result.requestHeaders = headerText;
result.requestBody = bodyText;
```

- [ ] Step 2: Make API key header auth null-safe when resolver is null.

Required pattern:

```java
String resolvedKeyName = resolver != null ? resolver.resolve(keyName) : keyName;
String resolvedKeyValue = resolver != null ? resolver.resolve(keyValue) : keyValue;
if (!headers.has(resolvedKeyName)) {
    headers.putDefault(resolvedKeyName, resolvedKeyValue);
}
```

- [ ] Step 3: Add tests for urlencoded body capture and resolver-null API key auth.

Run:

```bash
mvn -Dtest=RequestBuilderTest test
mvn test
```

Expected: PASS.

---

## Final Verification

- [ ] Run full tests:

```bash
mvn test
```

Expected: `BUILD SUCCESS`.

- [ ] Run package build:

```bash
mvn package
```

Expected: `target/universal-api-importer-2.0.0-jar-with-dependencies.jar` is built successfully.

- [ ] Manual smoke in Burp:
Load extension, import a small Postman collection, import an OpenAPI file with path/query params, run two collections with same variable names, verify no cross-collection leakage, and verify cancel/debug/retry controls work.
