package com.whisk.hackexlogger

import com.google.gson.GsonBuilder
import java.io.File
import java.util.Locale

/**
 * Marathon Mode: a guided income measurement.
 *
 * 1. **Timed pass** (5/10/20 min): hack and steal from as many targets as possible.
 * 2. Time's up (or "End early"): grab your own LOG. Logs may be grabbed any number of times during the
 *    run; every own-log scrape adds its steals to this run, so wiping or losing the log doesn't matter.
 * 3. Pass 2 (untimed): start again at the run's **first** target and steal from each one again.
 * 4. Finish → grab the LOG → results: every wallet ranked by income, using the same maths as the
 *    income estimate on the cards (amount stolen after the first steal ÷ time between them).
 * 5. Dismiss → chain another run (told where the last run ended) or end.
 *
 * Pure Kotlin (no Android types) so it is JVM-testable. The overlay drives it and listens for changes.
 * State is in memory only: a marathon does not survive a restart (developer, 2026-11-21).
 */
object Marathon {

    enum class Phase {
        IDLE,
        /** Timed pass running. */
        TIMED,
        /** Timer ended: waiting for an own-log scrape. */
        AWAITING_LOG,
        /** Log captured after the timer: ask the user to start pass 2. */
        READY_PASS2,
        /** Untimed second pass. */
        PASS2,
        /** Finish pressed: waiting for the final own-log scrape. */
        AWAITING_FINAL_LOG,
        /** Results panel is showing. */
        RESULTS,
        /** Results dismissed: chain another run or end. */
        CHAIN_PROMPT
    }

    val DURATIONS_MINUTES = listOf(5, 10, 20)

    /** One own-log steal seen during the run. [order] breaks ties between steals logged in the same minute. */
    data class Steal(val wallet: String, val amount: Int, val timeMs: Long, val ip: String?, val order: Long)

    /** Who a wallet belongs to, as far as the log and the database know. */
    data class Target(val ip: String?, val username: String?, val wallet: String) {
        /** IP if known, else username, else the wallet. */
        val label: String get() = ip ?: username ?: wallet
    }

    data class Row(
        val target: Target,
        val steals: Int,
        val firstAmount: Int,
        /** Everything stolen after the first steal (what refilled). */
        val refillAmount: Int,
        /** Minutes between the first and the last steal (log times are whole minutes). */
        val spanMinutes: Long,
        /** Crypto per hour, or null with fewer than two steals or no time between them. */
        val ratePerHour: Int?
    )

    data class Run(
        val chainId: Long,
        val runNumber: Int,
        val durationMinutes: Int,
        val startedAtMs: Long,
        val timerEndedAtMs: Long?,
        val endedEarly: Boolean,
        val finishedAtMs: Long,
        /** Where the previous run in the chain ended (null on the first run). */
        val startHint: Target?,
        val firstTarget: Target?,
        val lastTarget: Target?,
        val rows: List<Row>
    )

    // ---- state (guarded by `lock`) ----
    private val lock = Any()
    @Volatile var phase: Phase = Phase.IDLE; private set
    var durationMinutes: Int = 0; private set
    var startedAtMs: Long = 0; private set
    var deadlineMs: Long = 0; private set
    var timerEndedAtMs: Long? = null; private set
    var endedEarly: Boolean = false; private set
    var runNumber: Int = 0; private set
    private var chainId: Long = 0
    /** Where the previous run ended; shown at the start of a chained run. */
    var startHint: Target? = null; private set
    var lastRun: Run? = null; private set
    private val steals = LinkedHashMap<String, Steal>()
    /** Steals that belong to the previous run in the chain, so the next run never counts them again. */
    private val previousKeys = HashSet<String>()
    private var orderCounter = 0L

    /** Called after every state change, on whatever thread caused it. */
    @Volatile var listener: (() -> Unit)? = null

    /** Wallet → database record. Overridable for tests. */
    @Volatile var ownerLookup: (String) -> TargetRecord? = ::lookupOwnerInDatabase

    private fun lookupOwnerInDatabase(wallet: String): TargetRecord? =
        TargetRepository.getAllRecords()
            .filter { it.wallets.contains(wallet) }
            .minByOrNull { if (it.isMasked || it.isTemporary) 1 else 0 }

    /** Where finished runs are appended (`marathon_history.jsonl`). Null = don't save. Overridable for tests. */
    @Volatile var historyFileProvider: () -> File? = {
        try { File(TargetRepository.appContext.filesDir, HISTORY_FILE) } catch (e: Exception) { null }
    }
    const val HISTORY_FILE = "marathon_history.jsonl"

