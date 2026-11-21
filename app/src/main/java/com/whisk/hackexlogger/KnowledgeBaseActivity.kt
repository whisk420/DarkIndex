package com.whisk.hackexlogger

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** Knowledge Base index: one card per topic (icon, title, overview, source tally), with search across all topics. */
class KnowledgeBaseActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_knowledge_base)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        findViewById<ImageButton>(R.id.backBtn).setOnClickListener { finish() }
        container = findViewById(R.id.topicsContainer)

        val legend = findViewById<LinearLayout>(R.id.legendContainer)
        buildLegend(legend)
        findViewById<TextView>(R.id.legendToggle).setOnClickListener {
            legend.visibility = if (legend.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        val search = findViewById<EditText>(R.id.kbSearch)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = render(s?.toString().orEmpty())
        })

        render("")
    }

    private fun buildLegend(legend: LinearLayout) {
        val intro = TextView(this).apply {
            text = "Every number carries a badge saying where it comes from:"
            setTextColor(Color.parseColor("#cbd5e1"))
            textSize = 13f
        }
        legend.addView(intro)
        for (source in KbSource.values()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, KbViews.dp(this@KnowledgeBaseActivity, 6), 0, 0)
            }
            row.addView(KbViews.badge(this, source))
            row.addView(TextView(this).apply {
                text = source.meaning
                setTextColor(Color.parseColor("#94a3b8"))
                textSize = 12f
                setPadding(KbViews.dp(this@KnowledgeBaseActivity, 8), 0, 0, 0)
            })
            legend.addView(row)
        }
    }

    private fun render(query: String) {
        container.removeAllViews()
        val inflater = LayoutInflater.from(this)
        val topics = KnowledgeBase.search(query)

        if (topics.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "Nothing matches \"$query\"."
                setTextColor(Color.parseColor("#94a3b8"))
                textSize = 14f
                setPadding(0, KbViews.dp(this@KnowledgeBaseActivity, 24), 0, 0)
            })
            return
        }

        for (topic in topics) {
            val view = inflater.inflate(R.layout.item_kb_topic, container, false)
            view.findViewById<TextView>(R.id.topicIcon).text = topic.icon
            view.findViewById<TextView>(R.id.topicTitle).text = topic.title
            view.findViewById<TextView>(R.id.topicOverview).text = topic.overview
            view.findViewById<TextView>(R.id.topicTally).text = tally(topic)

            val snippetView = view.findViewById<TextView>(R.id.topicSnippet)
            val snippet = topic.matchSnippet(query)
            if (query.isNotBlank() && snippet != null) {
                snippetView.text = "“$snippet”"
                snippetView.visibility = View.VISIBLE
            }

            view.setOnClickListener {
                startActivity(
                    Intent(this, KnowledgeTopicActivity::class.java)
                        .putExtra(KnowledgeTopicActivity.EXTRA_TOPIC_ID, topic.id)
                )
            }
            container.addView(view)
        }
    }

    /** Coloured "4 measured · 12 guide · 2 unknown" line. */
    private fun tally(topic: KbTopic): CharSequence {
        val counts = topic.sourceCounts()
        val out = SpannableStringBuilder()
        for (source in KbSource.values()) {
            val n = counts[source] ?: continue
            if (out.isNotEmpty()) out.append("  ·  ")
            val start = out.length
            out.append("$n ${source.badge.lowercase()}")
            out.setSpan(ForegroundColorSpan(Color.parseColor(source.colorHex)), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return out
    }
}
