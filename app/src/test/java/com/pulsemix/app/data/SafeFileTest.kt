package com.pulsemix.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Vérifie qu'une bibliothèque déjà écrite ne peut pas être perdue : c'est
 * tout l'enjeu de l'écriture atomique.
 */
class SafeFileTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var main: File
    private lateinit var tmp: File
    private lateinit var bak: File

    private val toujoursValide: (String) -> Boolean = { true }

    private fun prepare() {
        main = File(folder.root, "library.json")
        tmp = File(folder.root, "library.json.tmp")
        bak = File(folder.root, "library.json.bak")
    }

    // ------------------------------------------------------------ écriture

    @Test
    fun `une premiere ecriture cree le fichier`() {
        prepare()
        assertTrue(SafeFile.writeAtomic(main, tmp, bak, "un".toByteArray()))
        assertEquals("un", main.readText())
    }

    @Test
    fun `la version precedente est gardee en filet`() {
        prepare()
        SafeFile.writeAtomic(main, tmp, bak, "ancien".toByteArray())
        SafeFile.writeAtomic(main, tmp, bak, "nouveau".toByteArray())
        assertEquals("nouveau", main.readText())
        assertEquals("ancien", bak.readText())
    }

    @Test
    fun `le temporaire ne traine pas apres l ecriture`() {
        prepare()
        SafeFile.writeAtomic(main, tmp, bak, "contenu".toByteArray())
        assertFalse("le temporaire devrait avoir été renommé", tmp.exists())
    }

    @Test
    fun `une ecriture impossible laisse la version en place intacte`() {
        prepare()
        SafeFile.writeAtomic(main, tmp, bak, "précieux".toByteArray())
        // Un dossier à la place du temporaire : l'écriture échouera
        assertTrue(tmp.mkdirs())
        assertFalse(SafeFile.writeAtomic(main, tmp, bak, "perdu".toByteArray()))
        assertEquals(
            "l'ancienne version doit survivre à une écriture ratée",
            "précieux", main.readText()
        )
    }

    @Test
    fun `le contenu binaire est rendu octet pour octet`() {
        prepare()
        val bytes = ByteArray(5_000) { (it % 251).toByte() }
        assertTrue(SafeFile.writeAtomic(main, tmp, bak, bytes))
        assertTrue(bytes.contentEquals(main.readBytes()))
    }

    // ------------------------------------------------------------- lecture

    @Test
    fun `la lecture rend le contenu du fichier principal`() {
        prepare()
        SafeFile.writeAtomic(main, tmp, bak, "bon".toByteArray())
        assertEquals("bon", SafeFile.readWithFallback(main, bak, toujoursValide))
    }

    @Test
    fun `un fichier principal corrompu fait tomber sur le filet`() {
        prepare()
        SafeFile.writeAtomic(main, tmp, bak, "{complet}".toByteArray())
        SafeFile.writeAtomic(main, tmp, bak, "{aussi complet}".toByteArray())
        // Le processus meurt en pleine écriture : JSON coupé en deux
        main.writeText("{tron")
        val lu = SafeFile.readWithFallback(main, bak) { it.endsWith("}") }
        assertEquals("{complet}", lu)
    }

    @Test
    fun `le filet utilise est remis en place comme fichier principal`() {
        prepare()
        SafeFile.writeAtomic(main, tmp, bak, "{v1}".toByteArray())
        SafeFile.writeAtomic(main, tmp, bak, "{v2}".toByteArray())
        main.writeText("{tron")
        SafeFile.readWithFallback(main, bak) { it.endsWith("}") }
        assertEquals(
            "le démarrage suivant doit repartir d'un fichier sain",
            "{v1}", main.readText()
        )
    }

    @Test
    fun `un fichier principal absent fait tomber sur le filet`() {
        prepare()
        SafeFile.writeAtomic(main, tmp, bak, "{v1}".toByteArray())
        SafeFile.writeAtomic(main, tmp, bak, "{v2}".toByteArray())
        // Mort entre le renommage vers le filet et celui du temporaire
        assertTrue(main.delete())
        assertEquals("{v1}", SafeFile.readWithFallback(main, bak) { it.endsWith("}") })
    }

    @Test
    fun `rien a lire rend null`() {
        prepare()
        assertNull(SafeFile.readWithFallback(main, bak, toujoursValide))
    }

    @Test
    fun `un fichier vide n est pas pris pour du contenu`() {
        prepare()
        main.writeText("")
        assertNull(SafeFile.readWithFallback(main, bak, toujoursValide))
    }

    @Test
    fun `les deux fichiers illisibles rendent null`() {
        prepare()
        SafeFile.writeAtomic(main, tmp, bak, "{v1}".toByteArray())
        SafeFile.writeAtomic(main, tmp, bak, "{v2}".toByteArray())
        main.writeText("cassé")
        bak.writeText("cassé aussi")
        assertNull(SafeFile.readWithFallback(main, bak) { it.endsWith("}") })
    }

    /**
     * Le cœur du correctif. Contrairement à un `writeText`, cette écriture
     * ne tronque jamais le fichier en place : le seul instant où le
     * principal n'existe pas est celui, très bref, entre les deux
     * renommages. On se place exactement là et on vérifie que la version
     * précédente est bien récupérée.
     */
    @Test
    fun `une mort entre les deux renommages rend la version precedente`() {
        prepare()
        val estComplet: (String) -> Boolean = { it.endsWith("}") }
        for (i in 1..10) {
            SafeFile.writeAtomic(main, tmp, bak, "{v$i}".toByteArray())
        }
        // État exact laissé par un processus tué pendant la 11e écriture,
        // juste après avoir mis la version en place de côté
        bak.delete()
        assertTrue(main.renameTo(bak))
        assertFalse(main.exists())

        assertEquals("{v10}", SafeFile.readWithFallback(main, bak, estComplet))
        assertEquals(
            "le fichier principal doit être rétabli",
            "{v10}", main.readText()
        )
    }

    /**
     * Le scénario que l'ancienne écriture produisait à chaque fois : un
     * fichier principal tronqué. Le filet doit encore sauver la mise.
     */
    @Test
    fun `un principal tronque et un filet sain rendent le filet`() {
        prepare()
        val estComplet: (String) -> Boolean = { it.endsWith("}") }
        for (i in 1..10) {
            SafeFile.writeAtomic(main, tmp, bak, "{v$i}".toByteArray())
        }
        main.writeText("{v11 tron")
        assertEquals("{v9}", SafeFile.readWithFallback(main, bak, estComplet))
    }
}
