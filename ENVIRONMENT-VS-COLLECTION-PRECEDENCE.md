# Environment vs Collection Precedence

When the same variable key exists in both the collection and the active environment, the active environment wins.

## Example

Collection variable:

```text
base_url = https://jsonplaceholder.typicode.com
```

Active environment variable:

```text
base_url = https://httpbin.org
```

Resolved value:

```text
https://httpbin.org
```

## Practical precedence used by the workbench

From lowest to highest:

1. Collection variables / collection defaults
2. Folder variables
3. Active environment variables
4. Runtime/script variables
5. Request variables
6. Auth / OAuth2 runtime mappings or explicit auth-derived runtime values

## Notes

- Active environment values are the user-selected runtime context.
- Runtime tokens used for export or OAuth2 population are intentionally not treated as normal exported environment values.
- Bruno folder and Bruno ZIP imports follow the same active-environment override rule in the workbench and runner paths.
