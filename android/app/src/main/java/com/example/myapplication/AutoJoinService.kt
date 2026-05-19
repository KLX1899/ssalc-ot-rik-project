package com.example.myapplication

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.http.SslError
import android.os.*
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.*
import android.widget.FrameLayout
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

data class ClassSpec(
    val name: String,
    val startTime: String,
    val endTime: String,
    val days: List<String>,
    val platform: String
)

class AutoJoinService : Service() {

    private lateinit var webView: WebView
    private lateinit var windowManager: WindowManager
    private lateinit var backgroundLayoutParams: WindowManager.LayoutParams
    private var isWebViewInBackground = false

    private enum class FlowState {
        IDLE,
        NAVIGATING_TO_HOME,
        WAITING_SSO_BUTTON,
        ON_CAS_PAGE,
        LMS_DASHBOARD,
        LMS_COURSE_PAGE,       // on panel/myLesson/...
        LMS_BBB_JOINING,
        NIMA_DASHBOARD,
        DISCOVERY_NAVIGATING,
        DISCOVERY_DASHBOARD,
        DISCOVERY_SCRAPING_COURSE,
    }

    private var flowState = FlowState.IDLE
    private var activeSpec: ClassSpec? = null
    private var refreshCount = 0
    private val maxRefreshes = 30

    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingReloadRunnable: Runnable? = null
    private var casInjectedForUrl: String? = null
    private var wakeLock: PowerManager.WakeLock? = null

    // ── Schedule & Discovery ─────────────────────────────────────────────────
    private val schedule = mutableListOf<ClassSpec>()
    private val alarmTimers = mutableListOf<Timer>()

    var isDiscovering = false
        private set
    private val discoveredCourseLinks = mutableListOf<Pair<String, String>>()
    private val discoveredClasses = mutableListOf<ClassDiscoveryManager.DiscoveredClass>()
    private var discoveryIndex = 0

    var discoveryProgressListener: ((current: Int, total: Int, name: String) -> Unit)? = null
    var discoveryCompleteListener: ((count: Int, success: Boolean) -> Unit)? = null

    // ── Logging ──────────────────────────────────────────────────────────────
    private val logHistory = mutableListOf<String>()
    var logUpdateListener: ((String) -> Unit)? = null

    fun log(msg: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val line = "[$time] $msg"
        logHistory.add(line)
        if (logHistory.size > 200) logHistory.removeAt(0)
        mainHandler.post { logUpdateListener?.invoke(logHistory.joinToString("\n")) }
    }

    fun getFullLogs() = if (logHistory.isEmpty()) "System idle..." else logHistory.joinToString("\n")

    inner class LocalBinder : Binder() {
        fun getService(): AutoJoinService = this@AutoJoinService
    }
    private val binder = LocalBinder()

    // =========================================================================
    //  Lifecycle
    // =========================================================================

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        startForegroundNotification()
        setupWebView()
        attachToBackground()
        loadSchedule()
        registerAlarms()
        checkCatchUp()
        log("✅ Service started.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when {
            intent?.getBooleanExtra("DISCOVER_CLASSES", false) == true -> {
                if (!isDiscovering) startDiscoveryFlow()
                else log("⚠️ Discovery already running.")
            }
            intent?.getBooleanExtra("CANCEL_DISCOVERY", false) == true -> cancelDiscovery()
            intent?.getBooleanExtra("RELOAD_SCHEDULE", false) == true -> { loadSchedule(); registerAlarms() }
            intent?.getStringExtra("CLASS_SPEC_JSON") != null ->
                startJoinFlow(parseSpecFromJson(intent.getStringExtra("CLASS_SPEC_JSON")!!))
            intent?.getStringExtra("COURSE_URL") == "TEST" -> testLogin()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder
    override fun onUnbind(intent: Intent?): Boolean {
        logUpdateListener = null
        discoveryProgressListener = null
        discoveryCompleteListener = null
        attachToBackground()
        return true
    }
    override fun onDestroy() {
        super.onDestroy()
        releaseWakeLock()
        alarmTimers.forEach { it.cancel() }
        cancelPendingReload()
        if (isWebViewInBackground) try { windowManager.removeView(webView) } catch (_: Exception) {}
        webView.destroy()
    }

    // =========================================================================
    //  Schedule
    // =========================================================================

    private fun loadSchedule() {
        schedule.clear()
        try {
            val arr = JSONArray(ClassDiscoveryManager.loadScheduleJson(this))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val days = (0 until o.getJSONArray("days").length()).map { o.getJSONArray("days").getString(it) }
                schedule.add(ClassSpec(o.getString("name"), o.getString("start"), o.getString("end"), days, o.optString("platform", "LMS").uppercase()))
            }
            log("📅 Loaded ${schedule.size} class(es).")
        } catch (e: Exception) { log("⚠️ Schedule: ${e.message}") }
    }

