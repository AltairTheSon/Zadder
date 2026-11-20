package com.tvbrowser

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Process
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

/** Kotlin side of Brave's adblock-rust engine (native code lives in /rust). */
object AdBlock {
    private const val TAG = "AdBlock"
    private const val MAX_AGE_MS = 3L * 24 * 60 * 60 * 1000

    enum class Mode { OFF, STANDARD, AGGRESSIVE }

    @Volatile
    var mode = Mode.STANDARD

    /** Set when a previous start died while loading the lists (usually out of memory): use only the small custom set. */
    @Volatile
    var forceLite = false

    val enabled: Boolean get() = mode != Mode.OFF
    val aggressive: Boolean get() = mode == Mode.AGGRESSIVE

    // The engine always uses these lists (Standard and Aggressive share one engine, so switching is instant).
    private val CORE = listOf(
        "easylist.txt" to "https://easylist.to/easylist/easylist.txt",
        "easyprivacy.txt" to "https://easylist.to/easylist/easyprivacy.txt",
        "ublock-filters.txt" to "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/filters.txt",
        "ublock-unbreak.txt" to "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/unbreak.txt",
    )

    // Aggressive adds a big list of ad / tracker / pop-up domains (Hagezi "Pro" + "Pop-up ads").
    // They are stored as 64-bit hashes (about 2 MB in memory) and only block third-party requests.
    private val DOMAIN_LISTS = listOf(
        "hagezi-pro.txt" to "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/pro-onlydomains.txt",
        "hagezi-popup.txt" to "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/popupads-onlydomains.txt",
    )
    private const val DOMAINS_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

    // ---- Your own block list. Always active in Standard and Aggressive (not when the shield is OFF).
    // It is checked directly in Kotlin, so it works instantly at startup and even if the engine fails.
    // These domains are blocked together with all their subdomains.
    private val CUSTOM_DOMAINS = listOf(
        "acscdn.com",
        "histats.com",
        "cloudflareinsights.com",
        "googletagmanager.com",
        "ligabletownman.com",
        "fx9olhkekguc56hoqj6mpy.rest",
        "rbhzzmglkf3wm8v.cfd",
        "llvpn.com",
        "corornisoralogy.cfd",
    )
    private const val CUSTOM_PATH_HOST = "graphcomment.com"
    private const val CUSTOM_PATH = "/api/pub/view"

    // The same list as filter rules, so the engine knows about it too.
    private val CUSTOM = (CUSTOM_DOMAINS.map { "||$it^" } + "||$CUSTOM_PATH_HOST$CUSTOM_PATH").joinToString("\n")

    @Volatile
    var ready = false
        private set

    @Volatile
    private var loading = false

    @Volatile
    private var loadedSet = ""

    val blockedCount = AtomicInteger(0)

    private val nativeOk: Boolean = try {
        System.loadLibrary("adblock_jni"); true
    } catch (t: Throwable) {
        Log.e(TAG, "Native library failed to load", t); false
    }

    @JvmStatic external fun nativeLoad(rules: String): Boolean
    @JvmStatic external fun nativeLoadCache(path: String): Boolean
    @JvmStatic external fun nativeSaveCache(path: String): Boolean
    @JvmStatic external fun nativeShouldBlock(url: String, source: String, type: String): Boolean
    @JvmStatic external fun nativeCosmetic(url: String): String?

    private fun wanted() = if (forceLite) "lite" else "standard"

    /** Call when the user switches the shield: gives the full lists another chance after a failure. */
    fun resetFailure(ctx: Context) {
        forceLite = false
        ctx.getSharedPreferences("tvb", Context.MODE_PRIVATE).edit().putBoolean("engine_died", false).apply()
    }

