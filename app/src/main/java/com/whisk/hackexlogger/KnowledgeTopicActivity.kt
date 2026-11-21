package com.whisk.hackexlogger

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayout

/** One Knowledge Base topic: every block of [KbTopic.blocks] rendered top to bottom, calculators live. */
class KnowledgeTopicActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TOPIC_ID = "topic_id"
    }

    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_kb_topic)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        findViewById<ImageButton>(R.id.backBtn).setOnClickListener { finish() }

        val topic = KnowledgeBase.topic(intent.getStringExtra(EXTRA_TOPIC_ID).orEmpty())
        if (topic == null) {
            finish()
            return
        }

        KbCalculators.defaults = loadPlayerDefaults()

        findViewById<TextView>(R.id.topicHeaderTitle).text = "${topic.icon}  ${topic.title}"
        content = findViewById(R.id.topicContent)

        content.addView(card(KbViews.text(this, topic.overview, "#e2e8f0", 15f)))
        for (block in topic.blocks) content.addView(render(block))
        if (topic.related.isNotEmpty()) content.addView(related(topic.related))
    }

    private fun loadPlayerDefaults(): KbPlayerDefaults = try {
        if (!PlayerProfileRepository.isReady()) PlayerProfileRepository.init(applicationContext)
        KbPlayerDefaults.fromProfile(
            PlayerProfileRepository.getProfile(),
            PlayerProfileRepository.getCurrentDevice(),
            PlayerProfileRepository.getCurrentNetwork()
        )
    } catch (e: Exception) {
        KbPlayerDefaults()
    }

    private fun dp(v: Int) = KbViews.dp(this, v)

    private fun card(vararg children: View, background: String = "#050811", stroke: String = "#1e293b"): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            this.background = KbViews.rounded(background, stroke, dp(12).toFloat())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(12) }
            children.forEach { addView(it) }
        }

    private fun render(block: KbBlock): View = when (block) {
        is KbBlock.Heading -> KbViews.text(this, block.text, "#38bdf8", 17f, bold = true).apply {
            setPadding(0, dp(10), 0, dp(8))
        }
        is KbBlock.Para -> withBadge(block.source, KbViews.text(this, block.text, "#cbd5e1", 14f)).apply {
            (layoutParams as? LinearLayout.LayoutParams)?.bottomMargin = dp(10)
        }
        is KbBlock.Bullets -> card(*buildList<View> {
            block.source?.let { add(KbViews.badgeRow(this@KnowledgeTopicActivity, it)) }
            block.items.forEach { add(KbViews.text(this@KnowledgeTopicActivity, "•  $it", "#cbd5e1", 14f).apply { setPadding(0, dp(3), 0, dp(3)) }) }
        }.toTypedArray())
        is KbBlock.Facts -> card(*block.facts.mapIndexed { i, f -> factRow(f, i > 0) }.toTypedArray())
        is KbBlock.Table -> table(block)
        is KbBlock.Tip -> card(KbViews.text(this, "💡  ${block.text}", "#fde68a", 14f), background = "#1c1708", stroke = "#854d0e")
        is KbBlock.Warning -> card(KbViews.text(this, "⚠  ${block.text}", "#fecaca", 14f), background = "#1f0b0b", stroke = "#991b1b")
        is KbBlock.Calc -> KbCalculators.byId(block.calculatorId)?.let { calculator(it) } ?: View(this)
        is KbBlock.Unknowns -> unknowns(block)
    }

    /** A view with a source badge above it (or the view alone when [source] is null). */
    private fun withBadge(source: KbSource?, view: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        if (source != null) addView(KbViews.badgeRow(this@KnowledgeTopicActivity, source))
        addView(view)
    }

    private fun factRow(fact: KbFact, divider: Boolean): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, if (divider) dp(10) else 0, 0, 0)
        if (divider) addView(View(this@KnowledgeTopicActivity).apply {
            setBackgroundColor(Color.parseColor("#1e293b"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { bottomMargin = dp(10) }
        })
        val top = LinearLayout(this@KnowledgeTopicActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(KbViews.text(this@KnowledgeTopicActivity, fact.label.uppercase(), "#94a3b8", 11f, bold = true).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(KbViews.badge(this@KnowledgeTopicActivity, fact.source))
        }
        addView(top)
        addView(KbViews.text(this@KnowledgeTopicActivity, fact.value, "#f8fafc", 15f).apply { setPadding(0, dp(3), 0, 0) })
        fact.note?.let { addView(KbViews.text(this@KnowledgeTopicActivity, it, "#64748b", 12f).apply { setTypeface(typeface, Typeface.ITALIC) }) }
    }

    private fun table(block: KbBlock.Table): View {
        val tableLayout = TableLayout(this).apply { isStretchAllColumns = false }
        fun row(cells: List<String>, header: Boolean, index: Int) = TableRow(this).apply {
            setBackgroundColor(Color.parseColor(if (header) "#1e293b" else if (index % 2 == 0) "#0b1120" else "#050811"))
            cells.forEachIndexed { c, cell ->
                addView(KbViews.text(this@KnowledgeTopicActivity, cell, if (header) "#e2e8f0" else if (c == 0) "#cbd5e1" else "#f8fafc", 13f, bold = header).apply {
                    setPadding(dp(10), dp(7), dp(10), dp(7))
                    maxWidth = dp(260)
                })
            }
        }
        tableLayout.addView(row(block.header, true, 0))
        block.rows.forEachIndexed { i, r -> tableLayout.addView(row(r, false, i)) }

        val scroller = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = true
            addView(tableLayout)
        }
        val children = mutableListOf<View>(KbViews.badgeRow(this, block.source), scroller)
        block.caption?.let { children += KbViews.text(this, it, "#64748b", 12f).apply { setPadding(0, dp(8), 0, 0) } }
        return card(*children.toTypedArray())
    }

    private fun unknowns(block: KbBlock.Unknowns): View {
        val children = mutableListOf<View>(KbViews.text(this, "Open questions (still to measure)", "#fca5a5", 14f, bold = true))
        block.items.forEach { (id, text) ->
            children += LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(6), 0, 0)
                addView(KbViews.chip(this@KnowledgeTopicActivity, id, KbSource.UNKNOWN.colorHex))
                addView(KbViews.text(this@KnowledgeTopicActivity, text, "#cbd5e1", 13f).apply {
                    setPadding(dp(8), 0, 0, 0)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
            }
        }
        return card(*children.toTypedArray(), background = "#140a0a", stroke = "#7f1d1d")
    }

    private fun related(ids: List<String>): View {
        val flex = FlexboxLayout(this).apply { flexWrap = FlexWrap.WRAP }
        for (id in ids) {
            val t = KnowledgeBase.topic(id) ?: continue
            flex.addView(KbViews.chip(this, "${t.icon} ${t.title}", "#38bdf8").apply {
                (layoutParams as? ViewGroup.MarginLayoutParams)?.setMargins(0, 0, dp(8), dp(8))
                setOnClickListener {
                    startActivity(Intent(this@KnowledgeTopicActivity, KnowledgeTopicActivity::class.java).putExtra(EXTRA_TOPIC_ID, id))
                }
            })
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
            addView(KbViews.text(this@KnowledgeTopicActivity, "Related", "#94a3b8", 13f, bold = true).apply { setPadding(0, 0, 0, dp(8)) })
            addView(flex)
        }
    }

    /** A live calculator: inputs from [KbCalculator.inputs], result recomputed on every change. */
    private fun calculator(calc: KbCalculator): View {
        val values = mutableMapOf<String, Any?>()
        val result = KbViews.text(this, "", "#a7f3d0", 14f).apply {
            typeface = Typeface.MONOSPACE
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = KbViews.rounded("#052e1f", "#065f46", dp(8).toFloat())
        }
        fun refresh() {
            result.text = try { calc.run(values) } catch (e: Exception) { "—" }
        }

        val children = mutableListOf<View>(KbViews.text(this, "🧮  ${calc.title}", "#34d399", 16f, bold = true).apply { setPadding(0, 0, 0, dp(8)) })
        for (input in calc.inputs) {
            children += when (input) {
                is CalcInput.Number -> labelled(input.label, EditText(this).apply {
                    inputType = InputType.TYPE_CLASS_NUMBER
                    setText(input.default.toString())
                    setTextColor(Color.parseColor("#f8fafc"))
                    textSize = 15f
                    setSelectAllOnFocus(true)
                    background = KbViews.rounded("#0f172a", "#334155", dp(6).toFloat())
                    setPadding(dp(10), dp(6), dp(10), dp(6))
                    minWidth = dp(96)
                    addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                        override fun afterTextChanged(s: Editable?) {
                            values[input.key] = s?.toString()?.toIntOrNull()
                            refresh()
                        }
                    })
                })
                is CalcInput.Choice -> labelled(input.label, Spinner(this).apply {
                    adapter = object : ArrayAdapter<String>(this@KnowledgeTopicActivity, android.R.layout.simple_spinner_item, input.options) {
                        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                            (super.getView(position, convertView, parent) as TextView).apply { setTextColor(Color.parseColor("#f8fafc")) }
                    }.also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
                    setSelection(input.defaultIndex)
                    background = KbViews.rounded("#0f172a", "#334155", dp(6).toFloat())
                    onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                            values[input.key] = position
                            refresh()
                        }
                        override fun onNothingSelected(parent: AdapterView<*>?) {}
                    }
                }, stacked = true)
                is CalcInput.Toggle -> CheckBox(this).apply {
                    text = input.label
                    isChecked = input.default
                    setTextColor(Color.parseColor("#cbd5e1"))
                    buttonTintList = ColorStateList.valueOf(Color.parseColor("#34d399"))
                    setOnCheckedChangeListener { _, checked ->
                        values[input.key] = checked
                        refresh()
                    }
                }
            }
        }
        children += View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(8)) }
        children += result
        refresh()
        return card(*children.toTypedArray(), background = "#03140e", stroke = "#065f46")
    }

    /** Label + field on one row, or the field under its label when [stacked]. */
    private fun labelled(label: String, field: View, stacked: Boolean = false): View = LinearLayout(this).apply {
        orientation = if (stacked) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        gravity = if (stacked) Gravity.NO_GRAVITY else Gravity.CENTER_VERTICAL
        setPadding(0, dp(4), 0, dp(4))
        addView(KbViews.text(this@KnowledgeTopicActivity, label, "#cbd5e1", 13f).apply {
            layoutParams = if (stacked) LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4) }
            else LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        addView(field, if (stacked) LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40))
            else LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
}

