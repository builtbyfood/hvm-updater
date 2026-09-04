package com.morpheusdata.hvmupdater

import groovy.util.logging.Slf4j

/**
 * Owns the live x-authtoken. Priority when a token is needed:
 *   1. in-memory cached token (last good)
 *   2. the manual `hpeToken` setting
 *   3. auto-login: HpeLogin HTTP replay, then the shell fallback (hpe_swc.py login)
 *
 * refresh() is called on a 401 from the portal; it clears the cache and re-runs
 * auto-login. Secrets are never logged.
 */
@Slf4j
class TokenManager {

    final Closure<Map> settings
    final CredentialResolver creds
    final Closure<Map> sshExec   // {cmd -> [rc,out,err]} using the SSH credential, for the shell fallback

    private volatile String cached
    private volatile long refreshedAt
    volatile String lastError
    volatile String lastSource

    TokenManager(Closure<Map> settings, CredentialResolver creds, Closure<Map> sshExec) {
        this.settings = settings
        this.creds = creds
        this.sshExec = sshExec
    }

    /** Best token we can offer without forcing a login. */
    String current() {
        if (cached) return cached
        String manual = SwcClient.normalize(settings().hpeToken as String)
        if (manual) { lastSource = 'setting'; return manual }
        return null
    }

    void store(String token) {
        cached = SwcClient.normalize(token)
        refreshedAt = System.currentTimeMillis()
    }

    /** Interactive login: password supplied per-call, never stored. Keeps only the token. */
    synchronized String loginWith(String user, String pass) {
        lastError = null
        if (!user || !pass) { lastError = 'username and password required'; return null }
        try {
            HpeLogin lg = new HpeLogin(user, pass)
            String tok = lg.login()
            if (tok) { store(tok); lastSource = 'interactive'; lastTrace = lg.trace; log.info('hvm-updater: token obtained via interactive login'); return tok }
            lastError = "login completed without a token; trace: ${lg.trace.join(' | ')}"; lastTrace = lg.trace
        } catch (HpeLogin.LoginException e) {
            lastError = e.message; lastTrace = e.trace ?: lastTrace
            log.warn("hvm-updater: interactive login failed: ${e.message}")
        } catch (Exception e) {
            lastError = "${e.class.simpleName}: ${e.message}"
            log.warn("hvm-updater: interactive login error: ${e.message}")
        }
        return null
    }

    volatile List<String> lastTrace = []

    /** Force a new token via stored-credential auto-login (fallback only). Returns the token or null. */
    synchronized String refresh() {
        lastError = null
        Map hpe = hpeCredential()
        if (hpe.error) { lastError = hpe.error as String; return null }

        // 1. HTTP replay
        try {
            HpeLogin lg = new HpeLogin(hpe.username as String, hpe.password as String)
            String tok = lg.login()
            if (tok) { store(tok); lastSource = 'http-replay'; log.info("hvm-updater: token refreshed via HTTP replay"); return tok }
        } catch (Exception e) {
            lastError = "http-replay: ${e.message}"
            log.warn("hvm-updater: HTTP replay login failed: ${e.message}")
        }

        // 2. shell fallback: hpe_swc.py login on the appliance, then read the token file
        try {
            String tok = shellLogin(hpe.username as String, hpe.password as String)
            if (tok) { store(tok); lastSource = 'shell'; log.info("hvm-updater: token refreshed via shell fallback"); return tok }
        } catch (Exception e) {
            lastError = (lastError ? lastError + '; ' : '') + "shell: ${e.message}"
            log.warn("hvm-updater: shell login failed: ${e.message}")
        }
        return null
    }

    /** Resolve the HPE username/password credential (separate setting from the SSH one). */
    private Map hpeCredential() {
        Map s = settings()
        String ref = (s.hpeCredential ?: '').toString().trim()
        if (!ref) return [error: 'no HPE credential configured (set "HPE credential" to a Trust ID/name or cypher key for auto-login), or paste a token manually']
        // reuse the resolver against the HPE ref by temporarily overriding the settings closure
        CredentialResolver r = new CredentialResolver(creds.morpheus, { s + [sshCredential: ref, sshUser: null] })
        Map c = r.resolve()
        if (c.error) return [error: "HPE credential '${ref}': ${c.error}".toString()]
        if (!c.username || !c.password) return [error: "HPE credential '${ref}' has no username/password"]
        return [username: c.username, password: c.password]
    }

    /**
     * Fallback: push the standalone script to the appliance and run its login, which
     * writes the token file we then cat back. Requires the SSH credential + a python3.
     * The HPE password is passed via env, never on the command line.
     */
    private String shellLogin(String user, String pass) {
        String dir = '/var/opt/morpheus/morpheus-ui/hvm-updater'
        String tokenFile = "${dir}/hpe-token"
        // The script is bundled as a resource; stage it if not present, then run `login`.
        String script = getClass().getResourceAsStream('/runner/hpe_swc.py')?.text
        if (!script) throw new RuntimeException('bundled hpe_swc.py not found in plugin resources')
        Map put = sshExec([putFile: [path: "${dir}/hpe_swc.py", content: script]])
        if (put?.error) throw new RuntimeException(put.error as String)
        String cmd = "cd ${dir} && HPE_USER=${shq(user)} HPE_PASS=${shq(pass)} " +
                     "python3 hpe_swc.py login --headless --token-file ${tokenFile} >/dev/null 2>&1; cat ${tokenFile} 2>/dev/null"
        Map r = sshExec([cmd: cmd])
        if (r?.rc != 0 && !r?.out) throw new RuntimeException("shell login rc=${r?.rc} ${r?.err?.toString()?.take(160)}")
        String tok = SwcClient.normalize(r.out as String)
        if (!tok) throw new RuntimeException('shell login produced no token')
        return tok
    }

    private static String shq(String v) { "'" + (v ?: '').replace("'", "'\\''") + "'" }

    Map describe() {
        [hasCached: cached != null, source: lastSource, refreshedAt: refreshedAt ?: null, lastError: lastError]
    }
}
