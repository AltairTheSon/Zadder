package com.tvbrowser

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

/** Kotlin side of Brave's adblock-rust engine (native code lives in /rust). */
object AdBlock {
    private const val TAG = "AdBlock"
    private const val MAX_AGE_MS = 3L * 24 * 60 * 60 * 1000

    // Trim this list if you want it even lighter.
    private val LISTS = listOf(
        "easylist.txt" to "https://easylist.to/easylist/easylist.txt",
        "easyprivacy.txt" to "https://easylist.to/easylist/easyprivacy.txt",
        "ublock-filters.txt" to "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/filters.txt",
    )

    @Volatile
    var ready = false
        private set

    @Volatile
    var enabled = true

    val blockedCount = AtomicInteger(0)

    private val nativeOk: Boolean = try {
        System.loadLibrary("adblock_jni"); true
    } catch (t: Throwable) {
        Log.e(TAG, "Native library failed to load", t); false
    }

    @JvmStatic external fun nativeLoad(rules: String): Boolean
    @JvmStatic external fun nativeShouldBlock(url: String, source: String, type: String): Boolean
    @JvmStatic external fun nativeCosmetic(url: String): String?

    fun start(ctx: Context, onStatus: (String) -> Unit) {
        if (!nativeOk) { onStatus("Ad blocker unavailable (native library failed to load)"); return }
        Thread {
            try {
                onStatus("Ad blocker: loading filter lists...")
                val dir = File(ctx.filesDir, "filters").apply { mkdirs() }
                val sb = StringBuilder()
                for ((name, url) in LISTS) {
                    val f = File(dir, name)
                    if (!f.exists() || System.currentTimeMillis() - f.lastModified() > MAX_AGE_MS) download(url, f)
                    if (f.exists()) sb.append(f.readText()).append('\n')
                }
                if (sb.isEmpty()) { onStatus("Ad blocker: no filter lists (check network)"); return@Thread }
                ready = nativeLoad(sb.toString())
                onStatus(if (ready) "Ad blocker ready" else "Ad blocker failed to load")
            } catch (t: Throwable) {
                Log.e(TAG, "start failed", t); onStatus("Ad blocker error: ${t.message}")
            }
        }.apply { name = "adblock-init"; start() }
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

    fun shouldBlock(url: String, source: String, type: String): Boolean {
        if (!enabled || !ready) return false
        val b = nativeShouldBlock(url, source, type)
        if (b) blockedCount.incrementAndGet()
        return b
    }

    fun cosmeticCss(url: String): String {
        if (!enabled || !ready) return ""
        return nativeCosmetic(url) ?: ""
    }
}
