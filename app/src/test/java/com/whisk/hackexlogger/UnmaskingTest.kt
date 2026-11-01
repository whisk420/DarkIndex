package com.whisk.hackexlogger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for target unmasking and verification logic.
 */
class UnmaskingTest {

    @Test
    fun testUnknownIpIsClassifiedAsMasked() {
        val extractedIp = HackExParser.extractIp("Device accessed from [UNKNOWN]")
        assertEquals("[UNKNOWN]", extractedIp)

        val target = TargetRecord(ip = "[UNKNOWN]")
        assertTrue(target.isMasked)
        assertFalse(target.isTemporary)
    }

    @Test
    fun testMaskedIpIsClassifiedAsMasked() {
        val target = TargetRecord(ip = "10.20.xxx.xxx")
        assertTrue(target.isMasked)
        assertFalse(target.isTemporary)
    }

    @Test
    fun testTemporaryRecordIsClassifiedAsTemporary() {
        val target = TargetRecord(ip = "unknown:jaywat2550", username = "jaywat2550")
        assertFalse(target.isMasked)
        assertTrue(target.isTemporary)
    }

    @Test
    fun testUnmaskingDoesNotInheritGenericMaskedSoftwareOrWallets() {
        // Generic masked record containing aggregated software/wallets from multiple masked users
        val maskedRecord = TargetRecord(
            ip = "xxx.xxx.xxx.xxx",
            username = "jaywat2550",
            wallets = mutableListOf("hxPollutedWallet1234"),
            downloads = mutableMapOf(
                "Spam" to SoftwareEntry(10, "installed", null),
                "Rootkit" to SoftwareEntry(5, "installed", null)
            )
        )

        // Concrete unmasked record for jaywat2550 with his own software
        val unmaskedRecord = TargetRecord(
            ip = "4.226.131.111",
            username = "jaywat2550",
            wallets = mutableListOf("hxCleanWallet5678"),
            downloads = mutableMapOf(
                "Firewall" to SoftwareEntry(8, "installed", null)
            )
        )

        // Simulate merging during reconcile
        val baseIsMasked = unmaskedRecord.isMasked
        val incomingIsMasked = maskedRecord.isMasked
        val ignoreMaskedData = (baseIsMasked != incomingIsMasked)

        assertTrue(ignoreMaskedData)

        val finalWallets = if (ignoreMaskedData) unmaskedRecord.wallets.toMutableList() else mutableListOf()
        val finalDownloads = if (ignoreMaskedData) unmaskedRecord.downloads.toMutableMap() else mutableMapOf()

        // Unmasked record retains only its clean wallet & software
        assertEquals(1, finalWallets.size)
        assertEquals("hxCleanWallet5678", finalWallets[0])

        assertEquals(1, finalDownloads.size)
        assertNotNull(finalDownloads["Firewall"])
        assertNull(finalDownloads["Spam"])
        assertNull(finalDownloads["Rootkit"])
    }

    @Test
    fun testUnmaskingInheritsUserSpecificTemporarySoftware() {
        // User-specific placeholder created when scraping jaywat2550's software screen while proxy was active
        val userSpecificTempRecord = TargetRecord(
            ip = "unknown:jaywat2550",
            username = "jaywat2550",
            downloads = mutableMapOf(
                "Spam" to SoftwareEntry(10, "installed", null)
            )
        )

        // Concrete unmasked record for jaywat2550 when proxy is down
        val unmaskedRecord = TargetRecord(
            ip = "4.226.131.111",
            username = "jaywat2550",
            downloads = mutableMapOf(
                "Firewall" to SoftwareEntry(8, "installed", null)
            )
        )

        val baseIsMasked = unmaskedRecord.isMasked
        val incomingIsMasked = userSpecificTempRecord.isMasked
        val ignoreMaskedData = (baseIsMasked != incomingIsMasked)

        assertFalse(ignoreMaskedData)

        val finalDownloads = (unmaskedRecord.downloads + userSpecificTempRecord.downloads).toMutableMap()

        // Unmasked record receives both its firewall and jaywat2550's scraped spam software
        assertEquals(2, finalDownloads.size)
        assertNotNull(finalDownloads["Firewall"])
        assertNotNull(finalDownloads["Spam"])
    }

    @Test
    fun testLogSoftwareIgnoredForMaskedIp() {
        val maskedTarget = TargetRecord(ip = "4.226.131.xxx")
        val update = HackExParser.ParsedUpdate(
            ip = "4.226.131.xxx",
            time = "12:00",
            software = HackExParser.Software("downloaded", 10, "Spam"),
            isOwnedSoftware = true,
            raw = "Downloaded Lv10 Spam from 4.226.131.xxx"
        )

        // Simulating software update logic in applyUpdatesToMap
        if (update.software != null && update.isOwnedSoftware && !maskedTarget.isMasked) {
            maskedTarget.downloads[update.software.name] = SoftwareEntry(update.software.level, update.software.action, update.time)
        }

        // Software is ignored because target IP is masked
        assertTrue(maskedTarget.downloads.isEmpty())
    }

    @Test
    fun testLogWalletRedirectedToOwnerWhenIpIsMasked() {
        val unmaskedOwner = TargetRecord(
            ip = "4.226.131.111",
            username = "jaywat2550",
            wallets = mutableListOf("hx9999...1111")
        )

        val recordMap = mutableMapOf(
            "4.226.131.111" to unmaskedOwner,
            "4.226.131.xxx" to TargetRecord(ip = "4.226.131.xxx")
        )

        val update = HackExParser.ParsedUpdate(
            ip = "4.226.131.xxx",
            time = "12:00",
            wallet = "hx9999...1111",
            stolenAmount = 1000,
            raw = "Stole 1,000 Crypto from hx9999...1111"
        )

        // Simulating wallet redirection in applyUpdatesToMap
        var ip = update.ip
        val isIpMasked = ip.isEmpty() || ip.contains("xxx", ignoreCase = true) || ip.contains("UNKNOWN", ignoreCase = true)
        if (update.wallet != null && isIpMasked) {
            val existingOwner = recordMap.values.find { it.wallets.contains(update.wallet) }
            if (existingOwner != null) {
                ip = existingOwner.ip
            }
        }

        // Resolved IP is jaywat2550's unmasked IP
        assertEquals("4.226.131.111", ip)
    }
}
