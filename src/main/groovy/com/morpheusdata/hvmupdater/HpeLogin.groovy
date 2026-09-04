package com.morpheusdata.hvmupdater

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j

import java.net.CookieManager
import java.net.CookiePolicy
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.regex.Matcher

/**
 * HPE Software Center login via Okta Identity Engine (IDX) HTTP replay.
 *
 * Confirmed flow (captured 2026-09-02, incognito):
 *   portal  GET  /cwp-ui/auth/oktaLogin?redirectUrl=... (302) → /cwp-ui/auth/authorize (302)
 *           → auth.hpe.com/hpe/cf/ (Okta hosted login app)
 *   okta    POST /oauth2/<issuer>/v1/interact        (form)  → { interactionHandle }
 *           POST /idp/idx/introspect                 (json)  { interactionHandle } → stateHandle + remediation
 *           POST /idp/idx/identify                   (json)  { identifier, stateHandle } → remediation
 *           POST /idp/idx/challenge/answer           (json)  { credentials:{passcode}, stateHandle } → success
 *   portal  redirect chain resumes → /cwp-ui/auth/onepass?code=... (302)
 *           → /cwp-ui/manage-assets/download?authToken=<uuid>   ← the token
 *
 * interact form fields (SPA client):
 *   client_id=0oa1keb1lcphJkrtc358, scope="openid profile email",
 *   redirect_uri=https://auth.hpe.com/hpe/cf/, code_challenge, code_challenge_method=S256, state, nonce
 */
@Slf4j
class HpeLogin {

    static final String PORTAL = 'https://myenterpriselicense.hpe.com'
    static final String OKTA   = 'https://auth.hpe.com'
    static final String ISSUER = OKTA + '/oauth2/aus43pf0g8mvh4ntv357'
    static final String IDX_CLIENT = '0oa1keb1lcphJkrtc358'
    static final String IDX_REDIRECT = OKTA + '/hpe/cf/'
    static final String START = PORTAL + '/cwp-ui/auth/oktaLogin?redirectUrl=%2Fcwp-ui%2Fmanage-assets%2Fdownload'
    static final String UA = 'Mozilla/5.0 (X11; Linux x86_64) hvm-updater/0.4'
    static final java.util.regex.Pattern AUTHTOKEN_RE = ~/[?&]authToken=([0-9a-fA-F-]{36})/

