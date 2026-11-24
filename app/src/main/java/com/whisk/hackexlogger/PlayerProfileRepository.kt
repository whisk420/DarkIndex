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

    /** False until [init] (or a test) sets [prefs]. */
    fun isReady(): Boolean = ::prefs.isInitialized

    /** Writes the profile and refreshes the Downloads backup, which now includes the profile. */
    private fun save(updated: PlayerProfile) {
        prefs.edit().putString(KEY_PROFILE, gson.toJson(updated)).apply()
        TargetRepository.requestBackupWrite()
    }

    /**
     * Restores a profile from an export/backup without overwriting anything newer: each field is only
     * filled in where the current profile has nothing (software levels: per app). Used by Import.
     */
    fun restoreFromBackup(imported: PlayerProfile) {
        val current = getProfile()
        @Suppress("USELESS_ELVIS", "UNNECESSARY_SAFE_CALL") // Gson can leave non-null fields null
        val importedSoftware: Map<String, Int> = imported.softwareLevels ?: emptyMap()
        val mergedSoftware = importedSoftware.toMutableMap().apply { putAll(current.softwareLevels) }
        val merged = current.copy(
            username = current.username ?: imported.username,
            playerLevel = current.playerLevel ?: imported.playerLevel,
            nextDevice = current.nextDevice ?: imported.nextDevice,
            nextNetwork = current.nextNetwork ?: imported.nextNetwork,
            botnetStatus = current.botnetStatus ?: imported.botnetStatus,
            softwareLevels = mergedSoftware,
            currentDevice = current.currentDevice ?: imported.currentDevice,
            currentNetwork = current.currentNetwork ?: imported.currentNetwork
        )
        if (merged != current) save(merged)
    }

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

        save(updated)
    }

    /**
     * Installed levels from your own Apps page. Stored as installed + 1, the Store's NEXT convention, so every
     * reader keeps subtracting 1. The newest scrape wins, Store or Apps (levels only drop after a reboot).
     */
    fun updateInstalledSoftware(installedLevels: Map<String, Int>) {
        if (installedLevels.isEmpty()) return
        val current = getProfile()
        val merged = current.softwareLevels.toMutableMap()
        installedLevels.forEach { (name, level) -> merged[name] = level + 1 }
        save(current.copy(softwareLevels = merged))
    }

    /** [installedBypasserLevel] = the Bypass screen's "Using Bypasser Lv.N", without any "(+N)" bonus. */
    fun updateBypasserLevel(installedBypasserLevel: Int) {
        val current = getProfile()
        val mergedSoftware = current.softwareLevels.toMutableMap()
        mergedSoftware["Bypasser"] = installedBypasserLevel + 1 // Store format (+1 for next upgrade level)
        val updated = current.copy(softwareLevels = mergedSoftware)
        save(updated)
    }

    fun updatePlayerLevel(level: Int) {
        val current = getProfile()
        val updated = current.copy(playerLevel = level)
        save(updated)
    }

    fun updateOwnProfile(username: String?, level: Int?) {
        val current = getProfile()
        val updated = current.copy(
            username = username ?: current.username,
            playerLevel = level ?: current.playerLevel
        )
        save(updated)
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

    /** True when the device you own is from the Apex prestige line (its perk: Bypasser/Cracker count +3). */
    fun isApexDeviceOwned(): Boolean =
        HardwareCatalog.device(getCurrentDevice())?.line == HardwareCatalog.Line.APEX

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

    /**
     * What you own when the Store offers [nextName] as NEXT: one level below, via [HardwareCatalog]
     * (standard and prestige devices and networks). Level 1 (`Raider`, `DSL`, `<Line> I`) stays the same.
     * Unknown names come back unchanged.
     */
    fun decrementHardwareName(nextName: String?): String? = HardwareCatalog.previous(nextName)
}
