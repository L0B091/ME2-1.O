package com.me2.android.media

import com.me2.android.gallery.GalleryClip
import kotlin.random.Random

/**
 * Selección del loop neutral menos repetitiva:
 * - aleatoria con pesos ([weightOf]);
 * - nunca repite ninguno de los últimos [recentWindow] clips (si la galería es chica, se acota a size-1);
 * - "loops largos tranquilos" (NEUTRAL_LARGO_*: café, estirarse…) aparecen de vez en cuando
 *   (~[longChance]) y nunca dos veces seguidas. Sin clips largos, funciona igual con los actuales.
 */
class NeutralLoopScheduler(
    private val recentWindow: Int = 3,
    private val longChance: Double = 0.25,
    private val weightOf: (GalleryClip) -> Double = { 1.0 }
) {
    private val history = ArrayDeque<String>()

    val recent: List<String> get() = history.toList()

    fun pick(gallery: List<GalleryClip>, currentId: String?, random: Random = Random.Default): GalleryClip? {
        val clips = gallery.distinctBy { it.id }
        if (clips.isEmpty()) return null
        if (currentId != null && history.lastOrNull() != currentId) remember(currentId)
        val chosen = choose(clips, history.toList(), random)
        chosen?.let { remember(it.id) }
        return chosen
    }

    private fun remember(id: String) {
        history.addLast(id)
        while (history.size > HISTORY_MAX) history.removeFirst()
    }

    internal fun choose(clips: List<GalleryClip>, recentIds: List<String>, random: Random): GalleryClip? {
        if (clips.isEmpty()) return null
        if (clips.size == 1) return clips.first()
        val (long, short) = clips.partition { isLong(it) }
        val lastId = recentIds.lastOrNull()
        val lastWasLong = lastId != null && clips.firstOrNull { it.id == lastId }?.let(::isLong) ?: (lastId?.let(::isLongName) == true)
        val wantLong = long.isNotEmpty() && !lastWasLong && (short.isEmpty() || random.nextDouble() < longChance)
        val primary = if (wantLong) long else short.ifEmpty { long }
        val pool = freshPool(primary, recentIds).ifEmpty { freshPool(clips.filterNot { lastWasLong && isLong(it) }, recentIds) }
            .ifEmpty { clips.filter { it.id != lastId } }
        return weightedPick(pool, random)
    }

    /** Excluye los recientes; la ventana se acota para que siempre quede al menos un candidato. */
    private fun freshPool(pool: List<GalleryClip>, recentIds: List<String>): List<GalleryClip> {
        if (pool.isEmpty()) return pool
        val window = minOf(recentWindow, recentIds.size)
        for (w in window downTo 1) {
            val excluded = recentIds.takeLast(w).toSet()
            val left = pool.filter { it.id !in excluded }
            if (left.isNotEmpty()) return left
        }
        return pool.filter { it.id != recentIds.lastOrNull() }
    }

    private fun weightedPick(pool: List<GalleryClip>, random: Random): GalleryClip? {
        if (pool.isEmpty()) return null
        val weights = pool.map { weightOf(it).coerceAtLeast(0.0) }
        val total = weights.sum()
        if (total <= 0.0) return pool[random.nextInt(pool.size)]
        var r = random.nextDouble() * total
        pool.forEachIndexed { i, c -> r -= weights[i]; if (r < 0) return c }
        return pool.last()
    }

    companion object {
        private const val HISTORY_MAX = 8
        const val LONG_MARKER = "NEUTRAL_LARGO"
        fun isLongName(name: String): Boolean = name.uppercase().contains(LONG_MARKER)
        fun isLong(clip: GalleryClip): Boolean =
            isLongName(clip.id) || isLongName(clip.displayName) || isLongName(clip.uri.toString())
    }
}
