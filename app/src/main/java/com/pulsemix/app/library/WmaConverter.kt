package com.pulsemix.app.library

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Lecture des fichiers WMA.
 *
 * La lecture passe par ExoPlayer, qui n'a AUCUN extracteur pour le
 * conteneur ASF : un WMA ne peut pas y être lu, même sur les téléphones
 * dont le système sait décoder le Windows Media (Samsung…). L'ancienne
 * version ne convertissait pas ces fichiers-là (« le téléphone sait les
 * lire ») — ils entraient dans la bibliothèque pour rester muets. Tout WMA
 * est donc converti, une fois pour toutes, en M4A (AAC) :
 *
 *  1. avec le ffmpeg embarqué (import depuis les plateformes) ;
 *  2. à défaut, avec les codecs du téléphone (MediaExtractor + MediaCodec
 *     décodent, l'encodeur AAC du système ré-encode) ;
 *
 * Le M4A est déposé à côté de l'original, ou, si le dossier n'est pas
 * accessible en écriture, dans le stockage de l'appli
 * (Android/data/com.pulsemix.app/files/WMA convertis). Le fichier converti
 * est ensuite un morceau comme un autre : lisible, analysable, mixable.
 *
 * L'original n'est pas touché.
 */
object WmaConverter {

    /** Suffixe des fichiers produits : sert aussi à les reconnaître. */
    private const val SUFFIX = " (converti).m4a"

    /** Un M4A plus petit est une conversion ratée (fichier vide, coupé). */
    private const val MIN_BYTES = 4 * 1024L

    fun isWma(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() == "wma"

    /** Nom du fichier converti correspondant à [wmaName]. */
    fun convertedName(wmaName: String): String =
        wmaName.substringBeforeLast('.') + SUFFIX

    /** Dossier de repli (stockage de l'appli). */
    private fun localDir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "WMA convertis")
            .apply { mkdirs() }

    /** Copie de repli d'un WMA : nom + empreinte de son URI, deux WMA de
     *  même nom dans deux dossiers ne s'écrasent pas. */
    private fun localFile(context: Context, src: DocumentFile): File {
        val name = src.name ?: "piste.wma"
        val tag = Integer.toHexString(src.uri.toString().hashCode())
        return File(localDir(context), name.substringBeforeLast('.') + " [$tag]" + SUFFIX)
    }

    /**
     * Conversion déjà faite pour [src] (à côté de l'original ou en repli),
     * ou null. Une conversion ratée (trop petite) est effacée pour être
     * refaite.
     */
    fun existing(context: Context, src: DocumentFile, parent: DocumentFile?): DocumentFile? {
        val name = src.name ?: return null
        parent?.findFile(convertedName(name))?.let { doc ->
            if (doc.length() >= MIN_BYTES) return doc
            log("conversion ratée trouvée ($name, ${doc.length()} o) : refaite")
            try {
                doc.delete()
            } catch (_: Exception) {
            }
        }
        val local = localFile(context, src)
        if (local.isFile) {
            if (local.length() >= MIN_BYTES) return DocumentFile.fromFile(local)
            local.delete()
        }
        return null
    }

    /**
     * Convertit [src] en M4A. @return le fichier converti (à côté de
     * l'original, ou dans le dossier de repli), ou null en cas d'échec —
     * journalisé avec la raison.
     */
    fun convert(
        context: Context,
        src: DocumentFile,
        parent: DocumentFile?
    ): DocumentFile? {
        val name = src.name ?: return null
        existing(context, src, parent)?.let { return it }

        val cacheDir = File(context.cacheDir, "wma").apply { mkdirs() }
        val inFile = File(cacheDir, "in.wma")
        val outFile = File(cacheDir, "out.m4a")
        outFile.delete()
        try {
            // ffmpeg ne sait pas lire une URI SAF : on lui donne un fichier
            context.contentResolver.openInputStream(src.uri).use { i ->
                if (i == null) {
                    log("$name : illisible (flux nul)")
                    return null
                }
                inFile.outputStream().use { o -> i.copyTo(o, 64 * 1024) }
            }
            val how = when {
                withFfmpeg(context, inFile, outFile, name) -> "ffmpeg"
                withPlatform(context, Uri.fromFile(inFile), outFile, name) -> "codecs du téléphone"
                else -> {
                    log(
                        "$name : conversion impossible (ffmpeg et codecs du téléphone " +
                            "ont échoué — WMA protégé par DRM ?)"
                    )
                    return null
                }
            }
            val doc = store(context, src, parent, outFile, name)
            if (doc != null) log("$name converti ($how) → ${doc.uri}")
            return doc
        } catch (e: Exception) {
            log("$name : conversion impossible : ${e::class.java.simpleName} ${e.message}")
            return null
        } finally {
            inFile.delete()
            outFile.delete()
        }
    }

    /** Range le M4A : à côté de l'original si possible, sinon en repli. */
    private fun store(
        context: Context,
        src: DocumentFile,
        parent: DocumentFile?,
        outFile: File,
        name: String
    ): DocumentFile? {
        if (parent != null) {
            try {
                val doc = parent.createFile("audio/mp4", convertedName(name))
                if (doc != null) {
                    context.contentResolver.openOutputStream(doc.uri).use { o ->
                        if (o == null) throw IllegalStateException("flux d'écriture nul")
                        outFile.inputStream().use { it.copyTo(o, 64 * 1024) }
                    }
                    if (doc.length() >= MIN_BYTES) return doc
                    doc.delete()
                }
                log("$name : dossier non inscriptible, copie dans le stockage de l'appli")
            } catch (e: Exception) {
                log("$name : écriture dans le dossier impossible (${e.message}), copie dans le stockage de l'appli")
            }
        }
        return try {
            val local = localFile(context, src)
            outFile.copyTo(local, overwrite = true)
            DocumentFile.fromFile(local)
        } catch (e: Exception) {
            log("$name : copie locale impossible : ${e.message}")
            null
        }
    }

    // ------------------------------------------------------------- ffmpeg

    private fun withFfmpeg(context: Context, inFile: File, outFile: File, name: String): Boolean {
        val cmd = FfmpegBin.command(context)
        if (cmd == null) {
            log("$name : ffmpeg indisponible, essai avec les codecs du téléphone")
            return false
        }
        return try {
            outFile.delete()
            val pb = ProcessBuilder(
                cmd + listOf(
                    "-hide_banner", "-loglevel", "error", "-y",
                    "-i", inFile.absolutePath,
                    "-vn", "-map", "0:a:0", "-c:a", "aac", "-b:a", "192k",
                    // Mono / stéréo : l'AAC multicanal passe mal partout
                    "-ac", "2",
                    "-f", "mp4",
                    outFile.absolutePath
                )
            )
            pb.environment().putAll(FfmpegBin.env())
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val out = proc.inputStream.bufferedReader().use { it.readText() }
            val code = proc.waitFor()
            val ok = code == 0 && outFile.length() >= MIN_BYTES
            if (!ok) log("$name : ffmpeg a échoué (code $code) : ${out.take(300)}")
            ok
        } catch (e: Exception) {
            log("$name : ffmpeg a échoué : ${e.message}")
            false
        }
    }

    // ------------------------------------------------- codecs du téléphone

    /**
     * Décodage par le système (MediaExtractor + décodeur WMA du téléphone,
     * présent chez Samsung et d'autres) puis ré-encodage AAC 192 kb/s dans
     * un MP4. Échoue proprement si le téléphone n'a pas le décodeur.
     */
    private fun withPlatform(context: Context, uri: Uri, outFile: File, name: String): Boolean {
        val ex = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        try {
            ex.setDataSource(context, uri, null)
            var track = -1
            var inFormat: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    track = i
                    inFormat = f
                    break
                }
            }
            if (track < 0 || inFormat == null) {
                log("$name : aucune piste audio reconnue par le téléphone")
                return false
            }
            val mime = inFormat.getString(MediaFormat.KEY_MIME)!!
            ex.selectTrack(track)
            decoder = try {
                MediaCodec.createDecoderByType(mime)
            } catch (e: Exception) {
                log("$name : pas de décodeur $mime sur ce téléphone")
                return false
            }
            decoder.configure(inFormat, null, null, 0)
            decoder.start()

            var sampleRate = inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var floatPcm = false
            var outChannels = channels.coerceIn(1, 2)

            val info = MediaCodec.BufferInfo()
            val encInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var decodeDone = false
            var encodeDone = false
            var encEosQueued = false
            var muxTrack = -1
            var framesFed = 0L
            // PCM 16 bits entrelacé en attente d'encodage
            var pending = ByteBuffer.allocate(0)

            fun startEncoder() {
                val fmt = MediaFormat.createAudioFormat(
                    MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, outChannels
                ).apply {
                    setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
                    setInteger(
                        MediaFormat.KEY_AAC_PROFILE,
                        MediaCodecInfo.CodecProfileLevel.AACObjectLC
                    )
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
                }
                encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).also {
                    it.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    it.start()
                }
                muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            }

            /** Convertit une sortie du décodeur en PCM 16 bits mono/stéréo. */
            fun toPcm16(buf: ByteBuffer): ByteBuffer {
                buf.order(ByteOrder.nativeOrder())
                val frames: Int
                val out: ByteBuffer
                if (floatPcm) {
                    val fb = buf.asFloatBuffer()
                    frames = fb.remaining() / channels
                    out = ByteBuffer.allocate(frames * outChannels * 2).order(ByteOrder.nativeOrder())
                    for (fr in 0 until frames) for (c in 0 until outChannels) {
                        val v = (fb.get(fr * channels + c) * 32767f).coerceIn(-32768f, 32767f)
                        out.putShort(v.toInt().toShort())
                    }
                } else {
                    val sb = buf.asShortBuffer()
                    frames = sb.remaining() / channels
                    out = ByteBuffer.allocate(frames * outChannels * 2).order(ByteOrder.nativeOrder())
                    for (fr in 0 until frames) for (c in 0 until outChannels) {
                        out.putShort(sb.get(fr * channels + c))
                    }
                }
                out.flip()
                return out
            }

            fun drainEncoder() {
                val enc = encoder ?: return
                while (true) {
                    val ix = enc.dequeueOutputBuffer(encInfo, 0)
                    when {
                        ix == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                        ix == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            muxTrack = muxer!!.addTrack(enc.outputFormat)
                            muxer!!.start()
                            muxerStarted = true
                        }
                        ix >= 0 -> {
                            val ob = enc.getOutputBuffer(ix)!!
                            if (encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                encInfo.size = 0
                            }
                            if (encInfo.size > 0 && muxerStarted) {
                                ob.position(encInfo.offset)
                                ob.limit(encInfo.offset + encInfo.size)
                                muxer!!.writeSampleData(muxTrack, ob, encInfo)
                            }
                            enc.releaseOutputBuffer(ix, false)
                            if (encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                encodeDone = true
                                return
                            }
                        }
                    }
                }
            }

            /** Pousse le PCM en attente dans l'encodeur (EOS à la fin). */
            fun feedEncoder() {
                val enc = encoder ?: return
                while (pending.hasRemaining() || (decodeDone && !encEosQueued)) {
                    val ix = enc.dequeueInputBuffer(5_000)
                    if (ix < 0) return
                    val ib = enc.getInputBuffer(ix)!!
                    ib.clear()
                    val pts = framesFed * 1_000_000L / sampleRate
                    if (!pending.hasRemaining()) {
                        enc.queueInputBuffer(ix, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        encEosQueued = true
                        return
                    }
                    val n = minOf(ib.remaining(), pending.remaining()) / (2 * outChannels) * (2 * outChannels)
                    val slice = pending.duplicate()
                    slice.limit(slice.position() + n)
                    ib.put(slice)
                    pending.position(pending.position() + n)
                    enc.queueInputBuffer(ix, 0, n, pts, 0)
                    framesFed += n / (2 * outChannels)
                }
            }

            val t0 = System.currentTimeMillis()
            while (!encodeDone) {
                if (System.currentTimeMillis() - t0 > 10 * 60_000L) {
                    log("$name : conversion par le téléphone trop longue, abandon")
                    return false
                }
                if (!inputDone) {
                    val ix = decoder.dequeueInputBuffer(5_000)
                    if (ix >= 0) {
                        val ib = decoder.getInputBuffer(ix)!!
                        val n = ex.readSampleData(ib, 0)
                        if (n < 0) {
                            decoder.queueInputBuffer(ix, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(ix, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                if (!decodeDone && !pending.hasRemaining()) {
                    val ox = decoder.dequeueOutputBuffer(info, 5_000)
                    when {
                        ox == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val f = decoder.outputFormat
                            sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            outChannels = channels.coerceIn(1, 2)
                            floatPcm = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                                f.getInteger(MediaFormat.KEY_PCM_ENCODING) ==
                                android.media.AudioFormat.ENCODING_PCM_FLOAT
                        }
                        ox >= 0 -> {
                            if (encoder == null) startEncoder()
                            val ob = decoder.getOutputBuffer(ox)!!
                            if (info.size > 0) {
                                ob.position(info.offset)
                                ob.limit(info.offset + info.size)
                                pending = toPcm16(ob.slice())
                            }
                            decoder.releaseOutputBuffer(ox, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                decodeDone = true
                            }
                        }
                    }
                }
                if (decodeDone && encoder == null) {
                    log("$name : le décodeur du téléphone n'a rien produit")
                    return false
                }
                feedEncoder()
                drainEncoder()
            }
            return framesFed > 0 && muxerStarted
        } catch (e: Exception) {
            log("$name : conversion par le téléphone impossible : ${e::class.java.simpleName} ${e.message}")
            return false
        } finally {
            try { decoder?.stop() } catch (_: Exception) {}
            try { decoder?.release() } catch (_: Exception) {}
            try { encoder?.stop() } catch (_: Exception) {}
            try { encoder?.release() } catch (_: Exception) {}
            try { if (muxerStarted) muxer?.stop() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
            try { ex.release() } catch (_: Exception) {}
        }
    }

    private fun log(message: String) {
        android.util.Log.w("WmaConverter", message)
        try {
            com.pulsemix.app.player.PlayerCore.engineLog("WMA", message)
        } catch (_: Exception) {
        }
    }
}
