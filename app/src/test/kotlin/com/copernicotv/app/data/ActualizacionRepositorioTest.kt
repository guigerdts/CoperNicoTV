package com.copernicotv.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.copernicotv.app.ClavesEnMemoria
import com.copernicotv.app.data.actualizacion.CausasActualizacion
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.app.data.db.entities.ColeccionCanalEntity
import com.copernicotv.app.data.db.entities.ColeccionEntity
import com.copernicotv.app.data.db.entities.FavoritoEntity
import com.copernicotv.app.data.db.entities.FuenteEntity
import com.copernicotv.app.data.db.entities.HistorialEntity
import com.copernicotv.app.data.db.entities.ListaEntity
import com.copernicotv.app.data.db.entities.OcultacionCanalEntity
import com.copernicotv.core.crypto.CifradoAes
import com.copernicotv.core.crypto.ResultadoDescifrado
import com.copernicotv.core.identity.EstadoCanal
import com.copernicotv.core.m3u.ImportadorM3U
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
import java.time.Instant

/**
 * E4-T1/T2/T6: Actualización de una Lista con reconciliación.
 *
 * Cubre F57a (orden permutado), F57b (A→B con N filas y convergencia),
 * F57c (Apariencia ausente conserva Canal y relaciones), F57d (decisión C),
 * F57m8 (ausente que reaparece), m9 (rollback A→B) y S6a (Actualizar manual
 * conserva Favoritos, Historial, Ocultaciones, Colecciones y PKs).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ActualizacionRepositorioTest {
    private lateinit var db: AppDatabase
    private lateinit var repositorio: ActualizacionRepositorio
    private val claves = ClavesEnMemoria()
    private val contexto: Context = ApplicationProvider.getApplicationContext()
    private var fuenteId: Long = 0L
    private var listaId: Long = 0L

    @Before
    fun abrir() {
        contexto.deleteDatabase(NOMBRE_BD)
        db =
            Room
                .databaseBuilder(contexto, AppDatabase::class.java, NOMBRE_BD)
                .allowMainThreadQueries()
                .build()
        repositorio = ActualizacionRepositorio(db, claves, IndiceHmacRepositorio(db, claves), contexto)
        runBlocking { sembrarFuenteYLista() }
    }

    @After
    fun cerrar() {
        runCatching { db.close() }
        contexto.deleteDatabase(NOMBRE_BD)
    }

    @Test
    fun `F57a el orden de la lista no altera la identidad ni las pks`() {
        runBlocking {
            val ids =
                actualizar(
                    """
                    #EXTM3U
                    #EXTINF:-1 tvg-id="uno" group-title="G",Uno
                    https://example.com/uno
                    #EXTINF:-1 tvg-id="dos" group-title="G",Dos
                    https://example.com/dos
                    #EXTINF:-1 tvg-id="tres" group-title="G",Tres
                    https://example.com/tres
                    """.trimIndent(),
                )
            assertEquals(3, ids.nuevas)
            val antes = db.canalDao().porFuente(fuenteId).associate { it.tvgId to it.id }

            actualizar(
                """
                #EXTM3U
                #EXTINF:-1 tvg-id="tres" group-title="G",Tres
                https://example.com/tres
                #EXTINF:-1 tvg-id="uno" group-title="G",Uno
                https://example.com/uno
                #EXTINF:-1 tvg-id="dos" group-title="G",Dos
                https://example.com/dos
                """.trimIndent(),
            )

            val despues = db.canalDao().porFuente(fuenteId)
            assertEquals(3, despues.size)
            assertEquals(antes, despues.associate { it.tvgId to it.id })
        }
    }

    @Test
    fun `F57b una declaracion canonica hace converger dos filas a B sin fusionar`() {
        runBlocking {
            actualizar(
                """
                #EXTM3U
                #EXTINF:-1 tvg-id="a" group-title="G",Comun
                https://example.com/comun
                #EXTINF:-1 tvg-id="b" group-title="G",Comun
                https://example.com/comun
                """.trimIndent(),
            )
            assertEquals(2, db.canalDao().porFuente(fuenteId).size)

            val resumen =
                actualizar(
                    """
                    #EXTM3U
                    #EXTINF:-1 group-title="G",Comun
                    https://example.com/comun
                    """.trimIndent(),
                )

            assertEquals(0, resumen.nuevas)
            assertEquals(0, resumen.ausentes)
            assertEquals(1, resumen.ambiguas)
            val filas = db.canalDao().porFuente(fuenteId)
            assertEquals(2, filas.size)
            assertTrue(filas.all { it.estado == EstadoCanal.B })
            assertTrue(filas.all { it.claveUnicidad == null })
            assertEquals(1, db.observacionPendienteDao().contarPorFuente(fuenteId))
            assertTrue(db.evidenciaColisionDao().contar() >= 1)
            assertEquals(0, db.detalleAusenciaDao().contar())
        }
    }

    @Test
    fun `F57c una apariencia ausente no borra el canal ni sus relaciones`() {
        runBlocking {
            actualizar(
                """
                #EXTM3U
                #EXTINF:-1 tvg-id="uno" group-title="Noticias",Uno
                https://example.com/uno
                """.trimIndent(),
            )
            val canal = requireNotNull(db.canalDao().porTvgId(fuenteId, "uno"))
            val ahora = Instant.parse("2026-02-01T00:00:00Z")
            db.favoritoDao().insertar(FavoritoEntity(canalId = canal.id, creadoEn = ahora))
            db.historialDao().insertar(HistorialEntity(canalId = canal.id, fecha = ahora))
            db.ocultacionCanalDao().insertar(OcultacionCanalEntity(canalId = canal.id, creadoEn = ahora))
            val coleccion = db.coleccionDao().insertar(ColeccionEntity(nombre = "Mixta", creadoEn = ahora, actualizadoEn = ahora))
            db.coleccionCanalDao().insertar(ColeccionCanalEntity(coleccionId = coleccion, canalId = canal.id, posicion = 2))

            actualizar(
                """
                #EXTM3U
                #EXTINF:-1 tvg-id="uno" group-title="Deportes",Uno
                https://example.com/uno
                """.trimIndent(),
            )

            val actualizado = requireNotNull(db.canalDao().porId(canal.id))
            assertEquals("uno", actualizado.tvgId)
            val apariencias = db.aparienciaDao().porCanalEnLista(listaId, canal.id)
            assertEquals(1, apariencias.size)
            assertEquals("Deportes", apariencias.single().groupTitle)
            assertNotNull(db.favoritoDao().porCanal(canal.id))
            assertNotNull(db.historialDao().porCanal(canal.id))
            assertNotNull(db.ocultacionCanalDao().porCanal(canal.id))
            assertEquals(1, db.coleccionCanalDao().porCanal(coleccion, canal.id).size)
            assertEquals(2, db.coleccionCanalDao().porCanal(coleccion, canal.id).single().posicion)
        }
    }

    @Test
    fun `F57d cerrar un conjunto C marca la decision del usuario como superada`() {
        runBlocking {
            actualizar(
                """
                #EXTM3U
                #EXTINF:-1 tvg-id="uno" group-title="G",Uno
                https://example.com/uno
                """.trimIndent(),
            )
            val original = requireNotNull(db.canalDao().porTvgId(fuenteId, "uno"))
            val ahora = Instant.parse("2026-02-01T00:00:00Z")
            db.canalDao().actualizar(
                original.copy(
                    estado = EstadoCanal.C,
                    claveUnicidad = null,
                    decision = "mismo_canal",
                    fechaDecision = ahora,
                    evidencia = "reconciliacion:v1;motivo=colision_canonica;filas=1",
                ),
            )

            actualizar(
                """
                #EXTM3U
                #EXTINF:-1 tvg-id="uno" group-title="G",Uno
                https://example.com/uno
                """.trimIndent(),
            )

            val cerrado = requireNotNull(db.canalDao().porId(original.id))
            assertEquals(EstadoCanal.A, cerrado.estado)
            assertEquals("tvg:v1:uno", cerrado.claveUnicidad)
            assertEquals("mismo_canal:superada", cerrado.decision)
            assertNotNull(cerrado.fechaDecision)
            assertNull(cerrado.evidencia)
        }
    }

    @Test
    fun `F57m8 un canal ausente se conserva y reaparece con la misma pk`() {
        runBlocking {
            actualizar(
                """
                #EXTM3U
                #EXTINF:-1 tvg-id="uno" group-title="G",Uno
                https://example.com/uno
                #EXTINF:-1 tvg-id="dos" group-title="G",Dos
                https://example.com/dos
                """.trimIndent(),
            )
            val dos = requireNotNull(db.canalDao().porTvgId(fuenteId, "dos"))

            val primera =
                actualizar(
                    """
                    #EXTM3U
                    #EXTINF:-1 tvg-id="uno" group-title="G",Uno
                    https://example.com/uno
                    """.trimIndent(),
                )
            assertEquals(1, primera.ausentes)
            assertEquals(EstadoCanal.A, requireNotNull(db.canalDao().porId(dos.id)).estado)
            assertEquals(dos.id, requireNotNull(db.canalDao().porTvgId(fuenteId, "dos")).id)

            val segunda =
                actualizar(
                    """
                    #EXTM3U
                    #EXTINF:-1 tvg-id="uno" group-title="G",Uno
                    https://example.com/uno
                    #EXTINF:-1 tvg-id="dos" group-title="G",Dos
                    https://example.com/dos
                    """.trimIndent(),
                )
            assertEquals(0, segunda.nuevas)
            assertEquals(0, segunda.ausentes)
            assertEquals(dos.id, requireNotNull(db.canalDao().porTvgId(fuenteId, "dos")).id)
            assertEquals(2, db.canalDao().porFuente(fuenteId).size)
        }
    }

    @Test
    fun `m9 un fallo tras liberar claves revierte todo el A a B`() {
        runBlocking {
            actualizar(
                """
                #EXTM3U
                #EXTINF:-1 tvg-id="a" group-title="G",Comun
                https://example.com/comun
                #EXTINF:-1 tvg-id="b" group-title="G",Comun
                https://example.com/comun
                """.trimIndent(),
            )
            val antes = db.canalDao().porFuente(fuenteId)
            assertEquals(2, antes.size)
            assertTrue(antes.all { it.estado == EstadoCanal.A })

            repositorio.puntoDeFallo = { etapa ->
                if (etapa == ActualizacionRepositorio.EtapaActualizacion.TRAS_LIBERAR_CLAVES) {
                    error("fallo inyectado")
                }
            }
            val fallida =
                actualizar(
                    """
                    #EXTM3U
                    #EXTINF:-1 group-title="G",Comun
                    https://example.com/comun
                    """.trimIndent(),
                )
            assertEquals("fallida", fallida.resultado)
            assertEquals("actualizacion_fallida", fallida.error)
            val trasFallo = db.canalDao().porFuente(fuenteId)
            assertEquals(antes.map { it.id }, trasFallo.map { it.id })
            assertTrue(trasFallo.all { it.estado == EstadoCanal.A })
            assertTrue(trasFallo.all { it.claveUnicidad != null })
            assertEquals(0, db.observacionPendienteDao().contarPorFuente(fuenteId))
            assertEquals(0, db.evidenciaColisionDao().contar())

            repositorio.puntoDeFallo = null
            actualizar(
                """
                #EXTM3U
                #EXTINF:-1 group-title="G",Comun
                https://example.com/comun
                """.trimIndent(),
            )
            val trasReintento = db.canalDao().porFuente(fuenteId)
            assertEquals(2, trasReintento.size)
            assertTrue(trasReintento.all { it.estado == EstadoCanal.B })
            assertEquals(1, db.observacionPendienteDao().contarPorFuente(fuenteId))
        }
    }

    @Test
    fun `S6a actualizar conserva favoritos historial ocultaciones colecciones y pks`() {
        runBlocking {
            actualizar(
                """
                #EXTM3U
                #EXTINF:-1 tvg-id="uno" group-title="Noticias",Uno
                https://example.com/uno
                """.trimIndent(),
            )
            val original = requireNotNull(db.canalDao().porTvgId(fuenteId, "uno"))
            val ahora = Instant.parse("2026-02-01T00:00:00Z")
            db.favoritoDao().insertar(FavoritoEntity(canalId = original.id, creadoEn = ahora))
            db.historialDao().insertar(HistorialEntity(canalId = original.id, fecha = ahora))
            db.ocultacionCanalDao().insertar(OcultacionCanalEntity(canalId = original.id, creadoEn = ahora))
            val coleccion = db.coleccionDao().insertar(ColeccionEntity(nombre = "Mixta", creadoEn = ahora, actualizadoEn = ahora))
            db.coleccionCanalDao().insertar(ColeccionCanalEntity(coleccionId = coleccion, canalId = original.id, posicion = 1))

            actualizar(
                """
                #EXTM3U
                #EXTINF:-1 tvg-id="uno" group-title="Noticias",Uno HD
                https://example.com/uno-hd
                """.trimIndent(),
            )

            val actualizado = requireNotNull(db.canalDao().porId(original.id))
            assertEquals(original.id, actualizado.id)
            assertEquals("Uno HD", actualizado.nombre)
            assertEquals("https://example.com/uno-hd", descifrar(actualizado.urlStreamCifrada))
            assertNotNull(db.favoritoDao().porCanal(original.id))
            assertEquals(ahora, requireNotNull(db.historialDao().porCanal(original.id)).fecha)
            assertNotNull(db.ocultacionCanalDao().porCanal(original.id))
            assertEquals(1, db.coleccionCanalDao().porCanal(coleccion, original.id).size)
            assertEquals(1, db.coleccionCanalDao().porCanal(coleccion, original.id).single().posicion)
            assertEquals(1, db.canalDao().porFuente(fuenteId).size)
            assertNotEquals(original.urlStreamCifrada.toList(), actualizado.urlStreamCifrada.toList())
        }
    }

    @Test
    fun `S6b la actualizacion automatica conserva favoritos historial y pks tras reabrir`() {
        runBlocking {
            actualizar(
                """
                #EXTM3U
                #EXTINF:-1 tvg-id="uno" group-title="Noticias",Uno
                https://example.com/uno
                """.trimIndent(),
            )
            val original = requireNotNull(db.canalDao().porTvgId(fuenteId, "uno"))
            val ahora = Instant.parse("2026-02-01T00:00:00Z")
            db.favoritoDao().insertar(FavoritoEntity(canalId = original.id, creadoEn = ahora))
            db.historialDao().insertar(HistorialEntity(canalId = original.id, fecha = ahora))

            actualizar(
                """
                #EXTM3U
                #EXTINF:-1 tvg-id="uno" group-title="Noticias",Uno HD
                https://example.com/uno-hd
                """.trimIndent(),
                causa = CausasActualizacion.AUTOMATICA,
            )

            // Un reinicio cierra la base y la vuelve a abrir desde el mismo archivo.
            db.close()
            db =
                Room
                    .databaseBuilder(contexto, AppDatabase::class.java, NOMBRE_BD)
                    .allowMainThreadQueries()
                    .build()
            repositorio = ActualizacionRepositorio(db, claves, IndiceHmacRepositorio(db, claves), contexto)

            val actualizado = requireNotNull(db.canalDao().porId(original.id))
            assertEquals(original.id, actualizado.id)
            assertEquals("Uno HD", actualizado.nombre)
            assertNotNull("El favorito sobrevive al reinicio", db.favoritoDao().porCanal(original.id))
            assertEquals(ahora, requireNotNull(db.historialDao().porCanal(original.id)).fecha)
            assertEquals(1, db.canalDao().porFuente(fuenteId).size)
        }
    }

    // ===== E7-T7: transporte HTTP de la Actualización =====

    /**
     * Sembrado de una Lista de esquema `http` con la ubicación cifrada como
     * en producción: la lectura HTTP descifra la URL y abre la conexión.
     */
    private suspend fun sembrarListaHttp(url: String): Long {
        val ahora = Instant.parse("2026-01-01T00:00:00Z")
        return db.listaDao().insertar(
            ListaEntity(
                fuenteId = fuenteId,
                nombre = "Lista HTTP",
                ubicacionCifrada =
                    CifradoAes.cifrar(
                        claves.claveAes,
                        CifradoAes.aad("Lista", "ubicacion_cifrada"),
                        url.toByteArray(),
                    ),
                ubicacionHmac = ByteArray(32),
                esquema = "http",
                orden = 1,
                estado = "activa",
                creadoEn = ahora,
                actualizadoEn = ahora,
            ),
        )
    }

    /** E7-T7: una Lista `http` se actualiza de extremo a extremo contra un servidor local. */
    @Test
    fun `la actualizacion de una lista http local importa el cuerpo`() {
        runBlocking {
            conServidorConCuerpo(TEXTO_UNO) { url ->
                val listaHttp = sembrarListaHttp(url)
                val http = ActualizacionRepositorio(db, claves, IndiceHmacRepositorio(db, claves), contexto, verificaCleartext = { true })

                val resumen = http.actualizarLista(listaHttp)

                assertEquals("completada", resumen.resultado)
                assertNotNull("el canal de la lista http quedó reconciliado", db.canalDao().porTvgId(fuenteId, "uno"))
            }
        }
    }

    /** `.invalid` es un TLD reservado (RFC 6761) que nunca resuelve. */
    @Test(timeout = 30_000)
    fun `un dominio que no resuelve devuelve dns tipado`() {
        runBlocking {
            val listaHttp = sembrarListaHttp("http://lista.prueba.invalid/lista.m3u")
            val http = ActualizacionRepositorio(db, claves, IndiceHmacRepositorio(db, claves), contexto, verificaCleartext = { true })

            val resumen = http.actualizarLista(listaHttp)

            assertEquals("fallida", resumen.resultado)
            assertEquals("dns_no_resuelto", resumen.error)
        }
    }

    /** Un puerto sin oyente se informa como conexión rechazada. */
    @Test
    fun `una conexion rechazada devuelve error tipado`() {
        runBlocking {
            val servidor = ServerSocket(0)
            val url = "http://127.0.0.1:${servidor.localPort}/lista.m3u"
            servidor.close()
            val listaHttp = sembrarListaHttp(url)
            val http = ActualizacionRepositorio(db, claves, IndiceHmacRepositorio(db, claves), contexto, verificaCleartext = { true })

            val resumen = http.actualizarLista(listaHttp)

            assertEquals("fallida", resumen.resultado)
            assertEquals("conexion_rechazada", resumen.error)
        }
    }

    /**
     * Con la política bloqueada el fallo es `bloqueo_http` y no se abre
     * conexión: el socket queda en escucha sin atender y una conexión real
     * colgaría en el timeout de lectura.
     */
    @Test
    fun `una politica que bloquea el texto claro se informa sin conectar`() {
        runBlocking {
            val servidor = ServerSocket(0)
            try {
                val listaHttp = sembrarListaHttp("http://127.0.0.1:${servidor.localPort}/lista.m3u")
                val bloqueado =
                    ActualizacionRepositorio(
                        db,
                        claves,
                        IndiceHmacRepositorio(db, claves),
                        contexto,
                        verificaCleartext = { false },
                    )

                val resumen = bloqueado.actualizarLista(listaHttp)

                assertEquals("fallida", resumen.resultado)
                assertEquals("bloqueo_http", resumen.error)
            } finally {
                servidor.close()
            }
        }
    }

    /** Servidor HTTP de un solo uso que responde 200 con [cuerpo] y cierra. */
    private suspend fun conServidorConCuerpo(
        cuerpo: String,
        bloque: suspend (String) -> Unit,
    ) {
        val servidor = ServerSocket(0)
        coroutineScope {
            val respuesta =
                async(Dispatchers.IO) {
                    runCatching {
                        servidor.use { escucha ->
                            escucha.accept().use { socket -> responderConCuerpo(socket, cuerpo) }
                        }
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

    private fun responderConCuerpo(
        socket: Socket,
        cuerpo: String,
    ) {
        socket.soTimeout = TIEMPO_ESPERA_MS.toInt()
        val lector = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        var linea = lector.readLine()
        while (linea != null && linea.isNotEmpty()) {
            linea = lector.readLine()
        }
        val bytes = cuerpo.toByteArray(Charsets.UTF_8)
        val cabecera =
            "HTTP/1.1 200 Prueba\r\n" +
                "Content-Type: audio/x-mpegurl; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n\r\n"
        socket.getOutputStream().apply {
            write(cabecera.toByteArray(Charsets.ISO_8859_1))
            write(bytes)
            flush()
        }
    }

    private suspend fun actualizar(
        texto: String,
        causa: String = CausasActualizacion.MANUAL,
    ): ResumenActualizacion {
        val importado = ImportadorM3U.importar(texto, claves.crearClaveHmac(1))
        return repositorio.actualizarConImportado(listaId, importado, causa)
    }

    private suspend fun sembrarFuenteYLista() {
        val ahora = Instant.parse("2026-01-01T00:00:00Z")
        fuenteId =
            db.fuenteDao().insertar(
                FuenteEntity(
                    nombre = "Fuente",
                    estado = "activa",
                    usarIntervaloGlobal = true,
                    creadoEn = ahora,
                    actualizadoEn = ahora,
                ),
            )
        listaId =
            db.listaDao().insertar(
                ListaEntity(
                    fuenteId = fuenteId,
                    nombre = "Lista",
                    ubicacionCifrada =
                        CifradoAes.cifrar(
                            claves.claveAes,
                            CifradoAes.aad("Lista", "ubicacion_cifrada"),
                            "content://prueba/lista.m3u".toByteArray(),
                        ),
                    ubicacionHmac = ByteArray(32),
                    esquema = "content",
                    orden = 0,
                    estado = "activa",
                    creadoEn = ahora,
                    actualizadoEn = ahora,
                ),
            )
    }

    private fun descifrar(cifrado: ByteArray): String {
        val resultado =
            CifradoAes.descifrar(
                claves.claveAes,
                CifradoAes.aad("Canal", "url_stream_cifrada"),
                cifrado,
            )
        return (resultado as ResultadoDescifrado.Ok).bytes.toString(Charsets.UTF_8)
    }

    private companion object {
        const val NOMBRE_BD = "actualizacion-repositorio.db"
        const val TIEMPO_ESPERA_MS = 10_000L

        val TEXTO_UNO =
            """
            #EXTM3U
            #EXTINF:-1 tvg-id="uno" group-title="Noticias",Uno
            https://example.com/uno
            """.trimIndent()
    }
}
