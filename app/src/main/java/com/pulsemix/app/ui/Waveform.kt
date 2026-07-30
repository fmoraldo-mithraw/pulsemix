package com.pulsemix.app.ui

import android.content.Context
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pulsemix.app.analysis.AudioDecoder
import com.pulsemix.app.data.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Enveloppe d'amplitude (forme d'onde) d'un morceau, en [POINTS] points
 * normalisés 0..1. Calculée une fois par décodage complet du fichier, puis
 * mémorisée sur disque (cacheDir/wave) : les affichages suivants sont
 * instantanés.
 */
object WaveformStore {

    const val POINTS = 400

    private val memory = LruCache<String, FloatArray>(16)

    // Un seul décodage à la fois : le calcul de wave ne doit pas concurrencer
    // les decks DJ ni l'analyse (MediaCodec + CPU).
    private val computeMutex = Mutex()

    private fun diskFile(context: Context, uri: String): java.io.File {
        val dir = java.io.File(context.cacheDir, "wave").apply { mkdirs() }
        val md = java.security.MessageDigest.getInstance("MD5")
            .digest(uri.toByteArray())
        return java.io.File(dir, md.joinToString("") { "%02x".format(it) } + ".bin")
    }

    suspend fun load(context: Context, uri: String): FloatArray? =
        withContext(Dispatchers.IO) {
            memory.get(uri)?.let { return@withContext it }
            fromDisk(context, uri)?.let { return@withContext it }
            computeMutex.withLock {
                // Un autre appel a pu le calculer pendant l'attente
                memory.get(uri)?.let { return@withLock it }
                fromDisk(context, uri)?.let { return@withLock it }
                val env = compute(context, uri) ?: return@withLock null
                memory.put(uri, env)
                try {
                    val bytes = ByteArray(env.size) {
                        (env[it] * 255f).toInt().coerceIn(0, 255).toByte()
                    }
                    diskFile(context, uri).writeBytes(bytes)
                } catch (_: Exception) {
                }
                env
            }
        }

    private fun fromDisk(context: Context, uri: String): FloatArray? {
        val f = diskFile(context, uri)
        if (!f.exists()) return null
        return try {
            val bytes = f.readBytes()
            if (bytes.size != POINTS) null
            else FloatArray(POINTS) { (bytes[it].toInt() and 0xFF) / 255f }
                .also { memory.put(uri, it) }
        } catch (_: Exception) {
            null
        }
    }

    private fun compute(context: Context, uri: String): FloatArray? {
        // Pics d'amplitude par tranche de ~50 ms
        val peaks = ArrayList<Float>(8192)
        var acc = 0f
        var cnt = 0
        var blockFrames = 0
        val ok = AudioDecoder().decode(context, Uri.parse(uri)) { pcm, frames, sr, ch ->
            if (blockFrames == 0) blockFrames = max(1, sr / 20)
            for (f in 0 until frames) {
                val v = abs(pcm[f * ch])
                if (v > acc) acc = v
                if (++cnt >= blockFrames) {
                    peaks.add(acc)
                    acc = 0f
                    cnt = 0
                }
            }
            true
        }
        if (!ok || peaks.size < 8) return null
        // Ré-échantillonnage en POINTS colonnes (max-pooling)
        val out = FloatArray(POINTS)
        for (k in 0 until POINTS) {
            val a = k * peaks.size / POINTS
            val b = max(a + 1, (k + 1) * peaks.size / POINTS)
            var m = 0f
            for (j in a until min(b, peaks.size)) m = max(m, peaks[j])
            out[k] = m
        }
        // Normalisation au 98e percentile : les crêtes isolées n'écrasent pas
        // le reste de l'onde.
        val sorted = out.clone().also { it.sort() }
        val ref = sorted[(POINTS * 98 / 100).coerceIn(0, POINTS - 1)]
            .coerceAtLeast(1e-3f)
        for (k in out.indices) out[k] = (out[k] / ref).coerceIn(0.02f, 1f)
        return out
    }
}

/**
 * Dessin d'une forme d'onde : barres en miroir autour de l'axe central,
 * partie déjà jouée colorée, passage fort surligné, zones de transition
 * marquées, tête de lecture.
 */
