package com.tvbrowser

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ComponentCallbacks2
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
import android.os.PowerManager
import android.os.SystemClock
import android.text.TextUtils
import android.util.Log
import android.view.Choreographer
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.webkit.JsPromptResult
import android.webkit.JsResult
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
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.util.Collections
import java.util.concurrent.Executors

const val HOME_URL = "https://tvbrowser.home/"
private const val MAX_TABS = 6

/**
 * Runs at the start of every page and frame (when the WebView supports it).
 * - remembers real (trusted) taps, so scripted clicks can't pass as the user
 * - asks the app before any window.open() and silently drops popups the app refuses
 * - ignores scripted clicks on links that would open another site / a new tab (pop-under ads)
 */
private const val PAGE_JS = """
(function(){
 var h=location.hostname;
 if(h==='youtube.com'||h.endsWith('.youtube.com')||h.endsWith('youtube-nocookie.com')||h==='youtu.be')return;
 if(window.__tvb)return; window.__tvb=1;
 function off(){ try{ return TVB.siteOff(); }catch(x){ return false; } }
 function inFs(){ try{ return TVB.inFs(); }catch(x){ return false; } }
 function f(e){ if(!e.isTrusted)return; var n=e.target,a=n&&n.closest?n.closest('a[href]'):null; try{TVB.tap(a?a.href:'')}catch(x){} }
 document.addEventListener('pointerdown',f,true);
 document.addEventListener('click',f,true);
 var _open=window.open;
 window.open=function(u){
  if(off())return _open.apply(this,arguments);
  var ok=true;
  try{ var abs=''; if(u!=null&&String(u)!==''){ abs=String(u); try{abs=new URL(abs,location.href).href}catch(x){} } ok=TVB.allowPopup(abs); }catch(x){}
  if(!ok)return null;
  return _open.apply(this,arguments);
 };
 document.addEventListener('click',function(e){
  if(off())return;
  var a=e.target&&e.target.closest?e.target.closest('a[href]'):null;
  if(!a||!(a.target==='_blank'||a.origin!==location.origin))return;
  if(!e.isTrusted){
   try{TVB.noteBlock('scripted link click',a.href)}catch(x){}
   e.preventDefault();e.stopImmediatePropagation();
  }else if(inFs()){
   try{TVB.noteBlock('link click in full screen',a.href)}catch(x){}
   e.preventDefault();e.stopImmediatePropagation();
  }
 },true);
 document.addEventListener('submit',function(e){
  if(!off()&&inFs()&&e.target&&e.target.target==='_blank'){e.preventDefault();e.stopImmediatePropagation();}
 },true);
 function remember(){var el=document.fullscreenElement||document.webkitFullscreenElement;if(el)window.__tvbFsEl=el;}
 document.addEventListener('fullscreenchange',remember,true);
 document.addEventListener('webkitfullscreenchange',remember,true);
 window.__tvbReenter=function(){
  var el=window.__tvbFsEl;
  if(el&&el.isConnected&&!document.fullscreenElement&&!document.webkitFullscreenElement){
   try{var p=el.requestFullscreen?el.requestFullscreen():el.webkitRequestFullscreen();if(p&&p.catch)p.catch(function(){});}catch(x){}
  }
 };
 function guard(o,n){
  var f=o&&o[n]; if(typeof f!=='function')return;
  o[n]=function(){
   var ok=true;
   try{ ok=TVB.allowFsExit(String(new Error().stack||'').substring(0,240)); }catch(x){}
   if(!ok)return (window.Promise?Promise.resolve():undefined);
   return f.apply(this,arguments);
  };
 }
 try{ guard(Document.prototype,'exitFullscreen'); guard(Document.prototype,'webkitExitFullscreen'); guard(Document.prototype,'webkitCancelFullScreen'); }catch(x){}
 try{ guard(HTMLVideoElement.prototype,'webkitExitFullscreen'); guard(HTMLVideoElement.prototype,'webkitExitFullScreen'); }catch(x){}
})();
"""

class Tab(var web: WebView) {
    @Volatile var pageUrl = ""
    var chainOk = true   // true when the current navigation chain was started by the user
    var lastCosmetic = ""
    var docStart = false          // the page script is installed by the WebView at document start
    var popupPending = false      // a popup tab that still has to pass its first check
    var popupOpener = ""
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
    private var downStartY = Float.MAX_VALUE
    private var lastLeft = 0L
    private var lastRight = 0L
    private var edgeJump = 0          // -1: pointer was jumped to the left edge, 1: right edge
    private var savedX = 0f

    // full screen video + popups
    private var userExit = false
    private var lastPoke = 0L
    private val hideCursorRun = Runnable { if (customView != null) cursor.visibility = View.GONE }

    // remote control timing (TV remotes such as Samsung / HDMI-CEC deliver keys late or without repeats)
    private var relaxed = false
    private val dtMax: Long get() = if (relaxed) 700L else 350L
    private val longMs: Long get() = if (relaxed) 900L else 600L
    private val lastDownEvt = LongArray(4)
    private var okHeld = false
    private var lastOkDown = 0L
    private val okLongRun = Runnable {
        okLong = true
        if (customView == null) toggleScrollMode()
    }

