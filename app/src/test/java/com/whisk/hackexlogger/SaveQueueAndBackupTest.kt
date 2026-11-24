package com.whisk.hackexlogger

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Background save queue, the reconcile fast path, and your profile in backups/exports (2026-11-20). */
class SaveQueueAndBackupTest {

    private lateinit var dir: File
    private var realQueue: ExecutorService? = null

    @Before
    fun setUp() {
        dir = TestEnv.freshRepository()
    }

    @After
    fun tearDown() {
        realQueue?.shutdownNow()
        TargetRepository.persistExecutor = java.util.concurrent.Executor { it.run() }
    }

    private fun useRealQueue() {
        realQueue = Executors.newSingleThreadExecutor()
        TargetRepository.persistExecutor = realQueue!!
    }

    private val dbFile get() = File(dir, TargetStore.FILE_NAME)

    private fun storedIps(): List<String> = TargetStore.read(dbFile)?.records?.map { it.ip } ?: emptyList()

    /** Simulates the next app start: memory emptied, then loaded from disk. */
    private fun restart() {
        TargetRepository.resetForTests()
        TargetRepository.loadCacheFromDisk()
    }

    private val mirror get() = File(dir, "DarkIndex/darkindex_database.json")

    // ---- Queue ----------------------------------------------------------------------------------

    @Test
    fun manyQuickSavesEndOnTheLatestState() {
        useRealQueue()
        for (i in 1..50) {
            TargetRepository.saveAllRecords((1..i).map { TargetRecord(ip = "10.0.0.$it") })
        }
        // Memory is up to date immediately, before the disk catches up
        assertEquals(50, TargetRepository.getAllRecords().size)

        assertTrue(TargetRepository.flushPendingWrites())
        assertEquals(50, storedIps().size)
        assertTrue(mirror.readText().contains("10.0.0.50"))
    }

    @Test
    fun wipeAfterAQueuedSaveStaysWiped() {
        useRealQueue()
        TargetRepository.saveAllRecords(listOf(TargetRecord(ip = "1.1.1.1")))
        TargetRepository.wipeDatabase()

        assertTrue(TargetRepository.flushPendingWrites())
        assertEquals(emptyList<String>(), storedIps())
        assertTrue(TargetRepository.getAllRecords().isEmpty())
    }

    @Test
    fun savedDataReloadsAfterARestart() {
        useRealQueue()
        TargetRepository.saveAllRecords(listOf(TargetRecord(ip = "2.2.2.2", username = "Persisted", level = 33)))
        assertTrue(TargetRepository.flushPendingWrites())

        restart()
        val loaded = TargetRepository.getAllRecords()
        assertEquals("Persisted", loaded.single().username)
        assertEquals(33, loaded.single().level)
    }

    @Test
    fun backupWaitsForItsTimerButTheDatabaseFileDoesNot() {
        TargetRepository.mirrorDelayMs = 60_000
        TargetRepository.saveAllRecords(listOf(TargetRecord(ip = "3.3.3.3")))
        assertEquals(listOf("3.3.3.3"), storedIps())
        assertFalse(mirror.exists())

        // Leaving the app (or an export) writes it straight away
        assertTrue(TargetRepository.flushPendingWrites())
        assertTrue(mirror.readText().contains("3.3.3.3"))
    }

    @Test
    fun wipeKeepsTheLastBackup() {
        TargetRepository.saveAllRecords(listOf(TargetRecord(ip = "4.4.4.4")))
        TargetRepository.wipeDatabase()
        assertTrue(TargetRepository.flushPendingWrites())
        assertTrue(mirror.readText().contains("4.4.4.4"))
    }

    // ---- Moving from SharedPreferences to targets.json (2026-11-22) --------------------------------

    /** What versions before 2026-11-22 stored: the record array as one prefs string. */
    private val legacyJson = """[{"ip":"5.5.5.5","username":"Old","wallets":["hxAAAA...1111"],"walletSource":"wallet_page",
        "walletThefts":{"hxAAAA...1111":[{"timestampMs":1000,"amount":7}]},"lastValidated":"2026-11-01 10:00"},
        {"ip":"6.6.6.6"}]"""

    private fun markThefsAlreadyUtc() = TargetRepository.prefs.edit().putBoolean(TargetRepository.KEY_THEFTS_UTC, true).apply()

