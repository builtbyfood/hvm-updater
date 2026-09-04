#!/usr/bin/env python3
"""
hpe_swc.py — track and download HPE Morpheus / VME releases from My HPE Software Center.

Runs two ways:
  * As a Morpheus Python task: reads inputs from morpheus['customOptions'].
  * Standalone CLI:            python3 hpe_swc.py --token <uuid> check

Actions
  list      Enumerate every product the account can download and the files for each.
  check     Compare current files/releaseDate against the state file, report what is new.
  download  Download files matching --pattern for the selected product(s), verify SHA-512.
  login     Open a browser to the portal, wait for you to sign in, capture the token
            into the token file (needs `pip install playwright && playwright install chromium`).
            With a persistent profile it first tries a silent refresh (no window) and only
            opens a window when HPE actually needs you to log in again.

Auth: the portal issues a 36-char UUID after SSO (localStorage.authToken in the SPA).
      It is sent as the header  x-authtoken: <uuid>.  Nothing else is required.
"""
import argparse
import hashlib
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request

BASE = "https://myenterpriselicense.hpe.com"
DEFAULT_STATE = "/var/opt/morpheus/hpe-swc/state.json"
DEFAULT_DEST = "/var/opt/morpheus/hpe-swc/downloads"
DEFAULT_TOKEN_FILE = os.environ.get("HPE_SWC_TOKEN_FILE") or os.path.expanduser("~/.hpe-swc/token")
DEFAULT_PROFILE = os.path.expanduser("~/.hpe-swc/browser-profile")
TOKEN_RE = re.compile(r"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
LOGIN_URL = BASE + "/cwp-ui/manage-assets/download"

BOOKMARKLET = (
    "javascript:(function(){var t=(localStorage.getItem('authToken')||'').replace(/\"/g,'');"
    "if(!t){alert('Not signed in');return;}navigator.clipboard.writeText(t).then(function(){"
    "alert('HPE token copied to clipboard');});})();"
)
UA = "hpe-swc/0.1 (+https://github.com/builtbyfood)"


# --------------------------------------------------------------------------- HTTP
class SWC:
    def __init__(self, token, timeout=60):
        self.token = token
        self.timeout = timeout

    def _api(self, path, body):
        req = urllib.request.Request(
            BASE + path,
            data=json.dumps(body).encode(),
            method="POST",
            headers={
                "Content-Type": "application/json",
                "Accept": "application/json",
                "X-Requested-With": "XMLHttpRequest",
                "x-authtoken": self.token,
                "User-Agent": UA,
            },
        )
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as r:
                return json.loads(r.read().decode() or "null")
        except urllib.error.HTTPError as e:
            text = e.read().decode(errors="replace")
            if e.code in (401, 403):
                raise SystemExit(f"auth failed ({e.code}) — x-authtoken expired or invalid: {text[:200]}")
            raise SystemExit(f"{path} -> HTTP {e.code}: {text[:300]}")
        except urllib.error.URLError as e:
            raise SystemExit(f"{path} -> network error: {e.reason}")

    def _search(self, kind):
        """kind: 'download' (evals/invitations, previously downloaded) or 'entitlement' (licensed)."""
        out, page = [], 1
        while True:
            res = self._api(
                f"/cwp-ui/api/search/{kind}",
                {
                    "pageNumber": page,
                    "pageSize": 50,
                    "sortDirection": "desc",
                    "sortColumn": "downloadDate" if kind == "download" else "productFamily",
                    "showPreviousVersions": False,
                },
            ) or {}
            rows = res.get("resultSet") or []
            out.extend(rows)
            if len(out) >= (res.get("totalCount") or 0) or not rows:
                return out
            page += 1

    def products(self):
        """Merge both search endpoints; dedupe on (productNumber, merchant)."""
        seen, out = set(), []
        for kind in ("download", "entitlement"):
            for r in self._search(kind):
                key = (r.get("productNumber"), r.get("merchant"))
                if key in seen:
                    continue
                seen.add(key)
                out.append(
                    {
                        "productNumber": r.get("productNumber"),
                        "productName": r.get("productName"),
                        "productVersion": r.get("productVersion") or "-",
                        "productFamily": r.get("productFamily"),
                        "merchant": r.get("merchant"),
                        "invitationId": r.get("invitationId") or None,
                        "pdapiOrderNumber": r.get("pdapiOrderNumber") or None,
                        "contractNumber": r.get("contractNumber") or None,
                        "source": kind,
                    }
                )
        return out

    def files(self, p):
        res = self._api(
            "/common/api/download/getProductDownload",
            {
                "productNumber": p["productNumber"],
                "productVersion": p.get("productVersion") or "-",
                "merchant": p["merchant"],
                "contractNumber": p.get("contractNumber"),
                "invitationId": p.get("invitationId"),
                "saAuth": None,
                "pdapiOrderNumber": p.get("pdapiOrderNumber"),
            },
        )
        details = (res or {}).get("downloadDetailsList") or []
        if not details:
            return {"releaseDate": None, "showEula": None, "files": []}
        d = details[0]
        return {
            "productName": d.get("productName"),
            "releaseDate": d.get("releaseDate"),
            "showEula": d.get("showEula"),
            "files": [
                {
                    "fileName": f.get("fileName"),
                    "size": f"{f.get('fileSize')}{f.get('fileSizeUnit')}",
                    "fileType": f.get("fileType"),
                    "sha512": f.get("checkSum"),
                    "url": f.get("url"),
                    "sigUrl": f.get("signatureFileUrl"),
                }
                for f in d.get("downloadFilesList") or []
            ],
        }


# ------------------------------------------------------------------------ helpers
VERSION_RE = re.compile(r"_(\d+\.\d+\.\d+(?:-\d+)?)_")


def version_of(filename):
    m = VERSION_RE.search(filename or "")
    return m.group(1) if m else None


def fmt_date(ms):
    return time.strftime("%Y-%m-%d", time.gmtime(ms / 1000)) if ms else "-"


def load_state(path):
    try:
        with open(path) as fh:
            return json.load(fh)
    except (FileNotFoundError, json.JSONDecodeError):
        return {}


def save_state(path, state):
    os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
    tmp = path + ".tmp"
    with open(tmp, "w") as fh:
        json.dump(state, fh, indent=2)
    os.replace(tmp, path)


def sha512_of(path, bufsize=8 << 20):
    h = hashlib.sha512()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(bufsize), b""):
            h.update(chunk)
    return h.hexdigest()


