package com.pulsemix.app.analysis

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri

/**
 * Décode n'importe quel fichier audio supporté par Android (mp3, m4a/aac, flac,
 * ogg, opus, wav...) en PCM float interleavé, via MediaExtractor + MediaCodec.
 *
 * Le sink reçoit (pcm interleavé, nbFrames, sampleRate, channels) et renvoie
 * false pour interrompre le décodage.
 */
class AudioDecoder {

    fun decode(
        context: Context,
        uri: Uri,
        startUs: Long = 0L,
        maxDurationUs: Long = Long.MAX_VALUE,
        sink: (pcm: FloatArray, frames: Int, sampleRate: Int, channels: Int) -> Boolean
    ): Boolean {
        // WMA : ni MediaExtractor ni MediaCodec ne savent le lire (sauf
        // quelques constructeurs) — décodeur natif embarqué
        if (com.pulsemix.app.audio.WmaFiles.isWma(context, uri) &&
            com.pulsemix.app.audio.WmaFiles.available
        ) {
            return decodeWma(context, uri, startUs, maxDurationUs, sink)
        }
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            if (trackIndex < 0 || format == null) return false
            extractor.selectTrack(trackIndex)
            if (startUs > 0) extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val mime = format.getString(MediaFormat.KEY_MIME) ?: return false
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            var sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE))
                format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
            var channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
            var floatPcm = false

            val endUs = if (maxDurationUs == Long.MAX_VALUE) Long.MAX_VALUE
            else startUs + maxDurationUs

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var keepGoing = true
            // Garde-fou : certains fichiers corrompus font tourner le codec
            // sans jamais produire de sortie ni d'erreur. ~8 s sans progrès
            // -> on abandonne le fichier au lieu de bloquer l'analyse.
            var idleRounds = 0

            while (!outputDone && keepGoing) {
                var progressed = false
                if (!inputDone) {
                    val inIx = codec.dequeueInputBuffer(10_000)
                    if (inIx >= 0) {
                        progressed = true
                        val buf = codec.getInputBuffer(inIx)
                        val size = if (buf != null) extractor.readSampleData(buf, 0) else -1
                        val sampleTime = extractor.sampleTime
                        if (size < 0 || (endUs != Long.MAX_VALUE && sampleTime > endUs)) {
                            codec.queueInputBuffer(
                                inIx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIx, 0, size, sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIx = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        progressed = true
                        val f = codec.outputFormat
                        if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE))
                            sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                            channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        floatPcm = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                                f.getInteger(MediaFormat.KEY_PCM_ENCODING) ==
                                AudioFormat.ENCODING_PCM_FLOAT
                    }
                    outIx >= 0 -> {
                        progressed = true
                        // Tampon qui CHEVAUCHE le point de départ (seek sur la
                        // trame de synchro d'avant) : gardé à partir de
                        // startUs, à l'échantillon près. Le jeter entier
                        // faisait démarrer le deck jusqu'à ~25 ms trop tard —
                        // l'attaque d'un drop, pile ce que le drop swap vise.
                        val ptsUs = info.presentationTimeUs
                        val overlaps = ptsUs < startUs && sampleRate > 0 &&
                            channels > 0 && info.size > 0 &&
                            ptsUs + (info.size / (channels * (if (floatPcm) 4 else 2))) *
                            1_000_000L / sampleRate > startUs
                        if (info.size > 0 && (ptsUs >= startUs || overlaps)) {
                            val ob = codec.getOutputBuffer(outIx)
                            if (ob != null) {
                                ob.position(info.offset)
                                ob.limit(info.offset + info.size)
                                val pcm: FloatArray
                                if (floatPcm) {
                                    val fb = ob.asFloatBuffer()
                                    pcm = FloatArray(fb.remaining())
                                    fb.get(pcm)
                                } else {
                                    val sb = ob.asShortBuffer()
                                    val nSamp = sb.remaining()
                                    pcm = FloatArray(nSamp)
                                    for (k in 0 until nSamp) {
                                        pcm[k] = sb.get(k) / 32768f
                                    }
                                }
                                val ch = if (channels < 1) 1 else channels
                                val skip = if (overlaps)
                                    ((startUs - ptsUs) * sampleRate / 1_000_000L).toInt()
                                        .coerceIn(0, pcm.size / ch)
                                else 0
                                val kept = if (skip > 0) pcm.copyOfRange(skip * ch, pcm.size)
                                else pcm
                                if (kept.isNotEmpty()) {
                                    keepGoing = sink(kept, kept.size / ch, sampleRate, ch)
                                }
                            }
                        }
                        codec.releaseOutputBuffer(outIx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                        if (endUs != Long.MAX_VALUE && info.presentationTimeUs > endUs) {
                            keepGoing = false
                        }
                    }
                }
                if (progressed) {
                    idleRounds = 0
                } else if (++idleRounds > 400) {
                    // ~8 s sans entrée ni sortie : fichier indécodable, abandon
                    return false
                }
            }
            return true
        } catch (e: Exception) {
            return false
        } finally {
            try {
                codec?.stop()
            } catch (_: Exception) {
            }
            try {
                codec?.release()
            } catch (_: Exception) {
            }
            try {
                extractor.release()
            } catch (_: Exception) {
            }
        }
    }

    /** Même contrat que [decode], par le décodeur WMA natif. */
    private fun decodeWma(
        context: Context,
        uri: Uri,
        startUs: Long,
        maxDurationUs: Long,
        sink: (pcm: FloatArray, frames: Int, sampleRate: Int, channels: Int) -> Boolean
    ): Boolean = try {
        com.pulsemix.app.audio.WmaFiles.open(context, uri).use { o ->
            val d = o.decoder
            val ch = d.channels
            val rate = d.sampleRate
            if (startUs > 0) d.seekUs(startUs)
            // Trames encore à rendre (fenêtre [startUs, startUs + maxDurationUs])
            var left = if (maxDurationUs == Long.MAX_VALUE) Long.MAX_VALUE
            else maxDurationUs * rate / 1_000_000L
            var keepGoing = true
            var bytes = ByteArray(0)
            while (keepGoing && left > 0) {
                var len = 0
                val n = d.decodeNext { p, l ->
                    if (bytes.size < l) bytes = ByteArray(l)
                    p.get(bytes, 0, l)
                    len = l
                }
                if (n < 0) break
                var frames = len / (2 * ch)
                if (frames.toLong() > left) frames = left.toInt()
                val pcm = FloatArray(frames * ch)
                for (i in pcm.indices) {
                    val lo = bytes[2 * i].toInt() and 0xFF
                    val hi = bytes[2 * i + 1].toInt()
                    pcm[i] = ((hi shl 8) or lo).toShort() / 32768f
                }
                left -= frames
                keepGoing = sink(pcm, frames, rate, ch)
            }
            true
        }
    } catch (e: Throwable) {
        com.pulsemix.app.audio.WmaFiles.log("décodage impossible : ${e.message}")
        false
    }
}
