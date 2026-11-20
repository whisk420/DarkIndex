package com.whisk.hackexlogger

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson

data class PlayerProfile(
    val username: String? = null,
    val playerLevel: Int? = null,
    /** Store "NEXT DEVICE" (the upgrade on offer). Null once the device is maxed. */
    val nextDevice: String? = null,
    /** Store "NEXT NETWORK" (the upgrade on offer). Null once the network is maxed. */
    val nextNetwork: String? = null,
    val botnetStatus: String? = null,
    val softwareLevels: Map<String, Int> = emptyMap(),
    /** The device you own: the shown item when the Store says MAX TIER, otherwise one below NEXT. */
    val currentDevice: String? = null,
    /** The network you own: the shown item when the Store says MAX TIER, otherwise one below NEXT. */
    val currentNetwork: String? = null
)

object PlayerProfileRepository {
    private const val PREFS_NAME = "HackExPlayerProfile"
    private const val KEY_PROFILE = "player_profile"

    lateinit var prefs: SharedPreferences
    private val gson = Gson()

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** Last parsed profile and the JSON it came from. Every target card reads the profile several times, so this skips re-parsing unchanged JSON. */
    @Volatile private var parsedCache: Pair<String, PlayerProfile>? = null

    fun getProfile(): PlayerProfile {
        val json = prefs.getString(KEY_PROFILE, null) ?: return PlayerProfile()
        parsedCache?.let { (cachedJson, cachedProfile) -> if (cachedJson == json) return cachedProfile }
        val profile = try {
            gson.fromJson(json, PlayerProfile::class.java) ?: PlayerProfile()
        } catch (e: Exception) {
            PlayerProfile()
        }
        parsedCache = json to profile
        return profile
    }

    fun updateProfile(storeScreen: StoreScreen) {
        val current = getProfile()

        // Merge the newly parsed software levels with the existing ones
        val mergedSoftware = current.softwareLevels.toMutableMap()
        storeScreen.nextSoftwareLevels.forEach { (name, level) ->
            mergedSoftware[name] = level
        }

        val shownDevice = storeScreen.nextDevice
        val shownNetwork = storeScreen.nextNetwork

        val updated = current.copy(
            nextDevice = when {
                shownDevice == null -> current.nextDevice
                storeScreen.deviceMaxed -> null
                else -> shownDevice
            },
            currentDevice = when {
                shownDevice == null -> current.currentDevice
                storeScreen.deviceMaxed -> shownDevice
                else -> decrementHardwareName(shownDevice)
            },
            nextNetwork = when {
                shownNetwork == null -> current.nextNetwork
                storeScreen.networkMaxed -> null
                else -> shownNetwork
            },
            currentNetwork = when {
                shownNetwork == null -> current.currentNetwork
                storeScreen.networkMaxed -> shownNetwork
                else -> decrementHardwareName(shownNetwork)
            },
            botnetStatus = storeScreen.botnetStatus ?: current.botnetStatus,
            softwareLevels = mergedSoftware
        )

        prefs.edit().putString(KEY_PROFILE, gson.toJson(updated)).apply()
    }

    fun updateBypasserLevel(effectiveBypasserLevel: Int) {
        val current = getProfile()
        val mergedSoftware = current.softwareLevels.toMutableMap()
        mergedSoftware["Bypasser"] = effectiveBypasserLevel + 1 // Store format (+1 for next upgrade level)
        val updated = current.copy(softwareLevels = mergedSoftware)
        prefs.edit().putString(KEY_PROFILE, gson.toJson(updated)).apply()
    }

    fun updatePlayerLevel(level: Int) {
        val current = getProfile()
        val updated = current.copy(playerLevel = level)
        prefs.edit().putString(KEY_PROFILE, gson.toJson(updated)).apply()
    }

    fun updateOwnProfile(username: String?, level: Int?) {
        val current = getProfile()
        val updated = current.copy(
            username = username ?: current.username,
            playerLevel = level ?: current.playerLevel
        )
        prefs.edit().putString(KEY_PROFILE, gson.toJson(updated)).apply()
    }

    /** The network you own (for upload maths). Falls back to "one below NEXT" for profiles saved before currentNetwork existed. */
    fun getCurrentNetwork(): String? {
        val profile = getProfile()
        return profile.currentNetwork ?: decrementHardwareName(profile.nextNetwork)
    }

    /** The device you own. Falls back to "one below NEXT" for profiles saved before currentDevice existed. */
    fun getCurrentDevice(): String? {
        val profile = getProfile()
        return profile.currentDevice ?: decrementHardwareName(profile.nextDevice)
    }

    fun isApexDeviceOwned(): Boolean {
        val profile = getProfile()
        // Same behaviour as before (pending owner verification), but still works once the device is maxed and NEXT is gone.
        val dev = (profile.nextDevice ?: profile.currentDevice)?.lowercase() ?: return false
        return dev.contains("apex")
    }

    fun getEffectiveBypasserLevel(): Int {
        val profile = getProfile()
        val nextLevel = profile.softwareLevels["Bypasser"]
        val baseLevel = if (nextLevel != null) (nextLevel - 1).coerceAtLeast(1) else 1
        val apexBonus = if (isApexDeviceOwned()) 3 else 0
        return baseLevel + apexBonus
    }

    fun getEffectiveCrackerLevel(): Int {
        val profile = getProfile()
        val nextLevel = profile.softwareLevels["Password Cracker"]
        val baseLevel = if (nextLevel != null) (nextLevel - 1).coerceAtLeast(1) else 1
        val apexBonus = if (isApexDeviceOwned()) 3 else 0
        return baseLevel + apexBonus
    }

    fun getInstalledSpamLevel(): Int {
        val profile = getProfile()
        val nextLevel = profile.softwareLevels["Spam"] ?: return 0
        return (nextLevel - 1).coerceAtLeast(0)
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
