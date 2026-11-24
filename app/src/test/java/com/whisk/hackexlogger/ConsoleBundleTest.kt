package com.whisk.hackexlogger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Unit tests verifying JSON data bundle import, export, and schema validation.
 */
class ConsoleBundleTest {

    @Test
    fun importRejectsWrongVersionMalformedJsonAndWrongFormat() {
        TestEnv.freshRepository()
        for (bad in listOf(
            """{"format": "target-intelligence-console", "version": 2}""",
            "{ not json",
            """{"format": "wrong-format", "version": 1}"""
        )) {
            assertThrows(bad, IllegalArgumentException::class.java) { DatabaseBackup.importDataBundle(bad) }
        }
    }

    @Test
    fun testExportImportRoundTripPreservesThefts() {
        TestEnv.freshRepository()

        var target = TargetRecord(ip = "1.2.3.4", username = "Thief")
        target = target.withTheftEvent("hx999", 1000L, 500)
        target = target.withTheftEvent("hx999", 2000L, 1000)
        TargetRepository.saveAllRecords(listOf(target))

        val exportJson = DatabaseBackup.exportDataBundle()
        
        // Clear and re-import
        TargetRepository.resetForTests()
        val importResult = DatabaseBackup.importDataBundle(exportJson)
        
        assertEquals(1, importResult.targetCount)
        val records = TargetRepository.getAllRecords()
        assertEquals(1, records.size)
        
        val restoredTarget = records[0]
        assertEquals(2, restoredTarget.walletThefts["hx999"]?.size)
        assertEquals(1000L, restoredTarget.walletThefts["hx999"]?.get(0)?.timestampMs)
        assertEquals(500, restoredTarget.walletThefts["hx999"]?.get(0)?.amount)
        assertEquals(2000L, restoredTarget.walletThefts["hx999"]?.get(1)?.timestampMs)
        assertEquals(1000, restoredTarget.walletThefts["hx999"]?.get(1)?.amount)
    }
}

