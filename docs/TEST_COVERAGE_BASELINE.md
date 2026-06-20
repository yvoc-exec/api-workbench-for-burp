# Test Coverage Baseline

## Scope and measurement context

- Repository: `yvoc-exec/api-workbench-for-burp`
- Branch: `feature/script-engine-rebuild`
- Measured commit: `f10d71c` (`ci: harden java matrix build workflow`)
- Measurement date: 2026-06-19
- Local toolchain used for baseline capture:
  - Java: Temurin 17.0.19+10 (repo-local `.tools/jdk17`)
  - Maven: 3.9.9 (repo-local `.tools/maven`)
  - JaCoCo: 0.8.15
- Commands used during this pass:
  - `mvn -B clean test`
  - `mvn -B clean verify`
  - `mvn -B clean package`
  - `java -cp target/*-jar-with-dependencies.jar burp.smoke.ScriptRuntimeProbe --require-full`
  - `mvn -B jacoco:report` (run after `clean package` only to regenerate the already-collected report files)

## Repository inventory

| Inventory item | Count | Notes |
|---|---:|---|
| Production Java source files | 174 | `src/main/java` |
| Test/support Java source files | 100 | `src/test/java`, includes 3 `testsupport` helpers |
| JaCoCo-analyzed compiled classes | 336 | Includes nested/inner classes |
| Test classes with executable test methods | 97 | Excludes fixture/support-only source files |
| Executed tests | 693 | From Surefire summary |
| Test failures | 0 | Final post-change validation runs |
| Test errors | 0 | Final post-change validation runs |
| Skipped tests | 0 | Final post-change validation runs |

## Build and runtime baseline facts

| Fact | Value |
|---|---|
| Maven version | 3.9.9 |
| Java version | 17.0.19 |
| Shaded JAR path | `target/api-workbench-for-burp-2.0.0-jar-with-dependencies.jar` |
| Shaded JAR size | 41,701,144 bytes |
| ScriptRuntimeProbe result | `Script engine: GraalJS`, `Graal available: true`, `Nashorn fallback available: false`, `Evaluation result: 2` |
| JaCoCo HTML report | `target/site/jacoco/index.html` |
| JaCoCo XML report | `target/site/jacoco/jacoco.xml` |
| JaCoCo CSV report | `target/site/jacoco/jacoco.csv` |

## Overall coverage

| Counter | Covered | Total | Coverage |
|---|---:|---:|---:|
| Line | 16,842 | 27,887 | 60.4% |
| Branch | 9,693 | 22,707 | 42.7% |
| Method | 2,031 | 3,185 | 63.8% |
| Class | 278 | 336 | 82.7% |

## Package coverage

| Package | Line | Branch | Method | Class |
|---|---:|---:|---:|---:|
| `burp` | 46.4% | 27.5% | 58.0% | 100.0% |
| `burp/auth` | 24.4% | 14.6% | 36.0% | 70.0% |
| `burp/diagnostics` | 76.3% | 44.2% | 85.7% | 100.0% |
| `burp/exporter` | 81.0% | 56.9% | 88.7% | 96.6% |
| `burp/history` | 74.4% | 51.3% | 85.1% | 100.0% |
| `burp/models` | 84.2% | 57.4% | 91.5% | 100.0% |
| `burp/parser` | 72.2% | 50.9% | 86.7% | 100.0% |
| `burp/runner` | 81.0% | 58.3% | 90.0% | 100.0% |
| `burp/scripts` | 58.6% | 37.6% | 53.8% | 92.5% |
| `burp/smoke` | 1.3% | 0.7% | 2.1% | 15.0% |
| `burp/ui` | 62.0% | 42.5% | 63.8% | 75.9% |
| `burp/ui/dnd` | 60.1% | 46.0% | 58.3% | 75.0% |
| `burp/ui/history` | 67.6% | 41.7% | 61.0% | 92.9% |
| `burp/ui/tree` | 65.8% | 49.1% | 77.4% | 86.7% |
| `burp/utils` | 71.3% | 53.2% | 78.2% | 82.6% |

## Zero-coverage production source files

These source files had `0` covered lines in the measured run.