    // popups the user decided about
    private val popupAllowDomains: MutableSet<String> = Collections.synchronizedSet(HashSet<String>())
    private val popupBlockDomains: MutableSet<String> = Collections.synchronizedSet(HashSet<String>())
    private var lastPromptAt = 0L
    private var promptShowing = false
    private var safeMode = false

    // sites where the shield was switched off by the user (base domains, remembered)
    private val shieldOffSites: MutableSet<String> = Collections.synchronizedSet(HashSet<String>())

    private fun shieldOffFor(url: String): Boolean {
        if (shieldOffSites.isEmpty() || url.isEmpty()) return false
        val d = baseDomain(url)
        return d.isNotEmpty() && shieldOffSites.contains(d)
    }
    @Volatile private var lastPopupAt = 0L
    @Volatile private var popupToken = 0L
    @Volatile private var activePageUrl = ""
    private val events = ArrayList<Pair<Long, String>>()

    // what the user last tapped (used to tell real clicks from scripted redirects)
    @Volatile private var lastTap = 0L
    @Volatile private var tapHref = ""
    private var lastToast = 0L

    private val reScript = Regex(".*\\.m?js$")
    private val reImage = Regex(".*\\.(png|jpe?g|gif|webp|svg|ico|avif|bmp)$")
    private val reFont = Regex(".*\\.(woff2?|ttf|otf)$")
    private val reMedia = Regex(".*\\.(mp4|webm|m3u8|mp3|ogg|m4a)$")

    private val ticker = object : Runnable {
        override fun run() {
            if (customView == null) updateShield()
            if (++tick % 10 == 0) sampleSystem()
            ui.postDelayed(this, 1000)
        }
    }

    private inner class TapBridge {
        @JavascriptInterface
        fun tap(href: String?) { tapHref = href ?: ""; lastTap = SystemClock.uptimeMillis() }

        @JavascriptInterface
        fun inFs(): Boolean = customView != null

        @JavascriptInterface
        fun siteOff(): Boolean = shieldOffFor(activePageUrl)

        @JavascriptInterface
        fun noteBlock(reason: String?, url: String?) {
            AdBlock.note(reason ?: "blocked", url ?: "")
            trace(reason ?: "blocked")
        }

        @JavascriptInterface
        fun allowFsExit(stack: String?): Boolean {
            if (customView == null || shieldOffFor(activePageUrl)) return true   // not full screen / shield off here
            val who = Regex("https?://([^/:\\s)]+)").find(stack ?: "")?.groupValues?.get(1) ?: "?"
            trace("page called exitFullscreen ($who)")
            return false                                  // Back exits full screen, scripts can't
        }

        @JavascriptInterface
        fun allowPopup(url: String?): Boolean {
            val ok = popupDecision(url ?: "")
            trace(if (ok) "window.open allowed" else "window.open blocked")
            if (!ok) AdBlock.note("window.open blocked", url ?: "")
            if (!ok) notifyBlocked("Blocked popup")
            return ok
        }
    }

    /** The background worker may already be shut down while the app closes: never let that throw. */
    private fun runBg(block: () -> Unit) {
        try { bg.execute { try { block() } catch (e: Exception) { } } } catch (e: java.util.concurrent.RejectedExecutionException) { }
    }

    private val trailFile: File by lazy { File(filesDir, "trail.txt") }
    private var prevTrail = ""
    private var wasClean = true
    private var tick = 0

