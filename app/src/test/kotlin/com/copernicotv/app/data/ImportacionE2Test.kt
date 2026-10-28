package com.copernicotv.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.copernicotv.app.ClavesEnMemoria
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.core.crypto.CifradoAes
import com.copernicotv.core.crypto.ResultadoDescifrado
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
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
import java.net.ServerSocket
import java.net.Socket

/**
 * Importación de E2 sobre Room real: metadatos, atributos extra, Estado de
 * Actualización y errores tipados sin destruir el catálogo previo (S54).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportacionE2Test {
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
    fun `persiste logo cifrado y atributos extra deterministas`() {
        runBlocking {
            val resumen = repositorio.importar(TEXTO_E2, "Fuente E2", UBICACION)

            assertEquals(1, resumen.canales)
            assertEquals(0, resumen.rechazadas)
            val fuente = requireNotNull(db.fuenteDao().porNombre("Fuente E2"))
            val canal = db.canalDao().porFuente(fuente.id).single()
            assertEquals("custom=x", canal.atributosExtra)
            assertNotNull(canal.logoUrlHmac)
            assertEquals(LOGO, descifrarLogo(requireNotNull(canal.logoUrlCifrada)))
        }
    }

    @Test
    fun `registra el estado de actualizacion completado`() {
        runBlocking {
            val resumen = repositorio.importar(TEXTO_E2, "Fuente E2", UBICACION)
            val listaId = requireNotNull(resumen.listaId)

            val estado = requireNotNull(db.estadoActualizacionDao().ultimoDeLista(listaId))
            assertEquals("completada", estado.resultado)
            assertNotNull(estado.fin)
            assertEquals("importacion_inicial", estado.causa)
        }
    }

    @Test
    fun `repetir la importacion no duplica y registra sin cambios`() {
        runBlocking {
            val primera = repositorio.importar(TEXTO_E2, "Fuente E2", UBICACION)
            val segunda = repositorio.importar(TEXTO_E2, "Fuente E2", UBICACION)

            assertEquals(primera.listaId, segunda.listaId)
            assertEquals(1, segunda.canales)
            assertEquals(1, db.canalDao().contar())
            assertEquals(2, db.estadoActualizacionDao().contar())
            val estado = requireNotNull(db.estadoActualizacionDao().ultimoDeLista(requireNotNull(primera.listaId)))
            assertEquals("sin_cambios", estado.resultado)
        }
    }

    @Test
    fun `una ubicacion invalida se rechaza sin escribir`() {
        runBlocking {
            val resumen = repositorio.importar(TEXTO_E2, "Fuente E2", "no-es-url")

            assertEquals("ubicacion_invalida", resumen.error)
            assertEquals(0, db.fuenteDao().todas().size)
            assertEquals(0, db.canalDao().contar())
        }
    }

    @Test
    fun `una lista vacia produce error tipado sin escribir`() {
        runBlocking {
            val resumen = repositorio.importar("#EXTM3U\n", "Fuente E2", UBICACION)

            assertEquals("sin_canales", resumen.error)
            assertNull(resumen.listaId)
            assertEquals(0, db.fuenteDao().todas().size)
        }
    }

    @Test
    fun `una entrada invalida se recupera y el resto se persiste`() {
        runBlocking {
            val texto =
                "#EXTM3U\n" +
                    "#EXTINF:-1,Roto\n" +
                    "no-es-url\n" +
                    "#EXTINF:-1,Bien\n" +
                    "https://example.invalid/bien.m3u8\n"
            val resumen = repositorio.importar(texto, "Fuente E2", UBICACION)

            assertEquals(1, resumen.canales)
            assertEquals(1, resumen.rechazadas)
            assertEquals(1, db.canalDao().contar())
        }
    }

    @Test
    fun `una url de importacion con esquema saf se rechaza`() {
        runBlocking {
            val resumen = repositorio.importarDesdeUrl("Fuente E2", "content://com.example/lista.m3u")

            assertEquals("esquema_no_admitido", resumen.error)
            assertEquals(0, db.fuenteDao().todas().size)
        }
    }

    @Test
    fun `un saf con esquema http se rechaza`() {
        runBlocking {
            val resumen = repositorio.importarDesdeSaf("Fuente E2", "https://example.invalid/lista.m3u")

            assertEquals("esquema_no_admitido", resumen.error)
            assertEquals(0, db.fuenteDao().todas().size)
        }
    }

    @Test
    fun `un 404 devuelve error tipado sin escribir`() {
        runBlocking {
            conServidor(404) { url ->
                val resumen = repositorio.importarDesdeUrl("Fuente E2", url)

                assertEquals("http_4xx", resumen.error)
                assertEquals(0, db.fuenteDao().todas().size)
            }
        }
    }

    @Test
    fun `un 500 conserva el catalogo previo`() {
        runBlocking {
            val primera = repositorio.importar(TEXTO_E2, "Fuente E2", UBICACION)
            assertEquals(1, primera.canales)
            conServidor(500) { url ->
                val resumen = repositorio.importarDesdeUrl("Fuente E2", url)

                assertEquals("http_5xx", resumen.error)
                assertEquals(1, db.canalDao().contar())
                assertEquals(1, db.fuenteDao().todas().size)
                val estado =
                    requireNotNull(db.estadoActualizacionDao().ultimoDeLista(requireNotNull(primera.listaId)))
                assertEquals("http_5xx", estado.resultado)
            }
        }
    }

    private fun descifrarLogo(sobre: ByteArray): String {
        val resultado = CifradoAes.descifrar(claves.claveAes, CifradoAes.aad("Canal", "logo_url_cifrada"), sobre)
        assertTrue("esperaba descifrado ok", resultado is ResultadoDescifrado.Ok)
        return String((resultado as ResultadoDescifrado.Ok).bytes, Charsets.UTF_8)
    }

    /** Servidor HTTP de un solo uso que responde [codigo] y cierra. */
    private suspend fun conServidor(
        codigo: Int,
        bloque: suspend (String) -> Unit,
    ) {
        val servidor = ServerSocket(0)
        coroutineScope {
            val respuesta =
                async(Dispatchers.IO) {
                    servidor.use {
                        it.accept().use { socket -> responder(socket, codigo) }
                    }
                }
            try {
                bloque("http://127.0.0.1:${servidor.localPort}/lista.m3u")
            } finally {
                servidor.close()
                withTimeoutOrNull(TIEMPO_ESPERA_MS) { respuesta.await() }
            }
        }
    }

    private fun responder(
        socket: Socket,
        codigo: Int,
    ) {
        socket.soTimeout = TIEMPO_ESPERA_MS.toInt()
        val lector = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        var linea = lector.readLine()
        while (linea != null && linea.isNotEmpty()) {
            linea = lector.readLine()
        }
        val cabecera = "HTTP/1.1 $codigo Prueba\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
        socket.getOutputStream().apply {
            write(cabecera.toByteArray(Charsets.ISO_8859_1))
            flush()
        }
    }

    private companion object {
        const val LOGO = "https://example.invalid/logo.png"
        const val UBICACION = "https://example.invalid/e2.m3u"
        const val TIEMPO_ESPERA_MS = 5_000L

        val TEXTO_E2 =
            """
            #EXTM3U
            #EXTINF:-1 tvg-id="e2-1" tvg-name="E2 Uno" group-title="Noticias" tvg-logo="$LOGO" custom="x",E2 Uno
            https://example.invalid/uno.m3u8
            """.trimIndent()
    }
}
