package com.copernicotv.app

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.copernicotv.app.crypto.ClavesApp
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
    fun `la clave heredada rechaza el IV del llamador`() {
        val heredada = crearClaveHeredada()

        val fallo = runCatching { CifradoAes.cifrar(heredada, AAD_SONDA, byteArrayOf(1)) }.exceptionOrNull()

        assertTrue(
            "Se esperaba InvalidAlgorithmParameterException y se obtuvo ${fallo?.let { it::class.java.name }}",
            fallo is InvalidAlgorithmParameterException,
        )
    }

    @Test
    fun `una clave heredada no impide importar el fixture`() {
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
    fun `la clave regenerada cifra y descifra con el sobre propio`() {
        crearClaveHeredada()

        val sobre = CifradoAes.cifrar(claves.claveAes, AAD_SONDA, byteArrayOf(7))
        val resultado = CifradoAes.descifrar(claves.claveAes, AAD_SONDA, sobre)

        assertTrue("esperaba descifrado ok", resultado is ResultadoDescifrado.Ok)
        assertArrayEquals(byteArrayOf(7), (resultado as ResultadoDescifrado.Ok).bytes)
    }

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
