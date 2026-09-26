package com.me2.android.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.me2.android.LoginActivity
import com.me2.android.MainActivity
import com.me2.android.R
import com.me2.android.data.SessionStorage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
            val clock = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            views.setTextViewText(R.id.widgetTime, clock)
            views.setTextViewText(R.id.widgetTemp, storage.loadLastTemperature())
            views.setImageViewResource(R.id.widgetAvatar, R.drawable.me2_mark)

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

        private fun resolveLaunchClass(storage: SessionStorage): Class<*> {
            return if (storage.loadUser() != null) MainActivity::class.java else LoginActivity::class.java
        }
    }
}
