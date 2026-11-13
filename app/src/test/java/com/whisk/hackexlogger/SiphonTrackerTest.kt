package com.whisk.hackexlogger

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class FakeContext : android.content.ContextWrapper(null) {
    private val tempDir = java.io.File(System.getProperty("java.io.tmpdir"), "hackex_test")
    init { tempDir.mkdirs() }
    override fun getFilesDir(): java.io.File = tempDir
}

class SiphonTrackerTest {

    @Before
    fun setUp() {
        TargetRepository.prefs = FakeSharedPreferences()
        TargetRepository.isInitialized = true
        SiphonTracker.init()
        SiphonTracker.clearSiphonTracking()
    }

    @Test
    fun testSiphonInstalledAndReinstalledAlert() {
        val fakeContext = FakeContext()

        // 1. Initial Siphon Scraping
        val initialSiphon = SiphonItem(ip = "192.168.1.50", level = 10, percent = "5%", amount = 1000, timeAgo = "1h ago")
        val initialScreen = SiphonScreen(deployedCount = 1, items = listOf(initialSiphon))

        SiphonTracker.processSiphonScreen(initialScreen, fakeContext)
        assertEquals(0, SiphonTracker.getSiphonAlerts().size)

        // 2. Amount drops (reinstalled after Antivirus wipe!)
        val reinstalledSiphon = SiphonItem(ip = "192.168.1.50", level = 10, percent = "5%", amount = 100, timeAgo = "1m ago")
        val reinstalledScreen = SiphonScreen(deployedCount = 1, items = listOf(reinstalledSiphon))

        SiphonTracker.processSiphonScreen(reinstalledScreen, fakeContext)

        val alerts = SiphonTracker.getSiphonAlerts()
        assertEquals(1, alerts.size)
        assertEquals("192.168.1.50", alerts[0])

        // 3. Clear alert
        SiphonTracker.removeSiphonAlert("192.168.1.50")
        assertEquals(0, SiphonTracker.getSiphonAlerts().size)
    }

    @Test
    fun testSiphonRemovalAlert() {
        val fakeContext = FakeContext()

        // Initial 1 siphon
        val initialSiphon = SiphonItem(ip = "10.0.0.99", level = 5, percent = "2%", amount = 500, timeAgo = "10m ago")
        SiphonTracker.processSiphonScreen(SiphonScreen(deployedCount = 1, items = listOf(initialSiphon)), fakeContext)

        // Now deployedCount = 0 (Siphon was completely wiped)
        SiphonTracker.processSiphonScreen(SiphonScreen(deployedCount = 0, items = emptyList()), fakeContext)

        val alerts = SiphonTracker.getSiphonAlerts()
        assertEquals(1, alerts.size)
        assertEquals("10.0.0.99", alerts[0])
    }
}