    @Test
    fun firstStartMovesThePrefsDatabaseToTheFile() {
        markThefsAlreadyUtc()
        TargetRepository.prefs.edit().putString(TargetRepository.KEY_TARGETS, legacyJson).apply()

        restart()

        val old = TargetRepository.getAllRecords().single { it.ip == "5.5.5.5" }
        assertEquals("Old", old.username)
        assertEquals(WalletSource.PAGE, old.walletSource)
        assertEquals(7, old.walletThefts.getValue("hxAAAA...1111").single().amount)
        assertTrue(TargetRepository.getAllRecords().single { it.ip == "6.6.6.6" }.wallets.isEmpty()) // missing lists filled in
        assertEquals(listOf("5.5.5.5", "6.6.6.6"), storedIps())
        assertNull(TargetRepository.prefs.getString(TargetRepository.KEY_TARGETS, null))
        // The original text is kept, byte for byte
        assertEquals(legacyJson, dir.listFiles()!!.single { it.name.startsWith("targets_from_prefs_") }.readText())
        assertNull(TargetRepository.loadReport)
    }

    @Test
    fun theFileKeepsTheSameFormatOlderVersionsRead() {
        TargetRepository.saveAllRecords(listOf(TargetRecord(ip = "7.7.7.7", wallets = listOf("hxW"), walletSource = WalletSource.PAGE)))
        val text = dbFile.readText()
        assertTrue(text, text.startsWith("[{\"ip\":\"7.7.7.7\""))
        assertTrue(text, text.contains("\"walletSource\":\"wallet_page\""))
    }

    @Test
    fun dataAnOlderVersionWroteAfterADowngradeIsMergedBackIn() {
        markThefsAlreadyUtc()
        TargetRepository.saveAllRecords(listOf(TargetRecord(ip = "5.5.5.5", level = 30), TargetRecord(ip = "8.8.8.8")))
        // An older build writes its own (newer) view to prefs
        TargetRepository.prefs.edit().putString(TargetRepository.KEY_TARGETS, legacyJson).apply()

        restart()

        val byIp = TargetRepository.getAllRecords().associateBy { it.ip }
        assertEquals(setOf("5.5.5.5", "6.6.6.6", "8.8.8.8"), byIp.keys)
        assertEquals(30, byIp.getValue("5.5.5.5").level)        // from the file
        assertEquals("Old", byIp.getValue("5.5.5.5").username)  // from the older version
        assertNull(TargetRepository.prefs.getString(TargetRepository.KEY_TARGETS, null))
    }

    @Test
    fun anUnreadableRecordIsKeptAsideAndTheRestLoad() {
        markThefsAlreadyUtc()
        dbFile.writeText("""[{"ip":"1.1.1.1"},{"ip":"2.2.2.2","level":"not a number"},{"username":"no ip"}]""")

        restart()

        assertEquals(listOf("1.1.1.1"), TargetRepository.getAllRecords().map { it.ip })
        val kept = dir.listFiles()!!.single { it.name.startsWith("targets_skipped_") }.readText()
        assertTrue(kept.contains("not a number") && kept.contains("no ip"))
        assertNotNull(TargetRepository.loadReport)
    }

    @Test
    fun anUnreadableFileIsSetAsideNeverOverwritten() {
        markThefsAlreadyUtc()
        dbFile.writeText("{ this is not json")

        restart()
        TargetRepository.saveAllRecords(listOf(TargetRecord(ip = "9.9.9.9")))

        val aside = dir.listFiles()!!.single { it.name.startsWith("targets_unreadable_") }
        assertEquals("{ this is not json", aside.readText())
        assertEquals(listOf("9.9.9.9"), storedIps())
        assertNotNull(TargetRepository.loadReport)
    }

    // ---- Reconcile fast path --------------------------------------------------------------------

