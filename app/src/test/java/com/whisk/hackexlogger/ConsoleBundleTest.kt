package com.whisk.hackexlogger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Unit tests verifying JSON data bundle import, export, and schema validation.
 */
class ConsoleBundleTest {

    @Test
    fun testExportAndImportBundle() {
        val sampleJson = """
            {
              "format": "target-intelligence-console",
              "version": 1,
              "exportedAt": "2026-09-19T15:13:38.782Z",
              "targets": [
                {
                  "ip": "10.20.248.84",
                  "username": "Pizaaparty",
                  "clan": null,
                  "level": 17,
                  "hardware": "Raider II • Cable",
                  "firewall": 11,
                  "encryptor": 12,
                  "wallets": ["hx1234...5678"],
                  "downloads": {
                    "Siphon": {
                      "level": 6,
                      "status": "installed"
                    }
                  }
                }
              ]
            }
        """.trimIndent()

        val gson = com.google.gson.Gson()
        val bundle = gson.fromJson(sampleJson, ConsoleBundle::class.java)

        assertEquals("target-intelligence-console", bundle.format)
        assertEquals(1, bundle.version)
        assertNotNull(bundle.targets)
        val targets = bundle.targets ?: emptyArray()
        assertEquals(1, targets.size)

        var target = targets[0]
        assertEquals("10.20.248.84", target.ip)
        assertEquals("Pizaaparty", target.username)
        assertEquals(17, target.level)
        assertEquals(11, target.firewall)
        assertEquals(12, target.encryptor)
        assertEquals(1, target.wallets.size)
        assertEquals("hx1234...5678", target.wallets[0])
        assertEquals(1, target.downloads.size)
        assertEquals(6, target.downloads["Siphon"]?.level)
        assertEquals("installed", target.downloads["Siphon"]?.status)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testImportRejectsWrongVersion() {
        TestEnv.freshRepository()
        DatabaseBackup.importDataBundle("""{"format": "target-intelligence-console", "version": 2}""")
    }

    @Test(expected = IllegalArgumentException::class)
    fun testImportRejectsMalformedJson() {
        TestEnv.freshRepository()
        DatabaseBackup.importDataBundle("{ not json")
    }

    @Test(expected = IllegalArgumentException::class)
    fun testImportInvalidFormat() {
        val invalidJson = """
            {
              "format": "wrong-format",
              "version": 1
            }
        """.trimIndent()

        // Was tautological (the test threw the exception itself). Now the real importer must reject it.
        TestEnv.freshRepository()
        DatabaseBackup.importDataBundle(invalidJson)
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

