package com.whisk.hackexlogger

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import com.google.gson.Gson
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The target database: an in-memory list of immutable [TargetRecord]s, saved to `filesDir/targets.json`
 * ([TargetStore]) on a background queue, with a pretty-printed backup mirrored to `Downloads/DarkIndex/`.
 *
 * Merge rules live in [RecordMerger], export/import in [DatabaseBackup], maintenance in [DatabaseMaintenance].
 */
object TargetRepository {
    const val PREFS_NAME = "HackExDB"
    /** Where the database lived up to 2026-11-22. Read once, moved to [TargetStore.FILE_NAME], then removed. */
    const val KEY_TARGETS = "targets"
    /** Set once stored theft times have been converted from phone-local to UTC (2026-11-20 fix). */
    const val KEY_THEFTS_UTC = "thefts_utc_v1"

    /** `HackExDB` prefs: siphon tracking and one-time migration flags. Targets are no longer stored here. */
    lateinit var prefs: SharedPreferences
    lateinit var appContext: Context
    val gson = Gson()

    /** The whole database. Replaced (never mutated) on every save, so readers can hold on to it safely. */
    @Volatile private var records: List<TargetRecord> = emptyList()
    private val writeLock = Any()

    private val _recordsFlow = MutableStateFlow<List<TargetRecord>>(emptyList())
    val recordsFlow: StateFlow<List<TargetRecord>> = _recordsFlow.asStateFlow()

    @Volatile var isInitialized = false

    /**
     * Set when loading found something it couldn't use (unreadable file, skipped records). The data is kept in
     * the file named here; the UI shows this once.
     */
    @Volatile var loadReport: String? = null

    fun init(context: Context) {
        if (!isInitialized) {
            appContext = context.applicationContext
            prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            loadCacheFromDisk()
            isInitialized = true
            notifyDataUpdated()
        }
    }

    fun contextOrNull(): Context? = if (::appContext.isInitialized) appContext else null

    private fun databaseFile(): File? = contextOrNull()?.let { File(it.filesDir, TargetStore.FILE_NAME) }

