# START HERE — Local HTTPBin OAuth Smoke Flow

1. Start the local server:
   `python .\awb-local-httpbin.py`
2. Import `00-environments/api-workbench-local-httpbin.environment.json`.
3. Import `06-native/api-workbench-local-httpbin-native.collection.json`.
4. Set `AWB Local HTTPBin` as the active environment.
5. Open the OAuth2 tab.
6. Populate or confirm client-credentials settings:
   - token URL: `{{oauth_token_url}}`
   - grant type: `client credentials`
   - client id: `{{oauth_client_id}}`
   - client secret: `{{oauth_client_secret}}`
   - scope: `{{oauth_scope}}`
7. Acquire token.
8. Confirm the token is stored/bound according to the current OAuth2 tab behavior. For this fixture, the protected request reads `{{oauth2_access_token}}`.
9. Send `AWB OAuth Protected Resource`.
10. Expected:
    - `200` response
    - `authenticated=true`
    - `oauth=true`
    - raw History request contains `Authorization: Bearer awb-oauth-local-access-token`

Curl checks:

```powershell
curl.exe -i -X POST "http://127.0.0.1:18080/oauth/token"
curl.exe -i "http://127.0.0.1:18080/oauth/protected"
curl.exe -i -H "Authorization: Bearer awb-oauth-local-access-token" "http://127.0.0.1:18080/oauth/protected"
```
