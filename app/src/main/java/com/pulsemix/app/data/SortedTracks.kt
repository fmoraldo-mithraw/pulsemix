package com.pulsemix.app.data

/**
 * Maintien de la bibliothèque triée par titre, par insertion.
 *
 * Elle était re-triée en entier à chaque morceau ajouté : un scan de 800
 * fichiers faisait 800 tris de 800 éléments, en réallouant un titre en
 * minuscules à chaque comparaison. Insérer au bon rang coûte une recherche
 * binaire.
 *
 * Isolé ici, sans Android, pour être vérifiable par des tests : c'est la
 * structure que voient tous les écrans, une erreur d'ordre ou un morceau
 * perdu s'y remarquerait tard.
 */
internal object SortedTracks {

    /** Le critère de tri : le titre, casse ignorée. */
    fun keyOf(t: Track): String = t.title.lowercase()

    /**
     * Premier rang où insérer [key] en gardant la liste triée. Se place
     * APRÈS les titres égaux : deux morceaux homonymes gardent leur ordre
     * d'arrivée.
     */
    fun insertionPoint(list: List<Track>, key: String): Int {
        var lo = 0
        var hi = list.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (keyOf(list[mid]) <= key) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** Ajoute ou remplace [track] (clé = uri), la liste restant triée. */
    fun put(list: List<Track>, track: Track): List<Track> {
        val idx = list.indexOfFirst { it.uri == track.uri }
        val out = list.toMutableList()
        if (idx >= 0) {
            // Le rang ne bouge que si le titre change (correction de tag)
            if (keyOf(list[idx]) == keyOf(track)) {
                out[idx] = track
                return out
            }
            out.removeAt(idx)
        }
        out.add(insertionPoint(out, keyOf(track)), track)
        return out
    }

    /**
     * Applique [transform] au morceau d'uri [uri]. Si le titre en sort
     * changé, le morceau est replacé au bon rang. Liste inchangée si l'uri
     * est inconnue.
     */
    fun update(
        list: List<Track>,
        uri: String,
        transform: (Track) -> Track
    ): List<Track> {
        val idx = list.indexOfFirst { it.uri == uri }
        if (idx < 0) return list
        val updated = transform(list[idx])
        val out = list.toMutableList()
        if (keyOf(list[idx]) == keyOf(updated)) {
            out[idx] = updated
        } else {
            out.removeAt(idx)
            out.add(insertionPoint(out, keyOf(updated)), updated)
        }
        return out
    }

    /** Tri complet, pour un chargement depuis le disque. */
    fun sorted(list: List<Track>): List<Track> = list.sortedBy { keyOf(it) }
}
