package com.whisk.hackexlogger

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.animation.AlphaAnimation
import android.os.Handler
import android.os.Looper
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.SharedPreferences
import android.view.ViewConfiguration
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.core.app.NotificationCompat

/**
 * Foreground Service responsible for presenting draggable floating overlay controls over the screen.
 *
 * Provides two main modes:
 * 1. Floating Quick-Scrape Button: A camera icon button that sends a trigger broadcast to [ScraperAccessibilityService] to capture on-screen game text.
 * 2. Target Validation Mode Toolbar: A floating toolbar that iterates through unvalidated targets, automatically copies their IP addresses to the clipboard, and allows marking targets valid or deleting invalid records.
 */
class OverlayService : Service() {

    companion object {
        const val ACTION_TOGGLE_VALIDATION = "com.whisk.hackexlogger.TOGGLE_VALIDATION"
        const val ACTION_START_VALIDATION = "com.whisk.hackexlogger.START_VALIDATION"
        const val ACTION_STOP_VALIDATION = "com.whisk.hackexlogger.STOP_VALIDATION"
        
        const val ACTION_TOGGLE_ADVANCED_TARGETING = "com.whisk.hackexlogger.TOGGLE_ADVANCED_TARGETING"

        const val ACTION_START_MARATHON = "com.whisk.hackexlogger.START_MARATHON"
        const val EXTRA_MARATHON_MINUTES = "marathon_minutes"
    }

    private lateinit var windowManager: WindowManager
    private lateinit var cameraFloatingButton: Button
    
    // The VALIDATE and RECON queue toolbars (QueueToolbars.kt)
    private var validationToolbar: ValidationToolbar? = null
    private var reconToolbar: ReconToolbar? = null

    private var isCloseTargetVisible = false
    private var closeTargetView: TextView? = null
    
