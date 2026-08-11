package com.pulsemix.app.player

/**
 * Arithmétique pure de la file de lecture, extraite de [PlayerCore] pour
 * être testable en JVM : PlayerCore est un objet à l'initialisation
 * lourde (ExoPlayer, Handler…) impossible à charger hors Android, alors
 * que ces formules-là décident silencieusement de l'affichage des phases.
 */
object QueueMath {

    /**
     * Bornes des phases d'un mix après déplacement d'un morceau [from] →
     * [to] : les débuts de phase entre les deux glissent d'un cran. Sans
     * ça, l'affichage de la phase en cours dérivait à chaque réordonnement.
     *
     * Chaque borne est ramenée dans [0, queueSize - 1] : déplacer l'unique
     * morceau d'une dernière phase la laisserait commencer APRÈS la fin de
     * la file — « suivant » viserait un index inexistant, que le lecteur
     * ignore en silence (bouton mort).
     */
    fun shiftPhaseStartsForMove(
        starts: List<Int>,
        from: Int,
        to: Int,
        queueSize: Int
    ): List<Int> {
        if (starts.isEmpty()) return starts
        val last = (queueSize - 1).coerceAtLeast(0)
        return starts.map { s ->
            when {
                from < s && s <= to -> s - 1
                to < s && s <= from -> s + 1
                else -> s
            }.coerceIn(0, last)
        }
    }
}
