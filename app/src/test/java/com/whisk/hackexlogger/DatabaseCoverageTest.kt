package com.whisk.hackexlogger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test

/**
 * DatabaseManager write paths that had no tests: Processes, Crew, Bypass and Import.
 * Masked-IP behaviour on the Process and Crew screens is a known open bug (see TODO.md) and is
 * deliberately not pinned here.
 */
class DatabaseCoverageTest {

    @Before
    fun setUp() {
        TestEnv.freshRepository()
    }

    private fun record(ip: String) = TargetRepository.getAllRecords().find { it.ip == ip }

    // ---- Processes ------------------------------------------------------------------------------

    @Test
    fun processScreenCreatesUnknownTargetsAndKeepsExistingOnes() {
        TargetRepository.saveAllRecords(listOf(TargetRecord(ip = "1.1.1.1", username = "Known", level = 30)))

        DatabaseManager.processProcessScreen(
            ProcessScreen(
                ips = listOf("1.1.1.1", "2.2.2.2"),
                invalidatedIps = listOf("3.3.3.3"),
                downloads = emptyList()
            )
        )

        // Existing record untouched
        assertEquals("Known", record("1.1.1.1")?.username)
        assertEquals(30, record("1.1.1.1")?.level)
        // A finished process marks the new target as seen now; an invalidated one stays unvalidated
        assertNotNull(record("2.2.2.2")?.lastValidated)
        assertNotNull(record("3.3.3.3"))
        assertNull(record("3.3.3.3")?.lastValidated)
        assertEquals(3, TargetRepository.getAllRecords().size)
    }

    @Test
    fun processScreenRecordsHigherDownloadsOnly() {
        TargetRepository.saveAllRecords(listOf(
            TargetRecord(ip = "1.1.1.1", downloads = mutableMapOf("Spam" to SoftwareEntry(10, "installed", null)))
        ))

        DatabaseManager.processProcessScreen(
            ProcessScreen(
                ips = listOf("1.1.1.1"),
                invalidatedIps = emptyList(),
                downloads = listOf(
                    ProcessDownloadedSoftware("1.1.1.1", "Spam", 8),     // lower: ignored
                    ProcessDownloadedSoftware("1.1.1.1", "Rootkit", 6),  // new: added
                    ProcessDownloadedSoftware("9.9.9.9", "Spam", 20)     // unknown target: ignored
                )
            )
        )

        val downloads = record("1.1.1.1")!!.downloads
        assertEquals(10, downloads["Spam"]?.level)
        assertEquals(6, downloads["Rootkit"]?.level)
        assertNull(record("9.9.9.9"))
    }

    @Test
    fun processScreenFromRealTextNeverStoresLocalhost() {
        val raw = """
            < back
            DISCONNECT
            // PROCESSES
            2 active tasks
            Antivirus Scan
            Lv.30
            DONE
            0.0.0.0
            Firewall Bypass
            Lv.25
            DONE
            64.59.40.157
        """.trimIndent()
        DatabaseManager.processProcessScreen(ProcessScreenParser.parse(raw.lines())!!)

        assertEquals(listOf("64.59.40.157"), TargetRepository.getAllRecords().map { it.ip })
    }

    // ---- Crew -----------------------------------------------------------------------------------

    @Test
    fun crewScreenSpreadsTheKnownClanTag() {
        TargetRepository.saveAllRecords(listOf(
            TargetRecord(ip = "1.1.1.1", username = "Leader", clan = "SPAIN"),
            TargetRecord(ip = "2.2.2.2", username = "OldClan", clan = "OLD")
        ))

        DatabaseManager.processCrewScreen(CrewScreen(members = listOf("1.1.1.1", "2.2.2.2", "3.3.3.3")))

        assertEquals("SPAIN", record("2.2.2.2")?.clan)   // updated
        assertEquals("OldClan", record("2.2.2.2")?.username)
        assertEquals("SPAIN", record("3.3.3.3")?.clan)   // created
    }

    @Test
    fun crewScreenWithNoKnownClanIsDiscarded() {
        // The crew name on screen can't be matched to a clan tag, so without a member whose clan we
        // already know, nothing is saved (owner decision 2026-11-19).
        TargetRepository.saveAllRecords(listOf(TargetRecord(ip = "1.1.1.1", username = "NoClan")))

        DatabaseManager.processCrewScreen(CrewScreen(members = listOf("1.1.1.1", "3.3.3.3")))

        assertNull(record("1.1.1.1")?.clan)
        assertNull(record("3.3.3.3"))
    }