    /**
     * Loads the database, from `targets.json` and/or the legacy prefs string.
     *
     * Version compatibility:
     * - First start after the 2026-11-22 update: the prefs string is copied to `targets.json` (and kept verbatim as
     *   `targets_from_prefs_<time>.json`), then removed from prefs.
     * - An older version run after that (a downgrade) writes to prefs again. On the next start of this version both
     *   are merged (by IP, then the usual username/wallet reconcile), so nothing recorded in between is lost.
     * - Records that can't be read are kept in a side file instead of being dropped (see [TargetStore]).
     */
    internal fun loadCacheFromDisk() {
        val file = databaseFile()
        val dir = file?.parentFile
        val problems = mutableListOf<String>()
        var loaded: List<TargetRecord> = emptyList()
        var needsWrite = false

        if (file != null && file.exists()) {
            try {
                val parsed = TargetStore.read(file)!!
                loaded = parsed.records
                TargetStore.keepSkipped(dir!!, parsed.skipped)?.let { problems.add("${parsed.skipped.size} record(s) couldn't be read and were kept in ${it.name}") }
            } catch (e: Exception) {
                val aside = File(dir, "targets_unreadable_${TargetStore.stamp()}.json")
                file.renameTo(aside)
                problems.add("The database file couldn't be read; it was kept as ${aside.name}")
                android.util.Log.e("TargetRepository", "Unreadable ${file.name}: ${e.message}", e)
            }
        }

        val legacy = try { prefs.getString(KEY_TARGETS, null) } catch (_: Exception) { null }
        if (legacy != null) {
            try {
                val parsed = TargetStore.parse(legacy)
                if (dir != null) {
                    File(dir, "targets_from_prefs_${TargetStore.stamp()}.json").writeText(legacy)
                    TargetStore.keepSkipped(dir, parsed.skipped)?.let { problems.add("${parsed.skipped.size} record(s) couldn't be read and were kept in ${it.name}") }
                }
                loaded = if (loaded.isEmpty()) parsed.records else mergeByIp(loaded, parsed.records)
                needsWrite = true
            } catch (e: Exception) {
                // Leave the prefs string where it is: an older version can still read it, and nothing is lost
                problems.add("The old database in app settings couldn't be read; it was left in place")
                android.util.Log.e("TargetRepository", "Unreadable legacy prefs database: ${e.message}", e)
            }
        }

        records = loaded

        if (!prefs.getBoolean(KEY_THEFTS_UTC, false)) {
            // One-time fix: log times used to be read in the phone's time zone instead of UTC
            val (shifted, count) = DatabaseBackup.shiftTheftsLocalToUtc(records, java.util.TimeZone.getDefault())
            records = shifted
            prefs.edit().putBoolean(KEY_THEFTS_UTC, true).apply()
            if (count > 0) needsWrite = true
        }

        if (needsWrite && file != null) {
            try {
                TargetStore.write(file, records)
                // Only drop the prefs copy once the file is on disk and reads back complete
                if (legacy != null && TargetStore.read(file)?.records?.size == records.size) {
                    prefs.edit().remove(KEY_TARGETS).apply()
                }
            } catch (e: Exception) {
                android.util.Log.e("TargetRepository", "Couldn't write ${file.name}: ${e.message}", e)
            }
        }

        loadReport = problems.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    /** [primary]'s records, merged with [other]'s (same IP → [RecordMerger.mergeRecords]), then reconciled. */
    private fun mergeByIp(primary: List<TargetRecord>, other: List<TargetRecord>): List<TargetRecord> {
        val map = LinkedHashMap<String, TargetRecord>()
        primary.forEach { map[it.ip] = it }
        other.forEach { rec -> map[rec.ip] = map[rec.ip]?.let { RecordMerger.mergeRecords(it, rec) } ?: rec }
        val merged = map.values.toList()
        return RecordMerger.reconcile(merged) ?: merged
    }

    /** The current database. Records are immutable, so this is the live list, not a copy. */
    fun getAllRecords(): List<TargetRecord> = if (isInitialized) records else emptyList()

    fun notifyDataUpdated() {
        _recordsFlow.value = getAllRecords()
        contextOrNull()?.let { context ->
            try {
                context.sendBroadcast(android.content.Intent("com.whisk.hackexlogger.DATA_UPDATED").setPackage(context.packageName))
            } catch (_: Exception) {}
        }
    }

    /**
     * Replaces the database with [newRecords] in memory immediately and queues the disk write.
     * Use [commit] instead when the change could create records that share a username or wallet.
     */
    fun saveAllRecords(newRecords: List<TargetRecord>) {
        synchronized(writeLock) { records = newRecords.toList() }
        schedulePersist()
        notifyDataUpdated()
    }

    /** Reconciles [newRecords] (merging records that share a username or wallet), then saves once. */
    fun commit(newRecords: List<TargetRecord>) {
        saveAllRecords(RecordMerger.reconcile(newRecords) ?: newRecords)
    }

    /** Applies [change] to the current list and saves the result (reconciled). Serialised against other updates. */
    fun update(change: (List<TargetRecord>) -> List<TargetRecord>?) {
        synchronized(writeLock) {
            val result = change(getAllRecords()) ?: return
            commit(result)
        }
    }

    /** Merges records that share a username or wallet, saving only if something changed. */
    fun reconcileDatabase() {
        synchronized(writeLock) {
            RecordMerger.reconcile(getAllRecords())?.let { saveAllRecords(it) }
        }
    }

    // ---- Ordered background persistence ------------------------------------------------------------

    /**
     * Folder for the Downloads auto-backup (`Downloads/DarkIndex/`). Returns null when there is no
     * Downloads folder (e.g. JVM unit tests), which skips the mirror. Tests point this at a temp dir.
     */
    @Volatile var backupDirProvider: () -> File? = {
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)?.let { File(it, "DarkIndex") }
    }

