package com.whisk.hackexlogger

/**
 * Classify → parse → persist for one screen snapshot.
 *
 * Extracted verbatim from ScraperAccessibilityService so it is unit-testable: same parsers, same
 * DatabaseManager calls, same order, same toast labels. When a [DumpSession] is supplied
 * (Raw Dump Mode), each parser that matches also reports its interpretation to the session.
 */
object ScrapePipeline {

    data class Result(
        val detectedType: ScreenType,
        val savedItems: List<String>,
        val fallbackUsed: Boolean,
        val detectMs: Double,
        val parseMs: Double
    )

    fun run(rawText: String, rawLines: List<String>, session: DumpSession? = null): Result {
        val savedItems = mutableListOf<String>()
        // The game's WebView leaks HTML entities (e.g. "&#39;s wallet"); decode before anything reads the text
        val combinedText = CommonParserUtil.decodeEntities(rawText)
        val allLines = if (combinedText === rawText) rawLines else rawLines.map { CommonParserUtil.decodeEntities(it) }

        val detectStart = System.nanoTime()
        val detectedType = ScreenClassifier.detectScreenType(combinedText, allLines)
        val detectMs = (System.nanoTime() - detectStart) / 1_000_000.0

        val parseStart = System.nanoTime()
        when (detectedType) {
            ScreenType.HOME -> home(combinedText, savedItems, session)
            ScreenType.SOFTWARE -> software(combinedText, savedItems, session)
            ScreenType.WALLET -> wallet(combinedText, savedItems, session)
            ScreenType.LOGS -> logs(allLines, savedItems, session)
            ScreenType.SIPHON -> { siphon(combinedText, savedItems, session); apps(allLines, savedItems, session) }
            ScreenType.SPAM_EARNINGS -> { spamEarnings(combinedText, savedItems, session); apps(allLines, savedItems, session) }
            ScreenType.APPS -> apps(allLines, savedItems, session)
            ScreenType.CREW -> crew(allLines, savedItems, session)
            ScreenType.OWN_PROFILE -> ownHome(combinedText, savedItems, session)
            ScreenType.STORE -> store(allLines, savedItems, session)
            ScreenType.BYPASS -> bypass(combinedText, savedItems, session)
            ScreenType.PROCESS -> process(allLines, savedItems, session)
            ScreenType.OWN_DEVICE -> {} // your own device/settings page: nothing to save
            ScreenType.UNKNOWN -> {
                // Fallback: evaluate every parser (same order as the original service code)
                home(combinedText, savedItems, session)
                software(combinedText, savedItems, session)
                wallet(combinedText, savedItems, session)
                logs(allLines, savedItems, session)
                siphon(combinedText, savedItems, session)
                crew(allLines, savedItems, session)
                store(allLines, savedItems, session)
                ownHome(combinedText, savedItems, session)
                bypass(combinedText, savedItems, session)
                process(allLines, savedItems, session)
                apps(allLines, savedItems, session)
            }
        }
        val parseMs = (System.nanoTime() - parseStart) / 1_000_000.0
        return Result(detectedType, savedItems, detectedType == ScreenType.UNKNOWN, detectMs, parseMs)
    }

    fun toastMessage(savedItems: List<String>): String =
        if (savedItems.isNotEmpty()) "Saved: " + savedItems.joinToString(", ") else "Nothing found to save"

    private fun home(text: String, saved: MutableList<String>, s: DumpSession?) {
        val parsed = HomeScreenParser.parse(text) ?: return
        s?.matched("Home", parsed)
        DatabaseManager.processHomeScreen(parsed)
        saved.add("Target Profile")
    }

    private fun software(text: String, saved: MutableList<String>, s: DumpSession?) {
        val parsed = SoftwareScreenParser.parse(text) ?: return
        s?.matched("Software", parsed)
        DatabaseManager.processSoftwareScreen(parsed)
        saved.add("Software Inventory")
    }

    private fun wallet(text: String, saved: MutableList<String>, s: DumpSession?) {
        val parsed = WalletScreenParser.parse(text) ?: return
        s?.matched("Wallet", parsed)
        DatabaseManager.processWalletScreen(parsed)
        saved.add("Wallet")
    }

