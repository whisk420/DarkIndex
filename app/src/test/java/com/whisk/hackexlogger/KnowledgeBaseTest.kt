package com.whisk.hackexlogger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [GameFormulas] against every number hackex.net/guide publishes (read 2026-11-21) and the verified Store prices
 * in CLAUDE.md §4, plus integrity checks on the Knowledge Base content and calculators.
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
        assertEquals(null, GameFormulas.crewPartUpgradePrice(GameFormulas.CrewPart.STORAGE, 1))
    }

    @Test
    fun storePricesMatchVerifiedNumbers() {
        assertEquals(35_596, GameFormulas.storePrice(GameFormulas.App.FIREWALL, 21))
        assertEquals(41_877, GameFormulas.storePrice(GameFormulas.App.BYPASSER, 21))
        assertEquals(31_408, GameFormulas.storePrice(GameFormulas.App.KEYGEN, 21))
        assertEquals(39_398, GameFormulas.storePrice(GameFormulas.App.KEYGEN, 25))
        assertEquals(40_046, GameFormulas.storePrice(GameFormulas.App.PROXY, 21))
        // Guide Lv15 Keygen 20,281: the curve is within a few crypto from Lv15 up
        assertTrue(Math.abs(GameFormulas.storePrice(GameFormulas.App.KEYGEN, 15)!! - 20_281) <= 3)
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
