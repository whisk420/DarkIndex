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
    private const val KEY_KNOWN_SIPHON_STARTS = "known_siphon_starts"

    private val knownSiphons = mutableMapOf<String, Int>()
    private val knownSiphonStartTimes = mutableMapOf<String, Long>()
    private val siphonAlerts = mutableSetOf<String>()
    private val accumulatedSiphons = mutableSetOf<String>()

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

        try {
            val startsJson = prefs.getString(KEY_KNOWN_SIPHON_STARTS, "{}")
            val startsType = object : TypeToken<Map<String, Long>>() {}.type
            val loadedStarts: Map<String, Long>? = gson.fromJson(startsJson, startsType)
            if (loadedStarts != null) {
                knownSiphonStartTimes.clear()
                knownSiphonStartTimes.putAll(loadedStarts)
            }
        } catch (e: Exception) {
            android.util.Log.e("SiphonTracker", "Error loading siphon start times: ${e.message}", e)
        }
    }

    private fun saveSiphonData() {
        TargetRepository.prefs.edit()
            .putString(KEY_SIPHON_ALERTS, TargetRepository.gson.toJson(siphonAlerts))
            .putString(KEY_KNOWN_SIPHONS, TargetRepository.gson.toJson(knownSiphons))
            .putString(KEY_KNOWN_SIPHON_STARTS, TargetRepository.gson.toJson(knownSiphonStartTimes))
            .apply()
    }

    var currentTimeProvider: () -> Long = { System.currentTimeMillis() }

    fun getSiphonAlerts(): List<String> {
        return siphonAlerts.toList()
    }

    fun getKnownSiphons(): Map<String, Int> {
        return knownSiphons.toMap()
    }

    fun restoreSiphonData(alerts: List<String>?, siphons: Map<String, Int>?) {
        if (alerts != null) {
            siphonAlerts.clear()
            siphonAlerts.addAll(alerts)
        }
        if (siphons != null) {
            knownSiphons.clear()
            knownSiphons.putAll(siphons)
        }
        saveSiphonData()
    }

    fun removeSiphonAlert(ip: String) {
        siphonAlerts.remove(ip)
        saveSiphonData()
    }

    fun clearSiphonTracking() {
        knownSiphons.clear()
        knownSiphonStartTimes.clear()
        siphonAlerts.clear()
        accumulatedSiphons.clear()
        currentTimeProvider = { System.currentTimeMillis() }
        saveSiphonData()
    }

    fun processSiphonScreen(siphonScreen: SiphonScreen, context: Context) {
        if (siphonScreen.items.isEmpty() && siphonScreen.deployedCount > 0) return

        val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val nowStr = isoFormat.format(Date())
        val exportFile = File(context.filesDir, "siphon_history.jsonl")

        val currentTime = currentTimeProvider()

        for (item in siphonScreen.items) {
            accumulatedSiphons.add(item.ip)
            // If the user goes back and scans their list, remove anything that was falsely alerted
            if (siphonAlerts.contains(item.ip)) {
                siphonAlerts.remove(item.ip)
            }
        }

        if (siphonScreen.deployedCount == 0 || accumulatedSiphons.size >= siphonScreen.deployedCount) {
            val missingIps = knownSiphons.keys - accumulatedSiphons
            for (missingIp in missingIps) {
                val amount = knownSiphons[missingIp] ?: 0
                val startTime = knownSiphonStartTimes[missingIp]
                val installedForMs = if (startTime != null) currentTime - startTime else 0L
                val isCrapInstall = installedForMs > 24L * 60 * 60 * 1000 && amount < 1000

                if (!isCrapInstall) {
                    siphonAlerts.add(missingIp)
                }
                
                knownSiphons.remove(missingIp)
                knownSiphonStartTimes.remove(missingIp)
                val logLine = """{"timestamp":"$nowStr","ip":"$missingIp","event":"REMOVED"}"""
                exportFile.appendText("$logLine\n")
            }
            accumulatedSiphons.clear()
        }

        for (item in siphonScreen.items) {
            val previousAmount = knownSiphons[item.ip]

            if (previousAmount != null && item.amount < previousAmount) {
                siphonAlerts.add(item.ip)
                val logLine = """{"timestamp":"$nowStr","ip":"${item.ip}","event":"REINSTALLED","old_amount":$previousAmount,"new_amount":${item.amount}}"""
                exportFile.appendText("$logLine\n")
            } else if (previousAmount == null) {
                knownSiphonStartTimes[item.ip] = currentTime
                val logLine = """{"timestamp":"$nowStr","ip":"${item.ip}","event":"INSTALLED","amount":${item.amount},"timeAgo":"${item.timeAgo}"}"""
                exportFile.appendText("$logLine\n")
            }
            knownSiphons[item.ip] = item.amount
        }
        saveSiphonData()
    }
}