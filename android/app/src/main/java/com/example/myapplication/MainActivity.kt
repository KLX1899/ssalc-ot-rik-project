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
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
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
    private lateinit var tvLogs: TextView
    private lateinit var logScrollView: ScrollView

    private lateinit var credentialStatusDot: View
    private lateinit var tvCredentialStatus: TextView
    private lateinit var btnShowCredentials: MaterialButton
    private lateinit var credentialInputSection: LinearLayout
    private lateinit var etUsername: TextInputEditText
    private lateinit var etPassword: TextInputEditText
    private lateinit var btnSave: MaterialButton
    private lateinit var btnCancel: MaterialButton
    private lateinit var btnDiscoverClasses: MaterialButton
    private lateinit var btnManageClasses: MaterialButton
    private lateinit var btnGoToClass: MaterialButton

    // Discovery progress UI — now a MaterialCardView, use View as the common base type
    private lateinit var discoveryProgressSection: View
    private lateinit var tvDiscoveryProgress: TextView
    private lateinit var btnCancelDiscovery: MaterialButton

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
                webViewContainer.getChildAt(0).visibility = View.GONE
            }
            autoJoinService?.attachToActivity(webViewContainer)
            tvLogs.text = autoJoinService?.getFullLogs()

            // Log listener
            autoJoinService?.logUpdateListener = { newLogs ->
                runOnUiThread {
                    tvLogs.text = newLogs
                    logScrollView.post { logScrollView.fullScroll(View.FOCUS_DOWN) }
                }
            }

            // Discovery progress listener
            autoJoinService?.discoveryProgressListener = { current, total, name ->
                runOnUiThread {
                    discoveryProgressSection.visibility = View.VISIBLE
                    btnDiscoverClasses.isEnabled = false
                    tvDiscoveryProgress.text = getString(R.string.discovery_progress, current, total)
                }
            }

            // Discovery complete listener
            autoJoinService?.discoveryCompleteListener = { count, success ->
                runOnUiThread {
                    discoveryProgressSection.visibility = View.GONE
                    btnDiscoverClasses.isEnabled = true
                    if (success && count > 0) {
                        Toast.makeText(
                            this@MainActivity,
                            getString(R.string.discovery_complete, count),
                            Toast.LENGTH_LONG
                        ).show()
                    } else if (!success) {
                        Toast.makeText(
                            this@MainActivity,
                            R.string.discovery_cancelled,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }

            // If discovery is already running when we connect, show progress
            if (autoJoinService?.isDiscovering == true) {
                discoveryProgressSection.visibility = View.VISIBLE
                btnDiscoverClasses.isEnabled = false
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

        // Apply window insets to the inner LinearLayout so content respects status/nav bars
        val mainLayout = findViewById<LinearLayout>(R.id.main)
        ViewCompat.setOnApplyWindowInsetsListener(mainLayout) { v, insets ->
            val sys = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(v.paddingLeft, sys.top, v.paddingRight, sys.bottom)
            insets
        }

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
        btnDiscoverClasses = findViewById(R.id.btnDiscoverClasses)
        btnManageClasses = findViewById(R.id.btnManageClasses)
        btnGoToClass = findViewById(R.id.btnGoToClass)
        discoveryProgressSection = findViewById(R.id.discoveryProgressSection)
        tvDiscoveryProgress = findViewById(R.id.tvDiscoveryProgress)
        btnCancelDiscovery = findViewById(R.id.btnCancelDiscovery)

        updateCredentialStatusDisplay()

        btnShowCredentials.setOnClickListener { authenticateAndShowCredentials() }

        btnSave.setOnClickListener {
            val user = etUsername.text.toString().trim()
            val pass = etPassword.text.toString().trim()
            if (user.isEmpty() || pass.isEmpty()) {
                Toast.makeText(this, R.string.both_fields_required, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val prefs = getSharedPreferences("LMS_PREFS", Context.MODE_PRIVATE)

            val oldUser = prefs.getString("USERNAME", "") ?: ""
            val oldPass = prefs.getString("PASSWORD", "") ?: ""

            prefs.edit()
                .putString("USERNAME", user)
                .putString("PASSWORD", pass)
                .apply()

            // If credentials changed, reset the LMS web session
            if (oldUser != user || oldPass != pass) {
                // 1. Reset the existing web session (clears cookies, cache, state)
                val resetIntent = Intent(this, AutoJoinService::class.java).apply {
                    putExtra("RESET_SESSION", true)
                }
                startService(resetIntent)

                // 2. After a short delay, start discovery for the new user's classes
                //    (delay gives the WebView time to finish clearing before loading new URLs)
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    val discoverIntent = Intent(this, AutoJoinService::class.java).apply {
                        putExtra("DISCOVER_CLASSES", true)
                    }
                    startService(discoverIntent)
                    discoveryProgressSection.visibility = View.VISIBLE
                    btnDiscoverClasses.isEnabled = false
                }, 1500L)

                Toast.makeText(
                    this,
                    "اطلاعات ورود ذخیره شد — در حال جستجوی کلاس‌های جدید...",
                    Toast.LENGTH_LONG
                ).show()
            }

            hideCredentialInputs()
            updateCredentialStatusDisplay()
        }

        btnCancel.setOnClickListener { hideCredentialInputs() }

        // ── Class Discovery ──────────────────────────────────────────────────
        btnDiscoverClasses.setOnClickListener {
            val prefs = getSharedPreferences("LMS_PREFS", Context.MODE_PRIVATE)
            if (prefs.getString("USERNAME", "").isNullOrEmpty() ||
                prefs.getString("PASSWORD", "").isNullOrEmpty()
            ) {
                Toast.makeText(this, R.string.discovery_needs_credentials, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (autoJoinService?.isDiscovering == true) {
                Toast.makeText(this, R.string.discovery_in_progress, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            Toast.makeText(this, R.string.discovery_started, Toast.LENGTH_LONG).show()
            discoveryProgressSection.visibility = View.VISIBLE
            btnDiscoverClasses.isEnabled = false

            val intent = Intent(this, AutoJoinService::class.java).apply {
                putExtra("DISCOVER_CLASSES", true)
            }
            startService(intent)
        }

        // ── Cancel Discovery ─────────────────────────────────────────────────
        btnCancelDiscovery.setOnClickListener {
            val intent = Intent(this, AutoJoinService::class.java).apply {
                putExtra("CANCEL_DISCOVERY", true)
            }
            startService(intent)
            discoveryProgressSection.visibility = View.GONE
            btnDiscoverClasses.isEnabled = true
        }

        // ── Manage Classes ───────────────────────────────────────────────────
        btnManageClasses.setOnClickListener {
            startActivity(Intent(this, ManageClassesActivity::class.java))
        }

        // ── Go To Class (برو کلاس) ──────────────────────────────────────────
        btnGoToClass.setOnClickListener {
            val prefs = getSharedPreferences("LMS_PREFS", Context.MODE_PRIVATE)
            if (prefs.getString("USERNAME", "").isNullOrEmpty() ||
                prefs.getString("PASSWORD", "").isNullOrEmpty()
            ) {
                Toast.makeText(this, "ابتدا اطلاعات ورود را وارد کنید", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (autoJoinService?.isDiscovering == true) {
                Toast.makeText(this, "لطفاً صبر کنید تا اکتشاف تمام شود", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            Toast.makeText(this, "در حال بررسی برنامه کلاسی...", Toast.LENGTH_SHORT).show()

            val intent = Intent(this, AutoJoinService::class.java).apply {
                putExtra("GO_TO_CLASS", true)
            }
            startService(intent)
        }

        // First launch prompt
        val prefs = getSharedPreferences("LMS_PREFS", Context.MODE_PRIVATE)
        if (prefs.getString("USERNAME", "").isNullOrEmpty() ||
            prefs.getString("PASSWORD", "").isNullOrEmpty()
        ) {
            showCredentialInputs(loadSaved = false)
            Toast.makeText(this, R.string.enter_credentials_prompt, Toast.LENGTH_LONG).show()
        }

        checkOverlayPermissionAndStartService()
    }

    override fun onResume() {
        super.onResume()
        // Refresh credential display whenever the activity comes back to the foreground
        updateCredentialStatusDisplay()
    }

    // ── Credential helpers ───────────────────────────────────────────────────

    private fun updateCredentialStatusDisplay() {
        val prefs = getSharedPreferences("LMS_PREFS", Context.MODE_PRIVATE)
        val user = prefs.getString("USERNAME", "") ?: ""
        val pass = prefs.getString("PASSWORD", "") ?: ""
        if (user.isNotEmpty() && pass.isNotEmpty()) {
            credentialStatusDot.setBackgroundResource(R.drawable.status_dot_green)
            val masked = "\u2022".repeat(pass.length.coerceAtMost(12))
            tvCredentialStatus.text = getString(R.string.credential_status_saved, user, masked)
            btnShowCredentials.setText(R.string.btn_edit)
        } else {
            credentialStatusDot.setBackgroundResource(R.drawable.status_dot_red)
            tvCredentialStatus.setText(R.string.credential_status_empty)
            btnShowCredentials.setText(R.string.btn_setup)
        }
    }

    private fun showCredentialInputs(loadSaved: Boolean = true) {
        val prefs = getSharedPreferences("LMS_PREFS", Context.MODE_PRIVATE)
        if (loadSaved) {
            etUsername.setText(prefs.getString("USERNAME", "") ?: "")
            etPassword.setText(prefs.getString("PASSWORD", "") ?: "")
        } else {
            etUsername.setText("")
            etPassword.setText("")
        }
        credentialInputSection.visibility = View.VISIBLE
        btnShowCredentials.visibility = View.GONE
        etUsername.requestFocus()
    }

    private fun hideCredentialInputs() {
        credentialInputSection.visibility = View.GONE
        btnShowCredentials.visibility = View.VISIBLE
        etPassword.setText("")
    }

    // ── Biometric ────────────────────────────────────────────────────────────

    private fun authenticateAndShowCredentials() {
        val bm = BiometricManager.from(this)
        when (bm.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_WEAK or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
        )) {
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
                        this@MainActivity, getString(R.string.auth_error, errString),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            override fun onAuthenticationFailed() {
                Toast.makeText(this@MainActivity, R.string.auth_failed, Toast.LENGTH_SHORT).show()
            }
        }
        BiometricPrompt(this, executor, callback).authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.biometric_title))
                .setSubtitle(getString(R.string.biometric_subtitle))
                .setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_WEAK or
                            BiometricManager.Authenticators.DEVICE_CREDENTIAL
                )
                .build()
        )
    }

    // ── Service ──────────────────────────────────────────────────────────────

    private fun checkOverlayPermissionAndStartService() {
        if (!Settings.canDrawOverlays(this)) {
            overlayPermissionLauncher.launch(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri())
            )
            Toast.makeText(this, R.string.allow_overlay, Toast.LENGTH_LONG).show()
        } else {
            startAutoJoinService()
        }
    }

    private fun startAutoJoinService() {
        val si = Intent(this, AutoJoinService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(si)
        else startService(si)
    }

    override fun onStart() {
        super.onStart()
        if (Settings.canDrawOverlays(this)) {
            Intent(this, AutoJoinService::class.java).also {
                bindService(it, connection, Context.BIND_AUTO_CREATE)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            autoJoinService?.logUpdateListener = null
            autoJoinService?.discoveryProgressListener = null
            autoJoinService?.discoveryCompleteListener = null
            autoJoinService?.attachToBackground()
            unbindService(connection)
            isBound = false
        }
    }
}