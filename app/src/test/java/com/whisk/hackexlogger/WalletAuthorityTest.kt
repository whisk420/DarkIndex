package com.whisk.hackexlogger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Owner rule (2026-11-20): the target's wallet screen is proof of its wallet. Log-inferred wallets are only
 * "highly probable", so a target can hold several; they never replace or move a wallet. Only a wallet-screen
 * scrape can replace a wallet (even a previous wallet-screen one, since wallets can rarely change).
 */
class WalletAuthorityTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = TestEnv.freshRepository()
    }

    private fun record(ip: String) = TargetRepository.getAllRecords().find { it.ip == ip }

    private fun steal(ip: String, wallet: String, amount: Int, time: String = "11-19 12:00") =
        ParsedUpdate(ip = ip, time = time, wallet = wallet, stolenAmount = amount, raw = "Stole $amount from $wallet", isOwnLogTheft = true)

    @Test
    fun logFillsAMissingWallet() {
        DatabaseManager.processUpdates(listOf(steal("1.1.1.1", "hxLOG1", 100)))
        val target = record("1.1.1.1")!!
        assertEquals(listOf("hxLOG1"), target.wallets)
        assertEquals(TargetRecord.WALLET_FROM_LOG, target.walletSource)
    }

    @Test
    fun logsCanCollectSeveralCandidateWallets() {
        DatabaseManager.processUpdates(listOf(steal("1.1.1.1", "hxLOG1", 100)))
        DatabaseManager.processUpdates(listOf(steal("1.1.1.1", "hxLOG2", 200, "11-19 13:00")))
        val target = record("1.1.1.1")!!
        assertEquals(listOf("hxLOG1", "hxLOG2"), target.wallets)
        assertEquals(1, target.walletThefts["hxLOG2"]?.size)
        assertTrue(TestEnv.conflictLog(dir).isEmpty())
    }

    @Test
    fun walletScreenReplacesLogWalletsAndDropsTheirThefts() {
        TargetRepository.saveAllRecords(listOf(TargetRecord(ip = "1.1.1.1", username = "Alice")))
        DatabaseManager.processUpdates(listOf(steal("1.1.1.1", "hxLOG1", 100), steal("1.1.1.1", "hxREAL", 300, "11-19 13:00")))

        DatabaseManager.processWalletScreen(WalletScreen(username = "Alice", wallet = "hxREAL"))

        val target = record("1.1.1.1")!!
        assertEquals(listOf("hxREAL"), target.wallets)
        assertTrue(target.walletFromPage)
        assertNull(target.walletThefts["hxLOG1"])
        assertEquals(listOf(300), target.walletThefts["hxREAL"]?.map { it.amount })
    }

    @Test
    fun logCannotAddToAProvenWallet() {
        TargetRepository.saveAllRecords(listOf(TargetRecord(ip = "1.1.1.1", username = "Alice")))
        DatabaseManager.processWalletScreen(WalletScreen(username = "Alice", wallet = "hxREAL"))

        DatabaseManager.processUpdates(listOf(steal("1.1.1.1", "hxOTHER", 50)))

        assertEquals(listOf("hxREAL"), record("1.1.1.1")!!.wallets)
        assertTrue(TestEnv.conflictLog(dir).any { "LogUpdate" in it && "hxOTHER" in it })
    }

    @Test
    fun aNewerWalletScreenReplacesAnOlderOne() {
        TargetRepository.saveAllRecords(listOf(TargetRecord(ip = "1.1.1.1", username = "Alice")))
        DatabaseManager.processWalletScreen(WalletScreen(username = "Alice", wallet = "hxOLD"))
        DatabaseManager.processWalletScreen(WalletScreen(username = "Alice", wallet = "hxNEW"))
        assertEquals(listOf("hxNEW"), record("1.1.1.1")!!.wallets)
    }

    @Test
    fun logCannotMoveAWalletFromAnotherTarget() {
        TargetRepository.saveAllRecords(listOf(TargetRecord(ip = "1.1.1.1", wallets = mutableListOf("hxW"), walletSource = TargetRecord.WALLET_FROM_LOG)))

        DatabaseManager.processUpdates(listOf(steal("2.2.2.2", "hxW", 70)))

        assertEquals(listOf("hxW"), record("1.1.1.1")!!.wallets)
        assertTrue(record("2.2.2.2")?.wallets.isNullOrEmpty())
        assertTrue(TestEnv.conflictLog(dir).any { "LogWalletOwnedElsewhere" in it })
    }

    @Test
    fun walletScreenTakesTheWalletFromAnotherTargetWithItsThefts() {
        val wrong = TargetRecord(ip = "1.1.1.1", wallets = mutableListOf("hxW"), walletSource = TargetRecord.WALLET_FROM_LOG)
            .apply { addTheftEvent("hxW", 1000L, 40) }
        TargetRepository.saveAllRecords(listOf(wrong, TargetRecord(ip = "2.2.2.2", username = "Bob")))

        DatabaseManager.processWalletScreen(WalletScreen(username = "Bob", wallet = "hxW"))

        assertTrue(record("1.1.1.1")!!.wallets.isEmpty())
        assertNull(record("1.1.1.1")!!.walletSource)
        val bob = record("2.2.2.2")!!
        assertEquals(listOf("hxW"), bob.wallets)
        assertEquals(listOf(40), bob.walletThefts["hxW"]?.map { it.amount })
        assertTrue(TestEnv.conflictLog(dir).any { "WalletScreenMovedFrom" in it && "1.1.1.1" in it })
    }

    @Test
    fun walletPlaceholderStillJoinsItsTargetFromALog() {
        // Unattributed steal → placeholder; later a log line pairs the wallet with an IP → they merge
        DatabaseManager.processUpdates(listOf(steal("", "hxP", 10)))
        DatabaseManager.processUpdates(listOf(steal("3.3.3.3", "hxP", 20, "11-19 14:00")))
        assertNull(record("unknown:wallet_hxP"))
        assertEquals(listOf(10, 20), record("3.3.3.3")!!.walletThefts["hxP"]?.map { it.amount })
    }

    @Test
    fun oldRecordsWithoutASourceBehaveLikeLogWallets() {
        // Records saved before 2026-11-20 have no walletSource
        TargetRepository.saveAllRecords(listOf(TargetRecord(ip = "1.1.1.1", wallets = mutableListOf("hxOLD"))))
        DatabaseManager.processUpdates(listOf(steal("1.1.1.1", "hxNEW", 5)))
        assertEquals(listOf("hxOLD", "hxNEW"), record("1.1.1.1")!!.wallets)
        assertFalse(record("1.1.1.1")!!.walletFromPage)
    }
}
