package com.whisk.hackexlogger

import android.content.Context
import com.google.gson.reflect.TypeToken
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object SiphonTracker {
    private const val KEY_SIPHON_ALERTS = "siphon_alerts"
    private const val KEY_KNOWN_SIPHONS = "known_siphons"

    private val knownSiphons = mutableMapOf<String, Int>()
    private val siphonAlerts = mutableSetOf<String>()
    private val recentlyScrapedSiphons = mutableMapOf<String, Long>()

    fun init() {
        val prefs = TargetRepository.prefs
        val gson = TargetRepository.gson
        try {
            val alertsJson = prefs.getString(KEY_SIPHON_ALERTS, "[]")
            val loadedAlertsArray: Array<String>? = gson.fromJson(alertsJson, Array<String>::class.java)
            if (loadedAlertsArray != null) {
                siphonAlerts.clear()
                siphonAlerts.addAll(loadedAlertsArray)
            }
        } catch (e: Exception) {
            android.util.Log.e("SiphonTracker", "Error loading siphon alerts cache: ${e.message}", e)
        }

        try {
            val siphonsJson = prefs.getString(KEY_KNOWN_SIPHONS, "{}")
            val siphonsType = object : TypeToken<Map<String, Int>>() {}.type
            val loadedSiphons: Map<String, Int>? = gson.fromJson(siphonsJson, siphonsType)
            if (loadedSiphons != null) {
                knownSiphons.clear()
                knownSiphons.putAll(loadedSiphons)
            }
        } catch (e: Exception) {
            android.util.Log.e("SiphonTracker", "Error loading known siphons cache: ${e.message}", e)
        }
    }

    private fun saveSiphonData() {
        TargetRepository.prefs.edit()
            .putString(KEY_SIPHON_ALERTS, TargetRepository.gson.toJson(siphonAlerts))
            .putString(KEY_KNOWN_SIPHONS, TargetRepository.gson.toJson(knownSiphons))
            .apply()
    }

    fun getSiphonAlerts(): List<String> {
        return siphonAlerts.toList()
    }

    fun removeSiphonAlert(ip: String) {
        siphonAlerts.remove(ip)
        saveSiphonData()
    }

    fun clearSiphonTracking() {
        knownSiphons.clear()
        siphonAlerts.clear()
        recentlyScrapedSiphons.clear()
        saveSiphonData()
    }

    fun processSiphonScreen(siphonScreen: SiphonScreen, context: Context) {
        if (siphonScreen.items.isEmpty() && siphonScreen.deployedCount > 0) return

        val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val nowStr = isoFormat.format(Date())
        val exportFile = File(context.filesDir, "siphon_history.jsonl")

        val currentTime = System.currentTimeMillis()
        val iterator = recentlyScrapedSiphons.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (currentTime - entry.value > 15 * 60 * 1000) {
                iterator.remove()
            }
        }

        for (item in siphonScreen.items) {
            recentlyScrapedSiphons[item.ip] = currentTime
        }

        if (siphonScreen.deployedCount == 0) {
            val missingIps = knownSiphons.keys.toList()
            for (missingIp in missingIps) {
                siphonAlerts.add(missingIp)
                knownSiphons.remove(missingIp)
                val logLine = """{"timestamp":"$nowStr","ip":"$missingIp","event":"REMOVED"}"""
                exportFile.appendText("$logLine\n")
            }
        } else if (recentlyScrapedSiphons.size >= siphonScreen.deployedCount) {
            val missingIps = knownSiphons.keys - recentlyScrapedSiphons.keys
            for (missingIp in missingIps) {
                siphonAlerts.add(missingIp)
                knownSiphons.remove(missingIp)
                val logLine = """{"timestamp":"$nowStr","ip":"$missingIp","event":"REMOVED"}"""
                exportFile.appendText("$logLine\n")
            }
        }

        for (item in siphonScreen.items) {
            val previousAmount = knownSiphons[item.ip]

            if (previousAmount != null && item.amount < previousAmount) {
                siphonAlerts.add(item.ip)
                val logLine = """{"timestamp":"$nowStr","ip":"${item.ip}","event":"REINSTALLED","old_amount":$previousAmount,"new_amount":${item.amount}}"""
                exportFile.appendText("$logLine\n")
            } else if (previousAmount == null) {
                val logLine = """{"timestamp":"$nowStr","ip":"${item.ip}","event":"INSTALLED","amount":${item.amount},"timeAgo":"${item.timeAgo}"}"""
                exportFile.appendText("$logLine\n")
            }
            knownSiphons[item.ip] = item.amount
        }
        saveSiphonData()
    }
}