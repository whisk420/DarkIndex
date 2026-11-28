package com.whisk.hackexlogger

import com.google.gson.JsonParser
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.StringWriter
import java.nio.file.Files

/**
 * Raw Dump Mode: log discard reasons (parser + db stage), the recording session, and the per-file store.
 */
class RawDumpTest {

    private lateinit var tmpDir: File

    @Before
    fun setUp() {
        TestEnv.freshRepository()
        TargetRepository.prefs = FakeSharedPreferences()
        TargetRepository.resetForTests()
        TargetRepository.isInitialized = true
        PlayerProfileRepository.prefs = FakeSharedPreferences()
        SiphonTracker.init()
        SiphonTracker.clearSiphonTracking()
        tmpDir = Files.createTempDirectory("rawdumps").toFile()
    }

    @After
    fun tearDown() {
        DatabaseManager.activeDiscardSink = null
        WalletConflictLog.listener = null
        tmpDir.deleteRecursively()
    }

    // ---------------------------------------------------------------- parser stage

    private val mixedLog = """
        // LOG
        SAVE
        ~/log.txt
        [11-19 16:09] Stole 1,226 Crypto from hxaa2a...3a3a
        [11-19 16:08] Accessed device at 5.6.7.8
        [11-19 16:05] Failed to crack password on 1.2.3.4
        [11-19 16:04] Password being cracked by 9.9.9.9...
        [11-19 16:03] Device accessed from [UNKNOWN]
        [11-19 16:02] Purchased Keygen Lv26
        [11-19 15:00] Downloaded Lv5 Spam
    """.trimIndent()

    @Test
    fun parseDetailedReportsEveryDiscardWithReason() {
        val r = LogScreenParser.parseDetailed(mixedLog.lines())
        assertEquals(7, r.timestampedLineCount)
        assertEquals(3, r.updates.size) // access + stole + failed crack (parsed since 2026-11-20)
        assertEquals(r.timestampedLineCount, r.updates.size + r.discarded.size)

        fun reasonOf(fragment: String) = r.discarded.single { it.line.contains(fragment) }.reason
        assertTrue(r.updates.any { it.ip == "1.2.3.4" && it.raw.contains("Failed to crack") })
        assertEquals(LogDiscardReason.NO_PATTERN, reasonOf("being cracked by"))
        assertEquals(LogDiscardReason.UNKNOWN_SOURCE, reasonOf("[UNKNOWN]"))
        assertEquals(LogDiscardReason.NO_PATTERN, reasonOf("Purchased Keygen"))
        assertEquals(LogDiscardReason.NO_TARGET_IP, reasonOf("Downloaded Lv5 Spam"))
        assertTrue(r.discarded.all { it.stage == "parser" })
    }

    // ---------------------------------------------------------------- db stage

    private fun collectDbDiscards(block: () -> Unit): List<DiscardedLogLine> {
        val out = mutableListOf<DiscardedLogLine>()
        DatabaseManager.activeDiscardSink = DatabaseManager.DbDiscardSink { line, reason -> out.add(DiscardedLogLine(line, "db", reason)) }
        try { block() } finally { DatabaseManager.activeDiscardSink = null }
        return out
    }