    @Test
    fun sharedUsernameOrWalletDetection() {
        val a = TargetRecord(ip = "1.1.1.1", username = "Alpha", wallets = mutableListOf("hx1"))
        val b = TargetRecord(ip = "2.2.2.2", username = "Bravo", wallets = mutableListOf("hx2"))
        assertFalse(RecordMerger.hasSharedUsernameOrWallet(listOf(a, b)))
        assertTrue(RecordMerger.hasSharedUsernameOrWallet(listOf(a, b, TargetRecord(ip = "3.3.3.3", username = "aLPHA"))))
        assertTrue(RecordMerger.hasSharedUsernameOrWallet(listOf(a, b, TargetRecord(ip = "3.3.3.3", wallets = mutableListOf("hx2")))))
        // The same wallet listed twice in ONE record isn't a match between records
        assertFalse(RecordMerger.hasSharedUsernameOrWallet(listOf(TargetRecord(ip = "4.4.4.4", wallets = mutableListOf("hx9", "hx9")), b)))
    }

    @Test
    fun reconcileStillMergesDuplicates() {
        TargetRepository.saveAllRecords(listOf(
            TargetRecord(ip = "unknown:Alpha", username = "alpha", wallets = mutableListOf("hx1")),
            TargetRecord(ip = "1.1.1.1", username = "Alpha", level = 20),
            TargetRecord(ip = "2.2.2.2", username = "Bravo")
        ))
        TargetRepository.reconcileDatabase()

        val records = TargetRepository.getAllRecords().associateBy { it.ip }
        assertEquals(setOf("1.1.1.1", "2.2.2.2"), records.keys)
        assertEquals(listOf("hx1"), records.getValue("1.1.1.1").wallets)
    }

    // ---- Profile in backups ---------------------------------------------------------------------

    @Test
    fun exportIncludesYourProfile() {
        // Nothing scraped yet: no empty profile in the file
        assertNull(TargetRepository.gson.fromJson(DatabaseBackup.exportDataBundle(), ConsoleBundle::class.java).playerProfile)

        PlayerProfileRepository.updateOwnProfile("Seamus", 40)
        val export = DatabaseBackup.exportDataBundle()
        val bundle = TargetRepository.gson.fromJson(export, ConsoleBundle::class.java)
        assertEquals(40, bundle.playerProfile?.playerLevel)
        assertEquals("Seamus", bundle.playerProfile?.username)
    }

    @Test
    fun importRestoresProfileAfterAReinstall() {
        PlayerProfileRepository.updateOwnProfile("Seamus", 40)
        PlayerProfileRepository.updateProfile(StoreScreen(nextDevice = "Apex IX", nextNetwork = "Null Route XI", networkMaxed = true,
            nextSoftwareLevels = mapOf("Spam" to 21)))
        val export = DatabaseBackup.exportDataBundle()

        // Reinstall: profile gone
        PlayerProfileRepository.prefs = FakeSharedPreferences()
        DatabaseBackup.importDataBundle(export)

        val profile = PlayerProfileRepository.getProfile()
        assertEquals(40, profile.playerLevel)
        assertEquals("Apex VIII", profile.currentDevice)
        assertEquals("Null Route XI", profile.currentNetwork)
        assertEquals(21, profile.softwareLevels["Spam"])
    }

    @Test
    fun importNeverOverwritesNewerProfileData() {
        PlayerProfileRepository.updateOwnProfile("Seamus", 38)
        PlayerProfileRepository.updateProfile(StoreScreen(nextSoftwareLevels = mapOf("Spam" to 18, "Trace" to 20)))
        val oldExport = DatabaseBackup.exportDataBundle()

        PlayerProfileRepository.updateOwnProfile("Seamus", 40)
        PlayerProfileRepository.updateProfile(StoreScreen(nextSoftwareLevels = mapOf("Spam" to 21)))
        DatabaseBackup.importDataBundle(oldExport)

        val profile = PlayerProfileRepository.getProfile()
        assertEquals(40, profile.playerLevel)                 // newer value kept
        assertEquals(21, profile.softwareLevels["Spam"])      // newer value kept
        assertEquals(20, profile.softwareLevels["Trace"])     // missing value filled from the backup
    }

    @Test
    fun profileChangeRefreshesTheDownloadsBackup() {
        TargetRepository.saveAllRecords(listOf(TargetRecord(ip = "1.1.1.1")))
        assertFalse(mirror.readText().contains("\"playerLevel\": 40"))

        PlayerProfileRepository.updateOwnProfile("Seamus", 40) // e.g. scraping your own Home screen

        assertTrue(mirror.readText().contains("\"playerLevel\": 40"))
        assertNotNull(TargetRepository.gson.fromJson(mirror.readText(), ConsoleBundle::class.java).playerProfile)
    }
}
