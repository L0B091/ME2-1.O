package com.me2.android.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import com.me2.android.LoginActivity
import com.me2.android.MainActivity
import com.me2.android.R
import com.me2.android.data.SessionStorage

/**
 * Widget redondo tipo reloj: avatar teaser + hora + temperatura (última conocida / placeholder).
 * Tap → abre la app. Respetar switch de bitácora (ocultar si Off).
 */
class Me2HomeWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { id -> updateOne(context, appWidgetManager, id) }
    }

    override fun onEnabled(context: Context) {
        // no-op
    }

    // Al redimensionar: recalcular el cuadrado 1:1 (sin deformar el anillo).
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) {
        updateOne(context, manager, appWidgetId)
    }

    companion object {
        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, Me2HomeWidgetProvider::class.java))
            if (ids.isEmpty()) return
            ids.forEach { updateOne(context, manager, it) }
        }

        fun updateOne(context: Context, manager: AppWidgetManager, appWidgetId: Int) {
            val storage = SessionStorage(context)
            val views = RemoteViews(context.packageName, R.layout.widget_me2_home)
            if (!storage.isHomeWidgetEnabled()) {
                views.setViewVisibility(R.id.widgetRoot, View.INVISIBLE)
                manager.updateAppWidget(appWidgetId, views)
                return
            }
            views.setViewVisibility(R.id.widgetRoot, View.VISIBLE)
            views.setTextViewText(R.id.widgetTemp, storage.loadLastTemperature())
            val square = squareFor(context, manager, appWidgetId)
            val density = context.resources.displayMetrics.density
            views.setViewPadding(
                R.id.widgetRoot,
                (square.padH * density).toInt(), (square.padV * density).toInt(),
                (square.padH * density).toInt(), (square.padV * density).toInt()
            )
            runCatching {
                views.setImageViewBitmap(R.id.widgetAvatar, WidgetClipFrames.nextAvatarBitmap(context, (square.side * density * 0.8f).toInt()))
            }.onFailure { views.setImageViewResource(R.id.widgetAvatar, R.drawable.me2_mark) }

            val openApp = Intent(context, resolveLaunchClass(storage)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pending = PendingIntent.getActivity(
                context,
                appWidgetId,
                openApp,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widgetRoot, pending)
            manager.updateAppWidget(appWidgetId, views)
        }

        private fun squareFor(context: Context, manager: AppWidgetManager, appWidgetId: Int): WidgetSizing.Square {
            val o = manager.getAppWidgetOptions(appWidgetId)
            val portrait = context.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
            return WidgetSizing.fromOptions(
                o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 110), o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 110),
                o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 110), o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 110),
                portrait
            )
        }

        private fun resolveLaunchClass(storage: SessionStorage): Class<*> {
            return if (storage.loadUser() != null) MainActivity::class.java else LoginActivity::class.java
        }
    }
}
