package com.copernicotv.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.test.platform.app.InstrumentationRegistry
import com.copernicotv.app.crypto.ClavesApp
import com.copernicotv.app.crypto.ClavesKeystore
import com.copernicotv.app.data.CatalogoRepositorio
import com.copernicotv.app.data.IndiceHmacRepositorio
import com.copernicotv.app.data.SelloIndiceHmac
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.app.data.db.SembrarEstadoIndiceHmac
import com.copernicotv.core.crypto.CifradoAes
import com.copernicotv.core.crypto.ResultadoDescifrado
import com.copernicotv.core.hmac.FaseIndiceHmac
import com.copernicotv.core.hmac.RechazoIndiceHmac
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.security.InvalidAlgorithmParameterException
import java.security.KeyStore
import java.time.Instant
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.inject.Inject

/**
 * Regresión de las claves de Keystore (E2-T6).
 *
 * La primera parte cubre la clave AES heredada: el `KeyGenParameterSpec` se
 * fija al generar la clave, así que una clave creada antes de
 * `setRandomizedEncryptionRequired(false)` conserva la protección activada y
 * rechaza todo IV del llamador. En un dispositivo que ya tenía esa clave, cada
 * importación fallaba con `importacion_fallida` aunque el código estuviera
 * corregido; un emulador recién creado nunca reproduce el caso.
 *
 * La segunda parte cubre la clave HMAC con Keystore real: creación idempotente
 * bajo concurrencia y pérdida controlada de la entrada del alias, con datos
 * sellados que deben seguir intactos. Son las pruebas que no se pueden
 * reproducir con claves en memoria.
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
        borrarAlias(ALIAS_HMAC_PRUEBA_97)
        borrarAlias(ALIAS_HMAC_PRUEBA_99)
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
        val repositorioNuevo = CatalogoRepositorio(db, clavesNuevas, IndiceHmacRepositorio(db, clavesNuevas), contexto())

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

    /**
     * Dos inicializaciones concurrentes deben terminar con la misma clave.
     *
     * Las claves de Android Keystore no son exportables, así que la igualdad se
     * comprueba por el material: cada clave devuelta y la persistida deben
     * producir el mismo MAC. Sin el candado, varias hebras leen "no existe" y
     * cada una genera; la última sobrescribe el alias y los sobres sellados con
     * las claves anteriores dejan de verificar.
     */
    @Test
    fun laCreacionConcurrenteDeLaClaveHmacEsIdempotente() {
        val generacion = 97
        val alias = ALIAS_PREFIJO_HMAC + generacion
        borrarAlias(alias)
        try {
            val hilos = 8
            val creadas =
                runBlocking(Dispatchers.IO) {
                    val barrera = CyclicBarrier(hilos)
                    (1..hilos)
                        .map {
                            async(Dispatchers.IO) {
                                barrera.await(30, TimeUnit.SECONDS)
                                claves.crearClaveHmac(generacion)
                            }
                        }.awaitAll()
                }

            assertEquals(hilos, creadas.size)
            val persistida = requireNotNull(claves.claveHmac(generacion))
            val esperado = hmac(persistida)
            creadas.forEach { clave ->
                val obtenido = runCatching { hmac(clave) }
                assertTrue("el MAC de una clave concurrente falló: ${obtenido.exceptionOrNull()}", obtenido.isSuccess)
                assertEquals("una corrutina recibió una clave distinta a la persistida", esperado, obtenido.getOrThrow())
            }
            assertTrue(claves.contieneClaveHmac(generacion))
        } finally {
            borrarAlias(alias)
        }
    }

    /**
     * Pérdida real de la entrada Keystore, con datos ya sellados.
     *
     * Usa la generación 99 sobre el mismo `ClavesKeystore` y la misma base que
     * la app, sin tocar la `v1` que usan el resto de las pruebas. El borrado del
     * alias es el mecanismo controlado del SO (el mismo que ya usa esta clase
     * para la clave AES) y jamás se expone una API de producción para simularlo.
     *
     * Comprueba las cuatro condiciones del contrato: la lectura no recrea la
     * clave, el sello transiciona a `recuperacion_pendiente`, las escrituras e
     * igualdades quedan bloqueadas y los datos originales siguen descifrando.
     */
    @Test
    fun laPerdidaDeLaClaveHmacBloqueaEscriturasSinTocarLosDatos() {
        val generacion = 99
        val alias = ALIAS_PREFIJO_HMAC + generacion
        borrarAlias(alias)
        claves.crearClaveHmac(generacion)
        val ahora = Instant.now()
        runBlocking {
            db.estadoIndiceHmacDao().guardar(
                SembrarEstadoIndiceHmac.fila(ahora).copy(versionActiva = "hmac:v$generacion"),
            )
        }

        val resumen = runBlocking { repositorio.importarFixture() }
        assertNull("La importación reportó el código ${resumen.error}", resumen.error)

        val fuente = runBlocking { db.fuenteDao().todas().single() }
        val canal = runBlocking { db.canalDao().porFuente(fuente.id) }.first()
        val sobreOriginal = canal.urlStreamCifrada.copyOf()

        borrarAlias(alias)

        assertNull("la lectura no debe recrear la clave perdida", claves.claveHmac(generacion))
        assertFalse(claves.contieneClaveHmac(generacion))

        val indice = IndiceHmacRepositorio(db, claves)
        val sello = runBlocking { indice.selloHmac(ahora) }
        val bloqueada = sello as? SelloIndiceHmac.Bloqueada
        assertNotNull("el sello debe bloquearse tras la pérdida: $sello", bloqueada)
        assertEquals(RechazoIndiceHmac.CODIGO, bloqueada?.codigo)

        assertEquals(
            FaseIndiceHmac.RECUPERACION_PENDIENTE,
            runBlocking { indice.estado(ahora) }.fase,
        )
        assertNotNull(
            "las igualdades deben quedar bloqueadas",
            runBlocking { indice.bloqueoIgualdad(ahora) },
        )

        val segunda = runBlocking { repositorio.importarFixture() }
        assertEquals(
            "la importación debe quedar bloqueada sin tocar datos",
            RechazoIndiceHmac.CODIGO,
            segunda.error,
        )

        val canalTrasPerdida = runBlocking { db.canalDao().porFuente(fuente.id) }.first()
        assertArrayEquals(sobreOriginal, canalTrasPerdida.urlStreamCifrada)
        val descifrado =
            CifradoAes.descifrar(
                claves.claveAes,
                CifradoAes.aad("Canal", "url_stream_cifrada"),
                canalTrasPerdida.urlStreamCifrada,
            )
        assertTrue("los datos deben seguir descifrando", descifrado is ResultadoDescifrado.Ok)
        assertEquals(
            "https://example.invalid/canario/uno.m3u8",
            String((descifrado as ResultadoDescifrado.Ok).bytes, Charsets.UTF_8),
        )
    }

    private fun hmac(clave: SecretKey): List<Byte> {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(clave)
        return mac.doFinal(byteArrayOf(1, 2, 3)).toList()
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
        const val ALIAS_PREFIJO_HMAC = "copernicotv.hmac.v"
        const val ALIAS_HMAC_PRUEBA_97 = "copernicotv.hmac.v97"
        const val ALIAS_HMAC_PRUEBA_99 = "copernicotv.hmac.v99"
        const val AAD_SONDA = "ClavesKeystoreTest.sonda"
    }
}
