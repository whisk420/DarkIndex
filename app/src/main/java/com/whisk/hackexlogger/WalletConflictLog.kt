package com.whisk.hackexlogger

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * `filesDir/wallet_conflicts.jsonl`: one line per wallet the database refused, replaced or moved
 * (developer rule 2026-11-20: every wallet change is recorded).
 */
object WalletConflictLog {
    /** Observer for wallet conflicts. Null (no-op) unless Raw Dump Mode is recording a scrape. */
    @Volatile var listener: ((ip: String, existingWallet: String, conflictingWallet: String, source: String) -> Unit)? = null

    const val FILE_NAME = "wallet_conflicts.jsonl"

    fun log(ip: String, wallet1: String, wallet2: String, source: String) {
        listener?.invoke(ip, wallet1, wallet2, source)
        try {
            val context = TargetRepository.contextOrNull() ?: return
            val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
            val line = """{"timestamp":"$now","ip":"$ip","existing_wallet":"$wallet1","conflicting_wallet":"$wallet2","source":"$source"}"""
            File(context.filesDir, FILE_NAME).appendText("$line\n")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
