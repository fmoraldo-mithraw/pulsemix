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

    private val cache = LruCache<String, Bitmap>(80)
    private val misses: MutableSet<String> =
        Collections.newSetFromMap(ConcurrentHashMap())

    private fun diskFile(context: Context, uri: String): java.io.File {
        val dir = java.io.File(context.cacheDir, "artwork").apply { mkdirs() }
        val md = java.security.MessageDigest.getInstance("MD5")
            .digest(uri.toByteArray())
        return java.io.File(dir, md.joinToString("") { "%02x".format(it) } + ".jpg")
    }

    suspend fun load(context: Context, uri: String, targetPx: Int): Bitmap? =
        withContext(Dispatchers.IO) {
            cache.get(uri)?.let { return@withContext it }
            if (uri in misses) return@withContext null

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
                    return@withContext fromDisk
                }
            }

            val mmr = MediaMetadataRetriever()
            try {
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
    LaunchedEffect(uri) {
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
