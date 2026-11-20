package com.whisk.hackexlogger

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DarkIndexInstrumentationTest {

    @Test
    fun testAppContextAndPackageName() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.whisk.hackexlogger", appContext.packageName)
    }

    /**
     * Gson builds TargetRecord by reflection, which behaves differently on ART than on the JVM
     * (no constructor runs, so missing lists come back null). This checks that a stored record with
     * missing fields loads and is repaired by sanitizeRecord on a real device. Read-only: it never
     * touches the app's saved data.
     */
    @Test
    fun testStoredRecordLoadsOnDevice() {
        val json = """{"ip":"192.168.1.100","username":"TestUser","level":25,"wallets":["hx1234...5678"]}"""
        val record = TargetRepository.gson.fromJson(json, TargetRecord::class.java)
        TargetRepository.sanitizeRecord(record)

        assertEquals("192.168.1.100", record.ip)
        assertEquals(25, record.level)
        assertEquals(listOf("hx1234...5678"), record.wallets)
        assertEquals(0, record.downloads.size)     // was null in the JSON
        assertEquals(0, record.walletThefts.size)  // was null in the JSON
        assertNull(record.getEstimatedIncome("hx1234...5678"))
    }
}