@Composable
fun WaveformView(
    env: FloatArray?,
    playhead: Float?,
    segment: ClosedFloatingPointRange<Float>? = null,
    transitions: List<ClosedFloatingPointRange<Float>> = emptyList(),
    modifier: Modifier = Modifier
) {
    val played = MaterialTheme.colorScheme.primary
    val idle = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.30f)
    val segColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.16f)
    val transColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.35f)
    val headColor = MaterialTheme.colorScheme.primary

    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val mid = h / 2f
            // Passage fort (fond surligné)
            segment?.let { s ->
                val x0 = (s.start.coerceIn(0f, 1f)) * w
                val x1 = (s.endInclusive.coerceIn(0f, 1f)) * w
                if (x1 > x0) drawRoundRect(
                    segColor,
                    topLeft = Offset(x0, 0f),
                    size = Size(x1 - x0, h),
                    cornerRadius = CornerRadius(6f, 6f)
                )
            }
            // Zones de transition
            for (t in transitions) {
                val x0 = (t.start.coerceIn(0f, 1f)) * w
                val x1 = (t.endInclusive.coerceIn(0f, 1f)) * w
                if (x1 > x0) drawRoundRect(
                    transColor,
                    topLeft = Offset(x0, 0f),
                    size = Size(x1 - x0, h),
                    cornerRadius = CornerRadius(6f, 6f)
                )
            }
            if (env != null && env.isNotEmpty()) {
                val n = env.size
                val step = w / n
                val bar = max(1f, step * 0.7f)
                for (k in 0 until n) {
                    val x = k * step + step / 2f
                    val amp = env[k] * (h / 2f - 1f)
                    val frac = (k + 0.5f) / n
                    val c = if (playhead != null && frac <= playhead) played else idle
                    drawLine(
                        c,
                        Offset(x, mid - amp),
                        Offset(x, mid + amp),
                        strokeWidth = bar
                    )
                }
            } else {
                drawLine(idle, Offset(0f, mid), Offset(w, mid), strokeWidth = 2f)
            }
            // Tête de lecture
            playhead?.let { p ->
                val x = p.coerceIn(0f, 1f) * w
                drawLine(headColor, Offset(x, 0f), Offset(x, h), strokeWidth = 3f)
            }
        }
    }
}

/**
 * Panneau waveform du lecteur (affiché à la place de la pochette) :
 * onde du morceau en cours + position + zones de transition, et onde du
 * morceau suivant quand il y en a un.
 */
@Composable
fun WaveformPanel(
    track: Track?,
    next: Track?,
    dj: Boolean,
    progress: Float,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var env by remember(track?.uri) { mutableStateOf<FloatArray?>(null) }
    var envNext by remember(next?.uri) { mutableStateOf<FloatArray?>(null) }
    LaunchedEffect(track?.uri) {
        track?.uri?.let { env = WaveformStore.load(context, it) }
    }
    LaunchedEffect(next?.uri) {
        next?.uri?.let { envNext = WaveformStore.load(context, it) }
    }

    // Durée approximative des fondus pour marquer les zones de transition
    val fadeMs = 7_000f

    Column(modifier) {
        val dur = (track?.durationMs ?: 0L).toFloat()
        var playhead: Float? = null
        var segRange: ClosedFloatingPointRange<Float>? = null
        var trans = emptyList<ClosedFloatingPointRange<Float>>()
        if (track != null && dur > 0f) {
            val segStart = track.bestStartMs / dur
            val segEnd = min(dur, (track.bestStartMs + track.segmentMs).toFloat()) / dur
            if (dj) {
                // En DJ, la progression couvre le passage fort uniquement
                playhead = segStart + progress.coerceIn(0f, 1f) * (segEnd - segStart)
                segRange = segStart..segEnd
                trans = listOf(
                    (segStart..min(1f, segStart + fadeMs / dur)),
                    (max(0f, segEnd - fadeMs / dur)..segEnd)
                )
            } else {
                playhead = progress.coerceIn(0f, 1f)
                if (track.analyzed && track.segmentMs > 0) segRange = segStart..segEnd
            }
        }
        WaveformView(
            env = env,
            playhead = playhead,
            segment = segRange,
            transitions = trans,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        )
        if (env == null && track != null) {
            Text(
                "Calcul de l'onde…",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }
        if (next != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                "Suivant : ${next.title}",
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            val durN = next.durationMs.toFloat()
            var segN: ClosedFloatingPointRange<Float>? = null
            var transN = emptyList<ClosedFloatingPointRange<Float>>()
            if (dj && durN > 0f) {
                val s = next.bestStartMs / durN
                val e = min(durN, (next.bestStartMs + next.segmentMs).toFloat()) / durN
                segN = s..e
                transN = listOf(s..min(1f, s + fadeMs / durN))
            }
            WaveformView(
                env = envNext,
                playhead = null,
                segment = segN,
                transitions = transN,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(30.dp)
            )
        }
    }
}
