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

    /**
     * Chemin du binaire, extrait au premier appel. null si indisponible.
     *
     * Depuis youtubedl-android 0.18, l'EXÉCUTABLE ffmpeg est livré sous le
     * nom `libffmpeg.so` dans le dossier des bibliothèques natives de
     * l'appli (le seul endroit où Android 10+ laisse exécuter un binaire
     * embarqué), et l'archive extraite dans les données ne contient plus
     * que ses bibliothèques (`…/packages/ffmpeg/usr/lib`). L'ancienne
     * recherche d'un fichier nommé « ffmpeg » dans les données ne trouvait
     * donc plus rien : mashup, export du meilleur passage, conversion WMA
     * et écriture des tags répondaient « ffmpeg indisponible ». On regarde
     * d'abord au bon endroit, l'ancien chemin reste en repli.
     */
    @Synchronized
    fun path(context: Context): String? {
        bin?.let { return it }
        val app = context.applicationContext
        try {
            FFmpeg.getInstance().init(app)
        } catch (e: Exception) {
            android.util.Log.w("FfmpegBin", "ffmpeg indisponible", e)
            log("init youtubedl-android impossible : ${e.message}")
            return null
        }
        val nativeDir = File(app.applicationInfo.nativeLibraryDir)
        val libDirs = ArrayList<String>()
        // Bibliothèques de ffmpeg (extraites par FFmpeg.init)
        val packagedLib = File(
            app.noBackupFilesDir, "youtubedl-android/packages/ffmpeg/usr/lib"
        )
        if (packagedLib.isDirectory) libDirs.add(packagedLib.absolutePath)
        var found: File? = File(nativeDir, "libffmpeg.so").takeIf { it.isFile }
        if (found == null) {
            // Anciennes versions : binaire « ffmpeg » extrait dans les données
            for (root in listOfNotNull(app.noBackupFilesDir, app.filesDir)) {
                root.walkTopDown().maxDepth(8).forEach { f ->
                    if (f.isFile && f.name == "ffmpeg" && f.canExecute()) {
                        if (found == null) found = f
                    } else if (f.isDirectory && f.name == "lib" &&
                        f.absolutePath !in libDirs
                    ) {
                        libDirs.add(f.absolutePath)
                    }
                }
                if (found != null) break
            }
        }
        val exe = found
        if (exe == null) {
            log(
                "binaire introuvable : ni ${nativeDir.absolutePath}/libffmpeg.so, " +
                    "ni « ffmpeg » dans les données"
            )
            return null
        }
        libDirs.add(nativeDir.absolutePath)
        env = buildMap {
            put("LD_LIBRARY_PATH", libDirs.joinToString(":"))
            put("PATH", (exe.parentFile?.absolutePath ?: "") + ":/system/bin")
        }
        bin = exe.absolutePath
        log("binaire : ${exe.absolutePath} ; LD_LIBRARY_PATH=${libDirs.joinToString(":")}")
        return bin
    }

    private fun log(message: String) {
        try {
            com.pulsemix.app.player.PlayerCore.engineLog("ffmpeg", message)
        } catch (_: Exception) {
        }
    }

    @Volatile private var cmdPrefix: List<String>? = null

    /**
     * Commande d'invocation de ffmpeg, testée une fois : le binaire seul,
     * ou via le linker système en repli.
     *
     * Android 10+ interdit aux applis récentes d'EXÉCUTER un binaire
     * rangé dans leurs propres données — précisément là où le ffmpeg
     * embarqué est extrait. L'interdiction porte sur l'exec direct ;
     * passer le binaire en argument au linker système (un exécutable
     * système, lui autorisé) le charge quand même. Sans ce repli, la
     * conversion des WMA et l'écriture des tags échouaient en silence
     * sur tout Android moderne.
     *
     * @return la commande à préfixer aux arguments, ou null si aucune
     * forme ne fonctionne.
     */
    @Synchronized
    fun command(context: Context): List<String>? {
        cmdPrefix?.let { return it }
        val exe = path(context) ?: return null

        fun works(cmd: List<String>): Boolean = try {
            val p = ProcessBuilder(cmd + "-version").apply {
                environment().putAll(env())
                redirectErrorStream(true)
            }.start()
            p.inputStream.bufferedReader().use { it.readText() }
            p.waitFor() == 0
        } catch (_: Exception) {
            false
        }

        val linker = if (File("/system/bin/linker64").exists())
            "/system/bin/linker64" else "/system/bin/linker"
        val chosen = listOf(listOf(exe), listOf(linker, exe)).firstOrNull { works(it) }
        if (chosen == null) {
            android.util.Log.w("FfmpegBin", "ffmpeg inexécutable, direct comme via $linker")
            log("binaire inexécutable, direct comme via $linker")
        } else {
            log("commande : ${chosen.joinToString(" ")}")
        }
        cmdPrefix = chosen
        return chosen
    }
}
