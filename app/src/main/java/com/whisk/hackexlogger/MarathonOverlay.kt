package com.whisk.hackexlogger

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Floating Marathon Mode UI, owned by [OverlayService]:
 * - a small draggable **bar** (timer / instructions / buttons for the current [Marathon.Phase]);
 * - a draggable, scrollable **results panel** that sits beside the game so targets can be tagged.
 *
 * All views are built in code. Everything runs on the main thread; [Marathon] changes from the scrape
 * thread are posted here.
 */
class MarathonOverlay(
    private val context: Context,
    private val windowManager: WindowManager,
    private val toast: (String) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private var bar: LinearLayout? = null
    private var barParams: WindowManager.LayoutParams? = null
    private lateinit var timerText: TextView
    private lateinit var infoText: TextView
    private lateinit var targetBadge: TextView
    private lateinit var buttonRow: LinearLayout

    private var panel: LinearLayout? = null
    private var panelList: ScrollView? = null

    private var renderedPhase: Marathon.Phase? = null
    private var abortArmedUntil = 0L

    private val ticker = object : Runnable {
        override fun run() {
            if (Marathon.phase != Marathon.Phase.TIMED) return
            val now = System.currentTimeMillis()
            if (Marathon.checkTimer(now)) {
                vibrate()
                return // the listener re-renders
            }
            timerText.text = Marathon.formatClock(Marathon.remainingMs(now))
            handler.postDelayed(this, 250)
        }
    }

    init {
        Marathon.listener = { handler.post { render() } }
    }

    // ---- entry points from OverlayService ----

    fun start(minutes: Int) {
        if (Marathon.isActive) {
            toast("Marathon already running")
            render()
            return
        }
        Marathon.start(minutes, System.currentTimeMillis())
    }

    fun destroy() {
        handler.removeCallbacksAndMessages(null)
        Marathon.listener = null
        if (Marathon.isActive) Marathon.end()
        removeBar()
        removePanel()
    }

    // ---- rendering ----

    private fun render() {
        val phase = Marathon.phase
        if (phase == Marathon.Phase.IDLE) {
            handler.removeCallbacks(ticker)
            removeBar(); removePanel()
            renderedPhase = phase
            return
        }
        if (phase == Marathon.Phase.RESULTS) {
            removeBar()
            if (panel == null) showPanel()
            renderedPhase = phase
            return
        }
        removePanel()
        ensureBar()
        val phaseChanged = renderedPhase != phase
        renderedPhase = phase

        buttonRow.removeAllViews()
        targetBadge.visibility = View.GONE
        timerText.visibility = View.VISIBLE
        timerText.setTextColor(Color.WHITE)
        timerText.textSize = 22f
        val run = "Run ${Marathon.runNumber}"

        when (phase) {
            Marathon.Phase.TIMED -> {
                timerText.text = Marathon.formatClock(Marathon.remainingMs(System.currentTimeMillis()))
                infoText.text = "$run · ${Marathon.durationMinutes} min · steal!  (grab your LOG any time)"
                Marathon.startHint?.let { hint ->
                    showTarget("Last run ended at", hint, copyNow = phaseChanged)
                }
                addButton("End early", "#f59e0b") { Marathon.endEarly(System.currentTimeMillis()) }
                addAbortButton()
                handler.removeCallbacks(ticker)
                handler.post(ticker)
            }
            Marathon.Phase.AWAITING_LOG -> {
                timerText.text = "0"
                timerText.textSize = 40f
                timerText.setTextColor(Color.parseColor("#ef4444"))
                infoText.text = "STOP! Open your LOG and tap 📸"
                addAbortButton()
            }
            Marathon.Phase.READY_PASS2 -> {
                timerText.visibility = View.GONE
                infoText.text = "$run · log captured · ${Marathon.stealCount} steals.\nReady for the second pass (untimed)?"
                addButton("Start pass 2", "#16a34a") { Marathon.startPass2() }
                addAbortButton()
            }
            Marathon.Phase.PASS2 -> {
                timerText.visibility = View.GONE
                infoText.text = "$run · pass 2 · steal from each target again"
                val first = Marathon.firstTarget()
                if (first != null) showTarget("Start at (first target)", first, copyNow = phaseChanged)
                else infoText.append("\nNo steals found in this run's logs")
                addButton("Finish", "#16a34a") { Marathon.finish() }
                addAbortButton()
            }
            Marathon.Phase.AWAITING_FINAL_LOG -> {
                timerText.visibility = View.GONE
                infoText.text = "$run · open your LOG and tap 📸 to finish"
                // Finish was tapped by mistake: return to pass 2
                addButton("Back", "#475569") { Marathon.backToPass2() }
                addAbortButton()
            }
            Marathon.Phase.CHAIN_PROMPT -> {
                timerText.visibility = View.GONE
                infoText.text = "Chain another run?"
                Marathon.lastRun?.lastTarget?.let { showTarget("Last run ended at", it, copyNow = false) }
                for (m in Marathon.DURATIONS_MINUTES) {
                    addButton("${m}m", "#2563eb") { Marathon.chain(m, System.currentTimeMillis()) }
                }
                addButton("End", "#475569") { Marathon.end() }
            }
            else -> {}
        }
    }

    private fun showTarget(caption: String, target: Marathon.Target, copyNow: Boolean) {
        val second = listOfNotNull(target.username?.takeIf { target.ip != null }).joinToString()
        targetBadge.text = "$caption:\n${target.label}" + if (second.isNotEmpty()) "  ($second)" else ""
        targetBadge.visibility = View.VISIBLE
        targetBadge.setOnClickListener { copy(target.label) }
        if (copyNow) copy(target.label)
    }

    private fun copy(text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Target", text))
        toast("Copied: $text")
    }

    private fun addButton(label: String, color: String, onClick: () -> Unit) {
        val b = Button(context).apply {
            text = label
            isAllCaps = false
            textSize = 13f
            setTextColor(Color.WHITE)
            minWidth = 0; minHeight = 0; minimumWidth = 0; minimumHeight = 0
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = GradientDrawable().apply { setColor(Color.parseColor(color)); cornerRadius = dp(6).toFloat() }
            setOnClickListener { onClick() }
        }
        buttonRow.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply { marginEnd = dp(6) })
    }

    /** ✕ needs two taps within 3 s so a stray touch doesn't throw a run away. */
    private fun addAbortButton() {
        addButton("✕", "#7f1d1d") {
            val now = System.currentTimeMillis()
            if (now < abortArmedUntil) {
                abortArmedUntil = 0
                Marathon.end()
                toast("Marathon ended")
            } else {
                abortArmedUntil = now + 3000
                toast("Tap ✕ again to end the marathon")
            }
        }
    }

    // ---- bar window ----

    private fun ensureBar() {
        if (bar != null) return
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E6020617")); cornerRadius = dp(10).toFloat()
                setStroke(dp(1), Color.parseColor("#f59e0b"))
            }
        }
        val header = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        timerText = TextView(context).apply {
            typeface = Typeface.MONOSPACE; setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE); textSize = 22f
            setPadding(0, 0, dp(10), 0)
        }
        infoText = TextView(context).apply {
            setTextColor(Color.parseColor("#e2e8f0")); textSize = 12f
            maxWidth = (context.resources.displayMetrics.widthPixels * 0.6).toInt()
        }
        header.addView(TextView(context).apply { text = "🏃"; textSize = 18f; setPadding(0, 0, dp(6), 0) })
        header.addView(timerText)
        header.addView(infoText)
        targetBadge = TextView(context).apply {
            setTextColor(Color.parseColor("#38bdf8")); textSize = 14f; setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = GradientDrawable().apply { setColor(Color.parseColor("#0f172a")); cornerRadius = dp(6).toFloat() }
        }
        buttonRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(header)
        root.addView(targetBadge, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        root.addView(buttonRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })

        val params = overlayParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            x = dp(70); y = dp(40)
        }
        makeDraggable(header, root, params)
        windowManager.addView(root, params)
        bar = root
        barParams = params
    }

    private fun removeBar() {
        handler.removeCallbacks(ticker)
        bar?.let { try { windowManager.removeView(it) } catch (e: Exception) {} }
        bar = null
        renderedPhase = null
    }

    // ---- results panel ----

    private fun showPanel() {
        val run = Marathon.lastRun ?: return
        val dm = context.resources.displayMetrics
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F0020617")); cornerRadius = dp(10).toFloat()
                setStroke(dp(1), Color.parseColor("#38bdf8"))
            }
        }
        val header = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val rated = run.rows.count { it.ratePerHour != null }
        header.addView(TextView(context).apply {
            text = "🏃 Run ${run.runNumber} · $rated/${run.rows.size} rated"
            setTextColor(Color.WHITE); textSize = 13f; setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(context).apply { addView(list); isVerticalScrollBarEnabled = true }
        header.addView(smallButton("–") {
            scroll.visibility = if (scroll.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        })
        header.addView(smallButton("✕") { Marathon.dismissResults() })
        root.addView(header)

        if (run.rows.isEmpty()) {
            list.addView(TextView(context).apply {
                text = "No steals were captured in this run."
                setTextColor(Color.parseColor("#94a3b8")); textSize = 12f
            })
        }
        run.rows.forEachIndexed { i, row -> list.addView(rowView(i + 1, row)) }

        val maxH = (dm.heightPixels * 0.5).toInt()
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // Cap the list height so the game stays visible; the list scrolls inside
        scroll.viewTreeObserver.addOnGlobalLayoutListener {
            if (scroll.height > maxH) scroll.layoutParams = scroll.layoutParams.apply { height = maxH }
        }

        val width = (dm.widthPixels * 0.62).toInt()
        val params = overlayParams(width, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            x = dm.widthPixels - width - dp(4); y = dp(80)
        }
        makeDraggable(header, root, params)
        windowManager.addView(root, params)
        panel = root
        panelList = scroll
    }

    private fun rowView(rank: Int, row: Marathon.Row): View {
        val t = row.target
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(5), dp(4), dp(5))
            setOnClickListener { copy(t.ip ?: t.username ?: t.wallet) }
        }
        val top = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        top.addView(TextView(context).apply {
            text = "$rank. ${t.label}"
            setTextColor(Color.parseColor(if (t.ip != null) "#38bdf8" else "#cbd5e1")); textSize = 13f
            setTypeface(typeface, Typeface.BOLD); maxLines = 1
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(TextView(context).apply {
            text = row.ratePerHour?.let { "₵${Marathon.formatCrypto(it)}/hr" } ?: "—"
            setTextColor(Color.parseColor(if (row.ratePerHour != null) "#4ade80" else "#64748b")); textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
        })
        val detail = buildList {
            if (t.ip != null && t.username != null) add(t.username)
            if (row.steals >= 2) add("₵${Marathon.formatCrypto(row.refillAmount)} in ${row.spanMinutes}m")
            else add("1 steal ₵${Marathon.formatCrypto(row.firstAmount)}")
            add(t.wallet)
        }.joinToString(" · ")
        box.addView(top)
        box.addView(TextView(context).apply {
            text = detail; setTextColor(Color.parseColor("#94a3b8")); textSize = 11f; maxLines = 1
        })
        return box
    }

    private fun smallButton(label: String, onClick: () -> Unit) = TextView(context).apply {
        text = label
        setTextColor(Color.WHITE); textSize = 16f; gravity = Gravity.CENTER
        setPadding(dp(10), dp(2), dp(10), dp(2))
        setOnClickListener { onClick() }
    }

    private fun removePanel() {
        panel?.let { try { windowManager.removeView(it) } catch (e: Exception) {} }
        panel = null
        panelList = null
    }

    // ---- helpers ----

    private fun overlayParams(w: Int, h: Int) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

    /** Dragging [handle] moves [window]; a tap still reaches the handle's children. */
    private fun makeDraggable(handle: View, window: View, params: WindowManager.LayoutParams) {
        var startX = 0; var startY = 0; var touchX = 0f; var touchY = 0f
        handle.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { startX = params.x; startY = params.y; touchX = e.rawX; touchY = e.rawY; true }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (e.rawX - touchX).toInt()
                    params.y = startY + (e.rawY - touchY).toInt()
                    try { windowManager.updateViewLayout(window, params) } catch (ex: Exception) {}
                    true
                }
                else -> false
            }
        }
    }

    private fun vibrate() {
        try {
            val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            vibrator?.vibrate(VibrationEffect.createOneShot(400, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) {}
    }
}
