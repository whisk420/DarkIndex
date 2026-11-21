package com.whisk.hackexlogger

/**
 * Single source of truth for device and network names, standard and prestige
 * (plan: docs/plans/prestige-hardware.md, owner answers 2026-11-19).
 *
 * - Standard devices: 12 levels, Raider (1) … Nova Ultra (12). Standard networks: 11 levels, DSL (1) … Quantum Link (11).
 * - Prestige ("Reboot Protocol") pieces are `<Line> <Roman>` and the numeral is the level, with the same
 *   speed as the standard item at that level. Device lines go to XII, network lines to XI.
 *   A reboot starts at `<Line> I`. A bare line name (seen once in old data, e.g. `Vector`) counts as level 1.
 * - R1–R5 use one loadout (device + its network); R6+ can mix any device line with any network line,
 *   so a device and a network are parsed independently.
 * - Names are matched case-insensitively and the canonical game spelling is returned.
 */
object HardwareCatalog {

    enum class Kind { DEVICE, NETWORK }

    /** The five prestige loadouts: device line + network line. */
    enum class Line(val deviceName: String, val networkName: String) {
        GHOST("Ghost", "Ghost Link"),
        VECTOR("Vector", "Contra Band"),
        SCYTHE("Scythe", "Harvest Node"),
        CIPHER("Cipher", "Void Net"),
        APEX("Apex", "Null Route");

        fun nameFor(kind: Kind): String = if (kind == Kind.DEVICE) deviceName else networkName
    }

    /** A recognised piece of hardware. [line] is null for standard (non-prestige) hardware. */
    data class Hardware(val kind: Kind, val level: Int, val line: Line?, val name: String) {
        val isPrestige: Boolean get() = line != null
    }

    val STANDARD_DEVICES = listOf(
        "Raider", "Raider II", "Raider III",
        "Bolt", "Bolt II", "Bolt III",
        "Nova", "Nova II", "Nova III",
        "Nova S", "Nova X", "Nova Ultra"
    )

    val STANDARD_NETWORKS = listOf(
        "DSL", "Cable", "Cable+", "4G LTE", "5G", "5G+",
        "Fiber", "Fiber+", "10G Fiber", "10G Fiber+", "Quantum Link"
    )

    /** Network speed multiplier by level (1-based), from hackex.net/guide. */
    private val NETWORK_BOOST = listOf(1.0, 1.5, 2.25, 3.5, 5.0, 7.0, 9.5, 12.0, 15.0, 18.0, 21.0)

    const val MAX_DEVICE_LEVEL = 12
    const val MAX_NETWORK_LEVEL = 11

    private val NETWORK_ALIASES = mapOf("4g" to "4G LTE")

    private val ROMAN = listOf("I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X", "XI", "XII")

    private fun maxLevel(kind: Kind) = if (kind == Kind.DEVICE) MAX_DEVICE_LEVEL else MAX_NETWORK_LEVEL

    /**
     * Parses a device or network name, e.g. `Nova S`, `5G+`, `Apex VII`, `Harvest Node X`.
     * Also accepts a target's combined `"<Device> • <Network>"` string only through [device]/[network].
     * Returns null for names we don't know.
     */
    fun parse(name: String?): Hardware? {
        val trimmed = name?.trim()?.replace(Regex("\\s+"), " ")?.takeIf { it.isNotEmpty() } ?: return null
        return parseKind(trimmed, Kind.DEVICE) ?: parseKind(trimmed, Kind.NETWORK)
    }

    /** The device half of a target's `"<Device> • <Network>"` string (or a bare device name). */
    fun device(hardware: String?): Hardware? {
        val part = hardware?.substringBefore("•")?.trim() ?: return null
        return parseKind(part.replace(Regex("\\s+"), " "), Kind.DEVICE)
    }

