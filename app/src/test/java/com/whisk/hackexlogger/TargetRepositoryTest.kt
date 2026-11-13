package com.whisk.hackexlogger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TargetRepositoryTest {

    @Before
    fun setUp() {
        TargetRepository.prefs = FakeSharedPreferences()
        TargetRepository.cachedRecords.clear()
        TargetRepository.isInitialized = true
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
        val targetA = TargetRecord(ip = "10.0.0.1", username = "UserA", wallets = mutableListOf("hx111"))
        val targetB = TargetRecord(ip = "10.0.0.2", username = "UserB", wallets = mutableListOf("hx222"))
        TargetRepository.saveAllRecords(listOf(targetA, targetB))

        // Change 10.0.0.2 to 10.0.0.1 -> Should merge targetB into targetA
        TargetRepository.editTargetIp("10.0.0.2", "10.0.0.1")

        val records = TargetRepository.getAllRecords()
        assertEquals(1, records.size)
        assertEquals("10.0.0.1", records[0].ip)
    }

    @Test
    fun testDeleteTargetRemovesRecord() {
        val target = TargetRecord(ip = "10.0.0.50", username = "ToDelete")
        TargetRepository.saveAllRecords(listOf(target))

        assertEquals(1, TargetRepository.getAllRecords().size)

        TargetRepository.deleteTarget("10.0.0.50")

        assertEquals(0, TargetRepository.getAllRecords().size)
    }

    @Test
    fun testRunDatabaseMaintenancePurgesOldTheftGaps() {
        val target = TargetRecord(ip = "10.0.0.99", username = "TheftTarget")

        // Add thefts: 1st hack at t=0, 2nd hack at t=30h (gap > 24h)
        val t0 = 1000000L
        val t1 = t0 + (30 * 3600 * 1000L) // 30 hours later
        target.addTheftEvent("hxWallet1", t0, 500)
        target.addTheftEvent("hxWallet1", t1, 1000)

        TargetRepository.saveAllRecords(listOf(target))

        val result = TargetRepository.runDatabaseMaintenance()
        assertTrue(result.oldTheftsCleared > 0)

        val updatedRecords = TargetRepository.getAllRecords()
        val updatedTarget = updatedRecords.first { it.ip == "10.0.0.99" }
        val thefts = updatedTarget.walletThefts["hxWallet1"]
        assertNotNull(thefts)
        // Verify old theft event before the >24h gap was purged
        assertEquals(1, thefts?.size)
        assertEquals(t1, thefts?.first()?.timestampMs)
    }
}
