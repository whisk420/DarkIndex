package com.whisk.hackexlogger

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Raw Dump Mode browser (debug). One card per recorded 📸 scrape.
 * Tap = copy that dump's JSON · ▸ = expand · long-press = DELETE · top bar: EXPORT ALL / DELETE ALL.
 */
class RawDumpsActivity : AppCompatActivity() {

    private lateinit var store: RawDumpStore
    private lateinit var adapter: RawDumpAdapter

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode == RESULT_OK && uri != null) exportAllTo(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_raw_dumps)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        store = RawDumpStore.forContext(this)
        adapter = RawDumpAdapter(
            loadJson = { id -> store.readJson(id) },
            onCopy = { id -> copyDump(id) },
            onDelete = { id -> deleteDump(id) }
        )
        findViewById<RecyclerView>(R.id.dumpsRecycler).adapter = adapter

        findViewById<ImageButton>(R.id.backBtn).setOnClickListener { finish() }
        findViewById<Button>(R.id.exportAllBtn).setOnClickListener { openExportPicker() }
        findViewById<Button>(R.id.deleteAllBtn).setOnClickListener { confirmDeleteAll() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        lifecycleScope.launch {
            val summaries = withContext(Dispatchers.IO) { store.listSummaries() }
            findViewById<TextView>(R.id.titleText).text = "Raw Dumps (${summaries.size})"
            findViewById<TextView>(R.id.emptyText).visibility = if (summaries.isEmpty()) View.VISIBLE else View.GONE
            adapter.submitList(summaries)
        }
    }

    private fun copyDump(id: Long) {
        lifecycleScope.launch {
            val json = withContext(Dispatchers.IO) { store.readJson(id) }
            if (json == null) {
                Toast.makeText(this@RawDumpsActivity, "Dump #$id not found", Toast.LENGTH_SHORT).show()
                return@launch
            }
            try {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("DarkIndex raw dump #$id", json))
                Toast.makeText(this@RawDumpsActivity, "Dump #$id copied (${json.length / 1024} KB)", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@RawDumpsActivity, "Dump #$id too large for the clipboard — use EXPORT ALL", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun deleteDump(id: Long) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { store.delete(id) }
            Toast.makeText(this@RawDumpsActivity, "Dump #$id deleted", Toast.LENGTH_SHORT).show()
            refresh()
        }
    }

    private fun confirmDeleteAll() {
        val count = adapter.itemCount
        if (count == 0) return
        AlertDialog.Builder(this)
            .setTitle("Delete all raw dumps?")
            .setMessage("This permanently deletes all $count dumps. Export them first if you might need them.")
            .setPositiveButton("Delete all") { _, _ ->
                lifecycleScope.launch {
                    val n = withContext(Dispatchers.IO) { store.deleteAll() }
                    Toast.makeText(this@RawDumpsActivity, "Deleted $n dumps", Toast.LENGTH_SHORT).show()
                    refresh()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openExportPicker() {
        if (adapter.itemCount == 0) {
            Toast.makeText(this, "No dumps to export", Toast.LENGTH_SHORT).show()
            return
        }
        val stamp = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date())
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "darkindex_dumps_$stamp.json")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                putExtra(
                    android.provider.DocumentsContract.EXTRA_INITIAL_URI,
                    Uri.parse("content://com.android.externalstorage.documents/document/primary:Download%2FDarkIndex")
                )
            }
        }
        exportLauncher.launch(intent)
    }

    private fun exportAllTo(uri: Uri) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val n = contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { store.exportAll(it) }
                    if (n == null) "Export failed: could not open file" else "Exported $n dumps"
                } catch (e: Exception) {
                    "Export failed: ${e.message}"
                }
            }
            Toast.makeText(this@RawDumpsActivity, result, Toast.LENGTH_LONG).show()
        }
    }
}