- `burp.auth.ClientCredentialsHandler` — 82 lines, 76 branches missed
- `burp.auth.PasswordGrantHandler` — 12 lines, 6 branches missed
- `burp.auth.RefreshTokenHandler` — 9 lines, 2 branches missed
- `burp.exporter.ExportException` — 4 lines, 0 branches missed
- `burp.ui.AuthSettingsDialog` — 121 lines, 55 branches missed
- `burp.ui.BearerTokenAliasDialog` — 91 lines, 48 branches missed
- `burp.ui.history.HistoryCompareDialog` — 19 lines, 2 branches missed
- `burp.ui.RequestPreviewTableModel` — 41 lines, 35 branches missed
- `burp.ui.ResponsePane` — 55 lines, 26 branches missed
- `burp.ui.RunnerPreviewTableModel` — 30 lines, 29 branches missed
- `burp.ui.tree.CheckBoxTreeCellRenderer` — 28 lines, 12 branches missed
- `burp.ui.UnresolvedVariablesDialog` — 139 lines, 48 branches missed
- `burp.utils.RequestDebugFormatter` — 53 lines, 18 branches missed
- `burp.utils.VariableDebugFormatter` — 30 lines, 20 branches missed

## Critical classes below 50% branch coverage

These are not every low-branch file. They are the behaviorally important ones that remain below 50% branch coverage after the current test suite.

| Class | Branch | Line | Why this matters |
|---|---:|---:|---|
| `burp.ui.ImporterPanel` | 44.5% | 63.5% | Central Workbench/Runner/History/Environment composition surface |
| `burp.ui.RequestEditorPanel` | 42.9% | 68.0% | Core authored request editing and resolved preview behavior |
| `burp.scripts.ScriptBindingsFactory` | 30.6% | 48.8% | Dialect API exposure and script context shaping |
| `burp.parser.OpenApiParser` | 38.6% | 58.3% | One of the major import formats; malformed/edge-path gaps remain |
| `burp.exporter.CollectionExportSupport` | 42.6% | 56.7% | Shared export semantics and variable-resolution behavior |
| `burp.utils.ScriptEngine` | 20.5% | 35.3% | Legacy/new script execution compatibility surface |
| `burp.UniversalImporter` | 30.1% | 49.5% | High-level send/import workspace orchestration |
| `burp.history.HistoryRequestSnapshot` | 31.6% | 46.5% | Raw/authored request persistence and replay fidelity |
| `burp.ui.RequestEditorStateMapper` | 46.9% | 72.1% | UI <-> model mapping safety for request editing |
| `burp.ui.history.HistoryDetailPanel` | 48.2% | 73.5% | Detailed history rendering path |
| `burp.ui.history.HistoryNativeHttpMessageFactory` | 33.6% | 51.1% | Native Burp message conversion for history viewers |
| `burp.utils.ScriptModeDetector` | 20.0% | 33.3% | Runtime-mode detection / fallback labeling |
| `burp.auth.OAuth2Manager` | 10.3% | 29.9% | Token acquisition/refresh orchestration |
| `burp.BurpExtender` | 20.7% | 41.5% | Startup wiring and failure handling |
| `burp.diagnostics.DiagnosticEvent` | 5.0% | 36.6% | Diagnostic serialization/data-shaping is mostly incidental today |

## Top 20 risk-ranked gaps

Ranking is evidence-based from missed-branch and missed-line totals, then filtered by behavioral relevance to the project source-of-truth docs.

| Rank | Class | Branch | Line | Why it is risky |
|---:|---|---:|---:|---|
| 1 | `burp.ui.ImporterPanel` | 44.5% | 63.5% | Largest remaining uncovered orchestration surface across Workbench, Runner, History, Environment, OAuth2, and diagnostics. |
| 3 | `burp.ui.RequestEditorPanel` | 42.9% | 68.0% | Strong existing tests, but many branch combinations in editing/materialization/resolved preview still unmeasured. |
| 4 | `burp.scripts.ScriptBindingsFactory` | 30.6% | 48.8% | Dialect bindings are central to the multi-format script goal but still under-covered. |
| 5 | `burp.runner.CollectionRunner` | 58.3% | 81.0% | Good line coverage, but branch space remains large for flow control, retries, queue/state transitions. |
| 6 | `burp.ui.tree.RequestTreeMutationService` | 58.6% | 80.3% | Important request-tree behavior is mostly covered, but complex move/rename edge cases still dominate missed branches. |
| 7 | `burp.parser.OpenApiParser` | 38.6% | 58.3% | OpenAPI import coverage exists, but malformed matrices and schema edge cases remain weak. |
| 8 | `burp.exporter.CollectionExportSupport` | 42.6% | 56.7% | Shared export semantics affect multiple formats; low branch coverage makes regressions expensive. |
| 9 | `burp.utils.ScriptEngine` | 20.5% | 35.3% | Legacy/fallback behavior, API shims, and unsupported flows remain largely unverified. |
| 10 | `burp.ui.tree.RequestTreeTransferHandler` | 0.0% | 4.1% | Critical drag/drop path with almost no direct branch coverage. |
| 11 | `burp.utils.RequestBuilder` | 54.4% | 85.7% | High-value core path already has many tests, but remaining branch gaps still justify Pass 2 focus. |
| 12 | `burp.parser.PostmanParser` | 51.2% | 77.5% | Good happy-path coverage, but substantial import variation remains uncovered. |
| 13 | `burp.parser.BrunoParser` | 55.9% | 71.0% | Important ZIP/folder import path, still missing malformed-input and safety matrix breadth. |
| 14 | `burp.UniversalImporter` | 30.1% | 49.5% | High-level orchestration still relies heavily on indirect UI tests. |
| 15 | `burp.history.HistoryEntry` | 63.0% | 83.1% | Data model is broad and widely used; remaining branches likely hide replay/export edge cases. |
| 16 | `burp.parser.ApiWorkbenchCollectionParser` | 53.2% | 76.4% | Native round-trip parser is partly covered but not yet stress-tested for malformed/boundary data. |
| 17 | `burp.history.HistoryRequestSnapshot` | 31.6% | 46.5% | Replay/history correctness depends on this representation. |
| 18 | `burp.exporter.OpenApiCollectionExporter` | 54.5% | 77.1% | Good base coverage, but export semantics still need more edge-case confidence. |
| 20 | `burp.utils.SharedRequestPipeline` | 56.8% | 83.7% | High-value execution path with solid line coverage but enough branches left to justify the next pass. |

