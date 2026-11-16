package com.whisk.hackexlogger

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeSharedPreferencesTest {

    @Test
    fun testListenerRegistrationAndCallbacks() {
        val prefs = FakeSharedPreferences()
        val changedKeys = mutableListOf<String>()

        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key != null) changedKeys.add(key)
        }

        prefs.registerOnSharedPreferenceChangeListener(listener)

        // Put value
        prefs.edit().putString("test_key", "value1").apply()
        assertEquals(listOf("test_key"), changedKeys)

        // Remove value
        changedKeys.clear()
        prefs.edit().remove("test_key").apply()
        assertEquals(listOf("test_key"), changedKeys)

        // Unregister listener
        changedKeys.clear()
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
        prefs.edit().putString("another_key", "value2").apply()
        assertTrue(changedKeys.isEmpty())
    }
}
