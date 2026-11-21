package com.whisk.hackexlogger

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Marathon Mode flow, fed with real own-log text (Raw Dump #15, 2026-11-21) through [ScrapePipeline].
 * The fixture's steals run 20:11–20:14 UTC; hx90dc...69d5 is stolen twice (380 @20:11, 33 @20:12).
 */
class MarathonTest {

    private lateinit var dir: File
    private val log = TestEnv.fixture("own_log_2026-11-21.txt")
    private val emptyLog = "// LOG\nSAVE\n~/log.txt\n\nLast saved: 2026-11-21 20:40:00\nHOME\nSCAN\nPROCESSES\nAPPS\nSTORE\nCREWS"

    private fun utc(h: Int, m: Int) = java.time.Instant.parse("2026-11-21T%02d:%02d:00Z".format(h, m)).toEpochMilli()
    private fun scrape(raw: String) = ScrapePipeline.run(raw, raw.split("\n"))

    @Before
    fun setUp() {
        dir = TestEnv.freshRepository()
        Marathon.resetForTests()
        Marathon.historyFileProvider = { File(dir, Marathon.HISTORY_FILE) }
    }

    @After
    fun tearDown() = Marathon.resetForTests()

    @Test
    fun fullRunWithEarlyEndWipedLogAndResults() {
        Marathon.start(10, utc(20, 10) + 30_000)
        assertEquals(Marathon.Phase.TIMED, Marathon.phase)

        // Grabbing the log mid-run collects steals but doesn't advance the flow
        val r = scrape(log)
        assertTrue(r.savedItems.any { it.startsWith("Marathon +10") })
        assertEquals(10, Marathon.stealCount)
        assertEquals(Marathon.Phase.TIMED, Marathon.phase)

        Marathon.endEarly(utc(20, 15))
        assertEquals(Marathon.Phase.AWAITING_LOG, Marathon.phase)
        assertEquals(0, Marathon.remainingMs(utc(20, 15)))

        // The player wiped the log: an empty own log still counts as "log grabbed", and nothing is lost
        scrape(emptyLog)
        assertEquals(Marathon.Phase.READY_PASS2, Marathon.phase)
        assertEquals(10, Marathon.stealCount)

        // First target = earliest steal (20:11, masked IP → wallet placeholder, so labelled by wallet)
        val first = Marathon.firstTarget()!!
        assertEquals("hx90dc...69d5", first.wallet)
        assertNull(first.ip)

        Marathon.startPass2()
        assertEquals(Marathon.Phase.PASS2, Marathon.phase)
        Marathon.finish()
        assertEquals(Marathon.Phase.AWAITING_FINAL_LOG, Marathon.phase)
        Marathon.backToPass2()
        assertEquals(Marathon.Phase.PASS2, Marathon.phase)
        Marathon.finish()

        // Final grab: a second steal from 1.163.20.168's wallet 20 minutes after the first
        val pass2 = "// LOG\nSAVE\n~/log.txt\n" +
            "[11-21 20:33] Stole 500 Crypto from hxfec3...ace3\n" +
            "[11-21 20:33] Accessed device at 1.163.20.168\n" +
            "\nLast saved: 2026-11-21 20:34:00\nHOME\nSCAN\nPROCESSES\nAPPS\nSTORE\nCREWS"
        scrape(pass2)
        assertEquals(Marathon.Phase.RESULTS, Marathon.phase)

        val run = Marathon.lastRun!!
        assertEquals(1, run.runNumber)
        assertTrue(run.endedEarly)
        assertEquals(9, run.rows.size) // 9 distinct wallets
        // 33 refilled in 1 min = 1,980/hr ranks above 500 in 20 min = 1,500/hr
        assertEquals("hx90dc...69d5", run.rows[0].target.wallet)
        assertEquals(1980, run.rows[0].ratePerHour)
        val fec3 = run.rows[1]
        assertEquals("hxfec3...ace3", fec3.target.wallet)
        assertEquals("1.163.20.168", fec3.target.ip)
        assertEquals(1500, fec3.ratePerHour)
        assertEquals(500, fec3.refillAmount)
        assertEquals(20L, fec3.spanMinutes)
        // Single-steal wallets have no rate and sort by their steal
        assertTrue(run.rows.drop(2).all { it.ratePerHour == null })
        assertEquals("hx3b73...f43c", run.rows[2].target.wallet) // 1,755
        assertEquals("1.163.20.168", run.lastTarget!!.ip)

        // Saved to history
        val history = File(dir, Marathon.HISTORY_FILE).readLines().filter { it.isNotBlank() }
        assertEquals(1, history.size)
        assertTrue(history[0].contains("hxfec3...ace3"))

        // Grabbing the log again while results show changes nothing
        scrape(log)
        assertEquals(Marathon.Phase.RESULTS, Marathon.phase)
    }

    @Test
    fun timerExpiryOnlyWhenDeadlinePassed() {
        val start = utc(20, 0)
        Marathon.start(5, start)
        assertFalse(Marathon.checkTimer(start + 4 * 60_000))
        assertEquals(60_000, Marathon.remainingMs(start + 4 * 60_000))
        assertTrue(Marathon.checkTimer(start + 5 * 60_000))
        assertEquals(Marathon.Phase.AWAITING_LOG, Marathon.phase)
        assertFalse(Marathon.checkTimer(start + 6 * 60_000))
    }

    @Test
    fun stealsBeforeTheRunStartAreIgnored() {
        Marathon.start(5, utc(20, 13) + 45_000) // started during 20:13 → the 4 steals at 20:13 and 3 at 20:14 count
        scrape(log)
        assertEquals(7, Marathon.stealCount)
    }

    @Test
    fun chainedRunStartsFromLastTargetAndNeverRecountsOldSteals() {
        Marathon.start(5, utc(20, 10))
        scrape(log)
        Marathon.endEarly(utc(20, 15)); scrape(log)
        Marathon.startPass2(); Marathon.finish(); scrape(log)
        val last = Marathon.lastRun!!.lastTarget
        assertEquals("15.148.54.125", last!!.ip)

        Marathon.dismissResults()
        assertEquals(Marathon.Phase.CHAIN_PROMPT, Marathon.phase)
        Marathon.chain(10, utc(20, 14)) // same minute as the old run's last steals
        assertEquals(Marathon.Phase.TIMED, Marathon.phase)
        assertEquals(2, Marathon.runNumber)
        assertEquals("15.148.54.125", Marathon.startHint!!.ip)
        scrape(log)
        assertEquals(0, Marathon.stealCount)

        Marathon.end()
        assertFalse(Marathon.isActive)
    }

    @Test
    fun onlyYourOwnLogFeedsTheRun() {
        assertTrue(Marathon.isOwnLogScreen(log.split("\n")))
        assertFalse(Marathon.isOwnLogScreen(listOf("// VICTIM LOG", "~/victim_log.txt", "[11-21 20:00] Device accessed from 1.2.3.4")))
        assertFalse(Marathon.isOwnLogScreen(listOf("CONNECTED", "DISCONNECT", "// LOG", "[11-21 20:00] Stole 5 Crypto from hx1234...abcd")))
        assertFalse(Marathon.isOwnLogScreen(TestEnv.fixture("target_wallet_2026-11-19.txt").split("\n")))

        // Idle: a log scrape is saved as usual and nothing is collected
        val r = scrape(log)
        assertTrue(r.savedItems.none { it.startsWith("Marathon") })
        assertNull(Marathon.onOwnLog(emptyList(), null, 0))
    }

    @Test
    fun knownOwnerSuppliesIpForMaskedSteal() {
        Marathon.ownerLookup = { w -> if (w == "hx90dc...69d5") TargetRecord(ip = "9.9.9.9", username = "Bob", wallets = mutableListOf(w)) else null }
        try {
            Marathon.start(5, utc(20, 10))
            scrape(log)
            val first = Marathon.firstTarget()
            assertNotNull(first)
            assertEquals("9.9.9.9", first!!.ip)
            assertEquals("Bob", first.username)
        } finally {
            Marathon.resetForTests()
        }
    }
}