    @Test
    fun dbStageReportsEmptyIpMaskedSoftwareConflictAndBadTimestamp() {
        // Wallet proven by the wallet screen, so a log line naming another wallet is a conflict
        TargetRepository.saveAllRecords(listOf(TargetRecord(ip = "1.1.1.1", wallets = mutableListOf("hxAAAA...1111"), walletSource = WalletSource.PAGE)))
        val updates = listOf(
            ParsedUpdate(ip = "", time = "11-19 16:00", raw = "[11-19 16:00] Cracked password on ???"),
            ParsedUpdate(ip = "4.4.4.xxx", time = "11-19 16:01", software = Software("downloaded", 9, "Spam"), isOwnedSoftware = true, raw = "masked-dl"),
            ParsedUpdate(ip = "1.1.1.1", time = "11-19 16:02", wallet = "hxBBBB...2222", stolenAmount = 50, isOwnLogTheft = true, raw = "conflict"),
            ParsedUpdate(ip = "2.2.2.2", time = "garbage", wallet = "hxCCCC...3333", stolenAmount = 70, isOwnLogTheft = true, raw = "bad-time")
        )
        val discards = collectDbDiscards { DatabaseManager.processUpdates(updates) }
        val byLine = discards.associate { it.line to it.reason }
        assertEquals(LogDiscardReason.EMPTY_IP, byLine["[11-19 16:00] Cracked password on ???"])
        assertEquals(LogDiscardReason.MASKED_SOFTWARE_IGNORED, byLine["masked-dl"])
        assertEquals(LogDiscardReason.WALLET_CONFLICT, byLine["conflict"])
        assertEquals(LogDiscardReason.BAD_TIMESTAMP, byLine["bad-time"])
        // Bad timestamp still links the wallet (partial effect)
        assertTrue(TargetRepository.getAllRecords().first { it.ip == "2.2.2.2" }.wallets.contains("hxCCCC...3333"))
    }

    // ---------------------------------------------------------------- session + pipeline

    @Test
    fun pipelineWithSessionRecordsLogStatsInterpretationAndDbEffects() {
        val dump = RawDump.create(nowMs = 1_000L)
        val session = DumpSession(dump)
        session.begin()
        val result = ScrapePipeline.run(mixedLog, mixedLog.lines(), session)
        session.end()

        assertEquals(ScreenType.LOGS, result.detectedType)
        assertFalse(result.fallbackUsed)
        assertEquals(listOf("Log"), dump.parsersMatched)
        val stats = dump.logStats!!
        assertEquals(7, stats.timestampedLines)
        assertEquals(4, stats.discarded)
        assertEquals(3, stats.interpreted)
        assertEquals(0, stats.partial)
        assertEquals(4, dump.discardedLogLines.size)

        val effects = dump.dbEffects!!
        assertEquals(0, effects.recordsBefore)
        assertEquals(listOf("1.2.3.4", "5.6.7.8"), effects.created.sorted()) // 1.2.3.4 from the failed crack
        // Hooks are removed after the session
        assertNull(DatabaseManager.activeDiscardSink)
        assertNull(WalletConflictLog.listener)
    }

    @Test
    fun walletScreenMergeShowsUpAsRemovedPlaceholders() {
        DatabaseManager.processSoftwareScreen(SoftwareScreen("Pizaaparty", mapOf("Spam" to SoftwareScreenItem(10, "installed"))))
        DatabaseManager.processUpdates(listOf(ParsedUpdate(ip = "", time = "10-16 12:00", wallet = "hx9999", stolenAmount = 500, raw = "s", isOwnLogTheft = true)))
        assertEquals(2, TargetRepository.getAllRecords().size)

        val dump = RawDump.create()
        val session = DumpSession(dump)
        session.begin()
        // Direct call mirrors what the pipeline does for a WALLET screen
        session.matched("Wallet", WalletScreen("Pizaaparty", "hx9999"))
        DatabaseManager.processWalletScreen(WalletScreen("Pizaaparty", "hx9999"))
        session.end()

        val e = dump.dbEffects!!
        assertEquals(2, e.recordsBefore)
        assertEquals(1, e.recordsAfter)
        assertEquals(1, e.removed.size)
        assertNotNull(e.removed.single().likelyMergedInto)
    }

    @Test
    fun pipelineWithoutSessionBehavesAsBefore() {
        val result = ScrapePipeline.run(mixedLog, mixedLog.lines(), null)
        // The sample is an own log with "Purchased Keygen Lv26", so the profile is updated from it too
        assertEquals(listOf("3 Log Events", "Profile from log"), result.savedItems)
        assertEquals("Saved: 3 Log Events, Profile from log", ScrapePipeline.toastMessage(result.savedItems))
        assertEquals("Nothing found to save", ScrapePipeline.toastMessage(emptyList()))
    }

