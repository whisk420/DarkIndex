package com.whisk.hackexlogger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Batch A bug fixes (2026-11-20), on real Raw Dump fixtures where possible. */
class ScreenFixesTest {

    @Before
    fun setUp() {
        TestEnv.freshRepository()
    }

    private fun lines(name: String) = TestEnv.fixture(name).split("\n")
    private fun scrape(text: String) = ScrapePipeline.run(text, text.split("\n"))
    private fun records() = TargetRepository.getAllRecords()

    // ---- Masked IPs -----------------------------------------------------------------------------

    @Test
    fun processScreenNeverStoresMaskedIps() {
        val parsed = ProcessScreenParser.parse(lines("processes_2026-11-20.txt"))!!
        assertTrue((parsed.ips + parsed.invalidatedIps).any { it.contains("xxx") }) // they are on screen...
        scrape(TestEnv.fixture("processes_2026-11-20.txt"))
        assertTrue(records().none { it.isMasked })                                 // ...but never stored
        assertTrue(records().isNotEmpty())
    }

    @Test
    fun crewScreenNeverStoresMaskedMembers() {
        scrape(TestEnv.fixture("target_home_2026-11-19.txt")) // 23.176.239.38, clan SPAIN, is in this crew
        scrape(TestEnv.fixture("crew_2026-11-19.txt"))
        assertTrue(records().none { it.isMasked })
        assertEquals("SPAIN", records().single { it.ip == "25.184.128.149" }.clan)
    }

    @Test
    fun crewMaskedMemberAloneDoesNotProvideTheClan() {
        // A masked record that happens to have a clan must not tag the whole crew
        TargetRepository.saveAllRecords(listOf(TargetRecord(ip = "xxx.xxx.xxx.xxx", clan = "WRONG")))
        DatabaseManager.processCrewScreen(CrewScreen(listOf("xxx.xxx.xxx.xxx", "9.9.9.9")))
        assertNull(records().find { it.ip == "9.9.9.9" })
    }

    // ---- Siphons --------------------------------------------------------------------------------

    @Test
    fun siphonParserCountsTheMaskedRow() {
        val parsed = SiphonScreenParser.parse(TestEnv.fixture("siphons_2026-11-20.txt"))!!
        assertEquals(1, parsed.maskedCount)
        assertEquals(parsed.deployedCount, parsed.items.size + parsed.maskedCount)
    }

    @Test
    fun removalAlertFiresEvenWithAMaskedSiphon() {
        // First full scroll: 2 real + 1 masked = 3 deployed
        DatabaseManager.processSiphonScreen(SiphonScreen(3, listOf(
            SiphonItem("1.1.1.1", 20, "5%", 500, "1h ago"),
            SiphonItem("2.2.2.2", 20, "5%", 500, "1h ago")
        ), maskedCount = 1))
        // Later: 1.1.1.1 is gone, a new one appeared, still one masked
        DatabaseManager.processSiphonScreen(SiphonScreen(3, listOf(
            SiphonItem("2.2.2.2", 20, "5%", 600, "2h ago"),
            SiphonItem("3.3.3.3", 20, "5%", 10, "1m ago")
        ), maskedCount = 1))

        assertEquals(listOf("1.1.1.1"), SiphonTracker.getSiphonAlerts())
    }

    // ---- Nav bar --------------------------------------------------------------------------------

    @Test
    fun navBarNoLongerFoolsCrewOrProcessParsers() {
        for (name in listOf("own_home_2026-11-20.txt", "log_2026-11-20.txt", "siphons_2026-11-20.txt", "spam_earnings_2026-11-20.txt", "target_home_2026-11-19.txt")) {
            assertNull(name, CrewScreenParser.parse(lines(name)))
            assertNull(name, ProcessScreenParser.parse(lines(name)))
        }
        // Real crew and process screens still parse
        assertNotNull(CrewScreenParser.parse(lines("crew_2026-11-19.txt")))
        assertNotNull(ProcessScreenParser.parse(lines("processes_2026-11-20.txt")))
    }

