package com.pulsemix.app.audio

import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avutil.AVChannelLayout
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avformat
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.ffmpeg.global.swresample
import org.bytedeco.ffmpeg.swresample.SwrContext
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.Pointer
import org.bytedeco.javacpp.PointerPointer
import java.io.Closeable

/**
 * Décodeur WMA NATIF : la bibliothèque ffmpeg embarquée (bytedeco) lit le
 * conteneur ASF et décode le Windows Media Audio (v1, v2, Pro, Lossless)
 * en PCM 16 bits entrelacé, mono ou stéréo, à la fréquence d'origine.
 *
 * Android n'en est pas capable (ni le conteneur ni le codec ne sont dans
 * AOSP), et ExoPlayer n'a aucun extracteur ASF : c'est ce décodeur qui
 * alimente la lecture ([WmaDataSource]), l'analyse et le moteur DJ
 * ([com.pulsemix.app.analysis.AudioDecoder]).
 *
 * Aucune dépendance Android : [path] est un chemin lisible par ffmpeg
 * (sur le téléphone, « /proc/self/fd/N » d'un descripteur SAF ouvert).
 * Une instance n'est pas partagée entre threads.
 */
class WmaDecoder(path: String) : Closeable {

    private var fmt: AVFormatContext? = AVFormatContext(null as Pointer?)
    private var ctx: AVCodecContext? = null
    private var swr: SwrContext? = null
    private val pkt: AVPacket = avcodec.av_packet_alloc()
    private val frame: AVFrame = avutil.av_frame_alloc()
    private val streamIndex: Int
    private val tbNum: Int
    private val tbDen: Int

    val sampleRate: Int
    /** Canaux en sortie (1 ou 2 : au-delà, mixage stéréo). */
    val channels: Int
    /** Durée annoncée par le fichier (µs), 0 si inconnue. */
    val durationUs: Long

    /** Titre, artiste, album, genre… (clés en minuscules). */
    val tags: Map<String, String>

    private var outBuf: BytePointer? = null
    private var outPtrs: PointerPointer<BytePointer>? = null
    private var outCapFrames = 0
    private var eof = false

    // ------------------------------------------------- repère temporel
    // Les horodates ASF (ms) ont un décalage constant : la première trame
    // du fichier est datée du délai du codec (~92 ms), pas de 0. Elles
    // sont donc lues RELATIVEMENT à celle de la première trame ([originTs]).
    // Après un seek, les trames partielles (reste d'une super-trame) ont
    // une horodate fausse : on les jette jusqu'à la première trame pleine
    // ([blockLen]), dont l'horodate est juste ; ensuite on COMPTE les
    // échantillons. Vérifié contre un décodage séquentiel : exact à
    // l'échantillon près.
    private var originTs = 0L
    private var blockLen = 0
    /** Index (en trames PCM) du prochain échantillon décodé. */
    private var pos = 0L
    private var aligning = false
    /** Index visé après un seek : ce qui précède est jeté. */
    private var targetIdx = -1L
    /** La première trame, décodée à l'ouverture pour le repère, reste à
     *  rendre. */
    private var pendingFrame = false

    init {
        try {
            val f = fmt!!
            check(avformat.avformat_open_input(f, path, null, null) >= 0) { "ouverture impossible" }
            check(avformat.avformat_find_stream_info(f, null as PointerPointer<*>?) >= 0) { "flux illisible" }
            streamIndex = avformat.av_find_best_stream(
                f, avutil.AVMEDIA_TYPE_AUDIO, -1, -1, null as PointerPointer<*>?, 0
            )
            check(streamIndex >= 0) { "aucune piste audio" }
            val st = f.streams(streamIndex)
            tbNum = st.time_base().num()
            tbDen = st.time_base().den()
            val codec = avcodec.avcodec_find_decoder(st.codecpar().codec_id())
                ?: error("codec non pris en charge")
            val c = avcodec.avcodec_alloc_context3(codec)
            ctx = c
            check(avcodec.avcodec_parameters_to_context(c, st.codecpar()) >= 0) { "paramètres invalides" }
            check(avcodec.avcodec_open2(c, codec, null as AVDictionary?) >= 0) { "décodeur indisponible" }
            sampleRate = c.sample_rate()
            val inCh = c.ch_layout().nb_channels().coerceAtLeast(1)
            channels = inCh.coerceAtMost(2)
            val outLayout = AVChannelLayout()
            avutil.av_channel_layout_default(outLayout, channels)
            val s = SwrContext(null as Pointer?)
            check(
                swresample.swr_alloc_set_opts2(
                    s, outLayout, avutil.AV_SAMPLE_FMT_S16, sampleRate,
                    c.ch_layout(), c.sample_fmt(), sampleRate, 0, null
                ) >= 0 && swresample.swr_init(s) >= 0
            ) { "conversion PCM impossible" }
            swr = s
            durationUs = f.duration().takeIf { it > 0 } ?: 0L
            val t = HashMap<String, String>()
            for (k in listOf("title", "artist", "album", "genre", "album_artist", "date", "track")) {
                val e = avutil.av_dict_get(f.metadata(), k, null, 0) ?: continue
                e.value()?.getString()?.trim()?.takeIf { it.isNotEmpty() }?.let { t[k] = it }
            }
            tags = t
            primeOrigin()
        } catch (e: Throwable) {
            close()
            throw e
        }
    }

