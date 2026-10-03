package com.me2.android.media

import android.content.Context

/** Último clip reproducido en el avatar, persistido: la anti-repetición inmediata sobrevive cierre y reinicio. */
class MediaHistory(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("me2_media_history", Context.MODE_PRIVATE)

    fun record(clipId: String) {
        val v1 = clipId.takeIf { it.startsWith("me2:") }?.substringAfterLast('/')?.substringBeforeLast('.')?.uppercase()
        prefs.edit().putString(KEY_CLIP, clipId).apply { if (v1 != null) putString(KEY_ID, v1) }.commit()
    }

    /** id V1 (p. ej. "NEUTRAL_002") del último recurso de la biblioteca. */
    fun lastId(): String? = prefs.getString(KEY_ID, null)

    /** id de reproducción (GalleryClip.id) del último clip, incluidos los de res/raw. */
    fun lastClipId(): String? = prefs.getString(KEY_CLIP, null)

    private companion object {
        const val KEY_ID = "last_media_id"
        const val KEY_CLIP = "last_clip_id"
    }
}
