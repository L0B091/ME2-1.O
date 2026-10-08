package com.me2.android.media

import com.me2.android.gallery.ClipPicker
import com.me2.android.gallery.GalleryClip
import kotlin.random.Random

/**
 * Loop de reposo sin cortes: el reproductor siempre tiene encolado como próximo ítem el siguiente clip del loop
 * (01_LOOP_NEUTRAL con red, 09_SIN_CONEXION sin red), elegido al azar sin repetir el que suena. Así el próximo clip
 * se precarga y la transición es continua (sin cuadro congelado ni recarga entre clips).
 *
 * Lógica pura sobre [Playlist] (en la app, la lista de ExoPlayer/Media3); testeable en JVM.
 * Las reacciones/presentación/despertador reemplazan la lista entera (setMediaItem), así que siguen interrumpiendo.
 */
object IdleLoopQueue {
    /** Vista mínima de la lista del reproductor. */
    interface Playlist {
        val currentIndex: Int
        val size: Int
        fun idAt(index: Int): String?
        fun append(clip: GalleryClip)
        /** Quita los ítems [from, to). */
        fun removeRange(from: Int, to: Int)
    }

    /** Próximo clip del loop sin repetir el actual (con un solo clip, se repite: loop continuo). */
    fun pickNext(gallery: List<GalleryClip>, currentId: String?, random: Random = Random.Default): GalleryClip? =
        ClipPicker.pickRandom(gallery.distinctBy { it.id }, gallery.firstOrNull { it.id == currentId }, random)

    /** Deja exactamente un ítem encolado después del actual. Devuelve el encolado (null si la galería está vacía). */
    fun queueNext(playlist: Playlist, gallery: List<GalleryClip>, random: Random = Random.Default): GalleryClip? {
        if (playlist.size == 0) return null
        val current = playlist.currentIndex.coerceIn(0, playlist.size - 1)
        if (playlist.size > current + 1) playlist.removeRange(current + 1, playlist.size)
        val next = pickNext(gallery, playlist.idAt(current), random) ?: return null
        playlist.append(next)
        return next
    }

    /** Transición automática al clip encolado: se descartan los ya reproducidos y se encola el siguiente. */
    fun onAdvanced(playlist: Playlist, gallery: List<GalleryClip>, random: Random = Random.Default): GalleryClip? {
        if (playlist.size == 0) return null
        val current = playlist.currentIndex.coerceIn(0, playlist.size - 1)
        if (current > 0) playlist.removeRange(0, current)
        return queueNext(playlist, gallery, random)
    }

    /**
     * La galería cambió (red ↔ sin red): si el encolado no pertenece a la galería nueva se reemplaza, así el cambio
     * ocurre al terminar el clip en curso, sin cortarlo. Si ya hay uno válido, no se toca.
     */
    fun retarget(playlist: Playlist, gallery: List<GalleryClip>, random: Random = Random.Default): GalleryClip? {
        if (playlist.size == 0) return null
        val current = playlist.currentIndex.coerceIn(0, playlist.size - 1)
        val queuedId = if (playlist.size == current + 2) playlist.idAt(current + 1) else null
        if (queuedId != null && gallery.any { it.id == queuedId }) return gallery.first { it.id == queuedId }
        return queueNext(playlist, gallery, random)
    }
}
