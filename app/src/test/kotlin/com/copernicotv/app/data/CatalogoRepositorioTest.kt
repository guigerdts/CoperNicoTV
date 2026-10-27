package com.copernicotv.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.copernicotv.app.ClavesEnMemoria
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.core.crypto.CifradoAes
import com.copernicotv.core.crypto.ResultadoDescifrado
import com.copernicotv.core.identity.ResultadoSobre
import com.copernicotv.core.identity.SobreHmac
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Importación canaria de punta a punta sobre Room real.
 *
 * Verifica que el camino parser → identidad → HMAC → cifrado → persistencia
 * conserva la URL original cifrada, indexa por HMAC de la canónica y deja el
 * catálogo en el orden de `Apariencia.orden`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CatalogoRepositorioTest {
    private lateinit var db: AppDatabase
    private lateinit var repositorio: CatalogoRepositorio
    private val claves = ClavesEnMemoria()
    private val contexto: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun abrir() {
        db =
            Room
                .inMemoryDatabaseBuilder(contexto, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        repositorio = CatalogoRepositorio(db, claves, contexto)
    }

    @After
    fun cerrar() {
        db.close()
    }

    @Test
    fun `importa el fixture y conserva la url original cifrada`() {
        runBlocking {
            val resumen = repositorio.importarFixture()

            assertEquals(3, resumen.canales)
            assertEquals(0, resumen.rechazadas)
            assertNotNull(resumen.listaId)

            val fuente = db.fuenteDao().todas().single()
            assertEquals("Fixture canario E1", fuente.nombre)

            val lista = db.listaDao().porFuente(fuente.id).single()
            assertEquals("content", lista.esquema)
            assertEquals(
                UBICACION,
                descifrar(claves.claveAes, CifradoAes.aad("Lista", "ubicacion_cifrada"), lista.ubicacionCifrada),
            )
            val sobreUbicacion = SobreHmac.parsear(String(lista.ubicacionHmac, Charsets.UTF_8))
            assertTrue(sobreUbicacion is ResultadoSobre.Versionado)
            assertEquals(1, (sobreUbicacion as ResultadoSobre.Versionado).versionCanonica)
        }
    }

    @Test
    fun `el catalogo se lee en orden de apariencia`() {
        runBlocking {
            val listaId = requireNotNull(repositorio.importarFixture().listaId)
            val nombres = repositorio.canalesDeLista(listaId).first()
            assertEquals(listOf("Canario Uno", "Canario Dos", "Canario Tres"), nombres)
        }
    }

    @Test
    fun `la identidad se deriva y el hmac usa la canonica`() {
        runBlocking {
            repositorio.importarFixture()
            val canales = db.canalDao().porFuente(db.fuenteDao().todas().single().id)
            val primero = canales.first { it.tvgId == "canario-1" }

            assertEquals("tvg:v1:canario-1", primero.claveUnicidad)
            assertTrue(primero.claveDerivable)
            assertNotNull(primero.claveCanonica)
            assertEquals(
                "https://example.invalid/canario/uno.m3u8",
                descifrar(claves.claveAes, CifradoAes.aad("Canal", "url_stream_cifrada"), primero.urlStreamCifrada),
            )
            val sobre = SobreHmac.parsear(String(primero.urlStreamHmac, Charsets.UTF_8))
            assertTrue(sobre is ResultadoSobre.Versionado)
            assertEquals(1, (sobre as ResultadoSobre.Versionado).versionCanonica)
        }
    }

    @Test
    fun `los grupos se crean una vez por nombre`() {
        runBlocking {
            val listaId = requireNotNull(repositorio.importarFixture().listaId)
            val apariencias = db.aparienciaDao().porLista(listaId)
            assertEquals(3, apariencias.size)
            assertEquals(listOf(0, 1, 2), apariencias.map { it.orden })
            assertEquals(2, db.grupoDao().contar(listaId))
        }
    }

    @Test
    fun `reimportar el mismo fixture viola la unicidad de la fuente`() {
        runBlocking {
            repositorio.importarFixture()
            val fallo = runCatching { repositorio.importarFixture() }.exceptionOrNull()
            assertNotNull(fallo)
        }
    }

    private fun descifrar(
        clave: javax.crypto.SecretKey,
        aad: String,
        sobre: ByteArray,
    ): String {
        val resultado = CifradoAes.descifrar(clave, aad, sobre)
        assertTrue("esperaba descifrado ok", resultado is ResultadoDescifrado.Ok)
        return String((resultado as ResultadoDescifrado.Ok).bytes, Charsets.UTF_8)
    }

    private companion object {
        const val UBICACION = "content://com.copernicotv.app.fixture/fixture-canario.m3u"
    }
}
