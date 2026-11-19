package com.tvbrowser

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.text.TextUtils
import android.util.Log
import android.view.Choreographer
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.URLEncoder
import java.util.concurrent.Executors

const val HOME_URL = "https://tvbrowser.home/"

class Tab(var web: WebView) {
    @Volatile var pageUrl = ""
    var chainOk = true   // true when the current navigation chain was started by the user
    var lastCosmetic = ""
}

private class Row(val title: String, val sub: String, val onClick: () -> Unit, val onDelete: (() -> Unit)? = null)

class MainActivity : Activity() {
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private lateinit var root: FrameLayout
    private lateinit var webHolder: FrameLayout
    private lateinit var cursor: CursorView
    private lateinit var toolbar: View
    private lateinit var urlBar: EditText
    private lateinit var progress: ProgressBar
    private lateinit var shield: TextView
    private lateinit var btnTabs: TextView
    private lateinit var btnFav: TextView

    private val prefs: SharedPreferences by lazy { getSharedPreferences("tvb", Context.MODE_PRIVATE) }
    private val ui = Handler(Looper.getMainLooper())
    private val home = HOME_URL
    private var density = 1f

    private val tabs = ArrayList<Tab>()
    private var cur = 0
    private val tab: Tab get() = tabs[cur]
    private val web: WebView get() = tabs[cur].web

    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var panel: View? = null
    private var panelScroll: ScrollView? = null

    // pointer + smooth movement
    private var cursorX = 0f
    private var cursorY = 0f
    private var savedY = 0f
    private val held = BooleanArray(4)          // up, down, left, right
    private val heldSince = LongArray(4)
    private val lastBeat = LongArray(4)
    private var lastBack = 0L
    private val bg = Executors.newSingleThreadExecutor()
    private var looping = false
    private var lastFrame = 0L
    private var remX = 0f
    private var remY = 0f
    private var scrollMode = false
    private var okLong = false
    private var lastUp = 0L
    private var lastDown = 0L

    // what the user last tapped (used to tell real clicks from scripted redirects)
    @Volatile private var lastTap = 0L
    @Volatile private var tapHref = ""
    private var lastToast = 0L

    private val reScript = Regex(".*\\.m?js$")
    private val reImage = Regex(".*\\.(png|jpe?g|gif|webp|svg|ico|avif|bmp)$")
    private val reFont = Regex(".*\\.(woff2?|ttf|otf)$")
    private val reMedia = Regex(".*\\.(mp4|webm|m3u8|mp3|ogg|m4a)$")

    private val ticker = object : Runnable {
        override fun run() { updateShield(); ui.postDelayed(this, 1000) }
    }

    private inner class TapBridge {
        @JavascriptInterface
        fun tap(href: String?) { tapHref = href ?: ""; lastTap = SystemClock.uptimeMillis() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        density = resources.displayMetrics.density

        root = findViewById(R.id.root)
        webHolder = findViewById(R.id.webHolder)
        cursor = findViewById(R.id.cursor)
        toolbar = findViewById(R.id.toolbar)
        urlBar = findViewById(R.id.urlBar)
        progress = findViewById(R.id.progress)
        shield = findViewById(R.id.btnShield)
        btnTabs = findViewById(R.id.btnTabs)
        btnFav = findViewById(R.id.btnFav)

        AdBlock.mode = AdBlock.Mode.values().getOrElse(prefs.getInt("mode", 1)) { AdBlock.Mode.STANDARD }

        val prevHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { th, ex ->
            try { prefs.edit().putString("last_crash", Log.getStackTraceString(ex).take(1500)).commit() } catch (e: Throwable) { }
            prevHandler?.uncaughtException(th, ex)
        }

        findViewById<View>(R.id.btnBack).setOnClickListener { if (web.canGoBack()) web.goBack() }
        findViewById<View>(R.id.btnFwd).setOnClickListener { if (web.canGoForward()) web.goForward() }
        findViewById<View>(R.id.btnReload).setOnClickListener { web.reload() }
        findViewById<View>(R.id.btnHome).setOnClickListener { tab.chainOk = true; web.loadUrl(home) }
        btnTabs.setOnClickListener { showTabs() }
        findViewById<View>(R.id.btnFavs).setOnClickListener { showFavs() }
        btnFav.setOnClickListener { toggleFav() }
        shield.setOnClickListener { cycleShield() }

        urlBar.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                load(v.text.toString()); hideKeyboard(); web.requestFocus(); true
            } else false
        }
        urlBar.setOnFocusChangeListener { _, focused ->
            if (focused) ui.postDelayed({ imm().showSoftInput(urlBar, 0) }, 100)
        }

