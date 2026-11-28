package com.whisk.hackexlogger

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The dashboard's ⋮ menu and what it runs: Settings, Export, Import, Database Maintenance, Wipe (typed confirmation)
 * and Reset Siphon Tracker. Moved out of MainActivity (2026-11-28) without changing behaviour.
 *
 * Create it as a property of the activity: the file-picker launchers must be registered before the activity starts.
 * [onDataChanged] redraws the dashboard after an import, wipe or maintenance run.
 */
class DashboardDataActions(
    private val activity: AppCompatActivity,
    private val onDataChanged: () -> Unit
) {
    private val exportLauncher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode != AppCompatActivity.RESULT_OK || uri == null) return@registerForActivityResult
        try {
            val jsonString = DatabaseManager.exportDataBundle()
            activity.contentResolver.openOutputStream(uri)?.use { it.write(jsonString.toByteArray(Charsets.UTF_8)) }
            Toast.makeText(activity, "Export successful", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(activity, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private val importLauncher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode != AppCompatActivity.RESULT_OK || uri == null) return@registerForActivityResult
        try {
            val jsonString = activity.contentResolver.openInputStream(uri)?.use { it.bufferedReader(Charsets.UTF_8).readText() }
            if (jsonString.isNullOrEmpty()) {
                Toast.makeText(activity, "File is empty", Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }
            val importResult = DatabaseManager.importDataBundle(jsonString)
            onDataChanged()
            val msg = if (importResult.ingestCount > 0) {
                "Imported ${importResult.targetCount} targets and ${importResult.ingestCount} raw logs."
            } else {
                "Imported ${importResult.targetCount} targets."
            }
            Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            AlertDialog.Builder(activity)
                .setTitle("Import Failed")
                .setMessage(e.message ?: "Failed to import data.")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    /** The ⋮ popup menu, anchored on the button that opened it. */
    fun showMenu(anchor: View) {
        val popup = PopupMenu(activity, anchor)
        popup.menu.add(0, 0, 0, "Settings & Account")
        popup.menu.add(0, 1, 0, "Export Data")
        popup.menu.add(0, 2, 0, "Import Data")
        popup.menu.add(0, 3, 0, "Database Maintenance")
        popup.menu.add(0, 4, 0, "Wipe Database")
        popup.menu.add(0, 5, 0, "Reset Siphon Tracker")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                0 -> activity.startActivity(Intent(activity, SettingsActivity::class.java))
                1 -> openExportPicker()
                2 -> openImportPicker()
                3 -> runMaintenance()
                4 -> confirmWipeDatabase()
                5 -> {
                    DatabaseManager.clearSiphonTracking()
                    Toast.makeText(activity, "Siphon tracker reset", Toast.LENGTH_SHORT).show()
                }
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
        popup.show()
    }

    fun openExportPicker() {
        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "target_console_$dateStr.json")
            putInitialFolder()
        }
        exportLauncher.launch(intent)
    }

    fun openImportPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain"))
            putInitialFolder()
        }
        importLauncher.launch(intent)
    }

    /** Both pickers open in Download/DarkIndex, where the automatic backup lives. */
    private fun Intent.putInitialFolder() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val initialUri = Uri.parse("content://com.android.externalstorage.documents/document/primary:Download%2FDarkIndex")
            putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, initialUri)
        }
    }

    private fun runMaintenance() {
        val result = DatabaseManager.runDatabaseMaintenance()
        onDataChanged()
        AlertDialog.Builder(activity)
            .setTitle("Database Maintenance Complete")
            .setMessage(
                "A backup copy was saved to:\n${result.backupFilePath}\n\n" +
                "Summary of actions taken:\n" +
                "• ${result.multiWalletRecordsFixed} multi-wallet target records cleared\n" +
                "• ${result.maskedSoftwareCleared} masked software inventories cleared\n" +
                "• ${result.maskedWalletsMigrated} masked wallets migrated to placeholders"
            )
            .setPositiveButton("OK", null)
            .show()
    }

    /**
     * Wipe Database is irreversible, so it needs a typed confirmation.
     * The Wipe button stays disabled until the user types WIPE; "Export first" opens the normal export.
     */
    private fun confirmWipeDatabase() {
        val recordCount = DatabaseManager.getAllRecords().size
        val pad = (20 * activity.resources.displayMetrics.density).toInt()
        val input = EditText(activity).apply {
            hint = "Type WIPE to confirm"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }
        val container = android.widget.FrameLayout(activity).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Wipe Database?")
            .setMessage(
                "This permanently deletes all $recordCount target records from the app.\n\n" +
                "It can't be undone. Export your data first if you might want it back."
            )
            .setView(container)
            .setPositiveButton("Wipe", null)
            .setNeutralButton("Export first") { _, _ -> openExportPicker() }
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            val wipeButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            wipeButton.isEnabled = false
            input.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    wipeButton.isEnabled = s?.toString()?.trim() == "WIPE"
                }
            })
            wipeButton.setOnClickListener {
                DatabaseManager.wipeDatabase()
                onDataChanged()
                Toast.makeText(activity, "Database wiped", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }
        dialog.show()
    }
}
