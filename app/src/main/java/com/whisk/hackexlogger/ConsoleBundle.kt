package com.whisk.hackexlogger

/**
 * Portable JSON data bundle container for exporting and importing target intelligence records.
 *
 * @property format Identification tag for schema validation ("target-intelligence-console").
 * @property version Data bundle schema version.
 * @property exportedAt ISO-8601 UTC timestamp of export.
 * @property targets List of exported target records.
 * @property ingests Optional list of archived raw log entries or screen captures.
 * @property siphonAlerts Archived IPs that triggered a siphon removal or drop alert.
 * @property knownSiphons Archived map of IPs to their last known siphon amounts.
 * @property playerProfile Your own profile (level, hardware, software levels). Added 2026-11-20; optional, so
 *   older exports (without it) still import, and older app versions simply ignore it.
 */
data class ConsoleBundle(
    val format: String = "target-intelligence-console",
    val version: Int = 1,
    val exportedAt: String? = null,
    val targets: Array<TargetRecord>? = null,
    val ingests: Array<ConsoleIngest>? = null,
    val siphonAlerts: Array<String>? = null,
    val knownSiphons: Map<String, Int>? = null,
    val playerProfile: PlayerProfile? = null,
    /** True for exports whose theft times are UTC (2026-11-20 and later). Older exports are converted on import. */
    val theftTimesUtc: Boolean? = null
)

/**
 * Raw data ingest element stored within an export bundle.
 */
data class ConsoleIngest(
    val type: String? = null,
    val raw: String? = null,
    val importedAt: String? = null,
    val id: Long? = null
)

/**
 * Result metrics returned after processing a JSON data bundle import.
 *
 * @property targetCount Number of targets imported or updated.
 * @property ingestCount Number of raw ingest blocks processed.
 */
data class ImportResult(
    val targetCount: Int,
    val ingestCount: Int
)
