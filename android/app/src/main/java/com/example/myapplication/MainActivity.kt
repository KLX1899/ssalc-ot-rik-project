// MainActivity.kt
package com.example.myapplication

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

class MainActivity : AppCompatActivity() {

    private var autoJoinService: AutoJoinService? = null
    private var isBound = false

    private lateinit var webViewContainer: FrameLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var tvLogs: TextView
    private lateinit var logScrollView: ScrollView

    // Credential UI
    private lateinit var credentialStatusDot: View
    private lateinit var tvCredentialStatus: TextView
    private lateinit var btnShowCredentials: MaterialButton
    private lateinit var credentialInputSection: LinearLayout
    private lateinit var etUsername: TextInputEditText
    private lateinit var etPassword: TextInputEditText
    private lateinit var btnSave: MaterialButton
    private lateinit var btnCancel: MaterialButton

    // -------------------------------------------------------------------------
    // FIX: track whether the service has been started at least once this
    // process lifetime.  The flag lives in a companion object so it survives
    // onStop/onStart cycles (back-stack, screen-off, recent-apps) without
    // triggering a redundant startForegroundService() call.
    // -------------------------------------------------------------------------
    companion object {
        private var serviceStarted = false
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        if (Settings.canDrawOverlays(this)) {
            startAutoJoinServiceOnce()
        } else {
            Toast.makeText(this, R.string.permission_required, Toast.LENGTH_LONG).show()
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as AutoJoinService.LocalBinder
            autoJoinService = binder.getService()
            isBound = true

            if (webViewContainer.childCount > 0) {
                webViewContainer.getChildAt(0).visibility = View.GONE
            }

            // Attach WebView
            autoJoinService?.attachToActivity(webViewContainer)

            // Connect Progress Bar
            autoJoinService?.pageLoadProgressListener = { progress ->
                runOnUiThread {
                    if (progress < 100) {
                        progressBar.visibility = View.VISIBLE
                        progressBar.progress = progress
                    } else {
                        progressBar.visibility = View.GONE
                    }
                }
            }

            // Connect Logs
            tvLogs.text = autoJoinService?.getFullLogs()
            autoJoinService?.logUpdateListener = { newLogs ->
                runOnUiThread {
                    tvLogs.text = newLogs
                    logScrollView.post { logScrollView.fullScroll(View.FOCUS_DOWN) }
                }
            }

            // FIX: Only trigger the test-login on the very first bind (i.e. when
            // the WebView has never navigated anywhere).  After the user presses
            // Home / Recents and comes back, the WebView is already showing a page,
            // so we must NOT restart the flow.
            if (autoJoinService?.isIdle() == true && autoJoinService?.getCurrentUrl().isNullOrBlank()) {
                autoJoinService?.triggerTestLogin()
            }
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound = false
            autoJoinService = null
        }
    }

    // =========================================================================
    //  Lifecycle
    // =========================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val mainLayout = findViewById<LinearLayout>(R.id.main)
        ViewCompat.setOnApplyWindowInsetsListener(mainLayout) { v, insets ->
            val sys = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val pad = (8 * resources.displayMetrics.density).toInt()
            v.setPadding(sys.left + pad, sys.top + pad, sys.right + pad, sys.bottom + pad)
            insets
        }

        // Find views
        webViewContainer    = findViewById(R.id.webViewContainer)
        tvLogs              = findViewById(R.id.tvLogs)
        logScrollView       = findViewById(R.id.logScrollView)
        credentialStatusDot = findViewById(R.id.credentialStatusDot)
        tvCredentialStatus  = findViewById(R.id.tvCredentialStatus)
        btnShowCredentials  = findViewById(R.id.btnShowCredentials)
        credentialInputSection = findViewById(R.id.credentialInputSection)
        etUsername          = findViewById(R.id.etUsername)
        etPassword          = findViewById(R.id.etPassword)
        btnSave             = findViewById(R.id.btnSave)
        btnCancel           = findViewById(R.id.btnCancel)

