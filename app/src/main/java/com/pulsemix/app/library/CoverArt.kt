package com.pulsemix.app.library

import android.content.Context
import com.pulsemix.app.ui.ArtworkCache
import java.net.HttpURLConnection
import java.net.URL

/**
 * Récupération de la jaquette d'un morceau via Cover Art Archive : la
 * pochette officielle du groupe de parutions MusicBrainz que l'empreinte
 * sonore (ou la recherche de tags) vient d'identifier.
 *
 * Rien n'est téléchargé pour un morceau qui a déjà une jaquette —
 * embarquée dans le fichier ou récupérée lors d'un passage précédent.
 */
object CoverArt {

    private const val USER_AGENT =
        "PulseMix/1 (https://github.com/fmoraldo-mithraw/pulsemix)"

    /** Garde-fou : certaines pochettes d'archives sont des scans énormes. */
    private const val MAX_BYTES = 3 * 1024 * 1024

    fun fetchIfMissing(context: Context, uri: String, releaseGroupId: String): Boolean =
        fetch(context, uri, releaseGroupId, force = false)

    /**
     * @param force true pour remplacer la jaquette affichée même si le
     * morceau en a déjà une (bouton « chercher la jaquette » : c'est
     * précisément une jaquette absente ou fausse qu'on veut corriger).
     * Le fichier audio n'est jamais touché.
     */
    fun fetch(
        context: Context,
        uri: String,
        releaseGroupId: String,
        force: Boolean
    ): Boolean {
        if (releaseGroupId.isBlank()) return false
        if (!force && ArtworkCache.loadBlocking(context, uri, 512) != null) {
            return false
        }
        val bytes = download(
            "https://coverartarchive.org/release-group/$releaseGroupId/front-500"
        ) ?: return false
        return ArtworkCache.store(context, uri, bytes, replaceCached = force)
    }

    /** Vignette (250 px) d'un groupe de parution, pour l'écran de choix. */
    fun thumbnail(releaseGroupId: String): ByteArray? =
        if (releaseGroupId.isBlank()) null
        else download(
            "https://coverartarchive.org/release-group/$releaseGroupId/front-250"
        )

    private fun download(u: String): ByteArray? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(u).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                // L'archive répond par une redirection vers le fichier ;
                // même protocole, elle est suivie automatiquement.
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", USER_AGENT)
            }
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_BYTES) return null
                    out.write(buf, 0, n)
                }
                out.toByteArray().takeIf { it.isNotEmpty() }
            }
        } catch (_: Exception) {
            null
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }
}
