package com.whisk.hackexlogger

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DarkIndexInstrumentationTest {

    @Test
    fun testAppContextAndPackageName() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.whisk.hackexlogger", appContext.packageName)
    }

    @Test
    fun testTargetRecordModelOnDevice() {
        val record = TargetRecord(
            ip = "192.168.1.100",
            username = "TestUser",
            level = 25,
            firewall = 10,
            hardware = "Nova • Fiber",
            wallets = mutableListOf("12345678901234567890")
        )
        assertEquals("192.168.1.100", record.ip)
        assertEquals("TestUser", record.username)
        assertEquals(25, record.level)
        val income = record.getEstimatedIncome("12345678901234567890")
        assertEquals(null, income)
    }
}
