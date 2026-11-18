package com.tvbrowser

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
    private val home = "https://duckduckgo.com/"
    private var density = 1f
    private var cursorX = 0f
    private var cursorY = 0f
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null

    @Volatile
    private var pageUrl = ""

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
                val scheme = request?.url?.scheme ?: return false
                return !(scheme == "http" || scheme == "https" || scheme == "about" || scheme == "data" || scheme == "blob")
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
        }
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
            s.contains(" ") || !s.contains(".") -> "https://duckduckgo.com/?q=" + URLEncoder.encode(s, "UTF-8")
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