def download(url, dest, expected_sha=None, log=print):
    """Stream to dest.part with Range resume, then verify and rename."""
    if os.path.exists(dest) and expected_sha:
        if sha512_of(dest) == expected_sha.lower():
            log(f"  already present and verified: {os.path.basename(dest)}")
            return dest
        log("  existing file failed checksum, re-downloading")
        os.remove(dest)
    part = dest + ".part"
    have = os.path.getsize(part) if os.path.exists(part) else 0
    headers = {"User-Agent": UA}
    if have:
        headers["Range"] = f"bytes={have}-"
    req = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(req, timeout=120) as r:
        if have and r.status != 206:
            have = 0  # server ignored Range; start over
        total = int(r.headers.get("Content-Length") or 0) + have
        mode = "ab" if have else "wb"
        done, last = have, time.time()
        with open(part, mode) as fh:
            for chunk in iter(lambda: r.read(4 << 20), b""):
                fh.write(chunk)
                done += len(chunk)
                if time.time() - last > 5:
                    pct = f"{done * 100 // total}%" if total else ""
                    log(f"  {done >> 20} MiB {pct}")
                    last = time.time()
    if expected_sha:
        actual = sha512_of(part)
        if actual != expected_sha.lower():
            raise SystemExit(f"checksum mismatch for {dest}: {actual[:16]}… != {expected_sha[:16]}…")
    os.replace(part, dest)
    return dest


