package com.whisk.hackexlogger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class TargetIncomeTest {

    @Test
    fun testIncomeCalculation() {
        val logLines = listOf(
            "[10-16 1:16] Stole 7,705 Crypto from hx6d13...90df",
            "[10-16 1:16] Accessed device at 90.49.133.67",
            "[10-15 12:24] Stole 6,302 Crypto from hx6d13...90df",
            "[10-15 12:24] Accessed device at 90.49.133.67"
        )

        val updates = LogScreenParser.parse(logLines).filter { it.wallet != null }
        assertEquals(2, updates.size)

        val target = TargetRecord(ip = "90.49.133.67")
        val wallet = updates.first().wallet
        assertNotNull(wallet)

        for (update in updates) {
            if (update.wallet != null && update.stolenAmount != null && update.time != null) {
                val timeMs = DatabaseManager.parseLogTime(update.time)
                assertNotNull(timeMs)
                if (timeMs != null) {
                    target.addTheftEvent(update.wallet, timeMs, update.stolenAmount)
                }
            }
        }

        val income = target.getEstimatedIncome(wallet!!)
        assertNotNull(income)
        println("Calculated income: $income")
        assertEquals("💵 ~598/hr", income)
    }
}

