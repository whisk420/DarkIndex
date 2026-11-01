package com.whisk.hackexlogger

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

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
    private fun scrapeAndParse() {
        Log.d("DOSSIER", "scrapeAndParse() called. Attempting to get root window...")
        val rootNode = rootInActiveWindow
        if (rootNode == null) {
            Log.e("DOSSIER", "ERROR: rootInActiveWindow is null. Cannot scrape.")
            return
        }

        val extractedTextList = mutableListOf<String>()
        extractText(rootNode, extractedTextList)

        val combinedText = extractedTextList.joinToString("\n")
        Log.d("DOSSIER", "Scraped ${extractedTextList.size} text nodes.")
        Log.d("DOSSIER_RAW", "--- RAW SCRAPED TEXT START ---\n$combinedText\n--- RAW SCRAPED TEXT END ---")
        
        // Split extracted UI text into discrete lines for log parsing
        val allLines = combinedText.split(Regex("""\r?\n"""))

        val savedItems = mutableListOf<String>()

        // Attempt parsing for HackEx Home/Profile screen format
        val homeScreen = HackExParser.parseHomeScreen(combinedText)
        if (homeScreen != null) {
            Log.d("DOSSIER", "Parsed HomeScreen: $homeScreen")
            DatabaseManager.processHomeScreen(homeScreen)
            savedItems.add("Target Profile")
        }

        // Attempt parsing for HackEx Software screen format
        val softwareScreen = HackExParser.parseSoftwareScreen(combinedText)
        if (softwareScreen != null) {
            Log.d("DOSSIER", "Parsed SoftwareScreen: $softwareScreen")
            DatabaseManager.processSoftwareScreen(softwareScreen)
            savedItems.add("Software Inventory")
        }

        // Attempt parsing for HackEx Wallet screen format
        val walletScreen = HackExParser.parseWalletScreen(combinedText)
        if (walletScreen != null) {
            Log.d("DOSSIER", "Parsed WalletScreen: $walletScreen")
            DatabaseManager.processWalletScreen(walletScreen)
            savedItems.add("Wallet")
        }

        // Attempt parsing for Game Activity Log screens
        val logUpdates = HackExParser.parseLogs(allLines)
        if (logUpdates.isNotEmpty()) {
            Log.d("DOSSIER", "Parsed ${logUpdates.size} Log Updates")
            DatabaseManager.processUpdates(logUpdates)
            savedItems.add("${logUpdates.size} Log Events")
        }

        // Attempt parsing for HackEx Siphon screen format
        val siphonScreen = HackExParser.parseSiphonScreen(combinedText)
        if (siphonScreen != null) {
            Log.d("DOSSIER", "Parsed SiphonScreen: $siphonScreen")
            DatabaseManager.processSiphonScreen(siphonScreen)
            savedItems.add("Siphons")
        }

        // Attempt parsing for HackEx Crew screen format
        val crewScreen = HackExParser.parseCrewScreen(allLines)
        if (crewScreen != null) {
            Log.d("DOSSIER", "Parsed CrewScreen: $crewScreen")
            DatabaseManager.processCrewScreen(crewScreen)
            savedItems.add("Crew")
        }

        // Attempt parsing for HackEx Process screen format
        val processScreen = HackExParser.parseProcessScreen(allLines)
        if (processScreen != null) {
            Log.d("DOSSIER", "Parsed ProcessScreen: $processScreen")
            DatabaseManager.processProcessScreen(processScreen)
            savedItems.add("Processes")
        }
        
        if (savedItems.isNotEmpty()) {
            val toastIntent = Intent("com.whisk.hackexlogger.SHOW_TOAST")
            toastIntent.setPackage(packageName)
            toastIntent.putExtra("message", "Saved: " + savedItems.joinToString(", "))
            sendBroadcast(toastIntent)
        } else {
            val toastIntent = Intent("com.whisk.hackexlogger.SHOW_TOAST")
            toastIntent.setPackage(packageName)
            toastIntent.putExtra("message", "Nothing found to save")
            sendBroadcast(toastIntent)
        }
        
        // Notify MainActivity to update the target list UI
        Log.d("DOSSIER", "Sending DATA_UPDATED broadcast to UI")
        val updateIntent = Intent("com.whisk.hackexlogger.DATA_UPDATED")
        updateIntent.setPackage(packageName)
        sendBroadcast(updateIntent)
    }

    /**
     * Recursively traverses accessibility node trees to gather all rendered text strings.
     */
    private fun extractText(node: AccessibilityNodeInfo, list: MutableList<String>) {
        if (node.text != null) {
            list.add(node.text.toString())
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                extractText(child, list)
            }
        }
    }

    companion object {
        const val ACTION_TRIGGER_SCRAPE = "com.whisk.hackexlogger.TRIGGER_SCRAPE"
    }
}