    /** Appended to a file (and flushed) so that after a crash or a restart of the whole box we can see what happened. */
    private fun trail(line: String) {
        val time = System.currentTimeMillis()
        runBg {
            try {
                val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(time))
                FileOutputStream(trailFile, true).use { out ->
                    out.write((stamp + " " + line + "\n").toByteArray())
                    out.fd.sync()
                }
            } catch (e: Exception) { }
        }
    }

    private fun sampleSystem() {
        runBg {
            try {
                val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val mi = ActivityManager.MemoryInfo()
                am.getMemoryInfo(mi)
                val pss = am.getProcessMemoryInfo(intArrayOf(android.os.Process.myPid())).firstOrNull()?.totalPss ?: 0
                var thermal = -1
                if (Build.VERSION.SDK_INT >= 29) thermal = (getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus
                trail("mem free " + (mi.availMem / 1048576) + "MB low=" + mi.lowMemory + " app " + (pss / 1024) +
                    "MB thermal=" + thermal + " tabs=" + tabs.size + " fullscreen=" + (customView != null))
            } catch (e: Exception) { }
        }
    }

    private fun readPrevTrail() {
        try {
            if (trailFile.exists()) {
                prevTrail = trailFile.readLines().takeLast(14).joinToString("\n")
                trailFile.delete()
            }
        } catch (e: Exception) { }
    }

    private fun trace(what: String) {
        trail(what)
        synchronized(events) {
            events.add(SystemClock.uptimeMillis() to what)
            while (events.size > 20) events.removeAt(0)
        }
    }

    /** Is this URL (from window.open) a popup the user wants? Never during full screen, never in bursts. */
    private fun popupDecision(u: String): Boolean {
        if (AdBlock.mode == AdBlock.Mode.OFF || shieldOffFor(activePageUrl)) return true
        if (customView != null) return false
        val now = SystemClock.uptimeMillis()
        if (now - lastPopupAt < 1500 || tabs.size >= MAX_TABS) return false
        val ok = if (u.startsWith("http")) popupAllowed(u, activePageUrl) else recentTap()
        if (ok) { lastPopupAt = now; popupToken = now + 2000 }
        return ok
    }

    /** A popup is fine when it is a link you tapped, or it stays on the same site (e.g. a video player window). */
    private fun popupAllowed(url: String, opener: String): Boolean {
        if (AdBlock.mode == AdBlock.Mode.OFF || shieldOffFor(opener)) return true
        if (AdBlock.shouldBlockNavigation(url, opener)) { AdBlock.note("popup (ad list)", url); return false }
        val linkTap = recentTap() && tapHref.isNotEmpty()
        if (linkTap || !isCrossSite(opener, url)) return true
        val d = baseDomain(url)
        if (popupAllowDomains.contains(d)) return true
        AdBlock.note("popup (other site)", url)
        if (!popupBlockDomains.contains(d)) askPopup(url, d)
        return false
    }

    /** A page you tapped wants a window on another site (could be a video player, could be an ad): ask once. */
    private fun askPopup(url: String, d: String) {
        if (!recentTap() || customView != null || promptShowing) return
        val now = SystemClock.uptimeMillis()
        if (now - lastPromptAt < 4000) return
        lastPromptAt = now
        ui.post { showPopupPrompt(url, d) }
    }

    private fun openUrlInTab(url: String) {
        if (tabs.size >= MAX_TABS) { tab.chainOk = true; web.loadUrl(url) } else newTab(url)
    }

    private fun showPopupPrompt(url: String, d: String) {
        if (isFinishing || promptShowing) return
        promptShowing = true
        val dlg = android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Open a window from $d?")
            .setMessage("The page tried to open:\n" + url.take(110))
            .setPositiveButton("Open") { _, _ -> openUrlInTab(url) }
            .setNeutralButton("Always allow") { _, _ ->
                popupAllowDomains.add(d)
                prefs.edit().putStringSet("popup_allow", HashSet(popupAllowDomains)).apply()
                openUrlInTab(url)
            }
            .setNegativeButton("Block") { _, _ -> popupBlockDomains.add(d) }
            .create()
        dlg.setOnDismissListener { promptShowing = false }
        dlg.show()
        dlg.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)?.requestFocus()   // safest choice is preselected
    }

    /** Native fallback for windows that didn't go through the page script (e.g. from iframes without it). */
    private fun admitPopup(): Boolean {
        if (AdBlock.mode == AdBlock.Mode.OFF || shieldOffFor(activePageUrl)) return true
        val now = SystemClock.uptimeMillis()
        if (now <= popupToken) { popupToken = 0L; return true }
        if (customView != null || tabs.size >= MAX_TABS || now - lastPopupAt < 1500) return false
        lastPopupAt = now
        return true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Safe mode: if the app died during start-up three times in a row, wipe what it caches and start light.
        val fails = prefs.getInt("boot_fail", 0)
        prefs.edit().putInt("boot_fail", fails + 1).commit()
        if (fails >= 2) {
            safeMode = true
            try { File(filesDir, "filters").deleteRecursively() } catch (e: Exception) { }
            try { File(applicationInfo.dataDir, "app_webview").deleteRecursively() } catch (e: Exception) { }
            prefs.edit().putBoolean("engine_died", true).putInt("boot_fail", 0).commit()
        }
        ui.postDelayed({ prefs.edit().putInt("boot_fail", 0).apply() }, 15000)
        wasClean = prefs.getBoolean("clean_exit", true)
        prefs.edit().putBoolean("clean_exit", false).commit()
        readPrevTrail()
        trail("app start (safe mode=$safeMode)")
        relaxed = prefs.getBoolean("relaxed", false)
        popupAllowDomains.addAll(prefs.getStringSet("popup_allow", emptySet()) ?: emptySet())
        shieldOffSites.addAll(prefs.getStringSet("site_off", emptySet()) ?: emptySet())

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
        if (safeMode) ui.postDelayed({
            showInfo("Started in safe mode",
                "The app stopped several times while starting, so it cleared its saved ad-block data and browser data and started with only your block list. Tap the shield button to try the full lists again.")
        }, 2500)
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
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(w, PAGE_JS, setOf("*"))
                t.docStart = true
            }
        } catch (e: Throwable) { Log.w("TVB", "document-start script unavailable", e) }

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
                if (request.isForMainFrame && t.popupPending) {
                    t.popupPending = false
                    if (url.startsWith("http") && !popupAllowed(url, t.popupOpener)) {
                        trace("popup tab blocked")
                        notifyBlocked("Blocked popup")
                        ui.post { closePopupTab(t) }
                        return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                    }
                    ui.post { val i = tabs.indexOf(t); if (i >= 0) switchTo(i) }
                    return null
                }
                if (request.isForMainFrame) return null
                if (!url.startsWith("http")) return null
                if (shieldOffFor(t.pageUrl)) return null
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
                trail("page " + (try { Uri.parse(t.pageUrl).host ?: "" } catch (e: Exception) { "" }))
                if (t === tab) {
                    activePageUrl = t.pageUrl
                    if (!urlBar.hasFocus()) urlBar.setText(if (isHome(t.pageUrl)) "" else t.pageUrl)
                    progress.visibility = View.VISIBLE
                    updateFavButton()
                }
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request != null && request.isForMainFrame && isHome(request.url.toString())) ui.post { t.web.loadUrl(home) }
            }

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                trail("WebView process gone (crashed=" + (detail?.didCrash() ?: false) + ")")
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
                trail("full screen ON")
                root.addView(view, FrameLayout.LayoutParams(MATCH, MATCH))
                cursor.bringToFront()
                // Full screen video: the pointer starts hidden, appears when you move it and hides again after 3 s.
                held.fill(false)
                ui.removeCallbacks(hideCursorRun)
                cursor.visibility = View.GONE
            }

            override fun onHideCustomView() {
                if (customView == null) return              // we closed it ourselves
                val byUser = userExit
                userExit = false
                hideCustom()
                if (!byUser) {
                    reportFullscreenExit()
                    ui.postDelayed({ tryReenterFullscreen() }, 300)
                }
            }

            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
                trace("onCreateWindow")
                val msg = resultMsg ?: return false
                val transport = msg.obj as? WebView.WebViewTransport ?: return false
                if (!admitPopup()) { notifyBlocked("Blocked popup"); return false }
                // The popup becomes a real (hidden) tab, so window.opener, form posts etc. keep working.
                // It is shown only after its first request passes the ad check; otherwise it is closed.
                val nt = newTab(null, activate = false)
                nt.popupOpener = t.pageUrl
                nt.popupPending = AdBlock.mode != AdBlock.Mode.OFF
                transport.webView = nt.web
                msg.sendToTarget()
                if (nt.popupPending) {
                    // Players that write their page with script never make a request: show them anyway.
                    ui.postDelayed({
                        if (tabs.contains(nt) && nt.popupPending) {
                            nt.popupPending = false
                            val i = tabs.indexOf(nt)
                            if (i >= 0) switchTo(i)
                        }
                    }, 1200)
                } else {
                    ui.post { val i = tabs.indexOf(nt); if (i >= 0) switchTo(i) }
                }
                return true
            }

            override fun onCloseWindow(window: WebView?) {
                ui.post {
                    val i = tabs.indexOfFirst { it.web === window }
                    if (i >= 0 && tabs.size > 1) closeTab(i)
                }
            }

            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                trace("alert()")
                if (customView != null) { result?.cancel(); return true }
                return false
            }

            override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                trace("confirm()")
                if (customView != null) { result?.cancel(); return true }
                return false
            }

            override fun onJsPrompt(view: WebView?, url: String?, message: String?, defaultValue: String?, result: JsPromptResult?): Boolean {
                trace("prompt()")
                if (customView != null) { result?.cancel(); return true }
                return false
            }
        }
    }

    private fun closePopupTab(t: Tab) {
        val i = tabs.indexOf(t)
        if (i >= 0 && tabs.size > 1) closeTab(i)
    }

    private var lastReenter = 0L

    /** A tap just before the exit gives the page permission to go full screen again: put the player back. */
    private fun tryReenterFullscreen() {
        val now = SystemClock.uptimeMillis()
        if (customView != null || now - lastReenter < 10000 || now - lastTap > 4500) return
        lastReenter = now
        trail("trying to re-enter full screen")
        try { web.evaluateJavascript("window.__tvbReenter&&window.__tvbReenter()", null) } catch (e: Exception) { }
    }

    /** Tells the user (and me) what happened around the moment a video left full screen on its own. */
    private fun reportFullscreenExit() {
        val t0 = SystemClock.uptimeMillis()
        ui.postDelayed({
            val ev = synchronized(events) {
                events.filter { it.first >= t0 - 2500 && it.first <= t0 + 1200 }
                    .map { (if (it.first < t0) "before: " else "after: ") + it.second }
            }
            toast(
                if (ev.isEmpty()) "Left full screen by itself (nothing seen before or after)"
                else "Left full screen. " + ev.joinToString("; ")
            )
        }, 1300)
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
        for ((idx, t) in tabs.withIndex()) {
            if (idx == i) t.web.visibility = View.VISIBLE
            else {
                // A hidden tab must not keep decoding video / playing ad audio (TV boxes have very few decoders).
                if (t.web.visibility == View.VISIBLE) pauseMedia(t)
                t.web.visibility = View.GONE
            }
        }
        activePageUrl = tab.pageUrl
        web.requestFocus()
        if (!urlBar.hasFocus()) urlBar.setText(if (isHome(tab.pageUrl)) "" else tab.pageUrl)
        progress.visibility = View.GONE
        updateTabsLabel()
        updateFavButton()
    }

    private fun pauseMedia(t: Tab) {
        try {
            t.web.evaluateJavascript(
                "document.querySelectorAll('video,audio').forEach(function(m){try{m.pause()}catch(e){}})", null
            )
        } catch (e: Exception) { }
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

    private fun isCompatSite(url: String): Boolean {
        val h = try { Uri.parse(url).host?.lowercase() } catch (e: Exception) { null } ?: return false
        return h == "youtu.be" || h == "youtube.com" || h.endsWith(".youtube.com") || h.endsWith("youtube-nocookie.com")
    }

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
        if (customView != null) return          // nothing may pop up over a full screen video
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
        if (isHome(t.pageUrl) || shieldOffFor(t.pageUrl)) { t.chainOk = true; return false }   // favorites / search box / shield off
        if (customView != null && isCrossSite(t.pageUrl, target)) {      // a page jumping away while a video is full screen
            trace("blocked page jump in full screen"); AdBlock.note("page jump in full screen", target); return true
        }
        if (AdBlock.shouldBlockNavigation(target, t.pageUrl)) { trace("blocked ad redirect"); notifyBlocked("Blocked ad redirect"); return true }
        if (isCompatSite(t.pageUrl)) { t.chainOk = true; return false }   // YouTube: only the filter lists apply
        val cross = isCrossSite(t.pageUrl, target)
        if (req.isRedirect) {
            if (AdBlock.aggressive && cross && !t.chainOk) { notifyBlocked("Blocked redirect (Aggressive shield)"); return true }
            return false
        }
        val tapped = req.hasGesture() || recentTap()
        val linkOut = tapped && tapHref.isNotEmpty() && baseDomain(tapHref) == baseDomain(target)
        t.chainOk = cross && linkOut
        val block = cross && (if (AdBlock.aggressive) !linkOut else !tapped)
        if (block) { trace("blocked redirect"); notifyBlocked(if (tapped) "Blocked redirect (Aggressive shield)" else "Blocked automatic redirect") }
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
        if (t.docStart) return
        try { t.web.evaluateJavascript(PAGE_JS, null) } catch (e: Exception) { }
    }

    /** Looks the hide-rules up on a background thread (they can be slow) and injects them on the UI thread. */
    private fun injectCosmetic(t: Tab, url: String?) {
        if (url == null || !url.startsWith("http") || isHome(url) || !AdBlock.enabled || shieldOffFor(url)) return
        if (t.lastCosmetic == url) return
        t.lastCosmetic = url
        runBg {
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
        trail("shield mode " + AdBlock.mode.name)
        AdBlock.resetFailure(applicationContext)
        AdBlock.ensureLoaded(applicationContext, ::status)
        toast("Shield: " + AdBlock.mode.name.lowercase())
        updateShield()
        web.reload()
    }

    private fun updateShield() {
        val n = AdBlock.blockedCount.get()
        shield.text = when {
            AdBlock.mode != AdBlock.Mode.OFF && shieldOffFor(tab.pageUrl) -> "Site: OFF"
            AdBlock.mode == AdBlock.Mode.OFF -> "Shield: OFF"
            !AdBlock.ready -> "Shield: loading"
            AdBlock.aggressive && AdBlock.domainCount == 0 -> "Aggr: loading"
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
        rows.add(Row("+ New tab", "Opens your favorites", { hidePanel(); if (tabs.size >= MAX_TABS) toast("Too many tabs - close one first") else newTab(home) }))
        for ((i, t) in tabs.withIndex()) {
            val name = t.web.title?.takeIf { it.isNotBlank() } ?: t.pageUrl.ifEmpty { "New tab" }
            rows.add(Row((if (i == cur) "> " else "") + name, if (isHome(t.pageUrl)) "Favorites" else t.pageUrl, { hidePanel(); switchTo(i) }, { closeTab(i); showTabs() }))
        }
        val dom = if (isHome(tab.pageUrl)) "" else baseDomain(tab.pageUrl)
        if (dom.isNotEmpty()) {
            val isOff = shieldOffSites.contains(dom)
            rows.add(Row("Shield on $dom: " + (if (isOff) "OFF" else "ON"),
                "Turn it off if a video player, login or menu on this site doesn't work. Tap to switch.",
                {
                    if (isOff) shieldOffSites.remove(dom) else shieldOffSites.add(dom)
                    prefs.edit().putStringSet("site_off", HashSet(shieldOffSites)).apply()
                    trail("shield " + (if (isOff) "on" else "off") + " for " + dom)
                    hidePanel(); web.reload()
                }))
        }
        rows.add(Row("Remote key test", "See which keys and timing your remote sends", { hidePanel(); showKeyTest() }))
        rows.add(Row("Remote timing: " + (if (relaxed) "Relaxed" else "Standard"),
            "Relaxed suits TV remotes that deliver keys late (Samsung, HDMI-CEC). Tap to switch.",
            { relaxed = !relaxed; prefs.edit().putBoolean("relaxed", relaxed).putBoolean("relaxed_auto", true).apply(); showTabs() }))
        rows.add(Row("Blocked log", "What the shield blocked recently", { hidePanel(); showBlockLog() }))
        showPanel("Tabs (${tabs.size})", rows)
    }

    private fun showBlockLog() {
        val items = AdBlock.recentBlocks()
        val ver = if (Build.VERSION.SDK_INT >= 26) (WebView.getCurrentWebViewPackage()?.versionName ?: "?") else "?"
        val head = Row("WebView $ver",
            if (tab.docStart) "Page script runs on every frame" else "Page script runs on the main page only (old WebView)", { })
        val rows = ArrayList<Row>()
        rows.add(head)
        if (items.isEmpty()) rows.add(Row("Nothing blocked yet", "Blocks show up here as you browse", { }))
        else for (it in items) rows.add(Row(it, "", { }))
        showPanel("Blocked log (newest first)", rows)
    }

    private fun showKeyTest() {
        val lines = ArrayList<String>()
        var backAt = 0L
        val dlg = android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Remote key test")
            .setMessage("Press keys on your remote. Press Back twice quickly to close.")
            .create()
        dlg.setOnKeyListener { d, code, ev ->
            val dev = ev.device
            val line = KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_") +
                (if (ev.action == KeyEvent.ACTION_DOWN) " down r=" + ev.repeatCount else " up") +
                "  [" + (dev?.name ?: ("id " + ev.deviceId)) + "]" + (if ((ev.source and 0x02000000) != 0) " HDMI" else "")
            lines.add(0, line)
            while (lines.size > 12) lines.removeAt(lines.size - 1)
            dlg.setMessage("Press Back twice quickly to close.\n\n" + lines.joinToString("\n"))
            if (code == KeyEvent.KEYCODE_BACK && ev.action == KeyEvent.ACTION_DOWN) {
                val now = SystemClock.uptimeMillis()
                if (now - backAt < 1200) d.dismiss()
                backAt = now
            }
            true
        }
        dlg.show()
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
        trail("full screen OFF")
        customView?.let { root.removeView(it) }
        customView = null
        customCallback?.onCustomViewHidden()
        customCallback = null
        ui.removeCallbacks(hideCursorRun)
        cursor.visibility = if (scrollMode) View.INVISIBLE else View.VISIBLE
        try { web.requestFocus() } catch (e: Exception) { }
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

    /** After a crash, a system kill or even a restart of the whole box, tell the user what we know. */
    private fun reportLastProblem() {
        var title: String? = null
        val sb = StringBuilder()
        val saved = prefs.getString("last_crash", null)
        if (saved != null) {
            prefs.edit().remove("last_crash").apply()
            title = "The app crashed last time"
            sb.append(saved).append('\n')
        } else if (Build.VERSION.SDK_INT >= 30) {
            try {
                val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val last = am.getHistoricalProcessExitReasons(packageName, 0, 1).firstOrNull()
                if (last != null && last.timestamp > prefs.getLong("exit_seen", 0L)) {
                    prefs.edit().putLong("exit_seen", last.timestamp).apply()
                    val name = when (last.reason) {
                        ApplicationExitInfo.REASON_LOW_MEMORY -> "the system killed it (low memory)"
                        ApplicationExitInfo.REASON_CRASH -> "it crashed"
                        ApplicationExitInfo.REASON_CRASH_NATIVE -> "it crashed (native code)"
                        ApplicationExitInfo.REASON_ANR -> "it stopped responding (ANR)"
                        ApplicationExitInfo.REASON_SIGNALED -> "the system stopped it"
                        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "it used too many resources"
                        else -> null
                    }
                    if (name != null) {
                        title = "Last time $name"
                        sb.append(last.description ?: "").append("\nMemory used: ").append(last.rss / 1024).append(" MB\n")
                    }
                }
            } catch (e: Exception) { }
        }
        // The app did not close normally and there is no other explanation: probably the whole box restarted.
        if (!wasClean && prevTrail.isNotEmpty()) {
            if (title == null) title = "The app or the TV box stopped unexpectedly"
            sb.append("\nLast events before it stopped:\n").append(prevTrail)
        }
        if (title != null) showInfo(title, sb.toString().take(1600))
    }

    private fun status(msg: String) { runOnUiThread { toast(msg) } }
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    private fun imm() = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    private fun hideKeyboard() = imm().hideSoftInputFromWindow(urlBar.windowToken, 0)

    // ---------------------------------------------------------------- remote control: smooth pointer + scrolling

    private val dtMin: Long get() = if (relaxed) 150L else 90L

    /** TV remotes (Samsung, HDMI-CEC) deliver keys late and without repeats: switch to relaxed timing once. */
    private fun detectRemote(e: KeyEvent) {
        if (relaxed || prefs.getBoolean("relaxed_auto", false)) return
        val name = (e.device?.name ?: "").lowercase()
        val hdmi = (e.source and 0x02000000) != 0          // key forwarded over HDMI-CEC
        if (hdmi || name.contains("samsung") || name.contains("cec")) {
            relaxed = true
            prefs.edit().putBoolean("relaxed", true).putBoolean("relaxed_auto", true).apply()
            toast("TV remote detected: relaxed key timing is on (change it under Tabs)")
        }
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (currentFocus is EditText) { held.fill(false); return super.dispatchKeyEvent(e) }   // typing in the URL bar
        val down = e.action == KeyEvent.ACTION_DOWN
        detectRemote(e)

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
                // Some remotes resend a plain "key down" while the key is held: that is a repeat, not a new press.
                val repeatLike = e.repeatCount > 0 || (held[idx] && now - lastDownEvt[idx] < 220)
                lastDownEvt[idx] = now
                if (customView != null) pokeCursor()
                if (!repeatLike) {
                    if (!held[idx]) heldSince[idx] = System.nanoTime()
                    held[idx] = true
                    if (idx == 0) { if (now - lastUp in dtMin..dtMax && !scrollMode) jumpToToolbar(); lastUp = now }
                    if (idx == 1) {
                        // Double-tap DOWN while the first tap started in the toolbar: go back to where we were.
                        if (now - lastDown in dtMin..dtMax && !scrollMode && downStartY <= toolbar.height) {
                            returnFromToolbar(); downStartY = Float.MAX_VALUE
                        } else downStartY = cursorY
                        lastDown = now
                    }
                    if (idx == 2) { if (now - lastLeft in dtMin..dtMax && !scrollMode) jumpHorizontal(-1); lastLeft = now }
                    if (idx == 3) { if (now - lastRight in dtMin..dtMax && !scrollMode) jumpHorizontal(1); lastRight = now }
                    startLoop()
                }
            } else held[idx] = false
            return true
        }

        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (down) {
                    val now = SystemClock.uptimeMillis()
                    val repeatLike = e.repeatCount > 0 || (okHeld && now - lastOkDown < 450)
                    lastOkDown = now
                    if (!repeatLike) {
                        okHeld = true
                        okLong = false
                        ui.removeCallbacks(okLongRun)
                        ui.postDelayed(okLongRun, longMs)          // hold OK = scroll mode (works without key repeats)
                    } else if (e.repeatCount >= 3 && !okLong) {
                        ui.removeCallbacks(okLongRun); okLongRun.run()
                    }
                } else {
                    okHeld = false
                    ui.removeCallbacks(okLongRun)
                    if (!okLong) {
                        if (scrollMode) toggleScrollMode()
                        else if (customView != null && cursor.visibility != View.VISIBLE) pokeCursor()   // first OK only shows the pointer
                        else clickAtCursor()
                    }
                }
                return true
            }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SEARCH, KeyEvent.KEYCODE_INFO -> { if (down && e.repeatCount == 0) urlBar.requestFocus(); return true }
            // Coloured keys: shortcuts for gestures that some remotes can't send.
            KeyEvent.KEYCODE_PROG_RED -> { if (down && e.repeatCount == 0) toggleScrollMode(); return true }
            KeyEvent.KEYCODE_PROG_GREEN -> { if (down && e.repeatCount == 0) showFavs(); return true }
            KeyEvent.KEYCODE_PROG_YELLOW -> { if (down && e.repeatCount == 0) showTabs(); return true }
            KeyEvent.KEYCODE_PROG_BLUE -> { if (down && e.repeatCount == 0) { jumpToToolbar(); pokeCursor() }; return true }
            // Media keys: play/pause and seek the video.
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE ->
                { if (down && e.repeatCount == 0) toggleVideo(); return true }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { if (down) { if (customView != null) seekVideo(-10) else web.pageUp(false) }; return true }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { if (down) { if (customView != null) seekVideo(10) else web.pageDown(false) }; return true }
            KeyEvent.KEYCODE_CHANNEL_UP -> { if (down) web.pageUp(false); return true }
            KeyEvent.KEYCODE_CHANNEL_DOWN -> { if (down) web.pageDown(false); return true }
        }
        return super.dispatchKeyEvent(e)
    }

    private fun toggleVideo() {
        web.evaluateJavascript(
            "(function(){var v=document.querySelector('video');if(!v)return 0;if(v.paused)v.play();else v.pause();return 1})()"
        ) { r -> if (r != "1") sendKeyToPage(KeyEvent.KEYCODE_SPACE) }
    }

    private fun seekVideo(sec: Int) {
        web.evaluateJavascript(
            "(function(){var v=document.querySelector('video');if(v&&isFinite(v.duration)){v.currentTime=Math.max(0,v.currentTime+(${sec}));}})()", null
        )
    }

    /** In full screen the pointer is hidden; it appears when moved and hides again after 3 seconds. */
    private fun pokeCursor() {
        if (customView == null) return
        val now = SystemClock.uptimeMillis()
        if (cursor.visibility != View.VISIBLE) { cursor.visibility = View.VISIBLE; lastPoke = 0L }
        if (now - lastPoke > 250) {
            lastPoke = now
            ui.removeCallbacks(hideCursorRun)
            ui.postDelayed(hideCursorRun, 3000)
        }
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
        if (customView != null) pokeCursor()
        if (scrollMode) scrollContent(dx * 1.6f, dy * 1.6f) else moveCursor(dx, dy)
    }

    private fun moveCursor(dx: Float, dy: Float) {
        val w = root.width.toFloat()
        val h = root.height.toFloat()
        if (w <= 0f) return
        val margin = 8 * density
        val top = if (panel != null) 0f else toolbar.height.toFloat()
        var nx = cursorX + dx
        var ny = cursorY + dy
        // The pointer travels right up to every screen border; pushing further against a border scrolls.
        if (dy > 0f && cursorY >= h - 1f - margin) scrollContent(0f, dy * 1.5f)
        else if (dy < 0f && cursorY <= top + margin && contentScrollY() > 0) { scrollContent(0f, dy * 1.5f); ny = cursorY }
        if (dx > 0f && cursorX >= w - 1f - margin) scrollContent(dx * 1.5f, 0f)
        else if (dx < 0f && cursorX <= margin) scrollContent(dx * 1.5f, 0f)
        cursorX = nx.coerceIn(0f, w - 1f)
        cursorY = ny.coerceIn(0f, h - 1f)
        if (edgeJump != 0 && cursorX > 120 * density && cursorX < w - 120 * density) edgeJump = 0
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

    private fun sendKeyToPage(code: Int) {
        val t = SystemClock.uptimeMillis()
        web.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        web.dispatchKeyEvent(KeyEvent(t, t + 40, KeyEvent.ACTION_UP, code, 0))
    }

    /** Double-tap LEFT / RIGHT: jump the pointer to that screen edge; double-tap the other way to come back. */
    private fun jumpHorizontal(dir: Int) {
        val w = root.width.toFloat()
        if (w <= 0f) return
        if (edgeJump == -dir) {
            cursorX = savedX
            edgeJump = 0
        } else {
            if (edgeJump == 0) savedX = cursorX
            cursorX = if (dir < 0) 10 * density else w - 1f - 10 * density
            edgeJump = dir
        }
        cursor.setPos(cursorX, cursorY)
    }

    private fun jumpToToolbar() {
        if (panel != null || cursorY <= toolbar.height) return
        savedY = cursorY
        cursorY = toolbar.height / 2f
        cursor.setPos(cursorX, cursorY)
    }

    private fun returnFromToolbar() {
        if (panel != null) return
        cursorY = if (savedY > toolbar.height) savedY else root.height / 2f
        cursor.setPos(cursorX, cursorY)
    }

    private fun clickAtCursor() {
        if (customView != null) pokeCursor()
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

    private fun imeVisible(): Boolean =
        Build.VERSION.SDK_INT >= 30 && web.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true

    /**
     * If the tap landed in a text field on the page, make sure the on-screen keyboard is up.
     * Some sites (YouTube) move focus around right after the tap and the keyboard closes again,
     * so check a few times and bring it back while a field is still focused.
     */
    private fun maybeShowKeyboard(attempt: Int = 0) {
        if (urlBar.hasFocus() || panel != null) return
        val w = web
        w.evaluateJavascript(
            "(function(){var e=document.activeElement;return !!e&&(e.tagName==='INPUT'||e.tagName==='TEXTAREA'||e.isContentEditable===true)})()"
        ) { r ->
            if (r == "true") {
                if (!w.hasFocus()) w.requestFocus()
                if (!imeVisible()) imm().showSoftInput(w, 0)
                if (attempt < 2) ui.postDelayed({ maybeShowKeyboard(attempt + 1) }, 500)
            }
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
        if (customView != null) { userExit = true; hideCustom(); userExit = false; return }
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

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        trail("low memory signal level=$level")
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL && tabs.size > 1 && customView == null) {
            trace("low memory")
            val keep = tab
            for (t in ArrayList(tabs)) if (t !== keep) { val i = tabs.indexOf(t); if (i >= 0) closeTab(i) }
            toast("Low memory: closed background tabs")
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.dataString?.let { if (tabs.size >= MAX_TABS) { tab.chainOk = true; web.loadUrl(it) } else newTab(it) }
    }

    override fun onStart() {
        super.onStart()
        prefs.edit().putBoolean("clean_exit", false).apply()
    }

    override fun onStop() {
        prefs.edit().putInt("boot_fail", 0).putBoolean("clean_exit", true).apply()
        trail("app stopped normally")
        super.onStop()
    }

    override fun onResume() { super.onResume(); web.onResume(); ui.post(ticker) }
    override fun onPause() { held.fill(false); ui.removeCallbacks(ticker); web.onPause(); super.onPause() }
    override fun onDestroy() { bg.shutdown(); for (t in tabs) t.web.destroy(); super.onDestroy() }
}
