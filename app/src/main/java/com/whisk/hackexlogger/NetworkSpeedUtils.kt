package com.whisk.hackexlogger

object NetworkSpeedUtils {

    /**
     * Maps network string (standard name or prestige line) to its Speed Boost Multiplier.
     * Capped at 21.0x (Quantum Link / Prestige XII equivalent).
     */
    fun getNetworkSpeedBoost(networkStr: String?): Double {
        if (networkStr == null) return 1.0
        val net = networkStr.substringAfter(" • ").ifEmpty { networkStr }.trim().lowercase()

        // 1. Standard Networks
        if (net == "dsl") return 1.0
        if (net == "cable") return 1.5
        if (net == "cable+") return 2.25
        if (net == "4g lte" || net == "4g") return 3.5
        if (net == "5g") return 5.0
        if (net == "5g+") return 7.0
        if (net == "fiber") return 9.5
        if (net == "fiber+") return 12.0
        if (net == "10g fiber") return 15.0
        if (net == "10g fiber+") return 18.0
        if (net == "quantum link") return 21.0

        // 2. Prestige Networks (Suffix I - XII mapped to 1.0x - 21.0x)
        return when {
            net.endsWith(" xii") -> 21.0
            net.endsWith(" xi") -> 21.0
            net.endsWith(" x") -> 18.0
            net.endsWith(" ix") -> 15.0
            net.endsWith(" viii") -> 12.0
            net.endsWith(" vii") -> 9.5
            net.endsWith(" vi") -> 7.0
            net.endsWith(" v") -> 5.0
            net.endsWith(" iv") -> 3.5
            net.endsWith(" iii") -> 2.25
            net.endsWith(" ii") -> 1.5
            net.endsWith(" i") -> 1.0
            // Base prestige network line without numeral defaults to 1.0x
            net.contains("null route") || net.contains("dark routing") ||
            net.contains("backchannel") || net.contains("fast deploy") ||
            net.contains("deep link") -> 1.0
            else -> 1.0
        }
    }

    /**
     * Calculates the base 1.0x DSL upload duration in seconds for a target device hardware tier (0 to 9).
     * Based on empirical data: Tier 0 takes ~3019s against a 15.0x speed boost network.
     * Base DSL (1.0x) = Empirical Time * 15.0x
     */
    fun getBaseUploadSeconds(targetHardware: String?): Int {
        val tier = GoodSpamUtils.getHardwarePenalty(targetHardware)
        return when (tier) {
            0 -> 45285  // 3019 * 15.0  (~12.5 hours)
            1 -> 60390  // 4026 * 15.0  (~16.7 hours)
            2 -> 75480  // 5032 * 15.0  (~21.0 hours)
            3 -> 90585  // 6039 * 15.0  (~25.1 hours)
            4 -> 105720 // 7048 * 15.0  (~29.3 hours)
            5 -> 120825 // 8055 * 15.0  (~33.5 hours)
            6 -> 135930 // 9062 * 15.0  (~37.7 hours)
            7 -> 151035 // 10069 * 15.0 (~41.9 hours)
            8 -> 166140 // 11076 * 15.0 (~46.1 hours)
            9 -> 181245 // 12083 * 15.0 (~50.3 hours)
            else -> 90585
        }
    }

    /**
     * Calculates the estimated upload process duration in seconds given target device hardware
     * and player network speed boost.
     */
    fun getEstimatedUploadSeconds(targetHardware: String?, playerNetwork: String?): Int {
        val baseSeconds = getBaseUploadSeconds(targetHardware)
        val speedBoost = getNetworkSpeedBoost(playerNetwork)
        return (baseSeconds / speedBoost).toInt().coerceAtLeast(1)
    }

    /**
     * Calculates the estimated upload duration in seconds for a specific virus level (1, 5, 10, 20)
     * using polynomial scaling: (Level / 20)^2.25 * BaseLv20UploadSeconds.
     */
    fun getUploadSecondsForLevel(targetHardware: String?, playerNetwork: String?, virusLevel: Int): Int {
        val baseLv20Secs = getEstimatedUploadSeconds(targetHardware, playerNetwork)
        val levelRatio = (virusLevel.coerceAtLeast(1).toDouble() / 20.0)
        val scalingFactor = Math.pow(levelRatio, 2.25)
        return (baseLv20Secs * scalingFactor).toInt().coerceAtLeast(1)
    }

    /**
     * Formats upload duration in seconds to a human-readable display string (e.g. "6m 37s" or "1h 15m").
     */
    fun formatUploadDuration(seconds: Int): String {
        val minutes = seconds / 60
        val remainingSecs = seconds % 60
        return if (minutes >= 60) {
            val hours = minutes / 60
            val remMins = minutes % 60
            "${hours}h ${remMins}m"
        } else if (minutes > 0) {
            "${minutes}m ${remainingSecs}s"
        } else {
            "${seconds}s"
        }
    }
}
