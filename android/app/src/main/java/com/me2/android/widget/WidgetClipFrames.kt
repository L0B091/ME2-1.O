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
import com.me2.android.media.MediaCategoria
import com.me2.android.media.MediaLibrary
import com.me2.android.media.MediaPermisos
import com.me2.android.media.MediaRequest
import com.me2.android.media.MediaResource
import com.me2.android.media.MediaSelector
import com.me2.android.media.MediaTipo
import kotlin.math.min

/**
 * Hook de rotación de clips para el widget: en cada actualización periódica (updatePeriodMillis, sin alarmas extra
 * → batería) elige un clip al azar de la biblioteca V1 (MediaLibrary) y extrae un fotograma (o varios, para un futuro GIF/frames).
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

    /**
     * Widget pasivo: pide la categoría WIDGET (GIF/imagen/video) al selector; si no hay, cae a LOOP_NEUTRAL y
     * reacciones no adultas. Nunca premium/adulto, nunca presentación. Sin repetir el anterior si hay más de uno.
     */
    private fun randomClipFrame(context: Context): Bitmap? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previous = prefs.getString(KEY_LAST, null)
        val library = MediaLibrary(context)
        val selection = MediaSelector.select(library.recursos(), WIDGET_REQUEST, MediaPermisos(), previous)
        if (selection != null) {
            val r = selection.recurso
            prefs.edit().putString(KEY_LAST, r.id).apply()
            return when (r.tipo) {
                MediaTipo.GIF, MediaTipo.IMAGEN -> decodeStill(context, library, r)
                else -> framesOf(context, library.uri(r), 1).firstOrNull()
            }
        }
        // Sin biblioteca V1: demos de ClipCatalog (res/raw).
        val clips = ClipCatalog(context).listByMood(ClipCatalog.MOOD_LOOP_NEUTRAL).distinctBy { it.id }
        val clip = ClipPicker.pickRandom(clips, clips.firstOrNull { it.id == previous }) ?: return null
        prefs.edit().putString(KEY_LAST, clip.id).apply()
        return framesOf(context, clip.uri, 1).firstOrNull()
    }

    /** Primer fotograma de un GIF/imagen (RemoteViews no anima GIF; se rota en cada actualización). */
    private fun decodeStill(context: Context, library: MediaLibrary, r: MediaResource): Bitmap? = runCatching {
        when (r.origen) {
            MediaResource.Origen.ASSETS -> context.assets.open("${MediaLibrary.ROOT}/${r.archivo}").use(BitmapFactory::decodeStream)
            MediaResource.Origen.FILES_DIR -> BitmapFactory.decodeFile(java.io.File(library.filesRoot(), r.archivo).path)
        }
    }.getOrNull()

    val WIDGET_REQUEST = MediaRequest(
        MediaCategoria.WIDGET,
        tipos = setOf(MediaTipo.GIF, MediaTipo.IMAGEN, MediaTipo.VIDEO),
        fallbacks = listOf(MediaCategoria.LOOP_NEUTRAL, MediaCategoria.REACCION)
    )

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
