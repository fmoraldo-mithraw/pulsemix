package com.pulsemix.app.data

import java.io.File

/**
 * Écriture d'un fichier qui ne doit jamais être trouvé à moitié écrit.
 *
 * La bibliothèque est réécrite en entier très souvent (tous les cinq
 * morceaux pendant un scan). Un `writeText` tronque puis remplit : si le
 * processus meurt dans cet intervalle — kill mémoire, batterie, plantage —
 * il ne reste qu'un JSON coupé en deux, illisible, et toute la
 * bibliothèque disparaît au démarrage suivant.
 *
 * Isolé ici, sans Android ni JSON, pour être vérifiable par des tests.
 */
internal object SafeFile {

    /**
     * Remplace [main] par [bytes], en passant par [tmp] et en gardant la
     * version précédente dans [bak].
     *
     * L'ordre compte : le temporaire est écrit ET vérifié avant qu'on
     * touche à quoi que ce soit. À tout instant, au moins un de [main] et
     * [bak] contient une version complète.
     *
     * @return true si [main] contient bien le nouveau contenu.
     */
    fun writeAtomic(main: File, tmp: File, bak: File, bytes: ByteArray): Boolean {
        return try {
            tmp.parentFile?.mkdirs()
            tmp.writeBytes(bytes)
            // Un disque plein tronque sans rien lever : remplacer la
            // bibliothèque par un fichier court serait pire que de ne pas
            // la sauvegarder du tout.
            if (tmp.length() != bytes.size.toLong()) {
                tmp.delete()
                return false
            }
            if (main.exists()) {
                bak.delete()
                if (!main.renameTo(bak)) main.copyTo(bak, overwrite = true)
            }
            if (!tmp.renameTo(main)) {
                tmp.copyTo(main, overwrite = true)
                tmp.delete()
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Contenu de [main], ou celui de [bak] si [main] manque ou n'est pas
     * jugé valide par [accept]. Quand le filet sert, il est aussi remis en
     * place comme fichier principal : la fois suivante repart d'un état sain.
     *
     * @return le contenu retenu, ou null si rien d'exploitable.
     */
    fun readWithFallback(main: File, bak: File, accept: (String) -> Boolean): String? {
        readIfAcceptable(main, accept)?.let { return it }
        val rescued = readIfAcceptable(bak, accept) ?: return null
        try {
            bak.copyTo(main, overwrite = true)
        } catch (_: Exception) {
        }
        return rescued
    }

    private fun readIfAcceptable(f: File, accept: (String) -> Boolean): String? = try {
        if (!f.exists() || f.length() == 0L) null
        else f.readText().takeIf(accept)
    } catch (_: Exception) {
        null
    }
}
