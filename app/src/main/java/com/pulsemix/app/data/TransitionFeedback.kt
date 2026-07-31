package com.pulsemix.app.data

import android.content.Context
import android.content.SharedPreferences
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Paires de morceaux marquées « transition ratée » par l'utilisateur :
 * la génération de mix évite de les remettre l'une derrière l'autre.
 */
object TransitionFeedback {

    private val bad: MutableSet<String> =
        Collections.newSetFromMap(ConcurrentHashMap())
    private var prefs: SharedPreferences? = null

    private fun key(fromUri: String, toUri: String) =
        "${fromUri.hashCode()}>${toUri.hashCode()}"

    fun init(context: Context) {
        val p = context.getSharedPreferences("bad_transitions", Context.MODE_PRIVATE)
        prefs = p
        bad.addAll(p.all.keys)
    }

    fun record(fromUri: String, toUri: String) {
        val k = key(fromUri, toUri)
        bad.add(k)
        prefs?.edit()?.putBoolean(k, true)?.apply()
    }

    fun isBad(fromUri: String, toUri: String): Boolean =
        key(fromUri, toUri) in bad
}
