package com.pulsemix.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.pulsemix.app.Graph
import com.pulsemix.app.MainActivity
import com.pulsemix.app.R
import com.pulsemix.app.player.PlayerCore
import com.pulsemix.app.ui.ArtworkCache
import kotlin.concurrent.thread

/**
 * Widgets d'écran d'accueil : un lecteur (jaquette, titre, artiste,
 * transport) et une file d'attente défilante. Tous deux se rafraîchissent
 * à chaque changement d'état via [PulseWidgets.refresh].
 */
object PulseWidgets {

    const val ACTION_PREV = "com.pulsemix.app.widget.PREV"
    const val ACTION_TOGGLE = "com.pulsemix.app.widget.TOGGLE"
    const val ACTION_NEXT = "com.pulsemix.app.widget.NEXT"
    const val ACTION_PLAY_AT = "com.pulsemix.app.widget.PLAY_AT"
    const val EXTRA_INDEX = "index"

    /** Redessine tous les widgets posés (appelé quand l'état change). */
    fun refresh(context: Context) {
        val app = context.applicationContext
        val mgr = AppWidgetManager.getInstance(app) ?: return
        try {
            val playerIds = mgr.getAppWidgetIds(
                ComponentName(app, PlayerWidget::class.java)
            )
            if (playerIds.isNotEmpty()) {
                PlayerWidget().onUpdate(app, mgr, playerIds)
            }
            val queueIds = mgr.getAppWidgetIds(
                ComponentName(app, QueueWidget::class.java)
            )
            if (queueIds.isNotEmpty()) {
                // Invalide les données de la liste puis l'en-tête
                mgr.notifyAppWidgetViewDataChanged(queueIds, R.id.queue_list)
                QueueWidget().onUpdate(app, mgr, queueIds)
            }
        } catch (_: Exception) {
        }
    }

    /** PendingIntent d'une action de transport vers le provider donné. */
    fun action(context: Context, cls: Class<*>, action: String): PendingIntent =
        PendingIntent.getBroadcast(
            context, action.hashCode(),
            Intent(context, cls).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    fun openApp(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    fun durationLabel(ms: Long): String {
        if (ms <= 0) return ""
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }
}

/** Widget lecteur : jaquette, titre, artiste et transport. */
class PlayerWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        manager: AppWidgetManager,
        ids: IntArray
    ) {
        val track = PlayerCore.currentTrack.value
        val playing = PlayerCore.isPlaying.value

        val views = RemoteViews(context.packageName, R.layout.widget_player)
        views.setTextViewText(
            R.id.widget_title,
            track?.title ?: context.getString(R.string.widget_nothing)
        )
        views.setTextViewText(
            R.id.widget_artist,
            track?.artist?.takeIf { it.isNotBlank() }
                ?: context.getString(R.string.widget_tap_to_open)
        )
        views.setImageViewResource(
            R.id.widget_toggle,
            if (playing) R.drawable.ic_widget_pause else R.drawable.ic_widget_play
        )
        views.setImageViewResource(R.id.widget_art, R.drawable.ic_widget_note)

        val cls = PlayerWidget::class.java
        views.setOnClickPendingIntent(
            R.id.widget_prev, PulseWidgets.action(context, cls, PulseWidgets.ACTION_PREV)
        )
        views.setOnClickPendingIntent(
            R.id.widget_toggle,
            PulseWidgets.action(context, cls, PulseWidgets.ACTION_TOGGLE)
        )
        views.setOnClickPendingIntent(
            R.id.widget_next, PulseWidgets.action(context, cls, PulseWidgets.ACTION_NEXT)
        )
        views.setOnClickPendingIntent(R.id.widget_open, PulseWidgets.openApp(context))
        views.setOnClickPendingIntent(R.id.widget_title, PulseWidgets.openApp(context))
        views.setOnClickPendingIntent(R.id.widget_art, PulseWidgets.openApp(context))
        manager.updateAppWidget(ids, views)

        // Jaquette : lecture disque/tag hors du thread principal, puis
        // seconde passe sur le widget une fois le bitmap prêt.
        val uri = track?.uri ?: return
        val app = context.applicationContext
        thread(name = "widget-art") {
            val bmp = try {
                ArtworkCache.loadBlocking(app, uri, 256)
            } catch (_: Exception) {
                null
            } ?: return@thread
            try {
                val v = RemoteViews(app.packageName, R.layout.widget_player)
                v.setImageViewBitmap(R.id.widget_art, bmp)
                AppWidgetManager.getInstance(app)
                    ?.partiallyUpdateAppWidget(ids, v)
            } catch (_: Exception) {
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            PulseWidgets.ACTION_PREV -> {
                Graph.init(context)
                PlayerCore.previous()
            }
            PulseWidgets.ACTION_TOGGLE -> {
                Graph.init(context)
                PlayerCore.togglePlayPause()
            }
            PulseWidgets.ACTION_NEXT -> {
                Graph.init(context)
                PlayerCore.next()
            }
            else -> super.onReceive(context, intent)
        }
        if (intent.action?.startsWith("com.pulsemix.app.widget.") == true) {
            PulseWidgets.refresh(context)
        }
    }
}

/** Widget file d'attente : liste défilante, morceau en cours surligné. */
class QueueWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        manager: AppWidgetManager,
        ids: IntArray
    ) {
        val queue = PlayerCore.queue.value
        val current = PlayerCore.currentTrack.value?.uri
        val pos = queue.indexOfFirst { it.uri == current }

        for (id in ids) {
            val views = RemoteViews(context.packageName, R.layout.widget_queue)
            views.setTextViewText(
                R.id.queue_header,
                if (queue.isEmpty()) context.getString(R.string.widget_queue_title)
                else "File d'attente (${(pos + 1).coerceAtLeast(1)}/${queue.size})"
            )
            views.setOnClickPendingIntent(
                R.id.queue_header, PulseWidgets.openApp(context)
            )

            // Adaptateur distant : la liste est peuplée par QueueWidgetService
            val svc = Intent(context, QueueWidgetService::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            svc.data = android.net.Uri.parse(svc.toUri(Intent.URI_INTENT_SCHEME))
            views.setRemoteAdapter(R.id.queue_list, svc)
            views.setEmptyView(R.id.queue_list, R.id.queue_empty)

            // Un tap sur une ligne lit le morceau : gabarit + fillInIntent
            val template = PendingIntent.getBroadcast(
                context, 0,
                Intent(context, QueueWidget::class.java)
                    .setAction(PulseWidgets.ACTION_PLAY_AT),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            views.setPendingIntentTemplate(R.id.queue_list, template)
            manager.updateAppWidget(id, views)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == PulseWidgets.ACTION_PLAY_AT) {
            val i = intent.getIntExtra(PulseWidgets.EXTRA_INDEX, -1)
            if (i >= 0) {
                Graph.init(context)
                PlayerCore.playQueueItem(i)
            }
            PulseWidgets.refresh(context)
        } else {
            super.onReceive(context, intent)
        }
    }
}
