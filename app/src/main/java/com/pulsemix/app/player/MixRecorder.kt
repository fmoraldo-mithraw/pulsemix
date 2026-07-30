package com.pulsemix.app.player

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File

/**
 * Enregistre le bus de sortie du moteur DJ (PCM float stéréo 44,1 kHz)
 * en fichier M4A/AAC 192 kbit/s.
 */
class MixRecorder(outFile: File) {

    private val codec: MediaCodec
    private val muxer: MediaMuxer
    private var trackIndex = -1
    private var muxerStarted = false
    private var presentationUs = 0L
    private val info = MediaCodec.BufferInfo()
    private var closed = false

    init {
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC, 44_100, 2
        )
        format.setInteger(
            MediaFormat.KEY_AAC_PROFILE,
            MediaCodecInfo.CodecProfileLevel.AACObjectLC
        )
        format.setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    /** Bloc stéréo entrelacé ; samples = nombre de floats. */
    @Synchronized
    fun write(pcm: FloatArray, samples: Int) {
        if (closed) return
        try {
            var fed = 0
            var guard = 0
            while (fed < samples && guard < 8) {
                val inIx = codec.dequeueInputBuffer(2_000)
                if (inIx < 0) {
                    drain()
                    guard++
                    continue
                }
                val buf = codec.getInputBuffer(inIx) ?: break
                buf.clear()
                val n = minOf(buf.capacity() / 2, samples - fed)
                for (k in 0 until n) {
                    val v = (pcm[fed + k] * 32767f).toInt().coerceIn(-32768, 32767)
                    buf.putShort(v.toShort())
                }
                codec.queueInputBuffer(inIx, 0, n * 2, presentationUs, 0)
                presentationUs += (n / 2) * 1_000_000L / 44_100L
                fed += n
                drain()
            }
        } catch (_: Exception) {
        }
    }

    private fun drain() {
        while (true) {
            val outIx = codec.dequeueOutputBuffer(info, 0)
            when {
                outIx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    trackIndex = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                outIx >= 0 -> {
                    val ob = codec.getOutputBuffer(outIx)
                    if (ob != null && info.size > 0 && muxerStarted &&
                        info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                    ) {
                        muxer.writeSampleData(trackIndex, ob, info)
                    }
                    codec.releaseOutputBuffer(outIx, false)
                }
                else -> return
            }
        }
    }

    @Synchronized
    fun stop() {
        if (closed) return
        closed = true
        try {
            val inIx = codec.dequeueInputBuffer(10_000)
            if (inIx >= 0) {
                codec.queueInputBuffer(
                    inIx, 0, 0, presentationUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                )
            }
            drain()
        } catch (_: Exception) {
        }
        try {
            codec.stop()
            codec.release()
        } catch (_: Exception) {
        }
        try {
            if (muxerStarted) muxer.stop()
            muxer.release()
        } catch (_: Exception) {
        }
    }
}
