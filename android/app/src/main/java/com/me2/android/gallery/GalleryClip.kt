package com.me2.android.gallery

import android.net.Uri

/**
 * Stable clip handle for ExoPlayer playback.
 *
 * Sources (priority when listing a mood):
 * 1) [Source.FILES_DIR] — drop-in under filesDir/gallery/{mood}/
 * 2) [Source.ASSETS] — bundled under assets/videos/{mood}/
 * 3) [Source.RAW] — demo fallbacks in res/raw
 *
 * Voice (spoken line) is only expected on welcome/presentacion clips;
 * other moods should carry ambient/onomatopoeia audio only.
 */
data class GalleryClip(
    val id: String,
    val mood: String,
    val displayName: String,
    val uri: Uri,
    val source: Source,
    val carriesVoice: Boolean = false,
    val rawResId: Int? = null
) {
    enum class Source { FILES_DIR, ASSETS, RAW }
}