    final String username, password
    final List<String> trace = []
    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL)
    private final HttpClient http

    HpeLogin(String username, String password) {
        this.username = username; this.password = password
        this.http = HttpClient.newBuilder().cookieHandler(cookies)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(20)).build()
    }

    static class LoginException extends RuntimeException { List<String> trace; LoginException(String m, List<String> t=null) { super(m); this.trace=t } }

    String login() {
        if (!username || !password) throw new LoginException('HPE username/password required')

        // 1. Portal → Okta. Follow redirects, priming cookies, until we're on the Okta login app.
        String tok = follow(START, 15)
        if (tok) { trace << 'token without login (live SSO cookie)'; return tok }

        // 2. Okta IDX: interact (form) → introspect (json) → identify → answer
        String verifier = codeVerifier()
        String challenge = codeChallenge(verifier)
        String state = randToken(32), nonce = randToken(32)
        Map interact = postForm("${ISSUER}/v1/interact", [
                client_id: IDX_CLIENT, scope: 'openid profile email', redirect_uri: IDX_REDIRECT,
                code_challenge: challenge, code_challenge_method: 'S256', state: state, nonce: nonce])
        String ih = (interact.interactionHandle ?: interact.interaction_handle) as String
        if (!ih) throw new LoginException("no interactionHandle from interact: ${brief(interact)}", trace)
        trace << 'interact ok'

        Map intro = postIon("${OKTA}/idp/idx/introspect", [interactionHandle: ih])
        String sh = intro.stateHandle as String
        if (!sh) throw new LoginException("no stateHandle from introspect: ${brief(intro)}", trace)
        trace << "introspect ok (rem: ${remNames(intro)})"

        Map idRem = remediation(intro, 'identify')
        Map afterId = postIon((idRem?.href ?: "${OKTA}/idp/idx/identify") as String,
                [identifier: username, stateHandle: sh])
        sh = (afterId.stateHandle ?: sh) as String
        if (afterId.messages) throw new LoginException("identify: ${msgs(afterId)}", trace)
        trace << "identify ok (rem: ${remNames(afterId)})"

        Map ansRem = remediation(afterId, 'challenge-authenticator') ?: remediation(afterId, 'challenge') ?: remediation(afterId, 'answer')
        Map done = postIon((ansRem?.href ?: "${OKTA}/idp/idx/challenge/answer") as String,
                [credentials: [passcode: password], stateHandle: sh])
        if (done.messages) throw new LoginException("password step: ${msgs(done)}", trace)
        trace << 'answer ok'

        // 3. IDX success → follow the success/interaction_code back through the portal callback.
        String successHref = deepFind(done, 'href')
        if (successHref) follow(successHref, 8)   // completes the OAuth code exchange, sets portal session

        // 4. Re-run the portal start; the session cookies now mint a token.
        tok = follow(START, 15)
        if (tok) { trace << 'token after login'; return tok }
        throw new LoginException("IDX completed but no authToken", trace)
    }

    // ── redirect follower with cookie jar ──────────────────────────────────────
    private String follow(String url, int max) {
        String cur = url
        for (int i = 0; i < max && cur; i++) {
            Matcher m = AUTHTOKEN_RE.matcher(cur)
            if (m.find()) return m.group(1)
            HttpResponse<String> r = get(cur)
            String loc = r.headers().firstValue('location').orElse(null)
            trace << "${r.statusCode()} ${hostPath(cur)}${loc ? ' -> ' + hostPath(loc) : ''}"
            if (loc) {
                Matcher lm = AUTHTOKEN_RE.matcher(loc)
                if (lm.find()) return lm.group(1)
                // Reaching the Okta hosted login app means we must run IDX; stop following.
                if (loc.contains('/hpe/cf') && !loc.contains('code=')) return null
                cur = abs(cur, loc)
            } else {
                Matcher bm = AUTHTOKEN_RE.matcher(r.body() ?: '')
                return bm.find() ? bm.group(1) : null
            }
        }
        return null
    }

    // ── HTTP ───────────────────────────────────────────────────────────────────
    private HttpResponse<String> get(String url) {
        http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                .header('User-Agent', UA).header('Accept', 'text/html,application/json,*/*').GET().build(),
                HttpResponse.BodyHandlers.ofString())
    }
    private Map postForm(String url, Map fields) {
        String body = fields.collect { k, v -> "${k}=${URLEncoder.encode(v as String, 'UTF-8')}" }.join('&')
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                .header('User-Agent', UA).header('Content-Type', 'application/x-www-form-urlencoded')
                .header('Accept', 'application/json')
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString())
        parse(r.body())
    }
    private Map postIon(String url, Map body) {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                .header('User-Agent', UA)
                .header('Content-Type', 'application/ion+json; okta-version=1.0.0')
                .header('Accept', 'application/ion+json; okta-version=1.0.0')
                .POST(HttpRequest.BodyPublishers.ofString(JsonOutput.toJson(body))).build(), HttpResponse.BodyHandlers.ofString())
        parse(r.body())
    }
    private static Map parse(String s) { try { s ? (new JsonSlurper().parseText(s) as Map) : [:] } catch (Exception e) { [_raw: s?.take(200)] } }

    // ── IDX helpers ──────────────────────────────────────────────────────────
    private static Map remediation(Map o, String name) {
        (((o?.remediation as Map)?.value ?: []) as List).find { (it as Map)?.name == name } as Map
    }
    private static List remNames(Map o) { (((o?.remediation as Map)?.value ?: []) as List).collect { (it as Map)?.name } }
    private static String msgs(Map o) { (((o?.messages as Map)?.value ?: []) as List).collect { (it as Map)?.message }.join('; ') ?: brief(o) }
    private static String deepFind(Object o, String key) {
        if (o instanceof Map) { if (o[key] instanceof String) return o[key]; for (v in o.values()) { String r = deepFind(v, key); if (r) return r } }
        else if (o instanceof List) { for (v in o) { String r = deepFind(v, key); if (r) return r } }
        null
    }
    private static String brief(Map o) { o?.toString()?.take(180) }
    private static String abs(String base, String loc) { loc.startsWith('http') ? loc : URI.create(base).resolve(loc).toString() }
    private static String hostPath(String u) { try { URI x = URI.create(u); x.host + x.path } catch (Exception e) { u?.take(40) } }

    // ── PKCE ───────────────────────────────────────────────────────────────────
    static String randToken(int n) { byte[] b = new byte[n]; new SecureRandom().nextBytes(b); b64u(b) }
    static String codeVerifier() { byte[] b = new byte[32]; new SecureRandom().nextBytes(b); b64u(b) }
    static String codeChallenge(String v) { b64u(MessageDigest.getInstance('SHA-256').digest(v.bytes)) }
    private static String b64u(byte[] b) { b.encodeBase64().toString().replace('+', '-').replace('/', '_').replaceAll('=+$', '') }
}