/** Small view factories shared by the Knowledge Base screens. */
object KbViews {
    fun dp(context: Context, v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    fun rounded(fill: String, stroke: String?, radius: Float): GradientDrawable = GradientDrawable().apply {
        setColor(Color.parseColor(fill))
        if (stroke != null) setStroke(1, Color.parseColor(stroke))
        cornerRadius = radius
    }

    fun text(context: Context, value: String, color: String, sizeSp: Float, bold: Boolean = false): TextView = TextView(context).apply {
        text = value
        setTextColor(Color.parseColor(color))
        textSize = sizeSp
        setLineSpacing(dp(context, 2).toFloat(), 1f)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    /** Pill with [label] in [colorHex] on a dark tint of the same colour. */
    fun chip(context: Context, label: String, colorHex: String): TextView = TextView(context).apply {
        text = label
        textSize = 11f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.parseColor(colorHex))
        val c = Color.parseColor(colorHex)
        background = GradientDrawable().apply {
            setColor(Color.argb(40, Color.red(c), Color.green(c), Color.blue(c)))
            setStroke(1, c)
            cornerRadius = dp(context, 10).toFloat()
        }
        setPadding(dp(context, 8), dp(context, 2), dp(context, 8), dp(context, 2))
        layoutParams = ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    fun badge(context: Context, source: KbSource): TextView = chip(context, source.badge, source.colorHex)

    /** A badge on its own line, with a little space under it. */
    fun badgeRow(context: Context, source: KbSource): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, 0, 0, dp(context, 6))
        addView(badge(context, source))
    }
}