    /** Décode la première trame : son horodate est l'origine du temps et
     *  sa longueur celle d'une trame pleine. Elle sera rendue telle quelle
     *  au premier [decodeNext]. */
    private fun primeOrigin() {
        val c = ctx ?: return
        val f = fmt ?: return
        while (true) {
            if (avcodec.avcodec_receive_frame(c, frame) >= 0) {
                val ts = frame.best_effort_timestamp()
                originTs = if (ts == avutil.AV_NOPTS_VALUE) 0L else ts
                blockLen = frame.nb_samples()
                pendingFrame = true
                return
            }
            if (avformat.av_read_frame(f, pkt) < 0) {
                eof = true
                return
            }
            if (pkt.stream_index() == streamIndex) avcodec.avcodec_send_packet(c, pkt)
            avcodec.av_packet_unref(pkt)
        }
    }

    /** Recale le décodage sur [us], à l'échantillon près : seek en amont,
     *  puis la tête est jetée (voir le repère temporel). */
    fun seekUs(us: Long) = seekFrames(us.coerceAtLeast(0L) * sampleRate / 1_000_000L)

    /** Recale le décodage sur la trame PCM d'index [index]. */
    fun seekFrames(index: Long) {
        val f = fmt ?: return
        if (pendingFrame) {
            avutil.av_frame_unref(frame)
            pendingFrame = false
        }
        targetIdx = index.coerceAtLeast(0L)
        val target = targetIdx * 1_000_000L / sampleRate
        // Une demi-seconde en amont : la cible tombe toujours après la
        // première trame pleine
        val back = (target - 500_000L).coerceAtLeast(0L)
        val ts = originTs + back * tbDen / (1_000_000L * tbNum)
        avformat.av_seek_frame(f, streamIndex, if (back == 0L) 0L else ts, avformat.AVSEEK_FLAG_BACKWARD)
        avcodec.avcodec_flush_buffers(ctx)
        eof = false
        aligning = true
    }

    /** Position (en trames PCM) du prochain échantillon rendu. */
    val positionFrames: Long get() = if (targetIdx >= 0) targetIdx else pos

    /**
     * Décode le bloc suivant : PCM 16 bits entrelacé (petit-boutiste),
     * [channels] canaux. @return le nombre d'octets écrits dans [sink]
     * (appelé une fois par bloc), ou -1 à la fin du fichier.
     */
    fun decodeNext(sink: (BytePointer, Int) -> Unit): Int {
        val c = ctx ?: return -1
        val f = fmt ?: return -1
        if (pendingFrame) {
            pendingFrame = false
            val n = convert(sink)
            if (n > 0) return n
        }
        while (true) {
            val r = avcodec.avcodec_receive_frame(c, frame)
            if (r >= 0) {
                val n = convert(sink)
                if (n > 0) return n
                continue
            }
            if (r == avutil.AVERROR_EOF()) return -1
            // Besoin d'un paquet de plus
            if (eof) return -1
            val rr = avformat.av_read_frame(f, pkt)
            if (rr < 0) {
                eof = true
                avcodec.avcodec_send_packet(c, null as AVPacket?) // vidange
                continue
            }
            if (pkt.stream_index() == streamIndex) avcodec.avcodec_send_packet(c, pkt)
            avcodec.av_packet_unref(pkt)
        }
    }

    private fun convert(sink: (BytePointer, Int) -> Unit): Int {
        val inFrames = frame.nb_samples()
        if (aligning) {
            val ts = frame.best_effort_timestamp()
            if (inFrames < blockLen || ts == avutil.AV_NOPTS_VALUE) {
                avutil.av_frame_unref(frame)
                return 0
            }
            val num = (ts - originTs) * tbNum * sampleRate
            val raw = (num + tbDen / 2) / tbDen
            // Horodates à la milliseconde : quelques échantillons d'erreur.
            // Les trames pleines commencent toutes sur un multiple de leur
            // longueur (la première est à 0) : on s'y cale.
            val snapped = if (blockLen > 0) (raw + blockLen / 2) / blockLen * blockLen else raw
            pos = if (kotlin.math.abs(snapped - raw) < blockLen / 4) snapped else raw
            aligning = false
        }
        val start = pos
        pos += inFrames
        var skip = 0
        if (targetIdx >= 0) {
            if (start + inFrames <= targetIdx) {
                avutil.av_frame_unref(frame)
                return 0
            }
            skip = (targetIdx - start).coerceIn(0L, inFrames.toLong()).toInt()
            targetIdx = -1L
        }
        if (inFrames > outCapFrames) {
            outBuf?.deallocate()
            outCapFrames = inFrames + 1024
            outBuf = BytePointer((outCapFrames * channels * 2).toLong())
        }
        val out = outBuf!!
        // PointerPointer(out) appellerait le constructeur de COPIE (out lu
        // comme un tableau de pointeurs) : le tableau est rempli à la main.
        val outs = outPtrs ?: PointerPointer<BytePointer>(1L).also { outPtrs = it }
        outs.put(0L, out)
        val got = swresample.swr_convert(
            swr, outs, outCapFrames,
            frame.extended_data(), inFrames
        )
        avutil.av_frame_unref(frame)
        if (got <= 0) return 0
        val frames = got - skip.coerceAtMost(got)
        if (frames <= 0) return 0
        val bytesPerFrame = channels * 2
        val slice = BytePointer(out).position((skip * bytesPerFrame).toLong())
        sink(slice, frames * bytesPerFrame)
        return frames * bytesPerFrame
    }

    override fun close() {
        swr?.let { swresample.swr_free(it) }
        swr = null
        ctx?.let { avcodec.avcodec_free_context(it) }
        ctx = null
        fmt?.let { if (!it.isNull) avformat.avformat_close_input(it) }
        fmt = null
        try {
            avcodec.av_packet_free(pkt)
        } catch (_: Throwable) {
        }
        try {
            avutil.av_frame_free(frame)
        } catch (_: Throwable) {
        }
        outBuf?.deallocate()
        outBuf = null
        outPtrs?.deallocate()
        outPtrs = null
    }
}
