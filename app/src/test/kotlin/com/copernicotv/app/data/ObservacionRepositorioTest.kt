package com.copernicotv.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.copernicotv.app.ClavesEnMemoria
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.app.data.db.entities.FavoritoEntity
import com.copernicotv.app.data.db.entities.FuenteEntity
import com.copernicotv.app.data.db.entities.HistorialEntity
import com.copernicotv.app.data.db.entities.ListaEntity
import com.copernicotv.core.crypto.CifradoAes
import com.copernicotv.core.crypto.ResultadoDescifrado
import com.copernicotv.core.identity.EstadoCanal
import com.copernicotv.core.m3u.ImportadorM3U
import kotlinx.coroutines.runBlocking
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
import java.time.Instant

/**
 * E4-T3: ciclo de vida de las Observaciones pendientes de importación.
 *
 * Cubre m6 (una declaración genuinamente nueva que colisiona con un conjunto
 * B/C no crea Canal ni se asigna a una fila) y m7 (una declaración histórica
 * reaparecida sin evidencia suficiente queda como Observación y no cambia el
 * estado del conjunto). Las tres resoluciones son explícitas: asignar como
 * evidencia, promover a Canal nuevo o descartar.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ObservacionRepositorioTest {
    private lateinit var db: AppDatabase
    private lateinit var actualizacion: ActualizacionRepositorio
    private lateinit var resolucion: ObservacionRepositorio
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
        actualizacion = ActualizacionRepositorio(db, claves, contexto)
        resolucion = ObservacionRepositorio(db)
        runBlocking { sembrarFuenteYLista() }
    }

    @After
    fun cerrar() {
        runCatching { db.close() }
        contexto.deleteDatabase(NOMBRE_BD)
    }

    @Test
    fun `m6 una declaracion nueva que colisiona con B queda como observacion`() {
        runBlocking {
            actualizar(
                """
                #EXTM3U
                #EXTINF:-1 tvg-id="uno" group-title="G",Uno
                https://example.com/uno
                """.trimIndent(),
            )
            val original = db.canalDao().porFuente(fuenteId).single()
            db.canalDao().actualizar(original.copy(estado = EstadoCanal.B, claveUnicidad = null))

            val nueva =
                ImportadorM3U.importar(
                    """
                    #EXTM3U
                    #EXTINF:-1 tvg-id="nuevo" group-title="G",Uno
                    https://example.com/uno
                    """.trimIndent(),
                    claves.claveHmac,
                )
            val resumen = actualizacion.actualizarConImportado(listaId, nueva)

            assertEquals(0, resumen.nuevas)
            assertEquals(1, resumen.observaciones)
            assertEquals(1, db.canalDao().contarPorFuente(fuenteId))
            val enB = requireNotNull(db.canalDao().porId(original.id))
            assertEquals(EstadoCanal.B, enB.estado)
            assertNull(enB.claveUnicidad)
            val observacion = db.observacionPendienteDao().porFuente(fuenteId).single()
            assertEquals("nuevo", observacion.tvgId)
            assertEquals(nueva.canales.single().claveCanonica, observacion.claveCanonica)

            val resultado = resolucion.promoverACanalNuevo(observacion.id, listaId)
            assertTrue(resultado is ResultadoResolucionObservacion.CanalPromovido)
            val promovido =
                requireNotNull(
                    db.canalDao().porId(
                        (resultado as ResultadoResolucionObservacion.CanalPromovido).canalId,
                    ),
                )
            assertEquals(EstadoCanal.A, promovido.estado)
            assertEquals("tvg:v1:nuevo", promovido.claveUnicidad)
            assertEquals("Uno", promovido.nombre)
            assertEquals("https://example.com/uno", descifrar(promovido.urlStreamCifrada))
            assertEquals(1, db.aparienciaDao().porCanalEnLista(listaId, promovido.id).size)
            assertEquals(0, db.observacionPendienteDao().contarPorFuente(fuenteId))
            assertEquals(2, db.canalDao().contarPorFuente(fuenteId))
            assertEquals(EstadoCanal.B, requireNotNull(db.canalDao().porId(original.id)).estado)
        }
    }

    @Test
    fun `m7 una declaracion reaparecida sin evidencia suficiente queda como observacion`() {
        runBlocking {
            actualizar(
                """
                #EXTM3U
                #EXTINF:-1 tvg-id="uno" group-title="G",Uno
                https://example.com/uno
                """.trimIndent(),
            )
            val original = db.canalDao().porFuente(fuenteId).single()
            val ahora = Instant.parse("2026-02-01T00:00:00Z")
            db.favoritoDao().insertar(FavoritoEntity(canalId = original.id, creadoEn = ahora))
            db.historialDao().insertar(HistorialEntity(canalId = original.id, fecha = ahora))

            val reaparecida =
                ImportadorM3U.importar(
                    """
                    #EXTM3U
                    #EXTINF:-1 group-title="G",Uno
                    https://example.com/uno?x=1
                    """.trimIndent(),
                    claves.claveHmac,
                )
            val resumen = actualizacion.actualizarConImportado(listaId, reaparecida)

            assertEquals(0, resumen.nuevas)
            assertEquals(1, resumen.observaciones)
            assertEquals(1, db.canalDao().contarPorFuente(fuenteId))
            val sinCambios = requireNotNull(db.canalDao().porId(original.id))
            assertEquals(EstadoCanal.A, sinCambios.estado)
            assertEquals("tvg:v1:uno", sinCambios.claveUnicidad)
            assertEquals(original.urlStreamCifrada.toList(), sinCambios.urlStreamCifrada.toList())
            assertNotNull(db.favoritoDao().porCanal(original.id))
            assertNotNull(db.historialDao().porCanal(original.id))

            val observacion = db.observacionPendienteDao().porFuente(fuenteId).single()
            assertEquals(ResultadoResolucionObservacion.Asignada, resolucion.asignarComoEvidencia(observacion.id, original.id))

            val resuelto = requireNotNull(db.canalDao().porId(original.id))
            assertEquals(EstadoCanal.A, resuelto.estado)
            assertEquals(reaparecida.canales.single().claveCanonica, resuelto.claveUnicidad)
            assertEquals("Uno", resuelto.nombre)
            assertEquals("https://example.com/uno?x=1", descifrar(resuelto.urlStreamCifrada))
            assertEquals(ObservacionRepositorio.DECISION_ASIGNADA, resuelto.decision)
            assertTrue(requireNotNull(resuelto.evidencia).startsWith("observacion:v1;id="))
            assertNotNull(db.favoritoDao().porCanal(original.id))
            assertNotNull(db.historialDao().porCanal(original.id))
            assertEquals(0, db.observacionPendienteDao().contarPorFuente(fuenteId))
            assertEquals(1, db.canalDao().contarPorFuente(fuenteId))
        }
    }

    @Test
    fun `descartar elimina la observacion sin tocar el canal`() {
        runBlocking {
            val observacionId = crearObservacionConB()
            val resultado = resolucion.descartar(observacionId)
            assertEquals(ResultadoResolucionObservacion.Descartada, resultado)
            assertEquals(0, db.observacionPendienteDao().contarPorFuente(fuenteId))
            assertEquals(1, db.canalDao().contarPorFuente(fuenteId))
        }
    }

    @Test
    fun `asignar una observacion inexistente no modifica nada`() {
        runBlocking {
            val resultado = resolucion.asignarComoEvidencia(9999L, 1L)
            assertEquals(ResultadoResolucionObservacion.Rechazada("observacion_no_existe"), resultado)
            assertEquals(0, db.observacionPendienteDao().contar())
        }
    }

    private suspend fun crearObservacionConB(): Long {
        actualizar(
            """
            #EXTM3U
            #EXTINF:-1 tvg-id="uno" group-title="G",Uno
            https://example.com/uno
            """.trimIndent(),
        )
        val original = db.canalDao().porFuente(fuenteId).single()
        db.canalDao().actualizar(original.copy(estado = EstadoCanal.B, claveUnicidad = null))
        actualizar(
            """
            #EXTM3U
            #EXTINF:-1 tvg-id="nuevo" group-title="G",Uno
            https://example.com/uno
            """.trimIndent(),
        )
        return db.observacionPendienteDao().porFuente(fuenteId).single().id
    }

    private suspend fun actualizar(texto: String): ResumenActualizacion {
        val importado = ImportadorM3U.importar(texto, claves.claveHmac)
        return actualizacion.actualizarConImportado(listaId, importado)
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
        const val NOMBRE_BD = "observacion-repositorio.db"
    }
}
