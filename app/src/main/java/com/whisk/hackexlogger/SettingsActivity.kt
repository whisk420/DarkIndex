package com.whisk.hackexlogger

import android.os.Bundle
import android.widget.ImageButton
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Activity for managing app settings and preferences.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var advancedTargetingSwitch: androidx.appcompat.widget.SwitchCompat

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_settings)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        DatabaseManager.init(this)
        initViews()
    }

    private fun initViews() {
        findViewById<ImageButton>(R.id.backBtn).setOnClickListener { finish() }

        advancedTargetingSwitch = findViewById(R.id.advancedTargetingSwitch)
        val prefs = getSharedPreferences("HackExSettings", MODE_PRIVATE)
        advancedTargetingSwitch.isChecked = prefs.getBoolean("pref_advanced_targeting", false)
        
        advancedTargetingSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("pref_advanced_targeting", isChecked).apply()
            // Send broadcast to update MainActivity if it's open
            val updateIntent = android.content.Intent("com.whisk.hackexlogger.DATA_UPDATED")
            updateIntent.setPackage(packageName)
            sendBroadcast(updateIntent)
        }
    }
}
