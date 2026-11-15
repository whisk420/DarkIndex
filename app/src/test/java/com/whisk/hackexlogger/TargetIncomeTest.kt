package com.whisk.hackexlogger

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

class TargetIncomeTest {

    private fun createTime(dayOffset: Int, hourOfDay: Int): Long {
        val c = Calendar.getInstance()
        c.set(2023, Calendar.JANUARY, 1, 0, 0, 0)
        c.set(Calendar.MILLISECOND, 0)
        c.add(Calendar.DAY_OF_MONTH, dayOffset)
        c.add(Calendar.HOUR_OF_DAY, hourOfDay)
        return c.timeInMillis
    }

    @Test
    fun testReliableClustersGroupingAnd7DaySplit() {
        val target = TargetRecord(ip = "1.1.1.1")
        val wallet = "hx123"

        // Cluster 1: Days 1-10, hacked every 12 hours (Gap <= 24h)
        // Should be split into a 7-day cluster (Days 1-7) and a 3-day cluster (Days 8-10)
        for (day in 1..10) {
            target.addTheftEvent(wallet, createTime(day, 10), 1000)
            target.addTheftEvent(wallet, createTime(day, 22), 1000)
        }

        // Gap of 48 hours (Day 11, Day 12 skipped)

        // Cluster 2: Day 13 (single event)
        target.addTheftEvent(wallet, createTime(13, 10), 500)

        // Cluster 3: Day 15-16, hacked every 12 hours
        for (day in 15..16) {
            target.addTheftEvent(wallet, createTime(day, 10), 1000)
            target.addTheftEvent(wallet, createTime(day, 22), 1000)
        }

        val clusters = target.getReliableClusters(wallet)

        assertEquals("Should have 4 clusters (2 split from first group + 1 single + 1 double-day)", 4, clusters.size)

        val c0 = clusters[0]
        val c2 = clusters[2]
        val c3 = clusters[3]

        // Cluster 0 should span exactly 7 days (Day 1 10:00 to Day 8 10:00)
        val span0 = c0.last().timestampMs - c0.first().timestampMs
        assertEquals("Cluster 0 should be exactly 7 days", 7 * 24 * 3600000L, span0)

        // Cluster 2 should be a single event
        assertEquals("Cluster 2 should have exactly 1 event", 1, c2.size)

        // Cluster 3 should span 1 day + 12 hours
        val span3 = c3.last().timestampMs - c3.first().timestampMs
        assertEquals("Cluster 3 should span 36 hours", 36 * 3600000L, span3)
    }

    @Test
    fun testEstimatedIncomeAveragesMultipleClusters() {
        val target = TargetRecord(ip = "1.1.1.1")
        val wallet = "hx123"

        // Cluster 1: 4000 stolen over 4 hours (Rate: 1000/hr)
        target.addTheftEvent(wallet, createTime(1, 10), 1000) // Initial hack (discarded for rate sum)
        target.addTheftEvent(wallet, createTime(1, 12), 2000)
        target.addTheftEvent(wallet, createTime(1, 14), 2000)

        // Cluster 2: 3000 stolen over 6 hours (Rate: 500/hr)
        target.addTheftEvent(wallet, createTime(5, 10), 1000) // Initial hack
        target.addTheftEvent(wallet, createTime(5, 13), 1500)
        target.addTheftEvent(wallet, createTime(5, 16), 1500)

        val income = target.getEstimatedIncome(wallet)
        // Avg of 1000/hr and 500/hr = 750/hr
        assertEquals("💵 ~750/hr", income)
    }

    @Test
    fun testEstimatedIncomeSingleEventFallback() {
        val target = TargetRecord(ip = "1.1.1.1")
        val wallet = "hx123"

        // Only one hack
        target.addTheftEvent(wallet, createTime(1, 10), 50000)

        val income = target.getEstimatedIncome(wallet)
        assertEquals("💵 ~50.0k", income)

        // Another single hack 5 days later (still separate clusters of size 1)
        target.addTheftEvent(wallet, createTime(6, 10), 20000)
        val income2 = target.getEstimatedIncome(wallet)
        // Should show the most recent flat amount
        assertEquals("💵 ~20.0k", income2)
    }
}
