package com.copernicotv.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.test.platform.app.InstrumentationRegistry
import com.copernicotv.app.crypto.ClavesApp
import com.copernicotv.app.crypto.ClavesKeystore
import com.copernicotv.app.data.CatalogoRepositorio
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.core.crypto.CifradoAes
import com.copernicotv.core.crypto.ResultadoDescifrado
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.security.InvalidAlgorithmParameterException
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.inject.Inject

/**
 * Regresión de la clave AES heredada de Keystore.
 *
 * El `KeyGenParameterSpec` se fija al generar la clave: una clave creada antes
 * de `setRandomizedEncryptionRequired(false)` conserva la protección activada y
 * rechaza todo IV del llamador. En un dispositivo que ya tenía esa clave, cada
 * importación fallaba con `importacion_fallida` aunque el código estuviera
 * corregido; un emulador recién creado nunca reproduce el caso.
 *
 * Estas pruebas crean la clave heredada a propósito y comprueban que el
 * repositorio se recupera, que el sobre propio funciona después y que la
 * premisa del fallo (rechazo del IV) sigue siendo cierta.
 */
@HiltAndroidTest
class ClavesKeystoreTest {
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
        borrarAlias(ALIAS_AES)
    }

    @After
    fun limpiar() {
        borrarAlias(ALIAS_AES)
    }

    @Test
    fun laClaveHeredadaRechazaElIvDelLlamador() {
        val heredada = crearClaveHeredada()

        val fallo = runCatching { CifradoAes.cifrar(heredada, AAD_SONDA, byteArrayOf(1)) }.exceptionOrNull()

        assertTrue(
            "Se esperaba InvalidAlgorithmParameterException y se obtuvo ${fallo?.let { it::class.java.name }}",
            fallo is InvalidAlgorithmParameterException,
        )
    }

    @Test
    fun unaClaveHeredadaNoImpideImportarElFixture() {
        crearClaveHeredada()

        val resumen = runBlocking { repositorio.importarFixture() }

        assertNull("La importación reportó el código ${resumen.error}", resumen.error)
        val listaId = resumen.listaId
        assertNotNull("La importación no devolvió lista", listaId)
        assertEquals(3, resumen.canales)
        val nombres = runBlocking { repositorio.canalesDeLista(requireNotNull(listaId)).first() }
        assertEquals(listOf("Canario Uno", "Canario Dos", "Canario Tres"), nombres)
    }

    @Test
    fun laClaveRegeneradaCifraYDescifraConElSobrePropio() {
        crearClaveHeredada()

        val sobre = CifradoAes.cifrar(claves.claveAes, AAD_SONDA, byteArrayOf(7))
        val resultado = CifradoAes.descifrar(claves.claveAes, AAD_SONDA, sobre)

        assertTrue("esperaba descifrado ok", resultado is ResultadoDescifrado.Ok)
        assertArrayEquals(byteArrayOf(7), (resultado as ResultadoDescifrado.Ok).bytes)
    }

    /**
     * Una instancia nueva de `ClavesKeystore` equivale al arranque de un proceso:
     * el `by lazy` se vuelve a evaluar y la sonda decide si la clave sirve. Si la
     * sonda regenerara una clave válida, el sobre anterior dejaría de descifrar.
     */
    @Test
    fun unaInstanciaNuevaDeClavesReutilizaLaClavePersistida() {
        val primera = ClavesKeystore()
        val sobre = CifradoAes.cifrar(primera.claveAes, AAD_SONDA, byteArrayOf(9))

        val segunda = ClavesKeystore()
        val resultado = CifradoAes.descifrar(segunda.claveAes, AAD_SONDA, sobre)

        assertTrue("esperaba descifrado ok", resultado is ResultadoDescifrado.Ok)
        assertArrayEquals(byteArrayOf(9), (resultado as ResultadoDescifrado.Ok).bytes)
    }

    /**
     * Un repositorio y unas claves nuevos releen el catálogo persistido.
     *
     * Es la versión con Keystore real de la recreación del proceso: la Lista se
     * resuelve en Room, no en la memoria del ViewModel, y la URL cifrada se
     * descifra con la clave que sobrevivió al cambio de instancia.
     */
    @Test
    fun unProcesoNuevoReleeElCatalogoYDescifraConLaClavePersistida() {
        val resumen = runBlocking { repositorio.importarFixture() }
        assertNull("La importación reportó el código ${resumen.error}", resumen.error)

        val clavesNuevas = ClavesKeystore()
        val repositorioNuevo = CatalogoRepositorio(db, clavesNuevas, contexto())

        val nombres = runBlocking { repositorioNuevo.canalesDeListaActiva().first() }
        assertEquals(listOf("Canario Uno", "Canario Dos", "Canario Tres"), nombres)

        val fuente = runBlocking { db.fuenteDao().todas().single() }
        val canal = runBlocking { db.canalDao().porFuente(fuente.id) }.first()
        val descifrado =
            CifradoAes.descifrar(
                clavesNuevas.claveAes,
                CifradoAes.aad("Canal", "url_stream_cifrada"),
                canal.urlStreamCifrada,
            )
        assertTrue("esperaba descifrado ok con la clave persistida", descifrado is ResultadoDescifrado.Ok)
        assertEquals(
            "https://example.invalid/canario/uno.m3u8",
            String((descifrado as ResultadoDescifrado.Ok).bytes, Charsets.UTF_8),
        )
    }

    private fun contexto(): Context = InstrumentationRegistry.getInstrumentation().targetContext

    /** Crea la clave con el `KeyGenParameterSpec` previo al arreglo del IV. */
    private fun crearClaveHeredada(): SecretKey {
        borrarAlias(ALIAS_AES)
        val generador = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generador.init(
            KeyGenParameterSpec
                .Builder(ALIAS_AES, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generador.generateKey()
    }

    private fun borrarAlias(alias: String) {
        val almacen = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (almacen.containsAlias(alias)) almacen.deleteEntry(alias)
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS_AES = "copernicotv.aes.v1"
        const val AAD_SONDA = "ClavesKeystoreTest.sonda"
    }
}
