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
    const val ACTION_MIX = "com.pulsemix.app.widget.MIX"
    const val ACTION_DJ = "com.pulsemix.app.widget.DJ"
    const val ACTION_SEEK = "com.pulsemix.app.widget.SEEK"
    const val EXTRA_INDEX = "index"
    const val EXTRA_FRACTION = "fraction"

    /**
     * Zones tactiles de la barre de progression. Un widget ne peut pas
     * contenir de curseur que l'on glisse : la barre est donc découpée en
     * tranches, chacune renvoyant à sa position dans le morceau.
     */
    val SEEK_ZONES = intArrayOf(
        R.id.seek0, R.id.seek1, R.id.seek2, R.id.seek3, R.id.seek4,
        R.id.seek5, R.id.seek6, R.id.seek7, R.id.seek8, R.id.seek9,
        R.id.seek10, R.id.seek11, R.id.seek12, R.id.seek13, R.id.seek14,
        R.id.seek15, R.id.seek16, R.id.seek17, R.id.seek18, R.id.seek19
    )

    /**
     * Construit puis lance un enchaînement sans passer par l'appli :
     * « Flow continu » en mix classique, « Auto-DJ » avec le moteur DJ.
     * Le plan se calcule hors du thread principal (toute la
     * bibliothèque), le lancement revient sur le thread principal
     * (ExoPlayer l'exige). onDone libère le broadcast (goAsync).
     */
    fun startMix(context: Context, dj: Boolean, onDone: () -> Unit) {
        val app = context.applicationContext
        Graph.init(app)
        thread(name = "widget-mix") {
            var plan: com.pulsemix.app.mix.MixEngine.MixPlan? = null
            try {
                val store = Graph.store
                // La bibliothèque peut encore être en cours de chargement
                var waited = 0
                while (!store.loaded.value && waited < 8_000) {
                    Thread.sleep(100)
                    waited += 100
                }
                val all = store.tracks.value.filter { !it.excluded }
                val plans = com.pulsemix.app.mix.MixEngine.proposeMixes(all, dj = dj)
                plan = plans.find { it.id == if (dj) "auto" else "flow" }
                    ?: plans.firstOrNull()
            } catch (_: Exception) {
            }
            val chosen = plan
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try {
                    if (chosen != null) {
                        if (dj) PlayerCore.startDj(chosen)
                        else PlayerCore.startMix(chosen)
                        // Enchaîner sur un mix du même type à la fin
                        PlayerCore.setMixSpec(
                            PlayerCore.MixSpec(chosen.id, dj, null, null)
                        )
                    }
                    refresh(app)
                } catch (_: Exception) {
                } finally {
                    onDone()
                }
            }
        }
    }

    /**
     * Signature de la file affichée. La barre de progression se rafraîchit
     * toutes les 3 s ; recharger la liste entière à chaque fois faisait
     * reconstruire tous ses éléments par le RemoteViewsFactory alors que
     * seule la barre avait bougé. On ne l'invalide donc que si la file (ou
     * la place qu'on y occupe) a réellement changé.
     */
    private var queueSignature: Int? = null

    /**
     * Ce que le widget lecteur affiche en ce moment : URI du morceau et
     * état play/pause de la dernière reconstruction complète. C'est ce qui
     * permet au tick de 3 s de savoir que SEULE la progression a bougé.
     */
    @Volatile private var shownUri: String? = null
    @Volatile private var shownPlaying = false

    /**
     * Dernière jaquette chargée (URI → bitmap). Le tick reconstruisait le
     * widget entier, et chaque reconstruction relançait un thread
     * « widget-art » pour relire la même jaquette sur disque : on la garde
     * tant que le morceau ne change pas.
     */
    @Volatile private var artCache: Pair<String, android.graphics.Bitmap>? = null

    /** Jaquette en cache pour cet URI, sinon null (le thread la chargera). */
    internal fun cachedArt(uri: String): android.graphics.Bitmap? =
        artCache?.takeIf { it.first == uri }?.second

    internal fun rememberArt(uri: String, bmp: android.graphics.Bitmap) {
        artCache = uri to bmp
    }

    /** Mémorise ce que la reconstruction complète vient d'afficher. */
    internal fun rememberShown(uri: String?, playing: Boolean) {
        shownUri = uri
        shownPlaying = playing
    }

    /**
     * Chemin LÉGER du tick de progression (toutes les 3 s pendant la
     * lecture) : si morceau et état play/pause n'ont pas changé, seule la
     * barre a avancé — un partiallyUpdateAppWidget avec la barre seule
     * suffit, au lieu de reconstruire ~25 PendingIntent, tout le
     * RemoteViews et un thread de jaquette par tick.
     */
    fun refreshProgress(context: Context) {
        val app = context.applicationContext
        val track = com.pulsemix.app.player.PlayerCore.currentTrack.value
        val playing = com.pulsemix.app.player.PlayerCore.isPlaying.value
        if (track?.uri != shownUri || playing != shownPlaying) {
            // La structure a changé (morceau, pause…) : reconstruction
            // complète, qui remettra la mémoire à jour.
            refresh(app)
            return
        }
        val mgr = AppWidgetManager.getInstance(app) ?: return
        try {
            val ids = mgr.getAppWidgetIds(
                ComponentName(app, PlayerWidget::class.java)
            )
            if (ids.isEmpty()) return
            val views = RemoteViews(app.packageName, R.layout.widget_player)
            views.setProgressBar(
                R.id.widget_progress, 1000,
                (com.pulsemix.app.player.PlayerCore.progress.value * 1000)
                    .toInt().coerceIn(0, 1000),
                false
            )
            mgr.partiallyUpdateAppWidget(ids, views)
        } catch (_: Exception) {
        }
    }

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
                val sig = currentQueueSignature()
                if (sig != queueSignature) {
                    queueSignature = sig
                    mgr.notifyAppWidgetViewDataChanged(queueIds, R.id.queue_list)
                }
                QueueWidget().onUpdate(app, mgr, queueIds)
            }
        } catch (_: Exception) {
        }
    }

    private fun currentQueueSignature(): Int {
        val core = com.pulsemix.app.player.PlayerCore
        var h = core.currentTrack.value?.uri.hashCode()
        for (t in core.queue.value) h = h * 31 + t.uri.hashCode()
        return h
    }

    /** Force le rechargement de la liste au prochain [refresh]. */
    fun invalidateQueue() {
        queueSignature = null
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
        // Mémoire du chemin léger (refreshProgress) : tant que ce couple ne
        // change pas, les prochains ticks n'auront que la barre à pousser.
        PulseWidgets.rememberShown(track?.uri, playing)

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
        // Jaquette déjà en cache pour ce morceau : posée tout de suite dans
        // la même passe, sans thread ni relecture disque. Sinon,
        // pictogramme d'attente, et chargement asynchrone plus bas.
        val cachedArt = track?.uri?.let { PulseWidgets.cachedArt(it) }
        if (cachedArt != null) {
            views.setImageViewBitmap(R.id.widget_art, cachedArt)
        } else {
            views.setImageViewResource(R.id.widget_art, R.drawable.ic_widget_note)
        }

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
        views.setOnClickPendingIntent(
            R.id.widget_mix, PulseWidgets.action(context, cls, PulseWidgets.ACTION_MIX)
        )
        views.setOnClickPendingIntent(
            R.id.widget_dj, PulseWidgets.action(context, cls, PulseWidgets.ACTION_DJ)
        )

        // Progression : la barre avance, et chaque tranche est tactile —
        // toucher la barre déplace la lecture, en transition.
        views.setProgressBar(
            R.id.widget_progress, 1000,
            (PlayerCore.progress.value * 1000).toInt().coerceIn(0, 1000),
            false
        )
        val zones = PulseWidgets.SEEK_ZONES
        for ((i, id) in zones.withIndex()) {
            // Milieu de la tranche : toucher le début de la barre revient
            // au début du morceau, la fin à sa fin
            val frac = (i + 0.5f) / zones.size
            views.setOnClickPendingIntent(
                id,
                PendingIntent.getBroadcast(
                    context, 2000 + i,
                    Intent(context, cls)
                        .setAction(PulseWidgets.ACTION_SEEK)
                        .putExtra(PulseWidgets.EXTRA_FRACTION, frac),
                    PendingIntent.FLAG_UPDATE_CURRENT or
                        PendingIntent.FLAG_IMMUTABLE
                )
            )
        }
        views.setOnClickPendingIntent(R.id.widget_title, PulseWidgets.openApp(context))
        views.setOnClickPendingIntent(R.id.widget_art, PulseWidgets.openApp(context))
        manager.updateAppWidget(ids, views)

        // Jaquette : lecture disque/tag hors du thread principal, puis
        // seconde passe sur le widget une fois le bitmap prêt. SEULEMENT si
        // elle n'était pas déjà en cache : sinon chaque rafraîchissement
        // relançait un thread pour relire la même image.
        val uri = track?.uri ?: return
        if (cachedArt != null) return
        val app = context.applicationContext
        thread(name = "widget-art") {
            val bmp = try {
                ArtworkCache.loadBlocking(app, uri, 256)
            } catch (_: Exception) {
                null
            } ?: return@thread
            try {
                // Retenue pour les reconstructions suivantes du même morceau
                PulseWidgets.rememberArt(uri, bmp)
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
            PulseWidgets.ACTION_SEEK -> {
                Graph.init(context)
                PlayerCore.seekToFractionSmooth(
                    intent.getFloatExtra(PulseWidgets.EXTRA_FRACTION, 0f)
                )
            }
            PulseWidgets.ACTION_MIX, PulseWidgets.ACTION_DJ -> {
                val dj = intent.action == PulseWidgets.ACTION_DJ
                // Retour visuel immédiat : la construction du plan prend
                // un instant sur une grosse bibliothèque
                showPreparing(context, dj)
                val pending = goAsync()
                PulseWidgets.startMix(context, dj) { pending.finish() }
                return
            }
            else -> super.onReceive(context, intent)
        }
        if (intent.action?.startsWith("com.pulsemix.app.widget.") == true) {
            PulseWidgets.refresh(context)
        }
    }

    /** Affiche « Préparation… » le temps que le plan se construise. */
    private fun showPreparing(context: Context, dj: Boolean) {
        try {
            val mgr = AppWidgetManager.getInstance(context) ?: return
            val ids = mgr.getAppWidgetIds(
                ComponentName(context, PlayerWidget::class.java)
            )
            if (ids.isEmpty()) return
            val v = RemoteViews(context.packageName, R.layout.widget_player)
            v.setTextViewText(
                R.id.widget_title,
                context.getString(
                    if (dj) R.string.widget_starting_dj
                    else R.string.widget_starting_mix
                )
            )
            mgr.partiallyUpdateAppWidget(ids, v)
        } catch (_: Exception) {
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