    private fun record(text: String): RawDump {
        val dump = RawDump.create()
        val session = DumpSession(dump)
        session.begin()
        val r = ScrapePipeline.run(text, text.lines(), session)
        session.end()
        dump.detectedType = r.detectedType.name
        dump.fallbackUsed = r.fallbackUsed
        return dump
    }

    @Test
    fun everyScreenTypeRecordsItsInterpretation() {
        val home = record("""
            CONNECTED
            DISCONNECT
            >
            Sh0w7
            _
            [ROBOT]
            LVL 38
            // SYSTEM INFO
            IP
            74.91.245.159
            DEVICE
            Nova
            NETWORK
            Fiber
            FIREWALL
            Lv.24
            ENCRYPTOR
            Lv.25
        """.trimIndent())
        assertEquals("HOME", home.detectedType)
        assertEquals(listOf("Home"), home.parsersMatched)
        val h = home.interpretation["Home"] as HomeScreen
        assertEquals("74.91.245.159", h.ip)
        assertEquals(listOf("74.91.245.159"), home.dbEffects!!.created)

        val sw = record("Sh0w7's installed software\nSpam\nLVL 22\nFirewall\nLVL 24")
        assertEquals(listOf("Software"), sw.parsersMatched)
        assertEquals(22, (sw.interpretation["Software"] as SoftwareScreen).softwareItems["Spam"]?.level)
        // merged into the existing 74.91.245.159 record (by username) → an update, not a new record
        assertTrue(sw.dbEffects!!.updated.any { it.ip == "74.91.245.159" && "downloads" in it.fields })

        val wallet = record("Sh0w7's wallet\nWALLET ADDRESS\nhx59a3c5b2c8ce22e619c9cb837be70a")
        assertEquals("hx59a3...e70a", (wallet.interpretation["Wallet"] as WalletScreen).wallet)
    }

    @Test
    fun storeScreenRecordsProfileChanges() {
        val store = record("""
            // STORE
            // NEXT DEVICE
            Apex VII
            LVL 7
            // NEXT NETWORK
            Null Route X
            LVL 10
            // SOFTWARE
            Bypasser
            LVL 21
            Keygen
            LVL 21
            Proprietary key generator built for your device.
        """.trimIndent())
        assertEquals("STORE", store.detectedType)
        val changes = store.dbEffects!!.profileChanges
        assertEquals(listOf(null, "Apex VII"), changes["nextDevice"])
        assertEquals(listOf(null, "Null Route X"), changes["nextNetwork"]) // "// NEXT NETWORK" header (not maxed)
        assertEquals(listOf(null, 21), changes["softwareLevels.Bypasser"])
    }

    @Test
    fun nothingMatchedStillProducesADump() {
        val dump = record("Some menu\nwith nothing useful")
        assertEquals("UNKNOWN", dump.detectedType)
        assertTrue(dump.parsersMatched.isEmpty())
        assertNull(dump.logStats)
        assertEquals(0, dump.dbEffects!!.created.size)
    }

    // ---------------------------------------------------------------- store

    private fun sampleDump(raw: String): RawDump = RawDump.create(nowMs = 1_700_000_000_000L).apply {
        rawText = raw
        detectedType = "LOGS"
        result = "Saved: 1 Log Events"
    }

    @Test
    fun storeAssignsIdsListsNewestFirstAndKeepsRawTextExact() {
        val store = RawDumpStore(tmpDir)
        val raw = "Backdoor blocked by 1.2.3.4&#39;s firewall\n  ÂLPHA <tag> \"q\"\n"
        assertEquals(1L, store.save(sampleDump(raw)))
        assertEquals(2L, store.save(sampleDump("b")))
        assertEquals(3L, store.save(sampleDump("c")))

        assertEquals(listOf(3L, 2L, 1L), store.listSummaries().map { it.id })
        val json = store.readJson(1)!!
        assertTrue("HTML must not be escaped", json.contains("&#39;s firewall") && !json.contains("\\u0026"))
        val parsed = JsonParser.parseString(json).asJsonObject
        assertEquals(raw, parsed.get("rawText").asString)
        assertEquals("darkindex-raw-dump", parsed.get("format").asString)
        assertEquals(1, parsed.get("id").asInt)
    }

