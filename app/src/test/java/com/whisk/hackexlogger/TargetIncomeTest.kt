package com.whisk.hackexlogger

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

class TargetIncomeTest {

    private fun createTime(dayOffset: Int, hourOfDay: Int, minute: Int = 0): Long {
        val c = Calendar.getInstance()
        c.set(2023, Calendar.JANUARY, 1, 0, 0, 0)
        c.set(Calendar.MILLISECOND, 0)
        c.add(Calendar.DAY_OF_MONTH, dayOffset)
        c.add(Calendar.HOUR_OF_DAY, hourOfDay)
        c.add(Calendar.MINUTE, minute)
        return c.timeInMillis
    }

    @Test
    fun testReliableClustersGroupingAnd7DaySplit() {
        var target = TargetRecord(ip = "1.1.1.1")
        val wallet = "hx123"

        // Cluster 1: Days 1-10, hacked every 30 minutes (Gap <= 1h)
        // Should be split into a 7-day cluster (Days 1-7) and a 3-day cluster (Days 8-10)
        var t = createTime(1, 10)
        val tenDays = 10 * 24 * 60 * 60 * 1000L
        val endTime = t + tenDays
        while (t <= endTime) {
            target = target.withTheftEvent(wallet, t, 1000)
            t += 30 * 60 * 1000L
        }

        // Gap of 48 hours is naturally formed here

        // Cluster 2: Day 13 (single event)
        target = target.withTheftEvent(wallet, createTime(13, 10), 500)
        // Cluster 3: Day 15-16, hacked every 30 minutes
        t = createTime(15, 10)
        val thirtySixHours = 36 * 60 * 60 * 1000L
        val endTwoDays = t + thirtySixHours
        while (t <= endTwoDays) {
            target = target.withTheftEvent(wallet, t, 1000)
            t += 30 * 60 * 1000L
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
        var target = TargetRecord(ip = "1.1.1.1")
        val wallet = "hx123"

        // Cluster 1: 4000 stolen over 1 hour (Rate: 4000/hr)
        target = target.withTheftEvent(wallet, createTime(1, 10, 0), 1000) // Initial hack (discarded for rate sum)
        target = target.withTheftEvent(wallet, createTime(1, 10, 30), 2000)
        target = target.withTheftEvent(wallet, createTime(1, 11, 0), 2000)
        // Cluster 2: 3000 stolen over 1 hour (Rate: 3000/hr)
        target = target.withTheftEvent(wallet, createTime(5, 10, 0), 1000) // Initial hack
        target = target.withTheftEvent(wallet, createTime(5, 10, 30), 1500)
        target = target.withTheftEvent(wallet, createTime(5, 11, 0), 1500)
        val income = target.getEstimatedIncome(wallet)
        // Avg of 4000/hr and 3000/hr = 3500/hr
        assertEquals("₵3,500/hr", income)
    }

    @Test
    fun testEstimatedIncomeSingleEventFallback() {
        var target = TargetRecord(ip = "1.1.1.1")
        val wallet = "hx123"

        // Only one hack
        target = target.withTheftEvent(wallet, createTime(1, 10), 50000)
        val income = target.getEstimatedIncome(wallet)
        assertEquals("₵50.0k", income)

        // Another single hack 5 days later (still separate clusters of size 1)
        target = target.withTheftEvent(wallet, createTime(6, 10), 20000)
        val income2 = target.getEstimatedIncome(wallet)
        // Should show the most recent flat amount
        assertEquals("₵20.0k", income2)
    }
}