    private var toastView: TextView? = null
    private var marathonOverlay: MarathonOverlay? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // Raw Dump Mode: the 📸 button turns into 📼 with a red ring while dumps are being recorded.
    private var settingsPrefs: SharedPreferences? = null
    private val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == RawDumpSettings.KEY_ENABLED) applyCameraButtonStyle() // listeners are delivered on the main thread
    }
    
    private val saveReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val message = intent.getStringExtra("message") ?: return
            showMinimalToast(message)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(1, buildNotification())

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        setupCameraFloatingButton()
        marathonOverlay = MarathonOverlay(this, windowManager) { showMinimalToast(it) }
        validationToolbar = ValidationToolbar(this, windowManager) { notifyDataUpdated() }
        reconToolbar = ReconToolbar(this, windowManager) { showMinimalToast(it) }

        settingsPrefs = getSharedPreferences(RawDumpSettings.PREFS_NAME, Context.MODE_PRIVATE).also {
            it.registerOnSharedPreferenceChangeListener(settingsListener)
        }
        applyCameraButtonStyle()
        
        androidx.core.content.ContextCompat.registerReceiver(
            this,
            saveReceiver,
            IntentFilter("com.whisk.hackexlogger.SHOW_TOAST"),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE_VALIDATION -> {
                val toolbar = validationToolbar
                if (toolbar?.isShowing == true) toolbar.stop() else toolbar?.start()
            }
            ACTION_START_VALIDATION -> validationToolbar?.start()
            ACTION_STOP_VALIDATION -> validationToolbar?.stop()
            ACTION_TOGGLE_ADVANCED_TARGETING -> {
                val recon = reconToolbar
                if (recon?.isShowing == true) {
                    recon.stop()
                } else {
                    validationToolbar?.stop() // only one queue toolbar at a time
                    recon?.start()
                }
            }
            ACTION_START_MARATHON -> {
                val minutes = intent.getIntExtra(EXTRA_MARATHON_MINUTES, 0)
                if (minutes > 0) marathonOverlay?.start(minutes)
            }
        }
        return START_STICKY
    }

    /**
     * Initializes the floating camera trigger button widget using WindowManager overlay parameters.
     */
    private fun setupCameraFloatingButton() {
        cameraFloatingButton = Button(this).apply {
            text = "📸"
            setBackgroundColor(Color.parseColor("#80000000"))
            setTextColor(Color.WHITE)
            textSize = 20f
            isAllCaps = false
            setPadding(0, 0, 0, 0)
            
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Color.parseColor("#99000000"))
            }
        }

        val size = (50 * resources.displayMetrics.density).toInt()

        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 100
        }

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop

        // Touch listener enables dragging the overlay button anywhere on screen
        cameraFloatingButton.setOnTouchListener { _, event ->
            val displayMetrics = resources.displayMetrics
            val closeThresholdY = displayMetrics.heightPixels - (150 * displayMetrics.density)

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val diffX = Math.abs(event.rawX - initialTouchX)
                    val diffY = Math.abs(event.rawY - initialTouchY)
                    
                    // Multiply touchSlop by 1.5 to make tapping more forgiving (less accidental drags)
                    if (!isDragging && (diffX > touchSlop * 1.5f || diffY > touchSlop * 1.5f)) {
                        isDragging = true
                        showCloseTarget()
                    }

                    if (isDragging) {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager.updateViewLayout(cameraFloatingButton, params)
                        
                        if (event.rawY > closeThresholdY) {
                            closeTargetView?.background = android.graphics.drawable.GradientDrawable().apply {
                                shape = android.graphics.drawable.GradientDrawable.OVAL
                                setColor(Color.parseColor("#CCFF0000")) // Semi-transparent red highlight
                            }
                        } else {
                            closeTargetView?.background = android.graphics.drawable.GradientDrawable().apply {
                                shape = android.graphics.drawable.GradientDrawable.OVAL
                                setColor(Color.parseColor("#80000000")) // Semi-transparent black default
                            }
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isDragging) {
                        hideCloseTarget()
                        if (event.rawY > closeThresholdY) {
                            stopSelf()
                        }
                    } else {
                        // User intentionally tapped. Bypassing broadcast queue for instantaneous capture.
                        val service = ScraperAccessibilityService.instance
                        if (service != null) {
                            service.scrapeAndParse()
                        } else {
                            val scrapeIntent = Intent(ScraperAccessibilityService.ACTION_TRIGGER_SCRAPE)
                            scrapeIntent.setPackage(packageName)
                            sendBroadcast(scrapeIntent)
                        }
                    }
                    true
                }
                else -> false
            }
        }

        // We don't use OnClickListener since we handle taps in ACTION_UP
        cameraFloatingButton.setOnClickListener(null)

        windowManager.addView(cameraFloatingButton, params)
    }

    /** Normal: 📸 on a dark circle. Raw Dump Mode: 📼 on a dark circle with a red ring. */
    private fun applyCameraButtonStyle() {
        if (!::cameraFloatingButton.isInitialized) return
        val dumping = RawDumpSettings.isEnabled(this)
        cameraFloatingButton.text = if (dumping) "📼" else "📸"
        cameraFloatingButton.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(Color.parseColor("#99000000"))
            if (dumping) setStroke((3 * resources.displayMetrics.density).toInt(), Color.parseColor("#ef4444"))
        }
    }

    private fun showMinimalToast(text: String) {
        if (toastView == null) {
            toastView = TextView(this).apply {
                setTextColor(Color.parseColor("#4ade80")) // Bright green text
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(32, 16, 32, 16)
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.parseColor("#FF000000")) // Solid black
                    cornerRadius = 20f
                }
            }
            
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = (40 * resources.displayMetrics.density).toInt()
                windowAnimations = android.R.style.Animation_Toast
            }
            
            windowManager.addView(toastView, params)
        }
        
        toastView?.text = text
        toastView?.alpha = 1f
        toastView?.visibility = View.VISIBLE
        
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.postDelayed({
            val fadeOut = AlphaAnimation(1f, 0f)
            fadeOut.duration = 500
            toastView?.startAnimation(fadeOut)
            mainHandler.postDelayed({
                toastView?.visibility = View.GONE
            }, 500)
        }, 1500)
    }

    private fun showCloseTarget() {
        if (isCloseTargetVisible) return
        if (closeTargetView == null) {
            closeTargetView = TextView(this).apply {
                text = "✖"
                textSize = 24f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(Color.parseColor("#80000000"))
                }
            }
        }
        
        val size = (60 * resources.displayMetrics.density).toInt()
        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (50 * resources.displayMetrics.density).toInt()
        }
        
        windowManager.addView(closeTargetView, params)
        isCloseTargetVisible = true
    }

    private fun hideCloseTarget() {
        if (!isCloseTargetVisible) return
        closeTargetView?.let {
            try { windowManager.removeView(it) } catch (e: Exception) {}
        }
        isCloseTargetVisible = false
    }

    /**
     * Broadcasts a notification to update MainActivity's target display adapter.
     */
    private fun notifyDataUpdated() {
        val updateIntent = Intent("com.whisk.hackexlogger.DATA_UPDATED")
        updateIntent.setPackage(packageName)
        sendBroadcast(updateIntent)
    }

    /**
     * Creates a low-priority notification channel required for foreground overlay services on Android.
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("OverlayServiceChannel", "DarkIndex Overlay Service", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, "OverlayServiceChannel")
            .setContentTitle("DarkIndex")
            .setContentText("Overlay active")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        TargetRepository.flushBackupSoon()
        settingsPrefs?.unregisterOnSharedPreferenceChangeListener(settingsListener)
        unregisterReceiver(saveReceiver)
        hideCloseTarget()
        toastView?.let { try { windowManager.removeView(it) } catch (e: Exception) {} }
        if (::cameraFloatingButton.isInitialized) {
            try { windowManager.removeView(cameraFloatingButton) } catch (e: Exception) {}
        }
        validationToolbar?.stop()
        reconToolbar?.stop() // was left on screen when the service stopped
        marathonOverlay?.destroy()
        marathonOverlay = null
    }
}