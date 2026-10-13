package com.memoryhelper

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.util.DisplayMetrics
import kotlinx.coroutines.*

class CardDetector(
    private val projection: MediaProjection,
    private val metrics: DisplayMetrics,
    var sensitivity: Float = 0.20f,      // fraction of 64 bits that must differ to count as "open"
    private val onUpdate: (GridState) -> Unit
) {
    var gridState: GridState? = null
        private set

    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Track open/close transitions per cell
    private val cellWasOpen = mutableMapOf<Int, Boolean>()
    private val openBuffer = mutableMapOf<Int, MutableList<Long>>() // frames while open

    fun setGrid(config: GridConfig) {
        gridState = GridState(config)
        cellWasOpen.clear()
        openBuffer.clear()
    }

    fun start() {
        val state = gridState ?: return
        if (job?.isActive == true) return
        setupCapture()
        job = scope.launch {
            delay(600) // let VirtualDisplay initialize
            captureBaseline(state)
            while (isActive) {
                delay(180)
                processFrame(state)
            }
        }
    }

    fun stop() {
        job?.cancel()
        virtualDisplay?.release()
        imageReader?.close()
        virtualDisplay = null
        imageReader = null
    }

    private fun setupCapture() {
        imageReader = ImageReader.newInstance(
            metrics.widthPixels, metrics.heightPixels,
            PixelFormat.RGBA_8888, 2
        )
        virtualDisplay = projection.createVirtualDisplay(
            "MemoryHelper",
            metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, null
        )
    }

    private suspend fun captureBaseline(state: GridState) {
        val bmp = captureScreen() ?: return
        for (i in 0 until state.total) {
            val cell = cropCell(bmp, i, state.config) ?: continue
            state.setBaseline(i, PHasher.hash(cell))
            cell.recycle()
        }
        state.baselineCaptured = true
        bmp.recycle()
    }

    private suspend fun processFrame(state: GridState) {
        val bmp = captureScreen() ?: return
        var changed = false
        val threshold = (64 * sensitivity).toInt()

        for (i in 0 until state.total) {
            if (state.cards[i] > 0) continue // already identified

            val cell = cropCell(bmp, i, state.config) ?: continue
            val hash = PHasher.hash(cell)
            cell.recycle()

            val open = state.isOpen(i, hash, sensitivity)
            val wasOpen = cellWasOpen[i] ?: false

            when {
                open && !wasOpen -> {
                    // Card just flipped open — start buffering
                    openBuffer[i] = mutableListOf(hash)
                }
                open && wasOpen -> {
                    openBuffer[i]?.add(hash)
                    // After 3 stable frames (~540 ms), lock in the reading
                    if ((openBuffer[i]?.size ?: 0) >= 3) {
                        val bestHash = openBuffer[i]!!.first()
                        state.assignCard(i, bestHash, threshold)
                        openBuffer.remove(i)
                        changed = true
                    }
                }
                !open && wasOpen -> {
                    // Card closed before we got 3 frames — use what we have
                    openBuffer[i]?.firstOrNull()?.let { h ->
                        state.assignCard(i, h, threshold)
                        changed = true
                    }
                    openBuffer.remove(i)
                }
            }
            cellWasOpen[i] = open
        }

        bmp.recycle()
        if (changed) withContext(Dispatchers.Main) { onUpdate(state) }
    }

    private fun captureScreen(): Bitmap? {
        val image = imageReader?.acquireLatestImage() ?: return null
        return try {
            val plane = image.planes[0]
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val w = metrics.widthPixels
            val h = metrics.heightPixels
            val bmp = Bitmap.createBitmap(rowStride / pixelStride, h, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(plane.buffer)
            if (rowStride / pixelStride == w) bmp
            else Bitmap.createBitmap(bmp, 0, 0, w, h).also { bmp.recycle() }
        } finally {
            image.close()
        }
    }

    private fun cropCell(bmp: Bitmap, idx: Int, cfg: GridConfig): Bitmap? {
        val col = idx % cfg.cols
        val row = idx / cfg.cols
        val cellW = ((cfg.rightFrac - cfg.leftFrac) * bmp.width / cfg.cols).toInt()
        val cellH = ((cfg.bottomFrac - cfg.topFrac) * bmp.height / cfg.rows).toInt()
        val x = (cfg.leftFrac * bmp.width + col * cellW).toInt()
        val y = (cfg.topFrac * bmp.height + row * cellH).toInt()
        if (x < 0 || y < 0 || x + cellW > bmp.width || y + cellH > bmp.height
            || cellW <= 0 || cellH <= 0) return null
        return Bitmap.createBitmap(bmp, x, y, cellW, cellH)
    }
}
