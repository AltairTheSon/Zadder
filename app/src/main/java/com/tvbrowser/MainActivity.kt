package com.tvbrowser

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.net.Uri
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.URLEncoder

class MainActivity : Activity() {
    private lateinit var root: FrameLayout
    private lateinit var web: WebView
    private lateinit var cursor: CursorView
    private lateinit var toolbar: View
    private lateinit var urlBar: EditText
    private lateinit var progress: ProgressBar
    private lateinit var shield: TextView

    private val ui = Handler(Looper.getMainLooper())
    private val home = "https://search.brave.com/"
    private var density = 1f
    private var cursorX = 0f
    private var cursorY = 0f
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null

    @Volatile
    private var pageUrl = ""
    private var lastTap = 0L
    private var lastToast = 0L

    private val reScript = Regex(".*\\.m?js$")
    private val reImage = Regex(".*\\.(png|jpe?g|gif|webp|svg|ico|avif|bmp)$")
    private val reFont = Regex(".*\\.(woff2?|ttf|otf)$")
    private val reMedia = Regex(".*\\.(mp4|webm|m3u8|mp3|ogg|m4a)$")

    private val ticker = object : Runnable {
        override fun run() { updateShield(); ui.postDelayed(this, 1000) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        density = resources.displayMetrics.density

        root = findViewById(R.id.root)
        web = findViewById(R.id.web)
        cursor = findViewById(R.id.cursor)
        toolbar = findViewById(R.id.toolbar)
        urlBar = findViewById(R.id.urlBar)
        progress = findViewById(R.id.progress)
        shield = findViewById(R.id.btnShield)

        web.requestFocus()
        root.post {
            cursorX = root.width / 2f
            cursorY = root.height / 2f
            cursor.setPos(cursorX, cursorY)
        }

        findViewById<View>(R.id.btnBack).setOnClickListener { if (web.canGoBack()) web.goBack() }
        findViewById<View>(R.id.btnFwd).setOnClickListener { if (web.canGoForward()) web.goForward() }
        findViewById<View>(R.id.btnReload).setOnClickListener { web.reload() }
        findViewById<View>(R.id.btnHome).setOnClickListener { web.loadUrl(home) }
        shield.setOnClickListener { AdBlock.enabled = !AdBlock.enabled; updateShield(); web.reload() }

        urlBar.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                load(v.text.toString()); hideKeyboard(); web.requestFocus(); true
            } else false
        }
        urlBar.setOnFocusChangeListener { _, focused ->
            if (focused) ui.postDelayed({ imm().showSoftInput(urlBar, 0) }, 100)
        }

