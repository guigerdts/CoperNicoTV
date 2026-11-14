package com.copernicotv.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.copernicotv.app.ClavesEnMemoria
import com.copernicotv.app.data.actualizacion.EstadoSolicitud
import com.copernicotv.app.data.actualizacion.ProgramadorActualizaciones
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.app.ui.CatalogoViewModel
import com.copernicotv.core.identity.CaseFolderCompleto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * Informe de la última importación visible en el catálogo (E7-T7 ronda 7,
 * S48-S49).
 *
 * La ruta de éxito del ViewModel descartaba `diagnostico`: el informe copiable
 * de éxito nunca apareció y, en cualquier caso, moría con el diálogo. Aquí se
 * demuestra el contrato completo: diagnóstico en la importación terminada y
 * informe sobreviviente que no depende del diálogo abierto.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CatalogoInformeImportacionTest {
    private lateinit var db: AppDatabase
    private lateinit var repositorio: CatalogoRepositorio
    private lateinit var viewModel: CatalogoViewModel
    private val claves = ClavesEnMemoria()
    private val contexto: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun abrir() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db =
            Room
                .inMemoryDatabaseBuilder(contexto, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        repositorio = CatalogoRepositorio(db, claves, IndiceHmacRepositorio(db, claves), contexto)
        val preferencias = PreferenciasOrdenEnMemoria()
        viewModel =
            CatalogoViewModel(
                repositorio,
                BusquedaCatalogoRepositorio(db),
                CatalogoCrudRepositorio(db, claves, IndiceHmacRepositorio(db, claves), preferencias),
                preferencias,
                ColeccionRepositorio(db),
                PerfilRepositorio(db.perfilDao(), db.listaDao(), CaseFolderCompleto),
                HistorialRepositorio(db),
                ProgramadorSinTrabajo,
            )
    }

    @After
    fun cerrar() {
        runCatching { db.close() }
        Dispatchers.resetMain()
    }

    /** S48-S49: éxito con diagnóstico y informe que sobrevive al cierre del diálogo. */
    @Test
    fun `la importacion exitosa expone el diagnostico y el informe sobrevive al cierre del dialogo`() {
        runBlocking {
            conServidorConCuerpo(200, TEXTO_M3U) { url ->
                viewModel.importarDesdeUrl("Fuente URL", url)
                val estado =
                    withTimeout(TIEMPO_ESPERA) {
                        viewModel.estado.first { it.importacionUrl?.canales != null }
                    }

                val importacion = requireNotNull(estado.importacionUrl)
                assertEquals(1, importacion.canales)
                // S48: el diagnóstico llega a la UI de éxito (botón visible).
                assertNotNull(importacion.diagnostico)
                // S49: el informe sobreviviente nace en la misma transición.
                val informe = requireNotNull(estado.ultimoInformeImportacion)
                assertTrue("esperaba resultado=completada", informe.startsWith("resultado=completada"))

                // Cerrar el diálogo (reiniciar) no borra el informe.
                viewModel.reiniciarImportacionUrl()
                val estadoTrasCierre = viewModel.estado.first { it.importacionUrl == null }
                assertEquals(informe, estadoTrasCierre.ultimoInformeImportacion)
            }
        }
    }

    /** S49: el error también deja informe sobreviviente tras cerrar el diálogo. */
    @Test
    fun `la importacion fallida tambien deja el informe sobreviviente`() {
        runBlocking {
            conServidorConCuerpo(200, TEXTO_HTML, "text/html; charset=utf-8") { url ->
                viewModel.importarDesdeUrl("Fuente URL", url)
                val estado =
                    withTimeout(TIEMPO_ESPERA) {
                        viewModel.estado.first { it.importacionUrl?.error != null }
                    }

                assertNotNull(estado.importacionUrl?.diagnostico)
                val informe = requireNotNull(estado.ultimoInformeImportacion)
                assertTrue("esperaba resultado=m3u_sin_cabecera", informe.startsWith("resultado=m3u_sin_cabecera"))

                viewModel.reiniciarImportacionUrl()
                val estadoTrasCierre = viewModel.estado.first { it.importacionUrl == null }
                assertEquals(informe, estadoTrasCierre.ultimoInformeImportacion)
            }
        }
    }

    /** S50: cifrado AES y Room medidos por separado en el informe de éxito. */
    @Test
    fun `el informe de exito reporta cifrado e insercion con tiempos independientes`() {
        runBlocking {
            conServidorConCuerpo(200, TEXTO_M3U) { url ->
                val resumen = repositorio.importarDesdeUrl("Fuente URL", url)

                assertNull(resumen.error)
                val d = requireNotNull(resumen.diagnostico)
                // 1 canal con logo ⇒ 2 AES por canal + 1 de la ubicación de la lista.
                assertNotNull("esperaba ops_aes", d.operacionesAes)
                assertEquals(3, d.operacionesAes)
                assertNotNull("esperaba tiempo_cifrado_ms", d.tiempoCifradoMs)
                assertTrue("tiempo de cifrado no puede ser negativo", (d.tiempoCifradoMs ?: -1) >= 0)
                assertNotNull("esperaba tiempo_insercion_ms", d.tiempoInsercionMs)
                assertTrue("tiempo de inserción no puede ser negativo", (d.tiempoInsercionMs ?: -1) >= 0)
                assertNotNull("esperaba lotes", d.lotes)
                assertTrue("al menos un lote", (d.lotes ?: 0) >= 1)
                assertEquals(1, d.transacciones)
                assertNotNull(d.canalesPersistidos)
                // El informe copiable lleva los campos nuevos; sin URL ni credenciales.
                val informe = d.aInforme("completada")
                assertTrue(!informe.contains("127.0.0.1") && !informe.contains("lista.m3u"))
                assertTrue("esperaba tiempo_cifrado_ms en el informe", informe.contains("tiempo_cifrado_ms="))
                assertTrue("esperaba ops_aes en el informe", informe.contains("ops_aes="))
                assertTrue("esperaba tiempo_insercion_ms en el informe", informe.contains("tiempo_insercion_ms="))
                assertTrue("esperaba lotes en el informe", informe.contains("lotes="))
                assertTrue("esperaba transacciones en el informe", informe.contains("transacciones="))
                assertTrue("esperaba velocidad_canales_s en el informe", informe.contains("velocidad_canales_s="))
            }
        }
    }

    /** S50: el informe de fallo por HTTP no muestra métricas de persistencia. */
    @Test
    fun `el informe de fallo http omite las metricas de persistencia`() {
        runBlocking {
            conServidor(401) { url ->
                val resumen = repositorio.importarDesdeUrl("Fuente URL", url)

                assertEquals("http_4xx", resumen.error)
                val d = requireNotNull(resumen.diagnostico)
                assertNull(d.tiempoCifradoMs)
                assertNull(d.tiempoInsercionMs)
                assertNull(d.lotes)
                assertNull(d.transacciones)
                val informe = d.aInforme("http_4xx")
                assertFalse(informe.contains("tiempo_cifrado_ms="))
                assertFalse(informe.contains("tiempo_insercion_ms="))
            }
        }
    }

    private companion object {
        const val TIEMPO_ESPERA = 15_000L

        val TEXTO_M3U =
            """
            #EXTM3U
            #EXTINF:-1 tvg-id="inf-1" tvg-name="Informe Uno" group-title="Noticias" tvg-logo="https://example.invalid/logo.png",Informe Uno
            https://example.invalid/uno.m3u8
            """.trimIndent()

        const val TEXTO_HTML = "<html><body>Acceso denegado</body></html>"
    }

    private suspend fun conServidorConCuerpo(
        codigo: Int,
        cuerpo: String,
        tipoContenido: String = "audio/x-mpegurl; charset=utf-8",
        bloque: suspend (String) -> Unit,
    ) {
        val servidor = ServerSocket(0)
        coroutineScope {
            val respuesta =
                async(Dispatchers.IO) {
                    servidor.use {
                        it.accept().use { socket -> responderConCuerpo(socket, codigo, cuerpo, tipoContenido) }
                    }
                }
            try {
                bloque("http://127.0.0.1:${servidor.localPort}/lista.m3u")
            } finally {
                servidor.close()
                withTimeoutOrNull(TIEMPO_ESPERA) { respuesta.await() }
            }
        }
    }

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
                withTimeoutOrNull(TIEMPO_ESPERA) { respuesta.await() }
            }
        }
    }

    private fun responder(
        socket: Socket,
        codigo: Int,
    ) {
        socket.soTimeout = TIEMPO_ESPERA.toInt()
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

    private fun responderConCuerpo(
        socket: Socket,
        codigo: Int,
        cuerpo: String,
        tipoContenido: String,
    ) {
        socket.soTimeout = TIEMPO_ESPERA.toInt()
        val lector = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        var linea = lector.readLine()
        while (linea != null && linea.isNotEmpty()) {
            linea = lector.readLine()
        }
        val bytes = cuerpo.toByteArray(Charsets.UTF_8)
        val cabecera =
            "HTTP/1.1 $codigo Prueba\r\n" +
                "Content-Type: $tipoContenido\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n\r\n"
        socket.getOutputStream().apply {
            write(cabecera.toByteArray(Charsets.ISO_8859_1))
            write(bytes)
            flush()
        }
    }

    private object ProgramadorSinTrabajo : ProgramadorActualizaciones {
        override fun observar(): Flow<Map<Long, EstadoSolicitud>> = flowOf(emptyMap())

        override fun observarLista(listaId: Long): Flow<EstadoSolicitud> = flowOf(EstadoSolicitud())

        override suspend fun solicitarLista(listaId: Long) = Unit

        override suspend fun solicitarFuente(fuenteId: Long) = Unit

        override suspend fun reconciliar() = Unit
    }
}
