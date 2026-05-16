// AutoJoinService.kt
package com.example.myapplication

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.net.http.SslError
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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

    // ── State machine ─────────────────────────────────────────────────────────
    private enum class FlowState {
        IDLE,
        NAVIGATING_TO_HOME,    
        WAITING_SSO_BUTTON,    
        WAITING_CAS_PAGE,      
        ON_CAS_PAGE,           
        WAITING_POST_LOGIN,    
        LMS_DASHBOARD,         
        LMS_COURSE_PAGE,       
        LMS_BBB_JOINING,       
        NIMA_DASHBOARD,        
        NIMA_BBB_JOINING,      
    }

    private var flowState = FlowState.IDLE
    private var activeSpec: ClassSpec? = null
    private var refreshCount = 0
    private val maxRefreshes = 30   

    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingReloadRunnable: Runnable? = null

    private var casInjectedForUrl: String? = null
    
    // Pre-loaded credentials
    private var savedUsername = ""
    private var savedPassword = ""

    // ── Schedule ──────────────────────────────────────────────────────────────
    private val schedule = mutableListOf<ClassSpec>()
    private val alarmTimers = mutableListOf<Timer>()

    // ── Callbacks & Logging ───────────────────────────────────────────────────
    private val logHistory = mutableListOf<String>()
    var logUpdateListener: ((String) -> Unit)? = null
    var pageLoadProgressListener: ((Int) -> Unit)? = null

    fun log(msg: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val line = "[$time] $msg"
        logHistory.add(line)
        if (logHistory.size > 200) logHistory.removeAt(0)
        mainHandler.post { logUpdateListener?.invoke(logHistory.joinToString("\n")) }
    }

    fun getFullLogs(): String =
        if (logHistory.isEmpty()) "System idle..." else logHistory.joinToString("\n")
        
    fun isIdle() = flowState == FlowState.IDLE
    fun triggerTestLogin() = testLogin()

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
        log("✅ Service started. WebView ready.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val specJson = intent?.getStringExtra("CLASS_SPEC_JSON")
        if (specJson != null) {
            val spec = parseSpecFromJson(specJson)
            log("⏰ Alarm: ${spec.name} [${spec.platform}]")
            startJoinFlow(spec)
        }
        val testTrigger = intent?.getStringExtra("COURSE_URL")
        if (testTrigger == "TEST") {
            log("🧪 Test mode: checking credentials and running login test...")
            testLogin()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onUnbind(intent: Intent?): Boolean {
        logUpdateListener = null
        pageLoadProgressListener = null
        attachToBackground()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        alarmTimers.forEach { it.cancel() }
        cancelPendingReload()
        if (isWebViewInBackground) {
            try { windowManager.removeView(webView) } catch (_: Exception) {}
        }
        webView.destroy()
    }
    
    // =========================================================================
    //  Credentials Pre-loading
    // =========================================================================
    
    private fun loadCredentials(): Boolean {
        val prefs = getSharedPreferences("LMS_PREFS", Context.MODE_PRIVATE)
        savedUsername = prefs.getString("USERNAME", "") ?: ""
        savedPassword = prefs.getString("PASSWORD", "") ?: ""
        return savedUsername.isNotEmpty() && savedPassword.isNotEmpty()
    }

    // =========================================================================
    //  Schedule
    // =========================================================================

    private fun loadSchedule() {
        try {
            val json = assets.open("schedule.json").bufferedReader().use { it.readText() }
            val arr  = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj     = arr.getJSONObject(i)
                val daysArr = obj.getJSONArray("days")
                val days    = (0 until daysArr.length()).map { daysArr.getString(it) }
                schedule.add(
                    ClassSpec(
                        name      = obj.getString("name"),
                        startTime = obj.getString("start"),
                        endTime   = obj.getString("end"),
                        days      = days,
                        platform  = obj.optString("platform", "LMS").uppercase()
                    )
                )
            }
            log("📅 Loaded ${schedule.size} class(es).")
        } catch (e: Exception) {
            log("⚠️ schedule.json error: ${e.message}")
        }
    }

    private fun registerAlarms() {
        alarmTimers.forEach { it.cancel() }
        alarmTimers.clear()
        val dayMap = mapOf(
            Calendar.SUNDAY to "sun", Calendar.MONDAY to "mon",
            Calendar.TUESDAY to "tue", Calendar.WEDNESDAY to "wed",
            Calendar.THURSDAY to "thu", Calendar.FRIDAY to "fri",
            Calendar.SATURDAY to "sat"
        )
        schedule.forEach { spec ->
            val timer = Timer(true)
            alarmTimers.add(timer)
            val task = object : TimerTask() {
                override fun run() {
                    val cal   = Calendar.getInstance(TimeZone.getTimeZone("Asia/Tehran"))
                    val today = dayMap[cal.get(Calendar.DAY_OF_WEEK)] ?: return
                    val hh    = cal.get(Calendar.HOUR_OF_DAY)
                    val mm    = cal.get(Calendar.MINUTE)
                    val ch    = spec.startTime.split(":")[0].toInt()
                    val cm    = spec.startTime.split(":")[1].toInt()
                    if (today in spec.days && hh == ch && mm == cm) {
                        mainHandler.post { startJoinFlow(spec) }
                    }
                }
            }
            val delay = (60 - Calendar.getInstance().get(Calendar.SECOND)) * 1000L
            timer.scheduleAtFixedRate(task, delay, 60_000L)
        }
        log("⏱️ Timer registered for ${schedule.size} class(es).")
    }

    private fun checkCatchUp() {
        val dayMap = mapOf(
            Calendar.SUNDAY to "sun", Calendar.MONDAY to "mon",
            Calendar.TUESDAY to "tue", Calendar.WEDNESDAY to "wed",
            Calendar.THURSDAY to "thu", Calendar.FRIDAY to "fri",
            Calendar.SATURDAY to "sat"
        )
        val cal   = Calendar.getInstance(TimeZone.getTimeZone("Asia/Tehran"))
        val today = dayMap[cal.get(Calendar.DAY_OF_WEEK)] ?: return
        val now   = "%02d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
        schedule.firstOrNull { today in it.days && now >= it.startTime && now < it.endTime }
            ?.let {
                log("⚠️ [CATCH-UP] Inside window for '${it.name}'.")
                mainHandler.postDelayed({ startJoinFlow(it) }, 2_000L)
            }
    }

    // =========================================================================
    //  Join flow
    // =========================================================================

    private fun startJoinFlow(spec: ClassSpec) {
        if (!loadCredentials()) {
            log("⚠️ Cannot join '${spec.name}': No credentials saved!")
            return
        }

        activeSpec       = spec
        refreshCount     = 0
        casInjectedForUrl = null
        cancelPendingReload()

        log("🚀 Joining '${spec.name}' [${spec.platform}]")
        flowState = FlowState.NAVIGATING_TO_HOME

        val homeUrl = if (spec.platform == "NIMA")
            "https://lms.aut.ac.ir/"
        else
            "https://lmshome.aut.ac.ir/"

        webView.loadUrl(homeUrl)
    }

    private fun testLogin() {
        if (!loadCredentials()) {
            log("⚠️ Test login failed: No credentials saved!")
            return
        }
        
        activeSpec        = null
        casInjectedForUrl = null
        flowState         = FlowState.NAVIGATING_TO_HOME
        webView.loadUrl("https://lmshome.aut.ac.ir/")
    }

    // =========================================================================
    //  WebView setup
    // =========================================================================

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView = WebView(this).apply {
            val currentWebView = this
            settings.apply {
                javaScriptEnabled               = true
                domStorageEnabled               = true
                databaseEnabled                 = true
                mediaPlaybackRequiresUserGesture = false
                setSupportZoom(true)
                builtInZoomControls             = true
                displayZoomControls             = false
                useWideViewPort                 = true
                loadWithOverviewMode            = true
                cacheMode                       = WebSettings.LOAD_DEFAULT
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                }
                userAgentString = userAgentString.replace("; wv", "").replace("Mobile", "Desktop")
            }

            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    setAcceptThirdPartyCookies(currentWebView, true)
                }
            }

            webViewClient = LmsWebViewClient()
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    super.onProgressChanged(view, newProgress)
                    pageLoadProgressListener?.invoke(newProgress)
                }
                
                override fun onPermissionRequest(request: PermissionRequest) {
                    request.grant(request.resources)
                    log("🎤 Granted WebRTC permissions.")
                }
                
                override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                    if (msg.message().startsWith("[AUT]")) {
                        log("   JS: ${msg.message().removePrefix("[AUT] ")}")
                    }
                    return true
                }
            }
        }

        backgroundLayoutParams = WindowManager.LayoutParams(
            1, 1,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSPARENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
    }

    // =========================================================================
    //  Custom WebViewClient
    // =========================================================================

    private inner class LmsWebViewClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            log("   ↳ Redirect: $url")
            if (isCasUrl(url)) {
                flowState = FlowState.WAITING_CAS_PAGE
            }
            return false
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            log("📡 Loading: $url")
        }

        override fun onPageFinished(view: WebView, url: String) {
            super.onPageFinished(view, url)
            log("📄 Loaded: $url  [state=$flowState]")
            handlePageLoaded(url)
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            handler.proceed()
        }
    }

    // =========================================================================
    //  URL detection helpers
    // =========================================================================

    private fun isCasUrl(url: String) = url.contains("cas.aut.ac.ir") || url.contains("/cas/login")
    private fun isLoginIndexPage(url: String) = url.contains("login/index.php")
    private fun isLmsDashboard(url: String) = url.contains("lmshome.aut.ac.ir") && url.contains("panel/home")
    private fun isNimaDashboard(url: String) = url.contains("lms.aut.ac.ir") && url.contains("users-panel")
    private fun isBbbPage(url: String) = url.contains("html5client") || url.contains("/bbb/") || url.contains("greenlight")
    private fun isCourseViewPage(url: String) = url.contains("course/view.php")

    // =========================================================================
    //  Main page-load handler
    // =========================================================================

    private fun handlePageLoaded(url: String) {
        when {
            isLoginIndexPage(url) -> {
                flowState = FlowState.WAITING_SSO_BUTTON
                log("🔐 Login page. Searching for SSO button...")
                clickSsoButton()
            }
            isCasUrl(url) -> {
                if (casInjectedForUrl == url) return
                flowState = FlowState.ON_CAS_PAGE
                casInjectedForUrl = url
                log("🔑 CAS page detected. Injecting credentials...")
                injectCasCredentials()
            }
            isLmsDashboard(url) -> {
                flowState = FlowState.LMS_DASHBOARD
                log("✅ LMS Dashboard reached!")
                if (activeSpec?.platform == "LMS") navigateToLmsCourse()
            }
            isNimaDashboard(url) -> {
                flowState = FlowState.NIMA_DASHBOARD
                log("✅ NIMA Dashboard reached!")
                if (activeSpec?.platform == "NIMA") scanNimaOngoingMeetings()
            }
            isCourseViewPage(url) -> {
                flowState = FlowState.LMS_COURSE_PAGE
                log("📖 Course page loaded. Scanning for join button...")
                scanLmsJoinButton()
            }
            isBbbPage(url) -> {
                flowState = FlowState.LMS_BBB_JOINING
                cancelPendingReload()
                log("🎧 BBB room loaded! Joining audio...")
                joinBbbListenOnly()
            }
        }
    }

    // =========================================================================
    //  Step 1 & 2: Moodle SSO & CAS
    // =========================================================================

    private fun clickSsoButton() {
        val js = """
            (function() {
                var iv = setInterval(function() {
                    var ssoBtn = document.querySelector('.login-identityprovider-btn');
                    if (!ssoBtn) {
                        ssoBtn = document.querySelector('a[href*="authCASattras"]');
                    }
                    
                    if (ssoBtn) {
                        clearInterval(iv);
                        console.log('[AUT] Exact SSO button found! Clicking...');
                        ssoBtn.click();
                        return;
                    }
                    
                    var links = document.querySelectorAll('a, button');
                    for (var i = 0; i < links.length; i++) {
                        var text = (links[i].textContent || '').trim();
                        if (text.includes('سامانه یکپارچه') && text.includes('ورود')) {
                            clearInterval(iv);
                            console.log('[AUT] SSO button found by strict text match! Clicking...');
                            links[i].click();
                            return;
                        }
                    }
                }, 500);
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun injectCasCredentials() {
        val safeUser = savedUsername.replace("'", "\\'")
        val safePass = savedPassword.replace("'", "\\'")

        val js = """
            (function() {
                var iv = setInterval(function() {
                    var userField = document.getElementById('username') || document.querySelector('input[name="username"]');
                    var passField = document.getElementById('password') || document.querySelector('input[name="password"]');
                    
                    if (userField && passField) {
                        clearInterval(iv);
                        userField.value = '$safeUser';
                        passField.value = '$safePass';
                        
                        console.log('[AUT] Filled credentials. Looking for submit button...');
                        setTimeout(function() {
                            var submitBtn = document.querySelector('input[name="submit"]') || 
                                            document.querySelector('.waves-button-input');
                            
                            var form = document.getElementById('fm1') || passField.closest('form');
                            
                            if (submitBtn) {
                                console.log('[AUT] Submit button found! Triggering click...');
                                submitBtn.click();
                            } 
                            
                            // Safe fallback: 500ms later, if we haven't navigated away, force form submit
                            setTimeout(function() {
                                if (form) {
                                    console.log('[AUT] Forcing form.submit() as fallback...');
                                    form.submit();
                                }
                            }, 500);
                            
                        }, 500);
                    }
                }, 500);
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    // =========================================================================
    //  Step 3a & 4: LMS Dashboard -> Course List -> Meeting Table
    // =========================================================================

    private fun navigateToLmsCourse() {
        val safeName = activeSpec!!.name.replace("'", "\\'")
        val js = """
            (function() {
                var iv = setInterval(function() {
                    var links = document.querySelectorAll('a.course-link, a[href*="course/view.php"]');
                    for (var i = 0; i < links.length; i++) {
                        if (links[i].textContent.includes('$safeName') || links[i].title.includes('$safeName')) {
                            clearInterval(iv);
                            console.log('[AUT] LMS Course found! Clicking...');
                            links[i].click();
                            return;
                        }
                    }
                }, 1000);
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun scanLmsJoinButton() {
        val startTime = activeSpec!!.startTime
        val js = """
            (function() {
                var iv = setInterval(function() {
                    var rows = document.querySelectorAll('tr');
                    var targetRow = null;
                    
                    for (var i = 0; i < rows.length; i++) {
                        if (rows[i].textContent.includes('$startTime')) {
                            targetRow = rows[i];
                            break;
                        }
                    }
                    
                    if (!targetRow) return;
                    
                    if (targetRow.textContent.includes('هنوز برگزار نشده')) {
                        clearInterval(iv);
                        console.log('[AUT] Class not started yet. Will retry...');
                        window._autNeedReload = true;
                        return;
                    }
                    
                    var btns = targetRow.querySelectorAll('button, a.btn');
                    for (var j = 0; j < btns.length; j++) {
                        var text = btns[j].textContent || '';
                        if (text.includes('ورود به جلسه') || text.includes('بیگبلوباتن') || text.includes('Join')) {
                            if (!btns[j].disabled) {
                                clearInterval(iv);
                                console.log('[AUT] BBB Join button clicked!');
                                btns[j].click();
                                return;
                            }
                        }
                    }
                }, 1000);
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)

        mainHandler.postDelayed({
            webView.evaluateJavascript("window._autNeedReload === true ? 'RELOAD' : 'OK'") { result ->
                if (result?.contains("RELOAD") == true) {
                    webView.evaluateJavascript("window._autNeedReload = false;", null)
                    scheduleReload(60_000L) // Refresh every 60s
                }
            }
        }, 15_000L)
    }

    // =========================================================================
    //  Step 3b: NIMA Dashboard -> Ongoing Meetings Component
    // =========================================================================

    private fun scanNimaOngoingMeetings() {
        val spec = activeSpec ?: return
        val expectedTimeRange = "${spec.startTime} - ${spec.endTime}"
        val mainKeyword = spec.name.split(" ").firstOrNull()?.replace("'", "\\'") ?: ""

        val js = """
            (function() {
                var iv = setInterval(function() {
                    var table = document.querySelector('app-users-panel-ongoing-meetings');
                    if (!table) return;
                    
                    var rows = table.querySelectorAll('tbody tr, .p-datatable-tbody tr');
                    for (var i = 0; i < rows.length; i++) {
                        var text = rows[i].innerText;
                        
                        if (text.includes('$expectedTimeRange') && text.includes('$mainKeyword')) {
                            var btn = rows[i].querySelector('button[name="join"], button');
                            if (btn && !btn.disabled && (btn.innerText.includes('ورود') || btn.name === 'join')) {
                                clearInterval(iv);
                                console.log('[AUT] NIMA Join button clicked for row: ' + text.trim());
                                btn.click();
                                return;
                            }
                        }
                    }
                    
                    console.log('[AUT] NIMA class not found in ongoing meetings table. Waiting...');
                    window._autNeedReload = true;
                    
                }, 1000);
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)

        mainHandler.postDelayed({
            webView.evaluateJavascript("window._autNeedReload === true ? 'RELOAD' : 'OK'") { result ->
                if (result?.contains("RELOAD") == true) {
                    webView.evaluateJavascript("window._autNeedReload = false;", null)
                    scheduleReload(60_000L) // Refresh every 60s
                }
            }
        }, 15_000L)
    }

    // =========================================================================
    //  Step 5/7: BBB Room — Join listen-only audio
    // =========================================================================

    private fun joinBbbListenOnly() {
        val js = """
            (function() {
                var iv = setInterval(function() {
                    var overlay = document.querySelector('.ReactModal__Overlay');
                    if (!overlay) return;
                    
                    var btns = overlay.querySelectorAll('button');
                    for (var i = 0; i < btns.length; i++) {
                        var txt = btns[i].textContent || '';
                        var label = btns[i].getAttribute('aria-label') || '';
                        var classes = btns[i].className || '';
                        
                        if (txt.includes('شنیدن') || txt.includes('Listen') || 
                            label.includes('شنیدن') || label.includes('Listen') ||
                            classes.includes('listen')) {
                            
                            clearInterval(iv);
                            console.log('[AUT] BBB Listen-only button clicked!');
                            btns[i].click();
                            return;
                        }
                    }
                }, 1000);
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    // =========================================================================
    //  Reload / retry
    // =========================================================================

    private fun scheduleReload(delayMs: Long = 60_000L) {
        if (refreshCount >= maxRefreshes) {
            log("❌ Max retries ($maxRefreshes) reached.")
            return
        }
        refreshCount++
        cancelPendingReload()
        log("🔄 Class not active yet. Refresh #$refreshCount in ${delayMs / 1000}s...")
        val r = Runnable {
            pendingReloadRunnable = null
            log("🔄 Reloading page now...")
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
    //  Activity attach / detach
    // =========================================================================

    fun attachToActivity(container: ViewGroup) {
        if (isWebViewInBackground) {
            try { windowManager.removeViewImmediate(webView) } catch (_: Exception) {}
            isWebViewInBackground = false
        }
        if (webView.parent != null) (webView.parent as ViewGroup).removeView(webView)
        webView.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        // Add at index 0 so it stays UNDER the ProgressBar!
        container.addView(webView, 0)
    }

    fun attachToBackground() {
        if (webView.parent != null) (webView.parent as ViewGroup).removeView(webView)
        if (!isWebViewInBackground) {
            try {
                webView.layoutParams = backgroundLayoutParams
                windowManager.addView(webView, backgroundLayoutParams)
                isWebViewInBackground = true
            } catch (_: Exception) {}
        }
    }

    // =========================================================================
    //  Notification
    // =========================================================================

    private fun startForegroundNotification() {
        val channelId = "AutoJoinChannel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                channelId, "Class Auto Joiner", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("LMS Auto Joiner")
            .setContentText("Watching your schedule...")
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .setOngoing(true)
            .build()
            
        try {
            // Android 14+ requires foregroundType defined in startForeground
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(1, notification)
            }
        } catch (e: Exception) {
            log("⚠️ Foreground Service constraint: ${e.message}")
        }
    }

    // =========================================================================
    //  Utility
    // =========================================================================

    private fun parseSpecFromJson(json: String): ClassSpec {
        val obj = JSONObject(json)
        val daysArr = obj.getJSONArray("days")
        val days = (0 until daysArr.length()).map { daysArr.getString(it) }
        return ClassSpec(
            name      = obj.getString("name"),
            startTime = obj.getString("startTime"),
            endTime   = obj.getString("endTime"),
            days      = days,
            platform  = obj.optString("platform", "LMS").uppercase()
        )
    }
}