    private fun registerAlarms() {
        alarmTimers.forEach { it.cancel() }
        alarmTimers.clear()
        val dayMap = mapOf(Calendar.SUNDAY to "sun", Calendar.MONDAY to "mon", Calendar.TUESDAY to "tue", Calendar.WEDNESDAY to "wed", Calendar.THURSDAY to "thu", Calendar.FRIDAY to "fri", Calendar.SATURDAY to "sat")
        schedule.forEach { spec ->
            val timer = Timer(true).also { alarmTimers.add(it) }
            timer.scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
                    val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Tehran"))
                    val today = dayMap[cal.get(Calendar.DAY_OF_WEEK)] ?: return
                    if (today in spec.days
                        && cal.get(Calendar.HOUR_OF_DAY) == spec.startTime.split(":")[0].toInt()
                        && cal.get(Calendar.MINUTE)      == spec.startTime.split(":")[1].toInt()
                    ) mainHandler.post { startJoinFlow(spec) }
                }
            }, (60 - Calendar.getInstance().get(Calendar.SECOND)) * 1000L, 60_000L)
        }
        log("⏱️ Timers set for ${schedule.size} class(es).")
    }

    private fun checkCatchUp() {
        val dayMap = mapOf(Calendar.SUNDAY to "sun", Calendar.MONDAY to "mon", Calendar.TUESDAY to "tue", Calendar.WEDNESDAY to "wed", Calendar.THURSDAY to "thu", Calendar.FRIDAY to "fri", Calendar.SATURDAY to "sat")
        val cal   = Calendar.getInstance(TimeZone.getTimeZone("Asia/Tehran"))
        val today = dayMap[cal.get(Calendar.DAY_OF_WEEK)] ?: return
        val now   = "%02d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
        schedule.firstOrNull { today in it.days && now >= it.startTime && now < it.endTime }?.let {
            log("⚠️ [CATCH-UP] '${it.name}' — joining now!")
            mainHandler.postDelayed({ startJoinFlow(it) }, 2_000L)
        }
    }

    // =========================================================================
    //  Join flow
    // =========================================================================

    private fun startJoinFlow(spec: ClassSpec) {
        val prefs = getSharedPreferences("LMS_PREFS", Context.MODE_PRIVATE)
        if (prefs.getString("USERNAME", "").isNullOrEmpty()) { log("⚠️ No credentials!"); return }
        activeSpec = spec; refreshCount = 0; casInjectedForUrl = null; isDiscovering = false
        cancelPendingReload()
        log("🚀 Joining '${spec.name}' [${spec.platform}]")
        flowState = FlowState.NAVIGATING_TO_HOME
        webView.loadUrl(if (spec.platform == "NIMA") "https://lms.aut.ac.ir/" else "https://lmshome.aut.ac.ir/")
    }

    private fun testLogin() {
        activeSpec = null; casInjectedForUrl = null; isDiscovering = false
        flowState = FlowState.NAVIGATING_TO_HOME
        webView.loadUrl("https://lmshome.aut.ac.ir/")
    }

    // =========================================================================
    //  Discovery flow
    // =========================================================================

    private fun startDiscoveryFlow() {
        val prefs = getSharedPreferences("LMS_PREFS", Context.MODE_PRIVATE)
        if (prefs.getString("USERNAME", "").isNullOrEmpty()) { log("⚠️ No credentials!"); return }
        isDiscovering = true; activeSpec = null; casInjectedForUrl = null
        discoveredCourseLinks.clear(); discoveredClasses.clear(); discoveryIndex = 0
        cancelPendingReload(); acquireWakeLock()
        updateNotification(getString(R.string.notif_discovering))
        log("🔍 Starting discovery...")
        flowState = FlowState.DISCOVERY_NAVIGATING
        webView.loadUrl("https://lmshome.aut.ac.ir/")
    }

    fun cancelDiscovery() {
        if (!isDiscovering) return
        log("🛑 Discovery cancelled.")
        isDiscovering = false; flowState = FlowState.IDLE; releaseWakeLock()
        updateNotification("Watching your schedule...")
        mainHandler.post { discoveryCompleteListener?.invoke(0, false) }
    }

    private fun scrapeCourseLinksfromDashboard() {
        log("📋 Scraping course links from dashboard...")
        val js = """
            (function() {
                var n = 0, MAX = 20;
                var iv = setInterval(function() {
                    n++;
                    var links = document.querySelectorAll('a.course-link');
                    if (links.length > 0) {
                        clearInterval(iv);
                        var res = [];
                        links.forEach(function(a) {
                            var name = a.textContent.replace(/\s+/g,' ').trim()
                                         .replace(/\s*-\s*گروه\s*\(.*\)/,'').trim();
                            res.push({name: name, url: a.href});
                        });
                        window._discoveredLinks = JSON.stringify(res);
                        console.log('[AUT] Links scraped: ' + res.length);
                    }
                    if (n >= MAX) { clearInterval(iv); window._discoveredLinks = '[]'; }
                }, 500);
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)

        mainHandler.postDelayed({
            if (!isDiscovering) return@postDelayed
            webView.evaluateJavascript("window._discoveredLinks || '[]'") { raw ->
                try {
                    val arr = JSONArray(raw?.trim('"')?.replace("\\\"","\"")?.replace("\\\\","\\") ?: "[]")
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        discoveredCourseLinks.add(Pair(o.getString("name"), o.getString("url")))
                    }
                    log("✅ Found ${discoveredCourseLinks.size} course link(s).")
                    if (discoveredCourseLinks.isEmpty()) finishDiscovery(false)
                    else visitNextCourseForDiscovery()
                } catch (e: Exception) { log("❌ Link parse error: ${e.message}"); finishDiscovery(false) }
            }
        }, 12_000L)
    }

    private fun visitNextCourseForDiscovery() {
        if (!isDiscovering) return
        if (discoveryIndex >= discoveredCourseLinks.size) { finishDiscovery(true); return }
        val (name, url) = discoveredCourseLinks[discoveryIndex]
        log("📖 [${discoveryIndex+1}/${discoveredCourseLinks.size}] $name")
        updateNotification("Scanning ${discoveryIndex+1} of ${discoveredCourseLinks.size}…")
        mainHandler.post { discoveryProgressListener?.invoke(discoveryIndex+1, discoveredCourseLinks.size, name) }
        flowState = FlowState.DISCOVERY_SCRAPING_COURSE
        webView.loadUrl(url)
    }

    private fun scrapeScheduleFromCoursePage() {
        if (!isDiscovering || discoveryIndex >= discoveredCourseLinks.size) return
        val (name, url) = discoveredCourseLinks[discoveryIndex]
        val js = """
            (function() {
                var n = 0, MAX = 15;
                var iv = setInterval(function() {
                    n++;
                    var lis = document.querySelectorAll('li'), txt = '';
                    for (var i = 0; i < lis.length; i++) {
                        if (lis[i].textContent.includes('زمان برگزاری')) {
                            var b = lis[i].querySelector('b');
                            if (b) { txt = b.textContent.replace(/\s+/g,' ').trim(); break; }
                        }
                    }
                    if (txt) { clearInterval(iv); window._courseSchedule = txt; }
                    if (n >= MAX) { clearInterval(iv); if (!window._courseSchedule) window._courseSchedule = ''; }
                }, 500);
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)

        mainHandler.postDelayed({
            if (!isDiscovering) return@postDelayed
            webView.evaluateJavascript("window._courseSchedule || ''") { raw ->
                val txt = raw?.trim('"')?.replace("\\\"","\"")?.replace("\\\\","\\")?.trim() ?: ""
                webView.evaluateJavascript("window._courseSchedule = '';", null) // reset flag
                if (txt.isNotEmpty()) {
                    val sessions = ClassDiscoveryManager.parseScheduleText(txt)
                    log("   📅 Raw: $txt → ${sessions.size} session(s)")
                    // Group sessions with same time window so days are merged
                    val grouped = mutableMapOf<Pair<String,String>, MutableList<String>>()
                    sessions.forEach { (day, start, end) -> grouped.getOrPut(Pair(start,end)) { mutableListOf() }.add(day) }
                    grouped.forEach { (timeKey, days) ->
                        discoveredClasses.add(ClassDiscoveryManager.DiscoveredClass(name=name, url=url, days=days, start=timeKey.first, end=timeKey.second))
                    }
                } else { log("   ⚠️ No schedule text found for '$name'") }
                discoveryIndex++
                mainHandler.postDelayed({ visitNextCourseForDiscovery() }, 1_500L)
            }
        }, 10_000L)
    }

    private fun finishDiscovery(success: Boolean) {
        isDiscovering = false; flowState = FlowState.IDLE; releaseWakeLock()
        if (success && discoveredClasses.isNotEmpty()) {
            val existingMap = ClassDiscoveryManager.loadDiscoveredClasses(this).associateBy { "${it.name}|${it.start}|${it.end}" }
            discoveredClasses.forEach { c ->
                existingMap["${c.name}|${c.start}|${c.end}"]?.let { c.platform = it.platform; c.enabled = it.enabled }
            }
            ClassDiscoveryManager.saveDiscoveredClasses(this, discoveredClasses)
            ClassDiscoveryManager.generateScheduleJson(this, discoveredClasses)
            log("✅ Discovery done: ${discoveredClasses.size} session(s) saved.")
            loadSchedule(); registerAlarms()
            updateNotification(getString(R.string.notif_discovery_done, discoveredClasses.size))
        } else {
            log("❌ Discovery finished — no classes found.")
            updateNotification("Watching your schedule...")
        }
        mainHandler.post { discoveryCompleteListener?.invoke(discoveredClasses.size, success) }
        mainHandler.postDelayed({ updateNotification("Watching your schedule...") }, 5_000L)
    }

    // =========================================================================
    //  WebView setup
    // =========================================================================

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView = WebView(this).apply {
            settings.apply {
                javaScriptEnabled = true; domStorageEnabled = true; databaseEnabled = true
                mediaPlaybackRequiresUserGesture = false
                setSupportZoom(true); builtInZoomControls = true; displayZoomControls = false
                useWideViewPort = true; loadWithOverviewMode = true
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                userAgentString = userAgentString.replace("; wv","").replace("Mobile","Desktop")
            }
            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) cm.setAcceptThirdPartyCookies(this, true)
            webViewClient = LmsWebViewClient()
            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(req: PermissionRequest) { req.grant(req.resources) }
                override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                    if (msg.message().startsWith("[AUT]")) log("   JS: ${msg.message().removePrefix("[AUT] ")}")
                    return true
                }
            }
        }
        backgroundLayoutParams = WindowManager.LayoutParams(
            1, 1,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSPARENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
    }

    // =========================================================================
    //  WebViewClient
    // =========================================================================

    private inner class LmsWebViewClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
            log("   ↳ ${req.url}")
            return false
        }
        override fun onPageFinished(view: WebView, url: String) {
            log("📄 Loaded: $url  [state=$flowState]")
            handlePageLoaded(url)
        }
        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            log("⚠️ SSL: ${error.primaryError} — proceeding")
            handler.proceed()
        }
        override fun onReceivedError(view: WebView, req: WebResourceRequest, err: WebResourceError) {
            if (req.isForMainFrame) log("❌ Page error: ${err.description} on ${req.url}")
        }
    }

    // =========================================================================
    //  URL helpers  ← THE KEY FIX IS HERE
    // =========================================================================

    private fun isCasUrl(url: String) =
        url.contains("cas.aut.ac.ir") || url.contains("/cas/login") ||
                url.contains("account.aut.ac.ir") || url.contains("sso.aut.ac.ir")

    /** LMS dashboard after login */
    private fun isLmsDashboard(url: String) =
        url.contains("lmshome.aut.ac.ir") && url.contains("panel/home")

    /**
     * LMS course/lesson page.
     * ACTUAL URL pattern from scraping: lmshome.aut.ac.ir/panel/myLesson/XXXX/XX/XXXX
     * NOT course/view.php — that was the bug causing the stall.
     */
    private fun isLmsCoursePage(url: String) =
        url.contains("lmshome.aut.ac.ir") &&
                (url.contains("panel/myLesson") || url.contains("course/view.php"))

    /** NIMA dashboard */
    private fun isNimaDashboard(url: String) =
        url.contains("lms.aut.ac.ir") && url.contains("users-panel")

    /** BBB room from either platform */
    private fun isBbbPage(url: String) =
        url.contains("html5client") || url.contains("bigbluebutton")

    /** Login index page (Moodle login) */
    private fun isLoginPage(url: String) = url.contains("login/index.php")

    // =========================================================================
    //  Page load handler
    // =========================================================================

    private fun handlePageLoaded(url: String) {
        when {
            // ── Login page ────────────────────────────────────────────────────
            isLoginPage(url) -> {
                flowState = FlowState.WAITING_SSO_BUTTON
                log("🔐 Login page — clicking SSO...")
                clickSsoButton()
            }

            // ── CAS credential page ───────────────────────────────────────────
            isCasUrl(url) -> {
                if (casInjectedForUrl == url) {
                    log("⚠️ CAS reloaded (same URL) — wrong credentials?"); return
                }
                flowState = FlowState.ON_CAS_PAGE
                casInjectedForUrl = url
                log("🔑 CAS page — injecting credentials...")
                injectCasCredentials()

                // Safety net: if still on CAS after 25s, log page content
                mainHandler.postDelayed({
                    if (isCasUrl(webView.url ?: "")) {
                        log("❌ Still on CAS after 25s! Check credentials.")
                        webView.evaluateJavascript("document.body.innerText.substring(0,300)") { log("   Page: ${it?.take(200)}") }
                    }
                }, 25_000L)
            }

            // ── LMS Dashboard ─────────────────────────────────────────────────
            isLmsDashboard(url) -> {
                if (isDiscovering) {
                    flowState = FlowState.DISCOVERY_DASHBOARD
                    log("✅ Dashboard (discovery mode) — scraping links...")
                    scrapeCourseLinksfromDashboard()
                } else {
                    flowState = FlowState.LMS_DASHBOARD
                    log("✅ LMS Dashboard reached.")
                    when {
                        activeSpec?.platform == "LMS" -> navigateToLmsCourse()
                        activeSpec == null -> log("ℹ️ Test login OK.")
                    }
                }
            }

            // ── NIMA Dashboard ────────────────────────────────────────────────
            isNimaDashboard(url) -> {
                if (isDiscovering) {
                    // NIMA discovery: scrape course links from NIMA side panel
                    flowState = FlowState.DISCOVERY_DASHBOARD
                    scrapeCourseLinksfromDashboard()
                } else {
                    flowState = FlowState.NIMA_DASHBOARD
                    log("✅ NIMA Dashboard reached.")
                    if (activeSpec?.platform == "NIMA") scanNimaOngoingMeetings()
                }
            }

            // ── LMS Course/Lesson page ────────────────────────────────────────
            isLmsCoursePage(url) -> {
                if (isDiscovering) {
                    // During discovery: scrape the schedule from each lesson page
                    flowState = FlowState.DISCOVERY_SCRAPING_COURSE
                    scrapeScheduleFromCoursePage()
                } else {
                    // During join: scan for the BBB join button
                    flowState = FlowState.LMS_COURSE_PAGE
                    log("📖 Course page loaded — scanning for join button...")
                    scanLmsJoinButton()
                }
            }

            // ── BBB Room ──────────────────────────────────────────────────────
            isBbbPage(url) -> {
                flowState = FlowState.LMS_BBB_JOINING
                cancelPendingReload()
                log("🎧 BBB room! Waiting for audio modal...")
                joinBbbListenOnly()
            }
        }
    }

    // =========================================================================
    //  Step helpers
    // =========================================================================

    // ── SSO button ───────────────────────────────────────────────────────────
    private fun clickSsoButton() {
        val js = """
            (function() {
                console.log('[AUT] Looking for SSO button...');
                var MAX = 30, n = 0;
                var iv = setInterval(function() {
                    n++;
                    var found = null;
                    var elems = document.querySelectorAll('a, button, input[type="submit"]');
                    for (var i = 0; i < elems.length; i++) {
                        var txt = (elems[i].textContent || elems[i].value || '').trim();
                        if (txt.indexOf('\u06CC\u06A9\u067E\u0627\u0631\u0686\u0647') !== -1 ||
                            txt.indexOf('\u0633\u0627\u0645\u0627\u0646\u0647') !== -1 ||
                            txt.indexOf('CAS') !== -1) {
                            found = elems[i]; break;
                        }
                    }
                    if (!found) found = document.querySelector('.login-identityprovider-btn');
                    if (!found) found = document.querySelector('a[href*="cas"]');
                    if (found) {
                        clearInterval(iv);
                        console.log('[AUT] SSO found: ' + found.textContent.trim().substring(0,40));
                        found.click();
                        return;
                    }
                    if (n >= MAX) {
                        clearInterval(iv);
                        var links = []; document.querySelectorAll('a').forEach(function(a) {
                            var t = a.textContent.trim();
                            if (t.length > 0 && t.length < 60) links.push(t);
                        });
                        console.log('[AUT] SSO NOT found. Links: ' + links.slice(0,10).join(' | '));
                    }
                }, 500);
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    // ── CAS credentials ───────────────────────────────────────────────────────
    private fun injectCasCredentials() {
        val prefs = getSharedPreferences("LMS_PREFS", Context.MODE_PRIVATE)
        val user = (prefs.getString("USERNAME", "") ?: "").replace("'","\\'").replace("\\","\\\\")
        val pass = (prefs.getString("PASSWORD", "") ?: "").replace("'","\\'").replace("\\","\\\\")
        if (user.isEmpty() || pass.isEmpty()) { log("⚠️ No credentials saved."); return }
        log("   Injecting for user: $user")
        val js = """
            (function() {
                var MAX = 40, n = 0;
                var iv = setInterval(function() {
                    n++;
                    var uf = document.getElementById('username') || document.querySelector('input[name="username"]') || document.querySelector('input[type="text"]');
                    var pf = document.getElementById('password') || document.querySelector('input[name="password"]') || document.querySelector('input[type="password"]');
                    if (!uf || !pf) { if (n >= MAX) { clearInterval(iv); console.log('[AUT] CAS fields NOT found'); } return; }
                    clearInterval(iv);
                    function fill(el, val) {
                        el.focus(); el.click();
                        try { Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(el,val); } catch(e) { el.value=val; }
                        ['focus','input','change','blur'].forEach(function(t){ el.dispatchEvent(new Event(t,{bubbles:true})); });
                    }
                    fill(uf, '$user'); fill(pf, '$pass');
                    console.log('[AUT] Fields filled. Submitting...');
                    setTimeout(function() {
                        pf.focus();
                        ['keydown','keypress','keyup'].forEach(function(t){
                            pf.dispatchEvent(new KeyboardEvent(t,{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true,cancelable:true}));
                        });
                        setTimeout(function() {
                            var sb = document.querySelector('input[type="submit"],button[type="submit"],button[name="submit"],.btn-submit,.btn-primary');
                            if (sb) { console.log('[AUT] Submit btn clicked'); sb.click(); }
                            setTimeout(function() {
                                var f = pf.closest('form'); if (f) { console.log('[AUT] Form submitted'); f.submit(); }
                            }, 300);
                        }, 300);
                    }, 300);
                }, 500);
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    // ── LMS: click course link from dashboard ─────────────────────────────────
    private fun navigateToLmsCourse() {
        val spec = activeSpec ?: return
        val safe = spec.name.replace("'", "\\'")
        log("🔍 Looking for course link: '${spec.name}'...")
        val js = """
            (function() {
                var MAX = 30, n = 0;
                var iv = setInterval(function() {
                    n++;
                    var links = document.querySelectorAll('a.course-link');
                    console.log('[AUT] Course links visible: ' + links.length);
                    var target = null;
                    for (var i = 0; i < links.length; i++) {
                        if (links[i].textContent.includes('$safe')) { target = links[i]; break; }
                    }
                    if (target) {
                        clearInterval(iv);
                        console.log('[AUT] Course link found — clicking: ' + target.href);
                        target.click();
                    }
                    if (n >= MAX) {
                        clearInterval(iv);
                        console.log('[AUT] Course link NOT found for: $safe');
                    }
                }, 500);
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    // ── LMS: find BBB join button on course page ──────────────────────────────
    private fun scanLmsJoinButton() {
        val spec = activeSpec ?: return
        val startTime = spec.startTime
        log("🔍 Scanning for join button (start: $startTime)...")

        val js = """
            (function() {
                var startTime = '$startTime';
                var MAX = 25, n = 0;
                window._autNeedReload = false;
                var iv = setInterval(function() {
                    n++;
                    // Find any table row that contains the start time
                    var rows = document.querySelectorAll('tr');
                    var targetRow = null;
                    for (var i = 0; i < rows.length; i++) {
                        if (rows[i].textContent.includes(startTime)) { targetRow = rows[i]; break; }
                    }
                    if (!targetRow) {
                        console.log('[AUT] No row with time ' + startTime + ' yet (attempt ' + n + ')');
                        if (n >= MAX) { clearInterval(iv); window._autNeedReload = true; console.log('[AUT] JOIN_TIMEOUT'); }
                        return;
                    }
                    // "Not started yet" indicator
                    if (targetRow.textContent.includes('\u0647\u0646\u0648\u0632 \u0628\u0631\u06AF\u0632\u0627\u0631 \u0646\u0634\u062F\u0647')) {
                        clearInterval(iv);
                        console.log('[AUT] CLASS_NOT_STARTED');
                        window._autNeedReload = true;
                        return;
                    }
                    // Find the join button inside the row
                    var btns = targetRow.querySelectorAll('button');
                    var joinBtn = null;
                    for (var j = 0; j < btns.length; j++) {
                        if (!btns[j].disabled && btns[j].textContent.includes('\u0648\u0631\u0648\u062F')) {
                            joinBtn = btns[j]; break;
                        }
                    }
                    if (joinBtn) {
                        clearInterval(iv);
                        console.log('[AUT] JOIN_BTN_CLICKED');
                        joinBtn.click();
                    } else if (n >= MAX) {
                        clearInterval(iv);
                        console.log('[AUT] JOIN_BTN_NOT_FOUND');
                        window._autNeedReload = true;
                    }
                }, 1000);
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)

        // After the JS finishes its 25 attempts (25s), read the flag
        mainHandler.postDelayed({
            webView.evaluateJavascript("String(window._autNeedReload)") { result ->
                if (result?.contains("true") == true) {
                    log("🕐 Join button not ready — scheduling reload in 60s...")
                    webView.evaluateJavascript("window._autNeedReload = false;", null)
                    scheduleReload()
                }
            }
        }, 27_000L)
    }

    // ── NIMA: find the join button in the ongoing meetings table ──────────────
    private fun scanNimaOngoingMeetings() {
        val spec = activeSpec ?: return
        val timeRange = "${spec.startTime} - ${spec.endTime}"
        val nameFragment = spec.name.take(6).replace("'", "\\'")
        log("🔍 NIMA: looking for '$timeRange'...")

        val js = """
            (function() {
                var timeRange = '$timeRange', nameFragment = '$nameFragment';
                var MAX = 30, n = 0;
                window._autNeedReload = false;
                var iv = setInterval(function() {
                    n++;
                    var comp = document.querySelector('app-users-panel-ongoing-meetings');
                    if (!comp) { if (n >= MAX) { clearInterval(iv); window._autNeedReload = true; } return; }
                    var rows = comp.querySelectorAll('tbody.p-datatable-tbody tr');
                    console.log('[AUT] NIMA rows: ' + rows.length);
                    for (var i = 0; i < rows.length; i++) {
                        var text = rows[i].innerText.replace(/\s+/g,' ').trim();
                        if (text.indexOf(timeRange) === -1) continue;
                        if (nameFragment && text.indexOf(nameFragment) === -1) continue;
                        var btn = rows[i].querySelector('button[name="join"]');
                        if (btn && !btn.disabled) {
                            clearInterval(iv);
                            console.log('[AUT] NIMA join clicked');
                            btn.click(); return;
                        }
                    }
                    if (n >= MAX) { clearInterval(iv); window._autNeedReload = true; }
                }, 1000);
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)

        mainHandler.postDelayed({
            webView.evaluateJavascript("String(window._autNeedReload)") { result ->
                if (result?.contains("true") == true) {
                    webView.evaluateJavascript("window._autNeedReload = false;", null)
                    scheduleReload()
                }
            }
        }, 35_000L)
    }

    // ── BBB: join listen-only audio ───────────────────────────────────────────
    private fun joinBbbListenOnly() {
        val js = """
            (function() {
                var MAX = 60, n = 0;
                var iv = setInterval(function() {
                    n++;
                    var overlay = document.querySelector('.ReactModal__Overlay');
                    if (!overlay) { if (n >= MAX) { clearInterval(iv); console.log('[AUT] BBB modal timeout'); } return; }
                    console.log('[AUT] BBB modal found at attempt ' + n);
                    var btn = null;
                    var iconEl = document.querySelector('button .icon-bbb-listen');
                    if (iconEl) btn = iconEl.closest('button');
                    if (!btn) btn = document.querySelector('button[aria-label="Listen only"]');
                    if (!btn) btn = document.querySelector('button[aria-label="\u0641\u0642\u0637 \u0634\u0646\u06CC\u062F\u0646"]');
                    if (!btn) {
                        var allBtns = overlay.querySelectorAll('button');
                        for (var i = 0; i < allBtns.length; i++) {
                            var c = allBtns[i].className || '', t = allBtns[i].textContent || '';
                            if (c.indexOf('listen') !== -1 || t.indexOf('Listen') !== -1 || t.indexOf('\u0641\u0642\u0637') !== -1) {
                                btn = allBtns[i]; break;
                            }
                        }
                    }
                    if (btn) { clearInterval(iv); btn.click(); console.log('[AUT] Listen-only clicked'); }
                    if (n >= MAX) clearInterval(iv);
                }, 1000);
            })();
        """.trimIndent()
        webView.evaluateJavascript(js) { log("🎙️ BBB listen-only join attempted.") }
    }

    // =========================================================================
    //  Reload / retry
    // =========================================================================

    private fun scheduleReload(delayMs: Long = 60_000L) {
        if (refreshCount >= maxRefreshes) { log("❌ Max retries reached."); return }
        refreshCount++
        cancelPendingReload()
        log("🔄 Refresh #$refreshCount in ${delayMs/1000}s...")
        val r = Runnable {
            pendingReloadRunnable = null
            log("🔄 Reloading page...")
            webView.reload()
        }
        pendingReloadRunnable = r
        mainHandler.postDelayed(r, delayMs)
    }

    private fun cancelPendingReload() {
        pendingReloadRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingReloadRunnable = null
    }

    // =========================================================================
    //  Activity attach / detach  (crash-safe)
    // =========================================================================

    fun attachToActivity(container: ViewGroup) {
        if (isWebViewInBackground) {
            try { windowManager.removeViewImmediate(webView) } catch (_: Exception) {}
            isWebViewInBackground = false
        } else {
            safeRemoveFromParent()
        }
        webView.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        container.addView(webView)
    }

    fun attachToBackground() {
        if (isWebViewInBackground) return
        safeRemoveFromParent()
        try {
            webView.layoutParams = backgroundLayoutParams
            windowManager.addView(webView, backgroundLayoutParams)
            isWebViewInBackground = true
        } catch (_: Exception) {}
    }

    /**
     * Safely remove WebView from its current parent.
     * Parent can be:
     *   - null        → nothing to do
     *   - ViewGroup   → normal removeView()
     *   - ViewRootImpl → is WindowManager root; use windowManager.removeViewImmediate()
     */
    private fun safeRemoveFromParent() {
        val parent = webView.parent ?: return
        if (parent is ViewGroup) {
            try { parent.removeView(webView) } catch (_: Exception) {}
        } else {
            // ViewRootImpl (WindowManager overlay) — cannot cast to ViewGroup
            try { windowManager.removeViewImmediate(webView); isWebViewInBackground = false } catch (_: Exception) {}
        }
    }

    // =========================================================================
    //  WakeLock
    // =========================================================================

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock == null) wakeLock = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LmsAutoJoiner::Discovery")
        if (wakeLock?.isHeld == false) wakeLock?.acquire(15 * 60 * 1000L)
        log("🔒 WakeLock acquired.")
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) { wakeLock?.release(); log("🔓 WakeLock released.") }
    }

    // =========================================================================
    //  Notification
    // =========================================================================

    private fun startForegroundNotification() {
        val cid = "AutoJoinChannel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(cid, "LMS Auto Joiner", NotificationManager.IMPORTANCE_LOW))
        startForeground(1, buildNotification("Watching your schedule..."))
    }

    private fun updateNotification(text: String) =
        getSystemService(NotificationManager::class.java).notify(1, buildNotification(text))

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, "AutoJoinChannel")
            .setContentTitle("LMS Auto Joiner")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    // =========================================================================
    //  Utility
    // =========================================================================

    private fun parseSpecFromJson(json: String): ClassSpec {
        val o = JSONObject(json)
        val days = (0 until o.getJSONArray("days").length()).map { o.getJSONArray("days").getString(it) }
        return ClassSpec(o.getString("name"), o.getString("startTime"), o.getString("endTime"), days, o.optString("platform", "LMS").uppercase())
    }
}