package com.whisk.hackexlogger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [GameFormulas] against every number hackex.net/guide publishes (read 2026-11-21) and the verified Store prices
 * in docs/game.md §3, plus integrity checks on the Knowledge Base content and calculators.
 */
class KnowledgeBaseTest {

    // ── Formulas vs published numbers ──────────────────────────────────────────────────────────────────────

    @Test
    fun spamIncomeMatchesDeveloperMeasurements() {
        // 2026-11-22, fresh uploads read off SPAM EARNINGS: (victim level, Spam level) → Crypto/hr
        val measured = mapOf(
            (43 to 1) to 18, (43 to 2) to 23, (43 to 3) to 27, (43 to 7) to 47, (43 to 11) to 69,
            (33 to 7) to 47, (33 to 13) to 81, (33 to 20) to 100,
            (28 to 1) to 18, (28 to 2) to 23, (28 to 6) to 42,
            (7 to 13) to 38, (7 to 20) to 30, (19 to 10) to 58,
            (2 to 1) to 13, (2 to 3) to 18, (2 to 7) to 24, (2 to 11) to 26,
            (3 to 1) to 13, (4 to 1) to 14, (6 to 1) to 15, (12 to 1) to 18, // 2026-11-27, Raw Dumps #83–#148 (R4 run)
            (2 to 5) to 21, // Spam 3 levels above the victim (b1r9gm0d, Lv2), Raw Dump #200: the first above-victim reading
        )
        measured.forEach { (key, v) ->
            val (victim, spam) = key
            assertEquals("victim Lv$victim Spam Lv$spam", v, GameFormulas.spamIncomePerHour(spam, victim))
        }
        // 2026-11-20 SPAM EARNINGS: Lv16 tops out at 99, Lv17 shows 100 → Lv17 is the lowest Spam that ever caps
        assertEquals(99, GameFormulas.spamIncomePerHour(16, 99))
        assertEquals(17, GameFormulas.spamBestCap().first)
    }

    @Test
    fun siphonPercentMatchesRawDump16() {
        // Every level seen on the 2026-11-22 SIPHON EARNINGS page (122 deployments)
        val seen = mapOf(1 to 1.0, 2 to 1.25, 5 to 2.0, 6 to 2.25, 13 to 4.0, 17 to 5.0, 20 to 5.75, 25 to 6.0)
        seen.forEach { (lvl, pct) -> assertEquals("Lv$lvl", pct, GameFormulas.siphonPercent(lvl)!!, 1e-9) }
        assertEquals(null, GameFormulas.siphonPercent(0))
    }

    @Test
    fun rootkitDebuffMatchesMeasuredLevels() {
        // Developer's in-game readings, 2026-11-22
        mapOf(1 to 1, 2 to 1, 3 to 2, 6 to 3).forEach { (lvl, debuff) -> assertEquals("Lv$lvl", debuff, GameFormulas.rootkitDebuff(lvl)) }
        assertEquals(3, GameFormulas.rootkitDebuff(30)) // −3 is the cap
        assertEquals(0, GameFormulas.rootkitDebuff(0))
    }

    @Test
    fun crewServerMatchesRawDump25() {
        // Crew server screen 2026-11-22: next-level prices shown as "829.8K / 1.1M" etc., upkeep 19,325/day
        assertEquals(1_120_000L, GameFormulas.crewPartUpgradePrice(GameFormulas.CrewPart.CPU, 4))   // "1.1M"
        assertEquals(1_120_000L, GameFormulas.crewPartUpgradePrice(GameFormulas.CrewPart.NIC, 4))   // "1.1M"
        assertEquals(1_920_000L, GameFormulas.crewPartUpgradePrice(GameFormulas.CrewPart.RAM, 4))   // "1.9M"
        assertEquals(405_000L, GameFormulas.crewPartUpgradePrice(GameFormulas.CrewPart.UPLINK, 3))  // "405K"
        val upkeep = GameFormulas.crewPartUpkeep(GameFormulas.CrewPart.CPU, 3)!! +
            GameFormulas.crewPartUpkeep(GameFormulas.CrewPart.NIC, 3)!! +
            GameFormulas.crewPartUpkeep(GameFormulas.CrewPart.RAM, 3)!! +
            GameFormulas.crewPartUpkeep(GameFormulas.CrewPart.UPLINK, 2)!! +
            GameFormulas.crewPartUpkeep(GameFormulas.CrewPart.STORAGE, 5)!!
        assertEquals(19_325L, upkeep) // "Upkeep 19,325/day (805/hr)"
    }

