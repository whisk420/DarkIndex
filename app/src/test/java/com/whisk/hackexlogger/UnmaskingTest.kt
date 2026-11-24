package com.whisk.hackexlogger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Unit tests for target unmasking and verification logic.
 *
 * Rewritten 2026-11-20: the old versions of the last five tests re-implemented the production logic
 * inside the test and asserted on their own copy, so they could never fail. They now call the real
 * code (RecordMerger.mergeRecords, DatabaseManager.processUpdates, DatabaseMaintenance.runDatabaseMaintenance).
 */
class UnmaskingTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = TestEnv.freshRepository()
    }

    @Test
    fun maskedUnknownAndPlaceholderIpsAreToldApart() {
        assertEquals("[UNKNOWN]", CommonParserUtil.extractIp("Device accessed from [UNKNOWN]"))
        for (masked in listOf("[UNKNOWN]", "10.20.xxx.xxx")) {
            assertTrue(masked, TargetRecord(ip = masked).isMasked)
            assertFalse(masked, TargetRecord(ip = masked).isTemporary)
        }
        val placeholder = TargetRecord(ip = "unknown:jaywat2550", username = "jaywat2550")
        assertFalse(placeholder.isMasked)
        assertTrue(placeholder.isTemporary)
    }

    @Test
    fun testUnmaskingDoesNotInheritGenericMaskedSoftwareOrWallets() {
        // Generic masked record that collected software/wallets from several masked users
        val maskedRecord = TargetRecord(
            ip = "xxx.xxx.xxx.xxx",
            username = "jaywat2550",
            wallets = mutableListOf("hxPollutedWallet1234"),
            downloads = mutableMapOf(
                "Spam" to SoftwareEntry(10, "installed", null),
                "Rootkit" to SoftwareEntry(5, "installed", null)
            )
        )
        // The real, unmasked record for the same user
        val unmaskedRecord = TargetRecord(
            ip = "4.226.131.111",
            username = "jaywat2550",
            wallets = mutableListOf("hxCleanWallet5678"),
            downloads = mutableMapOf("Firewall" to SoftwareEntry(8, "installed", null))
        )

        // Both argument orders must give the same answer
        for (merged in listOf(
            RecordMerger.mergeRecords(maskedRecord, unmaskedRecord),
            RecordMerger.mergeRecords(unmaskedRecord, maskedRecord)
        )) {
            assertEquals("4.226.131.111", merged.ip)
            assertEquals(listOf("hxCleanWallet5678"), merged.wallets)
            assertEquals(setOf("Firewall"), merged.downloads.keys)
        }
        // Different "masked vs real" data is dropped, not a wallet conflict
        assertTrue(TestEnv.conflictLog(dir).isEmpty())
    }

    @Test
    fun testUnmaskingInheritsUserSpecificTemporarySoftware() {
        // Placeholder created when jaywat2550's software screen was scraped behind a proxy
        val userSpecificTempRecord = TargetRecord(
            ip = "unknown:jaywat2550",
            username = "jaywat2550",
            downloads = mutableMapOf("Spam" to SoftwareEntry(10, "installed", null))
        )
        val unmaskedRecord = TargetRecord(
            ip = "4.226.131.111",
            username = "jaywat2550",
            downloads = mutableMapOf("Firewall" to SoftwareEntry(8, "installed", null))
        )

        val merged = RecordMerger.mergeRecords(userSpecificTempRecord, unmaskedRecord)

        // The real IP wins over the placeholder, and the user-specific software is kept
        assertEquals("4.226.131.111", merged.ip)
        assertEquals(setOf("Firewall", "Spam"), merged.downloads.keys)
        assertEquals(10, merged.downloads["Spam"]?.level)
    }

    @Test
    fun testLogSoftwareIgnoredForMaskedIp() {
        val update = ParsedUpdate(
            ip = "4.226.131.xxx",
            time = "11-19 12:00",
            software = Software("downloaded", 10, "Spam"),
            isOwnedSoftware = true,
            raw = "Downloaded Lv10 Spam from 4.226.131.xxx"
        )

        DatabaseManager.processUpdates(listOf(update))

        // No record anywhere may receive software from a masked log line
        assertTrue(TargetRepository.getAllRecords().all { it.downloads.isEmpty() })

        // Control: the same line with a real IP is stored
        DatabaseManager.processUpdates(listOf(update.copy(ip = "4.226.131.111", raw = "Downloaded Lv10 Spam from 4.226.131.111")))
        assertEquals(10, TargetRepository.getAllRecords().single { it.ip == "4.226.131.111" }.downloads["Spam"]?.level)
    }

    @Test
    fun walletScreenJoinsTheUsernameAndWalletPlaceholders() {
        // Software screen → unknown:Pizaaparty; unattributed steal → unknown:wallet_hx9999
        DatabaseManager.processSoftwareScreen(SoftwareScreen("Pizaaparty", mapOf("Spam" to SoftwareScreenItem(10, "installed"))))
        DatabaseManager.processUpdates(listOf(ParsedUpdate(ip = "", time = "10-16 12:00", wallet = "hx9999", stolenAmount = 500,
            raw = "Stole 500 Crypto from hx9999", isOwnLogTheft = true)))
        assertEquals(2, TargetRepository.getAllRecords().size)

        DatabaseManager.processWalletScreen(WalletScreen(username = "Pizaaparty", wallet = "hx9999"))

        val target = TargetRepository.getAllRecords().single()
        assertEquals("Pizaaparty", target.username)
        assertEquals(listOf("hx9999"), target.wallets)
        assertEquals(1, target.downloads.size)
        assertNotNull(target.walletThefts["hx9999"])
    }

    @Test
    fun testLogWalletRedirectedToOwnerWhenIpIsMasked() {
        TargetRepository.saveAllRecords(listOf(
            TargetRecord(ip = "4.226.131.111", username = "jaywat2550", wallets = mutableListOf("hx9999...1111"))
        ))

        val update = ParsedUpdate(
            ip = "4.226.131.xxx",
            time = "11-19 12:00",
            wallet = "hx9999...1111",
            stolenAmount = 1000,
            raw = "Stole 1,000 Crypto from hx9999...1111",
            isOwnLogTheft = true
        )
        DatabaseManager.processUpdates(listOf(update))

        val records = TargetRepository.getAllRecords()
        // The theft lands on the wallet's real owner, and no masked record is created
        assertEquals(listOf("4.226.131.111"), records.map { it.ip })
        val thefts = records.single().walletThefts["hx9999...1111"]
        assertEquals(1, thefts?.size)
        assertEquals(1000, thefts?.first()?.amount)
    }

    @Test
    fun testLogWalletWithMaskedIpAndNoOwnerGoesToPlaceholder() {
        val update = ParsedUpdate(
            ip = "xxx.xxx.xxx.xxx",
            time = "11-19 12:00",
            wallet = "hxaaaa...bbbb",
            stolenAmount = 50,
            raw = "Stole 50 Crypto from hxaaaa...bbbb",
            isOwnLogTheft = true
        )
        DatabaseManager.processUpdates(listOf(update))

        val records = TargetRepository.getAllRecords()
        assertEquals(listOf("unknown:wallet_hxaaaa...bbbb"), records.map { it.ip })
        assertEquals(listOf("hxaaaa...bbbb"), records.single().wallets)
    }

    @Test
    fun testDatabaseMaintenanceLogic() {
        // Several log-inferred wallets on one target are allowed now (developer rule 2026-11-20): left alone
        val multiLogWallets = TargetRecord(
            ip = "159.164.200.186",
            username = "Nelzayy",
            wallets = mutableListOf("hx5dbe...0478", "hxe9cb...0ebc")
        )
        // A proven (wallet-screen) wallet plus a stray extra: the extra goes
        val provenPlusExtra = TargetRecord(
            ip = "160.1.1.1",
            wallets = mutableListOf("hxPROV...0001", "hxXTRA...0002"),
            walletSource = WalletSource.PAGE
        ).withTheftEvent("hxXTRA...0002", 1000L, 9)
        val maskedSoftwareRecord = TargetRecord(
            ip = "103.249.xxx.xxx",
            downloads = mutableMapOf("Rootkit" to SoftwareEntry(6, "installed", null))
        )
        val maskedWalletRecord = TargetRecord(
            ip = "101.103.xxx.xxx",
            wallets = mutableListOf("hx16a5...aac4")
        )
        TargetRepository.saveAllRecords(listOf(multiLogWallets, provenPlusExtra, maskedSoftwareRecord, maskedWalletRecord))

        val result = DatabaseMaintenance.runDatabaseMaintenance()

        assertEquals(1, result.multiWalletRecordsFixed)
        assertEquals(1, result.maskedSoftwareCleared)
        assertEquals(1, result.maskedWalletsMigrated)
        // A backup is written before anything changes
        assertTrue(File(result.backupFilePath).exists())

        val byIp = TargetRepository.getAllRecords().associateBy { it.ip }
        assertEquals(listOf("hx5dbe...0478", "hxe9cb...0ebc"), byIp.getValue("159.164.200.186").wallets)
        assertEquals(listOf("hxPROV...0001"), byIp.getValue("160.1.1.1").wallets)
        assertNull(byIp.getValue("160.1.1.1").walletThefts["hxXTRA...0002"])
        // Masked software cleared
        assertTrue(byIp.getValue("103.249.xxx.xxx").downloads.isEmpty())
        // Masked wallet migrated to a placeholder
        assertTrue(byIp.getValue("101.103.xxx.xxx").wallets.isEmpty())
        assertEquals(listOf("hx16a5...aac4"), byIp["unknown:wallet_hx16a5...aac4"]?.wallets)

        val log = TestEnv.conflictLog(dir)
        assertEquals(1, log.size)
        assertTrue("MaintenanceDroppedUnproven" in log[0] && "hxXTRA...0002" in log[0])
    }
}