    val isActive: Boolean get() = phase != Phase.IDLE

    val stealCount: Int get() = synchronized(lock) { steals.size }

    // ---- transitions ----

    /** Starts a new chain with a timed pass. */
    fun start(minutes: Int, nowMs: Long) {
        synchronized(lock) {
            chainId = nowMs
            runNumber = 0
            startHint = null
            previousKeys.clear()
            beginRun(minutes, nowMs)
        }
        notifyChanged()
    }

    /** Starts the next run of the chain, telling the user where the last run ended. */
    fun chain(minutes: Int, nowMs: Long) {
        synchronized(lock) {
            if (phase != Phase.CHAIN_PROMPT && phase != Phase.RESULTS) return
            startHint = lastRun?.lastTarget
            previousKeys.addAll(steals.keys)
            beginRun(minutes, nowMs)
        }
        notifyChanged()
    }

    private fun beginRun(minutes: Int, nowMs: Long) {
        runNumber += 1
        durationMinutes = minutes
        startedAtMs = nowMs
        deadlineMs = nowMs + minutes * 60_000L
        timerEndedAtMs = null
        endedEarly = false
        steals.clear()
        phase = Phase.TIMED
    }

    /** Remaining time of the timed pass (0 once it has ended). */
    fun remainingMs(nowMs: Long): Long = if (phase == Phase.TIMED) (deadlineMs - nowMs).coerceAtLeast(0) else 0

    /** Ends the timed pass when the deadline has passed. Returns true if this call ended it. */
    fun checkTimer(nowMs: Long): Boolean {
        synchronized(lock) {
            if (phase != Phase.TIMED || nowMs < deadlineMs) return false
            timerEndedAtMs = deadlineMs
            phase = Phase.AWAITING_LOG
        }
        notifyChanged()
        return true
    }

    /** "End early": stop the timer now and go straight to the log grab, as if time had run out. */
    fun endEarly(nowMs: Long) {
        synchronized(lock) {
            if (phase != Phase.TIMED) return
            timerEndedAtMs = nowMs
            endedEarly = true
            phase = Phase.AWAITING_LOG
        }
        notifyChanged()
    }

    fun startPass2() {
        synchronized(lock) {
            if (phase != Phase.READY_PASS2) return
            phase = Phase.PASS2
        }
        notifyChanged()
    }

    fun finish() {
        synchronized(lock) {
            if (phase != Phase.PASS2) return
            phase = Phase.AWAITING_FINAL_LOG
        }
        notifyChanged()
    }

    /** Undo an accidental Finish. */
    fun backToPass2() {
        synchronized(lock) {
            if (phase != Phase.AWAITING_FINAL_LOG) return
            phase = Phase.PASS2
        }
        notifyChanged()
    }

    fun dismissResults() {
        synchronized(lock) {
            if (phase != Phase.RESULTS) return
            phase = Phase.CHAIN_PROMPT
        }
        notifyChanged()
    }

    /** Ends the marathon from any phase (End, or ✕ abort). */
    fun end() {
        synchronized(lock) {
            phase = Phase.IDLE
            steals.clear()
            previousKeys.clear()
            startHint = null
        }
        notifyChanged()
    }

    // ---- log input ----

    /**
     * True for your own log screen (`// LOG` / `~/log.txt`), not a victim log and not a target's screen
     * (target screens start with CONNECTED / DISCONNECT).
     */
    fun isOwnLogScreen(lines: List<String>): Boolean {
        val trimmed = lines.map { it.trim() }
        val hasHeader = trimmed.any { it.equals("// LOG", true) || it.equals("~/log.txt", true) }
        if (!hasHeader) return false
        val victim = trimmed.any { it.contains("victim", true) && (it.startsWith("//") || it.startsWith("~/")) }
        val target = trimmed.any { it.equals("CONNECTED", true) || it.equals("DISCONNECT", true) }
        return !victim && !target
    }

