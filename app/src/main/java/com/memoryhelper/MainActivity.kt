package com.memoryhelper

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQ_OVERLAY = 1001
        private const val REQ_PROJECTION = 1002
    }

    // Grid region sliders (store as % 0–100)
    private lateinit var slLeft: SeekBar
    private lateinit var slTop: SeekBar
    private lateinit var slRight: SeekBar
    private lateinit var slBottom: SeekBar
    private lateinit var spinnerCols: Spinner
    private lateinit var spinnerRows: Spinner
    private lateinit var slSens: SeekBar
    private lateinit var tvStatus: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button

    private var serviceRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        slLeft   = findViewById(R.id.slLeft)
        slTop    = findViewById(R.id.slTop)
        slRight  = findViewById(R.id.slRight)
        slBottom = findViewById(R.id.slBottom)
        spinnerCols = findViewById(R.id.spinnerCols)
        spinnerRows = findViewById(R.id.spinnerRows)
        slSens   = findViewById(R.id.slSensitivity)
        tvStatus = findViewById(R.id.tvStatus)
        btnStart = findViewById(R.id.btnStart)
        btnStop  = findViewById(R.id.btnStop)

        // Default region: center 60% of screen (typical game area)
        slLeft.progress   = 5
        slTop.progress    = 20
        slRight.progress  = 95
        slBottom.progress = 80
        slSens.progress   = 20  // 20% = sensitivity 0.20

        val numOptions = (2..8).map { it.toString() }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, numOptions)
            .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spinnerCols.adapter = adapter
        spinnerRows.adapter = adapter
        spinnerCols.setSelection(2) // default 4 cols
        spinnerRows.setSelection(2) // default 4 rows

        btnStart.setOnClickListener { checkAndStart() }
        btnStop.setOnClickListener  { stopService() }

        updateStatus("권한을 확인하고 [시작]을 눌러주세요")
    }

    private fun checkAndStart() {
        // 1. SYSTEM_ALERT_WINDOW
        if (!Settings.canDrawOverlays(this)) {
            updateStatus("'다른 앱 위에 표시' 권한을 허용해 주세요")
            startActivityForResult(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")),
                REQ_OVERLAY
            )
            return
        }

        // 2. POST_NOTIFICATIONS (Android 13+)
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            !NotificationManagerCompat.from(this).areNotificationsEnabled()
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 0)
            return
        }

        // 3. MediaProjection consent
        val mgr = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(mgr.createScreenCaptureIntent(), REQ_PROJECTION)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_OVERLAY -> {
                if (Settings.canDrawOverlays(this)) checkAndStart()
                else updateStatus("오버레이 권한이 필요합니다")
            }
            REQ_PROJECTION -> {
                if (resultCode == Activity.RESULT_OK && data != null) {
                    launchService(resultCode, data)
                } else {
                    updateStatus("화면 캡처 권한이 거부됐습니다")
                }
            }
        }
    }

    private fun launchService(resultCode: Int, data: Intent) {
        val cols = spinnerCols.selectedItem.toString().toInt()
        val rows = spinnerRows.selectedItem.toString().toInt()
        val config = GridConfig(
            leftFrac   = slLeft.progress / 100f,
            topFrac    = slTop.progress / 100f,
            rightFrac  = slRight.progress / 100f,
            bottomFrac = slBottom.progress / 100f,
            cols = cols,
            rows = rows
        )
        val sensitivity = slSens.progress / 100f

        val intent = Intent(this, OverlayService::class.java).apply {
            putExtra(OverlayService.EXTRA_RESULT_CODE, resultCode)
            putExtra(OverlayService.EXTRA_DATA, data)
            putExtra(OverlayService.EXTRA_GRID, config)
            putExtra(OverlayService.EXTRA_SENSITIVITY, sensitivity)
        }
        startForegroundService(intent)
        serviceRunning = true
        updateStatus("실행 중 — 게임으로 이동하세요\n버튼 바가 화면 위에 떠있습니다")
        btnStart.isEnabled = false
        btnStop.isEnabled = true
    }

    private fun stopService() {
        stopService(Intent(this, OverlayService::class.java))
        serviceRunning = false
        btnStart.isEnabled = true
        btnStop.isEnabled = false
        updateStatus("중지됨")
    }

    private fun updateStatus(msg: String) { tvStatus.text = msg }
}
