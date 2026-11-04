package com.copernicotv.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.copernicotv.app.ClavesEnMemoria
import com.copernicotv.app.crypto.ClavesApp
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.app.ui.CatalogoViewModel
import com.copernicotv.core.crypto.CifradoAes
import com.copernicotv.core.crypto.ResultadoDescifrado
import com.copernicotv.core.identity.CaseFolderCompleto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.GeneralSecurityException
import javax.crypto.SecretKey

/**
 * Persistencia del catálogo a través de la recreación del proceso.
 *
 * La base es un archivo real, no `inMemoryDatabaseBuilder`: cerrarla y volver a
 * abrirla es lo único que demuestra que el catálogo sobrevive a un reinicio.
 * Las pruebas JVM de `CatalogoRepositorioTest` usan una base en memoria y por
 * eso no podían ver el defecto: nunca simulaban un proceso nuevo.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CatalogoPersistenciaTest {
    private lateinit var db: AppDatabase
    private lateinit var repositorio: CatalogoRepositorio
    private val claves = ClavesEnMemoria()
    private val contexto: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun abrir() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        contexto.deleteDatabase(NOMBRE_BD)
        db = abrirBase()
        repositorio = CatalogoRepositorio(db, claves, contexto)
    }

    @After
    fun cerrar() {
        runCatching { db.close() }
        contexto.deleteDatabase(NOMBRE_BD)
        Dispatchers.resetMain()
    }

    @Test
    fun `el fixture persiste el catalogo completo en room`() {
        runBlocking {
            val resumen = repositorio.importarFixture()

            assertEquals(3, resumen.canales)
            assertEquals(0, resumen.rechazadas)
            assertEquals(1, db.fuenteDao().contar())
            assertEquals(1, db.listaDao().contar())
            assertEquals(3, db.canalDao().contar())
            assertEquals(3, db.aparienciaDao().contar(requireNotNull(resumen.listaId)))
            assertEquals(
                listOf("Canario Uno", "Canario Dos", "Canario Tres"),
                repositorio.canalesDeListaActiva().first(),
            )
        }
    }

    /**
     * Regresión del defecto reportado en dispositivo físico.
     *
     * Antes del arreglo, el ViewModel solo conocía la Lista por el resultado en
     * memoria de una importación del mismo proceso: al recrearlo, `listaId`
     * quedaba en `null`, nadie consultaba Room y la pantalla aparecía vacía con
     * los tres canales intactos en la base.
     */
    @Test
    fun `recrear el repositorio y el viewmodel carga los canales persistidos`() {
        runBlocking {
            val listaId = requireNotNull(repositorio.importarFixture().listaId)
            assertEquals(3, repositorio.canalesDeLista(listaId).first().size)

            db.close()
            db = abrirBase()
            val repositorioNuevo = CatalogoRepositorio(db, claves, contexto)
            val preferencias = PreferenciasOrdenEnMemoria()
            val viewModel =
                CatalogoViewModel(
                    repositorioNuevo,
                    BusquedaCatalogoRepositorio(db),
                    CatalogoCrudRepositorio(db, claves, preferencias),
                    preferencias,
                    ColeccionRepositorio(db),
                    PerfilRepositorio(db.perfilDao(), db.listaDao(), CaseFolderCompleto),
                    HistorialRepositorio(db),
                )
            val estado = withTimeout(TIEMPO_ESPERA) { viewModel.estado.first { it.canales.size == 3 } }

            assertEquals(listOf("Canario Uno", "Canario Dos", "Canario Tres"), estado.canales)
            assertNull(estado.mensaje)
        }
    }

    @Test
    fun `los registros cifrados se descifran con la misma clave tras recrear`() {
        runBlocking {
            repositorio.importarFixture()
            db.close()
            db = abrirBase()

            val fuente = db.fuenteDao().todas().single()
            val lista = db.listaDao().porFuente(fuente.id).single()
            val canales = db.canalDao().porFuente(fuente.id)

            assertEquals(
                UBICACION,
                descifrar(claves.claveAes, CifradoAes.aad("Lista", "ubicacion_cifrada"), lista.ubicacionCifrada),
            )
            assertEquals(
                listOf(
                    "https://example.invalid/canario/uno.m3u8",
                    "https://example.invalid/canario/dos.m3u8",
                    "https://example.invalid/canario/tres.m3u8",
                ),
                canales.map {
                    descifrar(claves.claveAes, CifradoAes.aad("Canal", "url_stream_cifrada"), it.urlStreamCifrada)
                },
            )
        }
    }

    @Test
    fun `una segunda importacion no duplica ni borra los canales`() {
        runBlocking {
            val primera = requireNotNull(repositorio.importarFixture().listaId)
            val segunda = requireNotNull(repositorio.importarFixture().listaId)

            assertEquals(primera, segunda)
            assertEquals(1, db.fuenteDao().contar())
            assertEquals(1, db.listaDao().contar())
            assertEquals(3, db.canalDao().contar())
            assertEquals(3, db.aparienciaDao().contar(segunda))
            assertEquals(3, repositorio.canalesDeListaActiva().first().size)
        }
    }

    /**
     * Una importación que falla después de insertar la Fuente no borra nada.
     *
     * El cifrado falla dentro de `withTransaction`, justo después del primer
     * `INSERT`; Room revierte la transacción completa y el catálogo anterior
     * queda intacto. La fuente nueva usa otro nombre para no caer en la ruta de
     * reutilización.
     */
    @Test
    fun `una importacion que falla no borra los canales existentes`() {
        runBlocking {
            val listaId = requireNotNull(repositorio.importarFixture().listaId)
            val texto = leerFixture()

            val fallo =
                runCatching {
                    CatalogoRepositorio(db, ClavesQueFallanAlCifrar(), contexto)
                        .importar(texto, OTRA_FUENTE, OTRA_UBICACION)
                }.exceptionOrNull()

            assertNotNull("La importación debía fallar al cifrar", fallo)
            assertTrue(fallo is GeneralSecurityException)
            assertNull(db.fuenteDao().porNombre(OTRA_FUENTE))
            assertEquals(1, db.fuenteDao().contar())
            assertEquals(3, db.canalDao().contar())
            assertEquals(3, repositorio.canalesDeLista(listaId).first().size)
        }
    }

    private fun abrirBase(): AppDatabase =
        Room
            .databaseBuilder(contexto, AppDatabase::class.java, NOMBRE_BD)
            .allowMainThreadQueries()
            .build()

    private fun leerFixture(): String = contexto.assets.open(FIXTURE).bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun descifrar(
        clave: SecretKey,
        aad: String,
        sobre: ByteArray,
    ): String {
        val resultado = CifradoAes.descifrar(clave, aad, sobre)
        assertTrue("esperaba descifrado ok", resultado is ResultadoDescifrado.Ok)
        return String((resultado as ResultadoDescifrado.Ok).bytes, Charsets.UTF_8)
    }

    /** Claves cuya AES falla al usarse: fuerza un fallo dentro de la transacción. */
    private class ClavesQueFallanAlCifrar : ClavesApp {
        private val base = ClavesEnMemoria()

        override val claveAes: SecretKey
            get() = throw GeneralSecurityException("clave AES no disponible")

        override val claveHmac: SecretKey = base.claveHmac
    }

    private companion object {
        const val NOMBRE_BD = "persistencia-canario.db"
        const val FIXTURE = "fixture-canario.m3u"
        const val UBICACION = "content://com.copernicotv.app.fixture/fixture-canario.m3u"
        const val OTRA_FUENTE = "Otra fuente"
        const val OTRA_UBICACION = "content://com.copernicotv.app.fixture/otra.m3u"
        const val TIEMPO_ESPERA = 10_000L
    }
}
