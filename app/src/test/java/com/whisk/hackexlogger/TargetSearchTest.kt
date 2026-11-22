package com.whisk.hackexlogger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetSearchTest {

    private fun target(ip: String, level: Int? = null, fw: Int? = null, enc: Int? = null, apps: Map<String, Int> = emptyMap()) =
        TargetRecord(
            ip = ip, level = level, firewall = fw, encryptor = enc,
            downloads = apps.mapValues { SoftwareEntry(it.value, "installed", null) }.toMutableMap()
        )

    private fun allAt(level: Int, except: Set<String> = emptySet()) =
        TargetSearch.LOOT_APPS.filter { it !in except }.associateWith { level }

    @Test
    fun allSoftwareRanksTheOneStopTargetFirstAndIgnoresItsFirewallLevel() {
        val oneStop = target("1.1.1.1", fw = 1, apps = allAt(20) + ("Firewall" to 1))   // every app but FW at 20
        val fullButHighWall = target("2.2.2.2", fw = 20, apps = allAt(20) + ("Firewall" to 20))
        val twoApps = target("3.3.3.3", fw = 0, apps = mapOf("Spam" to 20, "Siphon" to 20))
        val none = target("4.4.4.4", fw = 1, apps = allAt(19))
        val result = TargetSearch.search(listOf(twoApps, none, fullButHighWall, oneStop), "sw 20").map { it.ip }
        // Same 9 loot apps: lower Firewall wins; the 2-app target comes after both; no Lv20 app → not listed
        assertEquals(listOf("1.1.1.1", "2.2.2.2", "3.3.3.3"), result)
    }

    @Test
    fun allSoftwareTieBreaksOnEncryptorThenNearMisses() {
        val a = target("a", fw = 5, enc = 10, apps = mapOf("Spam" to 20))
        val b = target("b", fw = 5, enc = 3, apps = mapOf("Spam" to 20))
        val c = target("c", fw = 5, enc = 3, apps = mapOf("Spam" to 20, "Siphon" to 21))
        assertEquals(listOf("c", "b", "a"), TargetSearch.search(listOf(a, b, c), "software 20").map { it.ip })
    }

    @Test
    fun appSearchUsesAliasesFallsBackToNearLevelsAndPrefersLowWalls() {
        val far = target("far", fw = 1, apps = mapOf("Password Cracker" to 18))
        val near = target("near", fw = 9, apps = mapOf("Password Cracker" to 21))
        val exactHigh = target("exactHigh", fw = 30, apps = mapOf("Password Cracker" to 20))
        val exactLow = target("exactLow", fw = 2, apps = mapOf("Password Cracker" to 20))
        val all = listOf(far, near, exactHigh, exactLow)
        assertEquals(listOf("exactLow", "exactHigh"), TargetSearch.search(all, "crack 20").map { it.ip })
        assertEquals(listOf("exactLow", "exactHigh"), TargetSearch.search(all, "password cracker lv20").map { it.ip })
        // No Lv22 anywhere → ±1 (near, 21); then the result is only that
        assertEquals(listOf("near"), TargetSearch.search(all, "pc 22").map { it.ip })
        // ±2 reaches the 18
        assertEquals(listOf("far"), TargetSearch.search(listOf(far), "pc 16").map { it.ip })
    }

    @Test
    fun firewallSearchAlsoReadsTheHomeScreenFirewall() {
        val homeOnly = target("h", fw = 20)
        assertEquals(listOf("h"), TargetSearch.search(listOf(homeOnly), "fw 20").map { it.ip })
    }

    @Test
    fun playerLevelSearchExactAndRange() {
        val l30 = target("30a", level = 30, fw = 9)
        val l30low = target("30b", level = 30, fw = 1)
        val l32 = target("32", level = 32)
        val l40 = target("40", level = 40)
        val all = listOf(l40, l32, l30, l30low)
        assertEquals(listOf("30b", "30a"), TargetSearch.search(all, "lvl 30").map { it.ip })
        assertEquals(listOf("30b", "30a"), TargetSearch.search(all, "Level30").map { it.ip })
        assertEquals(listOf("30b", "30a", "32"), TargetSearch.search(all, "lv 28-32").map { it.ip })
    }

    @Test
    fun ordinaryTextIsNotMistakenForASoftwareQuery() {
        assertTrue(TargetSearch.parse("nova 3") is TargetSearch.Query.Text)
        assertTrue(TargetSearch.parse("12.34") is TargetSearch.Query.Text)
        assertTrue(TargetSearch.parse("a 5") is TargetSearch.Query.Text)
        val named = target("9.9.9.9").apply { username = "spammer" }
        assertEquals(listOf("9.9.9.9"), TargetSearch.search(listOf(named), "spammer").map { it.ip })
    }
}
