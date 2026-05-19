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

    // Discovery progress UI
    private lateinit var discoveryProgressSection: LinearLayout
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

        val mainLayout = findViewById<LinearLayout>(R.id.main)
        ViewCompat.setOnApplyWindowInsetsListener(mainLayout) { v, insets ->
            val sys = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val pad = (8 * resources.displayMetrics.density).toInt()
            v.setPadding(sys.left + pad, sys.top + pad, sys.right + pad, sys.bottom + pad)
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
            getSharedPreferences("LMS_PREFS", Context.MODE_PRIVATE)
                .edit().putString("USERNAME", user).putString("PASSWORD", pass).apply()
            Toast.makeText(this, R.string.credentials_saved, Toast.LENGTH_SHORT).show()
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

            // Check if already running
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