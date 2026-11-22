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
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.switchmaterial.SwitchMaterial
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
    private var currentRecords: List<TargetRecord> = emptyList()
    private var currentSearchQuery = ""
    private var isFullVisible = true
    private var isPartialVisible = false
    private var isGoodSpamEnabled = false
    private var isReconModeEnabled = false
    private var maxFirewallLevel = 20
    private var searchJob: Job? = null
    private var expectingPermissionResult = false

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

    /**
     * Wipe Database is irreversible, so it needs a typed confirmation.
     * The Wipe button stays disabled until the user types WIPE; "Export first" opens the normal export.
     */
    private fun confirmWipeDatabase() {
        val recordCount = DatabaseManager.getAllRecords().size
        val pad = (20 * resources.displayMetrics.density).toInt()
        val input = EditText(this).apply {
            hint = "Type WIPE to confirm"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }
        val container = android.widget.FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Wipe Database?")
            .setMessage(
                "This permanently deletes all $recordCount target records from the app.\n\n" +
                "It can't be undone. Export your data first if you might want it back."
            )
            .setView(container)
            .setPositiveButton("Wipe", null)
            .setNeutralButton("Export first") { _, _ -> openExportPicker() }
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            val wipeButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            wipeButton.isEnabled = false
            input.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    wipeButton.isEnabled = s?.toString()?.trim() == "WIPE"
                }
            })
            wipeButton.setOnClickListener {
                DatabaseManager.wipeDatabase()
                refreshData()
                Toast.makeText(this, "Database wiped", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun openExportPicker() {
        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "target_console_$dateStr.json")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val initialUri = Uri.parse("content://com.android.externalstorage.documents/document/primary:Download%2FDarkIndex")
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
                val initialUri = Uri.parse("content://com.android.externalstorage.documents/document/primary:Download%2FDarkIndex")
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
            DatabaseManager.recordsFlow.collect { records ->
                currentRecords = records
                refreshData()
            }
        }

        showPermissionDialog(force = false)
    }

    override fun onResume() {
        super.onResume()
        
        val prefs = getSharedPreferences("HackExSettings", MODE_PRIVATE)
        isReconModeEnabled = prefs.getBoolean("pref_advanced_targeting", false)
        val validateBtn = findViewById<ExtendedFloatingActionButton>(R.id.validateBtn)
        validateBtn.text = if (isReconModeEnabled) "RECON" else "VALIDATE"
        validateBtn.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor(if (isReconModeEnabled) "#f59e0b" else "#16a34a"))
        validateBtn.setIconResource(if (isReconModeEnabled) android.R.drawable.ic_menu_search else android.R.drawable.ic_menu_crop)

        // Raw Dump Mode (debug): swap the Siphon Alerts icon for the Raw Dumps icon
        val rawDumpMode = RawDumpSettings.isEnabled(this)
        findViewById<ImageButton>(R.id.siphonAlertsBtn).visibility = if (rawDumpMode) View.GONE else View.VISIBLE
        findViewById<ImageButton>(R.id.rawDumpsBtn).visibility = if (rawDumpMode) View.VISIBLE else View.GONE

        updateSearchAvailability()
        updateLevelBanner()
        refreshData()
        if (Settings.canDrawOverlays(this) && isAccessibilityServiceEnabled()) {
            startService(Intent(this, OverlayService::class.java))
        } else if (expectingPermissionResult) {
            expectingPermissionResult = false
            showPermissionDialog(force = true)
        }
    }

    private fun setupUI() {
        targetAdapter = TargetAdapter(this::onEditTarget, this::onDeleteTarget, this::onToggleFull, this::onTogglePartial)
        findViewById<RecyclerView>(R.id.targetsRecycler).adapter = targetAdapter

        val validateBtn = findViewById<ExtendedFloatingActionButton>(R.id.validateBtn)

        // Validation / Recon mode toggle button
        validateBtn.setOnClickListener {
            if (Settings.canDrawOverlays(this) && isAccessibilityServiceEnabled()) {
                val intent = Intent(this, OverlayService::class.java).apply {
                    action = if (isReconModeEnabled) OverlayService.ACTION_TOGGLE_ADVANCED_TARGETING else OverlayService.ACTION_TOGGLE_VALIDATION
                }
                startService(intent)
            } else {
                showPermissionDialog(force = true)
            }
        }

        // Marathon Mode: guided timed steal run → income ranking (MarathonOverlay)
        findViewById<ExtendedFloatingActionButton>(R.id.marathonBtn).setOnClickListener {
            if (!(Settings.canDrawOverlays(this) && isAccessibilityServiceEnabled())) {
                showPermissionDialog(force = true)
                return@setOnClickListener
            }
            if (Marathon.isActive) {
                AlertDialog.Builder(this)
                    .setTitle("Marathon running")
                    .setMessage("Run ${Marathon.runNumber} is in progress. Use the 🏃 bar over the game, or end the marathon here.")
                    .setPositiveButton("Keep going", null)
                    .setNegativeButton("End marathon") { _, _ -> Marathon.end() }
                    .show()
                return@setOnClickListener
            }
            val options = Marathon.DURATIONS_MINUTES
            AlertDialog.Builder(this)
                .setTitle("Marathon: how long is the timed run?")
                .setItems(options.map { "$it minutes" }.toTypedArray()) { _, which ->
                    startService(Intent(this, OverlayService::class.java).apply {
                        action = OverlayService.ACTION_START_MARATHON
                        putExtra(OverlayService.EXTRA_MARATHON_MINUTES, options[which])
                    })
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // Knowledge Base
        findViewById<ImageButton>(R.id.knowledgeBaseBtn).setOnClickListener {
            startActivity(Intent(this, KnowledgeBaseActivity::class.java))
        }

        // Siphon Alerts menu
        findViewById<ImageButton>(R.id.siphonAlertsBtn).setOnClickListener {
            val intent = Intent(this, SiphonAlertsActivity::class.java)
            startActivity(intent)
        }

        // Raw Dumps (debug) — only visible while Raw Dump Mode is on
        findViewById<ImageButton>(R.id.rawDumpsBtn).setOnClickListener {
            startActivity(Intent(this, RawDumpsActivity::class.java))
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
                                "• ${result.maskedWalletsMigrated} masked wallets migrated to placeholders"
                            )
                            .setPositiveButton("OK", null)
                            .show()
                        true
                    }
                    4 -> {
                        confirmWipeDatabase()
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

        // Recon Mode Setup
        val reconModeCheckBox = findViewById<SwitchMaterial>(R.id.reconModeCheckBox)
        reconModeCheckBox.isChecked = isReconModeEnabled
        reconModeCheckBox.setOnCheckedChangeListener { _, isChecked ->
            isReconModeEnabled = isChecked
            getSharedPreferences("HackExSettings", MODE_PRIVATE).edit().putBoolean("pref_advanced_targeting", isChecked).apply()
            val validateBtn = findViewById<ExtendedFloatingActionButton>(R.id.validateBtn)
            validateBtn.text = if (isChecked) "RECON" else "VALIDATE"
            validateBtn.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor(if (isChecked) "#f59e0b" else "#16a34a"))
            validateBtn.setIconResource(if (isChecked) android.R.drawable.ic_menu_search else android.R.drawable.ic_menu_crop)
            forceFastSwap = true
            updateSearchAvailability()
            refreshData()
        }

        // Spam Priority Filter Setup
        val goodSpamCheckBox = findViewById<SwitchMaterial>(R.id.goodSpamCheckBox)
        val firewallSliderLayout = findViewById<View>(R.id.firewallSliderLayout)

        goodSpamCheckBox.setOnCheckedChangeListener { _, isChecked ->
            isGoodSpamEnabled = isChecked
            forceFastSwap = true
            firewallSliderLayout.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (isChecked) {
                val playerBypasser = PlayerProfileRepository.getEffectiveBypasserLevel()
                maxFirewallLevel = playerBypasser
                val maxFirewallSeekBar = findViewById<SeekBar>(R.id.maxFirewallSeekBar)
                val maxFirewallValue = findViewById<TextView>(R.id.maxFirewallValue)
                maxFirewallSeekBar.progress = playerBypasser.coerceIn(0, 31)
                maxFirewallValue.text = if (playerBypasser >= 31) "Uncapped" else playerBypasser.toString()
            }
            updateSearchAvailability()
            refreshData()
        }

        val maxFirewallSeekBar = findViewById<SeekBar>(R.id.maxFirewallSeekBar)
        val maxFirewallValue = findViewById<TextView>(R.id.maxFirewallValue)

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
    }

    private var forceFastSwap = false

    /** Search doesn't combine with Recon or Spam Priority (they show their own lists), so hide and clear it while either is on. */
    private fun updateSearchAvailability() {
        val searchLayout = findViewById<View>(R.id.searchLayout)
        val searchInput = findViewById<EditText>(R.id.searchInput)
        if (isReconModeEnabled || isGoodSpamEnabled) {
            if (!searchInput.text.isNullOrEmpty()) searchInput.setText("")
            currentSearchQuery = ""
            searchLayout.visibility = View.GONE
        } else {
            searchLayout.visibility = View.VISIBLE
        }
    }

    /** Warns while your own level is unknown (e.g. after a reinstall), since Spam advice depends on it. */
    private fun updateLevelBanner() {
        val unknown = PlayerProfileRepository.getProfile().playerLevel == null
        findViewById<View>(R.id.levelUnknownBanner).visibility = if (unknown) View.VISIBLE else View.GONE
    }

    private fun onToggleFull() {
        isFullVisible = !isFullVisible
        forceFastSwap = true
        refreshData()
    }

    private fun onTogglePartial() {
        isPartialVisible = !isPartialVisible
        forceFastSwap = true
        refreshData()
    }

    /**
     * Filters stored target records based on search criteria and posts the formatted list to RecyclerView.
     */
    private fun refreshData() {
        lifecycleScope.launch(Dispatchers.Default) {
            val allRecords = currentRecords
            val prefs = getSharedPreferences("HackExSettings", MODE_PRIVATE)
            val isAdvancedTargetingMode = prefs.getBoolean("pref_advanced_targeting", false)
            
            var filtered = if (isAdvancedTargetingMode) {
                allRecords.filter { it.needsAdvancedTargeting }
            } else {
                allRecords
            }

            // Search results come back best first (see TargetSearch); keep that order below
            val searchActive = currentSearchQuery.isNotEmpty()
            if (searchActive) {
                filtered = TargetSearch.search(allRecords, currentSearchQuery) // search ignores the Recon filter, as before
            }

            if (isReconModeEnabled && !isGoodSpamEnabled && currentSearchQuery.isEmpty()) {
                filtered = filtered.filter { it.needsAdvancedTargeting }
            }

            if (isGoodSpamEnabled) {
                val currentNetwork = PlayerProfileRepository.getCurrentNetwork()
                val installedSpam = PlayerProfileRepository.getInstalledSpamLevel()
                // Capping targets first, then the fastest upload (weakest device), then lowest Firewall
                filtered = GoodSpamUtils.rankForSpam(
                    filtered.filter { record -> GoodSpamUtils.isEligible(record.firewall, maxFirewallLevel) },
                    currentNetwork, installedSpam
                )
            }

            val fullTargets = if (isGoodSpamEnabled || searchActive) {
                filtered.filter { !it.isMasked } // already sorted
            } else {
                filtered.filter { !it.isMasked }.sortedBy { it.ip }
            }
            
            val partialTargets = if (isGoodSpamEnabled || searchActive) {
                filtered.filter { it.isMasked } // already sorted
            } else {
                filtered.filter { it.isMasked }.sortedBy { it.ip }
            }

            val items = mutableListOf<ListItem>()

            val fullHeaderTitle = if (isFullVisible) {
                "▲ FULL TARGETS (${fullTargets.size})"
            } else {
                "▼ FULL TARGETS (${fullTargets.size})"
            }
            items.add(ListItem.Header(fullHeaderTitle, isPartialHeader = false, isExpanded = isFullVisible))
            if (isFullVisible) {
                fullTargets.forEach { items.add(ListItem.Target(it)) }
            }

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
                val recycler = findViewById<RecyclerView>(R.id.targetsRecycler)
                val emptyView = findViewById<View>(R.id.emptyStateView)
                val emptyText = findViewById<TextView>(R.id.emptyStateText)

                if (items.isEmpty()) {
                    recycler.visibility = View.GONE
                    emptyView.visibility = View.VISIBLE
                    if (currentSearchQuery.isNotEmpty()) {
                        emptyText.text = "No targets match your search criteria."
                    } else if (isGoodSpamEnabled) {
                        emptyText.text = "No targets found matching the Good Spam filter criteria."
                    } else {
                        emptyText.text = "No targets captured yet.\n\nTap the 📸 overlay to scan your HackEx 2 game screens!"
                    }
                } else {
                    recycler.visibility = View.VISIBLE
                    emptyView.visibility = View.GONE
                }

                if (forceFastSwap) {
                    // Bypass expensive O(N^2) DiffUtil calculation on massive list re-sorts/filters
                    targetAdapter.submitList(null)
                    forceFastSwap = false
                }
                targetAdapter.submitList(items)
            }
        }
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
        
        if (!hasOverlay) {
            builder.setTitle("Step 1: Overlay Permission")
            builder.setMessage("To use the on-screen scraping overlay, DarkIndex requires the 'Display over other apps' permission.")
            builder.setPositiveButton("Grant Overlay") { _, _ ->
                expectingPermissionResult = true
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            }
            if (!force) {
                builder.setNeutralButton("Don't Ask Again") { _, _ ->
                    prefs.edit().putBoolean("hide_permission_warning", true).apply()
                }
            }
        } else if (!hasAccessibility) {
            if (Build.VERSION.SDK_INT >= 33) {
                builder.setTitle("Step 2: Android 13+ Security Unlock")
                builder.setMessage("Android blocks sideloaded accessibility apps by default. You MUST follow these exact steps:\n\n1. Tap '1. Accessibility' below, find DarkIndex, and try to turn it on. Android will block you with a 'Restricted setting' popup. Hit OK and come back.\n\n2. Tap '2. App Info' below, tap the 3 dots (⋮) in the top-right corner, and select 'Allow restricted settings'. Come back.\n\n3. Tap '1. Accessibility' one last time to successfully turn it on.")
                
                builder.setPositiveButton("1. Accessibility") { _, _ ->
                    expectingPermissionResult = true
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                builder.setNeutralButton("2. App Info") { _, _ ->
                    expectingPermissionResult = true
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                }
            } else {
                builder.setTitle("Step 2: Accessibility Permission")
                builder.setMessage("DarkIndex requires the 'Accessibility' permission to read the game screen.")
                builder.setPositiveButton("Grant Accessibility") { _, _ ->
                    expectingPermissionResult = true
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                if (!force) {
                    builder.setNeutralButton("Don't Ask Again") { _, _ ->
                        prefs.edit().putBoolean("hide_permission_warning", true).apply()
                    }
                }
            }
        }

        builder.setNegativeButton(if (force) "Cancel" else "Not Now", null)
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