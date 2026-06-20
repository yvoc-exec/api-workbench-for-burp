# API Workbench Testing Strategy

This document summarizes the current automated test layers, the Maven profiles that gate them, and the local commands used to validate the repository.

## Test layers

### 1. Fast unit tests

- Default `mvn test`
- Focused on deterministic model, parser, exporter, auth, diagnostics, and utility behavior
- No public network access
- Safe on headless CI

### 2. Integration-style tests

- Routed through Surefire/Failsafe depending on the test naming convention
- Used for durable persistence, workspace restore, OAuth2 token workflows, and in-process HTTP fixtures
- Localhost-only, with random ports where needed

### 3. UI interaction tests

- Run only with `-Pui-tests`
- Require a display or Xvfb
- Kept small and behavior-focused
- Backed by state assertions, not pixel-perfect screenshots

### 4. Performance tests

- Run only with `-Pperformance-tests`
- Use broad, non-brittle timing budgets
- Intended for scheduled/manual execution rather than every push

### 5. Static analysis and mutation testing

- `-Pstatic-analysis` for SpotBugs
- `-Pmutation-tests` for PIT
- Not part of the default push path unless explicitly invoked by CI

## Local commands

```bash
mvn -B clean test
mvn -B clean verify
mvn -B clean package
java -cp target/*-jar-with-dependencies.jar burp.smoke.ScriptRuntimeProbe --require-full
```

Optional profiles:

```bash
mvn -B verify -Pui-tests
mvn -B verify -Pperformance-tests
mvn -B verify -Pstatic-analysis
mvn -B org.pitest:pitest-maven:mutationCoverage -Pmutation-tests
```

## Naming conventions

- `*Test` for ordinary unit/component coverage
- `*IT` for integration-style tests and profile-gated UI/performance coverage
- `*CompatibilityTest` for restore/migration fixtures
- `*SecurityTest` for negative-input and secret-safety assertions
- `*ConcurrencyTest` for bounded synchronization scenarios

## CI jobs

- Linux matrix: Java 17, 21, 25
- Windows matrix: Java 17, 25
- Linux Xvfb UI job: `mvn -B verify -Pui-tests`
- Scheduled/manual performance job: `mvn -B verify -Pperformance-tests`
- Scheduled/manual mutation job: PIT under `-Pmutation-tests`
- Static analysis job: `mvn -B verify -Pstatic-analysis`

## Coverage governance

The long-term documented targets remain:

- Overall: 80% line / 70% branch
- Critical auth/history/execution packages: 90% line / 80% branch
- Parsers/exporters: 85% line / 75% branch
- UI: 75% line / 65% branch

Current enforced JaCoCo floors should be conservative, evidence-based non-regression thresholds derived from the latest validated run.

## Fixture and helper guidance

- Prefer deterministic localhost servers over public services.
- Clean up servers, executors, dialogs, listeners, and temp files.
- Use fixture resources for compatibility tests when the shape of the persisted schema matters.
- Keep raw-history evidence tests explicit about the difference between raw and sanitized surfaces.

## Manual QA boundary

Some behaviors remain better suited to Burp manual verification, including:

- subjective visual layout checks
- complex multi-window interaction flows
- full end-to-end Burp extension host validation in non-test environments

Automated tests should prove the durable state contracts and keep manual QA focused on the remaining visual and host-specific gaps.

## Pass 13 governance snapshot

The repository now has the following current governance floors and workflows:

- JaCoCo non-regression floors are set in `pom.xml` based on the latest validated run.
- SpotBugs currently passes on the validated run; `config/spotbugs-exclude.xml` remains available if future accepted findings ever need a documented baseline.
- PIT is configured under `-Pmutation-tests` with a current enforceable floor of 54%.
- The documented long-term targets remain unchanged and higher than the current floors.

Current mutation-testing evidence:

- Mutation score: 55%
- PIT floor: 54%
- The score is below the aspirational 75% target, so the target remains a goal rather than a claimed achievement.