        setupWebView()
        AdBlock.start(applicationContext) { msg ->
            runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
        }
        load(intent?.dataString ?: home)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.setSupportMultipleWindows(true)          // popups come to onCreateWindow so we can vet them
        s.javaScriptCanOpenWindowsAutomatically = false  // scripts can't open windows without a tap
        // Ask sites for their desktop layout: it suits a 1080p TV better than the phone layout.
        val chrome = Regex("Chrome/([\\d.]+)").find(s.userAgentString)?.groupValues?.get(1) ?: "124.0.0.0"
        s.userAgentString =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chrome Safari/537.36"

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                if (request == null || request.isForMainFrame) return null
                val url = request.url.toString()
                if (!url.startsWith("http")) return null
                val source = pageUrl.ifEmpty { url }
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
                if (!req.isForMainFrame || !AdBlock.enabled) return false
                val target = req.url.toString()
                // 1) destination is a known ad / redirect domain
                if (AdBlock.shouldBlockNavigation(target, pageUrl)) { notifyBlocked("Blocked ad redirect"); return true }
                // 2) page jumps to another site by itself, with no tap from the user
                val tapped = req.hasGesture() || SystemClock.uptimeMillis() - lastTap < 2500
                if (!tapped && !req.isRedirect && isCrossSite(pageUrl, target)) {
                    notifyBlocked("Blocked automatic redirect"); return true
                }
                return false
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                pageUrl = url ?: ""
                if (!urlBar.hasFocus()) urlBar.setText(url)
                progress.visibility = View.VISIBLE
            }

            override fun onPageCommitVisible(view: WebView?, url: String?) = injectCosmetic(url)

            override fun onPageFinished(view: WebView?, url: String?) {
                injectCosmetic(url)
                progress.visibility = View.GONE
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) { progress.progress = newProgress }

            override fun onShowCustomView(view: View?, callback: WebChromeClient.CustomViewCallback?) {
                if (view == null) return
                customView = view
                customCallback = callback
                root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
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
                        if (url.startsWith("http") && !AdBlock.shouldBlockNavigation(url, pageUrl)) web.loadUrl(url)
                        else notifyBlocked("Blocked popup")
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

    private fun hideCustom() {
        customView?.let { root.removeView(it) }
        customView = null
        customCallback?.onCustomViewHidden()
        customCallback = null
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

    private fun injectCosmetic(url: String?) {
        if (url == null || !url.startsWith("http")) return
        val css = AdBlock.cosmeticCss(url)
        if (css.isEmpty()) return
        val js = "(function(){try{var s=document.querySelector('style[data-tvb]');" +
            "if(!s){s=document.createElement('style');s.setAttribute('data-tvb','1');" +
            "(document.head||document.documentElement).appendChild(s);}" +
            "s.textContent=" + JSONObject.quote(css) + ";}catch(e){}})();"
        web.evaluateJavascript(js, null)
    }

    private fun load(input: String) {
        val s = input.trim()
        if (s.isEmpty()) return
        val url = when {
            s.startsWith("http://") || s.startsWith("https://") -> s
            s.contains(" ") || !s.contains(".") -> "https://search.brave.com/search?q=" + URLEncoder.encode(s, "UTF-8")
            else -> "https://$s"
        }
        web.loadUrl(url)
    }

    private fun updateShield() {
        shield.text = if (AdBlock.enabled) "Ads: ON (${AdBlock.blockedCount.get()})" else "Ads: OFF"
    }

    private fun imm() = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

    private fun hideKeyboard() = imm().hideSoftInputFromWindow(urlBar.windowToken, 0)

    // ---- Remote control: D-pad moves a mouse pointer, OK clicks ----

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (currentFocus is EditText) return super.dispatchKeyEvent(e)   // typing in the URL bar
        val down = e.action == KeyEvent.ACTION_DOWN
        val step = 10 * density * (1 + minOf(e.repeatCount, 30) * 0.15f)
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> { if (down) moveCursor(0f, -step, e.repeatCount); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { if (down) moveCursor(0f, step, e.repeatCount); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { if (down) moveCursor(-step, 0f, e.repeatCount); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { if (down) moveCursor(step, 0f, e.repeatCount); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (down && e.repeatCount == 0) clickAtCursor()
                return true
            }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SEARCH -> { if (down) urlBar.requestFocus(); return true }
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_MEDIA_REWIND -> { if (down) web.pageUp(false); return true }
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { if (down) web.pageDown(false); return true }
        }
        return super.dispatchKeyEvent(e)
    }

    private fun moveCursor(dx: Float, dy: Float, repeat: Int) {
        val w = root.width.toFloat()
        val h = root.height.toFloat()
        val edge = 48 * density
        val topZone = toolbar.height + edge
        var nx = cursorX + dx
        var ny = cursorY + dy

        if (dy > 0 && cursorY >= h - edge) { web.scrollBy(0, dy.toInt()); ny = cursorY }
        else if (dy < 0 && cursorY <= topZone && web.scrollY > 0) {
            // Holding UP for a moment jumps the pointer to the toolbar even when the page is scrolled.
            if (repeat >= 12) ny = toolbar.height / 2f else { web.scrollBy(0, dy.toInt()); ny = cursorY }
        }
        if (dx > 0 && cursorX >= w - edge) { web.scrollBy(dx.toInt(), 0); nx = cursorX }
        else if (dx < 0 && cursorX <= edge) { web.scrollBy(dx.toInt(), 0); nx = cursorX }

        cursorX = nx.coerceIn(0f, w - 1)
        cursorY = ny.coerceIn(0f, h - 1)
        cursor.setPos(cursorX, cursorY)
    }

    private fun clickAtCursor() {
        val x = cursorX
        val y = cursorY
        val t = SystemClock.uptimeMillis()
        lastTap = t
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
        if (urlBar.hasFocus()) return
        web.evaluateJavascript(
            "(function(){var e=document.activeElement;return !!e&&(e.tagName==='INPUT'||e.tagName==='TEXTAREA'||e.isContentEditable===true)})()"
        ) { r ->
            if (r == "true") { web.requestFocus(); imm().showSoftInput(web, 0) }
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        when {
            customView != null -> hideCustom()
            urlBar.hasFocus() -> { hideKeyboard(); web.requestFocus() }
            web.canGoBack() -> web.goBack()
            else -> super.onBackPressed()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.dataString?.let { load(it) }
    }

    override fun onResume() { super.onResume(); web.onResume(); ui.post(ticker) }
    override fun onPause() { ui.removeCallbacks(ticker); web.onPause(); super.onPause() }
    override fun onDestroy() { web.destroy(); super.onDestroy() }
}
