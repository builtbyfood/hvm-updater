package com.morpheusdata.hvmupdater

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Cached view of what HPE will let this account download, refreshed on a
 * schedule and persisted to disk so a plugin restart doesn't lose it.
 * URLs are deliberately NOT cached — they're pre-signed; freshFile() re-fetches.
 */
@Slf4j
class Catalog {

    final Closure<Map> settings
    TokenManager tokens
    final File cacheFile
    volatile Map cache = [products: [], refreshedAt: null, error: null]
    volatile boolean tokenOk = false
    private ScheduledExecutorService sched

    Catalog(Closure<Map> settings, String workDir) {
        this.settings = settings
        this.cacheFile = new File(workDir, 'catalog.json')
        try { if (cacheFile.exists()) cache = new JsonSlurper().parseText(cacheFile.getText('UTF-8')) as Map } catch (Exception ignored) {}
    }

    Map snapshot() { cache }

    private SwcClient client() { new SwcClient(tokens ? tokens.current() : (settings().hpeToken as String)) }

    /** Run an SWC call, refreshing the token once on auth failure. */
    private <T> T withAuth(Closure<T> call) {
        try {
            return call(client())
        } catch (SwcClient.AuthException e) {
            if (tokens) tokens.store(null)   // clear the dead token so the page prompts for sign-in
            throw e
        }
    }

    /** Products to track: settings.products (comma list) or everything the account sees. */
    private List<Map> selectProducts(SwcClient c) {
        List<Map> all = c.products()
        String want = (settings().products ?: '') as String
        Set<String> filter = want.split(',').collect { it.trim() }.findAll { it } as Set
        return filter ? all.findAll { it.productNumber in filter } : all
    }

    synchronized Map refresh() {
        try {
            if (!client().hasToken()) { tokenOk = false; cache = cache + [error: 'not signed in to HPE — use the Sign in to HPE box on the plugin page']; return cache }
            List<Map> products = withAuth { SwcClient c -> selectProducts(c).collect { Map p ->
                Map info = c.files(p)
                p + [productName: info.productName ?: p.productName, releaseDate: info.releaseDate, showEula: info.showEula,
                     files: (info.files as List<Map>).collect { Map f -> f.findAll { k, v -> k != 'url' && k != 'sigUrl' } }]
            } }
            tokenOk = true
            cache = [products: products, refreshedAt: System.currentTimeMillis(), error: null]
            try { cacheFile.parentFile.mkdirs(); cacheFile.setText(JsonOutput.toJson(cache), 'UTF-8') } catch (Exception e) { log.warn("catalog cache write failed: ${e.message}") }
            log.info("hvm-updater catalog refreshed: ${products.size()} product(s), ${products.sum { (it.files as List).size() } ?: 0} file(s)")
        } catch (SwcClient.AuthException e) {
            tokenOk = false
            cache = cache + [error: e.message]
        } catch (Exception e) {
            log.error("catalog refresh failed: ${e.message}")
            cache = cache + [error: e.message]
        }
        return cache
    }

    /** Re-fetch one product listing to get a currently-signed URL for fileName. */
    Map freshFile(String productNumber, String fileName) {
        return withAuth { SwcClient c ->
        Map p = (cache.products as List<Map>).find { it.productNumber == productNumber }
        if (!p) p = c.products().find { it.productNumber == productNumber }
        if (!p) return null
        (c.files(p).files as List<Map>).find { it.fileName == fileName }
        }
    }

    void start(int intervalMinutes) {
        stop()
        sched = Executors.newSingleThreadScheduledExecutor({ r -> Thread t = new Thread(r, 'hvm-updater-catalog'); t.daemon = true; t })
        sched.scheduleWithFixedDelay({ refresh() } as Runnable, 20, Math.max(5, intervalMinutes) * 60L, TimeUnit.SECONDS)
    }

    void stop() { sched?.shutdownNow(); sched = null }
}
