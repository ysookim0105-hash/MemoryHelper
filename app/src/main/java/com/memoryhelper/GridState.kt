package com.memoryhelper

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class GridConfig(
    val leftFrac: Float,   // 0..1 fraction of screen width
    val topFrac: Float,
    val rightFrac: Float,
    val bottomFrac: Float,
    val cols: Int,
    val rows: Int
) : Parcelable

class GridState(val config: GridConfig) {
    val total = config.cols * config.rows

    // -1 = not yet seen, 0 = baseline captured, 1..N = card type ID
    val cards = IntArray(total) { -1 }
    val baselines = LongArray(total) { 0L }

    var baselineCaptured = false
    private var nextId = 1
    private val knownHashes = mutableListOf<Pair<Long, Int>>() // hash → cardId

    fun setBaseline(idx: Int, hash: Long) {
        baselines[idx] = hash
        cards[idx] = 0
    }

    fun isOpen(idx: Int, hash: Long, sensitivity: Float): Boolean {
        if (!baselineCaptured) return false
        val dist = PHasher.distance(baselines[idx], hash)
        return dist > (64 * sensitivity).toInt()
    }

    fun assignCard(idx: Int, hash: Long, matchThreshold: Int): Int {
        val match = knownHashes.minByOrNull { (h, _) -> PHasher.distance(h, hash) }
        val id = if (match != null && PHasher.distance(match.first, hash) <= matchThreshold) {
            match.second
        } else {
            val newId = nextId++
            knownHashes.add(hash to newId)
            newId
        }
        cards[idx] = id
        return id
    }

    val knownCount get() = cards.count { it > 0 }

    fun pairsFound(): List<Pair<Int, List<Int>>> =
        cards.mapIndexed { i, v -> v to i }
            .filter { it.first > 0 }
            .groupBy { it.first }
            .filter { it.value.size >= 2 }
            .map { (id, entries) -> id to entries.map { it.second } }
}