class RawDumpAdapter(
    private val loadJson: (Long) -> String?,
    private val onCopy: (Long) -> Unit,
    private val onDelete: (Long) -> Unit
) : ListAdapter<RawDumpSummary, RawDumpAdapter.VH>(Diff()) {

    private val expandedIds = mutableSetOf<Long>()
    private var overlayId: Long? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_raw_dump, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val s = getItem(position)
        holder.bind(
            s = s,
            expanded = s.id in expandedIds,
            overlay = overlayId == s.id,
            loadJson = loadJson,
            onTap = {
                if (overlayId != null) dismissOverlay() else onCopy(s.id)
            },
            onToggleExpand = {
                if (!expandedIds.add(s.id)) expandedIds.remove(s.id)
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) notifyItemChanged(pos)
            },
            onLongPress = {
                val prev = overlayId
                overlayId = if (prev == s.id) null else s.id
                refreshIds(prev, s.id)
            },
            onDelete = {
                overlayId = null
                expandedIds.remove(s.id)
                onDelete(s.id)
            }
        )
    }

    private fun dismissOverlay() {
        val prev = overlayId ?: return
        overlayId = null
        refreshIds(prev)
    }

    private fun refreshIds(vararg ids: Long?) {
        ids.filterNotNull().forEach { id ->
            val pos = currentList.indexOfFirst { it.id == id }
            if (pos >= 0) notifyItemChanged(pos)
        }
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        private val card: View = view.findViewById(R.id.cardContent)
        private val timestamp: TextView = view.findViewById(R.id.timestampText)
        private val expandBtn: TextView = view.findViewById(R.id.expandBtn)
        private val meta: TextView = view.findViewById(R.id.metaText)
        private val type: TextView = view.findViewById(R.id.typeText)
        private val result: TextView = view.findViewById(R.id.resultText)
        private val stats: TextView = view.findViewById(R.id.statsText)
        private val error: TextView = view.findViewById(R.id.errorText)
        private val detail: TextView = view.findViewById(R.id.detailText)
        private val overlayView: View = view.findViewById(R.id.actionOverlay)
        private val deleteBtn: Button = view.findViewById(R.id.deleteBtn)

        fun bind(
            s: RawDumpSummary,
            expanded: Boolean,
            overlay: Boolean,
            loadJson: (Long) -> String?,
            onTap: () -> Unit,
            onToggleExpand: () -> Unit,
            onLongPress: () -> Unit,
            onDelete: () -> Unit
        ) {
            val date = Date(s.capturedAtMs)
            timestamp.text = SimpleDateFormat("MMM d · HH:mm:ss", Locale.US).format(date)
            val utc = SimpleDateFormat("HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(date)
            meta.text = listOfNotNull("#${s.id}", "$utc UTC", s.foregroundPackage).joinToString(" · ")

            type.text = (s.detectedType ?: "—") + if (s.fallbackUsed) " → fallback" else ""
            type.setTextColor(Color.parseColor(if (s.detectedType == null || s.fallbackUsed) "#fbbf24" else "#38bdf8"))
            result.text = s.result ?: ""

            val parts = mutableListOf<String>()
            s.parsersMatched?.takeIf { it.isNotEmpty() }?.let { parts.add("matched: " + it.joinToString(", ")) }
            s.logStats?.let { ls ->
                var t = "log ${ls.timestampedLines} · ok ${ls.interpreted} · discarded ${ls.discarded}"
                if (ls.partial > 0) t += " · partial ${ls.partial}"
                parts.add(t)
            }
            parts.add("${s.rawChars} chars · ${s.nodeCount} nodes")
            stats.text = parts.joinToString("\n")
            val hasDiscards = (s.logStats?.discarded ?: 0) > 0 || (s.logStats?.partial ?: 0) > 0
            stats.setTextColor(Color.parseColor(if (hasDiscards) "#fbbf24" else "#94a3b8"))

            if (s.error.isNullOrBlank()) {
                error.visibility = View.GONE
            } else {
                error.visibility = View.VISIBLE
                error.text = "⚠ ${s.error}"
            }

            expandBtn.text = if (expanded) "▾" else "▸"
            if (expanded) {
                detail.visibility = View.VISIBLE
                detail.text = loadJson(s.id) ?: "(dump file missing)"
            } else {
                detail.visibility = View.GONE
                detail.text = ""
            }

            overlayView.visibility = if (overlay) View.VISIBLE else View.GONE

            card.setOnClickListener { onTap() }
            card.setOnLongClickListener { onLongPress(); true }
            expandBtn.setOnClickListener { onToggleExpand() }
            overlayView.setOnClickListener { onLongPress() } // tap outside DELETE dismisses
            deleteBtn.setOnClickListener { onDelete() }
        }
    }

    class Diff : DiffUtil.ItemCallback<RawDumpSummary>() {
        override fun areItemsTheSame(a: RawDumpSummary, b: RawDumpSummary) = a.id == b.id
        override fun areContentsTheSame(a: RawDumpSummary, b: RawDumpSummary) = a == b
    }
}
