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
                        if (info.size > 0 && info.presentationTimeUs >= startUs) {
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
                                keepGoing = sink(pcm, pcm.size / ch, sampleRate, ch)
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
}
