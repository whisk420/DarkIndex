package com.whisk.hackexlogger

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetAdapterTest {

    private val diffCallback = ListItemDiffCallback()

    @Test
    fun testDiffCallbackAreItemsTheSameForHeaders() {
        val header1 = ListItem.Header("FULL TARGETS (2)", isPartialHeader = false)
        val header2 = ListItem.Header("FULL TARGETS (5)", isPartialHeader = false)
        val partialHeader = ListItem.Header("▼ PARTIAL TARGETS (1)", isPartialHeader = true)

        // Headers of the same section type are considered the same item position
        assertTrue(diffCallback.areItemsTheSame(header1, header2))
        assertFalse(diffCallback.areItemsTheSame(header1, partialHeader))
    }

    @Test
    fun testDiffCallbackAreItemsTheSameForTargets() {
        val target1 = ListItem.Target(TargetRecord(ip = "192.168.1.1", level = 10))
        val target1Updated = ListItem.Target(TargetRecord(ip = "192.168.1.1", level = 20))
        val target2 = ListItem.Target(TargetRecord(ip = "10.0.0.1", level = 10))
        val header = ListItem.Header("FULL TARGETS (1)")

        // Same IP = same item identity
        assertTrue(diffCallback.areItemsTheSame(target1, target1Updated))
        assertFalse(diffCallback.areItemsTheSame(target1, target2))
        assertFalse(diffCallback.areItemsTheSame(target1, header))
    }

    @Test
    fun testDiffCallbackAreContentsTheSame() {
        val target1 = ListItem.Target(TargetRecord(ip = "192.168.1.1", level = 10, firewall = 5))
        val target1Identical = ListItem.Target(TargetRecord(ip = "192.168.1.1", level = 10, firewall = 5))
        val target1Modified = ListItem.Target(TargetRecord(ip = "192.168.1.1", level = 10, firewall = 15))

        assertTrue(diffCallback.areContentsTheSame(target1, target1Identical))
        assertFalse(diffCallback.areContentsTheSame(target1, target1Modified))

        val header1 = ListItem.Header("FULL TARGETS (2)", isPartialHeader = false, isExpanded = false)
        val header1Identical = ListItem.Header("FULL TARGETS (2)", isPartialHeader = false, isExpanded = false)
        val header1Expanded = ListItem.Header("FULL TARGETS (2)", isPartialHeader = false, isExpanded = true)

        assertTrue(diffCallback.areContentsTheSame(header1, header1Identical))
        assertFalse(diffCallback.areContentsTheSame(header1, header1Expanded))
    }
}
