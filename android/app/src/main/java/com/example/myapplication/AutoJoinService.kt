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
            intent?.getBooleanExtra("RESET_SESSION", false) == true -> {
                resetWebSession()
            }
            intent?.getBooleanExtra("CANCEL_DISCOVERY", false) == true -> cancelDiscovery()
            intent?.getBooleanExtra("RELOAD_SCHEDULE", false) == true -> {
                log("🔄 Reloading schedule...")
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

    // =========================================================================
    //  Schedule
    // =========================================================================

    private fun loadSchedule() {
        schedule.clear()
        try {
            val arr = JSONArray(ClassDiscoveryManager.loadScheduleJson(this))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val days = (0 until o.getJSONArray("days").length()).map {
                    o.getJSONArray("days").getString(it)
                }
                schedule.add(
                    ClassSpec(
                        o.getString("name"),
                        o.getString("start"),
                        o.getString("end"),
                        days,
                        o.optString("platform", "LMS").uppercase()
                    )
                )
            }
            log("📅 Loaded ${schedule.size} class(es).")
        } catch (e: Exception) {
            log("⚠️ Schedule: ${e.message}")
        }
    }

    // =========================================================================
    //  Alarms — single minute-tick timer
    // =========================================================================

    private fun registerAlarms() {
        alarmTimers.forEach { it.cancel() }
        alarmTimers.clear()
        if (schedule.isEmpty()) return

        val dayMap = mapOf(
            Calendar.SUNDAY to "sun", Calendar.MONDAY to "mon",
            Calendar.TUESDAY to "tue", Calendar.WEDNESDAY to "wed",
            Calendar.THURSDAY to "thu", Calendar.FRIDAY to "fri",
            Calendar.SATURDAY to "sat"
        )

        val timer = Timer(true).also { alarmTimers.add(it) }
        timer.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Tehran"))
                val today = dayMap[cal.get(Calendar.DAY_OF_WEEK)] ?: return
                val nowStr = "%02d:%02d".format(
                    cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE)
                )

                for (spec in schedule) {
                    if (today !in spec.days) continue
                    if (spec.startTime != nowStr) continue
                    mainHandler.post {
                        log("⏰ Timer: time to join '${spec.name}'")
                        if (flowState != FlowState.IDLE) {
                            log("🔄 Resetting from state $flowState for new class")
                            forceResetAndJoin(spec)
                        } else {
                            startJoinFlow(spec)
                        }
                    }
                }
            }
        }, (60 - Calendar.getInstance().get(Calendar.SECOND)) * 1000L, 60_000L)

        log("⏱️ Minute-tick timer registered for ${schedule.size} class(es).")
    }

    // =========================================================================
    //  Catch-up
    // =========================================================================

    private fun checkCatchUp() {
        val dayMap = mapOf(
            Calendar.SUNDAY to "sun", Calendar.MONDAY to "mon",
            Calendar.TUESDAY to "tue", Calendar.WEDNESDAY to "wed",
            Calendar.THURSDAY to "thu", Calendar.FRIDAY to "fri",
            Calendar.SATURDAY to "sat"
        )
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Tehran"))
        val today = dayMap[cal.get(Calendar.DAY_OF_WEEK)] ?: return
        val now = "%02d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))

        schedule.firstOrNull {
            today in it.days && now >= it.startTime && now < it.endTime
        }?.let {
            log("⚠️ [CATCH-UP] Inside window for '${it.name}' — joining!")
            mainHandler.postDelayed({ startJoinFlow(it) }, 2_000L)
        }
    }

    // =========================================================================
    //  Class end timer
    // =========================================================================

    private fun scheduleClassEndTimer(spec: ClassSpec) {
        cancelClassEndTimer()

        val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Tehran"))
        val endParts = spec.endTime.split(":")
        val endCal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Tehran")).apply {
            set(Calendar.HOUR_OF_DAY, endParts[0].toInt())
            set(Calendar.MINUTE, endParts[1].toInt())
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val delayMs = endCal.timeInMillis - cal.timeInMillis
        if (delayMs <= 0) {
            log("⚠️ End time ${spec.endTime} already passed")
            onClassEnded(spec)
            return
        }

        log("⏱️ '${spec.name}' auto-ends at ${spec.endTime} (in ${delayMs / 60000} min)")

        val r = Runnable {
            pendingClassEndRunnable = null
            log("⏰ Class '${spec.name}' time is up!")
            onClassEnded(spec)
        }
        pendingClassEndRunnable = r
        mainHandler.postDelayed(r, delayMs)
    }

    private fun cancelClassEndTimer() {
        pendingClassEndRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingClassEndRunnable = null
    }

    // =========================================================================
    //  When a class ends
    // =========================================================================

    private fun onClassEnded(endedSpec: ClassSpec) {
        log("🔚 Class ended: '${endedSpec.name}'")
        cancelClassEndTimer()
        cancelPendingReload()

        flowState = FlowState.IDLE
        activeSpec = null
        refreshCount = 0

        val nextClass = findCurrentlyActiveClass(exclude = endedSpec)

        if (nextClass != null) {
            log("➡️ Next class found: '${nextClass.name}' (${nextClass.startTime}-${nextClass.endTime})")
            log("🔄 Switching to next class...")
            mainHandler.postDelayed({ startJoinFlow(nextClass) }, 3_000L)
        } else {
            log("💤 No more classes right now. Going idle.")
            releaseWakeLock()
            updateNotification("Watching your schedule...")
            webView.loadUrl("about:blank")
        }
    }

    private fun findCurrentlyActiveClass(exclude: ClassSpec? = null): ClassSpec? {
        val dayMap = mapOf(
            Calendar.SUNDAY to "sun", Calendar.MONDAY to "mon",
            Calendar.TUESDAY to "tue", Calendar.WEDNESDAY to "wed",
            Calendar.THURSDAY to "thu", Calendar.FRIDAY to "fri",
            Calendar.SATURDAY to "sat"
        )
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Tehran"))
        val today = dayMap[cal.get(Calendar.DAY_OF_WEEK)] ?: return null
        val now = "%02d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))

        return schedule.firstOrNull {
            it != exclude &&
                    today in it.days &&
                    now >= it.startTime &&
                    now < it.endTime
        }
    }

    private fun findNextClassToday(): ClassSpec? {
        val dayMap = mapOf(
            Calendar.SUNDAY to "sun", Calendar.MONDAY to "mon",
            Calendar.TUESDAY to "tue", Calendar.WEDNESDAY to "wed",
            Calendar.THURSDAY to "thu", Calendar.FRIDAY to "fri",
            Calendar.SATURDAY to "sat"
        )
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Tehran"))
        val today = dayMap[cal.get(Calendar.DAY_OF_WEEK)] ?: return null
        val now = "%02d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))

        return schedule
            .filter { today in it.days && it.startTime > now }
            .minByOrNull { it.startTime }
    }

    // =========================================================================
    //  Force reset
    // =========================================================================

    private fun forceResetAndJoin(spec: ClassSpec) {
        log("⚡ Force-resetting for '${spec.name}'")
        cancelClassEndTimer()
        cancelPendingReload()

        // Reset state FIRST so handlePageLoaded works normally for new class
        flowState = FlowState.IDLE
        activeSpec = null
        refreshCount = 0
        casInjectedForUrl = null
        isDiscovering = false

        webView.stopLoading()

        // Set new spec before navigating so handlePageLoaded routes correctly
        activeSpec = spec
        refreshCount = 0
        acquireWakeLock()
        updateNotification("Joining: ${spec.name}")
        log("🚀 Force-joining '${spec.name}' [${spec.platform}]")

        val homeUrl = if (spec.platform == "NIMA") "https://lms.aut.ac.ir/"
                      else "https://lmshome.aut.ac.ir/"

        flowState = FlowState.NAVIGATING_TO_HOME
        webView.loadUrl(homeUrl)
    }

    // =========================================================================
    //  Go To Class — manually triggered
    // =========================================================================

    private fun goToClass() {
        val prefs = getSharedPreferences("LMS_PREFS", Context.MODE_PRIVATE)
        if (prefs.getString("USERNAME", "").isNullOrEmpty()) {
            log("⚠️ اطلاعات ورود وارد نشده!"); return
        }

        if (isDiscovering) {
            log("⚠️ اکتشاف در حال اجرا — لطفاً صبر کنید."); return
        }

        // Reload schedule to pick up any recent edits
        loadSchedule()
        registerAlarms()

        if (schedule.isEmpty()) {
            log("⚠️ برنامه کلاسی خالی است! ابتدا کلاس‌ها را اکتشاف کنید.")
            return
        }

        // If already in a class, just inform
        if (flowState == FlowState.IN_CLASS && activeSpec != null) {
            log("ℹ️ در حال حاضر در کلاس '${activeSpec!!.name}' هستید.")
            return
        }

        log("🔍 بررسی برنامه کلاسی...")

        val currentClass = findCurrentlyActiveClass()

        if (currentClass != null) {
            log("✅ کلاس فعال: '${currentClass.name}' (${currentClass.startTime}–${currentClass.endTime})")
            if (flowState != FlowState.IDLE) {
                log("🔄 ریست وضعیت قبلی...")
                forceResetAndJoin(currentClass)
            } else {
                startJoinFlow(currentClass)
            }
        } else {
            val nextClass = findNextClassToday()
            if (nextClass != null) {
                log("⏳ کلاس بعدی: '${nextClass.name}' ساعت ${nextClass.startTime}")
                updateNotification("⏳ کلاس بعدی: ${nextClass.name} ساعت ${nextClass.startTime}")
                mainHandler.postDelayed({ updateNotification("Watching your schedule...") }, 8_000L)
            } else {
                log("💤 امروز کلاسی ندارید.")
                updateNotification("💤 امروز کلاسی نیست")
                mainHandler.postDelayed({ updateNotification("Watching your schedule...") }, 5_000L)
            }
        }
    }

    // =========================================================================
    //  Join flow
    // =========================================================================

    private fun startJoinFlow(spec: ClassSpec) {
        val prefs = getSharedPreferences("LMS_PREFS", Context.MODE_PRIVATE)
        if (prefs.getString("USERNAME", "").isNullOrEmpty()) {
            log("⚠️ No credentials for '${spec.name}'!"); return
        }

        val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Tehran"))
        val now = "%02d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
        if (now >= spec.endTime) {
            log("⚠️ Class '${spec.name}' already ended (now=$now, end=${spec.endTime}). Skipping.")
            findCurrentlyActiveClass()?.let {
                log("➡️ Found active class: '${it.name}' — joining instead!")
                mainHandler.postDelayed({ startJoinFlow(it) }, 1_000L)
            }
            return
        }

        activeSpec = spec
        refreshCount = 0
        casInjectedForUrl = null
        isDiscovering = false
        cancelPendingReload()
        cancelClassEndTimer()

        acquireWakeLock()
        updateNotification("Joining: ${spec.name}")

        log("🚀 Joining '${spec.name}' [${spec.platform}] (${spec.startTime}–${spec.endTime})")
        flowState = FlowState.NAVIGATING_TO_HOME
        webView.loadUrl(
            if (spec.platform == "NIMA") "https://lms.aut.ac.ir/"
            else "https://lmshome.aut.ac.ir/"
        )
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
        if (prefs.getString("USERNAME", "").isNullOrEmpty()) {
            log("⚠️ No credentials!"); return
        }

        if (flowState == FlowState.IN_CLASS) {
            cancelClassEndTimer(); cancelPendingReload()
        }

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
        log("📋 Scraping course links...")
        val js = """
            (function() {
                var n=0,MAX=20;
                var iv=setInterval(function(){
                    n++;var links=document.querySelectorAll('a.course-link');
                    if(links.length>0){clearInterval(iv);var res=[];
                    links.forEach(function(a){
                        var name=a.textContent.replace(/\s+/g,' ').trim()
                            .replace(/\s*-\s*گروه\s*\(.*\)/,'').trim();
                        res.push({name:name,url:a.href});
                    });
                    window._discoveredLinks=JSON.stringify(res);}
                    if(n>=MAX){clearInterval(iv);window._discoveredLinks='[]';}
                },500);
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
        mainHandler.postDelayed({
            if (!isDiscovering) return@postDelayed
            webView.evaluateJavascript("window._discoveredLinks||'[]'") { raw ->
                try {
                    val arr = JSONArray(
                        raw?.trim('"')?.replace("\\\"", "\"")?.replace("\\\\", "\\") ?: "[]"
                    )
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        discoveredCourseLinks.add(Pair(o.getString("name"), o.getString("url")))
                    }
                    log("✅ Found ${discoveredCourseLinks.size} course link(s).")
                    if (discoveredCourseLinks.isEmpty()) finishDiscovery(false)
                    else visitNextCourseForDiscovery()
                } catch (e: Exception) {
                    log("❌ Parse: ${e.message}"); finishDiscovery(false)
                }
            }
        }, 12_000L)
    }

    private fun visitNextCourseForDiscovery() {
        if (!isDiscovering) return
        if (discoveryIndex >= discoveredCourseLinks.size) { finishDiscovery(true); return }
        val (name, url) = discoveredCourseLinks[discoveryIndex]
        log("📖 [${discoveryIndex + 1}/${discoveredCourseLinks.size}] $name")
        updateNotification("Scanning ${discoveryIndex + 1} of ${discoveredCourseLinks.size}…")
        mainHandler.post {
            discoveryProgressListener?.invoke(discoveryIndex + 1, discoveredCourseLinks.size, name)
        }
        flowState = FlowState.DISCOVERY_SCRAPING_COURSE
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
                val txt = raw?.trim('"')
                    ?.replace("\\\"", "\"")
                    ?.replace("\\\\", "\\")
                    ?.trim() ?: ""
                webView.evaluateJavascript("window._courseSchedule='';", null)
                if (txt.isNotEmpty()) {
                    val sessions = ClassDiscoveryManager.parseScheduleText(txt)
                    log("   📅 $txt → ${sessions.size} session(s)")
                    val grouped = mutableMapOf<Pair<String, String>, MutableList<String>>()
                    sessions.forEach { (day, s, e) ->
                        grouped.getOrPut(Pair(s, e)) { mutableListOf() }.add(day)
                    }
                    grouped.forEach { (tk, days) ->
                        discoveredClasses.add(
                            ClassDiscoveryManager.DiscoveredClass(name, "", days, tk.first, tk.second)
                        )
                    }
                } else {
                    log("   ⚠️ No schedule for '$name'")
                }
                discoveryIndex++
                mainHandler.postDelayed({ visitNextCourseForDiscovery() }, 1_500L)
            }
        }, 10_000L)
    }

    private fun finishDiscovery(success: Boolean) {
        isDiscovering = false; flowState = FlowState.IDLE; releaseWakeLock()
        if (success && discoveredClasses.isNotEmpty()) {
            val em = ClassDiscoveryManager.loadDiscoveredClasses(this)
                .associateBy { "${it.name}|${it.start}|${it.end}" }
            discoveredClasses.forEach { c ->
                em["${c.name}|${c.start}|${c.end}"]?.let {
                    c.platform = it.platform; c.enabled = it.enabled
                }
            }
            ClassDiscoveryManager.saveDiscoveredClasses(this, discoveredClasses)
            ClassDiscoveryManager.generateScheduleJson(this, discoveredClasses)
            log("✅ Discovery: ${discoveredClasses.size} session(s) saved.")
            loadSchedule(); registerAlarms()
            updateNotification(getString(R.string.notif_discovery_done, discoveredClasses.size))
        } else {
            log("❌ No classes found.")
            updateNotification("Watching your schedule...")
        }
        mainHandler.post { discoveryCompleteListener?.invoke(discoveredClasses.size, success) }
        mainHandler.postDelayed({ updateNotification("Watching your schedule...") }, 5_000L)

        // After discovery, check if we should be in a class right now
        mainHandler.postDelayed({
            if (flowState == FlowState.IDLE) {
                findCurrentlyActiveClass()?.let {
                    log("🔍 Post-discovery: found active class '${it.name}' — joining!")
                    startJoinFlow(it)
                }
            }
        }, 3_000L)
    }

    // =========================================================================
    //  WebView setup
    // =========================================================================

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
                setOnTouchListener { v, event ->
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    false
                }                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                userAgentString = userAgentString
                    .replace("; wv", "")
                    .replace("Mobile", "Desktop")
            }
            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
                cm.setAcceptThirdPartyCookies(this, true)
            webViewClient = LmsWebViewClient()
            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(req: PermissionRequest) {
                    req.grant(req.resources)
                }
                override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                    if (msg.message().startsWith("[AUT]"))
                        log("   JS: ${msg.message().removePrefix("[AUT] ")}")
                    return true
                }
            }
        }
        backgroundLayoutParams = WindowManager.LayoutParams(
            1, 1,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSPARENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
    }

    // =========================================================================
    //  WebViewClient
    // =========================================================================

    private inner class LmsWebViewClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(
            view: WebView, req: WebResourceRequest
        ): Boolean {
            log("   ↳ ${req.url}"); return false
        }

        override fun onPageFinished(view: WebView, url: String) {
            log("📄 Loaded: $url [state=$flowState]")
            handlePageLoaded(url)
        }

        override fun onReceivedSslError(
            view: WebView, handler: SslErrorHandler, error: SslError
        ) {
            log("⚠️ SSL: ${error.primaryError}"); handler.proceed()
        }

        override fun onReceivedError(
            view: WebView, req: WebResourceRequest, err: WebResourceError
        ) {
            if (req.isForMainFrame) log("❌ Error: ${err.description}")
        }
    }

    // =========================================================================
    //  URL helpers
    // =========================================================================

    private fun isCasUrl(u: String) =
        u.contains("cas.aut.ac.ir") || u.contains("/cas/login") ||
                u.contains("account.aut.ac.ir") || u.contains("sso.aut.ac.ir")

    private fun isLmsDashboard(u: String) =
        u.contains("lmshome.aut.ac.ir") && u.contains("panel/home")

    private fun isLmsCoursePage(u: String) =
        u.contains("lmshome.aut.ac.ir") &&
                (u.contains("panel/myLesson") || u.contains("course/view.php"))

    private fun isNimaDashboard(u: String) =
        u.contains("lms.aut.ac.ir") && u.contains("users-panel")

    private fun isBbbPage(u: String) =
        u.contains("html5client") || u.contains("bigbluebutton")

    private fun isLoginPage(u: String) = u.contains("login/index.php")

    private fun isBlankPage(u: String) = u == "about:blank"

    // =========================================================================
    //  Page load handler
    // =========================================================================

    private fun handlePageLoaded(url: String) {
        // When IN_CLASS, only react to session expiry
        if (flowState == FlowState.IN_CLASS) {
            if (isLoginPage(url)) {
                log("⚠️ Session expired during class!")
                flowState = FlowState.WAITING_SSO_BUTTON
                clickSsoButton()
            }
            return
        }

        // Ignore transition pages
        if (isBlankPage(url)) return

        when {
            isLoginPage(url) -> {
                flowState = FlowState.WAITING_SSO_BUTTON
                log("🔐 Login page — clicking SSO...")
                clickSsoButton()
            }
            isCasUrl(url) -> {
                if (casInjectedForUrl == url) {
                    log("⚠️ CAS reloaded — wrong creds?"); return
                }
                flowState = FlowState.ON_CAS_PAGE
                casInjectedForUrl = url
                log("🔑 CAS — injecting credentials...")
                injectCasCredentials()
                mainHandler.postDelayed({
                    if (isCasUrl(webView.url ?: "")) {
                        log("❌ Still on CAS after 25s!")
                        webView.evaluateJavascript(
                            "document.body.innerText.substring(0,300)"
                        ) { log("   Page: ${it?.take(200)}") }
                    }
                }, 25_000L)
            }
            isLmsDashboard(url) -> {
                if (isDiscovering) {
                    flowState = FlowState.DISCOVERY_DASHBOARD
                    scrapeCourseLinksfromDashboard()
                } else {
                    flowState = FlowState.LMS_DASHBOARD
                    log("✅ LMS Dashboard")
                    if (activeSpec?.platform == "LMS") navigateToLmsCourse()
                    else if (activeSpec == null) log("ℹ️ Test OK.")
                }
            }
            isNimaDashboard(url) -> {
                if (isDiscovering) {
                    flowState = FlowState.DISCOVERY_DASHBOARD
                    scrapeCourseLinksfromDashboard()
                } else {
                    flowState = FlowState.NIMA_DASHBOARD
                    log("✅ NIMA Dashboard")
                    if (activeSpec?.platform == "NIMA") scanNimaOngoingMeetings()
                }
            }
            isLmsCoursePage(url) -> {
                if (isDiscovering) {
                    flowState = FlowState.DISCOVERY_SCRAPING_COURSE
                    scrapeScheduleFromCoursePage()
                } else {
                    flowState = FlowState.LMS_COURSE_PAGE
                    log("📖 Course page — scanning...")
                    scanLmsJoinButton()
                }
            }
            isBbbPage(url) -> {
                flowState = FlowState.LMS_BBB_JOINING
                cancelPendingReload()
                log("🎧 BBB room!")
                joinBbbListenOnly()
            }
        }
    }

    // =========================================================================
    //  SSO
    // =========================================================================

    private fun clickSsoButton() {
        val js = """
            (function(){var MAX=30,n=0;var iv=setInterval(function(){n++;
            // Priority list — longest/most-specific Persian strings FIRST
            var candidates=[
                '\u0648\u0631\u0648\u062F \u0628\u0627 \u0633\u0627\u0645\u0627\u0646\u0647 \u06CC\u06A9\u067E\u0627\u0631\u0686\u0647',
                '\u06CC\u06A9\u067E\u0627\u0631\u0686\u0647',
                'CAS'
            ];
            var f=null;
            var el=document.querySelectorAll('a,button,input[type="submit"]');
            for(var c=0;c<candidates.length&&!f;c++){
                for(var i=0;i<el.length;i++){
                    var t=(el[i].textContent||el[i].value||'').replace(/\s+/g,' ').trim();
                    if(t===candidates[c]||(c>0&&t.indexOf(candidates[c])!==-1)){
                        f=el[i];break;
                    }
                }
            }
            if(!f)f=document.querySelector('.login-identityprovider-btn')
                    ||document.querySelector('a[href*="cas"]');
            if(f){clearInterval(iv);
                console.log('[AUT] SSO found: '+(f.textContent||'').trim());
                f.click();
            } else if(n>=MAX){
                clearInterval(iv);
                var lnks=[];
                document.querySelectorAll('a,button').forEach(function(a){
                    var t=(a.textContent||'').trim();
                    if(t.length>0&&t.length<80)lnks.push(t);
                });
                console.log('[AUT] SSO NOT found. Links: '+lnks.slice(0,10).join(' | '));
            }},500);})();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    // =========================================================================
    //  CAS credentials
    // =========================================================================

    private fun injectCasCredentials() {
        val prefs = getSharedPreferences("LMS_PREFS", Context.MODE_PRIVATE)
        val user = (prefs.getString("USERNAME", "") ?: "")
            .replace("'", "\\'").replace("\\", "\\\\")
        val pass = (prefs.getString("PASSWORD", "") ?: "")
            .replace("'", "\\'").replace("\\", "\\\\")
        if (user.isEmpty() || pass.isEmpty()) { log("⚠️ No credentials."); return }

        val js = """
            (function(){var MAX=40,n=0;var iv=setInterval(function(){n++;
            var uf=document.getElementById('username')
                ||document.querySelector('input[name="username"]')
                ||document.querySelector('input[type="text"]');
            var pf=document.getElementById('password')
                ||document.querySelector('input[name="password"]')
                ||document.querySelector('input[type="password"]');
            if(!uf||!pf){
                if(n>=MAX){clearInterval(iv);console.log('[AUT] CAS fields NOT found');}
                return;
            }
            clearInterval(iv);

            function fill(el,v){
                el.focus();el.click();
                try{
                    Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value')
                        .set.call(el,v);
                }catch(e){el.value=v;}
                ['focus','input','change','blur'].forEach(function(t){
                    el.dispatchEvent(new Event(t,{bubbles:true}));
                });
            }

            fill(uf,'$user');
            fill(pf,'$pass');
            console.log('[AUT] Fields filled');

            setTimeout(function(){
                // Primary: exact element from the page HTML
                var sb=document.querySelector('input[name="submit"][type="submit"]');

                // Fallback 1: any input[type="submit"] with value ورود
                if(!sb){
                    var inputs=document.querySelectorAll('input[type="submit"]');
                    for(var i=0;i<inputs.length;i++){
                        if((inputs[i].value||'').trim()==='\u0648\u0631\u0648\u062F'){
                            sb=inputs[i];break;
                        }
                    }
                }

                // Fallback 2: any button/input whose text contains ورود
                if(!sb){
                    var all=document.querySelectorAll('button,input[type="submit"]');
                    for(var i=0;i<all.length;i++){
                        var t=(all[i].textContent||all[i].value||'').trim();
                        if(t.indexOf('\u0648\u0631\u0648\u062F')!==-1){sb=all[i];break;}
                    }
                }

                // Fallback 3: generic submit selectors
                if(!sb){
                    sb=document.querySelector('button[type="submit"]')
                      ||document.querySelector('.btn-submit')
                      ||document.querySelector('.btn-primary');
                }

                if(sb){
                    sb.focus();
                    sb.click();
                    console.log('[AUT] ورود clicked (tag='+sb.tagName
                        +' value='+(sb.value||sb.textContent||'')+')');
                    // Also fire submit event on the form as backup
                    setTimeout(function(){
                        var form=sb.closest('form');
                        if(form){
                            form.dispatchEvent(
                                new Event('submit',{bubbles:true,cancelable:true})
                            );
                            console.log('[AUT] Form submit event dispatched');
                        }
                    },300);
                } else {
                    // Last resort: submit form directly
                    var form=pf.closest('form');
                    if(form){form.submit();console.log('[AUT] form.submit() called');}
                    else{console.log('[AUT] No submit button or form found!');}
                }
            },500);
            },500);})();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    // =========================================================================
    //  LMS: Navigate to course
    // =========================================================================

    private fun navigateToLmsCourse() {
        val spec = activeSpec ?: return
        val safe = spec.name.replace("'", "\\'")
        log("🔍 Looking for: '${spec.name}'...")
        val js = """
            (function(){var MAX=30,n=0;var iv=setInterval(function(){n++;
            var links=document.querySelectorAll('a.course-link'),t=null;
            for(var i=0;i<links.length;i++){
                if(links[i].textContent.includes('$safe')){t=links[i];break;}
            }
            if(t){clearInterval(iv);
                console.log('[AUT] Course clicked: '+t.href);t.click();
            } else if(n>=MAX){
                clearInterval(iv);
                console.log('[AUT] Course NOT found: $safe');
            }},500);})();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    // =========================================================================
    //  LMS: Scan for join button
    // =========================================================================

// =========================================================================
    //  LMS: Scan for join button
    // =========================================================================

    private fun scanLmsJoinButton() {
        val spec = activeSpec ?: return
        val st = spec.startTime
        // Also try without leading zero: "09:00" → "9:00"
        val stAlt = if (st.startsWith("0")) st.substring(1) else st
        log("🔍 LMS: Scanning for join button (time: $st / $stAlt)...")

        val js = """
        (function(){
            var st='$st', stAlt='$stAlt', MAX=30, n=0;
            window._autNeedReload = false;

            var iv = setInterval(function(){
                n++;

                var rows = document.querySelectorAll('tr');
                for (var r = 0; r < rows.length; r++) {
                    var row = rows[r];
                    
                    // CRITICAL FIX: Use textContent instead of innerText because DataTables 
                    // hides the time and button columns on mobile screens (display:none).
                    var text = (row.textContent || '').replace(/\s+/g,' ');
                    
                    // Match the time (e.g., "15:00:00")
                    if (text.indexOf(st) === -1 && text.indexOf(stAlt) === -1) continue;

                    // If it contains "هنوز برگزار نشده" (Not started yet), we need to wait and reload
                    if (text.indexOf('\u0647\u0646\u0648\u0632 \u0628\u0631\u06AF\u0632\u0627\u0631 \u0646\u0634\u062F\u0647') !== -1) {
                        clearInterval(iv);
                        window._autNeedReload = true;
                        return;
                    }

                    // Strategy 1: Find the form directly (even if hidden in a collapsed td) and submit it
                    var form = row.querySelector('form[action*="join"]');
                    if (form) {
                        clearInterval(iv);
                        console.log('[AUT] Submitting hidden join form directly from row');
                        form.submit();
                        return;
                    }

                    // Strategy 2: If the user/script already expanded it, it might be in the child row
                    var nextRow = row.nextElementSibling;
                    if (nextRow && nextRow.classList.contains('child')) {
                        form = nextRow.querySelector('form[action*="join"]');
                        if (form) {
                            clearInterval(iv);
                            console.log('[AUT] Submitting join form from expanded child row');
                            form.submit();
                            return;
                        }
                    }

                    // Strategy 3: Try to find any generic button with "ورود"
                    var btns = row.querySelectorAll('button');
                    for (var i = 0; i < btns.length; i++) {
                        var btnText = btns[i].textContent || '';
                        if (btnText.indexOf('\u0648\u0631\u0648\u062F') !== -1 && !btns[i].disabled) {
                            clearInterval(iv);
                            console.log('[AUT] Clicking generic join button');
                            btns[i].click();
                            return;
                        }
                    }

                    // Strategy 4: If form isn't in the DOM yet, click the '+' icon (first column) to expand the row
                    var expandBtn = row.querySelector('td.sorting_1, td[tabindex="0"], .dtr-control');
                    if (expandBtn && n % 3 === 0) { // Click every 3 seconds to avoid spamming
                        console.log('[AUT] Expanding collapsed row...');
                        expandBtn.click();
                        // Do NOT clear interval here; let it scan again next second to find the newly generated form!
                    }
                }

                if (n >= MAX) {
                    clearInterval(iv);
                    window._autNeedReload = true;
                }
            }, 1000);
        })();
        """.trimIndent()

        webView.evaluateJavascript(js, null)

        // Give the script 32 seconds to either find it, click it, or declare a reload needed
        mainHandler.postDelayed({
            webView.evaluateJavascript("String(window._autNeedReload)") { r ->
                if (r?.contains("true") == true) {
                    webView.evaluateJavascript("window._autNeedReload=false;", null)
                    log("🕐 LMS: Class not started — scheduling reload in 60s")
                    scheduleReload()
                }
            }
        }, 32_000L)
    }
    // =========================================================================
    //  NIMA: Scan ongoing meetings
    // =========================================================================

// =========================================================================
    //  NIMA: Scan ongoing meetings
    // =========================================================================

    private fun scanNimaOngoingMeetings() {
        val spec = activeSpec ?: return
        val st = spec.startTime                          // e.g. "13:00"
        val stAlt = if (st.startsWith("0")) st.substring(1) else st  // "9:00" alt
        // Take a flexible name fragment to maximize matching success
        val nameFrag = spec.name.take(8).replace("'", "\\'")

        log("🔍 NIMA: scanning for '$st' / name~'$nameFrag'...")

        val js = """
            (function(){
                var st='$st', stAlt='$stAlt', nameFrag='$nameFrag';
                var MAX=60, n=0;
                window._autNimaResult = 'waiting';

                var iv = setInterval(function(){
                    n++;

                    // ── Search globally for any table rows to prevent layout mismatch ──
                    var rows = document.querySelectorAll('tr, .p-datatable-tbody tr');
                    var matchedRow = null;

                    for (var i = 0; i < rows.length; i++) {
                        var row = rows[i];
                        var rowText = (row.innerText || '').replace(/\s+/g, ' ');

                        var hasTime = rowText.indexOf(st) !== -1 || rowText.indexOf(stAlt) !== -1;
                        var hasName = nameFrag.length === 0 || rowText.indexOf(nameFrag) !== -1;

                        if (hasTime && hasName) {
                            matchedRow = row;
                            break;
                        }
                    }

                    if (matchedRow) {
                        // Gather potential clickables inside the matched row
                        var clickables = matchedRow.querySelectorAll('button, a, [role="button"]');
                        var targetBtn = null;

                        // Priority 1: Find element containing "ورود" (Persian for Enter/Join)
                        for (var j = 0; j < clickables.length; j++) {
                            var btnText = (clickables[j].textContent || clickables[j].value || '').trim();
                            if (btnText.indexOf('\u0648\u0631\u0648\u062F') !== -1) {
                                targetBtn = clickables[j];
                                break;
                            }
                        }

                        // Priority 2: Look for button[name="join"]
                        if (!targetBtn) {
                            targetBtn = matchedRow.querySelector('button[name="join"]');
                        }

                        // Priority 3: Fallback to the first enabled and visible button/link in the row
                        if (!targetBtn && clickables.length > 0) {
                            for (var k = 0; k < clickables.length; k++) {
                                if (!clickables[k].disabled && clickables[k].offsetWidth > 0) {
                                    targetBtn = clickables[k];
                                    break;
                                }
                            }
                        }

                        if (targetBtn) {
                            clearInterval(iv);
                            console.log('[AUT] NIMA join button found and clicked!');
                            targetBtn.focus();
                            targetBtn.click();
                            
                            // Native event dispatch fallback
                            try {
                                var clickEvent = new MouseEvent('click', { bubbles: true, cancelable: true, view: window });
                                targetBtn.dispatchEvent(clickEvent);
                            } catch(e) {}
                            
                            window._autNimaResult = 'clicked';
                            return;
                        }
                    }

                    if (n >= MAX) {
                        clearInterval(iv);
                        window._autNimaResult = 'notfound';
                        console.log('[AUT] NIMA: meeting row or action button not resolved within ' + MAX + ' seconds.');
                    }
                }, 1000);
            })();
        """.trimIndent()

        webView.evaluateJavascript(js, null)

        // Evaluate the outcome after the 60 seconds interval has completed
        mainHandler.postDelayed({
            webView.evaluateJavascript("window._autNimaResult||'waiting'") { raw ->
                val result = raw?.trim('"') ?: "waiting"
                webView.evaluateJavascript("window._autNimaResult='waiting';", null)

                when {
                    result == "clicked" -> {
                        log("✅ NIMA: join action executed.")
                    }
                    result == "notfound" || result == "waiting" -> {
                        log("⏳ NIMA: meeting not active or not rendered yet. Retrying in 60s...")
                        scheduleNimaRetry()
                    }
                }
            }
        }, 62_000L)
    }

    private fun scheduleNimaRetry() {
        val spec = activeSpec ?: return
        if (refreshCount >= maxRefreshes) {
            log("❌ NIMA: max retries reached for '${spec.name}'")
            return
        }
        refreshCount++
        cancelPendingReload()

        log("🔄 NIMA: Scheduling retry #$refreshCount in 60s...")
        val r = Runnable {
            pendingReloadRunnable = null
            if (activeSpec == null || flowState == FlowState.IN_CLASS) return@Runnable
            log("🔄 NIMA: Reloading dashboard for retry #$refreshCount")
            
            flowState = FlowState.NIMA_DASHBOARD
            webView.loadUrl("https://lms.aut.ac.ir/users-panel/announcements-list")
        }
        pendingReloadRunnable = r
        mainHandler.postDelayed(r, 60_000L)
    }

    // =========================================================================
    //  BBB: Join listen-only + mark IN_CLASS + schedule end
    // =========================================================================

// =========================================================================
    //  BBB: Join listen-only + mark IN_CLASS + schedule end
    // =========================================================================

// =========================================================================
    //  BBB: Join listen-only + mark IN_CLASS + schedule end
    // =========================================================================

// =========================================================================
    //  BBB: Join listen-only + mark IN_CLASS + schedule end
    // =========================================================================

    private fun joinBbbListenOnly() {
        val js = """
            (function(){
                console.log('[AUT] BBB Audio Watcher initialized.');
                window._autBbbWaitingForModal = false;

                // Run every 2.5 seconds persistently to catch accidental or deliberate disconnects
                setInterval(function(){
                    
                    // 1. Check if the "Disconnected" icon exists on the screen
                    // If you are connected (Mic or Listen-Only), this icon is hidden, so we do nothing.
                    var noAudioIcon = document.querySelector('.icon-bbb-no_audio');
                    
                    if (noAudioIcon) {
                        // WE ARE DISCONNECTED.
                        
                        // 2. Check if the Listen Only modal is CURRENTLY open
                        var listenBtn = null;
                        var listenIcon = document.querySelector('.icon-bbb-listen');
                        
                        if (listenIcon) {
                            var parentBtn = listenIcon.closest('button');
                            if (parentBtn && (parentBtn.closest('.ReactModal__Overlay') || parentBtn.getAttribute('aria-label') === 'تنها شنونده')) {
                                listenBtn = parentBtn;
                            }
                        }
                        
                        if (!listenBtn) listenBtn = document.querySelector('button[aria-label="تنها شنونده"]');
                        if (!listenBtn) listenBtn = document.querySelector('button[aria-label="Listen only"]');
                        if (!listenBtn) listenBtn = document.querySelector('button[aria-label="\u0641\u0642\u0637 \u0634\u0646\u06CC\u062F\u0646"]');
                        
                        if (listenBtn && !listenBtn.disabled) {
                            console.log('[AUT] Listen-only modal found. Clicking...');
                            listenBtn.click();
                            window._autBbbWaitingForModal = false; // Reset lock
                            return;
                        }

                        // 3. If modal is NOT open, find the "Join Audio" button to open it
                        var joinAudioBtn = noAudioIcon.closest('button') || noAudioIcon.closest('span');
                        
                        if (!joinAudioBtn) {
                            var spans = document.querySelectorAll('span');
                            for(var j=0; j<spans.length; j++) {
                                var t = spans[j].textContent || '';
                                if (t.indexOf('\u067E\u06CC\u0648\u0633\u062A\u0646 \u0628\u0647 \u0635\u062F\u0627') !== -1 || t.indexOf('Join audio') !== -1) {
                                    joinAudioBtn = spans[j].closest('button') || spans[j].parentElement;
                                    break;
                                }
                            }
                        }

                        // 4. Click the Join Audio button (with deadlock prevention)
                        if (joinAudioBtn && !joinAudioBtn.disabled) {
                            if (!window._autBbbWaitingForModal) {
                                console.log('[AUT] Audio disconnected! Clicking "Join Audio" to open modal...');
                                joinAudioBtn.click();
                                window._autBbbWaitingForModal = true;
                                
                                // Wait 5 seconds for modal to appear. If it fails, unlock so we can click again.
                                setTimeout(function() {
                                    window._autBbbWaitingForModal = false;
                                }, 5000);
                            }
                        }
                    } else {
                        // Audio is connected! Reset the lock flag just in case.
                        window._autBbbWaitingForModal = false;
                    }

                }, 2500);
            })();
        """.trimIndent()

        webView.evaluateJavascript(js) {
            log("🎙️ BBB Audio Watcher activated (monitoring for disconnects).")
            flowState = FlowState.IN_CLASS

            activeSpec?.let { spec ->
                updateNotification("📚 In class: ${spec.name} (until ${spec.endTime})")
                scheduleClassEndTimer(spec)
                log("✅ IN_CLASS: '${spec.name}' until ${spec.endTime}")
            }
        }
    }
    // =========================================================================
    //  Reload / retry
    // =========================================================================

    private fun scheduleReload(delayMs: Long = 60_000L) {
        if (refreshCount >= maxRefreshes) { log("❌ Max retries."); return }
        refreshCount++
        cancelPendingReload()
        log("🔄 Refresh #$refreshCount in ${delayMs / 1000}s...")
        val r = Runnable {
            pendingReloadRunnable = null
            log("🔄 Reloading...")
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
    //  Attach / Detach
    // =========================================================================

    fun attachToActivity(container: ViewGroup) {
        if (isWebViewInBackground) {
            try { windowManager.removeViewImmediate(webView) } catch (_: Exception) {}
            isWebViewInBackground = false
        } else {
            detachWebViewSafely()
        }
        webView.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
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
        if (parent is ViewGroup) {
            try { parent.removeView(webView) } catch (_: Exception) {}
        } else {
            try {
                windowManager.removeViewImmediate(webView)
                isWebViewInBackground = false
            } catch (_: Exception) {}
        }
    }

    // =========================================================================
    //  WakeLock
    // =========================================================================

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock == null)
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LmsAutoJoiner::Active")
        if (wakeLock?.isHeld == false) wakeLock?.acquire(4 * 60 * 60 * 1000L)
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) wakeLock?.release()
    }

    // =========================================================================
    //  Notification
    // =========================================================================

    private fun startForegroundNotification() {
        val cid = "AutoJoinChannel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(
                    NotificationChannel(cid, "LMS Auto Joiner", NotificationManager.IMPORTANCE_LOW)
                )
        startForeground(1, buildNotification("Watching your schedule..."))
    }

    private fun updateNotification(text: String) =
        getSystemService(NotificationManager::class.java).notify(1, buildNotification(text))

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
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
        val days = (0 until o.getJSONArray("days").length()).map {
            o.getJSONArray("days").getString(it)
        }
        return ClassSpec(
            o.getString("name"),
            o.getString("startTime"),
            o.getString("endTime"),
            days,
            o.optString("platform", "LMS").uppercase()
        )
    }
    fun resetWebSession() {
        log("🧹 Resetting web session...")

        try {
            // Stop any current loading
            webView.stopLoading()

            // Clear cookies
            val cookieManager = CookieManager.getInstance()
            cookieManager.removeAllCookies(null)
            cookieManager.flush()

            // Clear WebView storage/session
            webView.clearCache(true)
            webView.clearHistory()
            webView.clearFormData()

            WebStorage.getInstance().deleteAllData()

            // Reset service state
            flowState = FlowState.IDLE
            activeSpec = null
            refreshCount = 0
            casInjectedForUrl = null
            isDiscovering = false

            cancelPendingReload()
            cancelClassEndTimer()

            // Blank page so next login starts fresh
            webView.loadUrl("about:blank")

            log("✅ Session reset complete.")
        } catch (e: Exception) {
            log("⚠️ Session reset failed: ${e.message}")
        }
    }
}