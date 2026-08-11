package com.pulsemix.app.library

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.pulsemix.app.analysis.ShazamSignature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Reconnaissance « à la Shazam » : écoute le micro, calcule l'empreinte
 * spectrale ([ShazamSignature]) et interroge le service de reconnaissance
 * de Shazam — celui qu'utilisent les clients libres comme SongRec, sans
 * clé d'API. Sert à retrouver le titre d'un morceau qui joue dans la
 * pièce pour lancer la recherche YouTube dessus.
 *
 * L'appelant garantit que la permission RECORD_AUDIO est accordée.
 */
object SongRecognizer {

    sealed class State {
        object Idle : State()
        /** Écoute en cours ; [searching] pendant une interrogation. */
        class Listening(val elapsedSec: Int, val searching: Boolean) : State()
        class Found(val title: String, val artist: String) : State()
        class NotFound(val message: String) : State()
        class Error(val message: String) : State()
    }

    val state = MutableStateFlow<State>(State.Idle)

    /**
     * Garde d'entrée ATOMIQUE : le test-puis-écriture sur [state] laissait
     * une fenêtre (construction du micro) où un double-appui lançait deux
     * écoutes — deux micros, états entrelacés, requêtes en double.
     */
    private val running = java.util.concurrent.atomic.AtomicBoolean(false)

    @Volatile private var stopRequested = false

    private const val SAMPLE_RATE = 16_000

    /** Essais successifs : plus on écoute, plus l'empreinte est sûre. */
    private val ATTEMPT_SECONDS = intArrayOf(4, 8, 12)

    fun stop() {
        stopRequested = true
    }

    fun reset() {
        state.value = State.Idle
    }

    /** Écoute le micro et identifie ce qui joue ; l'état suit sur [state]. */
    suspend fun listenAndRecognize(): Unit = withContext(Dispatchers.IO) {
        if (!running.compareAndSet(false, true)) return@withContext
        try {
            listenLocked()
        } finally {
            running.set(false)
        }
    }

    private fun listenLocked() {
        stopRequested = false
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                // Tampon interne dimensionné au PIRE cas d'une interrogation
                // réseau (délais de connexion + lecture) : l'enregistrement
                // continue pendant qu'elle est en vol, sans écraser les
                // échantillons pas encore lus.
                maxOf(minBuf, SAMPLE_RATE * 2 * 16)
            )
        } catch (e: Exception) {
            state.value = State.Error("Micro inaccessible : ${e.message ?: "?"}")
            return
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            state.value = State.Error("Micro inaccessible.")
            return
        }
        val buffer = ShortArray(ATTEMPT_SECONDS.last() * SAMPLE_RATE)
        var filled = 0
        try {
            recorder.startRecording()
            state.value = State.Listening(0, searching = false)
            for (targetSec in ATTEMPT_SECONDS) {
                val target = targetSec * SAMPLE_RATE
                while (filled < target) {
                    if (stopRequested) return finishIdle()
                    val n = recorder.read(buffer, filled, minOf(2048, target - filled))
                    if (n <= 0) {
                        state.value = State.Error("Le micro ne fournit pas de son.")
                        return
                    }
                    filled += n
                    state.value = State.Listening(filled / SAMPLE_RATE, searching = false)
                }
                state.value = State.Listening(targetSec, searching = true)
                val signature = ShazamSignature.fromPcm(buffer.copyOf(filled))
                // Silence ou quasi : inutile d'interroger le service
                if (signature.peakCount < 8) continue
                when (val r = query(signature)) {
                    is QueryResult.Found -> {
                        state.value = State.Found(r.title, r.artist)
                        return
                    }
                    is QueryResult.NoMatch -> {} // essai suivant, plus long
                    is QueryResult.Failed -> {
                        state.value = State.Error(r.message)
                        return
                    }
                }
                if (stopRequested) return finishIdle()
            }
            state.value = State.NotFound(
                "Rien reconnu. Rapproche-toi de la source ou monte le son."
            )
        } catch (e: SecurityException) {
            state.value = State.Error("Permission micro refusée.")
        } catch (e: Exception) {
            state.value = State.Error(e.message ?: "Échec de l'écoute.")
        } finally {
            try {
                recorder.stop()
            } catch (_: Exception) {
            }
            recorder.release()
        }
    }

    private fun finishIdle() {
        state.value = State.Idle
    }

    private sealed class QueryResult {
        class Found(val title: String, val artist: String) : QueryResult()
        object NoMatch : QueryResult()
        class Failed(val message: String) : QueryResult()
    }

    private fun query(signature: ShazamSignature.Signature): QueryResult {
        val timestamp = System.currentTimeMillis() and 0xFFFFFFFFL
        val body = JSONObject()
            .put(
                "geolocation",
                JSONObject().put("altitude", 300).put("latitude", 45).put("longitude", 2)
            )
            .put(
                "signature",
                JSONObject()
                    .put("samplems", signature.sampleMs)
                    .put("timestamp", timestamp)
                    .put("uri", signature.encodeToUri())
            )
            .put("timestamp", timestamp)
            .put("timezone", "Europe/Paris")
            .toString()
        val url = "https://amp.shazam.com/discovery/v5/en/US/android/-/tag/" +
            "${UUID.randomUUID().toString().uppercase()}/${UUID.randomUUID()}" +
            "?sync=true&webv3=true&sampling=true&connected=" +
            "&shazamapiversion=v3&sharehub=true&video=v3"
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                // Bornés pour que le pire cas (connexion + lecture, ~13 s)
                // tienne dans le tampon du micro qui enregistre pendant ce
                // temps — au-delà, des échantillons seraient écrasés.
                connectTimeout = 5_000
                readTimeout = 8_000
                setRequestProperty(
                    "User-Agent",
                    "Dalvik/2.1.0 (Linux; U; Android 12; Pixel 6 Build/SP2A.220505.002)"
                )
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Content-Language", "en_US")
            }
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            conn.disconnect()
            if (code == 429) {
                return QueryResult.Failed("Service saturé (réessaie dans un instant).")
            }
            if (code !in 200..299) {
                return QueryResult.Failed("Reconnaissance refusée (HTTP $code).")
            }
            val root = JSONObject(text)
            val track = root.optJSONObject("track")
                ?: return QueryResult.NoMatch
            val title = track.optString("title", "")
            if (title.isBlank()) return QueryResult.NoMatch
            QueryResult.Found(title, track.optString("subtitle", ""))
        } catch (e: Exception) {
            QueryResult.Failed(
                "Service injoignable : " +
                    (e.message ?: e::class.java.simpleName).take(100)
            )
        }
    }
}