    /**
     * Feeds one own-log scrape. Collects its steals (from the run's start onwards) and advances the
     * flow when a log was being waited for. Returns the number of new steals added, or null if no
     * marathon is collecting.
     */
    fun onOwnLog(updates: List<ParsedUpdate>, anchorUtcMs: Long?, nowMs: Long): Int? {
        val added: Int
        synchronized(lock) {
            if (phase == Phase.IDLE || phase == Phase.RESULTS || phase == Phase.CHAIN_PROMPT) return null
            val startMinute = startedAtMs - startedAtMs % 60_000L
            var count = 0
            for (u in updates) {
                if (!u.isOwnLogTheft) continue
                val wallet = u.wallet ?: continue
                val amount = u.stolenAmount ?: continue
                val t = u.time?.let { LogTime.toEpochMs(it, anchorUtcMs) } ?: continue
                if (t < startMinute) continue
                val key = "$wallet|$t|$amount"
                if (key in previousKeys || key in steals) continue
                val ip = u.ip.takeUnless { CommonParserUtil.isMaskedIp(it) }
                steals[key] = Steal(wallet, amount, t, ip, orderCounter++)
                count++
            }
            added = count
            when (phase) {
                Phase.AWAITING_LOG -> phase = Phase.READY_PASS2
                Phase.AWAITING_FINAL_LOG -> {
                    lastRun = buildRun(nowMs)
                    phase = Phase.RESULTS
                    saveHistory(lastRun!!)
                }
                else -> {}
            }
        }
        notifyChanged()
        return added
    }

    // ---- derived data ----

    private fun sortedSteals(): List<Steal> = steals.values.sortedWith(compareBy({ it.timeMs }, { it.order }))

    /** The run's first steal: where pass 2 starts. */
    fun firstTarget(): Target? = synchronized(lock) { sortedSteals().firstOrNull()?.let { resolve(it.wallet, it.ip) } }

    /** The run's latest steal. */
    fun lastTarget(): Target? = synchronized(lock) { sortedSteals().lastOrNull()?.let { resolve(it.wallet, it.ip) } }

    private fun resolve(wallet: String, logIp: String?): Target {
        val record = try { ownerLookup(wallet) } catch (e: Exception) { null }
        val recordIp = record?.ip?.takeUnless { record.isMasked || record.isTemporary }
        return Target(logIp ?: recordIp, record?.username, wallet)
    }

    /** Per-wallet income rows, best first; wallets without a rate go last (largest single steal first). */
    fun rows(): List<Row> = synchronized(lock) { computeRows() }

    private fun computeRows(): List<Row> {
        val byWallet = sortedSteals().groupBy { it.wallet }
        val rows = byWallet.map { (wallet, list) ->
            val ip = list.firstNotNullOfOrNull { it.ip }
            val spanMin = (list.last().timeMs - list.first().timeMs) / 60_000L
            val refill = list.drop(1).sumOf { it.amount }
            // Same maths as TargetRecord.getEstimatedIncome: what was stolen after the first steal ÷ the span
            val rate = if (list.size >= 2 && spanMin > 0) Math.round(refill * 60.0 / spanMin).toInt() else null
            Row(resolve(wallet, ip), list.size, list.first().amount, refill, spanMin, rate)
        }
        return rows.sortedWith(
            compareByDescending<Row> { it.ratePerHour != null }
                .thenByDescending { it.ratePerHour ?: 0 }
                .thenByDescending { it.firstAmount }
        )
    }

    private fun buildRun(nowMs: Long): Run {
        val sorted = sortedSteals()
        return Run(
            chainId = chainId,
            runNumber = runNumber,
            durationMinutes = durationMinutes,
            startedAtMs = startedAtMs,
            timerEndedAtMs = timerEndedAtMs,
            endedEarly = endedEarly,
            finishedAtMs = nowMs,
            startHint = startHint,
            firstTarget = sorted.firstOrNull()?.let { resolve(it.wallet, it.ip) },
            lastTarget = sorted.lastOrNull()?.let { resolve(it.wallet, it.ip) },
            rows = computeRows()
        )
    }

    private fun saveHistory(run: Run) {
        try {
            val file = historyFileProvider() ?: return
            file.parentFile?.mkdirs()
            file.appendText(GsonBuilder().disableHtmlEscaping().create().toJson(run) + "\n")
        } catch (e: Exception) {
            // History is a convenience; never break a scrape over it
        }
    }

    private fun notifyChanged() {
        try { listener?.invoke() } catch (e: Exception) {}
    }

    // ---- formatting ----

    fun formatCrypto(amount: Int): String =
        if (amount >= 10_000) String.format(Locale.US, "%.1fk", amount / 1000.0) else String.format(Locale.US, "%,d", amount)

    fun formatClock(ms: Long): String {
        val totalSec = (ms + 999) / 1000
        return String.format(Locale.US, "%d:%02d", totalSec / 60, totalSec % 60)
    }

    /** Test hook: back to a clean idle state. */
    internal fun resetForTests() {
        synchronized(lock) {
            phase = Phase.IDLE
            steals.clear(); previousKeys.clear()
            startHint = null; lastRun = null
            runNumber = 0; orderCounter = 0
        }
        listener = null
        ownerLookup = ::lookupOwnerInDatabase
    }
}
