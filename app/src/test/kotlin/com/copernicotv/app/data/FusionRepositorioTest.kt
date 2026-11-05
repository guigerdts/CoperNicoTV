package com.copernicotv.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.app.data.db.entities.AparienciaEntity
import com.copernicotv.app.data.db.entities.AsociacionEntity
import com.copernicotv.app.data.db.entities.CanalEntity
import com.copernicotv.app.data.db.entities.ColeccionCanalEntity
import com.copernicotv.app.data.db.entities.ColeccionEntity
import com.copernicotv.app.data.db.entities.FavoritoEntity
import com.copernicotv.app.data.db.entities.FuenteEntity
import com.copernicotv.app.data.db.entities.FuenteGuiaEntity
import com.copernicotv.app.data.db.entities.HistorialEntity
import com.copernicotv.app.data.db.entities.ListaEntity
import com.copernicotv.app.data.db.entities.OcultacionCanalEntity
import com.copernicotv.core.identity.EstadoCanal
import kotlinx.coroutines.runBlocking
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
import java.time.Instant

/**
 * E4-T4/T5: fusión confirmada de dos Canales ambiguos (ADR-0004:60).
 *
 * Cubre F58v (la PK retirada no se reutiliza), m2 (rollback tras trasladar
 * relaciones), m5 (posición de Colección en conflicto y pertenencia duplicada),
 * la consolidación de Historial con `max(fecha)`, los conflictos de Ocultación
 * y Asociación EPG, la preservación de Apariencias y el aborto por clave final
 * en conflicto.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FusionRepositorioTest {
    private lateinit var db: AppDatabase
    private lateinit var fusion: FusionRepositorio
    private val contexto: Context = ApplicationProvider.getApplicationContext()
    private var fuenteId: Long = 0L
    private var listaId: Long = 0L
    private var fuenteGuiaId: Long = 0L

    @Before
    fun abrir() {
        contexto.deleteDatabase(NOMBRE_BD)
        db =
            Room
                .databaseBuilder(contexto, AppDatabase::class.java, NOMBRE_BD)
                .allowMainThreadQueries()
                .build()
        fusion = FusionRepositorio(db)
        runBlocking { sembrar() }
    }

    @After
    fun cerrar() {
        runCatching { db.close() }
        contexto.deleteDatabase(NOMBRE_BD)
    }

    @Test
    fun `f58v la pk de la perdedora no se reutiliza`() {
        runBlocking {
            val superviviente = crearCanal("Uno", "canon:v2:uno")
            val perdedora = crearCanal("Uno bis", "canon:v2:uno")

            val plan = planear(superviviente, perdedora)
            assertTrue(fusion.confirmar(plan) is ResultadoConfirmacionFusion.Confirmada)

            assertNull(db.canalDao().porId(perdedora))
            val consolidado = requireNotNull(db.canalDao().porId(superviviente))
            assertEquals(EstadoCanal.A, consolidado.estado)
            assertEquals("canon:v2:uno", consolidado.claveUnicidad)
            assertEquals(1, db.canalDao().contar())

            db.close()
            db =
                Room
                    .databaseBuilder(contexto, AppDatabase::class.java, NOMBRE_BD)
                    .allowMainThreadQueries()
                    .build()
            fusion = FusionRepositorio(db)
            val nuevo = crearCanal("Nuevo", "canon:v2:nuevo", estado = EstadoCanal.A, claveUnicidad = "canon:v2:nuevo")
            assertTrue(nuevo != perdedora)
        }
    }

    @Test
    fun `m2 un fallo tras trasladar relaciones revierte toda la fusion`() {
        runBlocking {
            val superviviente = crearCanal("Uno", "canon:v2:uno")
            val perdedora = crearCanal("Uno bis", "canon:v2:uno")
            val coleccionId = crearColeccion()
            db.favoritoDao().insertar(FavoritoEntity(canalId = perdedora, creadoEn = AHORA))
            db.historialDao().insertar(HistorialEntity(canalId = perdedora, fecha = AHORA))
            db.coleccionCanalDao().insertar(
                ColeccionCanalEntity(coleccionId = coleccionId, canalId = perdedora, posicion = 0),
            )
            db.aparienciaDao().insertar(apariencia(perdedora, "g1"))

            val plan = planear(superviviente, perdedora)
            fusion.puntoDeFallo = { etapa ->
                if (etapa == FusionRepositorio.EtapaFusion.TRAS_TRASLADAR_RELACIONES) error("fallo simulado")
            }
            val fallo = runCatching { fusion.confirmar(plan) }
            assertTrue(fallo.isFailure)

            assertNotNull(db.canalDao().porId(perdedora))
            assertEquals(2, db.canalDao().contar())
            assertEquals(perdedora, db.favoritoDao().porCanal(perdedora)?.canalId)
            assertNull(db.favoritoDao().porCanal(superviviente))
            assertEquals(perdedora, db.historialDao().porCanal(perdedora)?.canalId)
            assertEquals(1, db.coleccionCanalDao().porCanal(coleccionId, perdedora).size)
            assertEquals(1, db.aparienciaDao().porCanal(perdedora).size)
            val ambiguo = requireNotNull(db.canalDao().porId(superviviente))
            assertEquals(EstadoCanal.B, ambiguo.estado)
            assertNull(ambiguo.claveUnicidad)
        }
    }

    @Test
    fun `m5 posiciones distintas se presentan y la elegida se aplica`() {
        runBlocking {
            val superviviente = crearCanal("Uno", "canon:v2:uno")
            val perdedora = crearCanal("Uno bis", "canon:v2:uno")
            val tercero = crearCanal("Tres", "canon:v2:tres")
            val coleccionId = crearColeccion()
            db.coleccionCanalDao().insertar(
                ColeccionCanalEntity(coleccionId = coleccionId, canalId = superviviente, posicion = 0),
            )
            db.coleccionCanalDao().insertar(
                ColeccionCanalEntity(coleccionId = coleccionId, canalId = tercero, posicion = 1),
            )
            db.coleccionCanalDao().insertar(
                ColeccionCanalEntity(coleccionId = coleccionId, canalId = perdedora, posicion = 2),
            )

            val plan = planear(superviviente, perdedora)
            val conflicto = plan.colecciones.single()
            assertEquals(coleccionId, conflicto.coleccionId)
            assertEquals(0, conflicto.posicionSuperviviente)
            assertEquals(2, conflicto.posicionPerdedora)

            val resultado =
                fusion.confirmar(
                    plan,
                    DecisionesFusion(
                        colecciones = mapOf(coleccionId to DecisionPosicionColeccion.CONSERVAR_PERDEDORA),
                    ),
                )
            assertTrue(resultado is ResultadoConfirmacionFusion.Confirmada)

            val filas = db.coleccionCanalDao().porColeccion(coleccionId)
            assertEquals(listOf(tercero, superviviente), filas.map { it.canalId })
            assertEquals(listOf(0, 1), filas.map { it.posicion })
        }
    }

    @Test
    fun `m5 una pertenencia duplicada identica se consolida`() {
        runBlocking {
            val superviviente = crearCanal("Uno", "canon:v2:uno")
            val perdedora = crearCanal("Uno bis", "canon:v2:uno")
            val coleccionId = crearColeccion()
            db.coleccionCanalDao().insertar(
                ColeccionCanalEntity(coleccionId = coleccionId, canalId = superviviente, posicion = 0),
            )
            db.coleccionCanalDao().insertar(
                ColeccionCanalEntity(coleccionId = coleccionId, canalId = perdedora, posicion = 0),
            )

            val plan = planear(superviviente, perdedora)
            assertTrue(plan.colecciones.isEmpty())
            assertEquals(listOf(coleccionId), plan.coleccionesDuplicadas)

            assertTrue(fusion.confirmar(plan) is ResultadoConfirmacionFusion.Confirmada)

            val filas = db.coleccionCanalDao().porColeccion(coleccionId)
            assertEquals(1, filas.size)
            assertEquals(superviviente, filas.single().canalId)
            assertEquals(0, filas.single().posicion)
        }
    }

    @Test
    fun `m5 tras consolidar la pertenencia el alta del superviviente no duplica`() {
        runBlocking {
            val superviviente = crearCanal("Uno", "canon:v2:uno")
            val perdedora = crearCanal("Uno bis", "canon:v2:uno")
            val tercero = crearCanal("Tres", "canon:v2:tres")
            val coleccionId = crearColeccion()
            db.coleccionCanalDao().insertar(
                ColeccionCanalEntity(coleccionId = coleccionId, canalId = superviviente, posicion = 0),
            )
            db.coleccionCanalDao().insertar(
                ColeccionCanalEntity(coleccionId = coleccionId, canalId = tercero, posicion = 1),
            )
            db.coleccionCanalDao().insertar(
                ColeccionCanalEntity(coleccionId = coleccionId, canalId = perdedora, posicion = 2),
            )

            val plan = planear(superviviente, perdedora)
            val resultado =
                fusion.confirmar(
                    plan,
                    DecisionesFusion(
                        colecciones = mapOf(coleccionId to DecisionPosicionColeccion.CONSERVAR_SUPERVIVIENTE),
                    ),
                )
            assertTrue(resultado is ResultadoConfirmacionFusion.Confirmada)

            val repositorio = ColeccionRepositorio(db)
            assertEquals(ResultadoAccion.Ok, repositorio.agregarCanal(coleccionId, superviviente))
            assertEquals(ResultadoAccion.Ok, repositorio.agregarCanal(coleccionId, superviviente))

            val filas = db.coleccionCanalDao().porColeccion(coleccionId)
            assertEquals(listOf(superviviente, tercero), filas.map { it.canalId })
            assertEquals(listOf(0, 1), filas.map { it.posicion })
            assertEquals(1, db.coleccionCanalDao().porCanal(coleccionId, superviviente).size)
            assertTrue(db.coleccionCanalDao().porCanal(coleccionId, perdedora).isEmpty())
        }
    }

    @Test
    fun `historial conserva la fecha maxima y una sola fila`() {
        runBlocking {
            val superviviente = crearCanal("Uno", "canon:v2:uno")
            val perdedora = crearCanal("Uno bis", "canon:v2:uno")
            val temprano = Instant.parse("2026-01-01T00:00:00Z")
            val tarde = Instant.parse("2026-03-01T00:00:00Z")
            db.historialDao().insertar(HistorialEntity(canalId = superviviente, fecha = temprano))
            db.historialDao().insertar(HistorialEntity(canalId = perdedora, fecha = tarde))

            val plan = planear(superviviente, perdedora)
            assertEquals(HistorialFusion.Ambas(tarde, LadoFusion.PERDEDORA), plan.historial)

            assertTrue(fusion.confirmar(plan) is ResultadoConfirmacionFusion.Confirmada)

            assertEquals(tarde, db.historialDao().porCanal(superviviente)?.fecha)
            assertNull(db.historialDao().porCanal(perdedora))
            assertEquals(1, db.historialDao().contar())
        }
    }

    @Test
    fun `ocultacion oculta contra visible se presenta y decide`() {
        runBlocking {
            val superviviente = crearCanal("Uno", "canon:v2:uno")
            val perdedora = crearCanal("Uno bis", "canon:v2:uno")
            db.ocultacionCanalDao().insertar(OcultacionCanalEntity(canalId = perdedora, creadoEn = AHORA))

            val plan = planear(superviviente, perdedora)
            assertEquals(ConflictoOcultacion(LadoFusion.PERDEDORA), plan.ocultacion)

            val resultado =
                fusion.confirmar(
                    plan,
                    DecisionesFusion(ocultacion = DecisionOcultacion.CONSERVAR_OCULTA),
                )
            assertTrue(resultado is ResultadoConfirmacionFusion.Confirmada)
            assertNotNull(db.ocultacionCanalDao().porCanal(superviviente))
            assertNull(db.ocultacionCanalDao().porCanal(perdedora))
        }
    }

    @Test
    fun `ocultacion conservar visible descarta la marca`() {
        runBlocking {
            val superviviente = crearCanal("Uno", "canon:v2:uno")
            val perdedora = crearCanal("Uno bis", "canon:v2:uno")
            db.ocultacionCanalDao().insertar(OcultacionCanalEntity(canalId = perdedora, creadoEn = AHORA))

            val plan = planear(superviviente, perdedora)
            val resultado =
                fusion.confirmar(
                    plan,
                    DecisionesFusion(ocultacion = DecisionOcultacion.CONSERVAR_VISIBLE),
                )
            assertTrue(resultado is ResultadoConfirmacionFusion.Confirmada)
            assertNull(db.ocultacionCanalDao().porCanal(superviviente))
            assertEquals(0, db.ocultacionCanalDao().contar())
        }
    }

    @Test
    fun `asociacion con identificadores distintos se presenta y decide`() {
        runBlocking {
            val superviviente = crearCanal("Uno", "canon:v2:uno")
            val perdedora = crearCanal("Uno bis", "canon:v2:uno")
            db.asociacionDao().insertar(asociacion(superviviente, "A"))
            db.asociacionDao().insertar(asociacion(perdedora, "B"))

            val plan = planear(superviviente, perdedora)
            val conflicto = plan.asociaciones.single()
            assertEquals(fuenteGuiaId, conflicto.fuenteGuiaId)
            assertEquals(listOf("A"), conflicto.superviviente)
            assertEquals(listOf("B"), conflicto.perdedora)

            val resultado =
                fusion.confirmar(
                    plan,
                    DecisionesFusion(
                        asociaciones = mapOf(fuenteGuiaId to DecisionAsociacion.CONSERVAR_PERDEDORA),
                    ),
                )
            assertTrue(resultado is ResultadoConfirmacionFusion.Confirmada)

            val filas = db.asociacionDao().porCanal(superviviente)
            assertEquals(listOf("B"), filas.map { it.channelIdTexto })
            assertTrue(db.asociacionDao().porCanal(perdedora).isEmpty())
        }
    }

    @Test
    fun `clave final en conflicto aborta sin cambios`() {
        runBlocking {
            val superviviente = crearCanal("Uno", "canon:v2:uno")
            val perdedora = crearCanal("Uno bis", "canon:v2:uno")
            crearCanal("Ajena", "canon:v2:ajena", estado = EstadoCanal.A, claveUnicidad = "canon:v2:uno")
            db.favoritoDao().insertar(FavoritoEntity(canalId = perdedora, creadoEn = AHORA))

            val plan = planear(superviviente, perdedora)
            val resultado = fusion.confirmar(plan)
            assertEquals(
                ResultadoConfirmacionFusion.Rechazada(FusionRepositorio.CODIGO_CLAVE_EN_CONFLICTO),
                resultado,
            )

            val ambiguo = requireNotNull(db.canalDao().porId(superviviente))
            assertEquals(EstadoCanal.B, ambiguo.estado)
            assertNull(ambiguo.claveUnicidad)
            assertNotNull(db.canalDao().porId(perdedora))
            assertEquals(perdedora, db.favoritoDao().porCanal(perdedora)?.canalId)
            assertEquals(3, db.canalDao().contar())
        }
    }

    @Test
    fun `apariencias identicas se consolidan y las distintas se trasladan`() {
        runBlocking {
            val superviviente = crearCanal("Uno", "canon:v2:uno")
            val perdedora = crearCanal("Uno bis", "canon:v2:uno")
            db.aparienciaDao().insertar(apariencia(superviviente, "g1"))
            db.aparienciaDao().insertar(apariencia(perdedora, "g1"))
            db.aparienciaDao().insertar(apariencia(perdedora, "g2"))

            val plan = planear(superviviente, perdedora)
            assertEquals(1, plan.aparienciasDuplicadas)

            assertTrue(fusion.confirmar(plan) is ResultadoConfirmacionFusion.Confirmada)

            val filas = db.aparienciaDao().porCanal(superviviente)
            assertEquals(setOf("g1", "g2"), filas.map { it.clavePresentacion }.toSet())
            assertEquals(2, filas.size)
            assertTrue(db.aparienciaDao().porCanal(perdedora).isEmpty())
        }
    }

    @Test
    fun `plan rechaza filas no ambiguas y fuentes distintas`() {
        runBlocking {
            val ambiguo = crearCanal("Uno", "canon:v2:uno")
            val claro = crearCanal("Dos", "canon:v2:dos", estado = EstadoCanal.A, claveUnicidad = "canon:v2:dos")
            assertEquals(
                ResultadoPlanFusion.Rechazado(FusionRepositorio.CODIGO_FILA_NO_AMBIGUA),
                fusion.planear(claro, ambiguo),
            )
            assertEquals(
                ResultadoPlanFusion.Rechazado(FusionRepositorio.CODIGO_MISMA_FILA),
                fusion.planear(ambiguo, ambiguo),
            )

            val otraFuente =
                db.fuenteDao().insertar(
                    FuenteEntity(
                        nombre = "Otra",
                        estado = "activa",
                        usarIntervaloGlobal = true,
                        creadoEn = AHORA,
                        actualizadoEn = AHORA,
                    ),
                )
            val ajeno = crearCanal("Ajeno", "canon:v2:ajeno", fuente = otraFuente)
            assertEquals(
                ResultadoPlanFusion.Rechazado(FusionRepositorio.CODIGO_FUENTES_DISTINTAS),
                fusion.planear(ambiguo, ajeno),
            )
        }
    }

    @Test
    fun `confirmar rechaza un plan obsoleto sin escribir`() {
        runBlocking {
            val superviviente = crearCanal("Uno", "canon:v2:uno")
            val perdedora = crearCanal("Uno bis", "canon:v2:uno")
            db.favoritoDao().insertar(FavoritoEntity(canalId = perdedora, creadoEn = AHORA))
            val plan = planear(superviviente, perdedora)
            assertFalse(plan.favoritoDuplicado)

            db.favoritoDao().insertar(FavoritoEntity(canalId = superviviente, creadoEn = AHORA))

            assertEquals(
                ResultadoConfirmacionFusion.Rechazada(FusionRepositorio.CODIGO_PLAN_DESACTUALIZADO),
                fusion.confirmar(plan),
            )
            assertNotNull(db.canalDao().porId(perdedora))
            assertEquals(perdedora, db.favoritoDao().porCanal(perdedora)?.canalId)
            assertEquals(2, db.favoritoDao().contar())
        }
    }

    @Test
    fun `confirmar exige decision explicita para un conflicto con perdida`() {
        runBlocking {
            val superviviente = crearCanal("Uno", "canon:v2:uno")
            val perdedora = crearCanal("Uno bis", "canon:v2:uno")
            db.ocultacionCanalDao().insertar(OcultacionCanalEntity(canalId = perdedora, creadoEn = AHORA))

            val plan = planear(superviviente, perdedora)
            assertEquals(
                ResultadoConfirmacionFusion.Rechazada(FusionRepositorio.CODIGO_CONFLICTO_SIN_RESOLVER),
                fusion.confirmar(plan),
            )
            assertNotNull(db.canalDao().porId(perdedora))
            assertEquals(perdedora, db.ocultacionCanalDao().porCanal(perdedora)?.canalId)
            assertNull(db.ocultacionCanalDao().porCanal(superviviente))
        }
    }

    private suspend fun sembrar() {
        fuenteId =
            db.fuenteDao().insertar(
                FuenteEntity(
                    nombre = "Fuente",
                    estado = "activa",
                    usarIntervaloGlobal = true,
                    creadoEn = AHORA,
                    actualizadoEn = AHORA,
                ),
            )
        listaId =
            db.listaDao().insertar(
                ListaEntity(
                    fuenteId = fuenteId,
                    nombre = "Lista",
                    ubicacionCifrada = ByteArray(8),
                    ubicacionHmac = ByteArray(32),
                    esquema = "content",
                    orden = 0,
                    estado = "activa",
                    creadoEn = AHORA,
                    actualizadoEn = AHORA,
                ),
            )
        fuenteGuiaId =
            db.fuenteGuiaDao().insertar(
                FuenteGuiaEntity(
                    nombre = "Guia",
                    usarRetencionGlobal = true,
                    creadoEn = AHORA,
                    actualizadoEn = AHORA,
                ),
            )
    }

    private suspend fun crearCanal(
        nombre: String,
        canonica: String,
        estado: EstadoCanal = EstadoCanal.B,
        claveUnicidad: String? = null,
        fuente: Long = fuenteId,
    ): Long =
        db.canalDao().insertar(
            CanalEntity(
                fuenteId = fuente,
                nombre = nombre,
                urlStreamCifrada = byteArrayOf(1, 2, 3),
                urlStreamHmac = ByteArray(32),
                claveCanonica = canonica,
                claveUnicidad = claveUnicidad,
                claveDerivable = true,
                estado = estado,
                creadoEn = AHORA,
                actualizadoEn = AHORA,
            ),
        )

    private suspend fun crearColeccion(): Long =
        db.coleccionDao().insertar(
            ColeccionEntity(nombre = "Coleccion", creadoEn = AHORA, actualizadoEn = AHORA),
        )

    private suspend fun planear(
        supervivienteId: Long,
        perdedoraId: Long,
    ): PlanFusion = (fusion.planear(supervivienteId, perdedoraId) as ResultadoPlanFusion.Listo).plan

    private fun apariencia(
        canalId: Long,
        clave: String,
    ): AparienciaEntity =
        AparienciaEntity(
            listaId = listaId,
            canalId = canalId,
            orden = 0,
            clavePresentacion = clave,
            repeticiones = 1,
            creadoEn = AHORA,
            actualizadoEn = AHORA,
        )

    private fun asociacion(
        canalId: Long,
        texto: String,
    ): AsociacionEntity =
        AsociacionEntity(
            canalId = canalId,
            fuenteGuiaId = fuenteGuiaId,
            channelIdTexto = texto,
            estado = "confirmada",
            origen = "prueba",
            huellaVersion = 1,
            senalesFuertes = "[]",
            ultimaObservacion = AHORA,
            actualizadaEn = AHORA,
        )

    private companion object {
        const val NOMBRE_BD = "fusion-repositorio.db"
        val AHORA: Instant = Instant.parse("2026-02-01T00:00:00Z")
    }
}