    @Test
    fun crewServerLogMatchesRawDump27() {
        // Every upgrade and surge in a crew's full server log (2026-11-22). Prices shown rounded down ("−1M" = 1.08M).
        val P = GameFormulas.CrewPart.values().associateBy { it.displayName }
        val upgrades = listOf("Storage" to 1 to 60_000L, "Storage" to 2 to 240_000L, "Storage" to 3 to 540_000L,
            "Storage" to 4 to 960_000L, "Storage" to 5 to 1_500_000L, "RAM" to 1 to 120_000L, "RAM" to 2 to 480_000L,
            "CPU" to 1 to 70_000L, "NIC" to 1 to 70_000L, "Uplink" to 1 to 45_000L, "CPU" to 2 to 280_000L,
            "NIC" to 2 to 280_000L, "Uplink" to 2 to 180_000L, "CPU" to 3 to 630_000L, "NIC" to 3 to 630_000L)
        upgrades.forEach { (pl, price) -> assertEquals("$pl", price, GameFormulas.crewPartUpgradePrice(P.getValue(pl.first), pl.second)) }
        // (part, level at the time, hours) → price
        val surges = listOf(Triple("CPU", 1, 24) to 16_380L, Triple("Uplink", 1, 24) to 10_530L, Triple("CPU", 2, 24) to 31_500L,
            Triple("Uplink", 2, 24) to 20_250L, Triple("CPU", 2, 12) to 16_625L, Triple("CPU", 3, 24) to 51_660L, Triple("NIC", 3, 24) to 51_660L)
        surges.forEach { (s, price) -> assertEquals("$s", price, GameFormulas.crewSurgePrice(P.getValue(s.first), s.second, s.third)) }
        assertEquals(null, GameFormulas.crewSurgePrice(GameFormulas.CrewPart.STORAGE, 5, 24))
        // The whole log balances: contributions 8,644,913 − (upgrades + surges + upkeep) = 829,823 ("829.8K" on screen)
        val upgradeTotal = upgrades.sumOf { it.second } + GameFormulas.crewPartUpgradePrice(P.getValue("RAM"), 3)!!
        assertEquals(829_823L, 8_644_913L - upgradeTotal - 510_005L - (125_207L + 14_878L))
    }

    @Test
    fun storePricesMatchEveryRealStorePrice() {
        // Every real Store price recorded: Raw Dumps #6 and #38 (Lv 19–35) and the R4 reboot run #41–#190 (Lv 1–7)
        val real = listOf(
            "Keygen" to listOf(1 to 600, 2 to 1_478, 3 to 2_503, 4 to 3_638, 5 to 4_862, 6 to 6_163, 21 to 31_408, 25 to 39_398, 35 to 61_016),
            "Bypasser" to listOf(2 to 1_970, 3 to 3_337, 6 to 8_217, 21 to 41_877, 34 to 78_346),
            "Password Cracker" to listOf(2 to 2_709, 3 to 4_589, 6 to 11_298, 35 to 111_862),
            "Firewall" to listOf(2 to 1_675, 3 to 2_837, 5 to 5_511, 21 to 35_596, 32 to 61_547),
            "Password Encryptor" to listOf(2 to 2_303, 19 to 42_973, 34 to 91_567),
            "Spam" to listOf(1 to 1_800, 2 to 4_433, 6 to 18_488, 21 to 94_224),
            "Siphon" to listOf(1 to 1_600, 2 to 3_940, 4 to 9_701, 26 to 110_557),
            "Rootkit" to listOf(1 to 1_400, 2 to 3_448, 7 to 17_570),
            "Antivirus" to listOf(1 to 850, 2 to 2_094, 31 to 73_824),
            "Proxy" to listOf(1 to 765, 2 to 1_885, 21 to 40_046, 28 to 58_207),
            "Trace" to listOf(1 to 765, 2 to 1_885, 30 to 63_669),
        )
        for ((name, rows) in real) {
            val app = GameFormulas.App.values().first { it.displayName == name }
            for ((level, price) in rows) assertEquals("$name Lv$level", price, GameFormulas.storePrice(app, level))
        }
    }

    @Test
    fun softwareXpMatchesRealPurchasesAndUnlocks() {
        // 2026-11-27 own-Home XP readings around each purchase/unlock (Raw Dumps #43–#120), plus Keygen 26 (older log)
        val seen = listOf(
            Triple("Keygen", 1, 120), Triple("Keygen", 2, 285), Triple("Keygen", 3, 473), Triple("Keygen", 4, 679),
            Triple("Keygen", 5, 897), Triple("Keygen", 26, 7_045), Triple("Proxy", 1, 153), Triple("Spam", 1, 360),
            Triple("Rootkit", 1, 280), Triple("Bypasser", 2, 380), Triple("Password Cracker", 2, 523),
            Triple("Antivirus", 1, 170), Triple("Firewall", 2, 323), Triple("Trace", 1, 153), Triple("Siphon", 1, 320),
            Triple("Antivirus", 2, 404), Triple("Password Encryptor", 2, 445),
            Triple("Spam", 5, 2_691), Triple("Bypasser", 5, 1_196), Triple("Siphon", 3, 1_263), Triple("Password Cracker", 5, 1_645), // Keygen unlocks, #174–#189
        )
        for ((name, level, xp) in seen) {
            val app = GameFormulas.App.values().first { it.displayName == name }
            // The game keeps XP fractions, so a reading is within 1 of the exact value
            assertEquals("$name Lv$level", xp.toDouble(), GameFormulas.softwareXp(app, level), 1.0)
        }
    }

