package com.whisk.hackexlogger

import java.io.File
import java.nio.file.Files

/**
 * A minimal Android [android.content.Context] for JVM tests: a real files dir (a temp folder), a fixed
 * package name, and broadcasts that go nowhere. Lets code that writes to `filesDir`
 * (e.g. `WalletConflictLog.log`) run for real instead of failing silently.
 */
class TestContext(private val dir: File) : android.content.ContextWrapper(null) {
    init { dir.mkdirs() }
    override fun getFilesDir(): File = dir
    override fun getApplicationContext(): android.content.Context = this
    override fun getPackageName(): String = "com.whisk.hackexlogger"
    override fun sendBroadcast(intent: android.content.Intent?) {}
}

/** Shared setup so every test starts from an empty, isolated repository. */
object TestEnv {

    /**
     * Resets [TargetRepository], [PlayerProfileRepository] and [SiphonTracker] to empty in-memory
     * state. Files (conflict log, Downloads mirror) go to a fresh temp folder, never into the project.
     * @return the temp folder, so tests can inspect what was written.
     */
    fun freshRepository(): File {
        val dir = Files.createTempDirectory("darkindex-test").toFile()
        dir.deleteOnExit()
        TargetRepository.prefs = FakeSharedPreferences()
        TargetRepository.resetForTests()
        TargetRepository.isInitialized = true
        TargetRepository.appContext = TestContext(dir)
        TargetRepository.backupDirProvider = { File(dir, "DarkIndex") }
        WalletConflictLog.listener = null
        // Run the background disk writes on the calling thread so tests are deterministic
        TargetRepository.persistExecutor = java.util.concurrent.Executor { it.run() }
        TargetRepository.mirrorDelayMs = 0 // write the Downloads backup straight after each save
        DatabaseManager.activeDiscardSink = null
        PlayerProfileRepository.prefs = FakeSharedPreferences()
        SiphonTracker.currentTimeProvider = { System.currentTimeMillis() }
        SiphonTracker.init()
        SiphonTracker.clearSiphonTracking()
        return dir
    }

    /** Lines of `wallet_conflicts.jsonl` written under [dir] (empty if none). */
    fun conflictLog(dir: File): List<String> =
        File(dir, "wallet_conflicts.jsonl").takeIf { it.exists() }?.readLines()?.filter { it.isNotBlank() } ?: emptyList()

    /** Reads a raw screen-text fixture from `src/test/resources/fixtures/`. */
    fun fixture(name: String): String {
        val stream = TestEnv::class.java.classLoader!!.getResourceAsStream("fixtures/$name")
            ?: error("Missing test fixture fixtures/$name")
        return stream.bufferedReader().use { it.readText() }.replace("\r\n", "\n")
    }
}
