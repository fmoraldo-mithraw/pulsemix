package com.pulsemix.app.audio

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Source ExoPlayer qui rend un WMA sous forme de WAV, décodé à la volée
 * par [WmaDecoder] : ExoPlayer n'a pas d'extracteur ASF, mais lit le WAV
 * (extracteur intégré, seek par octet) — le WMA se lit, se déplace, se
 * mixe comme n'importe quel morceau.
 *
 * Le flux : un en-tête WAV de 44 octets puis le PCM 16 bits. Sa taille
 * est déduite de la durée annoncée par le fichier ; si le décodage
 * s'arrête un peu avant, la fin est complétée de silence. Une ouverture
 * à la position P (seek d'ExoPlayer) recale le décodeur sur l'échantillon
 * correspondant, exactement.
 */
class WmaDataSource(private val context: Context) : BaseDataSource(/* isNetwork = */ false) {

    private var opened: WmaFiles.Opened? = null
    private var uri: Uri? = null
    private var header: ByteArray = ByteArray(0)
    private var bytesPerFrame = 4
    /** Position courante dans le flux WAV, et sa taille totale. */
    private var position = 0L
    private var total = 0L
    /** Octets restant à rendre pour cette ouverture (C.LENGTH_UNSET : jusqu'au bout). */
    private var remaining = 0L
    /** PCM décodé pas encore rendu. */
    private var pcm = ByteArray(0)
    private var pcmOff = 0
    private var pcmLen = 0
    private var decoderDone = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val o = try {
            WmaFiles.open(context, dataSpec.uri)
        } catch (e: Throwable) {
            throw IOException("WMA illisible : ${e.message}", e)
        }
        opened = o
        val d = o.decoder
        bytesPerFrame = d.channels * 2
        val frames = d.durationUs * d.sampleRate / 1_000_000L
        val dataBytes = frames * bytesPerFrame
        header = wavHeader(d.sampleRate, d.channels, dataBytes)
        total = HEADER + dataBytes
        position = dataSpec.position
        pendingSilence = 0
        if (position > total) throw IOException("position hors du fichier")
        // Données : recalage du décodeur sur la trame correspondante. Une
        // position au milieu d'une trame (ne devrait pas arriver) est
        // alignée sur la trame suivante, le reste rendu en silence.
        if (position > HEADER) {
            val pcmPos = position - HEADER
            val frame = (pcmPos + bytesPerFrame - 1) / bytesPerFrame
            if (frame > 0) d.seekFrames(frame)
            pendingSilence = (frame * bytesPerFrame - pcmPos).toInt()
        }
        pcmOff = 0
        pcmLen = 0
        decoderDone = false
        remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length
        else total - position
        transferStarted(dataSpec)
        return remaining
    }

    private var pendingSilence = 0

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining <= 0L || position >= total) return C.RESULT_END_OF_INPUT
        val want = minOf(length.toLong(), remaining, total - position).toInt()
        var n = 0
        // En-tête
        if (position < HEADER) {
            val k = minOf(want, (HEADER - position).toInt())
            System.arraycopy(header, position.toInt(), buffer, offset, k)
            n = k
        }
        if (n < want && pendingSilence > 0) {
            val k = minOf(want - n, pendingSilence)
            java.util.Arrays.fill(buffer, offset + n, offset + n + k, 0)
            pendingSilence -= k
            n += k
        }
        while (n < want) {
            if (pcmLen == 0) {
                if (!refill()) {
                    // Fin du décodage avant la durée annoncée : silence
                    java.util.Arrays.fill(buffer, offset + n, offset + want, 0)
                    n = want
                    break
                }
            }
            val k = minOf(want - n, pcmLen)
            System.arraycopy(pcm, pcmOff, buffer, offset + n, k)
            pcmOff += k
            pcmLen -= k
            n += k
        }
        position += n
        remaining -= n
        bytesTransferred(n)
        return n
    }

    private fun refill(): Boolean {
        if (decoderDone) return false
        val d = opened?.decoder ?: return false
        val got = try {
            d.decodeNext { p, len ->
                if (pcm.size < len) pcm = ByteArray(len)
                p.get(pcm, 0, len)
            }
        } catch (e: Throwable) {
            WmaFiles.log("décodage interrompu : ${e.message}")
            -1
        }
        if (got <= 0) {
            decoderDone = true
            return false
        }
        pcmOff = 0
        pcmLen = got
        return true
    }

    override fun getUri(): Uri? = uri

    /** Annonce du WAV : l'extracteur WAV est essayé en premier. */
    override fun getResponseHeaders(): Map<String, List<String>> =
        mapOf("Content-Type" to listOf("audio/wav"))

    override fun close() {
        val o = opened
        opened = null
        if (o != null) {
            o.close()
            transferEnded()
        }
        uri = null
    }

    companion object {
        const val HEADER = 44

        fun wavHeader(rate: Int, channels: Int, dataBytes: Long): ByteArray {
            val data = dataBytes.coerceAtMost(0xFFFF_FFFFL - 36L)
            val b = ByteBuffer.allocate(HEADER).order(ByteOrder.LITTLE_ENDIAN)
            b.put("RIFF".toByteArray(Charsets.US_ASCII))
            b.putInt((36L + data).toInt())
            b.put("WAVE".toByteArray(Charsets.US_ASCII))
            b.put("fmt ".toByteArray(Charsets.US_ASCII))
            b.putInt(16)
            b.putShort(1) // PCM
            b.putShort(channels.toShort())
            b.putInt(rate)
            b.putInt(rate * channels * 2)
            b.putShort((channels * 2).toShort())
            b.putShort(16)
            b.put("data".toByteArray(Charsets.US_ASCII))
            b.putInt(data.toInt())
            return b.array()
        }
    }
}

/**
 * Fabrique de sources du lecteur : les WMA passent par [WmaDataSource],
 * tout le reste par la source par défaut (fichiers, content://, SAF…).
 * Le choix se fait à l'ouverture, d'après l'URI.
 */
class PulseDataSourceFactory(context: Context) : DataSource.Factory {
    private val app = context.applicationContext
    private val default = DefaultDataSource.Factory(app)

    override fun createDataSource(): DataSource = Routing(app, default.createDataSource())

    private class Routing(
        private val context: Context,
        private val fallback: DataSource
    ) : DataSource {
        private var current: DataSource? = null
        private val listeners = ArrayList<TransferListener>()

        override fun addTransferListener(transferListener: TransferListener) {
            listeners.add(transferListener)
            fallback.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            val src = if (WmaFiles.available && WmaFiles.isWma(context, dataSpec.uri)) {
                WmaDataSource(context).also { w -> listeners.forEach { w.addTransferListener(it) } }
            } else fallback
            current = src
            return src.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            current?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT

        override fun getUri(): Uri? = current?.uri

        override fun getResponseHeaders(): Map<String, List<String>> =
            current?.responseHeaders ?: emptyMap()

        override fun close() {
            val c = current
            current = null
            c?.close()
        }
    }
}
