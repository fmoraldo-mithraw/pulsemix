package com.pulsemix.app.widget

import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.pulsemix.app.Graph
import com.pulsemix.app.R
import com.pulsemix.app.data.Track
import com.pulsemix.app.player.PlayerCore

/** Fournit les lignes de la file d'attente au widget [QueueWidget]. */
class QueueWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        QueueFactory(applicationContext)
}

private class QueueFactory(private val context: Context) :
    RemoteViewsService.RemoteViewsFactory {

    private var items: List<Track> = emptyList()
    private var currentUri: String? = null

    override fun onCreate() {
        Graph.init(context)
    }

    /** Appelé à chaque notifyAppWidgetViewDataChanged. */
    override fun onDataSetChanged() {
        items = PlayerCore.queue.value
        currentUri = PlayerCore.currentTrack.value?.uri
    }

    override fun onDestroy() {
        items = emptyList()
    }

    override fun getCount(): Int = items.size

    override fun getViewAt(position: Int): RemoteViews {
        val v = RemoteViews(context.packageName, R.layout.widget_queue_item)
        val t = items.getOrNull(position) ?: return v
        val isCurrent = t.uri == currentUri

        v.setTextViewText(R.id.item_index, if (isCurrent) "▶" else "${position + 1}")
        v.setTextViewText(R.id.item_title, t.title)
        v.setTextViewText(R.id.item_artist, t.artist)
        v.setViewVisibility(
            R.id.item_artist,
            if (t.artist.isBlank()) android.view.View.GONE
            else android.view.View.VISIBLE
        )
        v.setTextViewText(R.id.item_duration, PulseWidgets.durationLabel(t.durationMs))

        // Le morceau en cours ressort en couleur d'accent
        val accent = 0xFFB497FF.toInt()
        val normal = 0xFFEDE9F7.toInt()
        val dim = 0xFFA9A2C4.toInt()
        v.setTextColor(R.id.item_title, if (isCurrent) accent else normal)
        v.setTextColor(R.id.item_index, if (isCurrent) accent else dim)
        v.setTextColor(R.id.item_artist, if (isCurrent) accent else dim)
        v.setTextColor(R.id.item_duration, if (isCurrent) accent else dim)

        // Complète le gabarit posé par le widget : lire ce morceau
        v.setOnClickFillInIntent(
            R.id.item_root,
            Intent().putExtra(PulseWidgets.EXTRA_INDEX, position)
        )
        return v
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long =
        items.getOrNull(position)?.uri?.hashCode()?.toLong() ?: position.toLong()

    override fun hasStableIds(): Boolean = true
}