# ------------------------------------------------------------------------ actions
def act_list(client, products, log):
    out = []
    for p in products:
        info = client.files(p)
        out.append({**p, **info})
        log(f"\n{p['productNumber']}  ({p['merchant']})  {info.get('productName') or p['productName']}")
        log(f"  releaseDate={fmt_date(info.get('releaseDate'))}  files={len(info['files'])}")
        for f in info["files"]:
            log(f"    {f['fileName']:<75} {f['size']:>8}  v={version_of(f['fileName'])}")
    return out


def act_check(client, products, state_path, log):
    state = load_state(state_path)
    changes = []
    for p in products:
        info = client.files(p)
        key = f"{p['productNumber']}|{p['merchant']}"
        names = sorted(f["fileName"] for f in info["files"])
        prev = state.get(key) or {}
        new_files = sorted(set(names) - set(prev.get("files") or []))
        rel_changed = prev.get("releaseDate") not in (None, info.get("releaseDate"))
        versions = sorted({v for v in (version_of(n) for n in names) if v})
        if new_files or rel_changed or not prev:
            changes.append(
                {
                    "productNumber": p["productNumber"],
                    "merchant": p["merchant"],
                    "first_seen": not prev,
                    "releaseDate": fmt_date(info.get("releaseDate")),
                    "previousReleaseDate": fmt_date(prev.get("releaseDate")),
                    "versions": versions,
                    "newFiles": new_files,
                }
            )
        state[key] = {
            "productName": info.get("productName") or p["productName"],
            "releaseDate": info.get("releaseDate"),
            "files": names,
            "versions": versions,
            "checkedAt": int(time.time()),
        }
    save_state(state_path, state)
    if not changes:
        log("no changes")
    for c in changes:
        tag = "NEW PRODUCT" if c["first_seen"] else "UPDATE"
        log(f"{tag}: {c['productNumber']} releaseDate {c['previousReleaseDate']} -> {c['releaseDate']} versions={c['versions']}")
        for n in c["newFiles"]:
            log(f"    + {n}")
    return changes


def act_download(client, products, pattern, dest_dir, with_sig, log):
    rx = re.compile(pattern, re.I)
    os.makedirs(dest_dir, exist_ok=True)
    got = []
    for p in products:
        info = client.files(p)  # fresh call so the signed URLs are current
        for f in info["files"]:
            if not rx.search(f["fileName"]):
                continue
            log(f"downloading {f['fileName']} ({f['size']})")
            path = download(f["url"], os.path.join(dest_dir, f["fileName"]), f["sha512"], log)
            got.append(path)
            if with_sig and f.get("sigUrl"):
                sig = download(f["sigUrl"], path + ".sig", None, log)
                got.append(sig)
    if not got:
        log(f"nothing matched /{pattern}/")
    return got


# --------------------------------------------------------------------------- token
def read_token_file(path):
    try:
        with open(path) as fh:
            m = TOKEN_RE.search(fh.read())
            return m.group(0) if m else None
    except FileNotFoundError:
        return None


def write_token_file(path, token):
    os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as fh:
        fh.write(token + "\n")


def resolve_token(opts):
    """Precedence: explicit --token/input > $HPE_AUTHTOKEN > token file."""
    for cand in (opts.get("token"), os.environ.get("HPE_AUTHTOKEN"), read_token_file(opts.get("token_file") or DEFAULT_TOKEN_FILE)):
        if cand:
            m = TOKEN_RE.search(str(cand))
            if m:
                return m.group(0)
    return None


def token_valid(token):
    """Cheap probe: search endpoint with pageSize 1. Returns True/False."""
    try:
        SWC(token, timeout=30)._api("/cwp-ui/api/search/download",
                                    {"pageNumber": 1, "pageSize": 1, "sortDirection": "desc",
                                     "sortColumn": "downloadDate", "showPreviousVersions": False})
        return True
    except SystemExit as e:
        if "auth failed" in str(e):
            return False
        raise


