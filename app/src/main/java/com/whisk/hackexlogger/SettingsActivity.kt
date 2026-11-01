package com.whisk.hackexlogger

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Activity for managing app settings, Google Account sign-in, and cloud data preferences.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var userInfoLayout: LinearLayout
    private lateinit var userNameTv: TextView
    private lateinit var userEmailTv: TextView
    private lateinit var signInBtn: Button
    private lateinit var signOutBtn: Button
    private lateinit var standaloneDisclaimerTv: TextView

    private lateinit var cloudControlsCard: LinearLayout
    private lateinit var autoSyncCheckBox: CheckBox
    private lateinit var lastSyncTv: TextView
    private lateinit var backupNowBtn: Button
    private lateinit var restoreNowBtn: Button

    private val googleAccountPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val accountName = result.data?.getStringExtra(android.accounts.AccountManager.KEY_ACCOUNT_NAME)
            if (!accountName.isNullOrEmpty()) {
                val rawName = accountName.substringBefore("@")
                val displayName = rawName.split(".", "_", "-")
                    .joinToString(" ") { part -> part.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() } }
                val user = GoogleAccountUser(
                    displayName = displayName,
                    email = accountName,
                    photoUrl = null,
                    idToken = null
                )
                AccountManager.saveUser(this, user)
                CloudBackupManager.flushPendingBackup(this)
                Toast.makeText(this, "Signed in as $accountName", Toast.LENGTH_SHORT).show()
                updateUiState()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_settings)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        AccountManager.init(this)
        DatabaseManager.init(this)

        initViews()
        setupListeners()
        updateUiState()
    }

    private fun initViews() {
        findViewById<ImageButton>(R.id.backBtn).setOnClickListener { finish() }

        userInfoLayout = findViewById(R.id.userInfoLayout)
        userNameTv = findViewById(R.id.userNameTv)
        userEmailTv = findViewById(R.id.userEmailTv)
        signInBtn = findViewById(R.id.signInBtn)
        signOutBtn = findViewById(R.id.signOutBtn)
        standaloneDisclaimerTv = findViewById(R.id.standaloneDisclaimerTv)

        cloudControlsCard = findViewById(R.id.cloudControlsCard)
        autoSyncCheckBox = findViewById(R.id.autoSyncCheckBox)
        lastSyncTv = findViewById(R.id.lastSyncTv)
        backupNowBtn = findViewById(R.id.backupNowBtn)
        restoreNowBtn = findViewById(R.id.restoreNowBtn)
    }

    private fun setupListeners() {
        signInBtn.setOnClickListener {
            signInBtn.isEnabled = false
            lifecycleScope.launch {
                val result = AccountManager.signInWithGoogle(this@SettingsActivity)
                signInBtn.isEnabled = true
                if (result.isSuccess) {
                    Toast.makeText(this@SettingsActivity, "Signed in successfully", Toast.LENGTH_SHORT).show()
                    updateUiState()
                } else {
                    val msg = result.exceptionOrNull()?.message ?: ""
                    if (msg.contains("cancel", ignoreCase = true)) {
                        Toast.makeText(this@SettingsActivity, "Sign-in cancelled", Toast.LENGTH_SHORT).show()
                        updateUiState()
                    } else {
                        // Fallback to native System Google Account Chooser
                        try {
                            val intent = android.accounts.AccountManager.newChooseAccountIntent(
                                null, null, arrayOf("com.google"), false, null, null, null, null
                            )
                            googleAccountPickerLauncher.launch(intent)
                        } catch (e: Exception) {
                            Toast.makeText(this@SettingsActivity, "Sign-in error: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }

        signOutBtn.setOnClickListener {
            lifecycleScope.launch {
                AccountManager.signOut(this@SettingsActivity)
                Toast.makeText(this@SettingsActivity, "Signed out", Toast.LENGTH_SHORT).show()
                updateUiState()
            }
        }

        autoSyncCheckBox.setOnCheckedChangeListener { _, isChecked ->
            AccountManager.setCloudSyncEnabled(this, isChecked)
            if (isChecked) {
                CloudBackupManager.performBackup(this)
                updateUiState()
            }
        }

        backupNowBtn.setOnClickListener {
            try {
                val count = CloudBackupManager.performBackup(this)
                updateUiState()
                Toast.makeText(this, "Backed up $count target records to account space.", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Backup failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }

        restoreNowBtn.setOnClickListener {
            val savedCount = CloudBackupManager.getSavedBackupTargetCount(this)
            AlertDialog.Builder(this)
                .setTitle("Restore Cloud Data")
                .setMessage("Restore account backup ($savedCount target records) into your local database?")
                .setPositiveButton("Restore") { _, _ ->
                    try {
                        val result = CloudBackupManager.performRestore(this)
                        updateUiState()
                        val msg = if (result.ingestCount > 0) {
                            "Restored ${result.targetCount} targets and ${result.ingestCount} raw logs."
                        } else {
                            "Restored ${result.targetCount} targets."
                        }
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    } catch (e: Exception) {
                        Toast.makeText(this, "Restore failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun updateUiState() {
        if (!AccountManager.isGoogleAuthSupported) {
            // Standalone Offline Build
            userInfoLayout.visibility = View.GONE
            signInBtn.visibility = View.GONE
            signOutBtn.visibility = View.GONE
            standaloneDisclaimerTv.visibility = View.VISIBLE
            standaloneDisclaimerTv.text = "Standalone Build: Cloud sync unavailable in offline build."

            cloudControlsCard.visibility = View.GONE
            return
        }

        // Google Play Build
        standaloneDisclaimerTv.visibility = View.GONE
        cloudControlsCard.visibility = View.VISIBLE

        val user = AccountManager.getCurrentUser()
        if (user != null) {
            userInfoLayout.visibility = View.VISIBLE
            userNameTv.text = user.displayName ?: "Google User"
            userEmailTv.text = user.email ?: ""

            signInBtn.visibility = View.GONE
            signOutBtn.visibility = View.VISIBLE

            autoSyncCheckBox.isEnabled = true
            autoSyncCheckBox.isChecked = AccountManager.isCloudSyncEnabled(this)
            backupNowBtn.isEnabled = true
            restoreNowBtn.isEnabled = true

            val savedCount = CloudBackupManager.getSavedBackupTargetCount(this)
            val lastSyncTime = CloudBackupManager.getFormattedLastSyncTime(this)
            lastSyncTv.text = "Last Synced: $lastSyncTime ($savedCount records stored)"
        } else {
            userInfoLayout.visibility = View.GONE
            signInBtn.visibility = View.VISIBLE
            signOutBtn.visibility = View.GONE

            autoSyncCheckBox.isEnabled = false
            autoSyncCheckBox.isChecked = false
            backupNowBtn.isEnabled = false
            restoreNowBtn.isEnabled = false

            lastSyncTv.text = "Not Signed In (Local Data Only)"
        }
    }
}
