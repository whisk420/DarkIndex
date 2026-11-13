package com.whisk.hackexlogger

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.SeekBar
import android.widget.CheckBox
import android.widget.TextView
import android.widget.EditText
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Main Activity dashboard for HackExLogger.
 *
 * Provides a UI for browsing, searching, editing, and managing collected target data.
 * Also handles JSON data bundle import/export via Android Storage Access Framework
 * and guides users to enable required overlay and accessibility permissions.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var targetAdapter: TargetAdapter
    private var currentSearchQuery = ""
    private var isPartialVisible = false
    private var isGoodSpamEnabled = false
    private var maxFirewallLevel = 20
    private var searchJob: Job? = null

    // Activity result launcher for exporting database records as a JSON document
    private val exportLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data?.data != null) {
            val uri = result.data!!.data!!
            try {
                val jsonString = DatabaseManager.exportDataBundle()
                contentResolver.openOutputStream(uri)?.use { outputStream ->
                    outputStream.write(jsonString.toByteArray(Charsets.UTF_8))
                }
                Toast.makeText(this, "Export successful", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // Activity result launcher for importing database records from a JSON file
    private val importLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data?.data != null) {
            val uri = result.data!!.data!!
            try {
                val jsonString = contentResolver.openInputStream(uri)?.use { inputStream ->
                    inputStream.bufferedReader(Charsets.UTF_8).readText()
                }
                if (jsonString.isNullOrEmpty()) {
                    Toast.makeText(this, "File is empty", Toast.LENGTH_SHORT).show()
                    return@registerForActivityResult
                }
                val importResult = DatabaseManager.importDataBundle(jsonString)
                refreshData()
                val msg = if (importResult.ingestCount > 0) {
                    "Imported ${importResult.targetCount} targets and ${importResult.ingestCount} raw logs."
                } else {
                    "Imported ${importResult.targetCount} targets."
                }
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Import Failed")
                    .setMessage(e.message ?: "Failed to import data.")
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    private fun openExportPicker() {
        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "target_console_$dateStr.json")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val initialUri = Uri.parse("content://com.android.externalstorage.documents/document/primary:Download%2FHackExLogger")
                putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, initialUri)
            }
        }
        exportLauncher.launch(intent)
    }

    private fun openImportPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain"))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val initialUri = Uri.parse("content://com.android.externalstorage.documents/document/primary:Download%2FHackExLogger")
                putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, initialUri)
            }
        }
        importLauncher.launch(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Initialize local database storage
        DatabaseManager.init(this)
        setupUI()
        
        // Observe database updates reactively using StateFlow
        lifecycleScope.launch {
            DatabaseManager.recordsFlow.collect {
                refreshData()
            }
        }

        showPermissionDialog(force = false)
    }

    override fun onResume() {
        super.onResume()
        
        val prefs = getSharedPreferences("HackExSettings", MODE_PRIVATE)
        val isAdvancedTargetingMode = prefs.getBoolean("pref_advanced_targeting", false)
        val validateBtn = findViewById<Button>(R.id.validateBtn)
        validateBtn.text = if (isAdvancedTargetingMode) "Target" else "Validate"
        validateBtn.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor(if (isAdvancedTargetingMode) "#f59e0b" else "#16a34a"))
        
        refreshData()
        if (Settings.canDrawOverlays(this) && isAccessibilityServiceEnabled()) {
            startService(Intent(this, OverlayService::class.java))
        }
    }

    private fun setupUI() {
        targetAdapter = TargetAdapter(this::onEditTarget, this::onDeleteTarget, this::onTogglePartial)
        findViewById<RecyclerView>(R.id.targetsRecycler).adapter = targetAdapter

        val validateBtn = findViewById<Button>(R.id.validateBtn)

        // Validation / Advanced Targeting mode toggle button
        validateBtn.setOnClickListener {
            if (Settings.canDrawOverlays(this) && isAccessibilityServiceEnabled()) {
                val prefs = getSharedPreferences("HackExSettings", MODE_PRIVATE)
                val isAdvancedTargetingMode = prefs.getBoolean("pref_advanced_targeting", false)
                val intent = Intent(this, OverlayService::class.java).apply {
                    action = if (isAdvancedTargetingMode) OverlayService.ACTION_TOGGLE_ADVANCED_TARGETING else OverlayService.ACTION_TOGGLE_VALIDATION
                }
                startService(intent)
            } else {
                showPermissionDialog(force = true)
            }
        }

        // Knowledge Base menu (Temporarily disabled while formulas are being reworked)
        /*
        findViewById<ImageButton>(R.id.knowledgeBaseBtn).setOnClickListener {
            val intent = Intent(this, KnowledgeBaseActivity::class.java)
            startActivity(intent)
        }
        */

        // Siphon Alerts menu
        findViewById<ImageButton>(R.id.siphonAlertsBtn).setOnClickListener {
            val intent = Intent(this, SiphonAlertsActivity::class.java)
            startActivity(intent)
        }

        // Settings popup menu
        findViewById<ImageButton>(R.id.settingsBtn).setOnClickListener { view ->
            val popup = PopupMenu(this, view)
            popup.menu.add(0, 0, 0, "Settings & Account")
            popup.menu.add(0, 1, 0, "Export Data")
            popup.menu.add(0, 2, 0, "Import Data")
            popup.menu.add(0, 3, 0, "Database Maintenance")
            popup.menu.add(0, 4, 0, "Wipe Database")
            popup.menu.add(0, 5, 0, "Reset Siphon Tracker")

            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    0 -> {
                        startActivity(Intent(this, SettingsActivity::class.java))
                        true
                    }
                    1 -> {
                        openExportPicker()
                        true
                    }
                    2 -> {
                        openImportPicker()
                        true
                    }
                    3 -> {
                        val result = DatabaseManager.runDatabaseMaintenance()
                        refreshData()
                        AlertDialog.Builder(this)
                            .setTitle("Database Maintenance Complete")
                            .setMessage(
                                "A backup copy was saved to:\n${result.backupFilePath}\n\n" +
                                "Summary of actions taken:\n" +
                                "• ${result.multiWalletRecordsFixed} multi-wallet target records cleared\n" +
                                "• ${result.maskedSoftwareCleared} masked software inventories cleared\n" +
                                "• ${result.maskedWalletsMigrated} masked wallets migrated to placeholders\n" +
                                "• ${result.oldTheftsCleared} old theft events purged (24h+ gaps)"
                            )
                            .setPositiveButton("OK", null)
                            .show()
                        true
                    }
                    4 -> {
                        DatabaseManager.wipeDatabase()
                        refreshData()
                        android.widget.Toast.makeText(this, "Database wiped", android.widget.Toast.LENGTH_SHORT).show()
                        true
                    }
                    5 -> {
                        DatabaseManager.clearSiphonTracking()
                        android.widget.Toast.makeText(this, "Siphon tracker reset", android.widget.Toast.LENGTH_SHORT).show()
                        true
                    }
                    else -> false
                }
            }
            popup.show()
        }

        // Good Spam Filter Setup
        val goodSpamCheckBox = findViewById<CheckBox>(R.id.goodSpamCheckBox)
        val maxFirewallLabel = findViewById<TextView>(R.id.maxFirewallLabel)
        val maxFirewallSeekBar = findViewById<SeekBar>(R.id.maxFirewallSeekBar)
        val maxFirewallValue = findViewById<TextView>(R.id.maxFirewallValue)

        goodSpamCheckBox.setOnCheckedChangeListener { _, isChecked ->
            isGoodSpamEnabled = isChecked
            val visibility = if (isChecked) View.VISIBLE else View.GONE
            maxFirewallLabel.visibility = visibility
            maxFirewallSeekBar.visibility = visibility
            maxFirewallValue.visibility = visibility
            refreshData()
        }

        maxFirewallSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                maxFirewallLevel = progress
                maxFirewallValue.text = if (progress == 31) "Uncapped" else progress.toString()
                if (isGoodSpamEnabled) {
                    searchJob?.cancel()
                    searchJob = lifecycleScope.launch {
                        delay(150)
                        refreshData()
                    }
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Debounced search text watcher
        val searchInput = findViewById<EditText>(R.id.searchInput)
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                currentSearchQuery = s?.toString()?.trim()?.lowercase() ?: ""
                searchJob?.cancel()
                searchJob = lifecycleScope.launch {
                    delay(150)
                    refreshData()
                }
            }
        })

        findViewById<Button>(R.id.clearSearchBtn).setOnClickListener {
            searchInput.setText("")
        }
    }

    private fun onTogglePartial() {
        isPartialVisible = !isPartialVisible
        refreshData()
    }

    /**
     * Filters stored target records based on search criteria and posts the formatted list to RecyclerView.
     */
    private fun refreshData() {
        lifecycleScope.launch(Dispatchers.Default) {
            val allRecords = DatabaseManager.getAllRecords()
            val prefs = getSharedPreferences("HackExSettings", MODE_PRIVATE)
            val isAdvancedTargetingMode = prefs.getBoolean("pref_advanced_targeting", false)
            
            var filtered = if (isAdvancedTargetingMode) {
                allRecords.filter { it.needsAdvancedTargeting }
            } else {
                allRecords
            }

            if (currentSearchQuery.isNotEmpty()) {
                val query = currentSearchQuery.lowercase()
                
                // Parse software name and level search expressions (e.g. "firewall 10", "siphon lv5")
                val swMatch = Regex("""^(.*?)\s*(?:lv\.?|lvl\.?)?\s*(\d+)$""", RegexOption.IGNORE_CASE).find(query)
                var swFilteredRecords: List<TargetRecord>? = null
                
                if (swMatch != null) {
                    val swName = swMatch.groupValues[1].trim()
                    val targetLevel = swMatch.groupValues[2].toIntOrNull()
                    
                    if (swName.isNotEmpty() && targetLevel != null) {
                        fun getMatching(levels: List<Int>): List<TargetRecord> {
                            return allRecords.filter { record ->
                                record.downloads.any { (k, v) ->
                                    k.lowercase().contains(swName) && levels.contains(v.level)
                                }
                            }
                        }
                        
                        val exactMatches = getMatching(listOf(targetLevel))
                        if (exactMatches.isNotEmpty()) {
                            swFilteredRecords = exactMatches
                        } else {
                            val plusMinusOne = getMatching(listOf(targetLevel - 1, targetLevel + 1).filter { it >= 1 })
                            if (plusMinusOne.isNotEmpty()) {
                                swFilteredRecords = plusMinusOne
                            } else {
                                val plusMinusTwo = getMatching(listOf(targetLevel - 2, targetLevel + 2).filter { it >= 1 })
                                swFilteredRecords = plusMinusTwo
                            }
                        }
                    }
                }

                val genericFiltered = allRecords.filter { record ->
                    record.ip.lowercase().contains(query) ||
                    record.username?.lowercase()?.contains(query) == true ||
                    record.wallets.any { it.lowercase().contains(query) } ||
                    record.downloads.keys.any { it.lowercase().contains(query) }
                }
                
                filtered = if (swFilteredRecords != null) {
                    (swFilteredRecords + genericFiltered).distinctBy { it.ip }
                } else {
                    genericFiltered
                }
            }

            if (isGoodSpamEnabled) {
                val maxFw = if (maxFirewallLevel == 31) Int.MAX_VALUE else maxFirewallLevel
                filtered = filtered.filter { record ->
                    val fw = record.firewall ?: 0
                    fw <= maxFw
                }.sortedByDescending { record ->
                    val lvl = (record.level ?: 0).toDouble()
                    val fw = (record.firewall ?: 0).toDouble()
                    val hwPenalty = getHardwarePenalty(record.hardware)
                    
                    // High emphasis on player level (Spam payout), but explicitly placing weight on lower hardware tiers
                    // (faster upload speeds) and lower firewalls.
                    (lvl * 5.0) - hwPenalty - (fw * 1.5)
                }
            }

            val fullTargets = if (isGoodSpamEnabled) {
                filtered.filter { !it.isMasked } // already sorted
            } else {
                filtered.filter { !it.isMasked }.sortedBy { it.ip }
            }
            
            val partialTargets = if (isGoodSpamEnabled) {
                filtered.filter { it.isMasked } // already sorted
            } else {
                filtered.filter { it.isMasked }.sortedBy { it.ip }
            }

            val items = mutableListOf<ListItem>()
            items.add(ListItem.Header("FULL TARGETS (${fullTargets.size})"))
            fullTargets.forEach { items.add(ListItem.Target(it)) }

            val partialHeaderTitle = if (isPartialVisible) {
                "▲ PARTIAL / MASKED TARGETS (${partialTargets.size})"
            } else {
                "▼ PARTIAL / MASKED TARGETS (${partialTargets.size})"
            }
            items.add(ListItem.Header(partialHeaderTitle, isPartialHeader = true, isExpanded = isPartialVisible))
            if (isPartialVisible) {
                partialTargets.forEach { items.add(ListItem.Target(it)) }
            }

            withContext(Dispatchers.Main) {
                targetAdapter.submitList(items)
            }
        }
    }

    /**
     * Extracts the target's device name, computes its equivalent power tier index, 
     * and maps it to an upload difficulty penalty. (Target's network speed only affects their own
     * downloads, while target's CPU/Device governs how fast attackers can crack/upload to them).
     * 
     * Base Devices:
     * - Raider: 1
     * - Bolt: 4
     * - Nova: 7
     * 
     * Special Devices (Mirror standard progression, offset by numeral):
     * - Vector: Matches Bolt 1 (Power 4)
     * - Scythe X: Scythe 1 = Raider 1 (Power 1). Thus Scythe IV = Power 4.
     */
    private fun getHardwarePenalty(hardware: String?): Double {
        if (hardware == null) return 0.0
        val deviceStr = hardware.substringBefore(" • ").trim().lowercase()
        
        var basePower = 0.0
        
        when {
            deviceStr.contains("scythe") -> {
                // Scythe 1 is equivalent to Raider 1 (Power 1).
                basePower = 1.0
            }
            deviceStr.contains("raider") -> basePower = 1.0
            deviceStr.contains("vector") -> basePower = 4.0 // Equivalent to Bolt
            deviceStr.contains("bolt") -> basePower = 4.0
            deviceStr.contains("nova") -> {
                basePower = 7.0
                if (deviceStr.contains(" s")) basePower += 3.0 // Nova S
                if (deviceStr.contains(" x")) basePower += 4.0 // Nova X
            }
        }
        
        // Add modifier for roman numeral iterations (II, III, IV, etc.)
        val romanNumeralMap = mapOf(
            " ii" to 1.0,
            " iii" to 2.0,
            " iv" to 3.0,
            " v" to 4.0,
            " vi" to 5.0,
            " vii" to 6.0,
            " viii" to 7.0,
            " ix" to 8.0,
            " x" to 9.0
        )
        
        for ((numeral, power) in romanNumeralMap) {
            // Match the numeral exactly at the end of the string
            // Skip applying the " x" modifier again if we already applied it specifically to Nova
            if (deviceStr.endsWith(numeral)) {
                if (deviceStr.contains("nova x") && numeral == " x") {
                    // Already handled in the base block (+4 for Nova X vs Nova)
                    break
                }
                basePower += power
                break
            }
        }

        // We only penalize target's CPU/Device speed, as their Network speed doesn't affect our uploads to them.
        return basePower
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    override fun onStop() {
        super.onStop()
    }

    /**
     * Checks if overlay and accessibility permissions are granted, prompting user via a dialog if needed.
     */
    private fun showPermissionDialog(force: Boolean) {
        val hasOverlay = Settings.canDrawOverlays(this)
        val hasAccessibility = isAccessibilityServiceEnabled()

        if (hasOverlay && hasAccessibility) {
            startService(Intent(this, OverlayService::class.java))
            return
        }

        val prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val hideWarning = prefs.getBoolean("hide_permission_warning", false)

        if (!force && hideWarning) {
            return
        }

        val builder = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Scraper Permissions Required")
            .setMessage("To use the on-screen scraping overlay and validation tools, HackExLogger requires the 'Display over other apps' and 'Accessibility' permissions.\n\nYou can skip this if you only want to view or import data.")
            .setPositiveButton("Enable") { _, _ ->
                if (!hasOverlay) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                } else {
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    startActivity(intent)
                }
            }
            .setNegativeButton("Not Now", null)

        if (!force) {
            builder.setNeutralButton("Don't Ask Again") { _, _ ->
                prefs.edit().putBoolean("hide_permission_warning", true).apply()
            }
        }

        builder.show()
    }

    /**
     * Helper method to check if the app's [ScraperAccessibilityService] is enabled in Android settings.
     */
    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponentName = ComponentName(this, ScraperAccessibilityService::class.java)
        val enabledServicesSetting = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val stringizer = TextUtils.SimpleStringSplitter(':')
        stringizer.setString(enabledServicesSetting)
        while (stringizer.hasNext()) {
            val componentNameString = stringizer.next()
            val enabledComponent = ComponentName.unflattenFromString(componentNameString)
            if (enabledComponent != null && enabledComponent == expectedComponentName) {
                return true
            }
        }
        return false
    }

    private fun onEditTarget(target: TargetRecord) {
        val input = EditText(this)
        input.setText(target.ip)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Edit IP")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newIp = input.text.toString().trim()
                if (newIp.isNotEmpty() && newIp != target.ip) {
                    DatabaseManager.editTargetIp(target.ip, newIp)
                    refreshData()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun onDeleteTarget(target: TargetRecord) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Delete Target")
            .setMessage("Are you sure you want to delete ${target.ip}?")
            .setPositiveButton("Delete") { _, _ ->
                DatabaseManager.deleteTarget(target.ip)
                refreshData()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}