package com.whisk.hackexlogger

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import android.widget.EditText
import android.widget.ImageButton
import androidx.appcompat.app.AlertDialog
import androidx.activity.enableEdgeToEdge
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

/**
 * Main Activity dashboard for HackExLogger.
 *
 * The target list: search, Spam Priority and Recon switches, the firewall slider, and the per-card edit/delete.
 * The ⋮ menu (export, import, maintenance, wipe) is in [DashboardDataActions]; the permission walkthrough is in
 * [PermissionOnboarding].
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
    private val dataActions = DashboardDataActions(this) { refreshData() }
    private val permissions = PermissionOnboarding(this)

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

        permissions.showDialog(force = false)
        showLoadReport()
    }

    /** Tells the developer once if loading the database had to set anything aside (nothing is deleted). */
    private fun showLoadReport() {
        val report = TargetRepository.loadReport ?: return
        TargetRepository.loadReport = null
        AlertDialog.Builder(this)
            .setTitle("Database loaded with problems")
            .setMessage("$report\n\nThe files are in the app's private storage; nothing was deleted.")
            .setPositiveButton("OK", null)
            .show()
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
        if (permissions.hasAll()) {
            startService(Intent(this, OverlayService::class.java))
        } else if (permissions.expectingResult) {
            permissions.expectingResult = false
            permissions.showDialog(force = true)
        }
    }

    private fun setupUI() {
        targetAdapter = TargetAdapter(this::onEditTarget, this::onDeleteTarget, this::onToggleFull, this::onTogglePartial)
        findViewById<RecyclerView>(R.id.targetsRecycler).adapter = targetAdapter

        val validateBtn = findViewById<ExtendedFloatingActionButton>(R.id.validateBtn)

        // Validation / Recon mode toggle button
        validateBtn.setOnClickListener {
            if (permissions.hasAll()) {
                val intent = Intent(this, OverlayService::class.java).apply {
                    action = if (isReconModeEnabled) OverlayService.ACTION_TOGGLE_ADVANCED_TARGETING else OverlayService.ACTION_TOGGLE_VALIDATION
                }
                startService(intent)
            } else {
                permissions.showDialog(force = true)
            }
        }

        // Marathon Mode: guided timed steal run → income ranking (MarathonOverlay)
        findViewById<ExtendedFloatingActionButton>(R.id.marathonBtn).setOnClickListener {
            if (!(permissions.hasAll())) {
                permissions.showDialog(force = true)
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

        // ⋮ menu: Settings, Export, Import, Maintenance, Wipe, Reset Siphon Tracker
        findViewById<ImageButton>(R.id.settingsBtn).setOnClickListener { view -> dataActions.showMenu(view) }

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



    override fun onStop() {
        super.onStop()
        // Don't leave the Downloads backup waiting on its timer while the app is in the background
        TargetRepository.flushBackupSoon()
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