    // ---- Bypass ---------------------------------------------------------------------------------

    @Test
    fun bypassScreenUpdatesTargetDefencesAndPlayerBypasser() {
        TargetRepository.saveAllRecords(listOf(TargetRecord(ip = "231.130.229.178", firewall = 10, encryptor = 10)))

        DatabaseManager.processBypassScreen(
            BypassScreen(targetIp = "231.130.229.178", firewall = 23, encryptor = 24, playerBypasser = 20, apexBonus = 3)
        )

        assertEquals(23, record("231.130.229.178")?.firewall)
        assertEquals(24, record("231.130.229.178")?.encryptor)
        // Stored in Store ("next level") form: 20 + Apex (+3) + 1. Whether the Apex bonus is then
        // counted twice is part of the deferred "Verify isApexDeviceOwned" item.
        assertEquals(24, PlayerProfileRepository.getProfile().softwareLevels["Bypasser"])
    }

    @Test
    fun bypassScreenWithoutIpOnlyUpdatesPlayer() {
        DatabaseManager.processBypassScreen(
            BypassScreen(targetIp = null, firewall = 23, encryptor = 24, playerBypasser = 18, apexBonus = null)
        )
        assertTrue(TargetRepository.getAllRecords().isEmpty())
        assertEquals(19, PlayerProfileRepository.getProfile().softwareLevels["Bypasser"])
    }

    // ---- Import ---------------------------------------------------------------------------------

    @Test
    fun importMergesIntoExistingTargetsByIpUsernameOrWallet() {
        TargetRepository.saveAllRecords(listOf(
            TargetRecord(ip = "1.1.1.1", username = "Alpha", level = 10),
            TargetRecord(ip = "unknown:Bravo", username = "Bravo")
        ))
        val bundle = """
            {"format":"target-intelligence-console","version":1,"targets":[
              {"ip":"1.1.1.1","level":12},
              {"ip":"2.2.2.2","username":"bravo","firewall":9},
              {"ip":"3.3.3.3","username":"Charlie"}
            ]}
        """.trimIndent()

        val result = TargetRepository.importDataBundle(bundle)

        assertEquals(3, result.targetCount)
        assertEquals(12, record("1.1.1.1")?.level)            // same IP: incoming value wins
        assertEquals("Alpha", record("1.1.1.1")?.username)    // ...but missing fields are kept
        assertNull(record("unknown:Bravo"))                    // placeholder replaced by the real IP
        assertEquals(9, record("2.2.2.2")?.firewall)
        assertEquals("Charlie", record("3.3.3.3")?.username)
        assertEquals(3, TargetRepository.getAllRecords().size)
    }

    @Ignore("Known bug B2 (TODO: 'Import drops raw ingests'): the final save overwrites what the ingests wrote. Un-ignore when fixed.")
    @Test
    fun importAppliesRawIngests() {
        val bundle = """
            {"format":"target-intelligence-console","version":1,"targets":[],
             "ingests":[{"type":"home","raw":"> Zulu _\nLVL\n20\n// SYSTEM INFO\nIP\n5.5.5.5\nDEVICE\nBolt\nNETWORK\nDSL\nFIREWALL\nLv.7\nENCRYPTOR\nLv.8"}]}
        """.trimIndent()

        TargetRepository.importDataBundle(bundle)

        assertEquals("Zulu", record("5.5.5.5")?.username)
    }

    @Test
    fun importRestoresSiphonTrackerState() {
        val bundle = """
            {"format":"target-intelligence-console","version":1,"targets":[],
             "siphonAlerts":["7.7.7.7"],"knownSiphons":{"8.8.8.8":500}}
        """.trimIndent()

        TargetRepository.importDataBundle(bundle)

        assertEquals(listOf("7.7.7.7"), SiphonTracker.getSiphonAlerts())
        assertEquals(500, SiphonTracker.getKnownSiphons()["8.8.8.8"])
        assertFalse(SiphonTracker.getKnownSiphons().containsKey("7.7.7.7"))
    }
}