def act_login(opts, log):
    """Capture a token via a browser. Silent first (persistent profile), then headed."""
    token_file = opts.get("token_file") or DEFAULT_TOKEN_FILE
    profile = opts.get("profile") or DEFAULT_PROFILE
    try:
        from playwright.sync_api import sync_playwright
    except ImportError:
        log("playwright not installed — two alternatives:\n"
            "  1) pip install playwright && playwright install chromium   (then rerun `login`)\n"
            "  2) in your normal browser, sign in to the portal, run this bookmarklet, and paste\n"
            f"     the clipboard into {token_file}:\n     {BOOKMARKLET}")
        return {"token_file": token_file, "captured": False}

    def grab(page):
        raw = page.evaluate("() => localStorage.getItem('authToken') || ''")
        m = TOKEN_RE.search(raw or "")
        return m.group(0) if m else None

    hpe_user = os.environ.get("HPE_USER")
    hpe_pass = os.environ.get("HPE_PASS")

    def attempt(headless, wait_s):
        with sync_playwright() as pw:
            ctx = pw.chromium.launch_persistent_context(profile, headless=headless, viewport={"width": 1280, "height": 900})
            page = ctx.pages[0] if ctx.pages else ctx.new_page()
            page.goto(LOGIN_URL, wait_until="domcontentloaded")
            # non-interactive fill when creds are supplied via env (used by the plugin fallback)
            if hpe_user and hpe_pass:
                try:
                    page.wait_for_timeout(2500)
                    for sel in ["input[name=identifier]", "input[type=email]", "#idp-discovery-username", "input[name=username]"]:
                        if page.locator(sel).count():
                            page.fill(sel, hpe_user); break
                    for sel in ["input[type=submit]", "#idp-discovery-submit", "input[value='Next']", "button[type=submit]"]:
                        if page.locator(sel).count():
                            page.click(sel); break
                    page.wait_for_timeout(2500)
                    for sel in ["input[type=password]", "input[name=credentials.passcode]", "#okta-signin-password"]:
                        if page.locator(sel).count():
                            page.fill(sel, hpe_pass); break
                    for sel in ["input[type=submit]", "#okta-signin-submit", "button[type=submit]", "input[value='Verify']"]:
                        if page.locator(sel).count():
                            page.click(sel); break
                except Exception as _e:
                    log(f"auto-fill note: {_e}")
            deadline = time.time() + wait_s
            tok = None
            while time.time() < deadline:
                try:
                    if "myenterpriselicense.hpe.com" in page.url:
                        tok = grab(page)
                        if tok and token_valid(tok):
                            break
                        tok = None
                except Exception:
                    pass  # mid-navigation; try again
                page.wait_for_timeout(1000)
            ctx.close()
            return tok

    try:
        log("trying silent refresh with saved browser profile…")
        tok = attempt(headless=True, wait_s=int(opts.get("silent_wait") or 25))
        if not tok:
            log("sign in to HPE in the browser window that just opened (MFA included); "
                "this will finish automatically once the portal loads.")
            tok = attempt(headless=False, wait_s=int(opts.get("login_wait") or 600))
    except Exception as e:  # browser missing, no display, etc.
        raise SystemExit(f"browser launch failed: {str(e).splitlines()[0]}\n"
                         "  run `playwright install chromium`, or use the bookmarklet: "
                         "`hpe_swc.py --bookmarklet`")
    if not tok:
        raise SystemExit("no valid token captured (timed out before the portal loaded)")
    write_token_file(token_file, tok)
    log(f"token captured -> {token_file}")
    return {"token_file": token_file, "captured": True}


