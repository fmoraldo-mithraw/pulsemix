package com.pulsemix.app.library

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import java.io.File

/**
 * Accès au binaire ffmpeg embarqué (livré par youtubedl-android pour
 * l'import depuis les plateformes, et réutilisé ici pour convertir les
 * formats qu'Android ne lit pas et réécrire les tags).
 *
 * Son chemin dépend de la version de la bibliothèque qui l'extrait : on le
 * cherche au lieu de le coder en dur, et on prépare son environnement —
 * ses bibliothèques ne sont pas dans les chemins système.
 */
object FfmpegBin {

    @Volatile private var bin: String? = null
    @Volatile private var env: Map<String, String> = emptyMap()

    fun env(): Map<String, String> = env

    /** Chemin du binaire, extrait au premier appel. null si indisponible. */
    @Synchronized
    fun path(context: Context): String? {
        bin?.let { return it }
        val app = context.applicationContext
        try {
            FFmpeg.getInstance().init(app)
        } catch (e: Exception) {
            android.util.Log.w("FfmpegBin", "ffmpeg indisponible", e)
            return null
        }
        var found: File? = null
        val libDirs = ArrayList<String>()
        for (root in listOfNotNull(app.noBackupFilesDir, app.filesDir)) {
            root.walkTopDown().maxDepth(8).forEach { f ->
                if (f.isFile && f.name == "ffmpeg" && f.canExecute()) {
                    if (found == null) found = f
                } else if (f.isDirectory && f.name == "lib") {
                    libDirs.add(f.absolutePath)
                }
            }
            if (found != null) break
        }
        val exe = found ?: return null
        env = buildMap {
            if (libDirs.isNotEmpty()) put("LD_LIBRARY_PATH", libDirs.joinToString(":"))
            put("PATH", (exe.parentFile?.absolutePath ?: "") + ":/system/bin")
        }
        bin = exe.absolutePath
        return bin
    }
}