        // Setup the Top Progress Bar for the browser
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                (4 * resources.displayMetrics.density).toInt()
            ).apply { gravity = Gravity.TOP }
            max = 100
            visibility = View.GONE
        }
        webViewContainer.addView(progressBar)

        updateCredentialStatusDisplay()

        btnShowCredentials.setOnClickListener { authenticateAndShowCredentials() }

        btnSave.setOnClickListener {
            val user = etUsername.text.toString().trim()
            val pass = etPassword.text.toString().trim()

            if (user.isEmpty() || pass.isEmpty()) {
                Toast.makeText(this, R.string.both_fields_required, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            getSharedPreferences("LMS_PREFS", Context.MODE_PRIVATE)
                .edit()
                .putString("USERNAME", user)
                .putString("PASSWORD", pass)
                .apply()

            Toast.makeText(this, R.string.credentials_saved, Toast.LENGTH_SHORT).show()
            hideCredentialInputs()
            updateCredentialStatusDisplay()

            if (autoJoinService?.isIdle() == true && autoJoinService?.getCurrentUrl().isNullOrBlank()) {
                autoJoinService?.triggerTestLogin()
            }
        }

        btnCancel.setOnClickListener { hideCredentialInputs() }

        // First launch: prompt for credentials
        val prefs = getSharedPreferences("LMS_PREFS", Context.MODE_PRIVATE)
        val hasCredentials = !prefs.getString("USERNAME", "").isNullOrEmpty() &&
                !prefs.getString("PASSWORD", "").isNullOrEmpty()

        if (!hasCredentials) {
            showCredentialInputs(loadSaved = false)
            Toast.makeText(this, R.string.enter_credentials_prompt, Toast.LENGTH_LONG).show()
        }

        // FIX: Only request permission / start service once per process.
        // On every subsequent entry (Home, Recents, screen-off) we skip this
        // entirely — the service is already running as a foreground service.
        if (!serviceStarted) {
            checkOverlayPermissionAndStartService()
        }

        // Ask the user to disable battery optimization so the foreground
        // service is not killed when the screen turns off (common on OEM ROMs).
        requestIgnoreBatteryOptimizations()
    }

    // =========================================================================
    //  onNewIntent — called when launchMode="singleTop" intercepts a re-launch
    //  (e.g. tapping the launcher icon while the activity is already on top).
    //  We override it to do nothing, which is exactly what we want: just bring
    //  the existing instance to the foreground without restarting anything.
    // =========================================================================
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Intentionally empty — existing state is preserved as-is.
    }

    // =========================================================================
    //  Credential display
    // =========================================================================

    private fun updateCredentialStatusDisplay() {
        val prefs = getSharedPreferences("LMS_PREFS", Context.MODE_PRIVATE)
        val user  = prefs.getString("USERNAME", "") ?: ""
        val pass  = prefs.getString("PASSWORD", "") ?: ""

        if (user.isNotEmpty() && pass.isNotEmpty()) {
            credentialStatusDot.setBackgroundResource(R.drawable.status_dot_green)
            val maskedPass = "\u2022".repeat(pass.length.coerceAtMost(12))
            tvCredentialStatus.text = getString(R.string.credential_status_saved, user, maskedPass)
            btnShowCredentials.setText(R.string.btn_edit)
        } else {
            credentialStatusDot.setBackgroundResource(R.drawable.status_dot_red)
            tvCredentialStatus.setText(R.string.credential_status_empty)
            btnShowCredentials.setText(R.string.btn_setup)
        }
    }

    private fun showCredentialInputs(loadSaved: Boolean = true) {
        if (loadSaved) {
            val prefs = getSharedPreferences("LMS_PREFS", Context.MODE_PRIVATE)
            etUsername.setText(prefs.getString("USERNAME", ""))
            etPassword.setText(prefs.getString("PASSWORD", ""))
        } else {
            etUsername.setText("")
            etPassword.setText("")
        }
        credentialInputSection.visibility = View.VISIBLE
        btnShowCredentials.visibility     = View.GONE
    }

    private fun hideCredentialInputs() {
        credentialInputSection.visibility = View.GONE
        btnShowCredentials.visibility     = View.VISIBLE
        etPassword.setText("")
    }

    // =========================================================================
    //  Biometric / Device credential authentication
    // =========================================================================

    private fun authenticateAndShowCredentials() {
        val biometricManager = BiometricManager.from(this)
        val canAuth = biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_WEAK or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
        )
        when (canAuth) {
            BiometricManager.BIOMETRIC_SUCCESS -> showBiometricPrompt()
            else -> showCredentialInputs()
        }
    }

    private fun showBiometricPrompt() {
        val executor = ContextCompat.getMainExecutor(this)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                showCredentialInputs()
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                    errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON
                ) {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.auth_error, errString),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            override fun onAuthenticationFailed() {
                Toast.makeText(this@MainActivity, R.string.auth_failed, Toast.LENGTH_SHORT).show()
            }
        }

        val prompt     = BiometricPrompt(this, executor, callback)
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.biometric_title))
            .setSubtitle(getString(R.string.biometric_subtitle))
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_WEAK or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()
        prompt.authenticate(promptInfo)
    }

    // =========================================================================
    //  Service management
    // =========================================================================

    private fun checkOverlayPermissionAndStartService() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                "package:$packageName".toUri()
            )
            Toast.makeText(this, R.string.allow_overlay, Toast.LENGTH_LONG).show()
            overlayPermissionLauncher.launch(intent)
        } else {
            startAutoJoinServiceOnce()
        }
    }

    // FIX: renamed from startAutoJoinService() to make the "only once" contract
    // explicit, and guards with the companion-object flag.
    private fun startAutoJoinServiceOnce() {
        if (serviceStarted) return
        try {
            val serviceIntent = Intent(this, AutoJoinService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            serviceStarted = true
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to start service: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // =========================================================================
    //  onStart / onStop — bind/unbind WITHOUT restarting the service
    // =========================================================================

    override fun onStart() {
        super.onStart()
        // Bind whenever the overlay permission is granted.  BIND_AUTO_CREATE
        // will restart the service if it was killed (low memory / battery
        // optimiser), so we no longer gate on the serviceStarted flag here.
        if (Settings.canDrawOverlays(this)) {
            if (!serviceStarted) {
                startAutoJoinServiceOnce()
            }
            Intent(this, AutoJoinService::class.java).also { intent ->
                bindService(intent, connection, Context.BIND_AUTO_CREATE)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            // Detach listeners and move WebView back to the background overlay
            // window so it keeps running while the activity is not visible.
            autoJoinService?.logUpdateListener       = null
            autoJoinService?.pageLoadProgressListener = null
            autoJoinService?.attachToBackground()
            unbindService(connection)
            isBound = false
            autoJoinService = null
        }
    }

    // =========================================================================
    //  Battery optimisation
    // =========================================================================

    private fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        "package:$packageName".toUri()
                    )
                    startActivity(intent)
                } catch (e: Exception) {
                    // Some OEMs don't support the direct intent; silently ignore.
                }
            }
        }
    }
}