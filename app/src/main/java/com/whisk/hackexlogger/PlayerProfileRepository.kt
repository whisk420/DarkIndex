package com.whisk.hackexlogger

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson

data class PlayerProfile(
    val nextDevice: String? = null,
    val nextNetwork: String? = null,
    val botnetStatus: String? = null,
    val softwareLevels: Map<String, Int> = emptyMap()
)

object PlayerProfileRepository {
    private const val PREFS_NAME = "HackExPlayerProfile"
    private const val KEY_PROFILE = "player_profile"

    private lateinit var prefs: SharedPreferences
    private val gson = Gson()

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getProfile(): PlayerProfile {
        val json = prefs.getString(KEY_PROFILE, null) ?: return PlayerProfile()
        return try {
            gson.fromJson(json, PlayerProfile::class.java) ?: PlayerProfile()
        } catch (e: Exception) {
            PlayerProfile()
        }
    }

    fun updateProfile(storeScreen: StoreScreen) {
        val current = getProfile()

        // Merge the newly parsed software levels with the existing ones
        val mergedSoftware = current.softwareLevels.toMutableMap()
        storeScreen.nextSoftwareLevels.forEach { (name, level) ->
            mergedSoftware[name] = level
        }

        val updated = current.copy(
            nextDevice = storeScreen.nextDevice ?: current.nextDevice,
            nextNetwork = storeScreen.nextNetwork ?: current.nextNetwork,
            botnetStatus = storeScreen.botnetStatus ?: current.botnetStatus,
            softwareLevels = mergedSoftware
        )

        prefs.edit().putString(KEY_PROFILE, gson.toJson(updated)).apply()
    }

    fun decrementHardwareName(nextName: String?): String? {
        if (nextName == null) return null
        val trimmed = nextName.trim()

        // 1. Check Standard Devices list
        val standardDevices = listOf(
            "Raider", "Raider II", "Raider III",
            "Bolt", "Bolt II", "Bolt III",
            "Nova", "Nova II", "Nova III",
            "Nova S", "Nova X", "Nova Ultra"
        )
        val stdIndex = standardDevices.indexOfFirst { it.equals(trimmed, ignoreCase = true) }
        if (stdIndex > 0) {
            return standardDevices[stdIndex - 1]
        } else if (stdIndex == 0) {
            return "Raider" // Lowest standard device
        }

        // 2. Check Roman Numeral Suffix (e.g., "Apex VII", "Null Route X", "Ghost IV")
        val romanNumerals = listOf(
            "XII" to "XI",
            "XI" to "X",
            "X" to "IX",
            "IX" to "VIII",
            "VIII" to "VII",
            "VII" to "VI",
            "VI" to "V",
            "V" to "IV",
            "IV" to "III",
            "III" to "II",
            "II" to "I",
            "I" to ""
        )

        for ((currentRoman, prevRoman) in romanNumerals) {
            if (trimmed.endsWith(" $currentRoman", ignoreCase = true)) {
                val baseName = trimmed.substring(0, trimmed.length - currentRoman.length).trim()
                return if (prevRoman.isEmpty()) {
                    if (baseName.equals("Null Route", ignoreCase = true)) {
                        "Base Network"
                    } else {
                        "Nova Ultra" // Tier I prestige comes after Nova Ultra
                    }
                } else {
                    "$baseName $prevRoman"
                }
            }
        }

        return trimmed
    }
}
