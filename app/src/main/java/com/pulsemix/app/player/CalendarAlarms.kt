package com.pulsemix.app.player

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Réveils tirés de l'agenda : une sonnerie musicale au début de chaque
 * événement, ou à chacune de ses notifications, selon le réglage de chaque
 * agenda.
 *
 * « Se connecter à Google Agenda » passe par le fournisseur d'agenda
 * d'Android (CalendarContract) : les agendas Google du compte configuré sur
 * le téléphone y sont synchronisés par le système. Une seule permission
 * (lecture de l'agenda), aucun identifiant à saisir, aucun serveur — et ça
 * marche aussi hors connexion, sur ce qui a déjà été synchronisé.
 *
 * La programmation elle-même reste dans [AlarmClock] : il ne tient qu'UNE
 * alarme système, la plus proche de toutes (réveils + agenda).
 */
object CalendarAlarms {

    private const val PREFS = "alarm"
    private const val KEY_ENABLED = "calendarEnabled"
    private const val KEY_CONFIGS = "calendarConfigs"
    private const val KEY_FIRED = "calendarFired"

    /** Horizon de lecture de l'agenda (la prochaine alarme au-delà sera
     *  trouvée au ré-armement suivant : chaque sonnerie, chaque heure,
     *  chaque modification de l'agenda). */
    private const val HORIZON_MS = 8 * 86_400_000L

    data class CalendarInfo(
        val id: Long,
        val name: String,
        val account: String,
        val accountType: String,
        val color: Int
    ) {
        val isGoogle: Boolean get() = accountType == "com.google"
    }

    /** Interrupteur général « sonner pour l'agenda ». */
    val enabled = MutableStateFlow(false)

    /** Réglage par agenda (clé : id de l'agenda). */
    val configs = MutableStateFlow<Map<Long, CalendarConfig>>(emptyMap())

    /** Prochain déclenchement connu (pour l'écran). */
    val next = MutableStateFlow<CalendarTrigger?>(null)

    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        enabled.value = p.getBoolean(KEY_ENABLED, false)
        configs.value = AlarmSchedule.readCalendarConfigs(p.getString(KEY_CONFIGS, "[]") ?: "[]")
    }

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    /** Actif ET autorisé : sinon l'agenda ne compte pas. */
    fun active(context: Context): Boolean = enabled.value && hasPermission(context)

    fun setEnabled(context: Context, on: Boolean) {
        enabled.value = on
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, on).apply()
        observe(context)
    }

    fun setConfig(context: Context, cfg: CalendarConfig) {
        configs.value = configs.value + (cfg.calendarId to cfg)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_CONFIGS, AlarmSchedule.writeCalendarConfigs(configs.value.values))
            .apply()
    }

    /** Agendas visibles sur le téléphone (Google en tête). */
    fun listCalendars(context: Context): List<CalendarInfo> {
        if (!hasPermission(context)) return emptyList()
        val out = ArrayList<CalendarInfo>()
        try {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(
                    CalendarContract.Calendars._ID,
                    CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                    CalendarContract.Calendars.ACCOUNT_NAME,
                    CalendarContract.Calendars.ACCOUNT_TYPE,
                    CalendarContract.Calendars.CALENDAR_COLOR
                ),
                "${CalendarContract.Calendars.VISIBLE} = 1", null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    out.add(
                        CalendarInfo(
                            id = c.getLong(0),
                            name = c.getString(1) ?: "Agenda",
                            account = c.getString(2) ?: "",
                            accountType = c.getString(3) ?: "",
                            color = c.getInt(4)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            log("lecture des agendas impossible : ${e.message}")
        }
        return out.sortedWith(compareBy({ !it.isGoogle }, { it.account }, { it.name }))
    }

    /** Occurrences d'événements des agendas actifs sur [from, to], avec
     *  leurs rappels (méthode « notification » ou par défaut). Les
     *  événements refusés sont ignorés. */
    private fun loadInstances(context: Context, from: Long, to: Long, calendarIds: Set<Long>): List<CalendarInstance> {
        if (calendarIds.isEmpty()) return emptyList()
        data class Raw(val eventId: Long, val calendarId: Long, val title: String, val begin: Long, val allDay: Boolean)
        val raws = ArrayList<Raw>()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(from.toString()).appendPath(to.toString()).build()
        context.contentResolver.query(
            uri,
            arrayOf(
                CalendarContract.Instances.EVENT_ID,
                CalendarContract.Instances.CALENDAR_ID,
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.ALL_DAY,
                CalendarContract.Instances.SELF_ATTENDEE_STATUS
            ),
            "${CalendarContract.Instances.CALENDAR_ID} IN (${calendarIds.joinToString(",")})",
            null,
            "${CalendarContract.Instances.BEGIN} ASC"
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.getInt(5) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED) continue
                raws.add(Raw(c.getLong(0), c.getLong(1), c.getString(2) ?: "", c.getLong(3), c.getInt(4) != 0))
            }
        }
        if (raws.isEmpty()) return emptyList()
        val reminders = HashMap<Long, MutableList<Int>>()
        val ids = raws.map { it.eventId }.distinct()
        // Par paquets : une requête « IN » sur des milliers d'ids dépasse la
        // limite de variables de SQLite.
        for (chunk in ids.chunked(400)) {
            context.contentResolver.query(
                CalendarContract.Reminders.CONTENT_URI,
                arrayOf(
                    CalendarContract.Reminders.EVENT_ID,
                    CalendarContract.Reminders.MINUTES,
                    CalendarContract.Reminders.METHOD
                ),
                "${CalendarContract.Reminders.EVENT_ID} IN (${chunk.joinToString(",")})",
                null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val method = c.getInt(2)
                    if (method != CalendarContract.Reminders.METHOD_ALERT &&
                        method != CalendarContract.Reminders.METHOD_DEFAULT
                    ) continue
                    var min = c.getInt(1)
                    // -1 = « délai par défaut » du fournisseur : 10 min
                    if (min < 0) min = 10
                    reminders.getOrPut(c.getLong(0)) { ArrayList() }.add(min)
                }
            }
        }
        return raws.map {
            CalendarInstance(it.eventId, it.calendarId, it.title, it.begin, it.allDay, reminders[it.eventId].orEmpty())
        }
    }

    /** Prochain déclenchement tiré de l'agenda (ou null), mémorisé dans
     *  [next] pour l'écran. */
    fun nextTrigger(context: Context, now: Long = System.currentTimeMillis()): CalendarTrigger? {
        load(context)
        if (!active(context)) {
            next.value = null
            return null
        }
        val cfgs = configs.value.filterValues { it.enabled }
        val result = try {
            val instances = loadInstances(
                context, now - 2 * 86_400_000L, now + HORIZON_MS, cfgs.keys
            )
            AlarmSchedule.calendarTriggers(instances, cfgs, now, firedKeys(context)).firstOrNull()
        } catch (e: Exception) {
            log("lecture de l'agenda impossible : ${e::class.java.simpleName} ${e.message}")
            null
        }
        next.value = result
        return result
    }

    // ----------------------------------------------- sonneries déjà jouées

    private fun firedKeys(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_FIRED, emptySet()).orEmpty()
            .mapNotNull { it.substringAfter('|', "").takeIf(String::isNotEmpty) }
            .toSet()

    /** Note la sonnerie [key] comme jouée (entrées « heure|clé », purgées
     *  au-delà de trois jours). */
    fun markFired(context: Context, key: String, at: Long) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val limit = System.currentTimeMillis() - 3 * 86_400_000L
        val kept = p.getStringSet(KEY_FIRED, emptySet()).orEmpty()
            .filter { (it.substringBefore('|').toLongOrNull() ?: 0L) > limit }
            .toMutableSet()
        kept.add("$at|$key")
        p.edit().putStringSet(KEY_FIRED, kept).apply()
    }

    // ---------------------------------------------- suivi des modifications

    private var observer: ContentObserver? = null
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private var appContext: Context? = null
    private val rearmRunnable = Runnable {
        appContext?.let { AlarmClock.rearm(it, "agenda modifié") }
    }

    /**
     * Tant que le processus vit, toute modification de l'agenda (événement
     * ajouté, déplacé, rappel changé) ré-arme dans les 5 s. Appli fermée,
     * c'est le ré-armement horaire et le rappel système d'agenda
     * ([AlarmBootReceiver]) qui rattrapent.
     */
    fun observe(context: Context) {
        val app = context.applicationContext
        appContext = app
        val want = active(app)
        if (want && observer == null) {
            val obs = object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean) {
                    handler.removeCallbacks(rearmRunnable)
                    handler.postDelayed(rearmRunnable, 5_000L)
                }
            }
            try {
                app.contentResolver.registerContentObserver(CalendarContract.CONTENT_URI, true, obs)
                observer = obs
            } catch (_: Exception) {
            }
        } else if (!want && observer != null) {
            try {
                app.contentResolver.unregisterContentObserver(observer!!)
            } catch (_: Exception) {
            }
            observer = null
        }
    }

    private fun log(message: String) {
        try {
            PlayerCore.engineLog("Réveil", message)
        } catch (_: Exception) {
        }
    }
}
