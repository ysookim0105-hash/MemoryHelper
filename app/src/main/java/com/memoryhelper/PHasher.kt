package com.memoryhelper

import android.graphics.Bitmap
import kotlin.math.PI
import kotlin.math.cos

object PHasher {
    private const val SIZE = 32
    private const val BLOCK = 8

    fun hash(bitmap: Bitmap): Long {
        val scaled = Bitmap.createScaledBitmap(bitmap, SIZE, SIZE, true)
        val gray = FloatArray(SIZE * SIZE)
        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) {
                val p = scaled.getPixel(x, y)
                gray[y * SIZE + x] =
                    0.299f * ((p shr 16) and 0xff) +
                    0.587f * ((p shr 8) and 0xff) +
                    0.114f * (p and 0xff)
            }
        }
        scaled.recycle()

        val dct = dct2D(gray)

        // Top-left BLOCK×BLOCK of DCT result
        val block = FloatArray(BLOCK * BLOCK) { dct[(it / BLOCK) * SIZE + (it % BLOCK)] }
        val mean = block.sum() / block.size
        var hash = 0L
        block.forEachIndexed { i, v -> if (v > mean) hash = hash or (1L shl i) }
        return hash
    }

    fun distance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    private fun dct2D(data: FloatArray): FloatArray {
        val tmp = FloatArray(SIZE * SIZE)
        // DCT along rows
        for (y in 0 until SIZE) {
            val row = dct1D(FloatArray(SIZE) { data[y * SIZE + it] })
            for (x in 0 until SIZE) tmp[y * SIZE + x] = row[x]
        }
        // DCT along columns
        val out = FloatArray(SIZE * SIZE)
        for (x in 0 until SIZE) {
            val col = dct1D(FloatArray(SIZE) { tmp[it * SIZE + x] })
            for (y in 0 until SIZE) out[y * SIZE + x] = col[y]
        }
        return out
    }

    private fun dct1D(v: FloatArray): FloatArray {
        val n = v.size
        return FloatArray(n) { k ->
            var s = 0.0
            for (i in 0 until n) s += v[i] * cos(PI * k * (2 * i + 1) / (2 * n))
            s.toFloat()
        }
    }
}
