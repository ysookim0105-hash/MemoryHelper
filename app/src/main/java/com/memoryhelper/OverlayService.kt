package com.memoryhelper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService

class OverlayService : LifecycleService() {

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        const val EXTRA_GRID = "grid"
        const val EXTRA_SENSITIVITY = "sensitivity"
        private const val CH_ID = "mh_capture"
        private const val NOTIF_ID = 1
    }

    private lateinit var wm: WindowManager
    private lateinit var metrics: DisplayMetrics

    private var barView: View? = null
    private var gridOverlay: GridOverlayView? = null
    private var gridOverlayParams: WindowManager.LayoutParams? = null

    private var detector: CardDetector? = null
    private var mediaProjection: MediaProjection? = null

    private var isRecording = false
    private var showOverlay = false

    // Bar buttons (kept as fields to update their text)
    private var recBtn: Button? = null
    private var viewBtn: Button? = null

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        @Suppress("DEPRECATION")
        val gridConfig = intent?.getParcelableExtra<GridConfig>(EXTRA_GRID)
        val sensitivity = intent?.getFloatExtra(EXTRA_SENSITIVITY, 0.20f) ?: 0.20f

        if (resultCode == 0 || data == null || gridConfig == null) {
            stopSelf(); return START_NOT_STICKY
        }

        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        metrics = DisplayMetrics().also { wm.defaultDisplay.getRealMetrics(it) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }

        val mgr = getSystemService(MediaProjectionManager::class.java)
        mediaProjection = mgr.getMediaProjection(resultCode, data)

        detector = CardDetector(mediaProjection!!, metrics, sensitivity) { state ->
            updateBar(state)
            if (showOverlay) gridOverlay?.update(state, metrics.widthPixels, metrics.heightPixels)
        }.also { it.setGrid(gridConfig) }

        setupBar(gridConfig)
        return START_NOT_STICKY
    }

    // ── Floating action bar ────────────────────────────────────────────────

    private fun setupBar(config: GridConfig) {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = roundRect(0xCC101828.toInt(), 28f)
            setPadding(16, 10, 16, 10)

            addView(makeBtn("영역") { /* grid already set; future: re-configure */ })
            addView(space(10))

            recBtn = makeBtn("● REC") { onRecToggle() }
            addView(recBtn)
            addView(space(10))

            viewBtn = makeBtn("보기 0/${config.cols * config.rows}") { onViewToggle(config) }
            addView(viewBtn)
            addView(space(10))

            addView(makeBtn("✕") { cleanup(); stopSelf() })
        }

        val params = overlayWrapParams().apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 80
        }

        // Drag support
        var dx = 0f; var dy = 0f
        bar.setOnTouchListener { v, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> { dx = params.x - ev.rawX; dy = params.y - ev.rawY }
                MotionEvent.ACTION_MOVE -> {
                    params.x = (ev.rawX + dx).toInt()
                    params.y = (ev.rawY + dy).toInt()
                    wm.updateViewLayout(v, params)
                }
            }
            false
        }

        wm.addView(bar, params)
        barView = bar
    }

    private fun onRecToggle() {
        if (!isRecording) {
            detector?.start()
            isRecording = true
            recBtn?.text = "■ 중지"
            recBtn?.setTextColor(0xFFFF6B6B.toInt())
        } else {
            detector?.stop()
            isRecording = false
            recBtn?.text = "● REC"
            recBtn?.setTextColor(Color.WHITE)
        }
    }

    private fun onViewToggle(config: GridConfig) {
        showOverlay = !showOverlay
        if (showOverlay) {
            addGridOverlay(config)
            viewBtn?.text = "숨기기"
        } else {
            removeGridOverlay()
            viewBtn?.text = "보기 ${detector?.gridState?.knownCount ?: 0}/${config.cols * config.rows}"
        }
    }

    private fun updateBar(state: GridState) {
        if (!showOverlay) {
            viewBtn?.text = "보기 ${state.knownCount}/${state.total}"
        } else {
            viewBtn?.text = "숨기기 ${state.knownCount}/${state.total}"
        }
    }

    // ── Grid overlay ───────────────────────────────────────────────────────

    private fun addGridOverlay(config: GridConfig) {
        if (gridOverlay != null) return
        val v = GridOverlayView(this)
        val params = WindowManager.LayoutParams(
            metrics.widthPixels, metrics.heightPixels,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        // Show current state immediately
        detector?.gridState?.let { v.update(it, metrics.widthPixels, metrics.heightPixels) }
        wm.addView(v, params)
        gridOverlay = v
        gridOverlayParams = params
    }

    private fun removeGridOverlay() {
        gridOverlay?.let { wm.removeView(it) }
        gridOverlay = null
        gridOverlayParams = null
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    private fun cleanup() {
        detector?.stop()
        barView?.let { runCatching { wm.removeView(it) } }
        removeGridOverlay()
        mediaProjection?.stop()
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    // ── Notification ───────────────────────────────────────────────────────

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CH_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CH_ID, "카드 감지", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, OverlayService::class.java).also { it.action = "STOP" },
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CH_ID)
            .setContentTitle("기억력 게임 보조 실행 중")
            .setContentText("탭하여 앱으로 돌아가기")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .addAction(0, "중지", stopIntent)
            .setOngoing(true)
            .build()
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun makeBtn(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(Color.WHITE)
        background = roundRect(0xFF1E2A3A.toInt(), 20f)
        setPadding(20, 8, 20, 8)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }

    private fun space(dp: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp.dp, 1)
    }

    private val Int.dp get() = (this * resources.displayMetrics.density).toInt()

    private fun roundRect(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color); cornerRadius = radius
    }

    private fun overlayWrapParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT
    )
}
