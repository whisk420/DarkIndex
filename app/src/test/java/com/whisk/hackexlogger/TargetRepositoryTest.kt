package com.whisk.hackexlogger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class TargetRepositoryTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = TestEnv.freshRepository()
    }

    @Test
    fun testMarkTargetValidatedDoesNotWipeDatabase() {
        val target1 = TargetRecord(ip = "192.168.1.100", username = "Target1")
        val target2 = TargetRecord(ip = "192.168.1.101", username = "Target2")
        TargetRepository.saveAllRecords(listOf(target1, target2))

        assertEquals(2, TargetRepository.getAllRecords().size)

        // Mark target validated
        TargetRepository.markTargetValidated("192.168.1.100")

        val recordsAfter = TargetRepository.getAllRecords()
        // CRITICAL CHECK: Ensure database size is still 2 and NOT wiped!
        assertEquals(2, recordsAfter.size)

        val validatedTarget = recordsAfter.find { it.ip == "192.168.1.100" }
        assertNotNull(validatedTarget)
        assertNotNull(validatedTarget?.lastValidated)
    }

    @Test
    fun testEditTargetIpCollidesAndMerges() {
        var targetA = TargetRecord(ip = "10.0.0.1", username = "UserA", wallets = mutableListOf("hx111"))
        val targetB = TargetRecord(ip = "10.0.0.2", username = "UserB", wallets = mutableListOf("hx222"))
        TargetRepository.saveAllRecords(listOf(targetA, targetB))

        // Change 10.0.0.2 to 10.0.0.1 -> Should merge targetB into targetA
        TargetRepository.editTargetIp("10.0.0.2", "10.0.0.1")

        val records = TargetRepository.getAllRecords()
        assertEquals(1, records.size)
        assertEquals("10.0.0.1", records[0].ip)
        // Both wallets came from logs (not proof), so both are kept as candidates and nothing is logged
        assertEquals(listOf("hx111", "hx222"), records[0].wallets)
        assertTrue(TestEnv.conflictLog(dir).isEmpty())
    }

    @Test
    fun testEditTargetIpMergeKeepsTheProvenWallet() {
        var targetA = TargetRecord(ip = "10.0.0.1", username = "UserA", wallets = mutableListOf("hx111"))
        val targetB = TargetRecord(ip = "10.0.0.2", username = "UserB", wallets = mutableListOf("hx222"), walletSource = WalletSource.PAGE)
        targetA = targetA.withTheftEvent("hx111", 1000L, 5)
        TargetRepository.saveAllRecords(listOf(targetA, targetB))

        TargetRepository.editTargetIp("10.0.0.2", "10.0.0.1")

        val merged = TargetRepository.getAllRecords().single()
        assertEquals(listOf("hx222"), merged.wallets)
        assertTrue(merged.walletFromPage)
        assertTrue(merged.walletThefts["hx111"] == null)   // the unproven wallet's thefts are dropped
        val log = TestEnv.conflictLog(dir)
        assertEquals(1, log.size)
        assertTrue(log[0].contains("\"existing_wallet\":\"hx222\"") && log[0].contains("\"conflicting_wallet\":\"hx111\""))
    }

    @Test
    fun testNoMirrorWithoutBackupFolder() {
        // No Downloads folder available (what the JVM sees without an override): save still works, no file
        TargetRepository.backupDirProvider = { null }
        TargetRepository.saveAllRecords(listOf(TargetRecord(ip = "10.0.0.8")))
        assertEquals(1, TargetRepository.getAllRecords().size)
        assertFalse(File(dir, "DarkIndex").exists())
    }

    @Test
    fun testDeleteTargetRemovesRecord() {
        var target = TargetRecord(ip = "10.0.0.50", username = "ToDelete")
        TargetRepository.saveAllRecords(listOf(target))

        assertEquals(1, TargetRepository.getAllRecords().size)

        TargetRepository.deleteTarget("10.0.0.50")

        assertEquals(0, TargetRepository.getAllRecords().size)
    }

    @Test
    fun testRunDatabaseMaintenanceDoesNotPurgeThefts() {
        var target = TargetRecord(ip = "10.0.0.99", username = "TheftTarget")

        // Add thefts: 1st hack at t=0, 2nd hack at t=30h (gap > 1h)
        val t0 = 1000000L
        val t1 = t0 + (30 * 3600 * 1000L) // 30 hours later
        target = target.withTheftEvent("hxWallet1", t0, 500)
        target = target.withTheftEvent("hxWallet1", t1, 1000)
        TargetRepository.saveAllRecords(listOf(target))

        DatabaseMaintenance.runDatabaseMaintenance()

        val updatedRecords = TargetRepository.getAllRecords()
        var updatedTarget = updatedRecords.first { it.ip == "10.0.0.99" }
        val thefts = updatedTarget.walletThefts["hxWallet1"]
        assertNotNull(thefts)
        // Verify no theft event was purged by maintenance!
        assertEquals(2, thefts?.size)
    }

    @Test
    fun testPurgeOldTheftEventsSafetyShield() {
        var target = TargetRecord(ip = "10.0.0.100", username = "OldTarget")

        val cutoff = System.currentTimeMillis() - 30 * 24 * 3600000L
        
        // Add old thefts from 40 days ago that form a reliable cluster (>2 events, gaps < 1h)
        val oldTs1 = cutoff - 10 * 24 * 3600000L
        val oldTs2 = oldTs1 + 1800000L // 30 mins later
        target = target.withTheftEvent("hxWallet2", oldTs1, 500)
        target = target.withTheftEvent("hxWallet2", oldTs2, 1000)
        TargetRepository.saveAllRecords(listOf(target))
        
        // Even though these events are older than 30 days, they form the ONLY reliable cluster for this target.
        // Therefore, purgeOldTheftEvents() should PRESERVE them.
        val purgedCount = DatabaseMaintenance.purgeOldTheftEvents()
        assertEquals(0, purgedCount)
        
        val updatedRecords = TargetRepository.getAllRecords()
        var updatedTarget = updatedRecords.first { it.ip == "10.0.0.100" }
        assertEquals(2, updatedTarget.walletThefts["hxWallet2"]?.size)
        
        // Now add a recent reliable cluster!
        val newTs1 = System.currentTimeMillis() - 2 * 24 * 3600000L
        val newTs2 = newTs1 + 1800000L // 30 mins later
        updatedTarget = updatedTarget.withTheftEvent("hxWallet2", newTs1, 500)
        updatedTarget = updatedTarget.withTheftEvent("hxWallet2", newTs2, 1000)
        TargetRepository.saveAllRecords(listOf(updatedTarget))
        
        // Because there is now a new reliable cluster, the old one can safely be purged!
        val purgedCount2 = DatabaseMaintenance.purgeOldTheftEvents()
        assertEquals(2, purgedCount2)
        
        val finalRecords = TargetRepository.getAllRecords()
        val finalTarget = finalRecords.first { it.ip == "10.0.0.100" }
        assertEquals(2, finalTarget.walletThefts["hxWallet2"]?.size) // Only new ones left
    }
}
