# Smoke Mode Security Notes

Runtime smoke mode is a local QA feature, not a normal user-facing runtime path.

## Opt-in gate

Smoke mode only starts when one of these is provided:

- `API_WORKBENCH_SMOKE_CONFIG=<path-to-smoke-config-json>`
- `-DapiWorkbench.smoke.config=<path-to-smoke-config-json>`

If neither value is present, the extension runs normally.

## Config path behavior

The smoke config path points to a local JSON configuration file used by the tester workflow. The file tells the extension which fixtures, report paths, and optional live-check settings to use.

## Outputs and sensitivity

Smoke runs can write JSON, Markdown, evidence, and log-scan reports.

Treat those outputs as sensitive until reviewed, because they may contain request bodies, response bodies, tokens, or other testing data depending on the fixtures that were exercised.

Avoid real secrets in smoke configs and fixtures. Prefer synthetic values and local-only test endpoints.

## SAST notes

Security review tools may flag the following patterns in the smoke path:

- Environment-variable and system-property controls.
- Local config-file path handling.
- Evidence/report writing.
- Broad exception handling for local QA resilience.
- Reflection or Nashorn probing used to detect script capability.

These are expected local-QA controls, not normal runtime behavior.

## Operational reminders

- Smoke mode is disabled by default.
- Optional live endpoint checks should only target systems you are authorized to test.
- Tokens should be cleared on extension unload where supported by the runtime.
- Review generated evidence and logs before sharing them externally.