    @Test
    fun deleteOneDeleteAllAndIdsAreNeverReused() {
        val store = RawDumpStore(tmpDir)
        store.save(sampleDump("a")); store.save(sampleDump("b")); store.save(sampleDump("c"))
        assertTrue(store.delete(2))
        assertEquals(listOf(3L, 1L), store.listSummaries().map { it.id })
        assertEquals(2, store.deleteAll())
        assertEquals(0, store.count())
        assertEquals(4L, store.save(sampleDump("d")))
    }

    @Test
    fun exportAllIsValidJsonOldestFirst() {
        val store = RawDumpStore(tmpDir)
        val empty = StringWriter()
        assertEquals(0, store.exportAll(empty))
        assertEquals(0, JsonParser.parseString(empty.toString()).asJsonObject.getAsJsonArray("dumps").size())

        store.save(sampleDump("first")); store.save(sampleDump("second"))
        val w = StringWriter()
        assertEquals(2, store.exportAll(w))
        val root = JsonParser.parseString(w.toString()).asJsonObject
        assertEquals("darkindex-raw-dumps", root.get("format").asString)
        assertEquals(2, root.get("count").asInt)
        val dumps = root.getAsJsonArray("dumps")
        assertEquals("first", dumps[0].asJsonObject.get("rawText").asString)
        assertEquals("second", dumps[1].asJsonObject.get("rawText").asString)
    }

    // ---------------------------------------------------------------- JSON order + lean copy (items 9, 10)

    private fun recordedLogDump(): RawDump {
        val dump = RawDump.create(nowMs = 1_000L)
        dump.rawText = mixedLog
        dump.nodeTree = listOf(
            RawDumpNode(d = 0, cls = "android.widget.FrameLayout", id = null, text = null, desc = null, bounds = listOf(0, 0, 1008, 2244)),
            RawDumpNode(d = 1, cls = "android.view.View", id = null, text = "", desc = null, bounds = listOf(0, 0, 1008, 2244)),
            RawDumpNode(d = 2, cls = "android.widget.EditText", id = null, text = mixedLog, desc = null, bounds = listOf(36, 369, 1008, 1874)),
            RawDumpNode(d = 2, cls = "android.widget.Button", id = "nav-item-scan", text = "SCAN", desc = null, bounds = listOf(184, 2009, 306, 2128))
        )
        val session = DumpSession(dump)
        session.begin()
        val r = ScrapePipeline.run(mixedLog, mixedLog.lines(), session)
        session.end()
        dump.detectedType = r.detectedType.name
        dump.result = ScrapePipeline.toastMessage(r.savedItems)
        return dump
    }

    @Test
    fun compactExportSharesRepeatedLinesDropsNavAndNodesAndDedupesTexts() {
        // Two real Apps pages (Raw Dumps #29 and #33): same consumable descriptions, different levels
        val store = RawDumpStore(tmpDir)
        val texts = listOf("own_apps_2026-11-24.txt", "own_apps_locked_2026-11-24.txt").map { TestEnv.fixture(it) }
        val nodes = listOf(RawDumpNode(d = 0, cls = "android.widget.FrameLayout", id = "nav-item-scan", text = "SCAN", desc = null, bounds = listOf(0, 0, 1, 1)))
        fun apps(t: String) = sampleDump(t).apply { detectedType = "APPS"; nodeTree = nodes }
        texts.forEach { store.save(apps(it)) }
        store.save(apps(texts[1])) // identical to the second
        val w = StringWriter()
        assertEquals(3, store.exportCompact(w))
        val out = w.toString()

        assertTrue(out.startsWith("# darkindex-dumps-compact v1"))
        assertFalse("node tree must not be exported", out.contains("nav-item-scan") || out.contains("FrameLayout"))
        assertFalse("bottom nav stripped", out.contains("STORE\nCREWS"))
        assertEquals(3, Regex("""## dump \d+ · [^\n]* · APPS · nav""").findAll(out).count())
        assertTrue(out.contains("text: same as #2"))
        // A consumable description shared by both pages is written once, in ## shared
        val purge = "Nuke every virus on your device. No scan, no wait, just gone. Restores 90% of lost reputation."
        assertEquals(1, Regex(Regex.escape(purge)).findAll(out).count())
        assertTrue(out.lines().any { it.startsWith("§") && it.endsWith(purge) })
        assertTrue("three texts cost less than two raw texts", out.length < texts.sumOf { it.length })
    }