    private fun lastRunDied(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 30) return true
        return try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val r = am.getHistoricalProcessExitReasons(ctx.packageName, 0, 1).firstOrNull()?.reason ?: return true
            r == ApplicationExitInfo.REASON_LOW_MEMORY || r == ApplicationExitInfo.REASON_CRASH_NATIVE ||
                r == ApplicationExitInfo.REASON_SIGNALED || r == ApplicationExitInfo.REASON_CRASH ||
                r == ApplicationExitInfo.REASON_ANR || r == ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE
        } catch (e: Exception) {
            true
        }
    }

    /** Loads (or reloads, when the mode changed) the filter lists in the background. */
    fun ensureLoaded(ctx: Context, onStatus: (String) -> Unit) {
        if (aggressive) ensureDomains(ctx, onStatus)
        if (!nativeOk) { onStatus("Ad blocker engine unavailable (your custom block list still works)"); return }
        if (mode == Mode.OFF || loading) return
        val prefs = ctx.getSharedPreferences("tvb", Context.MODE_PRIVATE)
        // If the last run was killed while parsing the lists, don't try the big lists again automatically.
        if (prefs.getBoolean("engine_loading", false)) {
            prefs.edit().putBoolean("engine_loading", false).apply()
            if (lastRunDied(ctx)) {
                prefs.edit().putBoolean("engine_died", true).apply()
                onStatus("The ad-block lists ran out of memory last time. Using the small list; tap the shield to retry.")
            }
        }
        if (prefs.getBoolean("engine_died", false)) forceLite = true
        val set = wanted()
        if (set == loadedSet && ready) return
        loading = true
        ready = false
        Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            var ok = false
            try {
                val dir = File(ctx.filesDir, "filters").apply { mkdirs() }
                val cache = File(dir, "engine-$set.bin")
                val appStamp = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime } catch (e: Exception) { 0L }
                val fresh = set != "lite" && cache.exists() && cache.lastModified() > appStamp &&
                    System.currentTimeMillis() - cache.lastModified() < MAX_AGE_MS
                if (fresh && nativeLoadCache(cache.path)) {
                    ok = true
                    onStatus("Ad blocker ready ($set)")
                } else {
                    if (set != "lite") onStatus("Building the $set ad-block lists (first start takes a minute)...")
                    val sb = StringBuilder(4_000_000)
                    if (set != "lite") {
                        for ((name, url) in CORE) {
                            val f = File(dir, name)
                            if (!f.exists() || System.currentTimeMillis() - f.lastModified() > MAX_AGE_MS) download(url, f)
                            if (f.exists()) appendFiltered(f, sb)
                        }
                    }
                    sb.append(CUSTOM).append('\n')
                    val text = sb.toString()
                    sb.setLength(0)
                    prefs.edit().putBoolean("engine_loading", true).commit()   // survives a crash in the native code
                    ok = nativeLoad(text)
                    prefs.edit().putBoolean("engine_loading", false).commit()
                    if (ok && set != "lite") nativeSaveCache(cache.path)
                    onStatus(if (ok) "Ad blocker ready ($set)" else "Ad blocker failed to load")
                }
                if (ok) loadedSet = set
                ready = ok
            } catch (t: Throwable) {
                Log.e(TAG, "load failed", t); onStatus("Ad blocker error: ${t.message}")
            } finally {
                loading = false
            }
            // The mode may have been switched while we were loading (never retry after a failure).
            if (ok && mode != Mode.OFF && wanted() != loadedSet) ensureLoaded(ctx, onStatus)
        }.apply { name = "adblock-init"; start() }
    }

    /**
     * Copies a filter list, dropping what this browser never uses so the engine stays small:
     * comments, generic (site-independent) element-hiding rules and scriptlet rules.
     */
    private fun appendFiltered(f: File, sb: StringBuilder) {
        f.useLines { lines ->
            for (line in lines) {
                if (line.isEmpty()) continue
                val c = line[0]
                if (c == '!' || c == '[') continue
                if (line.startsWith("##") || line.startsWith("#@#") || line.startsWith("#?#") ||
                    line.startsWith("#\$#") || line.startsWith("#%#")) continue
                if (line.contains("+js(") || line.contains("#%#") || line.contains("#@%#")) continue
                sb.append(line).append('\n')
            }
        }
    }

    private fun download(url: String, dest: File) {
        try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 15000; c.readTimeout = 30000
            if (c.responseCode != 200) return
            val tmp = File(dest.path + ".tmp")
            c.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            tmp.renameTo(dest)
        } catch (t: Throwable) {
            Log.w(TAG, "download failed: $url", t)
        }
    }

    @Volatile
    private var domainHashes = LongArray(0)

    @Volatile
    private var domainsLoading = false

    val domainCount: Int get() = domainHashes.size

    private fun h64(s: String): Long {
        var h = -3750763034362895579L
        for (c in s) h = (h xor c.code.toLong()) * 1099511628211L
        return h
    }

    /** Loads the Aggressive domain list in the background (small and fast; cached for a week). */
    private fun ensureDomains(ctx: Context, onStatus: (String) -> Unit) {
        if (domainsLoading || domainHashes.isNotEmpty()) return
        domainsLoading = true
        Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            try {
                val dir = File(ctx.filesDir, "filters").apply { mkdirs() }
                val cache = File(dir, "domains.bin")
                if (!(cache.exists() && System.currentTimeMillis() - cache.lastModified() < DOMAINS_MAX_AGE_MS)) {
                    onStatus("Aggressive shield: downloading the tracker domain list...")
                    var arr = LongArray(320_000)
                    var n = 0
                    for ((name, url) in DOMAIN_LISTS) {
                        val f = File(dir, name)
                        download(url, f)
                        if (!f.exists()) continue
                        f.useLines { lines ->
                            for (line in lines) {
                                val d = line.trim().lowercase()
                                if (d.isEmpty() || d[0] == '#' || d[0] == '!' || d.contains('/') || d.contains(' ')) continue
                                if (n == arr.size) arr = arr.copyOf(n * 2)
                                arr[n++] = h64(d)
                            }
                        }
                        f.delete()
                    }
                    if (n > 0) {
                        java.util.Arrays.sort(arr, 0, n)
                        var m = 0
                        for (i in 0 until n) if (i == 0 || arr[i] != arr[i - 1]) arr[m++] = arr[i]
                        val out = arr.copyOf(m)
                        val bb = ByteBuffer.allocate(m * 8)
                        bb.asLongBuffer().put(out)
                        val tmp = File(cache.path + ".tmp")
                        tmp.writeBytes(bb.array())
                        tmp.renameTo(cache)
                    }
                }
                if (cache.exists()) {
                    val lb = ByteBuffer.wrap(cache.readBytes()).asLongBuffer()
                    val a = LongArray(lb.remaining())
                    lb.get(a)
                    domainHashes = a
                    onStatus("Aggressive shield ready (${a.size} domains)")
                } else {
                    onStatus("Aggressive shield: could not download the domain list (check the network)")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "domain list failed", t)
            } finally {
                domainsLoading = false
            }
        }.apply { name = "adblock-domains"; start() }
    }

    private fun domainBlocked(url: String): Boolean {
        val arr = domainHashes
        if (arr.isEmpty()) return false
        val host = try { Uri.parse(url).host?.lowercase() } catch (e: Exception) { null } ?: return false
        var start = 0
        while (true) {
            if (java.util.Arrays.binarySearch(arr, h64(host.substring(start))) >= 0) return true
            val dot = host.indexOf('.', start)
            if (dot < 0) return false
            val next = dot + 1
            if (host.indexOf('.', next) < 0) return false   // don't test a bare TLD like "com"
            start = next
        }
    }

    private fun baseDomain(url: String): String {
        val host = try { Uri.parse(url).host ?: "" } catch (e: Exception) { "" }
        val parts = host.split('.')
        return if (parts.size <= 2) host else parts.takeLast(2).joinToString(".")
    }

    // YouTube stops playing ("Something went wrong") when only some of its ad/telemetry hosts are blocked,
    // so the big Aggressive domain list is never applied to YouTube or to requests to its servers.
    private val DOMAIN_LIST_EXEMPT = listOf(
        "youtube.com", "youtu.be", "youtube-nocookie.com", "googlevideo.com", "ytimg.com", "ggpht.com",
    )

    private fun hostOf(url: String): String? = try { Uri.parse(url).host?.lowercase() } catch (e: Exception) { null }

    private fun exempt(host: String?): Boolean {
        if (host == null) return false
        for (d in DOMAIN_LIST_EXEMPT) if (host == d || host.endsWith(".$d")) return true
        return false
    }

    private fun domainListApplies(url: String, source: String): Boolean =
        !exempt(hostOf(url)) && !exempt(hostOf(source))

    private fun isThirdParty(url: String, source: String): Boolean {
        if (source.isEmpty()) return false
        val a = baseDomain(url)
        val b = baseDomain(source)
        return a.isNotEmpty() && b.isNotEmpty() && a != b
    }

    /** Your custom list, checked without the engine. */
    private fun customBlocked(url: String): Boolean {
        val uri = try { Uri.parse(url) } catch (e: Exception) { return false }
        val host = uri.host?.lowercase() ?: return false
        for (d in CUSTOM_DOMAINS) if (host == d || host.endsWith(".$d")) return true
        if ((host == CUSTOM_PATH_HOST || host.endsWith(".$CUSTOM_PATH_HOST")) &&
            (uri.path ?: "").startsWith(CUSTOM_PATH)) return true
        return false
    }

    fun shouldBlock(url: String, source: String, type: String): Boolean {
        if (!enabled) return false
        if (customBlocked(url)) { blockedCount.incrementAndGet(); return true }
        if (aggressive && domainListApplies(url, source) && isThirdParty(url, source) && domainBlocked(url)) { blockedCount.incrementAndGet(); return true }
        if (!ready) return false
        val b = nativeShouldBlock(url, source, type)
        if (b) blockedCount.incrementAndGet()
        return b
    }

    /** For main-frame navigations and popups: true if the destination looks like an ad/redirect domain. */
    fun shouldBlockNavigation(url: String, source: String): Boolean {
        if (!enabled) return false
        if (customBlocked(url)) { blockedCount.incrementAndGet(); return true }
        if (aggressive && domainListApplies(url, source) && isThirdParty(url, source) && domainBlocked(url)) { blockedCount.incrementAndGet(); return true }
        if (!ready) return false
        for (type in arrayOf("document", "subdocument", "popup")) {
            if (nativeShouldBlock(url, source, type)) { blockedCount.incrementAndGet(); return true }
        }
        return false
    }

    fun cosmeticCss(url: String): String {
        if (!enabled || !ready) return ""
        return nativeCosmetic(url) ?: ""
    }
}
