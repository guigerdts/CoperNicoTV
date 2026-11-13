package com.copernicotv.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.copernicotv.app.ClavesEnMemoria
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.core.crypto.CifradoAes
import com.copernicotv.core.crypto.ResultadoDescifrado
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
import java.net.InetAddress
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
        repositorio =
            CatalogoRepositorio(
                db,
                claves,
                IndiceHmacRepositorio(db, claves),
                contexto,
                verificaCleartext = { true },
            )
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
    fun `una url http valida persiste la fuente y su lista`() {
        runBlocking {
            conServidorConCuerpo(200, TEXTO_E2) { url ->
                val resumen = repositorio.importarDesdeUrl("Fuente URL", url)

                assertEquals(1, resumen.canales)
                assertEquals(1, db.canalDao().contar())
                val fuente = requireNotNull(db.fuenteDao().porNombre("Fuente URL"))
                val lista = db.listaDao().porFuente(fuente.id).single()
                assertEquals("http", lista.esquema)
                assertEquals("completada", db.estadoActualizacionDao().ultimoDeLista(lista.id)?.resultado)
            }
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

    @Test(timeout = 60_000)
    fun `un timeout conserva el catalogo previo`() {
        runBlocking {
            val primera = repositorio.importar(TEXTO_E2, "Fuente E2", UBICACION)
            assertEquals(1, primera.canales)
            val rapido =
                CatalogoRepositorio(
                    db,
                    claves,
                    IndiceHmacRepositorio(db, claves),
                    contexto,
                    TiemposHttp(lecturaMs = 300),
                    verificaCleartext = { true },
                )
            conServidorMudo { url ->
                val resumen = rapido.importarDesdeUrl("Fuente E2", url)

                assertEquals("timeout", resumen.error)
                assertEquals(1, db.canalDao().contar())
                assertEquals(1, db.fuenteDao().todas().size)
                val estado =
                    requireNotNull(db.estadoActualizacionDao().ultimoDeLista(requireNotNull(primera.listaId)))
                assertEquals("timeout", estado.resultado)
            }
        }
    }

    /** E7-T7: `.invalid` es un TLD reservado (RFC 6761) que nunca resuelve. */
    @Test
    fun `un dominio que no resuelve devuelve dns tipado sin escribir`() {
        runBlocking {
            val resumen = repositorio.importarDesdeUrl("Fuente E2", "http://lista.prueba.invalid/lista.m3u")

            assertEquals("dns_no_resuelto", resumen.error)
            assertEquals(0, db.fuenteDao().todas().size)
        }
    }

    /** E7-T7: un puerto sin oyente se informa como conexión rechazada. */
    @Test
    fun `una conexion rechazada devuelve error tipado sin escribir`() {
        runBlocking {
            val servidor = ServerSocket(0)
            val url = "http://127.0.0.1:${servidor.localPort}/lista.m3u"
            servidor.close()

            val resumen = repositorio.importarDesdeUrl("Fuente E2", url)

            assertEquals("conexion_rechazada", resumen.error)
            assertEquals(0, db.fuenteDao().todas().size)
        }
    }

    /** E7-T7: el diagnóstico expone los campos técnicos sin datos sensibles. */
    @Test
    fun `el diagnostico registra codigo http bytes entradas canales y tiempos`() {
        runBlocking {
            conServidorConCuerpo(200, TEXTO_E2) { url ->
                val resumen = repositorio.importarDesdeUrl("Fuente URL", url)

                assertNull(resumen.error)
                assertEquals(1, resumen.canales)
                val d = requireNotNull(resumen.diagnostico)
                assertEquals(200, d.codigoHttp)
                assertNotNull(d.tipoContenido)
                assertTrue("esperaba bytes contados", (d.bytesDescargados ?: 0) > 0)
                assertEquals(1, d.entradasDetectadas)
                assertEquals(1, d.canalesPersistidos)
                assertNotNull(d.tiempoDescargaMs)
                assertNotNull(d.tiempoProcesamientoMs)
                assertNotNull(d.tiempoGuardadoMs)
                // La línea de Logcat nunca lleva la URL ni credenciales.
                val linea = d.aLogcat("completada")
                assertTrue(!linea.contains("127.0.0.1") && !linea.contains("lista.m3u"))
            }
        }
    }

    /** E7-T7: 200 con HTML (panel rechaza credenciales) produce error tipado con tipo de contenido. */
    @Test
    fun `una respuesta html produce error tipado con diagnostico de contenido`() {
        runBlocking {
            conServidorConCuerpo(200, "<html><body>Acceso denegado</body></html>", "text/html; charset=utf-8") { url ->
                val resumen = repositorio.importarDesdeUrl("Fuente E2", url)

                assertEquals("m3u_sin_cabecera", resumen.error)
                assertEquals(0, db.fuenteDao().todas().size)
                val d = requireNotNull(resumen.diagnostico)
                assertEquals(200, d.codigoHttp)
                assertEquals("text/html; charset=utf-8", d.tipoContenido)
                assertEquals("m3u_sin_cabecera", d.motivo)
            }
        }
    }

    /** E7-T7: un 401 cae en la clase http_4xx y expone el código exacto. */
    @Test
    fun `un 401 devuelve error tipado con codigo http en el diagnostico`() {
        runBlocking {
            conServidor(401) { url ->
                val resumen = repositorio.importarDesdeUrl("Fuente E2", url)

                assertEquals("http_4xx", resumen.error)
                assertEquals(401, resumen.diagnostico?.codigoHttp)
                assertEquals(0, db.fuenteDao().todas().size)
            }
        }
    }

    /** E7-T7: la Lista importada queda como única activa, también repetida. */
    @Test
    fun `la importacion activa exclusivamente la lista importada`() {
        runBlocking {
            val primera = repositorio.importar(TEXTO_E2, "Fuente E2", UBICACION)
            assertEquals(1, primera.canales)
            conServidorConCuerpo(200, TEXTO_XTREAM) { url ->
                val resumen = repositorio.importarDesdeUrl("Fuente Xtream", url)

                assertEquals(2, resumen.canales)
                val activas = db.listaDao().listar().filter { it.estado == "activa" }
                assertEquals(1, activas.size)
                val fuente = requireNotNull(db.fuenteDao().porNombre("Fuente Xtream"))
                val lista = db.listaDao().porFuente(fuente.id).single()
                assertEquals(lista.id, activas.single().id)
                // El catálogo visible responde a la lista recién importada.
                val visibles = repositorio.canalesDeListaActiva().first()
                assertEquals(2, visibles.mapNotNull { it }.size)
            }
        }
    }

    /** E7-T7: repetir la importación de una Fuente existente selecciona su Lista. */
    @Test
    fun `la importacion repetida de una fuente existente selecciona su lista`() {
        runBlocking {
            val primera = repositorio.importar(TEXTO_E2, "Fuente E2", UBICACION)
            assertEquals(1, primera.canales)
            conServidorConCuerpo(200, TEXTO_XTREAM) { url ->
                repositorio.importarDesdeUrl("Fuente Xtream", url)
            }
            conServidorConCuerpo(200, TEXTO_E2) { url ->
                val resumen = repositorio.importarDesdeUrl("Fuente E2", url)

                assertNull(resumen.error)
                val activas = db.listaDao().listar().filter { it.estado == "activa" }
                assertEquals(1, activas.size)
                val fuente = requireNotNull(db.fuenteDao().porNombre("Fuente E2"))
                val lista = db.listaDao().porFuente(fuente.id).single()
                assertEquals(lista.id, activas.single().id)
                assertEquals("sin_cambios", db.estadoActualizacionDao().ultimoDeLista(lista.id)?.resultado)
            }
        }
    }

    /** E7-T7: las fases llegan en el orden contractual. */
    @Test
    fun `la fase avanza por el orden esperado`() {
        runBlocking {
            val fases = mutableListOf<String>()
            conServidorConCuerpo(200, TEXTO_E2) { url ->
                val resumen = repositorio.importarDesdeUrl("Fuente URL", url) { fase -> fases.add(fase.codigo) }

                assertNull(resumen.error)
            }
            assertEquals(
                listOf("conectando", "descargando", "procesando_m3u", "guardando"),
                fases,
            )
        }
    }

    /** E7-T7: EXTINF con URLs TS y M3U8 y nombres acentuados se importa completo. */
    @Test
    fun `un m3u xtream con urls ts y m3u8 y acentos se importa`() {
        runBlocking {
            conServidorConCuerpo(200, TEXTO_XTREAM) { url ->
                val resumen = repositorio.importarDesdeUrl("Fuente Xtream", url)

                assertNull(resumen.error)
                assertEquals(2, resumen.canales)
                val fuente = requireNotNull(db.fuenteDao().porNombre("Fuente Xtream"))
                val nombres = db.canalDao().porFuente(fuente.id).map { it.nombre }
                assertTrue("Información Deportiva" in nombres)
            }
        }
    }

    /**
     * E7-T7: si la política de red bloquea el texto claro, el fallo es
     * `bloqueo_http` y no se abre conexión (el servidor de prueba no es
     * atendido por nadie, así que una conexión real colgaría).
     */
    @Test
    fun `una politica que bloquea el texto claro se informa sin conectar`() {
        runBlocking {
            val bloqueado =
                CatalogoRepositorio(
                    db,
                    claves,
                    IndiceHmacRepositorio(db, claves),
                    contexto,
                    verificaCleartext = { false },
                )
            // Socket en escucha sin aceptar conexiones: el bloqueo ocurre
            // antes de abrir la conexión, así que nadie atiende el socket. Si
            // el código conectara de todos modos, la lectura colgaría en el
            // timeout y la prueba fallaría.
            val servidor = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
            try {
                val resumen = bloqueado.importarDesdeUrl("Fuente URL", "http://127.0.0.1:${servidor.localPort}/lista.m3u")

                assertEquals("bloqueo_http", resumen.error)
                assertEquals(0, db.fuenteDao().todas().size)
            } finally {
                servidor.close()
            }
        }
    }

    @Test
    fun `la importacion por url publica avance observable y diagnostico con canales detectados`() {
        runBlocking {
            conServidorConCuerpo(200, TEXTO_XTREAM) { url ->
                val fases = mutableListOf<String>()
                val fotos = mutableListOf<AvanceVisible>()
                val resumen =
                    repositorio.importarDesdeUrl(
                        "Fuente Avance",
                        url,
                        alFase = { fase -> fases += fase.codigo },
                        alAvance = { foto -> fotos += foto },
                    )

                assertNull(resumen.error)
                val diagnostico = checkNotNull(resumen.diagnostico)
                assertEquals(2, diagnostico.entradasDetectadas)
                assertEquals(2, diagnostico.canalesDetectados)
                // El informe lleva el último avance con contadores (S21).
                assertTrue(diagnostico.ultimoAvance.orEmpty().contains("canales="))
                // Contrato del callback de avance: contadores y tiempo válidos,
                // sin datos de la lista.
                fotos.forEach { foto ->
                    assertTrue(foto.msTranscurrido >= 0)
                    assertTrue(foto.entradas >= 0 && foto.canales >= 0 && foto.preparando >= 0)
                }
                assertTrue(fases.contains("procesando_m3u"))
                assertTrue(fases.contains("guardando"))
            }
        }
    }

    @Test
    fun `el informe diagnosticos es multilinea y no filtra la ubicacion`() {
        runBlocking {
            conServidorConCuerpo(200, TEXTO_XTREAM) { url ->
                val resumen = repositorio.importarDesdeUrl("Fuente Informe", url)

                assertNull(resumen.error)
                val informe = checkNotNull(resumen.diagnostico).aInforme("completada")
                assertTrue(informe.contains("\n"))
                assertTrue(informe.contains("resultado=completada"))
                assertTrue(informe.contains("canales_detectados=2"))
                assertTrue(informe.contains("entradas=2"))
                assertFalse(informe.contains("127.0.0.1"))
                assertFalse(informe.contains("example.invalid"))
            }
        }
    }

    @Test
    fun `cancelar en plena importacion conserva el catalogo previo`() {
        runBlocking {
            repositorio.importar(TEXTO_E2, "Fuente E2", UBICACION)
            conServidorConCuerpo(200, TEXTO_XTREAM) { url ->
                // La cancelación es cooperativa y determinista: el callback de
                // fase corre dentro de la coroutine de importación, así que
                // lanzar la cancelación en PROCESANDO aborta antes de escribir.
                val resultado =
                    runCatching {
                        repositorio.importarDesdeUrl(
                            "Fuente Cancel",
                            url,
                            alFase = { fase ->
                                if (fase == FaseImportacion.PROCESANDO) {
                                    throw CancellationException("cancelada por el usuario")
                                }
                            },
                        )
                    }

                assertTrue("esperaba cancelación", resultado.exceptionOrNull() is CancellationException)
                assertEquals(0, db.fuenteDao().todas().count { it.nombre == "Fuente Cancel" })
                // El catálogo previo queda intacto: la transacción no llegó a
                // abrirse (S22).
                assertEquals(1, db.canalDao().contar())
            }
        }
    }

    @Test
    fun `una lista de miles de canales persiste completa por lotes`() {
        val texto =
            buildString {
                append("#EXTM3U\n")
                repeat(5000) { indice ->
                    append("#EXTINF:-1 tvg-id=\"lote-$indice\" group-title=\"Lote ${indice % 7}\",Canal $indice\n")
                    append("https://example.invalid/$indice.ts\n")
                }
            }
        runBlocking {
            conServidorConCuerpo(200, texto) { url ->
                val resumen = repositorio.importarDesdeUrl("Fuente Lote", url)

                assertNull(resumen.error)
                assertEquals(5000, resumen.canales)
                val diagnostico = checkNotNull(resumen.diagnostico)
                assertEquals(5000, diagnostico.canalesDetectados)
                assertEquals(5000, db.canalDao().contar())
                assertEquals(7, db.grupoDao().contar(db.listaDao().listar().first().id))
                assertEquals(5000, db.aparienciaDao().contar(db.listaDao().listar().first().id))
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

    /** Servidor HTTP de un solo uso que responde [codigo] con [cuerpo]. */
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
                withTimeoutOrNull(TIEMPO_ESPERA_MS) { respuesta.await() }
            }
        }
    }

    /** Acepta la conexión y no responde: el cliente vence por tiempo de lectura. */
    private suspend fun conServidorMudo(bloque: suspend (String) -> Unit) {
        val servidor = ServerSocket(0)
        coroutineScope {
            val mudo =
                async(Dispatchers.IO) {
                    runCatching {
                        servidor.use { escucha ->
                            escucha.accept().use { delay(TIEMPO_MUDO_MS) }
                        }
                    }
                }
            try {
                bloque("http://127.0.0.1:${servidor.localPort}/lista.m3u")
            } finally {
                servidor.close()
                withTimeoutOrNull(TIEMPO_ESPERA_MS) { mudo.await() }
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

    private fun responderConCuerpo(
        socket: Socket,
        codigo: Int,
        cuerpo: String,
        tipoContenido: String,
    ) {
        socket.soTimeout = TIEMPO_ESPERA_MS.toInt()
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

    private companion object {
        const val LOGO = "https://example.invalid/logo.png"
        const val UBICACION = "https://example.invalid/e2.m3u"
        const val TIEMPO_ESPERA_MS = 5_000L
        const val TIEMPO_MUDO_MS = 2_000L

        /** E7-T7: playlist Xtream con URLs TS, M3U8 y nombres acentuados. */
        val TEXTO_XTREAM =
            """
            #EXTM3U
            #EXTINF:-1 tvg-id="dep-1" group-title="Deportes",Información Deportiva
            http://example.invalid/stream.ts
            #EXTINF:0 group-title="General",Canal General
            http://example.invalid/general.m3u8
            """.trimIndent()

        val TEXTO_E2 =
            """
            #EXTM3U
            #EXTINF:-1 tvg-id="e2-1" tvg-name="E2 Uno" group-title="Noticias" tvg-logo="$LOGO" custom="x",E2 Uno
            https://example.invalid/uno.m3u8
            """.trimIndent()
    }
}
