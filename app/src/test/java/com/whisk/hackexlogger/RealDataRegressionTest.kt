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
 * End-to-end regression tests on real screen text captured with Raw Dump Mode (2026-11-19/20).
 * Fixtures live in `src/test/resources/fixtures/` (plain .txt; `*.json` is git-ignored).
 * Each test runs the same classify → parse → save path the accessibility service uses.
 */
class RealDataRegressionTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = TestEnv.freshRepository()
    }

    private fun scrape(fixture: String): ScrapePipeline.Result {
        val raw = TestEnv.fixture(fixture)
        return ScrapePipeline.run(raw, raw.split("\n"))
    }

    private fun record(ip: String) = TargetRepository.getAllRecords().find { it.ip == ip }

    private fun theftsOf(ip: String, wallet: String) = record(ip)?.walletThefts?.get(wallet)?.map { it.amount }

    // ---- Log attribution ------------------------------------------------------------------------

    @Test
    fun ownLogDoubleStealAttribution() {
        val updates = LogScreenParser.parse(TestEnv.fixture("log_2026-11-19.txt").split("\n"))
        fun ipOf(wallet: String, amount: Int) = updates.single { it.wallet == wallet && it.stolenAmount == amount }.ip

        // Owner-confirmed (2026-11-19): the first steal after "Accessed device at 8.83.197.176" is that
        // target's wallet; the game skipped the next Accessed line, so the second steal stays unattributed.
        assertEquals("8.83.197.176", ipOf("hx77bd...ea69", 797))
        assertEquals("", ipOf("hxe94d...72db", 772))
        // Normal pairs either side of it
        assertEquals("93.194.40.177", ipOf("hx8477...0525", 722))
        assertEquals("113.149.48.111", ipOf("hx9087...65ca", 426))
        // A steal after a masked access keeps the masked IP (the DB then routes it by wallet)
        assertEquals("xxx.xxx.xxx.xxx", ipOf("hx17e1...2e33", 824))
    }

    @Test
    fun ownLogIsSavedWithTheftsOnTheRightTargets() {
        val result = scrape("log_2026-11-19.txt")
        assertEquals(ScreenType.LOGS, result.detectedType)

        assertEquals(listOf("hx77bd...ea69"), record("8.83.197.176")?.wallets)
        assertEquals(listOf(797), theftsOf("8.83.197.176", "hx77bd...ea69"))
        assertEquals(listOf("hx9087...65ca"), record("113.149.48.111")?.wallets)
        // The unattributed steal becomes a wallet placeholder, never a real target
        assertNotNull(record("unknown:wallet_hxe94d...72db"))
        assertTrue(TargetRepository.getAllRecords().none { "hxe94d...72db" in it.wallets && !it.isTemporary })
    }

    @Test
    fun missingAccessedLineCannotStealAnotherTargetsWallet() {
        // Day 1 teaches the DB that 8.83.197.176 owns hx77bd...ea69.
        scrape("log_2026-11-19.txt")
        // Day 2: the hx77bd line has scrolled off (100-line cap), so the parser now pairs
        // hxe94d...72db with 8.83.197.176. The DB must refuse that and log a conflict.
        val day2 = LogScreenParser.parseDetailed(TestEnv.fixture("log_2026-11-20.txt").split("\n"))
        assertEquals("8.83.197.176", day2.updates.single { it.wallet == "hxe94d...72db" }.ip)

        scrape("log_2026-11-20.txt")

        assertEquals(listOf("hx77bd...ea69"), record("8.83.197.176")?.wallets)
        assertTrue(TestEnv.conflictLog(dir).any { "hxe94d...72db" in it && "8.83.197.176" in it })
    }

    @Test
    fun sameStealSeenOnTwoDaysIsStoredOnce() {
        // The 16:06 steal of 426 appears in both logs. On day 2 its "Accessed device at 113.149.48.111"
        // line is gone, so the parser leaves it unattributed; the wallet still routes it to its owner.
        val day2 = LogScreenParser.parse(TestEnv.fixture("log_2026-11-20.txt").split("\n"))
        assertEquals("", day2.single { it.wallet == "hx9087...65ca" && it.stolenAmount == 426 }.ip)

        scrape("log_2026-11-19.txt")
        scrape("log_2026-11-20.txt") // also adds "Stole 5,093 Crypto from hx9087...65ca" on 113.149.48.111

        assertNull(record("unknown:wallet_hx9087...65ca"))
        assertEquals(listOf("hx9087...65ca"), record("113.149.48.111")?.wallets)
        assertEquals(listOf(426, 5093), theftsOf("113.149.48.111", "hx9087...65ca"))
    }

    @Test
    fun logDiscardsAreTheKnownSix() {
        val parsed = LogScreenParser.parseDetailed(TestEnv.fixture("log_2026-11-20.txt").split("\n"))
        assertEquals(99, parsed.timestampedLineCount)
        // Purchased lines and failed cracks aren't parsed yet (both are open TODO items)
        val reasons = parsed.discarded.map { it.line.substringAfter("] ").substringBefore(" on ").substringBefore(" Lv") }
        assertEquals(listOf("Failed to crack password", "Purchased Keygen", "Failed to crack password",
            "Failed to crack password", "Purchased Keygen").sorted(), reasons.sorted())
        assertTrue(parsed.discarded.all { it.reason == LogDiscardReason.NO_PATTERN })
    }

    // ---- One target across screens (mjja419) ------------------------------------------------------

    @Test
    fun homeWalletSoftwareAndLogBuildOneTarget() {
        assertEquals(ScreenType.HOME, scrape("target_home_2026-11-19.txt").detectedType)
        assertEquals(ScreenType.WALLET, scrape("target_wallet_2026-11-19.txt").detectedType)
        assertEquals(ScreenType.SOFTWARE, scrape("target_software_2026-11-19.txt").detectedType)
        scrape("log_2026-11-20.txt") // "[11-20 4:00] Stole 10,901 Crypto from hxff13...42f1" after accessing 23.176.239.38

        val target = record("23.176.239.38")!!
        assertEquals("mjja419", target.username)
        assertEquals("SPAIN", target.clan)
        assertEquals(33, target.level)
        assertEquals("Bolt III • 5G+", target.hardware)
        assertEquals(22, target.firewall)
        assertEquals(22, target.encryptor)
        assertEquals(listOf("hxff13...42f1"), target.wallets)
        assertEquals(10, target.downloads.size)
        assertEquals(14, target.downloads["Rootkit"]?.level)
        assertNull(target.downloads["Keygen"]) // device-bound, not downloadable
        assertEquals(listOf(10901), theftsOf("23.176.239.38", "hxff13...42f1"))
        // Everything landed on the one real record: no leftover placeholder for this user or wallet
        assertTrue(TargetRepository.getAllRecords().none { it.isTemporary && (it.username == "mjja419" || "hxff13...42f1" in it.wallets) })
    }

    @Test
    fun crewScreenUsesTheKnownMembersClan() {
        scrape("target_home_2026-11-19.txt") // 23.176.239.38 is in clan SPAIN and in this crew
        val result = scrape("crew_2026-11-19.txt")

        assertEquals(ScreenType.CREW, result.detectedType)
        assertEquals("SPAIN", record("25.184.128.149")?.clan) // the leader
        assertEquals("SPAIN", record("12.179.205.114")?.clan) // last member
    }

    @Test
    fun crewScreenWithoutAKnownMemberSavesNothing() {
        scrape("crew_2026-11-19.txt")
        assertTrue(TargetRepository.getAllRecords().isEmpty())
    }

    // ---- Own screens ----------------------------------------------------------------------------

    @Test
    fun ownHomeSetsPlayerLevel() {
        val result = scrape("own_home_2026-11-20.txt")
        assertEquals(ScreenType.OWN_PROFILE, result.detectedType)
        val profile = PlayerProfileRepository.getProfile()
        assertEquals(39, profile.playerLevel)
        assertEquals("Seamus", profile.username)
        assertTrue(TargetRepository.getAllRecords().isEmpty()) // never a target record
    }

    @Test
    fun processScreenCreatesTargetsFromRealText() {
        val result = scrape("processes_2026-11-20.txt")
        assertEquals(ScreenType.PROCESS, result.detectedType)

        assertNotNull(record("64.59.40.157")?.lastValidated)        // DONE
        assertNotNull(record("178.185.169.48"))                       // INVALIDATED...
        assertNull(record("178.185.169.48")?.lastValidated)          // ...so not marked as seen
        assertNull(record("0.0.0.0"))                                 // your own antivirus scan
    }

    @Test
    fun siphonScreenSkipsTheMaskedRow() {
        val raw = TestEnv.fixture("siphons_2026-11-20.txt")
        val parsed = SiphonScreenParser.parse(raw)!!
        assertEquals(122, parsed.deployedCount)
        // 122 deployed, one shown as xxx.xxx.xxx.xxx: the parser only accepts numeric IPs
        assertEquals(121, parsed.items.size)
        assertTrue(parsed.items.none { it.ip.contains("x") })
        assertEquals(11381, parsed.items.first().amount)

        assertEquals(ScreenType.SIPHON, scrape("siphons_2026-11-20.txt").detectedType)
        assertEquals(121, SiphonTracker.getKnownSiphons().size)
        assertFalse(SiphonTracker.getKnownSiphons().keys.any { it.contains("x") })
    }
}