        newTab(intent?.dataString ?: home)
        root.post {
            cursorX = root.width / 2f
            cursorY = root.height / 2f
            cursor.setPos(cursorX, cursorY)
        }
        AdBlock.ensureLoaded(applicationContext, ::status)
        ui.postDelayed({ reportLastProblem() }, 1500)
    }

    // ---------------------------------------------------------------- tabs

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    private fun configure(w: WebView, t: Tab) {
        val s = w.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.setSupportMultipleWindows(true)                  // popups reach onCreateWindow so we can vet them
        s.javaScriptCanOpenWindowsAutomatically = false     // scripts can't open windows without a tap
        // Ask sites for their desktop layout: it suits a 1080p TV better than the phone layout.
        val chrome = Regex("Chrome/([\\d.]+)").find(WebSettings.getDefaultUserAgent(this))?.groupValues?.get(1) ?: "124.0.0.0"
        s.userAgentString =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chrome Safari/537.36"
        w.isFocusable = true
        w.addJavascriptInterface(TapBridge(), "TVB")

        w.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                if (request == null) return null
                val url = request.url.toString()
                if (isHome(url)) {
                    return if (request.isForMainFrame)
                        WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(homeHtml().toByteArray(Charsets.UTF_8)))
                    else
                        WebResourceResponse("text/plain", "utf-8", 404, "Not Found", null, ByteArrayInputStream(ByteArray(0)))
                }
                if (request.isForMainFrame) return null
                if (!url.startsWith("http")) return null
                val source = t.pageUrl.ifEmpty { url }
                return if (AdBlock.shouldBlock(url, source, guessType(request)))
                    WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                else null
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val req = request ?: return false
                val scheme = req.url.scheme ?: return false
                if (scheme != "http" && scheme != "https") {
                    return !(scheme == "about" || scheme == "data" || scheme == "blob")
                }
                if (!req.isForMainFrame || AdBlock.mode == AdBlock.Mode.OFF) return false
                return blockNavigation(t, req)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                t.pageUrl = url ?: ""
                t.lastCosmetic = ""
                if (t === tab) {
                    if (!urlBar.hasFocus()) urlBar.setText(if (isHome(t.pageUrl)) "" else t.pageUrl)
                    progress.visibility = View.VISIBLE
                    updateFavButton()
                }
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request != null && request.isForMainFrame && isHome(request.url.toString())) ui.post { t.web.loadUrl(home) }
            }

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                ui.post { recoverTab(t) }
                return true
            }

            override fun onPageCommitVisible(view: WebView?, url: String?) { injectBridge(t); injectCosmetic(t, url) }

            override fun onPageFinished(view: WebView?, url: String?) {
                injectBridge(t)
                injectCosmetic(t, url)
                if (t === tab) { progress.visibility = View.GONE; updateFavButton() }
            }
        }

        w.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (t === tab) progress.progress = newProgress
            }

            override fun onShowCustomView(view: View?, callback: WebChromeClient.CustomViewCallback?) {
                if (view == null) return
                customView = view
                customCallback = callback
                root.addView(view, FrameLayout.LayoutParams(MATCH, MATCH))
                cursor.bringToFront()
            }

            override fun onHideCustomView() = hideCustom()

            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
                val msg = resultMsg ?: return false
                val transport = msg.obj as? WebView.WebViewTransport ?: return false
                val popup = WebView(this@MainActivity)
                var handled = false
                fun handle(url: String?) {
                    if (handled || url.isNullOrEmpty() || url == "about:blank") return
                    handled = true
                    ui.post {
                        val tapped = recentTap() || isUserGesture
                        val ok = url.startsWith("http") && (AdBlock.mode == AdBlock.Mode.OFF ||
                            (!AdBlock.shouldBlockNavigation(url, t.pageUrl) &&
                                (if (AdBlock.aggressive) recentTap() && tapHref.isNotEmpty() else tapped)))
                        if (ok) newTab(url) else notifyBlocked("Blocked popup")
                        popup.stopLoading(); popup.destroy()
                    }
                }
                popup.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView?, r: WebResourceRequest?): Boolean {
                        handle(r?.url?.toString()); return true
                    }
                    override fun shouldInterceptRequest(v: WebView?, r: WebResourceRequest?): WebResourceResponse? {
                        if (r != null && r.isForMainFrame) {
                            handle(r.url.toString())
                            return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                        }
                        return null
                    }
                }
                ui.postDelayed({ if (!handled) { handled = true; popup.destroy() } }, 10000)
                transport.webView = popup
                msg.sendToTarget()
                return true
            }
        }
    }

    private fun newTab(url: String?, activate: Boolean = true): Tab {
        val w = WebView(this)
        val t = Tab(w)
        configure(w, t)
        tabs.add(t)
        webHolder.addView(w, FrameLayout.LayoutParams(MATCH, MATCH))
        if (activate) switchTo(tabs.size - 1) else w.visibility = View.GONE
        if (url != null) w.loadUrl(url)
        updateTabsLabel()
        return t
    }

    private fun switchTo(i: Int) {
        cur = i
        for ((idx, t) in tabs.withIndex()) t.web.visibility = if (idx == i) View.VISIBLE else View.GONE
        web.requestFocus()
        if (!urlBar.hasFocus()) urlBar.setText(if (isHome(tab.pageUrl)) "" else tab.pageUrl)
        progress.visibility = View.GONE
        updateTabsLabel()
        updateFavButton()
    }

    private fun closeTab(i: Int) {
        if (tabs.size == 1) { tab.chainOk = true; web.loadUrl(home); return }
        val t = tabs.removeAt(i)
        webHolder.removeView(t.web)
        t.web.destroy()
        if (cur >= tabs.size) cur = tabs.size - 1 else if (i < cur) cur--
        switchTo(cur)
    }

    /** The WebView's renderer process was killed (usually low memory): rebuild this tab instead of crashing. */
    private fun recoverTab(t: Tab) {
        val i = tabs.indexOf(t)
        if (i < 0) return
        webHolder.removeView(t.web)
        try { t.web.destroy() } catch (e: Exception) { }
        val w = WebView(this)
        t.web = w
        t.pageUrl = ""
        t.chainOk = true
        configure(w, t)
        webHolder.addView(w, FrameLayout.LayoutParams(MATCH, MATCH))
        w.visibility = if (i == cur) View.VISIBLE else View.GONE
        w.loadUrl(home)
        if (i == cur) w.requestFocus()
        toast("The page ran out of memory and was reset")
    }

    private fun updateTabsLabel() { btnTabs.text = "Tabs (${tabs.size})" }

    // ---------------------------------------------------------------- ad / redirect policy

    private fun isHome(url: String) = url.startsWith("https://tvbrowser.home")

    private fun homeHtml(): String {
        val colors = arrayOf("#e8710a", "#1a73e8", "#188038", "#a142f4", "#d93025", "#12b5cb")
        val sb = StringBuilder()
        sb.append("<!doctype html><html><head><meta charset='utf-8'><title>Home</title><style>")
        sb.append("body{margin:0;background:#101114;color:#fff;font-family:sans-serif}")
        sb.append(".w{max-width:900px;margin:0 auto;padding:36px 20px}")
        sb.append("form{margin:0 0 32px}")
        sb.append("input{width:100%;box-sizing:border-box;padding:16px 22px;font-size:26px;border-radius:32px;border:0;background:#2a2b2f;color:#fff}")
        sb.append("h2{font-size:22px;color:#9aa0a6;font-weight:normal;margin:0 0 16px}")
        sb.append(".g{display:flex;flex-wrap:wrap;gap:18px}")
        sb.append(".t{display:block;width:200px;padding:18px 10px;background:#1e1f23;border-radius:14px;text-align:center;color:#fff;text-decoration:none}")
        sb.append(".i{width:64px;height:64px;line-height:64px;border-radius:50%;margin:0 auto 10px;font-size:30px;font-weight:bold}")
        sb.append(".n{font-size:18px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}")
        sb.append(".h{color:#9aa0a6;font-size:14px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}")
        sb.append(".e{color:#9aa0a6;font-size:20px;line-height:1.5}")
        sb.append("</style></head><body><div class='w'>")
        sb.append("<form action='https://search.brave.com/search' method='get'>")
        sb.append("<input name='q' placeholder='Search with Brave' autocomplete='off'></form>")
        sb.append("<h2>Favorites</h2>")
        val list = favs()
        if (list.isEmpty()) {
            sb.append("<div class='e'>No favorites yet.<br>Open any site and choose <b>+ Fav</b> in the toolbar to pin it here.</div>")
        } else {
            sb.append("<div class='g'>")
            for ((i, f) in list.withIndex()) {
                val host = try { Uri.parse(f.second).host ?: f.second } catch (e: Exception) { f.second }
                val letter = (f.first.trim().firstOrNull() ?: '?').uppercaseChar().toString()
                sb.append("<a class='t' href='").append(TextUtils.htmlEncode(f.second)).append("'>")
                sb.append("<div class='i' style='background:").append(colors[i % colors.size]).append("'>")
                    .append(TextUtils.htmlEncode(letter)).append("</div>")
                sb.append("<div class='n'>").append(TextUtils.htmlEncode(f.first)).append("</div>")
                sb.append("<div class='h'>").append(TextUtils.htmlEncode(host)).append("</div></a>")
            }
            sb.append("</div>")
        }
        sb.append("</div></body></html>")
        return sb.toString()
    }

    private fun recentTap() = SystemClock.uptimeMillis() - lastTap < 3000

    private fun baseDomain(url: String): String {
        val host = try { Uri.parse(url).host ?: "" } catch (e: Exception) { "" }
        val parts = host.split('.')
        return if (parts.size <= 2) host else parts.takeLast(2).joinToString(".")
    }

    private fun isCrossSite(from: String, to: String): Boolean {
        if (from.isEmpty()) return false
        val a = baseDomain(from)
        val b = baseDomain(to)
        return a.isNotEmpty() && b.isNotEmpty() && a != b
    }

    private fun notifyBlocked(text: String) {
        val now = SystemClock.uptimeMillis()
        if (now - lastToast < 3000) return
        lastToast = now
        runOnUiThread { Toast.makeText(this, text, Toast.LENGTH_SHORT).show() }
    }

    /**
     * Standard: block known ad domains and scripted jumps to another site with no tap.
     * Aggressive: additionally, a page's own buttons/links can't take you to a different site
     * (including via redirects) unless a link you tapped visibly points there.
     */
    private fun blockNavigation(t: Tab, req: WebResourceRequest): Boolean {
        val target = req.url.toString()
        if (isHome(t.pageUrl)) { t.chainOk = true; return false }   // tapping a favorite / using the search box
        if (AdBlock.shouldBlockNavigation(target, t.pageUrl)) { notifyBlocked("Blocked ad redirect"); return true }
        val cross = isCrossSite(t.pageUrl, target)
        if (req.isRedirect) {
            if (AdBlock.aggressive && cross && !t.chainOk) { notifyBlocked("Blocked redirect (Aggressive shield)"); return true }
            return false
        }
        val tapped = req.hasGesture() || recentTap()
        val linkOut = tapped && tapHref.isNotEmpty() && baseDomain(tapHref) == baseDomain(target)
        t.chainOk = cross && linkOut
        val block = cross && (if (AdBlock.aggressive) !linkOut else !tapped)
        if (block) notifyBlocked(if (tapped) "Blocked redirect (Aggressive shield)" else "Blocked automatic redirect")
        return block
    }

    private fun guessType(r: WebResourceRequest): String {
        val path = r.url.path?.lowercase() ?: ""
        val accept = r.requestHeaders["Accept"] ?: ""
        return when {
            reScript.matches(path) -> "script"
            path.endsWith(".css") -> "stylesheet"
            reImage.matches(path) -> "image"
            reFont.matches(path) -> "font"
            reMedia.matches(path) -> "media"
            accept.contains("text/html") -> "subdocument"
            accept.contains("text/css") -> "stylesheet"
            accept.startsWith("image/") -> "image"
            r.requestHeaders["X-Requested-With"] == "XMLHttpRequest" -> "xmlhttprequest"
            else -> "other"
        }
    }

    private fun injectBridge(t: Tab) {
        try {
            t.web.evaluateJavascript(
                "(function(){if(window.__tvb)return;window.__tvb=1;" +
                    "function f(e){var n=e.target,a=n&&n.closest?n.closest('a[href]'):null;TVB.tap(a?a.href:'');}" +
                    "document.addEventListener('pointerdown',f,true);document.addEventListener('click',f,true);})();",
                null
            )
        } catch (e: Exception) { }
    }

    /** Looks the hide-rules up on a background thread (they can be slow) and injects them on the UI thread. */
    private fun injectCosmetic(t: Tab, url: String?) {
        if (url == null || !url.startsWith("http") || isHome(url) || !AdBlock.enabled) return
        if (t.lastCosmetic == url) return
        t.lastCosmetic = url
        bg.execute {
            val css = AdBlock.cosmeticCss(url)
            if (css.isNotEmpty()) {
                val js = "(function(){try{var s=document.querySelector('style[data-tvb]');" +
                    "if(!s){s=document.createElement('style');s.setAttribute('data-tvb','1');" +
                    "(document.head||document.documentElement).appendChild(s);}" +
                    "s.textContent=" + JSONObject.quote(css) + ";}catch(e){}})();"
                runOnUiThread {
                    if (tabs.contains(t)) try { t.web.evaluateJavascript(js, null) } catch (e: Exception) { }
                }
            }
        }
    }

    private fun cycleShield() {
        AdBlock.mode = when (AdBlock.mode) {
            AdBlock.Mode.AGGRESSIVE -> AdBlock.Mode.STANDARD
            AdBlock.Mode.STANDARD -> AdBlock.Mode.OFF
            AdBlock.Mode.OFF -> AdBlock.Mode.AGGRESSIVE
        }
        prefs.edit().putInt("mode", AdBlock.mode.ordinal).apply()
        AdBlock.resetFailure(applicationContext)
        AdBlock.ensureLoaded(applicationContext, ::status)
        toast("Shield: " + AdBlock.mode.name.lowercase())
        updateShield()
        web.reload()
    }

    private fun updateShield() {
        val n = AdBlock.blockedCount.get()
        shield.text = when {
            AdBlock.mode == AdBlock.Mode.OFF -> "Shield: OFF"
            !AdBlock.ready -> "Shield: loading"
            AdBlock.aggressive -> "Aggr ($n)"
            else -> "Std ($n)"
        }
    }

    // ---------------------------------------------------------------- favorites

    private fun favs(): MutableList<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        try {
            val a = JSONArray(prefs.getString("favs", "[]"))
            for (i in 0 until a.length()) { val o = a.getJSONObject(i); out.add(o.getString("t") to o.getString("u")) }
        } catch (e: Exception) { }
        return out
    }

    private fun saveFavs(l: List<Pair<String, String>>) {
        val a = JSONArray()
        for (f in l) a.put(JSONObject().put("t", f.first).put("u", f.second))
        prefs.edit().putString("favs", a.toString()).apply()
    }

    private fun toggleFav() {
        val u = tab.pageUrl
        if (!u.startsWith("http")) return
        if (isHome(u)) { toast("Open a site first, then choose + Fav"); return }
        val l = favs()
        if (l.any { it.second == u }) {
            l.removeAll { it.second == u }; toast("Removed from favorites")
        } else {
            val title = web.title?.takeIf { it.isNotBlank() } ?: u
            l.add(0, title to u); toast("Added to favorites")
        }
        saveFavs(l)
        updateFavButton()
    }

    private fun updateFavButton() { btnFav.text = if (favs().any { it.second == tab.pageUrl }) "- Fav" else "+ Fav" }

    private fun showFavs() {
        val rows = ArrayList<Row>()
        for (f in favs()) {
            rows.add(Row(f.first, f.second, { hidePanel(); tab.chainOk = true; web.loadUrl(f.second) },
                { saveFavs(favs().filter { it.second != f.second }); updateFavButton(); if (isHome(tab.pageUrl)) web.reload(); showFavs() }))
        }
        if (rows.isEmpty()) rows.add(Row("No favorites yet", "Open a page and choose + Fav in the toolbar", { }))
        showPanel("Favorites", rows)
    }

    private fun showTabs() {
        val rows = ArrayList<Row>()
        rows.add(Row("+ New tab", "Opens your favorites", { hidePanel(); newTab(home) }))
        for ((i, t) in tabs.withIndex()) {
            val name = t.web.title?.takeIf { it.isNotBlank() } ?: t.pageUrl.ifEmpty { "New tab" }
            rows.add(Row((if (i == cur) "> " else "") + name, if (isHome(t.pageUrl)) "Favorites" else t.pageUrl, { hidePanel(); switchTo(i) }, { closeTab(i); showTabs() }))
        }
        showPanel("Tabs (${tabs.size})", rows)
    }

    // ---------------------------------------------------------------- panel UI (tabs / favorites)

    private fun dp(v: Int) = (v * density).toInt()

    private fun button(text: String, onClick: () -> Unit): TextView = TextView(this).apply {
        this.text = text
        textSize = 16f
        setTextColor(Color.WHITE)
        setBackgroundColor(0xFF3C4043.toInt())
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(8), dp(16), dp(8))
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun rowView(r: Row): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF2A2B2F.toInt())
            setPadding(dp(16), dp(10), dp(10), dp(10))
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            setOnClickListener { r.onClick() }
        }
        texts.addView(TextView(this).apply {
            text = r.title; textSize = 18f; setTextColor(Color.WHITE)
            setSingleLine(true); ellipsize = TextUtils.TruncateAt.END
        })
        texts.addView(TextView(this).apply {
            text = r.sub; textSize = 13f; setTextColor(0xFF9AA0A6.toInt())
            setSingleLine(true); ellipsize = TextUtils.TruncateAt.END
        })
        row.addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f))
        r.onDelete?.let { d -> row.addView(button("Delete") { d() }) }
        return row
    }

    private fun showPanel(title: String, rows: List<Row>) {
        hidePanel()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xF0121316.toInt())
            setPadding(dp(48), dp(20), dp(48), dp(20))
            isClickable = true
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(TextView(this).apply {
            text = title; textSize = 24f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        head.addView(button("Close") { hidePanel() })
        col.addView(head, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(12) })

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (r in rows) list.addView(rowView(r), LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) })
        val sv = ScrollView(this).apply { addView(list) }
        col.addView(sv, LinearLayout.LayoutParams(MATCH, 0, 1f))

        panel = col
        panelScroll = sv
        root.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))
        cursor.bringToFront()
    }

    private fun hidePanel() {
        panel?.let { root.removeView(it) }
        panel = null
        panelScroll = null
        web.requestFocus()
    }

    private fun hideCustom() {
        customView?.let { root.removeView(it) }
        customView = null
        customCallback?.onCustomViewHidden()
        customCallback = null
    }

    // ---------------------------------------------------------------- navigation helpers

    private fun load(input: String) {
        val s = input.trim()
        if (s.isEmpty()) return
        val url = when {
            s.startsWith("http://") || s.startsWith("https://") -> s
            s.contains(" ") || !s.contains(".") -> "https://search.brave.com/search?q=" + URLEncoder.encode(s, "UTF-8")
            else -> "https://$s"
        }
        tab.chainOk = true
        web.loadUrl(url)
    }

    private fun showInfo(title: String, text: String) {
        android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(title).setMessage(text).setPositiveButton("OK", null).show()
    }

    /** After a crash or a system kill, tell the user why (this also tells me what to fix). */
    private fun reportLastProblem() {
        val saved = prefs.getString("last_crash", null)
        if (saved != null) {
            prefs.edit().remove("last_crash").apply()
            showInfo("The app crashed last time", saved)
            return
        }
        if (Build.VERSION.SDK_INT < 30) return
        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val last = am.getHistoricalProcessExitReasons(packageName, 0, 1).firstOrNull() ?: return
            if (last.timestamp <= prefs.getLong("exit_seen", 0L)) return
            prefs.edit().putLong("exit_seen", last.timestamp).apply()
            val name = when (last.reason) {
                ApplicationExitInfo.REASON_LOW_MEMORY -> "the system killed it (low memory)"
                ApplicationExitInfo.REASON_CRASH -> "it crashed"
                ApplicationExitInfo.REASON_CRASH_NATIVE -> "it crashed (native code)"
                ApplicationExitInfo.REASON_ANR -> "it stopped responding (ANR)"
                ApplicationExitInfo.REASON_SIGNALED -> "the system stopped it"
                ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "it used too many resources"
                else -> return
            }
            var text = (last.description ?: "") + "\nMemory used: " + (last.rss / 1024) + " MB"
            if (last.reason == ApplicationExitInfo.REASON_ANR) {
                try {
                    val trace = last.traceInputStream?.bufferedReader()?.use { r ->
                        val buf = CharArray(6000)
                        val n = r.read(buf)
                        if (n > 0) String(buf, 0, n) else ""
                    } ?: ""
                    val at = trace.indexOf("\"main\" prio")
                    text += "\n" + (if (at >= 0) trace.substring(at) else trace).take(800)
                } catch (e: Exception) { }
            }
            showInfo("Last time $name", text.take(1100))
        } catch (e: Exception) { }
    }

    private fun status(msg: String) { runOnUiThread { toast(msg) } }
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    private fun imm() = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    private fun hideKeyboard() = imm().hideSoftInputFromWindow(urlBar.windowToken, 0)

    // ---------------------------------------------------------------- remote control: smooth pointer + scrolling

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (currentFocus is EditText) { held.fill(false); return super.dispatchKeyEvent(e) }   // typing in the URL bar
        val down = e.action == KeyEvent.ACTION_DOWN
        val idx = when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> 0
            KeyEvent.KEYCODE_DPAD_DOWN -> 1
            KeyEvent.KEYCODE_DPAD_LEFT -> 2
            KeyEvent.KEYCODE_DPAD_RIGHT -> 3
            else -> -1
        }
        if (idx >= 0) {
            if (down) {
                val now = SystemClock.uptimeMillis()
                lastBeat[idx] = now
                if (e.repeatCount == 0) {
                    if (!held[idx]) heldSince[idx] = System.nanoTime()
                    held[idx] = true
                    if (idx == 0) { if (now - lastUp < 350 && !scrollMode) jumpToToolbar(); lastUp = now }
                    if (idx == 1) { if (now - lastDown < 350 && !scrollMode) returnFromToolbar(); lastDown = now }
                    startLoop()
                }
            } else held[idx] = false
            return true
        }
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (down) {
                    if (e.repeatCount == 0) okLong = false
                    else if (!okLong && e.repeatCount >= 2) { okLong = true; toggleScrollMode() }
                } else if (!okLong) {
                    if (scrollMode) toggleScrollMode() else clickAtCursor()
                }
                return true
            }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SEARCH -> { if (down) urlBar.requestFocus(); return true }
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_MEDIA_REWIND -> { if (down) web.pageUp(false); return true }
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { if (down) web.pageDown(false); return true }
        }
        return super.dispatchKeyEvent(e)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) held.fill(false)
    }

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            // Failsafe: if a key-up was ever lost, stop moving after 3 s without a key event.
            val nowMs = SystemClock.uptimeMillis()
            for (i in 0..3) if (held[i] && nowMs - lastBeat[i] > 3000) held[i] = false
            if (!held.any { it }) { looping = false; lastFrame = 0L; return }
            val dt = if (lastFrame == 0L) 0.016f else ((frameTimeNanos - lastFrame) / 1e9f).coerceAtMost(0.05f)
            lastFrame = frameTimeNanos
            step(dt, frameTimeNanos)
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun startLoop() {
        if (looping) return
        looping = true
        lastFrame = 0L
        Choreographer.getInstance().postFrameCallback(frame)
    }

    /** Pixels per second: starts slow for precision, ramps up the longer the key is held. */
    private fun speed(axis: Int, now: Long): Float {
        val t = ((now - heldSince[axis]) / 1e9f).coerceIn(0f, 1f)
        return (240f + 1500f * t * t) * density
    }

    private fun step(dt: Float, now: Long) {
        var dx = 0f
        var dy = 0f
        if (held[0]) dy -= speed(0, now) * dt
        if (held[1]) dy += speed(1, now) * dt
        if (held[2]) dx -= speed(2, now) * dt
        if (held[3]) dx += speed(3, now) * dt
        if (scrollMode) scrollContent(dx * 1.6f, dy * 1.6f) else moveCursor(dx, dy)
    }

    private fun moveCursor(dx: Float, dy: Float) {
        val w = root.width.toFloat()
        val h = root.height.toFloat()
        if (w <= 0f) return
        val zone = 70 * density
        val top = if (panel != null) 0f else toolbar.height.toFloat()
        var nx = cursorX + dx
        var ny = cursorY + dy
        // Pushing against the top/bottom/side edge scrolls the page instead of moving the pointer.
        if (dy > 0f && cursorY >= h - zone) { scrollContent(0f, dy * 1.5f); ny = cursorY }
        else if (dy < 0f && cursorY <= top + zone && contentScrollY() > 0) { scrollContent(0f, dy * 1.5f); ny = cursorY }
        if (dx > 0f && cursorX >= w - zone) { scrollContent(dx * 1.5f, 0f); nx = cursorX }
        else if (dx < 0f && cursorX <= zone) { scrollContent(dx * 1.5f, 0f); nx = cursorX }
        cursorX = nx.coerceIn(0f, w - 1f)
        cursorY = ny.coerceIn(0f, h - 1f)
        cursor.setPos(cursorX, cursorY)
    }

    private fun contentScrollY(): Int = panelScroll?.scrollY ?: web.scrollY

    private fun scrollContent(dx: Float, dy: Float) {
        remX += dx
        remY += dy
        val ix = remX.toInt()
        val iy = remY.toInt()
        remX -= ix
        remY -= iy
        if (ix == 0 && iy == 0) return
        val sv = panelScroll
        if (sv != null) sv.scrollBy(0, iy) else web.scrollBy(ix, iy)
    }

    private fun toggleScrollMode() {
        scrollMode = !scrollMode
        cursor.visibility = if (scrollMode) View.INVISIBLE else View.VISIBLE
        toast(if (scrollMode) "Scroll mode: arrows scroll the page. Press OK to go back to the pointer." else "Pointer mode")
    }

    private fun jumpToToolbar() {
        if (panel != null || cursorY <= toolbar.height) return
        savedY = cursorY
        cursorY = toolbar.height / 2f
        cursor.setPos(cursorX, cursorY)
    }

    private fun returnFromToolbar() {
        if (panel != null || cursorY > toolbar.height) return
        cursorY = if (savedY > toolbar.height) savedY else root.height / 2f
        cursor.setPos(cursorX, cursorY)
    }

    private fun clickAtCursor() {
        val x = cursorX
        val y = cursorY
        val t = SystemClock.uptimeMillis()
        lastTap = t
        tapHref = ""
        val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0)
        down.source = InputDevice.SOURCE_TOUCHSCREEN
        root.dispatchTouchEvent(down)
        down.recycle()
        ui.postDelayed({
            val up = MotionEvent.obtain(t, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0)
            up.source = InputDevice.SOURCE_TOUCHSCREEN
            root.dispatchTouchEvent(up)
            up.recycle()
            ui.postDelayed({ maybeShowKeyboard() }, 250)
        }, 70)
    }

    /** If the tap landed in a text field on the page, bring up the on-screen keyboard. */
    private fun maybeShowKeyboard() {
        if (urlBar.hasFocus() || panel != null) return
        web.evaluateJavascript(
            "(function(){var e=document.activeElement;return !!e&&(e.tagName==='INPUT'||e.tagName==='TEXTAREA'||e.isContentEditable===true)})()"
        ) { r ->
            if (r == "true") { web.requestFocus(); imm().showSoftInput(web, 0) }
        }
    }

    private fun showExitDialog() {
        android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Exit TV Browser?")
            .setPositiveButton("Exit") { _, _ -> finishAndRemoveTask() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (customView != null) { hideCustom(); return }
        if (panel != null) { hidePanel(); return }
        if (urlBar.hasFocus()) { hideKeyboard(); web.requestFocus(); return }
        if (scrollMode) { toggleScrollMode(); return }
        // Two quick Back presses open the exit popup.
        val now = SystemClock.uptimeMillis()
        if (now - lastBack < 1500) { lastBack = 0L; showExitDialog(); return }
        lastBack = now
        when {
            web.canGoBack() -> web.goBack()
            tabs.size > 1 -> closeTab(cur)
            else -> toast("Press Back again to exit")
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.dataString?.let { newTab(it) }
    }

    override fun onResume() { super.onResume(); web.onResume(); ui.post(ticker) }
    override fun onPause() { held.fill(false); ui.removeCallbacks(ticker); web.onPause(); super.onPause() }
    override fun onDestroy() { bg.shutdown(); for (t in tabs) t.web.destroy(); super.onDestroy() }
}
