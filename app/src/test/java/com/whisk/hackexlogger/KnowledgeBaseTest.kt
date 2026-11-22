package com.whisk.hackexlogger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/**
 * [GameFormulas] against every number hackex.net/guide publishes (read 2026-11-21) and the verified Store prices
 * in CLAUDE.md §4, plus integrity checks on the Knowledge Base content and calculators.
 */
class KnowledgeBaseTest {

    // ── Formulas vs published numbers ──────────────────────────────────────────────────────────────────────

    @Test
    fun spamGuideCurveMatchesEveryGuideCell() {
        fun guide(lvl: Int, gap: Int) =
            (GameFormulas.spamGuideBaseIncome(lvl) * GameFormulas.spamLevelGapMultiplier(gap)).roundToInt().coerceAtMost(100)
        val equal = mapOf(1 to 12, 5 to 24, 10 to 42, 15 to 62, 20 to 83)
        val up10 = mapOf(1 to 17, 5 to 34, 10 to 59, 15 to 87, 20 to 100)
        val down10 = mapOf(15 to 31, 20 to 42)
        equal.forEach { (lvl, v) -> assertEquals("equal Lv$lvl", v, guide(lvl, 0)) }
        up10.forEach { (lvl, v) -> assertEquals("+10 Lv$lvl", v, guide(lvl, 10)) }
        down10.forEach { (lvl, v) -> assertEquals("-10 Lv$lvl", v, guide(lvl, -10)) }
    }

    @Test
    fun spamIncomeMatchesOwnerMeasurements() {
        // 2026-11-22: player Lv45, victim Lv43, fresh uploads, read off SPAM EARNINGS
        val measured = mapOf(1 to 18, 2 to 23, 3 to 27, 7 to 47, 11 to 69)
        measured.forEach { (lvl, v) -> assertEquals("gap -2 Lv$lvl", v, GameFormulas.spamIncomePerHour(lvl, -2)) }
        // Same victim, cap: Lv16 is the cheapest level reaching 100/hr; Lv15 at equal level
        assertEquals(16, GameFormulas.spamCapLevel(-2))
        assertEquals(15, GameFormulas.spamCapLevel(0))
    }

    @Test
    fun spamBonusCapsAtFiftyPercentAndPenaltyHasAFloor() {
        assertEquals(1.5, GameFormulas.spamLevelGapMultiplier(30), 1e-9)
        assertEquals(0.10, GameFormulas.spamLevelGapMultiplier(-40), 1e-9)
        assertEquals(0, GameFormulas.spamIncomePerHour(0, 0))
        assertEquals(100, GameFormulas.spamIncomePerHour(60, 0))
    }

    @Test
    fun spamDecayMatchesGuide() {
        assertEquals(100, (GameFormulas.spamDecayFactor(1.0) * 100).roundToInt())
        assertEquals(100, (GameFormulas.spamDecayFactor(21.0) * 100).roundToInt())
        assertEquals(90, (GameFormulas.spamDecayFactor(30.0) * 100).roundToInt())
        assertEquals(67, (GameFormulas.spamDecayFactor(50.0) * 100).roundToInt())
        assertEquals(44, (GameFormulas.spamDecayFactor(70.0) * 100).roundToInt())
        assertEquals(10, (GameFormulas.spamDecayFactor(100.0) * 100).roundToInt())
        assertEquals(10, (GameFormulas.spamDecayFactor(400.0) * 100).roundToInt())
        // Scythe: half as fast
        assertEquals(GameFormulas.spamDecayFactor(50.0), GameFormulas.spamDecayFactor(100.0, halfDecay = true), 1e-9)
        // Decay applies to the capped rate
        assertEquals(67, GameFormulas.spamIncomePerHour(30, 0, 50.0))
    }

    @Test
    fun spamFeeAndBotnetExampleMatchGuide() {
        val fee = mapOf(2 to 0.0, 4 to 0.01, 8 to 0.03, 16 to 0.07, 28 to 0.13, 44 to 0.21, 64 to 0.30)
        fee.forEach { (n, r) -> assertEquals("fee at $n", r, GameFormulas.spamFeeRate(n), 1e-9) }
        assertEquals(0.15, GameFormulas.spamFeeRate(64, 0.50), 1e-9)

        // Guide example: all slots full of Lv15 Spam on equal victims (62/hr), net per hour
        val netPerHour = mapOf("Free" to 124, "Node" to 246, "Cluster" to 481, "Grid" to 923, "Farm" to 1510, "Hive" to 2155, "Nexus" to 2778)
        for (tier in GameFormulas.BOTNET_TIERS) {
            val gross = tier.slots * 62
            val net = gross - (gross * GameFormulas.spamFeeRate(tier.slots)).roundToInt()
            assertEquals(tier.name, netPerHour[tier.name], net)
        }
    }

