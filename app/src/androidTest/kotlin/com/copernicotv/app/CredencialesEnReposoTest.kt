package com.copernicotv.app

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.copernicotv.app.crypto.ClavesApp
import com.copernicotv.app.crypto.ClavesKeystore
import com.copernicotv.app.data.CatalogoRepositorio
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.core.crypto.CifradoAes
import com.copernicotv.core.crypto.MotivoCifrado
import com.copernicotv.core.crypto.ResultadoDescifrado
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.security.KeyStore
import javax.inject.Inject

/**
 * Credenciales en reposo con Keystore real (E2-T6, DC-04 §11).
 *
 * Cubre los casos que una prueba JVM no puede demostrar porque necesitan el
 * proveedor criptográfico de Android: ciphertext alterado, AAD de otra columna,
 * clave eliminada, reglas de backup, base sin plaintext y logcat sin URL.
 */
@HiltAndroidTest
class CredencialesEnReposoTest {
    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var db: AppDatabase

    @Inject
    lateinit var repositorio: CatalogoRepositorio

    @Inject
    lateinit var claves: ClavesApp

    @Before
    fun preparar() {
        hiltRule.inject()
        db.clearAllTables()
    }

    @After
    fun limpiar() {
        borrarAlias(ALIAS_AES)
    }

    @Test
    fun unCiphertextAlteradoNoDescifra() {
        val sobre = CifradoAes.cifrar(claves.claveAes, AAD_CANAL, URL_EN_CLARO)
        sobre[sobre.size - 1] = (sobre[sobre.size - 1].toInt() xor 0x01).toByte()

        val resultado = CifradoAes.descifrar(claves.claveAes, AAD_CANAL, sobre)

        assertEquals(ResultadoDescifrado.Fallo(MotivoCifrado.FALLO_AUTENTICACION), resultado)
    }

    @Test
    fun unSobreConAadDeOtraColumnaNoDescifra() {
        val sobre = CifradoAes.cifrar(claves.claveAes, AAD_CANAL, URL_EN_CLARO)

        val resultado = CifradoAes.descifrar(claves.claveAes, AAD_LISTA, sobre)

        assertEquals(ResultadoDescifrado.Fallo(MotivoCifrado.FALLO_AUTENTICACION), resultado)
    }

    /**
     * Sin la clave de Keystore el sobre anterior no se recupera y la app no
     * adivina ni reconstruye una clave a partir del ciphertext.
     */
    @Test
    fun laClaveEliminadaDejaElSobreAnteriorSinDescifrar() {
        val sobre = CifradoAes.cifrar(claves.claveAes, AAD_CANAL, URL_EN_CLARO)
        borrarAlias(ALIAS_AES)

        val clavesNuevas = ClavesKeystore()
        val resultado = CifradoAes.descifrar(clavesNuevas.claveAes, AAD_CANAL, sobre)

        assertTrue("esperaba un fallo y se obtuvo $resultado", resultado is ResultadoDescifrado.Fallo)
        val propio = CifradoAes.cifrar(clavesNuevas.claveAes, AAD_CANAL, URL_EN_CLARO)
        assertTrue(
            "la clave regenerada debe servir para sobres nuevos",
            CifradoAes.descifrar(clavesNuevas.claveAes, AAD_CANAL, propio) is ResultadoDescifrado.Ok,
        )
    }

    @Test
    fun elBackupAutomaticoEstaDeshabilitado() {
        val info = contexto().packageManager.getApplicationInfo(contexto().packageName, 0)

        assertEquals(
            "DC-04 exige excluir la base Room y la cache del backup automatico",
            0,
            info.flags and ApplicationInfo.FLAG_ALLOW_BACKUP,
        )
    }

    /**
     * Dump crudo de la base: ni la URL del stream ni la ubicación de la Lista
     * aparecen en claro, y el catálogo sí quedó persistido.
     */
    @Test
    fun laBaseNoContieneLaUrlEnClaro() {
        val resumen = runBlocking { repositorio.importarFixture() }
        assertNull("La importación reportó el código ${resumen.error}", resumen.error)
        assertEquals(3, resumen.canales)

        val bytes = volcadoDeBase()
        val texto = String(bytes, Charsets.ISO_8859_1)

        assertFalse("la URL del stream quedó en claro", texto.contains("example.invalid/canario"))
        assertFalse("la ubicación de la Lista quedó en claro", texto.contains(UBICACION_FIXTURE))
    }

    @Test
    fun unaImportacionFallidaNoRegistraLaUrlEnLogcat() {
        val marca = "sonda-${System.nanoTime()}"
        val url = "https://example.invalid/$marca/lista.m3u"
        Log.i(TAG_SONDA, "sonda de logcat disponible")

        val resumen = runBlocking { repositorio.importarDesdeUrl("Fuente logcat", url) }

        assertNotNull("la importación debía fallar sin red hacia example.invalid", resumen.error)
        val volcado = logcat()
        assertTrue("logcat no devolvió líneas; la prueba no podría afirmar nada", volcado.isNotEmpty())
        assertFalse("la URL apareció en logcat", volcado.contains(marca))
    }

    private fun contexto(): Context = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * Junta el archivo principal y su WAL: con `journal_mode=WAL` una escritura
     * reciente puede vivir todavía en el `-wal` y no en el archivo principal.
     */
    private fun volcadoDeBase(): ByteArray {
        db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { it.moveToFirst() }
        val principal = contexto().getDatabasePath(AppDatabase.NOMBRE)
        assertTrue("la base no existe en ${principal.path}", principal.exists())
        val wal = File("${principal.path}-wal")
        return principal.readBytes() + if (wal.exists()) wal.readBytes() else ByteArray(0)
    }

    private fun logcat(): String =
        runCatching {
            Runtime.getRuntime()
                .exec(arrayOf("logcat", "-d", "-t", "4000"))
                .inputStream
                .bufferedReader()
                .readText()
        }.getOrDefault("")

    private fun borrarAlias(alias: String) {
        val almacen = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (almacen.containsAlias(alias)) almacen.deleteEntry(alias)
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS_AES = "copernicotv.aes.v1"
        const val TAG_SONDA = "CredencialesEnReposoTest"
        const val UBICACION_FIXTURE = "content://com.copernicotv.app.fixture/fixture-canario.m3u"

        val URL_EN_CLARO = "https://example.invalid/canario/uno.m3u8".toByteArray(Charsets.UTF_8)

        val AAD_CANAL: String = CifradoAes.aad("Canal", "url_stream_cifrada")
        val AAD_LISTA: String = CifradoAes.aad("Lista", "ubicacion_cifrada")
    }
}
