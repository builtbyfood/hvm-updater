# HPE Software Center

Everything the plugin knows about My HPE Software Center (`myenterpriselicense.hpe.com`) was captured from the browser.. It is undocumented and may change; check the portal's terms of use before use.

## Auth

The SPA stores a **36-character UUID** in `localStorage.authToken` after SSO and sends it as the header `x-authtoken: <uuid>`. Nothing else is required. Tokens expire; the page header shows the state.

Sources, in order (`TokenManager`):
1. In-memory cached token from the last successful call.
2. The **HPE token** plugin setting (or a Trust credential referenced from it).
3. **Sign in to HPE** on the page → `HpeLogin`: an HTTP replay of the Okta Identity Engine flow:
   ```
   portal  GET  /cwp-ui/auth/oktaLogin?redirectUrl=…      302 → /cwp-ui/auth/authorize → auth.hpe.com/hpe/cf/
   okta    POST /oauth2/<issuer>/v1/interact   (PKCE form) → interactionHandle
           POST /idp/idx/introspect                        → stateHandle + remediations
           POST /idp/idx/identify     {identifier}         → remediation
           POST /idp/idx/challenge/answer {passcode}       → success redirect
   portal  …/cwp-ui/auth/onepass?code=…  302 → /cwp-ui/manage-assets/download?authToken=<uuid>
   ```
   The password is used once and discarded; the resulting token lives in memory only. A wrong password shows up as the flow stopping after `identify ok` with `challenge-authenticator` still pending.
4. Shell fallback: `hpe_swc.py login` on the appliance (Playwright, needs a browser — mostly for headless-but-interactive setups).

A `401` from the portal clears the cache and re-runs steps 2–4.

## Endpoints used

| Call | Purpose |
|---|---|
| `POST /cwp-ui/api/search/download` | Products previously downloaded / invitations for the account. |
| `POST /cwp-ui/api/search/entitlement` | Licensed products — the primary product list, filtered by `productNumber` if configured. |
| `POST /common/api/download/getProductDownload` | Per product: the file list (`fileName`, `fileSize`, `releaseDate`, `sha512`, `version`) **including pre-signed CDN URLs**. The URLs are short-lived and **never cached** — a download re-fetches this listing first. Optional `.sig` fetched alongside if ticked. |

`catalog.json` caches the product/file listing (no URLs) with a timestamp; *Refresh from HPE* forces a refetch, the timer does it every *Catalog refresh interval*.

## Download and verify

On the appliance, via `TaskAgent`:
```
curl -fL --retry 3 -C - -o "<file>.part" "<presigned>"   # resumable
sha512sum "<file>.part"  == listing sha512  →  mv to "<file>" and touch "<file>.sha512ok"
```
Only files with the marker appear as *staged* and can be passed to the upgrade. Delete/move on the page operate on the file, its `.part`, `.sig` and marker together.

## `hpe_swc.py`

Standalone or Morpheus-task Python tool with the same portal knowledge:

```
python3 hpe_swc.py --token <uuid> list
python3 hpe_swc.py --token <uuid> check --state state.json
python3 hpe_swc.py --token <uuid> download --product S6E64 --pattern 'debian' --dest ./dl
python3 hpe_swc.py login --token-file ~/.hpe_token     # opens a browser via Playwright
```

It predates the plugin and is kept both as the plugin's last-resort login fallback and as a way to script the portal without Morpheus.
