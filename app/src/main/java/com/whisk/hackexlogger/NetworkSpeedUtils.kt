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
     */
    fun getBaseUploadSeconds(targetHardware: String?): Int {
        val tier = GoodSpamUtils.getHardwarePenalty(targetHardware)
        return when (tier) {
            0 -> 3774  // ~1h 02m
            1 -> 5033  // ~1h 23m
            2 -> 6290  // ~1h 44m
            3 -> 7549  // ~2h 05m
            4 -> 8810  // ~2h 26m
            5 -> 10069 // ~2h 47m
            6 -> 11328 // ~3h 08m
            7 -> 12586 // ~3h 29m
            8 -> 13845 // ~3h 50m
            9 -> 15104 // ~4h 11m
            else -> 7549
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
