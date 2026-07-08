# Environment vs Collection Precedence

This is the authoritative reference for how API Workbench resolves variables, owns environment profiles, and persists state.

## 1. Normal request-build precedence

Lowest to highest:

1. Collection environment
2. Collection definition variables
3. Ancestor-folder variables
4. Collection runtime OAuth2
5. Collection runtime variables
6. Active Environment overlay
   - OAuth2 environment config is added first
   - normal environment variables can override same-named config keys
7. Explicit execution/runtime/script overlay
8. Authored request variables
9. Auth/runtime mapping when enabled
10. `{{name|default}}` fallback syntax, used only when no higher layer resolved the key

Later layers win. The active environment wins over collection variables and collection runtime layers. Request variables remain the strongest normal authored variable override.

Default placeholders are not a normal mutable scope.

## 2. Active-environment ownership and behavior

- Environment profiles are independent workspace objects.
- The Environment tab selects an environment profile, not a collection.
- The Active Environment applies to Workbench Send, Runner, Repeater, Intruder, Sitemap request construction, and previews.
- An environment profile contains normal variables plus OAuth2 configuration and output bindings.
- The OAuth2 tab selects and uses an environment profile.
- Successful token acquisition writes configured token outputs to that environment.
- Collection `runtimeVars` and `runtimeOAuth2` remain compatibility/runtime storage layers, but they are not the primary operator-facing model.

## 3. Script-specific scopes and lifetime

Script-local values, helper objects, and global script context are execution-time constructs. They are not the same as persisted collection/environment layers.

- Script-local values live for the current script execution or lifecycle phase.
- Script mutations can target supported persisted scopes when the exposed binding allows it.
- `VariableScopeStore` and the script runtime bridge support transient script state separately from the normal request-build precedence list.

## 4. Persistence matrix

| Scope / storage | Persisted with workspace | Owner / notes |
| --- | --- | --- |
| Authored collection data | Yes | Collection structure, variables, auth, and scripts |
| Authored environment data | Yes | Environment profiles, variables, OAuth2 config, and bindings |
| Folder data | Yes | Folder variables, auth, and script blocks |
| Collection runtime values | Yes | Compatibility/runtime storage layer |
| Active environment | Yes | Selected environment profile ID |
| Authored request variables | Yes | Persisted as part of the request/workspace model |
| Explicit execution/runtime overlay | No | Transient unless written through a supported persisted scope |
| Script local / global context | No | Transient unless explicitly written to Environment, collection, folder, or another supported persisted scope |

## 5. Workbench Send, Runner, import destinations, and export behavior

- Workbench Send uses the current Active Environment overlay and any explicit request/runtime/script overlay.
- Runner uses the same shared request pipeline as Workbench Send.
- Repeater, Intruder, and Sitemap import destinations also honor the active environment where request resolution is needed.
- Exported collections and environments may lose metadata that their target schema cannot represent.
- Native API Workbench collection export is the most faithful format for authored collection structure, auth, variables, folder metadata, requests, and native script blocks. It does not automatically serialize `runtimeVars` or `runtimeOAuth2`.
- Optional Active Environment resolution can materialize values into the exported representation when selected.
- Native API Workbench environment export preserves the selected environment profile's variables, OAuth2 configuration, and output bindings.

## 6. Worked examples

### 6.1 Active environment overrides collection values

Collection:

```text
base_url = https://api.example.test
```

Active environment:

```text
base_url = https://api.example.prod
```

Resolved value:

```text
https://api.example.prod
```

### 6.2 Request variable overrides active environment

Active environment:

```text
tenant = alpha
```

Request variable:

```text
tenant = bravo
```

Resolved value:

```text
bravo
```

### 6.3 Default placeholder only applies when unresolved

If no higher layer resolves `host`, then:

```text
{{host|https://example.invalid}}
```

resolves to the placeholder value. If any higher layer resolves `host`, that value wins and the default is ignored.

### 6.4 Script mutation becomes visible in the active environment

A script can mutate a supported persisted variable scope through the exposed binding. For example:

```javascript
pm.environment.set("token", "value");
```

When it does, the active environment view and later request resolutions can observe that mutation. Collection-targeted mutation does not automatically become an Environment-profile mutation.

## 7. Notes

- The Environment tab is the operator home for profile management.
- Do not treat default placeholders as persistent configuration.
- Burp project files can contain secrets; review them before sharing.
