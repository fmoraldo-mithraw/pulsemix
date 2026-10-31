package com.pulsemix.app.player

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.AlarmClock as SysAlarm
import android.widget.Toast
import com.pulsemix.app.MainActivity

/**
 * Point d'entrée des intents « horloge » d'Android : c'est ce qui permet à
 * « Hey Google, mets une alarme à 18h » de créer le réveil dans PulseMix.
 *
 *  - SET_ALARM : crée (ou réactive) le réveil ; sans heure, ouvre l'écran
 *    des réveils pour le régler à la main.
 *  - SHOW_ALARMS : ouvre l'écran des réveils.
 *  - DISMISS_ALARM : coupe le réveil qui sonne, sinon désactive celui de
 *    l'heure dite.
 *  - SNOOZE_ALARM : répète le réveil qui sonne.
 *
 * Activité invisible : elle agit, affiche un toast et se ferme.
 */
class VoiceAlarmActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            handle(intent)
        } catch (e: Exception) {
            toast("PulseMix : réveil non réglé (${e.message})")
        }
        finish()
    }

    private fun handle(intent: Intent?) {
        when (intent?.action) {
            SysAlarm.ACTION_SET_ALARM -> setAlarm(intent)
            SysAlarm.ACTION_SHOW_ALARMS -> openAlarms()
            SysAlarm.ACTION_DISMISS_ALARM -> dismissAlarm(intent)
            SysAlarm.ACTION_SNOOZE_ALARM -> snoozeAlarm(intent)
        }
    }

    private fun setAlarm(intent: Intent) {
        if (!intent.hasExtra(SysAlarm.EXTRA_HOUR)) {
            openAlarms()
            return
        }
        val hour = intent.getIntExtra(SysAlarm.EXTRA_HOUR, 7)
        val minute = intent.getIntExtra(SysAlarm.EXTRA_MINUTES, 0)
        val days = AlarmSchedule.daysFromCalendar(
            intent.getIntegerArrayListExtra(SysAlarm.EXTRA_DAYS).orEmpty()
        )
        val label = intent.getStringExtra(SysAlarm.EXTRA_MESSAGE).orEmpty()
        val a = AlarmClock.setFromVoice(applicationContext, hour, minute, days, label)
        toast("Réveil PulseMix réglé à %02d:%02d".format(a.hour, a.minute))
        if (!intent.getBooleanExtra(SysAlarm.EXTRA_SKIP_UI, true)) openAlarms()
    }

    private fun dismissAlarm(intent: Intent) {
        AlarmClock.init(applicationContext)
        if (AlarmClock.isRinging) {
            AlarmClock.dismiss(applicationContext)
            toast("Réveil arrêté")
            return
        }
        val mode = intent.getStringExtra(SysAlarm.EXTRA_ALARM_SEARCH_MODE)
        if (mode == SysAlarm.ALARM_SEARCH_MODE_TIME && intent.hasExtra(SysAlarm.EXTRA_HOUR)) {
            val hour = intent.getIntExtra(SysAlarm.EXTRA_HOUR, -1)
            val minute = intent.getIntExtra(SysAlarm.EXTRA_MINUTES, 0)
            val n = AlarmClock.disableAt(applicationContext, hour, minute)
            if (n > 0) {
                toast("Réveil de %02d:%02d désactivé".format(hour, minute))
                return
            }
        }
        // Rien de précis à faire : on laisse choisir dans l'écran
        openAlarms()
    }

    private fun snoozeAlarm(intent: Intent) {
        AlarmClock.init(applicationContext)
        if (!AlarmClock.isRinging) return
        val min = intent.getIntExtra(SysAlarm.EXTRA_ALARM_SNOOZE_DURATION, AlarmClock.SNOOZE_CHOICES.first())
            .coerceIn(1, 60)
        AlarmClock.snooze(applicationContext, min)
        toast("Réveil répété dans $min min")
    }

    private fun openAlarms() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_ALARMS, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
    }

    private fun toast(text: String) =
        Toast.makeText(applicationContext, text, Toast.LENGTH_SHORT).show()
}
