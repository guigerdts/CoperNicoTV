package com.copernicotv.app.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.core.app.ApplicationProvider
import com.copernicotv.app.AlmacenClaveDatosEnMemoria
import com.copernicotv.app.ClavesEnMemoria
import com.copernicotv.app.crypto.ClavesDatos
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.core.crypto.CifradoAes
import com.copernicotv.core.crypto.ResultadoDescifrado
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
import java.net.ServerSocket
import java.net.Socket

/**
 * Migración de E7-T7 ronda 8 (S6): la importación es la migración.
 *
 * Cubre los casos de migración obligatorios sobre Room real: interrupción con
 * reanudación, repetición idempotente, informe sin material de claves y la
 * importación masiva de 146.762 canales con readback de descifrado.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportacionMigracionTest {
    private lateinit var db: AppDatabase
    private lateinit var repositorio: CatalogoRepositorio
    private val claves = ClavesEnMemoria()
    private val almacenClaveDatos = AlmacenClaveDatosEnMemoria()
    private val clavesDatos = ClavesDatos(claves, almacenClaveDatos)
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
                clavesDatos,
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
    fun `la migracion interrumpida conserva los datos y se reanuda completa`() {
        runBlocking {
            repositorio.importar(TEXTO_E2, "Fuente E2", UBICACION)
            conServidorConCuerpo(200, TEXTO_XTREAM) { url ->
                val resultado =
                    runCatching {
                        repositorio.importarDesdeUrl(
                            "Fuente Interrumpida",
                            url,
                            alFase = { fase ->
                                if (fase == FaseImportacion.GUARDANDO) {
                                    throw CancellationException("interrumpida antes de escribir")
                                }
                            },
                        )
                    }

                assertTrue("esperaba interrupción", resultado.exceptionOrNull() is CancellationException)
                // El catálogo previo (0x02) queda intacto y sigue legible.
                assertEquals(1, db.canalDao().contar())
                val fuente = requireNotNull(db.fuenteDao().porNombre("Fuente E2"))
                val canal = db.canalDao().porFuente(fuente.id).single()
                assertEquals(
                    "https://example.invalid/uno.m3u8",
                    descifrarUrlCanal(requireNotNull(canal.urlStreamCifrada)),
                )
            }
            // Reanudación: la reimportación completa la migración (servidor nuevo:
            // el de arriba cierra su única conexión, como cualquier servidor real).
            conServidorConCuerpo(200, TEXTO_XTREAM) { urlReanudacion ->
                val reanudada = repositorio.importarDesdeUrl("Fuente Interrumpida", urlReanudacion)
                assertNull(reanudada.error)
                assertEquals(2, db.canalDao().contar())
            }
        }
    }

    @Test
    fun `la migracion repetida conserva los datos legibles en 0x02`() {
        runBlocking {
            repositorio.importar(TEXTO_E2, "Fuente E2", UBICACION)
            repositorio.importar(TEXTO_E2, "Fuente E2", UBICACION)

            assertEquals(1, db.canalDao().contar())
            val fuente = requireNotNull(db.fuenteDao().porNombre("Fuente E2"))
            val canal = db.canalDao().porFuente(fuente.id).single()
            assertEquals(
                "el sobre persistido debe ser 0x02",
                CifradoAes.VERSION_DATOS,
                canal.urlStreamCifrada[0],
            )
            assertEquals("https://example.invalid/uno.m3u8", descifrarUrlCanal(requireNotNull(canal.urlStreamCifrada)))
        }
    }

    @Test
    fun `el informe no contiene material de claves`() {
        runBlocking {
            conServidorConCuerpo(200, TEXTO_XTREAM) { url ->
                val resumen = repositorio.importarDesdeUrl("Fuente Claves", url)

                assertNull(resumen.error)
                val d = checkNotNull(resumen.diagnostico)
                val informe = d.aInforme("completada")
                // La envuelta de la clave de datos (lo único que sale del
                // proceso) nunca aparece en el informe, ni completa ni en
                // fragmento identificable.
                val envueltaB64 = java.util.Base64.getEncoder().encodeToString(requireNotNull(almacenClaveDatos.leer()))
                assertFalse("el informe filtra la envuelta de la clave", informe.contains(envueltaB64))
                assertFalse(
                    "el informe filtra un fragmento de la envuelta",
                    informe.contains(envueltaB64.take(16)),
                )
            }
        }
    }

    @Test
    fun `la importacion masiva de 146762 canales persiste completa y legible`() {
        val total = 146_762
        val texto =
            buildString {
                append("#EXTM3U\n")
                repeat(total) { indice ->
                    append("#EXTINF:-1 tvg-id=\"masiva-$indice\" group-title=\"M ${indice % 50}\",Canal $indice\n")
                    append("https://ejemplo.invalid/masiva/$indice.ts\n")
                }
            }
        runBlocking {
            conServidorConCuerpo(200, texto) { url ->
                val resumen = repositorio.importarDesdeUrl("Fuente Masiva", url)

                assertNull(resumen.error)
                assertEquals(total, resumen.canales)
                val d = checkNotNull(resumen.diagnostico)
                // 1 AES por canal (URL sin logo) + 1 de la ubicación de la lista.
                assertEquals(total + 1, d.operacionesAes)
                assertEquals(total + 1, d.sobre0x02)
                assertEquals(0, d.sobre0x01)
                assertTrue("tiempo de cifrado no puede ser negativo", (d.tiempoCifradoMs ?: -1) >= 0)
                assertTrue("tiempo de inserción no puede ser negativo", (d.tiempoInsercionMs ?: -1) >= 0)
                assertNotNull("esperaba ops_keystore", d.operacionesKeystore)
                assertEquals(total, db.canalDao().contar())
                // Readback barato: el primer canal se descifra por despacho.
                db.query(SimpleSQLiteQuery("SELECT urlStreamCifrada FROM Canal LIMIT 1")).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    val sobre = requireNotNull(cursor.getBlob(0))
                    assertEquals(CifradoAes.VERSION_DATOS, sobre[0])
                    assertTrue(descifrarUrlCanal(sobre).startsWith("https://ejemplo.invalid/masiva/"))
                }
            }
        }
    }

    /** Descifra la URL de un canal por el despacho de versión (0x01/0x02). */
    private fun descifrarUrlCanal(sobre: ByteArray): String {
        val resultado =
            CifradoAes.descifrarSobre(
                claves.claveAes,
                clavesDatos.clave(),
                CifradoAes.aad("Canal", "url_stream_cifrada"),
                sobre,
            )
        assertTrue("esperaba descifrado ok", resultado is ResultadoDescifrado.Ok)
        return String((resultado as ResultadoDescifrado.Ok).bytes, Charsets.UTF_8)
    }

    /** Servidor HTTP de un solo uso que responde [codigo] con [cuerpo]. */
    private suspend fun conServidorConCuerpo(
        codigo: Int,
        cuerpo: String,
        bloque: suspend (String) -> Unit,
    ) {
        val servidor = ServerSocket(0)
        coroutineScope {
            val respuesta =
                async(Dispatchers.IO) {
                    servidor.use {
                        it.accept().use { socket -> responderConCuerpo(socket, codigo, cuerpo) }
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
        codigo: Int,
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
            "HTTP/1.1 $codigo Prueba\r\n" +
                "Content-Type: audio/x-mpegurl; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n\r\n"
        socket.getOutputStream().apply {
            write(cabecera.toByteArray(Charsets.ISO_8859_1))
            write(bytes)
            flush()
        }
    }

    private companion object {
        const val UBICACION = "https://example.invalid/e2.m3u"
        const val TIEMPO_ESPERA_MS = 5_000L

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
            #EXTINF:-1 tvg-id="e2-1" tvg-name="E2 Uno" group-title="Noticias" custom="x",E2 Uno
            https://example.invalid/uno.m3u8
            """.trimIndent()
    }
}