    private fun logs(lines: List<String>, saved: MutableList<String>, s: DumpSession?) {
        val result = LogScreenParser.parseDetailed(lines)
        // Record stats whenever the log parser saw timestamped lines, even if none were usable.
        if (s != null && result.timestampedLineCount > 0) s.recordLogParse(result)
        val updates = result.updates
        if (updates.isNotEmpty()) {
            s?.matched("Log", updates.map { describe(it) })
            DatabaseManager.processUpdates(updates, result.lastSavedUtcMs)
            saved.add("${updates.size} Log Events")
        }
        // Marathon Mode: every own-log grab feeds the run, even an empty (wiped) log, which still
        // counts as "log grabbed" for advancing the flow. Runs after the DB save so wallet owners resolve.
        if (Marathon.isActive && Marathon.isOwnLogScreen(lines)) {
            val added = Marathon.onOwnLog(updates, result.lastSavedUtcMs, System.currentTimeMillis())
            if (added != null) saved.add("Marathon +$added (${Marathon.stealCount} steals)")
        }
    }

    private fun siphon(text: String, saved: MutableList<String>, s: DumpSession?) {
        val parsed = SiphonScreenParser.parse(text) ?: return
        s?.matched("Siphon", parsed)
        DatabaseManager.processSiphonScreen(parsed)
        saved.add("Siphons")
    }

    /** Recognised and parsed so Raw Dump shows it; nothing is stored yet (the Spam tracker will use it). */
    private fun spamEarnings(text: String, saved: MutableList<String>, s: DumpSession?) {
        val parsed = SpamEarningsParser.parse(text) ?: return
        s?.matched("SpamEarnings", parsed)
        saved.add("Spam earnings (${parsed.items.size + parsed.maskedCount}/${parsed.deployedCount} read, not stored yet)")
    }
    /** Your installed levels from the Apps page (also present under the Spam and Siphon earnings pages). */
    private fun apps(lines: List<String>, saved: MutableList<String>, s: DumpSession?) {
        val parsed = OwnAppsParser.parse(lines) ?: return
        s?.matched("Apps", parsed)
        DatabaseManager.processOwnAppsScreen(parsed)
        saved.add("My Apps (${parsed.installedLevels.size} levels)")
    }

    private fun crew(lines: List<String>, saved: MutableList<String>, s: DumpSession?) {
        val parsed = CrewScreenParser.parse(lines) ?: return
        s?.matched("Crew", parsed)
        DatabaseManager.processCrewScreen(parsed)
        saved.add("Crew")
    }

    private fun ownHome(text: String, saved: MutableList<String>, s: DumpSession?) {
        val parsed = OwnHomeScreenParser.parse(text) ?: return
        s?.matched("OwnHome", parsed)
        DatabaseManager.processOwnHomeScreen(parsed)
        saved.add("My Profile (Lv.${parsed.level ?: "?"})")
    }

    private fun store(lines: List<String>, saved: MutableList<String>, s: DumpSession?) {
        val parsed = StoreScreenParser.parse(lines) ?: return
        s?.matched("Store", parsed)
        DatabaseManager.processStoreScreen(parsed)
        saved.add("Store / Upgrades")
    }

    private fun bypass(text: String, saved: MutableList<String>, s: DumpSession?) {
        val parsed = BypassScreenParser.parse(text) ?: return
        s?.matched("Bypass", parsed)
        DatabaseManager.processBypassScreen(parsed)
        saved.add("Bypass / Target Info")
    }

    private fun process(lines: List<String>, saved: MutableList<String>, s: DumpSession?) {
        val parsed = ProcessScreenParser.parse(lines) ?: return
        s?.matched("Process", parsed)
        DatabaseManager.processProcessScreen(parsed)
        saved.add("Processes")
    }

    /** Flat, explicit view of one log update for the dump's interpretation section. */
    private fun describe(u: ParsedUpdate): Map<String, Any?> = linkedMapOf(
        "line" to u.raw,
        "time" to u.time,
        "ip" to u.ip.ifEmpty { null },
        "wallet" to u.wallet,
        "amount" to u.stolenAmount,
        "ownTheft" to u.isOwnLogTheft,
        "software" to u.software?.let { "${it.action} Lv${it.level} ${it.name}" },
        "ownedSoftware" to u.isOwnedSoftware
    )
}
