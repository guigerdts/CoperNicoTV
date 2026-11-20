package com.copernicotv.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.copernicotv.app.AlmacenClaveDatosEnMemoria
import com.copernicotv.app.ClavesEnMemoria
import com.copernicotv.app.crypto.ClavesDatos
import com.copernicotv.app.data.actualizacion.CausasActualizacion
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.app.data.db.entities.AparienciaEntity
import com.copernicotv.app.data.db.entities.CanalEntity
import com.copernicotv.app.data.db.entities.FuenteEntity
import com.copernicotv.app.data.db.entities.GrupoEntity
import com.copernicotv.app.data.db.entities.ListaEntity
import com.copernicotv.core.crypto.CifradoAes
import com.copernicotv.core.crypto.ResultadoDescifrado
import com.copernicotv.core.identity.ClavePresentacion
import com.copernicotv.core.identity.EstadoCanal
import com.copernicotv.core.m3u.ImportadorM3U
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * P0: actualización de un catálogo de 147 000 canales con el camino real de
 * `ActualizacionRepositorio.aplicar()`.
 *
 * El seed se hace por lotes directos a Room (no por `actualizar`) para que el
 * costo de la prueba sea la actualización y no la importación inicial. La
 * actualización quita 5 canales, modifica 2 y agrega 10: cubre ausencias,
 * modificaciones e inserciones sobre el catálogo completo, además de conservar
 * canales, Apariencias y Grupos.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ActualizacionGrandeTest {
    private lateinit var db: AppDatabase
    private lateinit var repositorio: ActualizacionRepositorio
    private lateinit var indice: IndiceHmacRepositorio
    private val claves = ClavesEnMemoria()
    private val clavesDatos = ClavesDatos(claves, AlmacenClaveDatosEnMemoria())
    private val contexto: Context = ApplicationProvider.getApplicationContext()
    private var fuenteId: Long = 0L
    private var listaId: Long = 0L

    @Before
    fun abrir() {
        db =
            Room
                .inMemoryDatabaseBuilder(contexto, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        indice = IndiceHmacRepositorio(db, claves)
        repositorio = ActualizacionRepositorio(db, claves, clavesDatos, indice, contexto)
        runBlocking {
            indice.inicializarLegitimamente()
            sembrarFuenteYLista()
        }
    }

    @After
    fun cerrar() {
        db.close()
    }

    @Test(timeout = 20 * 60 * 1000L)
    fun `147000 canales se actualizan completos y conservan identidad grupos e historial`() {
        runBlocking {
            println("P0-PROGRESO seed inicio")
            sembrarCatalogo(TOTAL)
            println("P0-PROGRESO seed fin")
            assertEquals(TOTAL, db.canalDao().contarPorFuente(fuenteId))
            assertEquals(TOTAL, db.aparienciaDao().contar(listaId))
            assertEquals(GRUPOS, db.grupoDao().contar(listaId))

            val texto = m3u(TOTAL, mutar = true)
            println("P0-PROGRESO m3u fin (${texto.length} chars)")
            val resumen = actualizar(texto)
            println("P0-PROGRESO aplicar fin")

            assertEquals("completada", resumen.resultado)
            assertEquals(NUEVAS, resumen.nuevas)
            assertEquals(BAJAS, resumen.ausentes)
            assertEquals(TOTAL - BAJAS, resumen.actualizadas)

            // Los ausentes no se borran: la fila y su Apariencia se conservan.
            assertEquals(TOTAL + NUEVAS, db.canalDao().contarPorFuente(fuenteId))
            assertEquals(TOTAL + NUEVAS, db.aparienciaDao().contar(listaId))
            assertEquals(GRUPOS, db.grupoDao().contar(listaId))
            assertEquals(BAJAS, db.detalleAusenciaDao().contar())

            for (i in 0 until BAJAS) {
                val canal = requireNotNull(db.canalDao().porTvgId(fuenteId, "g-$i"))
                assertEquals(EstadoCanal.A, canal.estado)
                assertEquals("tvg:v1:g-$i", canal.claveUnicidad)
                assertEquals("Canal $i", canal.nombre)
            }

            for (i in BAJAS until BAJAS + MODIFICADAS) {
                val canal = requireNotNull(db.canalDao().porTvgId(fuenteId, "g-$i"))
                assertEquals("Canal renovado $i", canal.nombre)
                assertEquals("https://example.com/renovado/$i.ts", descifrar(canal.urlStreamCifrada))
            }

            for (j in 0 until NUEVAS) {
                val canal = db.canalDao().porTvgId(fuenteId, "nuevo-$j")
                assertNotNull(canal)
                assertEquals("Nuevo $j", canal!!.nombre)
                assertEquals(EstadoCanal.A, canal.estado)
                assertEquals("https://example.com/nuevo/$j.ts", descifrar(canal.urlStreamCifrada))
            }
        }
    }

    private suspend fun sembrarCatalogo(total: Int) {
        val ahora = Instant.parse("2026-01-01T00:00:00Z")
        val grupoIds =
            db.grupoDao().insertarTodos(
                (0 until GRUPOS).map { g -> GrupoEntity(listaId = listaId, nombre = grupo(g)) },
            )
        for ((indiceLote, lote) in (0 until total).chunked(LOTE_SEED).withIndex()) {
            val canales = lote.map { i -> canalSeed(i, ahora) }
            val ids = db.canalDao().insertarTodos(canales)
            val apariencias =
                lote.mapIndexed { posicion, i ->
                    aparienciaSeed(
                        canalId = ids[posicion],
                        indice = i,
                        grupoId = grupoIds[i % GRUPOS],
                        ahora = ahora,
                    )
                }
            db.aparienciaDao().insertarTodas(apariencias)
            if ((indiceLote + 1) % 20 == 0) {
                println("P0-PROGRESO seed ${(indiceLote + 1) * LOTE_SEED}/$total")
            }
        }
    }

    private fun canalSeed(
        indice: Int,
        ahora: Instant,
    ): CanalEntity =
        CanalEntity(
            fuenteId = fuenteId,
            nombre = "Canal $indice",
            urlStreamCifrada = byteArrayOf(0x01),
            urlStreamHmac = "hmac:v1:seed:$indice".toByteArray(Charsets.UTF_8),
            tvgId = "g-$indice",
            claveCanonica = "canon:v2:" + indice.toString(16).padStart(64, '0'),
            claveUnicidad = "tvg:v1:g-$indice",
            claveDerivable = true,
            estado = EstadoCanal.A,
            creadoEn = ahora,
            actualizadoEn = ahora,
        )

    private fun aparienciaSeed(
        canalId: Long,
        indice: Int,
        grupoId: Long,
        ahora: Instant,
    ): AparienciaEntity =
        AparienciaEntity(
            listaId = listaId,
            canalId = canalId,
            grupoId = grupoId,
            orden = indice + 1,
            groupTitle = grupo(indice),
            tvgName = null,
            clavePresentacion = ClavePresentacion.clave(grupo(indice), null),
            repeticiones = 1,
            creadoEn = ahora,
            actualizadoEn = ahora,
        )

    private fun m3u(
        total: Int,
        mutar: Boolean,
    ): String =
        buildString(CAPACIDAD_TEXTO) {
            appendLine("#EXTM3U")
            for (i in 0 until total) {
                if (mutar && i < BAJAS) continue
                val renovado = mutar && i < BAJAS + MODIFICADAS
                val nombre = if (renovado) "Canal renovado $i" else "Canal $i"
                val url =
                    if (renovado) {
                        "https://example.com/renovado/$i.ts"
                    } else {
                        "https://example.com/canal/$i.ts"
                    }
                appendLine("#EXTINF:-1 tvg-id=\"g-$i\" group-title=\"${grupo(i)}\",$nombre")
                appendLine(url)
            }
            if (mutar) {
                for (j in 0 until NUEVAS) {
                    appendLine("#EXTINF:-1 tvg-id=\"nuevo-$j\" group-title=\"${grupo(j)}\",Nuevo $j")
                    appendLine("https://example.com/nuevo/$j.ts")
                }
            }
        }

    private fun grupo(indice: Int): String = "G ${indice % GRUPOS}"

    private suspend fun actualizar(texto: String): ResumenActualizacion {
        println("P0-PROGRESO importar inicio")
        val importado = ImportadorM3U.importar(texto, claves.crearClaveHmac(1))
        println("P0-PROGRESO importar fin (${importado.canales.size} canales)")
        return repositorio.actualizarConImportado(listaId, importado, CausasActualizacion.MANUAL)
    }

    private suspend fun sembrarFuenteYLista() {
        val ahora = Instant.parse("2026-01-01T00:00:00Z")
        fuenteId =
            db.fuenteDao().insertar(
                FuenteEntity(
                    nombre = "Fuente grande",
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
                    nombre = "Lista grande",
                    ubicacionCifrada =
                        CifradoAes.cifrar(
                            claves.claveAes,
                            CifradoAes.aad("Lista", "ubicacion_cifrada"),
                            "content://prueba/grande.m3u".toByteArray(),
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
            CifradoAes.descifrarSobre(
                claves.claveAes,
                clavesDatos.clave(),
                CifradoAes.aad("Canal", "url_stream_cifrada"),
                cifrado,
            )
        return (resultado as ResultadoDescifrado.Ok).bytes.toString(Charsets.UTF_8)
    }

    private companion object {
        const val TOTAL = 147_000
        const val GRUPOS = 50
        const val BAJAS = 5
        const val MODIFICADAS = 2
        const val NUEVAS = 10
        const val LOTE_SEED = 500
        const val CAPACIDAD_TEXTO = 1 shl 24
    }
}