    /** The network half of a target's `"<Device> • <Network>"` string (or a bare network name). */
    fun network(hardware: String?): Hardware? {
        if (hardware == null) return null
        val part = (if (hardware.contains("•")) hardware.substringAfter("•") else hardware).trim()
        return parseKind(part.replace(Regex("\\s+"), " "), Kind.NETWORK)
    }

    private fun parseKind(name: String, kind: Kind): Hardware? {
        if (name.isEmpty()) return null
        val standard = if (kind == Kind.DEVICE) STANDARD_DEVICES else STANDARD_NETWORKS

        val alias = if (kind == Kind.NETWORK) NETWORK_ALIASES[name.lowercase()] else null
        val stdIndex = standard.indexOfFirst { it.equals(alias ?: name, ignoreCase = true) }
        if (stdIndex >= 0) return Hardware(kind, stdIndex + 1, null, standard[stdIndex])

        for (line in Line.values()) {
            val lineName = line.nameFor(kind)
            if (name.equals(lineName, ignoreCase = true)) {
                return Hardware(kind, 1, line, "$lineName I")
            }
            if (name.length > lineName.length && name.startsWith("$lineName ", ignoreCase = true)) {
                val numeral = name.substring(lineName.length + 1).trim().uppercase()
                val level = ROMAN.indexOf(numeral) + 1
                if (level in 1..maxLevel(kind)) return Hardware(kind, level, line, "$lineName ${ROMAN[level - 1]}")
            }
        }
        return null
    }

    private val reportedUnknown = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** Unknown names seen so far (so new hardware can be added to the catalogue). */
    fun unknownNamesSeen(): Set<String> = synchronized(reportedUnknown) { reportedUnknown.toSet() }

    /**
     * Records a hardware name the catalogue doesn't know (logged once per name) and returns the marker to
     * show next to it on a card. Unknown devices are treated as tier 3 and unknown networks as 1.0× (DSL).
     */
    fun noteUnknown(name: String): String {
        val key = name.trim()
        if (key.isNotEmpty() && reportedUnknown.add(key)) {
            try { android.util.Log.w("HardwareCatalog", "Unknown hardware name: \"$key\" (using fallback values)") } catch (_: Throwable) {}
        }
        return " ?"
    }

    /** Canonical name for a level of a line (null line = standard). Null if the level doesn't exist. */
    fun nameFor(kind: Kind, line: Line?, level: Int): String? {
        if (level !in 1..maxLevel(kind)) return null
        if (line == null) return (if (kind == Kind.DEVICE) STANDARD_DEVICES else STANDARD_NETWORKS)[level - 1]
        return "${line.nameFor(kind)} ${ROMAN[level - 1]}"
    }

    /**
     * The hardware one level below [name], i.e. what you own when the Store offers [name] as NEXT.
     * Level 1 stays the same (a reboot starts at `<Line> I`, so nothing sits below it; and below Raider/DSL
     * there is nothing). Unknown names come back unchanged.
     */
    fun previous(name: String?): String? {
        if (name == null) return null
        val hw = parse(name) ?: return name.trim()
        if (hw.level <= 1) return hw.name
        return nameFor(hw.kind, hw.line, hw.level - 1)
    }

    /** Network speed multiplier (1.0 DSL … 21.0 Quantum Link / `<Line> XI`). Null for unknown names. */
    fun networkBoost(network: String?): Double? {
        val hw = network(network) ?: return null
        return NETWORK_BOOST[hw.level.coerceAtMost(MAX_NETWORK_LEVEL) - 1]
    }

    /**
     * How hard a target's device is to upload to, as tier 0–9: Raider I–III (levels 1–3) upload identically
     * (owner-verified), then one tier per level up to Nova Ultra / `<Line> XII`. Null for unknown devices.
     */
    fun uploadDefenceTier(hardware: String?): Int? {
        val hw = device(hardware) ?: return null
        return (hw.level - 3).coerceAtLeast(0)
    }
}
