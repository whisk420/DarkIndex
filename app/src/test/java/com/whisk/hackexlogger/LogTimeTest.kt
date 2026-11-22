package com.whisk.hackexlogger

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.TimeZone

/** Log times are UTC, anchored by the log footer; old phone-local theft times are converted once (2026-11-20). */
class LogTimeTest {

    private fun utc(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int = 0): Long =
        java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { clear(); set(y, mo - 1, d, h, mi, s) }.timeInMillis

    @Before
    fun setUp() {
        TestEnv.freshRepository()
    }

    @After
    fun tearDown() {
        LogTime.nowProvider = { System.currentTimeMillis() }
    }

    @Test
    fun lineTimesAreUtcWhateverThePhoneZone() {
        val anchor = utc(2026, 11, 20, 12, 38, 57)
        assertEquals(utc(2026, 11, 20, 4, 0), LogTime.toEpochMs("11-20 4:00", anchor))
        assertEquals(utc(2026, 11, 19, 23, 59), LogTime.toEpochMs("11-19 23:59", anchor))
    }

    @Test
    fun decemberLinesReadInJanuaryBelongToLastYear() {
        val anchor = utc(2027, 1, 1, 0, 5)
        assertEquals(utc(2026, 12, 31, 23, 58), LogTime.toEpochMs("12-31 23:58", anchor))
        assertEquals(utc(2027, 1, 1, 0, 1), LogTime.toEpochMs("1-1 0:01", anchor))
    }

    @Test
    fun withoutAFooterTheCurrentUtcTimeIsTheAnchor() {
        LogTime.nowProvider = { utc(2027, 1, 2, 10, 0) }
        assertEquals(utc(2026, 12, 30, 8, 0), LogTime.toEpochMs("12-30 8:00"))
    }

    @Test
    fun invalidTimesAreRejected() {
        assertNull(LogTime.toEpochMs("13-01 1:00"))
        assertNull(LogTime.toEpochMs("11-20 25:00"))
        assertNull(LogTime.toEpochMs("garbage"))
    }

    @Test
    fun accessWindowWorksAcrossNewYear() {
        // The old minute counter ignored the month, so 12-31 23:59 vs 1-1 0:01 looked ~30 days apart
        val log = listOf(
            "// LOG",
            "[1-1 0:01] Stole 500 Crypto from hxaaaa...bbbb",
            "[12-31 23:59] Accessed device at 7.7.7.7",
            "Last saved: 2027-01-01 00:02:00"
        )
        val steal = LogScreenParser.parse(log).single { it.wallet != null }
        assertEquals("7.7.7.7", steal.ip)
    }

    @Test
    fun accessWindowStillClosesAfterThreeMinutes() {
        val log = listOf(
            "[11-20 10:05] Stole 500 Crypto from hxaaaa...bbbb",
            "[11-20 10:01] Accessed device at 7.7.7.7",
            "Last saved: 2026-11-20 10:06:00"
        )
        assertEquals("", LogScreenParser.parse(log).single { it.wallet != null }.ip)
    }

    @Test
    fun storedThefsAreMovedFromPhoneTimeToUtcOnce() {
        val denver = TimeZone.getTimeZone("America/Denver")
        // "[11-20 4:00]" stored by the old code on a Denver phone = 04:00 MST = 11:00 UTC
        val oldStored = utc(2026, 11, 20, 11, 0)
        TargetRepository.saveAllRecords(listOf(TargetRecord(ip = "1.1.1.1").withTheftEvent("hxW", oldStored, 10901)))

        val previous = TimeZone.getDefault()
        try {
            TimeZone.setDefault(denver)
            TargetRepository.loadCacheFromDisk() // first start after the update
            val after = TargetRepository.getAllRecords().single().walletThefts.getValue("hxW").single()
            assertEquals(utc(2026, 11, 20, 4, 0), after.timestampMs)

            TargetRepository.loadCacheFromDisk() // second start: not moved again
            assertEquals(utc(2026, 11, 20, 4, 0), TargetRepository.getAllRecords().single().walletThefts.getValue("hxW").single().timestampMs)
        } finally {
            TimeZone.setDefault(previous)
        }

        // The same log line read now lands on the same event (no duplicate)
        var record = TargetRepository.getAllRecords().single()
        record = record.withTheftEvent("hxW", LogTime.toEpochMs("11-20 4:00", utc(2026, 11, 20, 12, 0))!!, 10901)
        assertEquals(1, record.walletThefts.getValue("hxW").size)
    }

    @Test
    fun oldExportsAreConvertedOnImportNewOnesAreNot() {
        val previous = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Denver"))
            val oldStored = utc(2026, 11, 20, 11, 0)
            val oldBundle = """{"format":"target-intelligence-console","version":1,"targets":[
                {"ip":"1.1.1.1","walletThefts":{"hxW":[{"timestampMs":$oldStored,"amount":5}]}}]}"""
            DatabaseBackup.importDataBundle(oldBundle)
            assertEquals(utc(2026, 11, 20, 4, 0), TargetRepository.getAllRecords().single().walletThefts.getValue("hxW").single().timestampMs)

            // Round trip through a new export: unchanged
            val export = DatabaseBackup.exportDataBundle()
            TestEnv.freshRepository()
            DatabaseBackup.importDataBundle(export)
            assertEquals(utc(2026, 11, 20, 4, 0), TargetRepository.getAllRecords().single().walletThefts.getValue("hxW").single().timestampMs)
        } finally {
            TimeZone.setDefault(previous)
        }
    }
}
