package com.whisk.hackexlogger

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * On-demand UI screen reader service utilizing Android's Accessibility APIs.
 *
 * This service is used strictly as a helper tool for the HackEx game. When explicit scraping
 * is requested by the user (via the floating overlay button), it reads the current on-screen text
 * nodes from the active window, parses HackEx game elements (such as target IPs, software levels,
 * and game logs), updates the local on-device database, and notifies the UI.
 *
 * Passive accessibility event tracking is ignored; execution only occurs on manual user trigger.
 */
class ScraperAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(Dispatchers.Default)

    private val triggerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.d("DOSSIER", "Broadcast received in AccessibilityService! Action: ${intent.action}")
            if (intent.action == ACTION_TRIGGER_SCRAPE) {
                scrapeAndParse()
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d("DOSSIER", "Accessibility Service successfully Connected to OS")
        val filter = IntentFilter(ACTION_TRIGGER_SCRAPE)
        androidx.core.content.ContextCompat.registerReceiver(
            this,
            triggerReceiver,
            filter,
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        unregisterReceiver(triggerReceiver)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Passive accessibility events are intentionally ignored to save resources;
        // scraping is triggered strictly on-demand by the user via local broadcast.
    }

    override fun onInterrupt() {
    }

    /**
     * Inspects the active window node hierarchy, extracts visible text, and parses game entity details.
     */
    fun scrapeAndParse() {
        val totalStartNanos = System.nanoTime()
        Log.d("DOSSIER", "scrapeAndParse() called. Attempting to get root window...")
        val rootNode = rootInActiveWindow
        if (rootNode == null) {
            Log.e("DOSSIER", "ERROR: rootInActiveWindow is null. Cannot scrape.")
            return
        }

        // IMMEDIATELY traverse the node tree on the main thread to capture the exact current screen state.
        // Offloading tree traversal to a background thread causes race conditions if the user navigates away quickly.
        val extractStartNanos = System.nanoTime()
        val extractedTextList = mutableListOf<String>()
        try {
            extractText(rootNode, extractedTextList)
        } catch (e: Exception) {
            Log.e("DOSSIER", "Error extracting text nodes: ${e.message}", e)
        }
        val extractDurationMs = (System.nanoTime() - extractStartNanos) / 1_000_000.0

        if (extractedTextList.isEmpty()) {
            val toastIntent = Intent("com.whisk.hackexlogger.SHOW_TOAST").apply {
                setPackage(packageName)
                putExtra("message", "Nothing found to save")
            }
            sendBroadcast(toastIntent)
            return
        }

        val combinedText = extractedTextList.joinToString("\n")
        Log.i("DOSSIER_RAW", "--- RAW SCRAPED TEXT START ---\n$combinedText\n--- RAW SCRAPED TEXT END ---")

        // Split extracted UI text into discrete lines for log parsing
        val allLines = combinedText.split(Regex("""\r?\n"""))

        // Offload ALL Regex parsing and storage to background thread
        serviceScope.launch(Dispatchers.Default) {
            val savedItems = mutableListOf<String>()

            // 1. Benchmark Screen Type Detection
            val detectStartNanos = System.nanoTime()
            val detectedType = ScreenClassifier.detectScreenType(combinedText, allLines)
            val detectDurationMs = (System.nanoTime() - detectStartNanos) / 1_000_000.0

            // 2. Benchmark Regex Parsing & Database Processing
            val parseStartNanos = System.nanoTime()
            when (detectedType) {
                ScreenType.HOME -> {
                    val homeScreen = HomeScreenParser.parse(combinedText)
                    if (homeScreen != null) {
                        Log.d("DOSSIER", "Parsed HomeScreen: $homeScreen")
                        DatabaseManager.processHomeScreen(homeScreen)
                        savedItems.add("Target Profile")
                    }
                }
                ScreenType.SOFTWARE -> {
                    val softwareScreen = SoftwareScreenParser.parse(combinedText)
                    if (softwareScreen != null) {
                        Log.d("DOSSIER", "Parsed SoftwareScreen: $softwareScreen")
                        DatabaseManager.processSoftwareScreen(softwareScreen)
                        savedItems.add("Software Inventory")
                    }
                }
                ScreenType.WALLET -> {
                    val walletScreen = WalletScreenParser.parse(combinedText)
                    if (walletScreen != null) {
                        Log.d("DOSSIER", "Parsed WalletScreen: $walletScreen")
                        DatabaseManager.processWalletScreen(walletScreen)
                        savedItems.add("Wallet")
                    }
                }
                ScreenType.LOGS -> {
                    val logUpdates = LogScreenParser.parse(allLines)
                    if (logUpdates.isNotEmpty()) {
                        Log.d("DOSSIER", "Parsed ${logUpdates.size} Log Updates")
                        DatabaseManager.processUpdates(logUpdates)
                        savedItems.add("${logUpdates.size} Log Events")
                    }
                }
                ScreenType.SIPHON -> {
                    val siphonScreen = SiphonScreenParser.parse(combinedText)
                    if (siphonScreen != null) {
                        Log.d("DOSSIER", "Parsed SiphonScreen: $siphonScreen")
                        DatabaseManager.processSiphonScreen(siphonScreen)
                        savedItems.add("Siphons")
                    }
                }
                ScreenType.CREW -> {
                    val crewScreen = CrewScreenParser.parse(allLines)
                    if (crewScreen != null) {
                        Log.d("DOSSIER", "Parsed CrewScreen: $crewScreen")
                        DatabaseManager.processCrewScreen(crewScreen)
                        savedItems.add("Crew")
                    }
                }
                ScreenType.PROCESS -> {
                    val processScreen = ProcessScreenParser.parse(allLines)
                    if (processScreen != null) {
                        Log.d("DOSSIER", "Parsed ProcessScreen: $processScreen")
                        DatabaseManager.processProcessScreen(processScreen)
                        savedItems.add("Processes")
                    }
                }
                ScreenType.UNKNOWN -> {
                    // Fallback to evaluating all parsers if screen type signature was ambiguous
                    parseAllScreensFallback(combinedText, allLines, savedItems)
                }
            }
            val parseDurationMs = (System.nanoTime() - parseStartNanos) / 1_000_000.0
            val totalPipelineMs = (System.nanoTime() - totalStartNanos) / 1_000_000.0

            // Output Precision Benchmark Report to Logcat
            Log.i("DOSSIER_BENCHMARK", String.format(Locale.US, "⏱️ [PERFORMANCE BENCHMARK] Screen: %s", detectedType))
            Log.i("DOSSIER_BENCHMARK", String.format(Locale.US, "├─ Node Extraction: %.2f ms (%d text nodes)", extractDurationMs, extractedTextList.size))
            Log.i("DOSSIER_BENCHMARK", String.format(Locale.US, "├─ Screen Type Detection: %.2f ms", detectDurationMs))
            Log.i("DOSSIER_BENCHMARK", String.format(Locale.US, "├─ Parsing & Database Processing: %.2f ms", parseDurationMs))
            Log.i("DOSSIER_BENCHMARK", String.format(Locale.US, "└─ TOTAL PIPELINE TIME: %.2f ms", totalPipelineMs))

            val toastMessage = if (savedItems.isNotEmpty()) {
                "Saved: " + savedItems.joinToString(", ")
            } else {
                "Nothing found to save"
            }

            val toastIntent = Intent("com.whisk.hackexlogger.SHOW_TOAST").apply {
                setPackage(packageName)
                putExtra("message", toastMessage)
            }
            sendBroadcast(toastIntent)

            Log.d("DOSSIER", "Sending DATA_UPDATED broadcast to UI")
            val updateIntent = Intent("com.whisk.hackexlogger.DATA_UPDATED").apply {
                setPackage(packageName)
            }
            sendBroadcast(updateIntent)
        }
    }

    private fun parseAllScreensFallback(combinedText: String, allLines: List<String>, savedItems: MutableList<String>) {
        val homeScreen = HomeScreenParser.parse(combinedText)
        if (homeScreen != null) {
            DatabaseManager.processHomeScreen(homeScreen)
            savedItems.add("Target Profile")
        }
        val softwareScreen = SoftwareScreenParser.parse(combinedText)
        if (softwareScreen != null) {
            DatabaseManager.processSoftwareScreen(softwareScreen)
            savedItems.add("Software Inventory")
        }
        val walletScreen = WalletScreenParser.parse(combinedText)
        if (walletScreen != null) {
            DatabaseManager.processWalletScreen(walletScreen)
            savedItems.add("Wallet")
        }
        val logUpdates = LogScreenParser.parse(allLines)
        if (logUpdates.isNotEmpty()) {
            DatabaseManager.processUpdates(logUpdates)
            savedItems.add("${logUpdates.size} Log Events")
        }
        val siphonScreen = SiphonScreenParser.parse(combinedText)
        if (siphonScreen != null) {
            DatabaseManager.processSiphonScreen(siphonScreen)
            savedItems.add("Siphons")
        }
        val crewScreen = CrewScreenParser.parse(allLines)
        if (crewScreen != null) {
            DatabaseManager.processCrewScreen(crewScreen)
            savedItems.add("Crew")
        }
        val processScreen = ProcessScreenParser.parse(allLines)
        if (processScreen != null) {
            DatabaseManager.processProcessScreen(processScreen)
            savedItems.add("Processes")
        }
    }

    /**
     * Recursively traverses accessibility node trees to gather all rendered text strings.
     */
    private fun extractText(node: AccessibilityNodeInfo?, list: MutableList<String>) {
        if (node == null) return

        val text = node.text
        if (!text.isNullOrEmpty()) {
            list.add(text.toString())
        }

        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = node.getChild(i)
            if (child != null) {
                extractText(child, list)
            }
        }
    }

    companion object {
        const val ACTION_TRIGGER_SCRAPE = "com.whisk.hackexlogger.TRIGGER_SCRAPE"
        var instance: ScraperAccessibilityService? = null
    }
}
