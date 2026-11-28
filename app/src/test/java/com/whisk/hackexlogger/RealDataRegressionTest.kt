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

    // ---- Home screens -----------------------------------------------------------------------------

    @Test
    fun prestigedTargetHomeKeepsItsLevel() {
        // Raw Dump #28: the prestige rank sits between the label and the value ("LVL" / "R1" / "48").
        // It must not stop the level being read, nor be taken for it.
        scrape("target_home_prestige_2026-11-23.txt")
        val r = record("61.213.195.71")!!
        assertEquals(48, r.level)
        assertEquals("vernix", r.username)
        assertEquals("SPAIN", r.clan)
        assertEquals(28, r.firewall)
        assertEquals(30, r.encryptor)
    }

    // ---- Log attribution ------------------------------------------------------------------------

    @Test
    fun ownLogDoubleStealAttribution() {
        val updates = LogScreenParser.parse(TestEnv.fixture("log_2026-11-19.txt").split("\n"))
        fun ipOf(wallet: String, amount: Int) = updates.single { it.wallet == wallet && it.stolenAmount == amount }.ip

        // Developer-confirmed (2026-11-19): the first steal after "Accessed device at 8.83.197.176" is that
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
    fun logFooterAnchorsTheYearAndTimesAreUtc() {
        val parsed = LogScreenParser.parseDetailed(TestEnv.fixture("log_2026-11-20.txt").split("\n"))
        // "Last saved: 2026-11-20 12:38:57" (UTC)
        assertEquals(1795178337000L, parsed.lastSavedUtcMs)
        // "[11-20 4:00] Stole 10,901 Crypto from hxff13...42f1" is 04:00 UTC on 2026-11-20
        assertEquals(1795147200000L, DatabaseManager.parseLogTime("11-20 4:00", parsed.lastSavedUtcMs))
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
    fun missingAccessedLineAddsAWalletCandidateUntilTheWalletScreenProvesIt() {
        // Day 1 links hx77bd...ea69 to 8.83.197.176 (log, so "highly probable").
        scrape("log_2026-11-19.txt")
        // Day 2: the hx77bd line has scrolled off (100-line cap), so the parser now pairs
        // hxe94d...72db with 8.83.197.176 as well.
        val day2 = LogScreenParser.parseDetailed(TestEnv.fixture("log_2026-11-20.txt").split("\n"))
        assertEquals("8.83.197.176", day2.updates.single { it.wallet == "hxe94d...72db" }.ip)
        scrape("log_2026-11-20.txt")

        // Logs aren't proof, so both stay as candidates (developer rule 2026-11-20)
        assertEquals(listOf("hx77bd...ea69", "hxe94d...72db"), record("8.83.197.176")?.wallets)

        // The target's wallet screen settles it: the proven wallet stays, the other one and its thefts go
        TargetRepository.saveAllRecords(TargetRepository.getAllRecords().map { if (it.ip == "8.83.197.176") it.copy(username = "victim883") else it })
        DatabaseManager.processWalletScreen(WalletScreen(username = "victim883", wallet = "hx77bd...ea69"))

        val target = record("8.83.197.176")!!
        assertEquals(listOf("hx77bd...ea69"), target.wallets)
        assertTrue(target.walletFromPage)
        assertNull(target.walletThefts["hxe94d...72db"])
        assertTrue(TestEnv.conflictLog(dir).any { "WalletScreenReplaced" in it && "hxe94d...72db" in it })

        // From now on a log can't attach another wallet to it
        scrape("log_2026-11-20.txt")
        assertEquals(listOf("hx77bd...ea69"), record("8.83.197.176")?.wallets)
    }

    @Test
    fun sameStealSeenOnTwoDaysIsStoredOnce() {
        // The 16:06 steal of 426 appears in both logs. On day 2 its "Accessed device at 113.149.48.111"
        // line is gone, so the parser leaves it unattributed; the wallet still routes it to its owner.
        val day2 = LogScreenParser.parse(TestEnv.fixture("log_2026-11-20.txt").split("\n"))
        assertEquals("", day2.single { it.wallet == "hx9087...65ca" && it.stolenAmount == 426 }.ip)

        scrape("log_2026-11-19.txt")
        assertEquals(ScreenType.LOGS, scrape("log_2026-11-20.txt").detectedType) // also adds "Stole 5,093 Crypto from hx9087...65ca" on 113.149.48.111

        assertNull(record("unknown:wallet_hx9087...65ca"))
        assertEquals(listOf("hx9087...65ca"), record("113.149.48.111")?.wallets)
        assertEquals(listOf(426, 5093), theftsOf("113.149.48.111", "hx9087...65ca"))
    }

    @Test
    fun logDiscardsAreTheKnownOnes() {
        val parsed = LogScreenParser.parseDetailed(TestEnv.fixture("log_2026-11-20.txt").split("\n"))
        assertEquals(99, parsed.timestampedLineCount)
        // Failed cracks are parsed since 2026-11-20; Purchased lines are a planned feature (profile from log)
        val reasons = parsed.discarded.map { it.line.substringAfter("] ").substringBefore(" on ").substringBefore(" Lv") }
        assertEquals(listOf("Purchased Keygen", "Purchased Keygen"), reasons)
        assertEquals(setOf("44.204.208.233", "203.137.119.10", "231.130.229.178"),
            parsed.updates.filter { it.raw.contains("Failed to crack") }.map { it.ip }.toSet())
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
        assertTrue(TargetRepository.getAllRecords().none { it.isMasked })  // masked members are skipped
    }

    @Test
    fun ownCrewServerPageSavesNothing() {
        // Raw Dump #25: own crew page (treasury, server parts, server log); no roster, so nothing to store
        val result = scrape("crew_server_2026-11-22.txt")
        assertEquals(ScreenType.CREW, result.detectedType)
        assertTrue(TargetRepository.getAllRecords().isEmpty())
    }

    @Test
    fun scanListStoresEveryRealRowWithItsOwnLevels() {
        val result = scrape("scan_2026-11-22.txt") // Raw Dump #24: 12 rows (crew Uplink +2), 5 masked, Apex (ENC shown)
        assertEquals(ScreenType.BYPASS, result.detectedType)
        assertTrue(result.savedItems.contains("Scan (7 targets)"))
        assertEquals(7, TargetRepository.getAllRecords().size)
        assertEquals(32, record("241.118.225.244")?.firewall)
        assertEquals(40, record("241.118.225.244")?.encryptor)
        assertEquals(25, record("140.199.238.221")?.firewall)
        assertEquals(27, record("140.199.238.221")?.encryptor)
        assertEquals(33, record("148.130.96.85")?.firewall) // last row
        assertTrue(TargetRepository.getAllRecords().none { it.isMasked }) // incl. the partly masked 226.217.xxx.xxx
    }

    @Test
    fun maskedScanRowDoesNotLendItsLevelsToTheNextRealIp() {
        // Same real screen with a masked row moved to the top
        val lines = TestEnv.fixture("scan_2026-11-22.txt").split("\n").toMutableList()
        val masked = lines.indexOf("xxx.xxx.xxx.xxx FW Lv.39 ENC Lv.32 >")
        lines.add(lines.indexOf("12 targets") + 1, lines.removeAt(masked))
        val raw = lines.joinToString("\n")
        ScrapePipeline.run(raw, raw.split("\n"))

        assertEquals(32, record("241.118.225.244")?.firewall)
        assertEquals(40, record("241.118.225.244")?.encryptor)
        assertEquals(7, TargetRepository.getAllRecords().size)
    }

    @Test
    fun defaultScanRowsUpdateFirewallAndKeepKnownEncryptor() {
        // Raw Dump #153: a Ghost reboot's scan, no ENC column (the default row format), 12 rows with the crew Uplink
        TargetRepository.update { listOf(TargetRecord(ip = "84.161.233.248", username = "Known", firewall = 4, encryptor = 6)) }
        val result = scrape("scan_ghost_2026-11-27.txt")
        assertEquals(ScreenType.BYPASS, result.detectedType)
        assertEquals(12, TargetRepository.getAllRecords().size)
        assertEquals(5, record("78.4.246.30")?.firewall)
        assertNull(record("78.4.246.30")?.encryptor)
        assertNull(record("78.4.246.30")?.lastValidated) // new scan IPs go to the front of the VALIDATE queue
        // A known target: FW updated from the scan, the rest kept
        assertEquals(1, record("84.161.233.248")?.firewall)
        assertEquals(6, record("84.161.233.248")?.encryptor)
        assertEquals("Known", record("84.161.233.248")?.username)
    }

    @Test
    fun filteredProcessesHeaderIsNotAnIp() {
        // Raw Dump #166: the Processes page filtered to one target shows "205.79.204.429 PROCESSES" (the IP glued to
        // the count "9"). The masked-IP fallback used to accept it and create a record 205.79.204.429.
        val result = scrape("processes_running_2026-11-27.txt")
        assertEquals(ScreenType.PROCESS, result.detectedType)
        assertEquals(listOf("205.79.204.42"), TargetRepository.getAllRecords().map { it.ip })
        // The 8 running downloads (new two-line layout: "Download" / app name / level) are read
        assertEquals(8, record("205.79.204.42")?.downloads?.size)
        assertEquals(5, record("205.79.204.42")?.downloads?.get("Trace")?.level)
        assertEquals(4, record("205.79.204.42")?.downloads?.get("Firewall")?.level)
    }

    @Test
    fun groupedProcessesHeaderIsNotAnIp() {
        // Raw Dump #202: grouped by target, each group header is "<ip><count> PROCESS[ES]" ("234.227.242.41 PROCESS" =
        // 234.227.242.4 + "1 PROCESS"), which created a junk record 234.227.242.41
        scrape("processes_grouped_2026-11-28.txt")
        val ips = TargetRepository.getAllRecords().map { it.ip }.toSet()
        assertEquals(setOf("232.149.127.160", "234.227.242.4", "243.99.126.190", "254.102.70.194", "255.158.209.227"), ips)
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
    fun ownDevicePageSavesNothing() {
        // Raw Dump #146 (email and IP redacted): the own My Device / settings page. As UNKNOWN it ran every parser,
        // saved a target named "IP Address" and renamed the player profile.
        scrape("own_home_2026-11-20.txt")
        val result = scrape("own_device_2026-11-27.txt")
        assertEquals(ScreenType.OWN_DEVICE, result.detectedType)
        assertTrue(result.savedItems.isEmpty())
        assertEquals("Seamus", PlayerProfileRepository.getProfile().username)
        assertTrue(TargetRepository.getAllRecords().isEmpty())
    }

    @Test
    fun storeBlackMarketAndCreditsTabsSaveNothing() {
        // Raw Dumps #156 (Black Market, was classified HOME) and #157 (Credits tab, was UNKNOWN: every parser ran)
        scrape("own_home_2026-11-20.txt")
        val before = PlayerProfileRepository.getProfile()
        for (fixture in listOf("store_black_market_2026-11-27.txt", "store_credits_2026-11-27.txt")) {
            val result = scrape(fixture)
            assertEquals(fixture, ScreenType.STORE_OTHER, result.detectedType)
            assertTrue(fixture, result.savedItems.isEmpty())
        }
        assertEquals(before, PlayerProfileRepository.getProfile())
        assertTrue(TargetRepository.getAllRecords().isEmpty())
    }

    @Test
    fun ownAppsPageSetsInstalledLevels() {
        // Raw Dump #29: your own Apps page. Levels are stored as installed + 1 (the Store's NEXT convention).
        val result = scrape("own_apps_2026-11-24.txt")
        assertEquals(ScreenType.APPS, result.detectedType)
        assertTrue(result.savedItems.contains("My Apps (11 levels)"))
        val expectedInstalled = mapOf(
            "Antivirus" to 30, "Firewall" to 29, "Password Encryptor" to 30, "Proxy" to 27, "Trace" to 29,
            "Bypasser" to 31, "Password Cracker" to 30, "Spam" to 20, "Rootkit" to 6, "Siphon" to 25, "Keygen" to 33
        )
        assertEquals(expectedInstalled.mapValues { it.value + 1 }, PlayerProfileRepository.getProfile().softwareLevels)
        // "(+3)" is shown beside the level, not in it; LOCKED rows (e.g. Antivirus LVL 42) are not installed
        assertEquals(31, PlayerProfileRepository.getEffectiveBypasserLevel()) // no Apex device in this profile
        assertEquals(20, PlayerProfileRepository.getInstalledSpamLevel())
        assertTrue(TargetRepository.getAllRecords().isEmpty())
    }

    @Test
    fun appsPageIsNotASiphonScrapeAndNewestLevelsWin() {
        // The Siphon earnings page carries the Apps list underneath (2026-11-20: Antivirus 20, Bypasser 20).
        scrape("siphons_2026-11-20.txt")
        assertEquals(21, PlayerProfileRepository.getProfile().softwareLevels["Antivirus"])
        val known = SiphonTracker.getKnownSiphons().size

        // The plain Apps page contains "siphoned" and "DEPLOYED" but must not count as a (empty) siphon list
        scrape("own_apps_2026-11-24.txt")
        assertEquals(known, SiphonTracker.getKnownSiphons().size)
        assertTrue(SiphonTracker.getSiphonAlerts().isEmpty())
        assertEquals(31, PlayerProfileRepository.getProfile().softwareLevels["Antivirus"])
        assertEquals(32, PlayerProfileRepository.getProfile().softwareLevels["Bypasser"])
    }

    @Test
    fun bypassScreenDoesNotCountTheApexBonusTwice() {
        // B12, Raw Dump #30: "Using Bypasser Lv.31" + "(+3)" on an Apex device. Before the fix the scrape stored
        // 31 + 3 + 1 = 35 and the effective level added +3 again (37). The Apps page (dump #29) also says 31.
        DatabaseManager.processStoreScreen(StoreScreen("Apex VII", null, null, emptyMap()))
        scrape("own_apps_2026-11-24.txt")
        assertEquals(34, PlayerProfileRepository.getEffectiveBypasserLevel())

        val result = scrape("bypass_apex_2026-11-24.txt")
        assertEquals(ScreenType.BYPASS, result.detectedType)
        assertEquals(32, PlayerProfileRepository.getProfile().softwareLevels["Bypasser"])
        assertEquals(34, PlayerProfileRepository.getEffectiveBypasserLevel())
        assertEquals(33, PlayerProfileRepository.getEffectiveCrackerLevel()) // Cracker 30 + Apex 3
        assertEquals(31, record("57.134.50.54")?.firewall)
        assertEquals(32, record("57.134.50.54")?.encryptor)
    }

    @Test
    fun processScreenCreatesTargetsFromRealText() {
        val result = scrape("processes_2026-11-20.txt")
        assertEquals(ScreenType.PROCESS, result.detectedType)

        assertNotNull(record("64.59.40.157")?.lastValidated)        // DONE
        assertNotNull(record("178.185.169.48"))                       // INVALIDATED...
        assertNull(record("178.185.169.48")?.lastValidated)          // ...so not marked as seen
        assertNull(record("0.0.0.0"))                                 // your own antivirus scan
        assertTrue(TestEnv.fixture("processes_2026-11-20.txt").contains("xxx"))   // masked rows are on screen...
        assertTrue(TargetRepository.getAllRecords().none { it.isMasked })          // ...but never stored
    }

    @Test
    fun siphonScreenSkipsTheMaskedRow() {
        val raw = TestEnv.fixture("siphons_2026-11-20.txt")
        val parsed = SiphonScreenParser.parse(raw)!!
        assertEquals(122, parsed.deployedCount)
        // 122 deployed, one shown as xxx.xxx.xxx.xxx: the parser only accepts numeric IPs
        assertEquals(121, parsed.items.size)
        assertEquals(1, parsed.maskedCount) // counts towards the full-scroll check
        assertTrue(parsed.items.none { it.ip.contains("x") })
        assertEquals(11381, parsed.items.first().amount)

        assertEquals(ScreenType.SIPHON, scrape("siphons_2026-11-20.txt").detectedType)
        assertEquals(121, SiphonTracker.getKnownSiphons().size)
        assertFalse(SiphonTracker.getKnownSiphons().keys.any { it.contains("x") })
    }
}
