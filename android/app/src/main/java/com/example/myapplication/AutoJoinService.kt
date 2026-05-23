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
        LMS_COURSE_PAGE,
        LMS_BBB_JOINING,
        IN_CLASS,
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
    private var pendingClassEndRunnable: Runnable? = null
    private var casInjectedForUrl: String? = null
    private var wakeLock: PowerManager.WakeLock? = null

    // ── Schedule ─────────────────────────────────────────────────────────────
    private val schedule = mutableListOf<ClassSpec>()
    private val alarmTimers = mutableListOf<Timer>()

    // ── Discovery ────────────────────────────────────────────────────────────
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
            }
            intent?.getBooleanExtra("RESET_SESSION", false) == true -> resetWebSession()
            intent?.getBooleanExtra("CANCEL_DISCOVERY", false) == true -> cancelDiscovery()
            intent?.getBooleanExtra("RELOAD_SCHEDULE", false) == true -> {
                loadSchedule(); registerAlarms()
            }
            intent?.getBooleanExtra("GO_TO_CLASS", false) == true -> goToClass()
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
        try { attachToBackground() } catch (_: Exception) {}
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseWakeLock()
        alarmTimers.forEach { it.cancel() }
        cancelPendingReload()
        cancelClassEndTimer()
        if (isWebViewInBackground) try { windowManager.removeView(webView) } catch (_: Exception) {}
        webView.destroy()
    }

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
        if (schedule.isEmpty()) return

        val dayMap = mapOf(Calendar.SUNDAY to "sun", Calendar.MONDAY to "mon", Calendar.TUESDAY to "tue", Calendar.WEDNESDAY to "wed", Calendar.THURSDAY to "thu", Calendar.FRIDAY to "fri", Calendar.SATURDAY to "sat")
        val timer = Timer(true).also { alarmTimers.add(it) }
        timer.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Tehran"))
                val today = dayMap[cal.get(Calendar.DAY_OF_WEEK)] ?: return
                val nowStr = "%02d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))

                for (spec in schedule) {
                    if (today !in spec.days || spec.startTime != nowStr) continue
                    mainHandler.post {
                        if (flowState != FlowState.IDLE) forceResetAndJoin(spec)
                        else startJoinFlow(spec)
                    }
                }
            }
        }, (60 - Calendar.getInstance().get(Calendar.SECOND)) * 1000L, 60_000L)
    }

    private fun checkCatchUp() {
        val dayMap = mapOf(Calendar.SUNDAY to "sun", Calendar.MONDAY to "mon", Calendar.TUESDAY to "tue", Calendar.WEDNESDAY to "wed", Calendar.THURSDAY to "thu", Calendar.FRIDAY to "fri", Calendar.SATURDAY to "sat")
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Tehran"))
        val today = dayMap[cal.get(Calendar.DAY_OF_WEEK)] ?: return
        val now = "%02d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))

        schedule.firstOrNull { today in it.days && now >= it.startTime && now < it.endTime }?.let {
            mainHandler.postDelayed({ startJoinFlow(it) }, 2_000L)
        }
    }

    private fun scheduleClassEndTimer(spec: ClassSpec) {
        cancelClassEndTimer()
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Tehran"))
        val endParts = spec.endTime.split(":")
        val endCal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Tehran")).apply {
            set(Calendar.HOUR_OF_DAY, endParts[0].toInt()); set(Calendar.MINUTE, endParts[1].toInt()); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val delayMs = endCal.timeInMillis - cal.timeInMillis
        if (delayMs <= 0) { onClassEnded(spec); return }

        val r = Runnable { pendingClassEndRunnable = null; onClassEnded(spec) }
        pendingClassEndRunnable = r
        mainHandler.postDelayed(r, delayMs)
    }

    private fun cancelClassEndTimer() {
        pendingClassEndRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingClassEndRunnable = null
    }

    private fun onClassEnded(endedSpec: ClassSpec) {
        cancelClassEndTimer()
        cancelPendingReload()

        if (activeSpec?.name == endedSpec.name) {
            flowState = FlowState.IDLE
            activeSpec = null
            refreshCount = 0

            val nextClass = findCurrentlyActiveClass(exclude = endedSpec)
            if (nextClass != null) mainHandler.postDelayed({ startJoinFlow(nextClass) }, 2_000L)
            else { releaseWakeLock(); updateNotification("Watching your schedule..."); webView.loadUrl("about:blank") }
        }
    }

    private fun findCurrentlyActiveClass(exclude: ClassSpec? = null): ClassSpec? {
        val dayMap = mapOf(Calendar.SUNDAY to "sun", Calendar.MONDAY to "mon", Calendar.TUESDAY to "tue", Calendar.WEDNESDAY to "wed", Calendar.THURSDAY to "thu", Calendar.FRIDAY to "fri", Calendar.SATURDAY to "sat")
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Tehran"))
        val today = dayMap[cal.get(Calendar.DAY_OF_WEEK)] ?: return null
        val now = "%02d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
        return schedule.firstOrNull { it != exclude && today in it.days && now >= it.startTime && now < it.endTime }
    }

    private fun findNextClassToday(): ClassSpec? {
        val dayMap = mapOf(Calendar.SUNDAY to "sun", Calendar.MONDAY to "mon", Calendar.TUESDAY to "tue", Calendar.WEDNESDAY to "wed", Calendar.THURSDAY to "thu", Calendar.FRIDAY to "fri", Calendar.SATURDAY to "sat")
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Tehran"))
        val today = dayMap[cal.get(Calendar.DAY_OF_WEEK)] ?: return null
        val now = "%02d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
        return schedule.filter { today in it.days && it.startTime > now }.minByOrNull { it.startTime }
    }

    private fun forceResetAndJoin(spec: ClassSpec) {
        if (activeSpec?.name == spec.name && flowState != FlowState.IDLE) return
        cancelClassEndTimer(); cancelPendingReload()

        flowState = FlowState.IDLE
        activeSpec = spec
        refreshCount = 0
        casInjectedForUrl = null
        isDiscovering = false

        webView.stopLoading()
        acquireWakeLock()
        updateNotification("Joining: ${spec.name}")

        val homeUrl = if (spec.platform == "NIMA") "https://lms.aut.ac.ir/" else "https://lmshome.aut.ac.ir/"
        flowState = FlowState.NAVIGATING_TO_HOME
        webView.loadUrl(homeUrl)
    }

    private fun goToClass() {
        val prefs = getSharedPreferences("LMS_PREFS", Context.MODE_PRIVATE)
        if (prefs.getString("USERNAME", "").isNullOrEmpty() || isDiscovering) return

        loadSchedule(); registerAlarms()
        if (schedule.isEmpty() || (flowState == FlowState.IN_CLASS && activeSpec != null)) return

        val currentClass = findCurrentlyActiveClass()
        if (currentClass != null) {
            if (flowState != FlowState.IDLE) forceResetAndJoin(currentClass) else startJoinFlow(currentClass)
        } else {
            findNextClassToday()?.let {
                updateNotification("⏳ کلاس بعدی: ${it.name} ساعت ${it.startTime}")
                mainHandler.postDelayed({ updateNotification("Watching your schedule...") }, 8_000L)
            }
        }
    }

    private fun startJoinFlow(spec: ClassSpec) {
        if (activeSpec?.name == spec.name && flowState != FlowState.IDLE) return
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Tehran"))
        val now = "%02d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
        if (now >= spec.endTime) return

        activeSpec = spec; refreshCount = 0; casInjectedForUrl = null; isDiscovering = false
        cancelPendingReload(); cancelClassEndTimer(); acquireWakeLock()
        updateNotification("Joining: ${spec.name}")

        flowState = FlowState.NAVIGATING_TO_HOME
        webView.loadUrl(if (spec.platform == "NIMA") "https://lms.aut.ac.ir/" else "https://lmshome.aut.ac.ir/")
    }

    private fun testLogin() {
        activeSpec = null; casInjectedForUrl = null; isDiscovering = false
        flowState = FlowState.NAVIGATING_TO_HOME
        webView.loadUrl("https://lmshome.aut.ac.ir/")
    }

    private fun startDiscoveryFlow() {
        val prefs = getSharedPreferences("LMS_PREFS", Context.MODE_PRIVATE)
        if (prefs.getString("USERNAME", "").isNullOrEmpty()) return

        if (flowState == FlowState.IN_CLASS) { cancelClassEndTimer(); cancelPendingReload() }
        isDiscovering = true; activeSpec = null; casInjectedForUrl = null
        discoveredCourseLinks.clear(); discoveredClasses.clear(); discoveryIndex = 0
        cancelPendingReload(); acquireWakeLock()

        updateNotification(getString(R.string.notif_discovering))
        flowState = FlowState.DISCOVERY_NAVIGATING
        webView.loadUrl("https://lmshome.aut.ac.ir/")
    }

    fun cancelDiscovery() {
        if (!isDiscovering) return
        isDiscovering = false; flowState = FlowState.IDLE; releaseWakeLock()
        updateNotification("Watching your schedule...")
        mainHandler.post { discoveryCompleteListener?.invoke(0, false) }
    }

    private fun scrapeCourseLinksfromDashboard() {
        val js = """
            (function() {
                var n=0,MAX=20;
                var iv=setInterval(function(){
                    n++;var links=document.querySelectorAll('a.course-link');
                    if(links.length>0){
                        clearInterval(iv);
                        var res=[];
                        for(var i=0; i<links.length; i++){
                            var a = links[i];
                            // Removes anything after and including " - گروه" safely
                            var name = a.textContent.replace(/\s+/g,' ').trim().replace(/\s*-\s*گروه.*/,'').trim();
                            res.push({name:name,url:a.href});
                        }
                        window._discoveredLinks=JSON.stringify(res);
                    }
                    if(n>=MAX){clearInterval(iv);window._discoveredLinks='[]';}
                },500);
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
        mainHandler.postDelayed({
            if (!isDiscovering) return@postDelayed
            webView.evaluateJavascript("window._discoveredLinks||'[]'") { raw ->
                try {
                    val arr = JSONArray(raw?.trim('"')?.replace("\\\"", "\"")?.replace("\\\\", "\\") ?: "[]")
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        discoveredCourseLinks.add(Pair(o.getString("name"), o.getString("url")))
                    }
                    if (discoveredCourseLinks.isEmpty()) finishDiscovery(false) else visitNextCourseForDiscovery()
                } catch (e: Exception) { finishDiscovery(false) }
            }
        }, 12_000L)
    }

    private fun visitNextCourseForDiscovery() {
        if (!isDiscovering) return
        if (discoveryIndex >= discoveredCourseLinks.size) { finishDiscovery(true); return }
        val (name, url) = discoveredCourseLinks[discoveryIndex]
        updateNotification("Scanning ${discoveryIndex + 1} of ${discoveredCourseLinks.size}…")
        mainHandler.post { discoveryProgressListener?.invoke(discoveryIndex + 1, discoveredCourseLinks.size, name) }

        // Fix: Leave the state as NAVIGATING so onPageFinished injects correctly
        flowState = FlowState.DISCOVERY_NAVIGATING
        webView.loadUrl(url)
    }

    private fun scrapeScheduleFromCoursePage() {
        if (!isDiscovering || discoveryIndex >= discoveredCourseLinks.size) return
        val (name, _) = discoveredCourseLinks[discoveryIndex]
        val js = """
            (function(){var n=0,MAX=15;var iv=setInterval(function(){n++;
            var lis=document.querySelectorAll('li'),txt='';
            for(var i=0;i<lis.length;i++){
                if(lis[i].textContent.includes('زمان برگزاری')){
                    var b=lis[i].querySelector('b');
                    if(b){txt=b.textContent.replace(/\s+/g,' ').trim();break;}
                }
            }
            if(txt){clearInterval(iv);window._courseSchedule=txt;}
            if(n>=MAX){clearInterval(iv);if(!window._courseSchedule)window._courseSchedule='';}
            },500);})();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
        mainHandler.postDelayed({
            if (!isDiscovering) return@postDelayed
            webView.evaluateJavascript("window._courseSchedule||''") { raw ->
                val txt = raw?.trim('"')?.replace("\\\"", "\"")?.replace("\\\\", "\\")?.trim() ?: ""
                webView.evaluateJavascript("window._courseSchedule='';", null)
                if (txt.isNotEmpty()) {
                    val sessions = ClassDiscoveryManager.parseScheduleText(txt)
                    val grouped = mutableMapOf<Pair<String, String>, MutableList<String>>()
                    sessions.forEach { (day, s, e) -> grouped.getOrPut(Pair(s, e)) { mutableListOf() }.add(day) }
                    grouped.forEach { (tk, days) -> discoveredClasses.add(ClassDiscoveryManager.DiscoveredClass(name, "", days, tk.first, tk.second)) }
                }
                discoveryIndex++
                mainHandler.postDelayed({ visitNextCourseForDiscovery() }, 1_500L)
            }
        }, 10_000L)
    }

    private fun finishDiscovery(success: Boolean) {
        isDiscovering = false; flowState = FlowState.IDLE; releaseWakeLock()
        if (success && discoveredClasses.isNotEmpty()) {
            val em = ClassDiscoveryManager.loadDiscoveredClasses(this).associateBy { "${it.name}|${it.start}|${it.end}" }
            discoveredClasses.forEach { c -> em["${c.name}|${c.start}|${c.end}"]?.let { c.platform = it.platform; c.enabled = it.enabled } }
            ClassDiscoveryManager.saveDiscoveredClasses(this, discoveredClasses)
            ClassDiscoveryManager.generateScheduleJson(this, discoveredClasses)
            loadSchedule(); registerAlarms()
            updateNotification(getString(R.string.notif_discovery_done, discoveredClasses.size))
        } else {
            updateNotification("Watching your schedule...")
        }
        mainHandler.post { discoveryCompleteListener?.invoke(discoveredClasses.size, success) }
        mainHandler.postDelayed({ updateNotification("Watching your schedule...") }, 5_000L)
        mainHandler.postDelayed({ if (flowState == FlowState.IDLE) findCurrentlyActiveClass()?.let { startJoinFlow(it) } }, 3_000L)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView = WebView(this).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                mediaPlaybackRequiresUserGesture = false
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
                useWideViewPort = true
                loadWithOverviewMode = true
                isVerticalScrollBarEnabled = true
                isHorizontalScrollBarEnabled = true
                setSupportMultipleWindows(false)

                setOnTouchListener { v, _ -> v.parent?.requestDisallowInterceptTouchEvent(true); false }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                userAgentString = userAgentString.replace("; wv", "").replace("Mobile", "Desktop")
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSPARENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
    }

    private inner class LmsWebViewClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean = false

        override fun onPageFinished(view: WebView, url: String) {
            view.evaluateJavascript("window.open = function(url){ window.location.href = url; return null; };", null)
            handlePageLoaded(url)
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            super.doUpdateVisitedHistory(view, url, isReload)
            handlePageLoaded(url)
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            log("⚠️ SSL Error ignored."); handler.proceed()
        }

        override fun onReceivedError(view: WebView, req: WebResourceRequest, err: WebResourceError) {
            if (req.isForMainFrame && flowState != FlowState.IDLE) scheduleReload(30_000L)
        }

        override fun onReceivedHttpError(view: WebView, req: WebResourceRequest, errorResponse: WebResourceResponse) {
            if (req.isForMainFrame && errorResponse.statusCode >= 500 && flowState != FlowState.IDLE) scheduleReload(30_000L)
        }
    }

    private fun isCasUrl(u: String) = u.contains("accounts.aut.ac.ir") || u.contains("cas.aut.ac.ir") || u.contains("/cas/login")
    private fun isLmsDashboard(u: String) = u.contains("lmshome.aut.ac.ir") && u.contains("panel/home")
    private fun isLmsCoursePage(u: String) = u.contains("lmshome.aut.ac.ir") && (u.contains("panel/myLesson") || u.contains("course/view.php"))
    private fun isNimaDashboard(u: String): Boolean {
        if (!u.startsWith("https://lms.aut.ac.ir")) return false
        if (u.contains("lmshome.aut.ac.ir")) return false
        return u.contains("users-panel") || u.contains("dashboard") || u.contains("announcements-list") || u.contains("panel") || u.trimEnd('/') == "https://lms.aut.ac.ir"
    }
    private fun isBbbPage(u: String) = u.contains("html5client") || u.contains("bigbluebutton")
    private fun isLoginPage(u: String) = u.contains("login/index.php")
    private fun isBlankPage(u: String) = u == "about:blank"

    private fun handlePageLoaded(url: String) {
        if (flowState == FlowState.IN_CLASS) {
            if (isLoginPage(url)) {
                log("⚠️ Session expired during class!")
                flowState = FlowState.WAITING_SSO_BUTTON
                clickSsoButton()
            }
            return
        }

        if (isBlankPage(url)) return

        when {
            isLoginPage(url) -> {
                if (flowState == FlowState.WAITING_SSO_BUTTON) return
                flowState = FlowState.WAITING_SSO_BUTTON
                clickSsoButton()
            }
            isCasUrl(url) -> {
                if (flowState == FlowState.ON_CAS_PAGE && casInjectedForUrl == url) return
                flowState = FlowState.ON_CAS_PAGE
                casInjectedForUrl = url
                injectCasCredentials()
            }
            isLmsDashboard(url) -> {
                if (flowState == FlowState.LMS_DASHBOARD || flowState == FlowState.DISCOVERY_DASHBOARD) return
                if (isDiscovering) {
                    flowState = FlowState.DISCOVERY_DASHBOARD
                    scrapeCourseLinksfromDashboard()
                } else {
                    flowState = FlowState.LMS_DASHBOARD
                    if (activeSpec?.platform == "LMS") navigateToLmsCourse()
                    else if (activeSpec?.platform == "NIMA") webView.loadUrl("https://lms.aut.ac.ir/")
                }
            }
            isNimaDashboard(url) -> {
                if (flowState == FlowState.NIMA_DASHBOARD || flowState == FlowState.DISCOVERY_DASHBOARD) return
                if (isDiscovering) {
                    flowState = FlowState.DISCOVERY_DASHBOARD
                    scrapeCourseLinksfromDashboard()
                } else {
                    flowState = FlowState.NIMA_DASHBOARD
                    if (activeSpec?.platform == "NIMA") scanNimaOngoingMeetings()
                    else if (activeSpec?.platform == "LMS") webView.loadUrl("https://lmshome.aut.ac.ir/")
                }
            }
            isLmsCoursePage(url) -> {
                if (flowState == FlowState.LMS_COURSE_PAGE || flowState == FlowState.DISCOVERY_SCRAPING_COURSE) return
                if (isDiscovering) {
                    flowState = FlowState.DISCOVERY_SCRAPING_COURSE
                    scrapeScheduleFromCoursePage()
                } else {
                    flowState = FlowState.LMS_COURSE_PAGE
                    scanLmsJoinButton()
                }
            }
            isBbbPage(url) -> {
                if (flowState == FlowState.LMS_BBB_JOINING || flowState == FlowState.IN_CLASS) return
                flowState = FlowState.LMS_BBB_JOINING
                cancelPendingReload()
                joinBbbListenOnly()
            }
        }
    }

    private fun clickSsoButton() {
        val js = """
        (function(){
            var iv = setInterval(function(){
                var ssoBtn = document.querySelector('.login-identityprovider-btn');
                if (!ssoBtn) ssoBtn = document.querySelector('a[href*="CASattras"]');
                if (ssoBtn) { clearInterval(iv); ssoBtn.click(); }
            }, 500);
        })();
        """
        webView.evaluateJavascript(js, null)
    }

    private fun injectCasCredentials() {
        val prefs = getSharedPreferences("LMS_PREFS", Context.MODE_PRIVATE)
        val user = (prefs.getString("USERNAME", "") ?: "").replace("\\", "\\\\").replace("'", "\\'")
        val pass = (prefs.getString("PASSWORD", "") ?: "").replace("\\", "\\\\").replace("'", "\\'")
        if (user.isEmpty() || pass.isEmpty()) return

        val js = """
        (function(){
            var iv = setInterval(function(){
                var uf = document.getElementById('username');
                var pf = document.getElementById('password');
                if (uf && pf) {
                    clearInterval(iv);
                    uf.value = '$user'; pf.value = '$pass';
                    setTimeout(function(){
                        var sb = document.querySelector('input[name="submit"], button[type="submit"], input[type="submit"]');
                        if (sb) sb.click();
                        else { var form = uf.closest('form'); if (form) form.submit(); }
                    }, 500);
                }
            }, 500);
        })();
        """
        webView.evaluateJavascript(js, null)
    }

    private fun navigateToLmsCourse() {
        val spec = activeSpec ?: return
        val safe = spec.name.replace("'", "\\'")
        val js = """
            (function(){var iv=setInterval(function(){
            var links=document.querySelectorAll('a.course-link'),t=null;
            for(var i=0;i<links.length;i++){ if(links[i].textContent.includes('$safe')){t=links[i];break;} }
            if(t){clearInterval(iv); t.click(); }
            },500);})();
        """
        webView.evaluateJavascript(js, null)
    }

    private fun scanLmsJoinButton() {
        val spec = activeSpec ?: return
        val st = spec.startTime
        val stAlt = if (st.startsWith("0")) st.substring(1) else st

        val js = """
        (function(){
            var st='$st', stAlt='$stAlt', MAX=30, n=0;
            window._autNeedReload = false;

            var iv = setInterval(function(){
                n++;
                var rows = document.querySelectorAll('tr');
                for (var r = 0; r < rows.length; r++) {
                    var row = rows[r];
                    var text = (row.textContent || '').replace(/\s+/g,' ');
                    if (text.indexOf(st) === -1 && text.indexOf(stAlt) === -1) continue;
                    if (text.indexOf('\u0647\u0646\u0648\u0632 \u0628\u0631\u06AF\u0632\u0627\u0631 \u0646\u0634\u062F\u0647') !== -1) {
                        clearInterval(iv); window._autNeedReload = true; return;
                    }

                    var form = row.querySelector('form[action*="join"]');
                    if (form) { clearInterval(iv); form.submit(); return; }

                    var nextRow = row.nextElementSibling;
                    if (nextRow && nextRow.classList.contains('child')) {
                        form = nextRow.querySelector('form[action*="join"]');
                        if (form) { clearInterval(iv); form.submit(); return; }
                    }

                    var btns = row.querySelectorAll('button');
                    for (var i = 0; i < btns.length; i++) {
                        if ((btns[i].textContent || '').indexOf('\u0648\u0631\u0648\u062F') !== -1 && !btns[i].disabled) {
                            clearInterval(iv); 
                            btns[i].click(); 
                            btns[i].dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, view: window }));
                            return;
                        }
                    }

                    var expandBtn = row.querySelector('td.sorting_1, td[tabindex="0"], .dtr-control');
                    if (expandBtn && n % 3 === 0) expandBtn.click();
                }

                if (n >= MAX) { clearInterval(iv); window._autNeedReload = true; }
            }, 1000);
        })();
        """

        webView.evaluateJavascript(js, null)
        mainHandler.postDelayed({
            webView.evaluateJavascript("String(window._autNeedReload)") { r ->
                if (r?.contains("true") == true) {
                    webView.evaluateJavascript("window._autNeedReload=false;", null)
                    scheduleReload()
                }
            }
        }, 32_000L)
    }

    private fun scanNimaOngoingMeetings() {
        val spec = activeSpec ?: return
        val st = spec.startTime
        val stAlt = if (st.startsWith("0")) st.substring(1) else st
        val nameFrag = spec.name.take(8).replace("'", "\\'")

        val js = """
        (function(){
            var st = '$st', stAlt = '$stAlt', nameFrag = '$nameFrag';
            if (window._autNimaInterval) clearInterval(window._autNimaInterval);
            
            var MAX = 60, n = 0;
            window._autNimaResult = 'waiting';

            window._autNimaInterval = setInterval(function(){
                n++;
                var targetBtn = null;
                
                // Locate the correct row
                var rows = document.querySelectorAll('tr');
                for (var i = 0; i < rows.length; i++) {
                    var text = (rows[i].textContent || '').replace(/\s+/g, ' ');
                    if (text.indexOf(st) !== -1 || text.indexOf(stAlt) !== -1 || text.indexOf(nameFrag) !== -1) {
                        var btn = rows[i].querySelector('button[name="join"]:not([disabled]):not(.p-disabled)');
                        if (btn) { targetBtn = btn; break; }
                    }
                }
                
                // Fallbacks
                if (!targetBtn) {
                    var allJoinBtns = document.querySelectorAll('button[name="join"]:not([disabled]):not(.p-disabled)');
                    if (allJoinBtns.length > 0) targetBtn = allJoinBtns[0];
                }
                if (!targetBtn) {
                    var btns = document.querySelectorAll('button:not([disabled]):not(.p-disabled)');
                    for (var i = 0; i < btns.length; i++) {
                        if ((btns[i].textContent || '').indexOf('\u0648\u0631\u0648\u062F') !== -1) {
                            targetBtn = btns[i]; break;
                        }
                    }
                }

                if (targetBtn) {
                    clearInterval(window._autNimaInterval);
                    console.log('[AUT] NIMA join button found properly enabled');
                    
                    targetBtn.scrollIntoView({ behavior: 'instant', block: 'center' });
                    
                    setTimeout(function(){
                        targetBtn.focus();
                        var evObj = { bubbles: true, cancelable: true, view: window };
                        
                        targetBtn.dispatchEvent(new MouseEvent('mousedown', evObj));
                        targetBtn.dispatchEvent(new MouseEvent('mouseup', evObj));
                        targetBtn.click();
                        targetBtn.dispatchEvent(new MouseEvent('click', evObj));
                        
                        var spans = targetBtn.querySelectorAll('span');
                        for(var k=0; k<spans.length; k++){
                            spans[k].click();
                            spans[k].dispatchEvent(new MouseEvent('click', evObj));
                        }
                        window._autNimaResult = 'clicked';
                    }, 500);
                    return;
                }

                var pageText = document.body.innerText || '';
                if (pageText.indexOf('\u0647\u0646\u0648\u0632 \u0634\u0631\u0648\u0639 \u0646\u0634\u062F\u0647') !== -1 ||
                    pageText.indexOf('\u0647\u0646\u0648\u0632 \u0628\u0631\u06AF\u0632\u0627\u0631 \u0646\u0634\u062F\u0647') !== -1) {
                    clearInterval(window._autNimaInterval);
                    window._autNimaResult = 'notstarted';
                    return;
                }

                if (n >= MAX) {
                    clearInterval(window._autNimaInterval);
                    window._autNimaResult = 'notfound';
                }
            }, 1000);
        })();
        """

        webView.evaluateJavascript(js, null)

        mainHandler.postDelayed({
            webView.evaluateJavascript("window._autNimaResult || 'waiting'") { raw ->
                val result = raw?.trim('"') ?: "waiting"
                webView.evaluateJavascript("window._autNimaResult = 'waiting';", null)

                when (result) {
                    "clicked" -> log("✅ NIMA: join button clicked successfully.")
                    else -> scheduleNimaRetry()
                }
            }
        }, 65_000L)
    }

    private fun scheduleNimaRetry() {
        if (refreshCount >= maxRefreshes) return
        refreshCount++
        cancelPendingReload()

        val r = Runnable {
            pendingReloadRunnable = null
            if (activeSpec == null || flowState == FlowState.IN_CLASS) return@Runnable
            flowState = FlowState.NAVIGATING_TO_HOME
            webView.loadUrl("https://lms.aut.ac.ir/users-panel/announcements-list")
        }
        pendingReloadRunnable = r
        mainHandler.postDelayed(r, 60_000L)
    }

    private fun joinBbbListenOnly() {
        val js = """
            (function(){
                window._autBbbWaitingForModal = false;
                setInterval(function(){
                    var noAudioIcon = document.querySelector('.icon-bbb-no_audio');
                    if (noAudioIcon) {
                        var listenBtn = document.querySelector('button[aria-label="تنها شنونده"]') || document.querySelector('button[aria-label="Listen only"]');
                        if (listenBtn && !listenBtn.disabled) {
                            listenBtn.click();
                            window._autBbbWaitingForModal = false; 
                            return;
                        }
                        var joinAudioBtn = noAudioIcon.closest('button') || noAudioIcon.closest('span');
                        if (joinAudioBtn && !joinAudioBtn.disabled && !window._autBbbWaitingForModal) {
                            joinAudioBtn.click();
                            window._autBbbWaitingForModal = true;
                            setTimeout(function() { window._autBbbWaitingForModal = false; }, 5000);
                        }
                    } else { window._autBbbWaitingForModal = false; }
                }, 2500);
            })();
        """
        webView.evaluateJavascript(js) {
            flowState = FlowState.IN_CLASS
            activeSpec?.let { spec ->
                updateNotification("📚 In class: ${spec.name} (until ${spec.endTime})")
                scheduleClassEndTimer(spec)
                log("✅ IN_CLASS: '${spec.name}' until ${spec.endTime}")
            }
        }
    }

    private fun scheduleReload(delayMs: Long = 60_000L) {
        if (refreshCount >= maxRefreshes) return
        refreshCount++
        cancelPendingReload()
        val r = Runnable {
            pendingReloadRunnable = null
            flowState = FlowState.NAVIGATING_TO_HOME
            webView.reload()
        }
        pendingReloadRunnable = r
        mainHandler.postDelayed(r, delayMs)
    }

    private fun cancelPendingReload() {
        pendingReloadRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingReloadRunnable = null
    }

    fun attachToActivity(container: ViewGroup) {
        if (isWebViewInBackground) {
            try { windowManager.removeViewImmediate(webView) } catch (_: Exception) {}
            isWebViewInBackground = false
        } else detachWebViewSafely()

        webView.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        try { container.addView(webView) } catch (_: Exception) {}
    }

    fun attachToBackground() {
        if (isWebViewInBackground) return
        detachWebViewSafely()
        try {
            webView.layoutParams = FrameLayout.LayoutParams(1, 1)
            windowManager.addView(webView, backgroundLayoutParams)
            isWebViewInBackground = true
        } catch (_: Exception) {}
    }

    private fun detachWebViewSafely() {
        val parent = webView.parent ?: return
        if (parent is ViewGroup) try { parent.removeView(webView) } catch (_: Exception) {}
        else try { windowManager.removeViewImmediate(webView); isWebViewInBackground = false } catch (_: Exception) {}
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock == null) wakeLock = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LmsAutoJoiner::Active")
        if (wakeLock?.isHeld == false) wakeLock?.acquire(4 * 60 * 60 * 1000L)
    }

    private fun releaseWakeLock() { if (wakeLock?.isHeld == true) wakeLock?.release() }

    private fun startForegroundNotification() {
        val cid = "AutoJoinChannel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(cid, "LMS Auto Joiner", NotificationManager.IMPORTANCE_LOW))
        startForeground(1, buildNotification("Watching your schedule..."))
    }

    private fun updateNotification(text: String) = getSystemService(NotificationManager::class.java).notify(1, buildNotification(text))

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, "AutoJoinChannel").setContentTitle("LMS Auto Joiner").setContentText(text).setSmallIcon(android.R.drawable.ic_menu_agenda).setOngoing(true).setContentIntent(pi).build()
    }

    private fun parseSpecFromJson(json: String): ClassSpec {
        val o = JSONObject(json)
        val days = (0 until o.getJSONArray("days").length()).map { o.getJSONArray("days").getString(it) }
        return ClassSpec(o.getString("name"), o.getString("startTime"), o.getString("endTime"), days, o.optString("platform", "LMS").uppercase())
    }

    fun resetWebSession() {
        try {
            webView.stopLoading(); CookieManager.getInstance().removeAllCookies(null); CookieManager.getInstance().flush()
            webView.clearCache(true); webView.clearHistory(); webView.clearFormData(); WebStorage.getInstance().deleteAllData()
            flowState = FlowState.IDLE; activeSpec = null; refreshCount = 0; casInjectedForUrl = null; isDiscovering = false
            cancelPendingReload(); cancelClassEndTimer(); webView.loadUrl("about:blank")
        } catch (_: Exception) { }
    }
}