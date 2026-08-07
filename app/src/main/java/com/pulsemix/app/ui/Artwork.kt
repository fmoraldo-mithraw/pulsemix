package com.pulsemix.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Cache mémoire des jaquettes embarquées dans les fichiers audio.
 * Les fichiers sans jaquette sont mémorisés pour ne pas être relus.
 */
object ArtworkCache {

    // Borné en OCTETS (20 Mo), pas en nombre d'images : 80 bitmaps 512x512
    // pouvaient occuper ~80 Mo et faire tuer l'appli par manque de mémoire.
    private val cache = object : LruCache<String, Bitmap>(20 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val misses: MutableSet<String> =
        Collections.newSetFromMap(ConcurrentHashMap())

    /**
     * S'incrémente à chaque jaquette ajoutée, remplacée ou retirée : les
     * vues déjà composées rechargent — sans lui, un bitmap retenu par
     * remember() affichait l'ancienne pochette jusqu'au prochain passage.
     */
    val version = kotlinx.coroutines.flow.MutableStateFlow(0)

    private fun hashName(uri: String): String =
        java.security.MessageDigest.getInstance("MD5")
            .digest(uri.toByteArray())
            .joinToString("") { "%02x".format(it) } + ".jpg"

    private fun diskFile(context: Context, uri: String): java.io.File {
        val dir = java.io.File(context.cacheDir, "artwork").apply { mkdirs() }
        return java.io.File(dir, hashName(uri))
    }

    /**
     * Jaquette récupérée en ligne (Cover Art Archive). Rangée dans les
     * fichiers de l'appli, pas dans le cache : le système peut purger le
     * cache, et une jaquette téléchargée ne serait jamais re-téléchargée —
     * le morceau est marqué comme déjà examiné par la correction des tags.
     */
    private fun coverFile(context: Context, uri: String): java.io.File {
        val dir = java.io.File(context.filesDir, "covers").apply { mkdirs() }
        return java.io.File(dir, hashName(uri))
    }

    suspend fun load(context: Context, uri: String, targetPx: Int): Bitmap? =
        withContext(Dispatchers.IO) { loadBlocking(context, uri, targetPx) }

    /**
     * Variante bloquante (widgets : pas de coroutine disponible).
     * À n'appeler que depuis un thread de fond.
     */
    fun loadBlocking(context: Context, uri: String, targetPx: Int): Bitmap? {
        cache.get(uri)?.let { return it }
        if (uri in misses) return null

        // Cache disque : scroll instantané dès le premier lancement
        val disk = diskFile(context, uri)
        if (disk.exists()) {
            val fromDisk = try {
                BitmapFactory.decodeFile(disk.absolutePath)
            } catch (_: Exception) {
                null
            }
            if (fromDisk != null) {
                cache.put(uri, fromDisk)
                return fromDisk
            }
        }

        // Jaquette téléchargée lors d'une correction de tags
        val cover = coverFile(context, uri)
        if (cover.exists()) {
            val fromCover = try {
                BitmapFactory.decodeFile(cover.absolutePath)
            } catch (_: Exception) {
                null
            }
            if (fromCover != null) {
                cache.put(uri, fromCover)
                return fromCover
            }
        }

        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(context, Uri.parse(uri))
            val bytes = mmr.embeddedPicture
            if (bytes == null) {
                misses.add(uri)
                null
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= 512) sample *= 2
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                if (bmp != null) {
                    cache.put(uri, bmp)
                    try {
                        java.io.FileOutputStream(disk).use {
                            bmp.compress(Bitmap.CompressFormat.JPEG, 85, it)
                        }
                    } catch (_: Exception) {
                    }
                } else misses.add(uri)
                bmp
            }
        } catch (_: Exception) {
            misses.add(uri)
            null
        } finally {
            try {
                mmr.release()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Range une jaquette téléchargée (octets d'image) pour [uri] :
     * décodée et rééchantillonnée comme les jaquettes embarquées, écrite
     * durablement, mise en cache mémoire, et le morceau cesse d'être
     * compté « sans jaquette ».
     *
     * @param replaceCached true pour supprimer aussi la copie en cache de
     * la jaquette embarquée : sans ça, elle repasserait devant la jaquette
     * téléchargée au prochain chargement — l'ordre de lecture la privilégie.
     */
    fun store(
        context: Context,
        uri: String,
        bytes: ByteArray,
        replaceCached: Boolean = false
    ): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 512) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } catch (_: Exception) {
            null
        } ?: return false
        return try {
            java.io.FileOutputStream(coverFile(context, uri)).use {
                bmp.compress(Bitmap.CompressFormat.JPEG, 85, it)
            }
            if (replaceCached) {
                try {
                    diskFile(context, uri).delete()
                } catch (_: Exception) {
                }
            }
            cache.put(uri, bmp)
            misses.remove(uri)
            version.value++
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Supprime la jaquette téléchargée de [uri] (correction annulée). */
    fun removeCover(context: Context, uri: String) {
        try {
            coverFile(context, uri).delete()
        } catch (_: Exception) {
        }
        cache.remove(uri)
        misses.remove(uri)
        version.value++
    }

    /** Supprime toutes les jaquettes téléchargées (remise à zéro des tags). */
    fun clearCovers(context: Context) {
        try {
            java.io.File(context.filesDir, "covers")
                .listFiles()?.forEach { it.delete() }
        } catch (_: Exception) {
        }
        cache.evictAll()
        misses.clear()
        version.value++
    }
}

/** Jaquette d'un morceau, avec repli sur un emoji quand il n'y en a pas. */
@Composable
fun TrackArtwork(
    uri: String?,
    modifier: Modifier = Modifier,
    corner: Dp,
    targetPx: Int,
    fallback: String,
    fallbackStyle: TextStyle = MaterialTheme.typography.titleLarge
) {
    val context = LocalContext.current
    var bmp by remember(uri) { mutableStateOf<Bitmap?>(null) }
    // La version suit les jaquettes ajoutées/retirées : une pochette tout
    // juste téléchargée apparaît sans attendre une recomposition complète.
    val version by ArtworkCache.version.collectAsState()
    LaunchedEffect(uri, version) {
        if (uri != null) bmp = ArtworkCache.load(context, uri, targetPx)
    }
    Box(
        modifier.background(
            MaterialTheme.colorScheme.surfaceVariant,
            RoundedCornerShape(corner)
        ),
        contentAlignment = Alignment.Center
    ) {
        val b = bmp
        if (b != null) {
            Image(
                bitmap = b.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(corner)),
                contentScale = ContentScale.Crop
            )
        } else {
            Text(fallback, style = fallbackStyle)
        }
    }
}
