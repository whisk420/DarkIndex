package com.whisk.hackexlogger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RecordMergingTest {

    @Before
    fun setUp() {
        TargetRepository.prefs = FakeSharedPreferences()
        TargetRepository.cachedRecords.clear()
        TargetRepository.isInitialized = true
    }

    @Test
    fun testWalletScreenScrapeMergesUsernameAndWalletPlaceholders() {
        // 1. Scrape software screen -> creates unknown:Pizaaparty placeholder
        val softwareScreen = SoftwareScreen(
            username = "Pizaaparty",
            softwareItems = mapOf("Spam" to SoftwareScreenItem(10, "installed"))
        )
        DatabaseManager.processSoftwareScreen(softwareScreen)

        // 2. Scrape log screen -> creates unknown:wallet_hx9999 placeholder
        val logUpdate = ParsedUpdate(
            ip = "",
            time = "10-16 12:00",
            wallet = "hx9999",
            stolenAmount = 500,
            raw = "Stole 500 Crypto from hx9999",
            isOwnLogTheft = true
        )
        DatabaseManager.processUpdates(listOf(logUpdate))

        val initialRecords = TargetRepository.getAllRecords()
        assertEquals(2, initialRecords.size)

        // 3. Scrape Wallet Screen -> links username "Pizaaparty" to wallet "hx9999"
        val walletScreen = WalletScreen(username = "Pizaaparty", wallet = "hx9999")
        DatabaseManager.processWalletScreen(walletScreen)

        val mergedRecords = TargetRepository.getAllRecords()
        // CRITICAL CHECK: The two placeholders must merge into a single record!
        assertEquals(1, mergedRecords.size)

        val target = mergedRecords[0]
        assertEquals("Pizaaparty", target.username)
        assertTrue(target.wallets.contains("hx9999"))
        assertEquals(1, target.downloads.size)
        assertNotNull(target.walletThefts["hx9999"])
    }

    @Test
    fun testRealIpPrevailsOverTemporaryPlaceholderWhenMerging() {
        // Create temporary record unknown:UserX with wallet hx123
        val tempRecord = TargetRecord(ip = "unknown:UserX", username = "UserX", wallets = mutableListOf("hx123"))
        // Create real record 192.168.1.100 with no wallet
        val realRecord = TargetRecord(ip = "192.168.1.100", username = "UserX")

        TargetRepository.saveAllRecords(listOf(tempRecord, realRecord))

        val merged = TargetRepository.mergeRecords(tempRecord, realRecord)

        // CRITICAL CHECK: Real IP 192.168.1.100 must prevail over unknown:UserX!
        assertEquals("192.168.1.100", merged.ip)
        assertEquals("UserX", merged.username)
        assertTrue(merged.wallets.contains("hx123"))
    }
}
