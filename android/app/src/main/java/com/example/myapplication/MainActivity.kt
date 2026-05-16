// MainActivity.kt
package com.example.myapplication

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
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

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        if (Settings.canDrawOverlays(this)) {
            startAutoJoinService()
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
                // Hide the "waiting for service" text
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

            // Automatically start the login test flow if the service is currently idle
            // so the webview isn't blank
            if (autoJoinService?.isIdle() == true) {
                autoJoinService?.triggerTestLogin()
            }
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound = false
            autoJoinService = null
        }
    }

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
        webViewContainer = findViewById(R.id.webViewContainer)
        tvLogs = findViewById(R.id.tvLogs)
        logScrollView = findViewById(R.id.logScrollView)
        credentialStatusDot = findViewById(R.id.credentialStatusDot)
        tvCredentialStatus = findViewById(R.id.tvCredentialStatus)
        btnShowCredentials = findViewById(R.id.btnShowCredentials)
        credentialInputSection = findViewById(R.id.credentialInputSection)
        etUsername = findViewById(R.id.etUsername)
        etPassword = findViewById(R.id.etPassword)
        btnSave = findViewById(R.id.btnSave)
        btnCancel = findViewById(R.id.btnCancel)

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

        // Show current credential status
        updateCredentialStatusDisplay()

        // Edit button — guarded by biometric/PIN
        btnShowCredentials.setOnClickListener {
            authenticateAndShowCredentials()
        }

        // Save credentials
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

            // If they just saved credentials and we are idle, run test login
            if (autoJoinService?.isIdle() == true) {
                autoJoinService?.triggerTestLogin()
            }
        }

        // Cancel editing
        btnCancel.setOnClickListener {
            hideCredentialInputs()
        }

        // First launch: prompt for credentials
        val prefs = getSharedPreferences("LMS_PREFS", Context.MODE_PRIVATE)
        val hasCredentials = !prefs.getString("USERNAME", "").isNullOrEmpty() &&
                !prefs.getString("PASSWORD", "").isNullOrEmpty()

        if (!hasCredentials) {
            showCredentialInputs(loadSaved = false)
            Toast.makeText(this, R.string.enter_credentials_prompt, Toast.LENGTH_LONG).show()
        }

        checkOverlayPermissionAndStartService()
    }

    // =========================================================================
    //  Credential display
    // =========================================================================

    private fun updateCredentialStatusDisplay() {
        val prefs = getSharedPreferences("LMS_PREFS", Context.MODE_PRIVATE)
        val user = prefs.getString("USERNAME", "") ?: ""
        val pass = prefs.getString("PASSWORD", "") ?: ""

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
        btnShowCredentials.visibility = View.GONE
    }

    private fun hideCredentialInputs() {
        credentialInputSection.visibility = View.GONE
        btnShowCredentials.visibility = View.VISIBLE
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
            BiometricManager.BIOMETRIC_SUCCESS -> {
                showBiometricPrompt()
            }
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED,
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> {
                showCredentialInputs()
            }
            else -> {
                showCredentialInputs()
            }
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

        val prompt = BiometricPrompt(this, executor, callback)

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
            startAutoJoinService()
        }
    }

    private fun startAutoJoinService() {
        try {
            val serviceIntent = Intent(this, AutoJoinService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to start service: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onStart() {
        super.onStart()
        if (Settings.canDrawOverlays(this)) {
            Intent(this, AutoJoinService::class.java).also { intent ->
                bindService(intent, connection, Context.BIND_AUTO_CREATE)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            autoJoinService?.logUpdateListener = null
            autoJoinService?.pageLoadProgressListener = null
            autoJoinService?.attachToBackground()
            unbindService(connection)
            isBound = false
        }
    }
}