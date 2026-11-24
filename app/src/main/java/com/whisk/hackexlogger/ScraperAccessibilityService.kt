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
 * This service is used strictly as a helper tool for the HackEx game, and reads nothing from any other app
 * ([ScrapeGate]: the active window must be HackEx 2, checked before any text is read). When explicit scraping
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
     * When Raw Dump Mode is on, the same scrape is also recorded as a dump (see `RawDump.kt`).
     */
    fun scrapeAndParse() {
        val totalStartNanos = System.nanoTime()

        Log.d("DOSSIER", "scrapeAndParse() called. Attempting to get root window...")
        val rootNode = rootInActiveWindow
        // Privacy gate: read nothing unless the active window is HackEx 2. Checked before any text is read,
        // and before a Raw Dump is started, so another app's screen is never parsed, logged or stored.
        if (rootNode != null && !ScrapeGate.isGameWindow(rootNode.packageName)) {
            sendToast(ScrapeGate.NOT_GAME_MESSAGE)
            return
        }
        val dump: RawDump? = if (RawDumpSettings.isEnabled(this)) RawDump.create(app = RawDumpCapture.appInfo(this)) else null
        if (rootNode == null) {
            Log.e("DOSSIER", "ERROR: rootInActiveWindow is null. Cannot scrape.")
            if (dump != null) {
                dump.error = "rootInActiveWindow was null (no readable window)"
                dump.result = "Nothing scraped"
                saveDumpAndToast(dump, "Nothing scraped")
            }
            return
        }
        dump?.foregroundPackage = rootNode.packageName?.toString()

        // IMMEDIATELY traverse the node tree on the main thread to capture the exact current screen state.
        // Offloading tree traversal to a background thread causes race conditions if the user navigates away quickly.
        val extractStartNanos = System.nanoTime()
        val extractedTextList = mutableListOf<String>()
        try {
            extractText(rootNode, extractedTextList)
        } catch (e: Exception) {
            Log.e("DOSSIER", "Error extracting text nodes: ${e.message}", e)
            dump?.error = "extractText failed: ${e.stackTraceToString().take(4000)}"
        }
        val extractDurationMs = (System.nanoTime() - extractStartNanos) / 1_000_000.0

        if (dump != null) {
            try {
                dump.nodeTree = RawDumpCapture.nodeTree(rootNode)
            } catch (e: Exception) {
                dump.error = (dump.error?.plus("\n") ?: "") + "nodeTree capture failed: ${e.message}"
            }
            dump.timing.extractMs = RawDump.roundMs(extractDurationMs)
            dump.timing.nodeCount = dump.nodeTree.size
        }

        if (extractedTextList.isEmpty()) {
            if (dump != null) {
                dump.result = "Nothing found to save"
                saveDumpAndToast(dump, "Nothing found to save")
            } else {
                sendToast("Nothing found to save")
            }
            return
        }

        // The screen text is never written to Logcat. Raw Dump Mode (opt-in, app-private storage) records it instead.
        val combinedText = extractedTextList.joinToString("\n")
        dump?.rawText = combinedText

        // Split extracted UI text into discrete lines for log parsing
        val allLines = combinedText.split(Regex("""\r?\n"""))

        // Offload ALL Regex parsing and storage to background thread
        serviceScope.launch(Dispatchers.Default) {
            var result: ScrapePipeline.Result? = null
            var failure: Throwable? = null

            if (dump != null) {
                // Serialize recorded scrapes: the session installs global hooks for its duration.
                synchronized(DumpSession.LOCK) {
                    val session = DumpSession(dump)
                    session.begin()
                    try {
                        result = ScrapePipeline.run(combinedText, allLines, session)
                    } catch (t: Throwable) {
                        failure = t
                    } finally {
                        try { session.end() } catch (t: Throwable) { Log.e("DOSSIER", "DumpSession.end failed", t) }
                    }
                }
            } else {
                try {
                    result = ScrapePipeline.run(combinedText, allLines, null)
                } catch (t: Throwable) {
                    failure = t
                }
            }

            val totalPipelineMs = (System.nanoTime() - totalStartNanos) / 1_000_000.0
            val r = result
            val toastMessage: String
            if (r != null) {
                // Output Precision Benchmark Report to Logcat
                Log.i("DOSSIER_BENCHMARK", String.format(Locale.US, "⏱️ [PERFORMANCE BENCHMARK] Screen: %s", r.detectedType))
                Log.i("DOSSIER_BENCHMARK", String.format(Locale.US, "├─ Node Extraction: %.2f ms (%d text nodes)", extractDurationMs, extractedTextList.size))
                Log.i("DOSSIER_BENCHMARK", String.format(Locale.US, "├─ Screen Type Detection: %.2f ms", r.detectMs))
                Log.i("DOSSIER_BENCHMARK", String.format(Locale.US, "├─ Parsing & Database Processing: %.2f ms", r.parseMs))
                Log.i("DOSSIER_BENCHMARK", String.format(Locale.US, "└─ TOTAL PIPELINE TIME: %.2f ms", totalPipelineMs))
                toastMessage = ScrapePipeline.toastMessage(r.savedItems)
            } else {
                Log.e("DOSSIER", "Scrape pipeline failed", failure)
                toastMessage = "Scrape error: ${failure?.javaClass?.simpleName ?: "unknown"}"
            }

            if (dump != null) {
                dump.detectedType = r?.detectedType?.name
                dump.fallbackUsed = r?.fallbackUsed ?: false
                dump.timing.detectMs = RawDump.roundMs(r?.detectMs ?: 0.0)
                dump.timing.parseMs = RawDump.roundMs(r?.parseMs ?: 0.0)
                dump.timing.totalMs = RawDump.roundMs(totalPipelineMs)
                dump.result = toastMessage
                failure?.let { dump.error = (dump.error?.plus("\n") ?: "") + it.stackTraceToString().take(4000) }
                saveDumpAndToast(dump, toastMessage)
            } else {
                sendToast(toastMessage)
            }

            Log.d("DOSSIER", "Sending DATA_UPDATED broadcast to UI")
            val updateIntent = Intent("com.whisk.hackexlogger.DATA_UPDATED").apply {
                setPackage(packageName)
            }
            sendBroadcast(updateIntent)
        }
    }

    private fun sendToast(message: String) {
        val toastIntent = Intent("com.whisk.hackexlogger.SHOW_TOAST").apply {
            setPackage(packageName)
            putExtra("message", message)
        }
        sendBroadcast(toastIntent)
    }

    /** Persists the dump off the main thread, then shows the normal toast with "📼 #id" appended. */
    private fun saveDumpAndToast(dump: RawDump, message: String) {
        serviceScope.launch(Dispatchers.IO) {
            val suffix = try {
                " · 📼 #" + RawDumpStore.forContext(this@ScraperAccessibilityService).save(dump)
            } catch (e: Exception) {
                Log.e("DOSSIER", "Failed to save raw dump", e)
                " · 📼 save failed"
            }
            sendToast(message + suffix)
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
