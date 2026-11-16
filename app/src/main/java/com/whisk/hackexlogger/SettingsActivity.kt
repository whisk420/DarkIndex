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

        val profile = PlayerProfileRepository.getProfile()
        
        val hardwareText = findViewById<android.widget.TextView>(R.id.hardwareText)
        val softwareText = findViewById<android.widget.TextView>(R.id.softwareText)
        
        val currentDevice = PlayerProfileRepository.decrementHardwareName(profile.nextDevice) ?: "Unknown"
        val currentNetwork = PlayerProfileRepository.decrementHardwareName(profile.nextNetwork) ?: "Unknown"

        hardwareText.text = "Device: $currentDevice\n" +
            "Network: $currentNetwork\n" +
            "Botnet: ${profile.botnetStatus ?: "Unknown"}"

        val softwareLevelsStr = profile.softwareLevels.entries
            .joinToString("\n") { "${it.key}: ${it.value - 1}" } // Store shows NEXT level, so subtract 1 for current
            
        softwareText.text = softwareLevelsStr.ifEmpty { "No software parsed yet." }

        findViewById<android.widget.Button>(R.id.btnPurgeThefts).setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Purge Old Theft Data")
                .setMessage("Are you sure you want to delete all theft events older than 30 days? (Events that make up the only reliable data for a target will be safely preserved).")
                .setPositiveButton("Purge") { _, _ ->
                    val purgedCount = DatabaseManager.purgeOldTheftEvents()
                    android.widget.Toast.makeText(this, "Purged $purgedCount old theft events", android.widget.Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }
}