## Immediate follow-up priorities driven by measured evidence

1. **Pass 2 target already justified by numbers**: `RequestBuilder`, `SharedRequestPipeline`, `ScriptEngine`, `RuntimeResolverFactory`, `VariableResolver`, and surrounding request-execution helpers are measurable risk despite strong existing tests.
2. **Script-engine contract coverage is still materially shallow**: `burp/scripts` is only **37.6% branch** despite behaviorally important tests.
3. **Auth/OAuth2 needs dedicated hardening**: `burp/auth` is only **14.6% branch**, with three zero-coverage grant handlers.
4. **UI orchestration remains the largest single blind spot**: `ImporterPanel` and `RequestEditorPanel` dominate residual risk score.

## Notes

- One early pre-change `mvn -B clean test` run surfaced a non-reproduced Mockito stub error in `HistorySendToRepeaterActionTest`. Every subsequent focused and full validation run in this pass completed successfully, so the issue is recorded here as a possible flake risk rather than a current failing baseline.
- No production source files under `src/main/java` were changed in this pass.

## Current validated snapshot after Passes 8-13

This section captures the latest successful validation state after the test-hardening passes completed.

### Validation summary

| Item | Value |
|---|---:|
| Executed tests | 792 |
| Failures | 0 |
| Errors | 0 |
| Skipped | 0 |
| ScriptRuntimeProbe | PASS |
| Mutation score | 55% |
| Enforced PIT floor | 54% |

### Overall coverage

| Counter | Coverage |
|---|---:|
| Line | 62.8% |
| Branch | 45.2% |

### Selected package coverage

| Package | Line | Branch |
|---|---:|---:|
| `burp.auth` | 74.1% | 57.7% |
| `burp.history` | 87.2% | 64.0% |
| `burp.diagnostics` | 83.8% | 55.8% |
| `burp.parser` | 74.1% | 52.7% |
| `burp.exporter` | 81.5% | 56.9% |
| `burp.runner` | 81.0% | 58.3% |
| `burp.ui` | 62.6% | 43.3% |
| `burp.ui.history` | 67.6% | 41.6% |
| `burp.ui.tree` | 74.6% | 57.2% |
| `burp.utils` | 72.6% | 55.0% |

### Current enforced floors

The repository now uses conservative non-regression floors in `pom.xml` derived from the latest measured run:

- Overall: `0.61` line / `0.43` branch
- `burp.auth`: `0.73` line / `0.57` branch
- `burp.history`: `0.74` line / `0.51` branch
- `burp.diagnostics`: `0.76` line / `0.44` branch
- `burp.parser`: `0.73` line / `0.52` branch
- `burp.exporter`: `0.80` line / `0.56` branch
- `burp.runner`: `0.80` line / `0.58` branch
- `burp.scripts`: `0.58` line / `0.37` branch
- `burp.ui`: `0.62` line / `0.43` branch
- `burp.ui.history`: `0.67` line / `0.41` branch
- `burp.ui.tree`: `0.65` line / `0.49` branch
- `burp.utils`: `0.71` line / `0.53` branch
