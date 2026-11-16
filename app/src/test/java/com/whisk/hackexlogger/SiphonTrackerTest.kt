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

    @Test
    fun testEmptyItemsWithDeployedCountGreaterThanZero_EarlyExit() {
        val fakeContext = FakeContext()

        // Initial 2 siphons recorded
        val item1 = SiphonItem("192.168.1.1", 10, "5%", 1000, "1h ago")
        val item2 = SiphonItem("192.168.1.2", 10, "5%", 1000, "1h ago")
        SiphonTracker.processSiphonScreen(SiphonScreen(deployedCount = 2, items = listOf(item1, item2)), fakeContext)

        // Player scrapes header while scrolled to top above list (items empty, but deployedCount > 0)
        SiphonTracker.processSiphonScreen(SiphonScreen(deployedCount = 2, items = emptyList()), fakeContext)

        // No alerts should be triggered because early-exit protects against partial top header scrapes
        assertEquals(0, SiphonTracker.getSiphonAlerts().size)
    }

    @Test
    fun testViewportAccumulationAndRemoval() {
        val fakeContext = FakeContext()
        var mockTime = 1_000_000L
        SiphonTracker.currentTimeProvider = { mockTime }

        // Initial 3 siphons installed
        val ip1 = "10.0.0.1"
        val ip2 = "10.0.0.2"
        val ip3 = "10.0.0.3"

        val item1 = SiphonItem(ip1, 10, "5%", 1000, "1h ago")
        val item2 = SiphonItem(ip2, 10, "5%", 1000, "1h ago")
        val item3 = SiphonItem(ip3, 10, "5%", 1000, "1h ago")

        // First scrape captures all 3
        SiphonTracker.processSiphonScreen(SiphonScreen(deployedCount = 3, items = listOf(item1, item2, item3)), fakeContext)
        assertEquals(0, SiphonTracker.getSiphonAlerts().size)

        // 20 minutes pass... old recentlyScrapedSiphons entries expire
        mockTime += 20 * 60 * 1000L

        // Later, ip2 was wiped by Antivirus (game shows DEPLOYED 2). Player scrapes page 1 (visible: ip1)
        SiphonTracker.processSiphonScreen(SiphonScreen(deployedCount = 2, items = listOf(item1)), fakeContext)
        // recentlyScrapedSiphons has size 1 (< deployedCount 2), so removal is NOT triggered yet until full scroll
        assertEquals(0, SiphonTracker.getSiphonAlerts().size)

        // Player scrolls down to page 2 (visible: ip3). Now recentlyScrapedSiphons has ip1 + ip3 (size 2 >= deployedCount 2)
        SiphonTracker.processSiphonScreen(SiphonScreen(deployedCount = 2, items = listOf(item3)), fakeContext)

        // Now removal detection triggers because accumulated scraped siphons size (2) >= deployedCount (2).
        // ip2 was in knownSiphons but is missing from recentlyScrapedSiphons!
        val alerts = SiphonTracker.getSiphonAlerts()
        assertEquals(1, alerts.size)
        assertEquals(ip2, alerts[0])
    }

    @Test
    fun test15MinuteTTLEviction() {
        val fakeContext = FakeContext()
        var mockTime = 1_000_000L
        SiphonTracker.currentTimeProvider = { mockTime }

        val item1 = SiphonItem("192.168.1.1", 10, "5%", 1000, "1h ago")
        val item2 = SiphonItem("192.168.1.2", 10, "5%", 1000, "1h ago")

        // Initial scrape at T=1,000,000
        SiphonTracker.processSiphonScreen(SiphonScreen(deployedCount = 2, items = listOf(item1, item2)), fakeContext)
        assertEquals(0, SiphonTracker.getSiphonAlerts().size)

        // Advance mock time by 16 minutes (>15 minute TTL)
        mockTime += 16 * 60 * 1000L

        // Scrape page with only item1 and deployedCount = 2
        SiphonTracker.processSiphonScreen(SiphonScreen(deployedCount = 2, items = listOf(item1)), fakeContext)

        // item2 was evicted from recentlyScrapedSiphons due to TTL expiry (>15 min),
        // so recentlyScrapedSiphons size is now only 1 (item1), which is < deployedCount (2).
        // Therefore, removal alert is not triggered prematurely until full viewport scroll completes.
        assertEquals(0, SiphonTracker.getSiphonAlerts().size)
    }
}
