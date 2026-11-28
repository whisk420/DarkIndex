package com.whisk.hackexlogger

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

/*
 * The two floating queue toolbars shown over the game, moved out of OverlayService (2026-11-28) without changing
 * behaviour: VALIDATE (✓ still there / 🗑 gone / ✕) and RECON (⏭ skip / ✓ done / 🗑 not found / ✕). Each copies the
 * next target's IP so it can be pasted into the game, and is dragged around by its IP badge.
 */

/** Overlay window settings both toolbars use: wraps its content, never takes focus, starts near the top left. */
private fun toolbarParams() = WindowManager.LayoutParams(
    WindowManager.LayoutParams.WRAP_CONTENT,
    WindowManager.LayoutParams.WRAP_CONTENT,
    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
    PixelFormat.TRANSLUCENT
).apply {
    gravity = Gravity.TOP or Gravity.START
    x = 20
    y = 250
}

/** Dragging [handle] moves the whole toolbar [view]. */
private fun WindowManager.makeDraggable(handle: View, view: View, params: WindowManager.LayoutParams) {
    var initialX = 0
    var initialY = 0
    var initialTouchX = 0f
    var initialTouchY = 0f
    handle.setOnTouchListener { _, event ->
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = params.x
                initialY = params.y
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                true
            }
            MotionEvent.ACTION_MOVE -> {
                params.x = initialX + (event.rawX - initialTouchX).toInt()
                params.y = initialY + (event.rawY - initialTouchY).toInt()
                updateViewLayout(view, params)
                true
            }
            else -> false
        }
    }
}

private fun copyIp(context: Context, ip: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Target IP", ip))
}

private fun WindowManager.removeQuietly(view: View) {
    try { removeView(view) } catch (_: Exception) { /* already removed */ }
}

/** VALIDATE: walks the validation queue (never-validated first, then oldest). [onDataChanged] tells the dashboard. */
class ValidationToolbar(
    private val context: Context,
    private val windowManager: WindowManager,
    private val onDataChanged: () -> Unit
) {
    private var view: View? = null
    private var current: TargetRecord? = null

    val isShowing: Boolean get() = view != null

    fun start() {
        if (view != null) {
            loadNext()
            return
        }
        val toolbar = LayoutInflater.from(context).inflate(R.layout.overlay_validation, null)
        view = toolbar

        toolbar.findViewById<Button>(R.id.overlayValidBtn).setOnClickListener {
            current?.let { DatabaseManager.markValidated(it.ip); onDataChanged() }
            loadNext()
        }
        toolbar.findViewById<Button>(R.id.overlayInvalidBtn).setOnClickListener {
            current?.let { DatabaseManager.deleteTarget(it.ip); onDataChanged() }
            loadNext()
        }
        toolbar.findViewById<Button>(R.id.overlayCloseBtn).setOnClickListener { stop() }

        val params = toolbarParams()
        windowManager.makeDraggable(toolbar.findViewById<TextView>(R.id.overlayIpBadge), toolbar, params)
        windowManager.addView(toolbar, params)
        loadNext()
    }

    fun stop() {
        current = null
        view?.let { windowManager.removeQuietly(it) }
        view = null
    }

    /** Shows the next target and copies its IP. */
    private fun loadNext() {
        val next = DatabaseManager.getNextRecordToValidate()
        if (next == null) {
            Toast.makeText(context, "All unmasked targets validated!", Toast.LENGTH_SHORT).show()
            stop()
            return
        }
        current = next
        copyIp(context, next.ip)
        view?.findViewById<TextView>(R.id.overlayIpBadge)?.text = next.ip
        Toast.makeText(context, "Copied IP: ${next.ip}", Toast.LENGTH_SHORT).show()
    }
}

/** RECON: walks targets still missing a wallet or key software. [toast] is the service's small HUD pill. */
class ReconToolbar(
    private val context: Context,
    private val windowManager: WindowManager,
    private val toast: (String) -> Unit
) {
    private var view: View? = null
    private var current: TargetRecord? = null

    val isShowing: Boolean get() = view != null

    fun start() {
        if (view != null) return
        val toolbar = LayoutInflater.from(context).inflate(R.layout.overlay_targeting, null)
        view = toolbar

        val ipBadge = toolbar.findViewById<TextView>(R.id.overlayIpBadge)
        toolbar.findViewById<View>(R.id.overlayCloseBtn)?.setOnClickListener { stop() }

        ipBadge?.setOnClickListener {
            current?.let { target ->
                copyIp(context, target.ip)
                toast("Copied: ${target.ip}")
            }
        }

        toolbar.findViewById<Button>(R.id.overlaySkipBtn)?.setOnClickListener {
            val target = current ?: return@setOnClickListener
            DatabaseManager.markTargetValidated(target.ip)
            val playerBypasser = PlayerProfileRepository.getEffectiveBypasserLevel()
            val fw = target.firewall
            if (fw != null && fw > playerBypasser) {
                toast("FW Lv.$fw > Bypasser Lv.$playerBypasser - Skipped to back")
            } else {
                toast("Skipped: ${target.ip}")
            }
            loadNext()
        }

        toolbar.findViewById<Button>(R.id.overlayValidBtn).setOnClickListener {
            val ip = current?.ip ?: return@setOnClickListener
            DatabaseManager.markTargetValidated(ip)
            toast("Completed: $ip")
            loadNext()
        }

        toolbar.findViewById<Button>(R.id.overlayInvalidBtn).setOnClickListener {
            val ip = current?.ip ?: return@setOnClickListener
            DatabaseManager.deleteTarget(ip)
            toast("Deleted (Not Found): $ip")
            loadNext()
        }

        val params = toolbarParams()
        windowManager.makeDraggable(ipBadge, toolbar, params)
        windowManager.addView(toolbar, params)
        loadNext()
    }

    fun stop() {
        current = null
        view?.let { windowManager.removeQuietly(it) }
        view = null
    }

    /** Shows the next target and copies its IP. */
    private fun loadNext() {
        val next = DatabaseManager.getNextTargetForAdvancedTargeting()
        if (next == null) {
            Toast.makeText(context, "Queue Empty: All targets have info!", Toast.LENGTH_SHORT).show()
            stop()
            return
        }
        current = next
        view?.findViewById<TextView>(R.id.overlayIpBadge)?.text = next.ip
        copyIp(context, next.ip)
        toast("Copied Target: ${next.ip}")
    }
}