    @Test
    fun compactExportLogRowsPointAtTextLines() {
        val store = RawDumpStore(tmpDir)
        store.save(recordedLogDump())
        val out = StringWriter().also { store.exportCompact(it) }.toString()
        val textLines = out.substringAfter("text:\n").split("\n")
        val rows = out.substringAfter("interp Log (").substringBefore("text:").lines().drop(1).filter { it.isNotBlank() }
        assertTrue(rows.isNotEmpty())
        for (row in rows) {
            val n = Regex("""^\s+L(\d+) """).find(row)!!.groupValues[1].toInt()
            // The row's wallet, or else its IP, is on that line (a steal's IP comes from the access line above it)
            val (ip, wallet) = row.trim().split(" ").let { it[1] to it[2] }
            val key = if (wallet != "-") wallet else ip
            if (key != "-") assertTrue("$row → ${textLines[n]}", textLines[n].contains(key))
        }
        assertTrue(out.contains("discarded: "))
    }

    @Test
    fun storedJsonHasExplicitKeyOrder() {
        val store = RawDumpStore(tmpDir)
        val id = store.save(recordedLogDump())
        val keys = JsonParser.parseString(store.readJson(id)!!).asJsonObject.keySet().toList()
        assertEquals(listOf("format", "version", "id", "capturedAtUtc"), keys.take(4))
        assertEquals(listOf("interpretation", "rawText", "nodeTree"), keys.takeLast(3))
        assertTrue(keys.indexOf("discardedLogLines") < keys.indexOf("dbEffects"))
    }

    @Test
    fun leanCopyIsCompactAndKeepsWhatMatters() {
        val store = RawDumpStore(tmpDir)
        val id = store.save(recordedLogDump())
        val full = store.readJson(id)!!
        val leanText = RawDumpJson.leanJson(full)
        assertTrue("lean (${leanText.length}) should be much smaller than full (${full.length})", leanText.length < full.length / 2)
        assertFalse("lean is compact", leanText.contains("\n  "))

        val lean = JsonParser.parseString(leanText).asJsonObject
        assertEquals(RawDumpJson.LEAN_FORMAT, lean.get("format").asString)
        assertEquals(id, lean.get("dump").asLong)
        assertEquals("LOGS", lean.get("screen").asString)
        assertFalse("null error dropped", lean.has("error"))
        assertEquals(mixedLog, lean.get("rawText").asString)

        // discarded rows: [line, "stage:reason"]
        val discarded = lean.getAsJsonArray("discarded")
        assertEquals(4, discarded.size())
        assertTrue(discarded.any { it.asJsonArray[1].asString == "parser:NO_TARGET_IP" })

        // Log rows reference rawText line numbers
        val rows = lean.getAsJsonObject("interp").getAsJsonObject("Log").getAsJsonArray("rows")
        assertEquals(3, rows.size())
        val rawLines = mixedLog.split("\n")
        val stole = rows.map { it.asJsonArray }.single { it.size() > 2 && !it[2].isJsonNull }
        val access = rows.map { it.asJsonArray }.single { it !== stole && it.size() == 2 && it[1].asString == "5.6.7.8" }
        assertTrue("trailing null/false trimmed", rawLines[access[0].asInt].contains("Accessed device at 5.6.7.8"))
        assertTrue(rawLines[stole[0].asInt].contains("Stole 1,226"))
        assertEquals("5.6.7.8", stole[1].asString)
        assertEquals(1226, stole[3].asInt)

        // db: empty lists dropped, created kept
        val db = lean.getAsJsonObject("db")
        assertFalse(db.has("removed"))
        assertTrue(db.getAsJsonArray("created").map { it.asString }.contains("5.6.7.8"))

        // nodes: only text/id/desc rows, short class names, text truncated
        val nodes = lean.getAsJsonObject("nodes")
        assertEquals(4, nodes.get("total").asInt)
        val nodeRows = nodes.getAsJsonArray("rows")
        assertEquals(2, nodeRows.size())
        assertEquals("EditText", nodeRows[0].asJsonArray[1].asString)
        assertTrue(nodeRows[0].asJsonArray[4].asString.length <= 60)
        assertEquals("nav-item-scan", nodeRows[1].asJsonArray[2].asString)
    }

