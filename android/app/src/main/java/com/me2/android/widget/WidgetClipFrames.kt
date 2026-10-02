package com.me2.android.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import android.media.MediaMetadataRetriever
import android.util.Log
import com.me2.android.R
import com.me2.android.gallery.ClipCatalog
import com.me2.android.gallery.ClipPicker
import kotlin.math.min

/**
 * Hook de rotación de clips para el widget: en cada actualización periódica (updatePeriodMillis, sin alarmas extra
 * → batería) elige un clip al azar de ClipCatalog y extrae un fotograma (o varios, para un futuro GIF/frames).
 * Si algo falla, usa la imagen de respaldo. Siempre recorta en círculo (el widget nunca se deforma).
 */
object WidgetClipFrames {
    private const val TAG = "Me2WidgetFrames"
    private const val PREFS = "me2_widget_frames"
    private const val KEY_LAST = "last_clip_id"

    fun nextAvatarBitmap(context: Context, sizePx: Int): Bitmap {
        val frame = runCatching { randomClipFrame(context) }.onFailure { Log.w(TAG, "frame fallback: ${it.javaClass.simpleName}") }.getOrNull()
        val source = frame ?: BitmapFactory.decodeResource(context.resources, R.drawable.me2_mark)
        return circleCrop(source, sizePx.coerceIn(64, 512))
    }

    /** Fotogramas espaciados de un clip (base para animación tipo GIF en el widget). */
    fun framesOf(context: Context, uri: android.net.Uri, count: Int): List<Bitmap> {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            val durUs = (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) * 1000L
            (0 until count).mapNotNull { i -> r.getFrameAtTime(durUs * (i + 1) / (count + 1), MediaMetadataRetriever.OPTION_CLOSEST_SYNC) }
        } finally {
            runCatching { r.release() }
        }
    }

    private fun randomClipFrame(context: Context): Bitmap? {
        val clips = ClipCatalog(context).listAll().distinctBy { it.id }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previous = clips.firstOrNull { it.id == prefs.getString(KEY_LAST, null) }
        val clip = ClipPicker.pickRandom(clips, previous) ?: return null
        prefs.edit().putString(KEY_LAST, clip.id).apply()
        return framesOf(context, clip.uri, 1).firstOrNull()
    }

    fun circleCrop(src: Bitmap, size: Int): Bitmap {
        val side = min(src.width, src.height)
        val square = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
        val scaled = Bitmap.createScaledBitmap(square, size, size, true)
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = BitmapShader(scaled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        Canvas(out).drawCircle(size / 2f, size / 2f, size / 2f, paint)
        return out
    }
}
