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

    // Standard = these lists. Aggressive = these + the extra lists below (like Brave's "Aggressive" shield).
    private val CORE = listOf(
        "easylist.txt" to "https://easylist.to/easylist/easylist.txt",
        "easyprivacy.txt" to "https://easylist.to/easylist/easyprivacy.txt",
        "ublock-filters.txt" to "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/filters.txt",
        "ublock-unbreak.txt" to "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/unbreak.txt",
    )
    private val EXTRA = listOf(
        "ublock-privacy.txt" to "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/privacy.txt",
        "ublock-badware.txt" to "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/badware.txt",
        "ublock-abuse.txt" to "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/resource-abuse.txt",
        "adguard-tracking.txt" to "https://filters.adtidy.org/extension/ublock/filters/3.txt",
    )

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

    private fun wanted() = when {
        forceLite -> "lite"
        mode == Mode.AGGRESSIVE -> "aggressive"
        else -> "standard"
    }

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
                        val lists = if (set == "aggressive") CORE + EXTRA else CORE
                        for ((name, url) in lists) {
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
        if (!ready) return false
        val b = nativeShouldBlock(url, source, type)
        if (b) blockedCount.incrementAndGet()
        return b
    }

    /** For main-frame navigations and popups: true if the destination looks like an ad/redirect domain. */
    fun shouldBlockNavigation(url: String, source: String): Boolean {
        if (!enabled) return false
        if (customBlocked(url)) { blockedCount.incrementAndGet(); return true }
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
