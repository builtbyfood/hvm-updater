package com.morpheusdata.hvmupdater

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.regex.Matcher

/**
 * Minimal client for My HPE Software Center (myenterpriselicense.hpe.com).
 *
 * Auth is a single header, x-authtoken: <36-char uuid>, which the portal stores as
 * localStorage.authToken after SSO. Three POST endpoints cover everything:
 *   /cwp-ui/api/search/download          products previously downloaded / invitations
 *   /cwp-ui/api/search/entitlement       licensed products
 *   /common/api/download/getProductDownload  file list + pre-signed CDN URLs for one product
 */
@Slf4j
class SwcClient {

    static final String BASE = System.getProperty('hvmUpdater.base', 'https://myenterpriselicense.hpe.com')
    static final String UA   = 'hvm-updater/0.1'
    static final java.util.regex.Pattern VERSION_RE = ~/_(\d+\.\d+\.\d+(?:-\d+)?)_/

    final String token
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()

    static final java.util.regex.Pattern UUID_RE = ~/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/

    /** Accepts the bare UUID or anything it was pasted with (quotes, whitespace, 'x-authtoken:' prefix). */
    SwcClient(String token) { this.token = normalize(token) }

    static String normalize(String raw) {
        if (!raw) return null
        Matcher m = UUID_RE.matcher(raw)
        if (m.find()) return m.group(0)
        return raw.trim().replaceAll(/^["']+|["']+$/, '')
    }

    static class AuthException extends RuntimeException {
        AuthException(String m) { super(m) }
    }

    boolean hasToken() { token && token.length() >= 20 && !token.contains(' ') }

    private Object post(String path, Map body) {
        if (!hasToken()) throw new AuthException('HPE token not configured')
        HttpRequest req = HttpRequest.newBuilder(URI.create(BASE + path))
                .timeout(Duration.ofSeconds(60))
                .header('Content-Type', 'application/json')
                .header('Accept', 'application/json')
                .header('X-Requested-With', 'XMLHttpRequest')
                .header('x-authtoken', token)
                .header('User-Agent', UA)
                .POST(HttpRequest.BodyPublishers.ofString(JsonOutput.toJson(body)))
                .build()
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() in [401, 403]) throw new AuthException("HPE token expired or invalid (${resp.statusCode()})")
        if (resp.statusCode() >= 400) throw new RuntimeException("${path} -> HTTP ${resp.statusCode()}: ${resp.body()?.take(300)}")
        return resp.body() ? new JsonSlurper().parseText(resp.body()) : null
    }

    /** Cheap validity probe. */
    boolean tokenValid() {
        try {
            post('/cwp-ui/api/search/download', [pageNumber: 1, pageSize: 1, sortDirection: 'desc',
                                                sortColumn: 'downloadDate', showPreviousVersions: false])
            return true
        } catch (AuthException ignored) {
            return false
        }
    }

    private List<Map> search(String kind) {
        List<Map> out = []
        int page = 1
        while (true) {
            Map res = (post("/cwp-ui/api/search/${kind}", [
                    pageNumber: page, pageSize: 50, sortDirection: 'desc',
                    sortColumn: kind == 'download' ? 'downloadDate' : 'productFamily',
                    showPreviousVersions: false]) ?: [:]) as Map
            List rows = (res.resultSet ?: []) as List
            out.addAll(rows as List<Map>)
            int total = (res.totalCount ?: 0) as int
            if (out.size() >= total || !rows) return out
            page++
        }
    }

    /** All products the account can download, de-duplicated on (productNumber, merchant). */
    List<Map> products() {
        Set seen = [] as Set
        List<Map> out = []
        ['download', 'entitlement'].each { kind ->
            search(kind).each { Map r ->
                def key = [r.productNumber, r.merchant]
                if (key in seen) return
                seen << key
                out << [productNumber   : r.productNumber,
                        productName     : r.productName,
                        productVersion  : r.productVersion ?: '-',
                        productFamily   : r.productFamily,
                        merchant        : r.merchant,
                        invitationId    : r.invitationId ?: null,
                        pdapiOrderNumber: r.pdapiOrderNumber ?: null,
                        contractNumber  : r.contractNumber ?: null,
                        source          : kind]
            }
        }
        return out
    }

    /** File listing for one product (as returned by products()). URLs are pre-signed — fetch fresh before downloading. */
    Map files(Map p) {
        Map res = (post('/common/api/download/getProductDownload', [
                productNumber   : p.productNumber,
                productVersion  : p.productVersion ?: '-',
                merchant        : p.merchant,
                contractNumber  : p.contractNumber,
                invitationId    : p.invitationId,
                saAuth          : null,
                pdapiOrderNumber: p.pdapiOrderNumber]) ?: [:]) as Map
        List details = (res.downloadDetailsList ?: []) as List
        if (!details) return [productName: p.productName, releaseDate: null, showEula: null, files: []]
        Map d = details[0] as Map
        return [productName: d.productName,
                releaseDate: d.releaseDate,
                showEula   : d.showEula,
                files      : ((d.downloadFilesList ?: []) as List<Map>).collect { Map f ->
                    [fileName: f.fileName, size: "${f.fileSize}${f.fileSizeUnit}".toString(), fileType: f.fileType,
                     sha512: f.checkSum, url: f.url, sigUrl: f.signatureFileUrl,
                     version: versionOf(f.fileName as String)]
                }]
    }

    static String versionOf(String fileName) {
        Matcher m = VERSION_RE.matcher(fileName ?: '')
        return m.find() ? m.group(1) : null
    }

    /**
     * Compare release versions. Only the dotted part counts: the package suffix
     * ("9.0.2-1") is a build number and the appliance manifest reports "9.0.2".
     */
    static int compareVersions(String a, String b) {
        if (!a || !b) return 0
        List<Integer> pa = a.split('-')[0].split(/\./).collect { it.isInteger() ? it as int : 0 }
        List<Integer> pb = b.split('-')[0].split(/\./).collect { it.isInteger() ? it as int : 0 }
        int n = Math.max(pa.size(), pb.size())
        for (int i = 0; i < n; i++) {
            int x = i < pa.size() ? pa[i] : 0
            int y = i < pb.size() ? pb[i] : 0
            if (x != y) return x <=> y
        }
        return 0
    }
}
