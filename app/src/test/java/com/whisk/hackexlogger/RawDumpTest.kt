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
        TargetRepository.prefs = FakeSharedPreferences()
        TargetRepository.cachedRecords.clear()
        TargetRepository.isInitialized = true
        PlayerProfileRepository.prefs = FakeSharedPreferences()
        SiphonTracker.init()
        SiphonTracker.clearSiphonTracking()
        tmpDir = Files.createTempDirectory("rawdumps").toFile()
    }

    @After
    fun tearDown() {
        DatabaseManager.activeDiscardSink = null
        TargetRepository.walletConflictListener = null
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
        assertEquals(2, r.updates.size) // access + stole
        assertEquals(r.timestampedLineCount, r.updates.size + r.discarded.size)

        fun reasonOf(fragment: String) = r.discarded.single { it.line.contains(fragment) }.reason
        assertEquals(LogDiscardReason.NO_PATTERN, reasonOf("Failed to crack"))
        assertEquals(LogDiscardReason.NO_PATTERN, reasonOf("being cracked by"))
        assertEquals(LogDiscardReason.UNKNOWN_SOURCE, reasonOf("[UNKNOWN]"))
        assertEquals(LogDiscardReason.NO_PATTERN, reasonOf("Purchased Keygen"))
        assertEquals(LogDiscardReason.NO_TARGET_IP, reasonOf("Downloaded Lv5 Spam"))
        assertTrue(r.discarded.all { it.stage == "parser" })
    }

    @Test
    fun parseIsUnchangedByDetailedVariant() {
        val fixtures = listOf(
            mixedLog,
            """
            [11-1 11:01] Stole 86 Crypto from hxed11...16ef
            [11-1 11:00] Accessed device at 88.205.81.140
            [11-1 10:43] Stole 9,906 Crypto from hx47fc...9b0b
            [11-1 10:43] Cracked password on 205.101.110.145
            [11-1 10:28] Accessed device at 202.94.241.81
            """.trimIndent(),
            """
            [11-2 3:34] 28 Crypto transferred to hx5a4e...3c37
            [11-2 3:34] Device accessed from 168.222.130.xxx

            [11-1 22:19] Device accessed from 86.123.81.1
            """.trimIndent()
        )
        for (f in fixtures) {
            val lines = f.lines()
            assertEquals(LogScreenParser.parse(lines), LogScreenParser.parseDetailed(lines).updates)
        }
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
        TargetRepository.saveAllRecords(listOf(TargetRecord(ip = "1.1.1.1", wallets = mutableListOf("hxAAAA...1111"))))
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

    @Test
    fun noSinkMeansNoBehaviourChange() {
        assertNull(DatabaseManager.activeDiscardSink)
        DatabaseManager.processUpdates(listOf(ParsedUpdate(ip = "", time = null, raw = "x")))
        assertEquals(0, TargetRepository.getAllRecords().size)
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
        assertEquals(5, stats.discarded)
        assertEquals(2, stats.interpreted)
        assertEquals(0, stats.partial)
        assertEquals(5, dump.discardedLogLines.size)

        val effects = dump.dbEffects!!
        assertEquals(0, effects.recordsBefore)
        assertEquals(listOf("5.6.7.8"), effects.created)
        // Hooks are removed after the session
        assertNull(DatabaseManager.activeDiscardSink)
        assertNull(TargetRepository.walletConflictListener)
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
        assertEquals(listOf("2 Log Events"), result.savedItems)
        assertEquals("Saved: 2 Log Events", ScrapePipeline.toastMessage(result.savedItems))
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
        assertEquals(listOf(null, 21), changes["softwareLevels.Bypasser"])
    }

    @Test
    fun unknownScreenRecordsFallbackAndMatchedParsers() {
        // Only "Uploaded" lines: no LOGS signature → UNKNOWN → fallback still parses it
        val dump = record("[9-19 15:08] Uploaded Lv25 Siphon to 5.225.204.180")
        assertEquals("UNKNOWN", dump.detectedType)
        assertTrue(dump.fallbackUsed)
        assertEquals(listOf("Log"), dump.parsersMatched)
        assertEquals(1, dump.logStats!!.timestampedLines)
        assertEquals(1, dump.logStats!!.interpreted)
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

    @Test
    fun emptyExportIsStillValidJson() {
        val w = StringWriter()
        assertEquals(0, RawDumpStore(tmpDir).exportAll(w))
        assertEquals(0, JsonParser.parseString(w.toString()).asJsonObject.getAsJsonArray("dumps").size())
    }
}
