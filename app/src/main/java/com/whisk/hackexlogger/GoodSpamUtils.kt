package com.whisk.hackexlogger

object GoodSpamUtils {
    /**
     * Extracts the target's device name and maps it directly to deterministic empirical hardware tiers
     * which dictate upload difficulty penalties (0 to 9).
     */
    fun getHardwarePenalty(hardware: String?): Int {
        if (hardware == null) return 0
        val deviceStr = hardware.substringBefore(" • ").trim().lowercase()

        // 1. Check exact Standard Devices (the 12 base tiers)
        if (deviceStr.startsWith("raider")) return 0
        if (deviceStr == "bolt") return 1
        if (deviceStr == "bolt ii") return 2
        if (deviceStr == "bolt iii") return 3
        if (deviceStr == "nova") return 4
        if (deviceStr == "nova ii") return 5
        if (deviceStr == "nova iii") return 6
        if (deviceStr == "nova s") return 7
        if (deviceStr == "nova x") return 8
        if (deviceStr == "nova ultra") return 9

        // 2. Check Prestige Devices (Ghost, Vector, Scythe, Cipher, Apex)
        // Their Roman numeral suffix maps exactly 1-to-1 with the 12 standard devices.
        return when {
            deviceStr.endsWith(" xii") -> 9 // Nova Ultra equivalent
            deviceStr.endsWith(" xi") -> 8  // Nova X equivalent
            deviceStr.endsWith(" x") -> 7   // Nova S equivalent
            deviceStr.endsWith(" ix") -> 6  // Nova III equivalent
            deviceStr.endsWith(" viii") -> 5 // Nova II equivalent
            deviceStr.endsWith(" vii") -> 4 // Nova equivalent
            deviceStr.endsWith(" vi") -> 3  // Bolt III equivalent
            deviceStr.endsWith(" v") -> 2   // Bolt II equivalent
            deviceStr.endsWith(" iv") -> 1  // Bolt equivalent
            deviceStr.endsWith(" iii") || deviceStr.endsWith(" ii") || deviceStr.endsWith(" i") -> 0 // Raider tier
            // Fallback for base prestige devices without a numeral
            deviceStr.contains("ghost") || deviceStr.contains("vector") || deviceStr.contains("scythe") ||
            deviceStr.contains("cipher") || deviceStr.contains("apex") -> 0
            else -> 3 // Robust default for unknown strings
        }
    }

    /**
     * Calculates the Good Spam priority score for a target.
     * Incorporates target level, estimated upload minutes (using player network speed boost), and firewall level.
     * Formula: (Level * 10.0) - (UploadMinutes * 0.5) - (Firewall * 1.0)
     */
    fun calculateScore(level: Int?, firewall: Int?, hardware: String?, playerNetwork: String? = null): Double {
        val lvl = (level ?: 0).toDouble()
        val fw = (firewall ?: 0).toDouble()
        val uploadSecs = NetworkSpeedUtils.getEstimatedUploadSeconds(hardware, playerNetwork)
        val uploadMins = uploadSecs / 60.0
        return (lvl * 10.0) - (uploadMins * 0.5) - fw
    }

    /**
     * Checks if a target's firewall level is within the maxFirewallLevel threshold.
     * maxFirewallLevel == 31 means "Uncapped" (allows any firewall level).
     */
    fun isEligible(firewall: Int?, maxFirewallLevel: Int): Boolean {
        val fw = firewall ?: 0
        val maxFw = if (maxFirewallLevel == 31) Int.MAX_VALUE else maxFirewallLevel
        return fw <= maxFw
    }
}