    @Test
    fun unrecognisedScreenWithTheNavBarSavesNothing() {
        val page = "// SOMETHING NEW\n12.34.56.78\nCrew rankings\nHOME\nSCAN\nPROCESSES\nAPPS\nSTORE\nCREWS 1"
        TargetRepository.saveAllRecords(listOf(TargetRecord(ip = "12.34.56.78", clan = "SPAIN")))
        val result = scrape(page)
        assertEquals(ScreenType.UNKNOWN, result.detectedType)
        assertTrue(result.savedItems.isEmpty())
        assertNull(records().single().lastValidated)
    }

    @Test
    fun stripNavBarRemovesTheBlockAnywhere() {
        val lines = listOf("a", "HOME", "SCAN", "PROCESSES", "APPS", "STORE", "CREWS 1", "b", "HOME", "SCAN", "PROCESSES", "APPS", "STORE", "CREWS")
        assertEquals(listOf("a", "b"), CommonParserUtil.stripNavBar(lines))
        // A lone "PROCESSES" heading isn't the nav bar
        assertEquals(listOf("PROCESSES", "x"), CommonParserUtil.stripNavBar(listOf("PROCESSES", "x")))
    }

    // ---- Log screen detection ---------------------------------------------------------------------

    @Test
    fun bothRealLogsAreRecognised() {
        for (name in listOf("log_2026-11-19.txt", "log_2026-11-20.txt")) {
            assertEquals(ScreenType.LOGS, ScreenClassifier.detectScreenType(TestEnv.fixture(name), lines(name)))
        }
    }

    // ---- HTML entities ----------------------------------------------------------------------------

    @Test
    fun htmlEntitiesAreDecodedBeforeParsing() {
        assertEquals("mjja419's wallet & more <3 \"x\"", CommonParserUtil.decodeEntities("mjja419&#39;s wallet &amp; more &lt;3 &quot;x&quot;"))
        assertEquals("é", CommonParserUtil.decodeEntities("&#xE9;"))
        assertEquals("&unknown;", CommonParserUtil.decodeEntities("&unknown;"))

        TargetRepository.saveAllRecords(listOf(TargetRecord(ip = "23.176.239.38", username = "mjja419")))
        val wallet = TestEnv.fixture("target_wallet_2026-11-19.txt").replace("mjja419's wallet", "mjja419&#39;s wallet")
        val result = scrape(wallet)
        assertEquals(ScreenType.WALLET, result.detectedType)
        assertEquals(listOf("hxff13...42f1"), records().single { it.ip == "23.176.239.38" }.wallets)
    }

    // ---- Spam Earnings ----------------------------------------------------------------------------

    @Test
    fun spamEarningsScreenIsRecognisedAndParsed() {
        val text = TestEnv.fixture("spam_earnings_2026-11-20.txt")
        assertEquals(ScreenType.SPAM_EARNINGS, ScreenClassifier.detectScreenType(text, text.split("\n")))
        val parsed = SpamEarningsParser.parse(text)!!
        assertEquals(58, parsed.deployedCount)
        assertEquals(5728, parsed.ratePerHour)
        assertEquals(SpamItem("175.223.63.126", 17, 100, 12100, "5d"), parsed.items.first())
        assertEquals(SpamItem("226.189.216.255", 18, 87, 2020, "1d"), parsed.items.last())
        assertTrue(parsed.items.none { it.ip.contains("x") })
        // The siphon screen isn't mistaken for it
        assertNull(SpamEarningsParser.parse(TestEnv.fixture("siphons_2026-11-20.txt")))
        // Nothing stored yet, and no siphon tracking side effects
        val result = scrape(text)
        assertEquals(ScreenType.SPAM_EARNINGS, result.detectedType)
        assertTrue(records().isEmpty())
        assertTrue(SiphonTracker.getKnownSiphons().isEmpty())
        assertFalse(result.fallbackUsed)
    }
}
