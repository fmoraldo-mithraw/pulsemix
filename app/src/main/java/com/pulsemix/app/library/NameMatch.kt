package com.pulsemix.app.library

import java.net.URLDecoder
import java.text.Normalizer

/**
 * Comparaison d'un candidat de tag avec le nom du fichier.
 *
 * Une même empreinte sonore est souvent rattachée à quinze enregistrements
 * — le morceau sur son album, sur six compilations, en version
 * remasterisée, en concert. AcoustID leur donne exactement le même score :
 * il ne les départage pas, parce que c'est le même son. Le nom du fichier,
 * lui, dit souvent lequel on a sous la main.
 *
 * C'est le nom du FICHIER qui sert de référence, pas le tag titre : le tag
 * a pu être abîmé par une correction précédente, le nom du fichier non.
 *
 * Sans Android ni réseau : vérifiable par des tests.
 */
internal object NameMatch {

    /**
     * Habillage sans rapport avec l'identité du morceau, à ignorer des deux
     * côtés. « live », « remix », « remaster », « acoustic » n'y sont PAS :
     * ce sont précisément eux qui distinguent deux versions, les effacer
     * reviendrait à renoncer à choisir.
     */
    private val JUNK = setOf(
        "official", "video", "videoclip", "clip", "lyric", "lyrics", "audio",
        "hd", "hq", "4k", "paroles", "visualizer", "visualiser", "mv",
        "explicit", "www", "com", "mp3", "m4a", "flac", "wav", "ogg", "opus",
        "aac", "wma", "mp4"
    )

    /**
     * Nom de fichier tiré d'une URI, quel que soit son encodage. Les URI de
     * l'explorateur de documents portent le chemin complet dans leur
     * dernier segment, une fois décodé.
     */
    fun fileNameOf(uri: String): String {
        val raw = uri.substringAfterLast('/')
        val decoded = try {
            // decode() prend « + » pour une espace : le protéger d'abord,
            // sinon un fichier qui en contient perd son vrai nom.
            URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8")
        } catch (_: Exception) {
            raw
        }
        return decoded.substringAfterLast('/').substringBeforeLast('.')
    }

    /** Mots significatifs d'un texte : minuscules, sans accents ni habillage. */
    fun tokens(raw: String): Set<String> {
        var s = raw.lowercase()
        // Suffixe d'identifiant YouTube en fin de nom
        s = s.replace(Regex("\\[[a-z0-9_-]{8,}\\]\\s*$"), " ")
        s = Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
        return s.split(Regex("[^a-z0-9]+"))
            .filter { it.isNotEmpty() }
            .filterNot { it in JUNK }
            // Numéros de piste : « 03 », « 7 ». Un titre comme « 1979 »
            // fait quatre chiffres et reste, lui.
            .filterNot { it.length <= 2 && it.all { c -> c.isDigit() } }
            .toSet()
    }

    /**
     * Proximité entre deux ensembles de mots, de 0 (rien en commun) à 1
     * (les mêmes mots). Coefficient de Dice : il récompense ce qui est
     * commun ET pénalise ce que le candidat ajoute — c'est ce second point
     * qui écarte « Everlong (Live at Wembley) » quand le fichier ne dit que
     * « Everlong », ou un crédit « Various Artists » sur une compilation.
     */
    fun similarity(a: Set<String>, b: Set<String>): Float {
        if (a.isEmpty() || b.isEmpty()) return 0f
        val common = a.count { it in b }
        return 2f * common / (a.size + b.size)
    }

    /** Proximité entre le nom de fichier et un couple titre/artiste. */
    fun similarityToFile(fileTokens: Set<String>, title: String, artist: String): Float {
        val candidate = tokens("$artist $title")
        return similarity(fileTokens, candidate)
    }
}