    fun getDownloadsBackupFile(): File? {
        val dir = backupDirProvider() ?: return null
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "darkindex_database.json")
    }

    /** Where disk writes run. A single background thread in the app; tests swap in a same-thread executor. */
    @Volatile var persistExecutor: Executor = Executors.newSingleThreadExecutor { r -> Thread(r, "DarkIndex-save") }

    /**
     * How long the Downloads backup waits after a save before it is rewritten. The backup is a full pretty-printed
     * export, the most expensive write, so a burst of scrapes produces one backup instead of one per scrape.
     * The database file itself is written right away. Tests set 0 (write the backup straight after each save).
     */
    @Volatile var mirrorDelayMs: Long = 30_000L

    private val mirrorTimer: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "DarkIndex-backup-timer").apply { isDaemon = true }
    }
    private val mirrorPending = AtomicBoolean(false)

    private val requestedGeneration = AtomicLong(0)
    @Volatile private var persistedGeneration = 0L
    private val persistLock = Any()

    /** Re-writes the Downloads backup (e.g. after your profile changed). No-op before [init]. */
    fun requestBackupWrite() {
        if (isInitialized) scheduleMirror()
    }

    private fun schedulePersist() {
        val generation = requestedGeneration.incrementAndGet()
        persistExecutor.execute { persistLatest(generation) }
    }

    private fun persistLatest(generation: Long) {
        synchronized(persistLock) {
            // A newer save already wrote everything this one would have.
            if (generation <= persistedGeneration) return
            val target = requestedGeneration.get()
            val snapshot = records
            try {
                databaseFile()?.let { TargetStore.write(it, snapshot) }
            } catch (e: Exception) {
                android.util.Log.e("TargetRepository", "Saving the database failed: ${e.message}", e)
            }
            persistedGeneration = target
            // An empty database never overwrites the backup: after a wipe, the last backup is the way back.
            if (snapshot.isNotEmpty()) scheduleMirror()
        }
    }

    private fun scheduleMirror() {
        // Only inside the real app (init() sets appContext), so unit tests can't write a stray DarkIndex/ folder
        if (contextOrNull() == null) return
        if (!mirrorPending.compareAndSet(false, true)) return
        val delay = mirrorDelayMs
        if (delay <= 0) persistExecutor.execute { writeMirrorIfPending() }
        else mirrorTimer.schedule({ persistExecutor.execute { writeMirrorIfPending() } }, delay, TimeUnit.MILLISECONDS)
    }

    private fun writeMirrorIfPending() {
        if (!mirrorPending.getAndSet(false)) return
        try {
            val file = getDownloadsBackupFile() ?: return
            if (records.isEmpty()) return
            file.bufferedWriter().use { DatabaseBackup.writeDataBundle(it) }
            // Re-index the file so apps that list Downloads from Android's media index (e.g. the Files app) show it.
            // Writing through the file path alone doesn't always refresh that index (developer, 2026-11-24: the backup
            // was visible in the app's own picker but missing from Files).
            contextOrNull()?.let { ctx ->
                runCatching { android.media.MediaScannerConnection.scanFile(ctx, arrayOf(file.absolutePath), arrayOf("application/json"), null) }
            }
        } catch (e: Exception) {
            android.util.Log.e("TargetRepository", "Writing the Downloads backup failed: ${e.message}", e)
        }
    }

    /** Writes a pending Downloads backup now instead of waiting for the timer (e.g. when the app goes to the background). */
    fun flushBackupSoon() {
        persistExecutor.execute { writeMirrorIfPending() }
    }

    /**
     * Blocks until every queued disk write, including a pending Downloads backup, has finished (or [timeoutMs]
     * passes). Returns true if all writes are done. Used before exports and by tests.
     */
    fun flushPendingWrites(timeoutMs: Long = 10_000): Boolean {
        val latch = java.util.concurrent.CountDownLatch(1)
        persistExecutor.execute { writeMirrorIfPending(); latch.countDown() }
        return latch.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    fun wipeDatabase() {
        // Through the queue, so an older save still waiting can't bring the data back afterwards.
        saveAllRecords(emptyList())
    }

    /** Test helper: empties memory without touching disk. */
    internal fun resetForTests() {
        synchronized(writeLock) { records = emptyList() }
        loadReport = null
        mirrorPending.set(false)
    }

    // ---- Validation queue and single-record edits ----------------------------------------------------

    fun getNextTargetForAdvancedTargeting(): TargetRecord? {
        val candidates = getAllRecords().filter { it.needsAdvancedTargeting }
        return candidates.firstOrNull { it.lastValidated == null } ?: candidates.minByOrNull { it.lastValidated ?: "" }
    }

    fun getNextRecordToValidate(): TargetRecord? {
        val unmasked = getAllRecords().filter { !it.isMasked }
        return unmasked.firstOrNull { it.lastValidated == null } ?: unmasked.minByOrNull { it.lastValidated ?: "" }
    }

    fun markValidated(ip: String) {
        val now = getCurrentTimestamp()
        update { list ->
            if (list.none { it.ip == ip }) null
            else list.map { if (it.ip == ip) it.copy(lastValidated = now) else it }
        }
    }

    fun markTargetValidated(ip: String) = markValidated(ip)

    fun editTargetIp(oldIp: String, newIp: String) {
        update { list ->
            val existing = list.find { it.ip == oldIp } ?: return@update null
            val rest = list.filter { it.ip != oldIp }
            val collision = rest.find { it.ip == newIp }
            val moved = if (collision != null) RecordMerger.mergeRecords(collision, existing).copy(ip = newIp) else existing.copy(ip = newIp)
            rest.filter { it.ip != newIp } + moved
        }
    }

    fun deleteTarget(ip: String) {
        update { list -> list.filter { it.ip != ip } }
    }

    fun clearTargetTheftEvents(ip: String, wallet: String? = null) {
        update { list ->
            list.map { r ->
                if (r.ip != ip) r
                else if (wallet != null) r.withoutTheftEvents(wallet)
                else r.copy(walletThefts = emptyMap())
            }
        }
    }

    fun getCurrentTimestamp(): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
}
