package com.copernicotv.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.copernicotv.app.ClavesEnMemoria
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.app.data.db.entities.CanalEntity
import com.copernicotv.app.data.db.entities.FuenteEntity
import com.copernicotv.app.data.db.entities.FuenteGuiaEntity
import com.copernicotv.core.epg.EstadoAsociacion
import com.copernicotv.core.epg.LimitesRetencion
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
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
import java.io.ByteArrayInputStream
import java.time.Instant

/**
 * E5-T1/T2/T3/T4 sobre Room real: staging, publicación atómica, retención y
 * reconciliación de Asociaciones.
 *
 * El parser y las reglas de estabilidad ya tienen pruebas puras en `:core`.
 * Esta prueba comprueba el contrato de persistencia: qué queda publicado y qué
 * se descarta cuando el parseo, la retención o la publicación fallan.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EpgRepositorioTest {
    private lateinit var db: AppDatabase
    private lateinit var repositorio: EpgRepositorio
    private val claves = ClavesEnMemoria()
    private lateinit var preferencias: FakePreferenciasRetencion
    private val contexto: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun abrir() {
        db =
            Room
                .inMemoryDatabaseBuilder(contexto, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        preferencias = FakePreferenciasRetencion()
        repositorio = EpgRepositorio(db, IndiceHmacRepositorio(db, claves), preferencias)
        repositorio.reloj = { AHORA }
    }

    @After
    fun cerrar() {
        db.close()
    }

    @Test
    fun `publica la generacion nueva y elimina la anterior`() =
        runBlocking {
            val fuenteGuiaId = sembrarFuenteGuia()
            val primera = importar(fuenteGuiaId, xml(canal("c1", "Canal Uno"), programa("c1", "Uno")))
            assertEquals(EpgRepositorio.RESULTADO_EXITOSA, primera.resultado)
            assertEquals(1, primera.programas)
            val primeraGeneracion = assertNotNullId(primera.generacionId)

            val segunda = importar(fuenteGuiaId, xml(canal("c1", "Canal Uno"), programa("c1", "Dos")))
            val segundaGeneracion = assertNotNullId(segunda.generacionId)
            assertNotEquals(primeraGeneracion, segundaGeneracion)

            val fuente = db.fuenteGuiaDao().porId(fuenteGuiaId)
            assertEquals(segundaGeneracion, fuente?.generacionPublicadaId)
            assertNull(db.generacionGuiaDao().porId(primeraGeneracion))
            assertEquals(1, db.programaDao().contar(segundaGeneracion))
            assertTrue(
                db.generacionGuiaDao().porFuente(fuenteGuiaId).none {
                    it.estado == EpgRepositorio.ESTADO_STAGING
                },
            )
        }

    @Test
    fun `un XML invalido conserva la guia publicada y elimina el staging`() =
        runBlocking {
            val fuenteGuiaId = sembrarFuenteGuia()
            val exitosa = importar(fuenteGuiaId, xml(canal("c1", "Canal Uno"), programa("c1", "Uno")))
            val generacion = assertNotNullId(exitosa.generacionId)

            val fallida = importar(fuenteGuiaId, "<tv><channel id=\"c1\">")

            assertEquals(EpgRepositorio.RESULTADO_FALLIDA, fallida.resultado)
            assertEquals("eof_abrupto", fallida.errorGlobal?.codigo)
            assertEquals(generacion, db.fuenteGuiaDao().porId(fuenteGuiaId)?.generacionPublicadaId)
            assertEquals(1, db.programaDao().contar(generacion))
            assertEquals(1, db.generacionGuiaDao().porFuente(fuenteGuiaId).size)
            val estado =
                db.estadoActualizacionDao().ultimoDeVersion(
                    "xmltv:$fuenteGuiaId:${AHORA.toEpochMilli()}",
                )
            assertEquals("fallida:eof_abrupto", estado?.resultado)
        }

    @Test
    fun `una cancelacion descarta el staging y no toca la generacion publicada`() =
        runBlocking {
            val fuenteGuiaId = sembrarFuenteGuia()
            val exitosa = importar(fuenteGuiaId, xml(canal("c1", "Canal Uno"), programa("c1", "Uno")))
            val generacion = assertNotNullId(exitosa.generacionId)

            val cancelada =
                repositorio.importar(
                    fuenteGuiaId = fuenteGuiaId,
                    entrada = ByteArrayInputStream(xml(canal("c1", "Canal Uno"), programa("c1", "Dos")).toByteArray()),
                    cancelacion = { true },
                )

            assertEquals(EpgRepositorio.RESULTADO_FALLIDA, cancelada.resultado)
            assertEquals("cancelado", cancelada.detalle)
            assertEquals(generacion, db.fuenteGuiaDao().porId(fuenteGuiaId)?.generacionPublicadaId)
            assertEquals(1, db.programaDao().contar(generacion))
            assertEquals(1, db.generacionGuiaDao().porFuente(fuenteGuiaId).size)
        }

    @Test
    fun `la retencion recorta por canal sin tocar la ventana`() =
        runBlocking {
            val fuenteGuiaId =
                sembrarFuenteGuia(
                    usarGlobal = false,
                    maxProgramas = 2,
                )
            val programas =
                (1..3).joinToString(separator = "") { indice ->
                    programa("c1", "Programa $indice", hora = indice)
                }

            val resumen = importar(fuenteGuiaId, xml(canal("c1", "Canal Uno"), programas))
            val generacion = assertNotNullId(resumen.generacionId)

            assertEquals(3, resumen.programas)
            assertEquals(2, db.programaDao().contarCanal(generacion, "c1"))
        }

    @Test
    fun `el global de DataStore es el default y la excepcion por fuente manda`() =
        runBlocking {
            preferencias.guardar(LimitesRetencion(maxProgramasPorCanal = 1))

            val global = sembrarFuenteGuia(usarGlobal = true)
            val conGlobal =
                importar(
                    global,
                    xml(
                        canal("c1", "Canal Uno"),
                        programa("c1", "Uno", hora = 1) + programa("c1", "Dos", hora = 2),
                    ),
                )
            assertEquals(1, db.programaDao().contarCanal(assertNotNullId(conGlobal.generacionId), "c1"))

            val excepcion = sembrarFuenteGuia(usarGlobal = false, maxProgramas = 2)
            val conExcepcion =
                importar(
                    excepcion,
                    xml(
                        canal("c1", "Canal Uno"),
                        programa("c1", "Uno", hora = 1) +
                            programa("c1", "Dos", hora = 2) +
                            programa("c1", "Tres", hora = 3),
                    ),
                )
            assertEquals(2, db.programaDao().contarCanal(assertNotNullId(conExcepcion.generacionId), "c1"))

            preferencias.guardar(LimitesRetencion(maxProgramasPorCanal = 3))
            val trasCambiarGlobal =
                importar(
                    excepcion,
                    xml(
                        canal("c1", "Canal Uno"),
                        programa("c1", "Uno", hora = 1) +
                            programa("c1", "Dos", hora = 2) +
                            programa("c1", "Tres", hora = 3),
                    ),
                )
            assertEquals(
                2,
                db.programaDao().contarCanal(assertNotNullId(trasCambiarGlobal.generacionId), "c1"),
            )
        }

    @Test
    fun `un fallo de publicacion hace rollback y conserva la generacion anterior`() =
        runBlocking {
            val fuenteGuiaId = sembrarFuenteGuia()
            val exitosa = importar(fuenteGuiaId, xml(canal("c1", "Canal Uno"), programa("c1", "Uno")))
            val generacion = assertNotNullId(exitosa.generacionId)

            repositorio.puntoDeFallo = { error("fallo inyectado") }
            val fallida =
                importar(
                    fuenteGuiaId,
                    xml(canal("c1", "Canal Uno"), programa("c1", "Dos")),
                )
            repositorio.puntoDeFallo = null

            assertEquals(EpgRepositorio.RESULTADO_FALLIDA, fallida.resultado)
            assertEquals("fallo inyectado", fallida.detalle)
            assertEquals(generacion, db.fuenteGuiaDao().porId(fuenteGuiaId)?.generacionPublicadaId)
            assertEquals(1, db.programaDao().contar(generacion))
            assertEquals(1, db.generacionGuiaDao().porFuente(fuenteGuiaId).size)
        }

    @Test
    fun `una importacion propone la asociacion por tvgid y la confirmacion la publica`() =
        runBlocking {
            val fuenteGuiaId = sembrarFuenteGuia()
            val canalId = sembrarCanal(tvgId = "c1")

            importar(fuenteGuiaId, xml(canal("c1", "Canal Uno"), programa("c1", "Uno")))

            val propuesta = db.asociacionDao().porFuente(fuenteGuiaId).single()
            assertEquals(EstadoAsociacion.PROPUESTA.valor, propuesta.estado)
            assertEquals(canalId, propuesta.canalId)

            val asociaciones = AsociacionRepositorio(db)
            asociaciones.reloj = { AHORA }
            val confirmada = asociaciones.confirmar(propuesta.id)
            assertEquals(EstadoAsociacion.CONFIRMADA, confirmada?.estado)

            val guia = GuiaRepositorio(db)
            val fila = guia.actualYSiguiente(canalId, fuenteGuiaId, AHORA)
            assertNotNull(fila)
            assertEquals("Uno", fila?.actual?.titulo)
            assertNull(fila?.siguiente)
            assertEquals(
                1,
                guia.programas(
                    canalId = canalId,
                    desde = AHORA.minusSeconds(60),
                    hasta = AHORA.plusSeconds(7200),
                    fuenteGuiaId = fuenteGuiaId,
                ).size,
            )

            val otroCanal = sembrarCanal(tvgId = "c2", nombre = "Canal Dos")
            val editada = asociaciones.editar(propuesta.id, otroCanal)
            assertEquals(EstadoAsociacion.CONFIRMADA, editada?.estado)
            assertEquals(otroCanal, editada?.canalId)
            val revocada = asociaciones.revocar(propuesta.id)
            assertEquals(EstadoAsociacion.REVOCADA, revocada?.estado)
        }

    private suspend fun importar(
        fuenteGuiaId: Long,
        texto: String,
    ): ResumenImportacionEpg =
        repositorio.importar(
            fuenteGuiaId = fuenteGuiaId,
            entrada = ByteArrayInputStream(texto.toByteArray()),
        )

    private suspend fun sembrarFuenteGuia(
        usarGlobal: Boolean = true,
        maxProgramas: Long? = null,
    ): Long =
        db.fuenteGuiaDao().insertar(
            FuenteGuiaEntity(
                nombre = "Guía",
                usarRetencionGlobal = usarGlobal,
                retencionMaxProgramas = maxProgramas,
                creadoEn = AHORA,
                actualizadoEn = AHORA,
            ),
        )

    private suspend fun sembrarCanal(
        tvgId: String,
        nombre: String = "Canal Uno",
    ): Long {
        val fuenteId =
            db.fuenteDao().insertar(
                FuenteEntity(
                    nombre = "Fuente $tvgId",
                    estado = "activa",
                    usarIntervaloGlobal = true,
                    creadoEn = AHORA,
                    actualizadoEn = AHORA,
                ),
            )
        return db.canalDao().insertar(
            CanalEntity(
                fuenteId = fuenteId,
                nombre = nombre,
                urlStreamCifrada = byteArrayOf(1),
                urlStreamHmac = byteArrayOf(2),
                tvgId = tvgId,
                claveDerivable = false,
                creadoEn = AHORA,
                actualizadoEn = AHORA,
            ),
        )
    }

    private fun assertNotNullId(id: Long?): Long {
        assertNotNull(id)
        return requireNotNull(id)
    }

    private fun xml(
        canal: String,
        programas: String,
    ): String =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <tv>
          $canal
          $programas
        </tv>
        """.trimIndent()

    private fun canal(
        id: String,
        nombre: String,
    ): String = "<channel id=\"$id\"><display-name>$nombre</display-name></channel>"

    private fun programa(
        canalId: String,
        titulo: String,
        hora: Int = 0,
    ): String {
        val inicio = AHORA.plusSeconds(hora * 3600L)
        val fin = inicio.plusSeconds(3600)
        return "<programme channel=\"$canalId\" start=\"${texto(inicio)}\" stop=\"${texto(fin)}\">" +
            "<title>$titulo</title></programme>"
    }

    private fun texto(instante: Instant): String =
        instante.toString().replace("-", "").replace(":", "").replace("T", "").replace("Z", " +0000")

    private companion object {
        val AHORA: Instant = Instant.parse("2026-01-01T12:00:00Z")
    }
}

private class FakePreferenciasRetencion(
    inicial: LimitesRetencion = LimitesRetencion.GLOBAL,
) : PreferenciasRetencion {
    private val estado = MutableStateFlow(inicial)

    override fun limites(): Flow<LimitesRetencion> = estado

    override suspend fun actuales(): LimitesRetencion = estado.value

    override suspend fun guardar(limites: LimitesRetencion) {
        estado.value = limites
    }

    override suspend fun restablecer() {
        estado.value = LimitesRetencion.GLOBAL
    }
}