    @Test
    fun leanMapsDuplicateLogLinesToDistinctRawLines() {
        val raw = "[1-1 1:00] Accessed device at 1.1.1.1\n[1-1 1:00] Accessed device at 1.1.1.1"
        val dump = RawDump.create().apply {
            rawText = raw
            interpretation["Log"] = listOf(
                linkedMapOf("line" to "[1-1 1:00] Accessed device at 1.1.1.1", "ip" to "1.1.1.1"),
                linkedMapOf("line" to "[1-1 1:00] Accessed device at 1.1.1.1", "ip" to "1.1.1.1")
            )
        }
        val rows = JsonParser.parseString(RawDumpJson.leanJson(RawDumpJson.fullJson(dump))).asJsonObject
            .getAsJsonObject("interp").getAsJsonObject("Log").getAsJsonArray("rows")
        assertEquals(setOf(0, 1), rows.map { it.asJsonArray[0].asInt }.toSet())
    }

    @Test
    fun leanWorksOnOldAlphabeticalDumps() {
        // Shape written by build 1.5 (14): Gson on Android emitted keys alphabetically
        val legacy = """{"app":{"versionCode":14,"versionName":"1.5"},"capturedAtUtc":"2026-11-20T04:00:29.242Z",
            "dbEffects":{"created":[],"knownSiphonsAfter":120,"knownSiphonsBefore":120,"profileChanges":{},"recordsAfter":4298,
            "recordsBefore":4298,"removed":[],"siphonAlertsAdded":[],"siphonAlertsCleared":[],"updated":[],"walletConflicts":[]},
            "detectedType":"WALLET","discardedLogLines":[],"error":null,"fallbackUsed":false,"foregroundPackage":"net.cncapps.hackex2",
            "format":"darkindex-raw-dump","id":3,"interpretation":{"Wallet":{"username":"mjja419","wallet":"hxff13...42f1"}},
            "logStats":null,"nodeTree":[{"bounds":[0,0,1008,2244],"cls":"android.widget.FrameLayout","d":0,"desc":null,"id":null,"text":null},
            {"bounds":[63,1102,972,1210],"cls":"android.widget.EditText","d":9,"desc":null,"id":"steal-amount","text":"10901"}],
            "parsersMatched":["Wallet"],"rawText":"mjja419's wallet\nWALLET ADDRESS\nhxff13eb217d263685e767a57d3942f1","result":"Saved: Wallet",
            "timing":{"detectMs":0.04,"extractMs":1.93,"nodeCount":25,"parseMs":381.61,"totalMs":425.44},"version":1}"""
        val lean = JsonParser.parseString(RawDumpJson.leanJson(legacy)).asJsonObject
        assertEquals("1.5 (14)", lean.get("app").asString)
        assertEquals("WALLET", lean.get("screen").asString)
        assertEquals(listOf("recordsBefore", "recordsAfter"), lean.getAsJsonObject("db").keySet().toList())
        assertFalse(lean.has("discarded"))
        assertEquals("hxff13...42f1", lean.getAsJsonObject("interp").getAsJsonObject("Wallet").get("wallet").asString)
        assertEquals(1, lean.getAsJsonObject("nodes").getAsJsonArray("rows").size())
    }

}
