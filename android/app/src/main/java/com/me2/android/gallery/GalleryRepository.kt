package com.me2.android.gallery

import android.net.Uri
import java.io.File

/**
 * Extension point for the future video gallery UI and drop-in clip packs.
 *
 * Directory convention (documented + enforced by [ClipCatalog]):
 * - Bundled:   assets/videos/{mood}/ (mp4 or webm)
 * - Runtime:   context.filesDir/gallery/{mood}/ (mp4 or webm)
 *
 * Moods: loop_neutral, presentacion, calida, alegre, atenta, aliviada, agradecida
 *
 * Adding clips later = drop files into those folders; no architecture rewrite.
 * Gallery UI can call [listAll] / [listByMood] without touching ExoPlayer wiring.
 */
interface GalleryRepository {
    /** Creates filesDir/gallery/{mood}/ so drop-in works without manual mkdir. */
    fun ensureDirs()

    /** Absolute path of the runtime gallery root (filesDir/gallery). */
    fun runtimeGalleryRoot(): File

    fun listAll(): List<GalleryClip>

    fun listByMood(mood: String): List<GalleryClip>

    fun findById(id: String): GalleryClip?

    fun playbackUri(clip: GalleryClip): Uri
}
