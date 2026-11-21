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

    private fun storedIps(): List<String> {
        val json = TargetRepository.prefs.getString(TargetRepository.KEY_TARGETS, null) ?: return emptyList()
        return TargetRepository.gson.fromJson(json, Array<TargetRecord>::class.java).map { it.ip }
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
        assertNull(TargetRepository.prefs.getString(TargetRepository.KEY_TARGETS, null))
        assertTrue(TargetRepository.getAllRecords().isEmpty())
    }

    @Test
    fun savedDataReloadsAfterARestart() {
        useRealQueue()
        TargetRepository.saveAllRecords(listOf(TargetRecord(ip = "2.2.2.2", username = "Persisted", level = 33)))
        assertTrue(TargetRepository.flushPendingWrites())

        // Simulate a fresh app start reading the same prefs
        val prefs = TargetRepository.prefs
        synchronized(TargetRepository.cachedRecords) { TargetRepository.cachedRecords.clear() }
        TargetRepository.isInitialized = false
        val loaded = TargetRepository.gson.fromJson(prefs.getString(TargetRepository.KEY_TARGETS, "[]"), Array<TargetRecord>::class.java)
        TargetRepository.isInitialized = true
        assertEquals("Persisted", loaded.single().username)
        assertEquals(33, loaded.single().level)
    }

    // ---- Reconcile fast path --------------------------------------------------------------------

    @Test
    fun sharedUsernameOrWalletDetection() {
        val a = TargetRecord(ip = "1.1.1.1", username = "Alpha", wallets = mutableListOf("hx1"))
        val b = TargetRecord(ip = "2.2.2.2", username = "Bravo", wallets = mutableListOf("hx2"))
        assertFalse(TargetRepository.hasSharedUsernameOrWallet(listOf(a, b)))
        assertTrue(TargetRepository.hasSharedUsernameOrWallet(listOf(a, b, TargetRecord(ip = "3.3.3.3", username = "aLPHA"))))
        assertTrue(TargetRepository.hasSharedUsernameOrWallet(listOf(a, b, TargetRecord(ip = "3.3.3.3", wallets = mutableListOf("hx2")))))
        // The same wallet listed twice in ONE record isn't a match between records
        assertFalse(TargetRepository.hasSharedUsernameOrWallet(listOf(TargetRecord(ip = "4.4.4.4", wallets = mutableListOf("hx9", "hx9")), b)))
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
        PlayerProfileRepository.updateOwnProfile("Seamus", 40)
        val export = TargetRepository.exportDataBundle()
        val bundle = TargetRepository.gson.fromJson(export, ConsoleBundle::class.java)
        assertEquals(40, bundle.playerProfile?.playerLevel)
        assertEquals("Seamus", bundle.playerProfile?.username)
    }

    @Test
    fun emptyProfileIsLeftOutOfTheExport() {
        val bundle = TargetRepository.gson.fromJson(TargetRepository.exportDataBundle(), ConsoleBundle::class.java)
        assertNull(bundle.playerProfile)
    }

    @Test
    fun importRestoresProfileAfterAReinstall() {
        PlayerProfileRepository.updateOwnProfile("Seamus", 40)
        PlayerProfileRepository.updateProfile(StoreScreen(nextDevice = "Apex IX", nextNetwork = "Null Route XI", networkMaxed = true,
            nextSoftwareLevels = mapOf("Spam" to 21)))
        val export = TargetRepository.exportDataBundle()

        // Reinstall: profile gone
        PlayerProfileRepository.prefs = FakeSharedPreferences()
        TargetRepository.importDataBundle(export)

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
        val oldExport = TargetRepository.exportDataBundle()

        PlayerProfileRepository.updateOwnProfile("Seamus", 40)
        PlayerProfileRepository.updateProfile(StoreScreen(nextSoftwareLevels = mapOf("Spam" to 21)))
        TargetRepository.importDataBundle(oldExport)

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