    @Test
    fun proxyActivationMatchesTheGame() {
        // Raw Dump #34: Proxy Lv 27 "Select Duration" panel
        assertEquals(listOf(104, 297, 562, 1_061), listOf(2, 6, 12, 24).map { GameFormulas.proxyActivationCost(27, it) })
    }

    @Test
    fun keygenFeesMatchLockedRowsOnOwnAppsPage() {
        // Raw Dump #33: 9 LOCKED copies at Lv 25–42; Raw Dump #173 (R4 run): 8 at Lv 3–5, jumps of 2 to 4
        for ((fixture, rows) in listOf("own_apps_locked_2026-11-24.txt" to 9, "own_apps_locked_r4_2026-11-27.txt" to 8)) {
        val apps = OwnAppsParser.parse(TestEnv.fixture(fixture).split("\n"))!!
        assertEquals(fixture, rows, apps.lockedPrices.size)
        for ((name, price) in apps.lockedPrices) {
            val app = GameFormulas.App.values().first { it.displayName == name }
            val fee = GameFormulas.keygenFee(app, apps.installedLevels.getValue(name), apps.lockedLevels.getValue(name))!!
            assertEquals(name, price, fee)
        }
        }
    }

    // ── Content & calculators ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun contentIsConsistent() {
        val topics = KnowledgeBase.topics
        assertTrue(topics.size >= 20)
        assertEquals("topic ids unique", topics.size, topics.map { it.id }.toSet().size)
        val ids = topics.map { it.id }.toSet()
        val calcIds = KbCalculators.all().map { it.id }.toSet()
        val usedCalcs = mutableSetOf<String>()
        for (t in topics) {
            assertTrue("${t.id} overview", t.overview.isNotBlank())
            assertTrue("${t.id} has content", t.blocks.isNotEmpty())
            t.related.forEach { assertTrue("${t.id} → $it", it in ids) }
            for (b in t.blocks) when (b) {
                is KbBlock.Calc -> { assertTrue("${t.id} calc ${b.calculatorId}", b.calculatorId in calcIds); usedCalcs += b.calculatorId }
                is KbBlock.Unknowns -> b.items.forEach { assertTrue("${t.id} ${it.first}", Regex("Q-\\d\\d").matches(it.first)) }
                is KbBlock.Table -> b.rows.forEach { assertEquals("${t.id} table width", b.header.size, it.size) }
                else -> {}
            }
        }
        assertEquals("every calculator appears somewhere", calcIds, usedCalcs)
    }

    @Test
    fun everyCalculatorRunsAtDefaultsAndExtremes() {
        for (c in KbCalculators.all()) {
            assertTrue(c.id, c.run().isNotBlank())
            val low = c.inputs.associate { i ->
                i.key to when (i) {
                    is CalcInput.Number -> i.min
                    is CalcInput.Choice -> 0
                    is CalcInput.Toggle -> true
                }
            }
            val high = c.inputs.associate { i ->
                i.key to when (i) {
                    is CalcInput.Number -> i.max
                    is CalcInput.Choice -> i.options.size - 1
                    is CalcInput.Toggle -> false
                }
            }
            assertTrue("${c.id} low", c.run(low).isNotBlank())
            assertTrue("${c.id} high", c.run(high).isNotBlank())
        }
    }

    @Test
    fun calculatorDefaultsComeFromTheProfile() {
        val profile = PlayerProfile(playerLevel = 33, softwareLevels = mapOf("Spam" to 18, "Proxy" to 21))
        val defaults = KbPlayerDefaults.fromProfile(profile, "Nova", "Fiber+")
        assertEquals(33, defaults.level)
        assertEquals(17, defaults.spamLevel)
        assertEquals(20, defaults.proxyLevel)
        assertEquals(7, defaults.deviceLevel)
        assertEquals(8, defaults.networkLevel)
        KbCalculators.defaults = defaults
        try {
            val upload = KbCalculators.byId(KbCalculators.UPLOAD)!!
            val network = upload.inputs.first { it.key == "network" } as CalcInput.Choice
            assertEquals(7, network.defaultIndex)
        } finally {
            KbCalculators.defaults = KbPlayerDefaults()
        }
    }

    @Test
    fun searchFindsTopicsAndSnippets() {
        assertEquals(KnowledgeBase.topics.size, KnowledgeBase.search("  ").size)
        val hits = KnowledgeBase.search("170 h banked").map { it.id }
        assertTrue(hits.toString(), "proxy" in hits)
        assertTrue(KnowledgeBase.search("zzqqxx").isEmpty())
        val snippet = KnowledgeBase.topic("siphon")!!.matchSnippet("30%")
        assertNotNull(snippet)
        assertTrue(snippet!!.contains("30%"))
    }
}