# --------------------------------------------------------------------------- main
def run(opts, log=print):
    action = opts.get("action") or "check"
    if action == "login":
        return {"action": "login", "result": act_login(opts, log)}

    token = resolve_token(opts)
    if (not token or not token_valid(token)) and opts.get("auto_login"):
        log("no valid token — launching login")
        act_login(opts, log)
        token = resolve_token({**opts, "token": None})
    if not token:
        raise SystemExit("no token: run `hpe_swc.py login`, set $HPE_AUTHTOKEN, or pass --token "
                         f"(token file: {opts.get('token_file') or DEFAULT_TOKEN_FILE})")
    client = SWC(token)

    products = client.products()
    want = [s.strip() for s in (opts.get("products") or "").split(",") if s.strip()]
    if want:
        products = [p for p in products if p["productNumber"] in want]
        if not products:
            raise SystemExit(f"none of {want} found in this account's downloads/entitlements")
    log(f"{len(products)} product(s): " + ", ".join(p["productNumber"] for p in products))

    if action == "list":
        result = act_list(client, products, log)
    elif action == "check":
        result = act_check(client, products, opts.get("state") or DEFAULT_STATE, log)
    elif action == "download":
        result = act_download(
            client, products, opts.get("pattern") or r"debian.*\.deb$",
            opts.get("dest") or DEFAULT_DEST, opts.get("sig", False), log,
        )
    else:
        raise SystemExit(f"unknown action {action}")
    return {"action": action, "products": [p["productNumber"] for p in products], "result": result}


def morpheus_opts():
    """Map Morpheus task inputs -> options. Checkbox inputs arrive as 'on'/'off'."""
    co = morpheus.get("customOptions", {})  # noqa: F821  (injected by Morpheus)
    return {
        "token": co.get("hpe_token"),
        "action": co.get("hpe_action", "check"),
        "products": co.get("hpe_products", ""),
        "pattern": co.get("hpe_pattern", ""),
        "dest": co.get("hpe_dest", ""),
        "state": co.get("hpe_state", ""),
        "sig": co.get("hpe_sig", "off") == "on",
        "token_file": co.get("hpe_token_file", "/var/opt/morpheus/hpe-swc/token"),
        "auto_login": False,  # no browser on the appliance; refresh the token file from a workstation
    }


def cli_opts():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("action", nargs="?", default="check", choices=["login", "list", "check", "download"])
    ap.add_argument("--token", default=None, help="x-authtoken UUID (else $HPE_AUTHTOKEN, else --token-file)")
    ap.add_argument("--token-file", default=DEFAULT_TOKEN_FILE, help="where `login` saves the token / others read it")
    ap.add_argument("--profile", default=DEFAULT_PROFILE, help="persistent browser profile for `login`")
    ap.add_argument("--auto-login", action="store_true", help="if the token is missing/expired, run login first")
    ap.add_argument("--headless", action="store_true", help="never open a window; use with HPE_USER/HPE_PASS env for unattended login")
    ap.add_argument("--bookmarklet", action="store_true", help="print the copy-token bookmarklet and exit")
    ap.add_argument("--products", default="", help="comma-separated productNumbers to limit to")
    ap.add_argument("--pattern", default=r"debian.*\.deb$", help="regex on fileName for download")
    ap.add_argument("--dest", default=DEFAULT_DEST)
    ap.add_argument("--state", default=DEFAULT_STATE)
    ap.add_argument("--sig", action="store_true", help="also fetch .sig files")
    ap.add_argument("--json", action="store_true", help="print JSON summary at the end")
    a = ap.parse_args()
    if a.bookmarklet:
        print(BOOKMARKLET)
        raise SystemExit(0)
    return vars(a), a.json


if __name__ == "__main__":
    if "morpheus" in globals():
        summary = run(morpheus_opts())
        print("RESULT_JSON=" + json.dumps(summary, default=str))
    else:
        o, want_json = cli_opts()
        summary = run(o)
        if want_json:
            print(json.dumps(summary, indent=2, default=str))
