package com.pulsemix.app.library

/**
 * Tenue de la liste des corrections de tags appliquées : **une seule ligne
 * par morceau, la plus récente**.
 *
 * Un morceau peut être corrigé plusieurs fois — une passe automatique,
 * puis une reprise à la main. Empiler les corrections remplissait la liste
 * d'étapes intermédiaires sans intérêt, et laissait deux lignes pour le
 * même fichier.
 *
 * La règle qui compte : en fusionnant, on garde les valeurs d'ORIGINE de
 * la toute première correction. Sinon « revenir à l'original » ramènerait
 * à l'étape intermédiaire au lieu du tag qu'avait le fichier au départ.
 *
 * Isolé ici, sans Android, pour être vérifiable par des tests.
 */
internal object AppliedTags {

    /** Une correction ne change rien si elle rend le morceau à son état d'origine. */
    fun isNoOp(s: TagFixer.Suggestion): Boolean =
        s.newTitle == s.oldTitle && s.newArtist == s.oldArtist

    /**
     * Ajoute [s] en tête, en remplaçant toute correction précédente du même
     * morceau et en lui reprenant ses valeurs d'origine. Si le résultat
     * revient au tag de départ, la ligne disparaît : il n'y a plus rien à
     * signaler ni à annuler.
     */
    fun record(
        list: List<TagFixer.Suggestion>,
        s: TagFixer.Suggestion,
        max: Int
    ): List<TagFixer.Suggestion> {
        val previous = list.firstOrNull { it.uri == s.uri }
        val merged =
            if (previous == null) s
            else s.copy(oldTitle = previous.oldTitle, oldArtist = previous.oldArtist)
        val rest = list.filter { it.uri != s.uri }
        return if (isNoOp(merged)) rest else (listOf(merged) + rest).take(max)
    }

    /**
     * Réduit une liste déjà empilée à une ligne par morceau : pour les
     * historiques enregistrés avant cette règle. [list] va du plus récent
     * au plus ancien.
     */
    fun collapse(list: List<TagFixer.Suggestion>): List<TagFixer.Suggestion> {
        val out = ArrayList<TagFixer.Suggestion>()
        val seen = HashSet<String>()
        for (s in list) {
            if (!seen.add(s.uri)) continue
            // L'entrée la plus ancienne du morceau porte le tag de départ
            val oldest = list.last { it.uri == s.uri }
            val merged = s.copy(oldTitle = oldest.oldTitle, oldArtist = oldest.oldArtist)
            if (!isNoOp(merged)) out.add(merged)
        }
        return out
    }

    /** Retire toute trace du morceau [uri] (correction annulée). */
    fun remove(
        list: List<TagFixer.Suggestion>,
        uri: String
    ): List<TagFixer.Suggestion> = list.filter { it.uri != uri }
}
