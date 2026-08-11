package com.pulsemix.app.data

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap

/**
 * Paires de morceaux marquées « transition ratée » par l'utilisateur :
 * la génération de mix évite de les remettre l'une derrière l'autre.
 *
 * Clés = les URIs COMPLETS ("from>to") : les anciennes clés en hashCode
 * pouvaient entrer en collision et condamner des paires innocentes.
 * Chaque entrée porte sa date de marquage, ce qui permet de plafonner
 * l'ensemble ([MAX]) en retirant les plus anciennes — sans borne, il
 * grossissait à chaque marquage pour toujours.
 */
object TransitionFeedback {

    /** Assez pour couvrir des années de marquages, borné pour les prefs. */
    private const val MAX = 200

    /** clé "fromUri>toUri" → date du marquage (ms epoch). */
    private val bad = ConcurrentHashMap<String, Long>()
    private var prefs: SharedPreferences? = null

    private fun key(fromUri: String, toUri: String) = "$fromUri>$toUri"

    /** Ancien format : "hashCode>hashCode" (deux entiers signés). */
    private val legacyKey = Regex("-?\\d+>-?\\d+")

    fun init(context: Context) {
        val p = context.getSharedPreferences("bad_transitions", Context.MODE_PRIVATE)
        prefs = p
        var editor: SharedPreferences.Editor? = null
        for ((k, v) in p.all) {
            // Migration : les clés numériques de l'ancien format (hashCode,
            // collisions possibles) sont intraduisibles vers les URIs —
            // on les jette plutôt que de garder des condamnations douteuses.
            // Les anciennes valeurs Boolean (sans date) partent avec elles.
            if (v !is Long || legacyKey.matches(k)) {
                editor = (editor ?: p.edit()).remove(k)
            } else {
                bad[k] = v
            }
        }
        editor?.apply()
        trim()
    }

    fun record(fromUri: String, toUri: String) {
        val k = key(fromUri, toUri)
        val now = System.currentTimeMillis()
        bad[k] = now
        prefs?.edit()?.putLong(k, now)?.apply()
        trim()
    }

    // containsKey explicite : sur une ConcurrentHashMap, `in` résout vers
    // contains(value) — sémantique piégeuse promue erreur par le compilateur
    fun isBad(fromUri: String, toUri: String): Boolean =
        bad.containsKey(key(fromUri, toUri))

    /** Retire les marquages les plus anciens au-delà du plafond. */
    private fun trim() {
        if (bad.size <= MAX) return
        val doomed = bad.entries.sortedBy { it.value }.take(bad.size - MAX)
        val editor = prefs?.edit()
        for (e in doomed) {
            bad.remove(e.key)
            editor?.remove(e.key)
        }
        editor?.apply()
    }
}