    @Test
    fun proxyCostMatchesAllTwentyGuideCells() {
        val levels = listOf(1, 5, 10, 19, 20)
        val expected = mapOf(
            2 to listOf(20, 45, 64, 88, 90),
            6 to listOf(57, 128, 181, 249, 255),
            12 to listOf(108, 242, 342, 471, 483),
            24 to listOf(204, 457, 646, 890, 913)
        )
        expected.forEach { (hours, row) ->
            levels.zip(row).forEach { (lvl, cost) -> assertEquals("Lv$lvl ${hours}h", cost, GameFormulas.proxyActivationCost(lvl, hours)) }
        }
        assertNull(GameFormulas.proxyActivationCost(10, 3))
        assertEquals("192.168.1.xxx", GameFormulas.proxyMaskExample(5))
        assertEquals("192.168.xxx.xxx", GameFormulas.proxyMaskExample(6))
        assertEquals("192.xxx.xxx.xxx", GameFormulas.proxyMaskExample(15))
        assertEquals("xxx.xxx.xxx.xxx", GameFormulas.proxyMaskExample(16))
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

    @Test
    fun keygenFeeRatio() {
        assertNull(GameFormulas.keygenFeeRatio(0))
        assertEquals(0.15, GameFormulas.keygenFeeRatio(1)!!, 1e-9)
        assertEquals(0.50, GameFormulas.keygenFeeRatio(20)!!, 1e-9)
        assertEquals(0.50, GameFormulas.keygenFeeRatio(35)!!, 1e-9)
        assertNull(GameFormulas.keygenFee(GameFormulas.App.SPAM, 20, 20))
        // 1-level jump on Keygen Lv25: 15% of 39,398, rounded up
        assertEquals(5_910, GameFormulas.keygenFee(GameFormulas.App.KEYGEN, 24, 25))
    }

    @Test
    fun antivirusAndHardeningMatchGuide() {
        val av = mapOf(7 to 95, 5 to 95, 4 to 90, 3 to 80, 2 to 70, 1 to 60, 0 to 50, -1 to 20, -2 to 18, -3 to 16,
            -4 to 14, -5 to 12, -6 to 10, -7 to 8, -8 to 6, -9 to 5, -15 to 5)
        av.forEach { (d, pct) -> assertEquals("AV $d", pct, GameFormulas.avRemovalChance(20 + d, 20)) }
        assertEquals(180.0, GameFormulas.avScanMinutes(1), 1e-9)
        assertEquals(53.0, GameFormulas.avScanMinutes(10), 1e-9)
        assertEquals(45.0, GameFormulas.avScanMinutes(12), 1e-9)
        assertEquals(105.0, GameFormulas.avScanMinutes(4), 1e-9) // between 120 (Lv3) and 90 (Lv5)
        // Guide: +1 hack → 5h 15m on Raider
        assertEquals(315.0, GameFormulas.avScanMinutes(1) * GameFormulas.concurrencyMultiplier(2), 1e-9)

        assertEquals(0, GameFormulas.hardeningBlockChance(2))
        assertEquals(9, GameFormulas.hardeningBlockChance(5))
        assertEquals(18, GameFormulas.hardeningBlockChance(8))
        assertEquals(90, GameFormulas.hardeningBlockChance(40))
    }

    @Test
    fun repAndScore() {
        assertEquals(GameFormulas.score(20, 1), 20L)
        // Guide: "a Lv 5 player with 1,000 rep out-scores a Lv 20 player with 1 rep"
        assertTrue(GameFormulas.score(5, 1000) > GameFormulas.score(20, 1))
        val rep = GameFormulas.repForScore(30, 100_000)
        assertTrue(GameFormulas.score(30, rep) >= 100_000)
        assertTrue(GameFormulas.score(30, rep - 1) < 100_000)
        assertEquals(500, GameFormulas.repLossFromVirus(25))
        assertEquals(125, GameFormulas.repLossFromVirus(5))
        assertEquals(19, GameFormulas.defenceRep(20))
        assertEquals(16, GameFormulas.defenceRep(1))
        assertEquals(25, GameFormulas.traceDetectionRep(30))
        assertEquals(1.0, GameFormulas.hackRepGapFactor(5), 1e-9)
        assertEquals(0.0, GameFormulas.hackRepGapFactor(10), 1e-9)
    }

    @Test
    fun processSpeedups() {
        assertEquals(0.66, GameFormulas.remainingAfterAds(4), 1e-9) // 34% total cut (guide)
        assertEquals(1.75, GameFormulas.concurrencyMultiplier(2), 1e-9)
        assertEquals(3.25, GameFormulas.concurrencyMultiplier(4), 1e-9)
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
                is KbBlock.Unknowns -> b.items.forEach { assertTrue("${t.id} ${it.first}", Regex("M-\\d\\d").matches(it.first)) }
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
    fun calculatorsUseTheSameMaths() {
        val spam = KbCalculators.byId(KbCalculators.SPAM)!!.run(mapOf("spam" to 20, "me" to 35, "target" to 45, "days" to 0))
        assertTrue(spam, spam.contains("Fresh upload: 100/hr"))
        assertTrue(spam, spam.contains("Lv 10"))
        val proxy = KbCalculators.byId(KbCalculators.PROXY)!!.run(mapOf("level" to 20, "hours" to 3, "count" to 2))
        assertTrue(proxy, proxy.contains("913"))
        assertTrue(proxy, proxy.contains("1,826"))
        val av = KbCalculators.byId(KbCalculators.ANTIVIRUS)!!.run(mapOf("av" to 18, "virus" to 20, "scans" to 1, "device" to 0, "hacks" to 0))
        assertTrue(av, av.contains("18%"))
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
