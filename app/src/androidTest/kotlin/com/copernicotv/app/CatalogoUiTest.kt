package com.copernicotv.app

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import com.copernicotv.app.data.PreferenciasOrden
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.app.data.db.entities.AparienciaEntity
import com.copernicotv.app.data.db.entities.CanalEntity
import com.copernicotv.app.data.db.entities.FuenteEntity
import com.copernicotv.app.data.db.entities.GrupoEntity
import com.copernicotv.app.data.db.entities.HistorialEntity
import com.copernicotv.app.data.db.entities.ListaEntity
import com.copernicotv.app.ui.PREFIJO_TAG_CANAL
import com.copernicotv.app.ui.PREFIJO_TAG_MODO_OPCION
import com.copernicotv.app.ui.PREFIJO_TAG_SECCION_GRUPO
import com.copernicotv.app.ui.TAG_ACCION_BAJAR
import com.copernicotv.app.ui.TAG_ACCION_CERRAR
import com.copernicotv.app.ui.TAG_ACCION_EDITAR
import com.copernicotv.app.ui.TAG_ACCION_ELIMINAR
import com.copernicotv.app.ui.TAG_ACCION_FAVORITO
import com.copernicotv.app.ui.TAG_ACCION_OCULTO
import com.copernicotv.app.ui.TAG_CONFIRMAR_ELIMINAR
import com.copernicotv.app.ui.TAG_DIALOGO_GUARDAR
import com.copernicotv.app.ui.TAG_DIALOGO_NOMBRE
import com.copernicotv.app.ui.TAG_DIALOGO_URL
import com.copernicotv.app.ui.TAG_DIRECCION
import com.copernicotv.app.ui.TAG_ELIMINAR_LISTA
import com.copernicotv.app.ui.TAG_FILTRO_FAVORITOS
import com.copernicotv.app.ui.TAG_FILTRO_OCULTOS
import com.copernicotv.app.ui.TAG_FILTRO_TODOS
import com.copernicotv.app.ui.TAG_NUEVA_LISTA
import com.copernicotv.app.ui.TAG_NUEVO_CANAL
import com.copernicotv.core.identity.ClavePresentacion
import com.copernicotv.core.orden.ModoOrden
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import javax.inject.Inject

/**
 * E3-T6/T8 en un emulador: CRUD de Lista y Canal, Favoritos, Ocultaciones,
 * reordenamiento manual y recorrido con D-pad.
 *
 * La prueba usa la UI real, el ViewModel y Room; no simula la lista.
 */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
class CatalogoUiTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Inject
    lateinit var db: AppDatabase

    @Inject
    lateinit var preferencias: PreferenciasOrden

    private var listaId: Long = 0
    private var canalIds: List<Long> = emptyList()

    @Before
    fun preparar() {
        hiltRule.inject()
        runBlocking {
            db.clearAllTables()
            preferencias.barrerHuerfanas(emptySet())
        }
    }

    @Test
    fun elDpadRecorreCanalesYCentroAbreLasAcciones() {
        sembrarCanales()

        composeRule.onNodeWithTag(PREFIJO_TAG_CANAL + 0).requestFocus()
        composeRule.onNodeWithTag(PREFIJO_TAG_CANAL + 0).assertIsFocused()
        composeRule.onNodeWithTag(PREFIJO_TAG_CANAL + 0).performKeyInput {
            pressKey(Key.DirectionDown)
        }
        composeRule.onNodeWithTag(PREFIJO_TAG_CANAL + 1).assertIsFocused()
        composeRule.onNodeWithTag(PREFIJO_TAG_CANAL + 1).performKeyInput {
            pressKey(Key.DirectionCenter)
        }

        esperarTag(TAG_ACCION_FAVORITO)
        composeRule.onNodeWithTag(TAG_ACCION_CERRAR).performClick()
        esperarTagAusente(TAG_ACCION_FAVORITO)
    }

    @Test
    fun favoritoYocultacionFiltranYseRevierten() {
        sembrarCanales()

        composeRule.onNodeWithTag(PREFIJO_TAG_CANAL + 0).performClick()
        esperarTag(TAG_ACCION_FAVORITO)
        composeRule.onNodeWithTag(TAG_ACCION_FAVORITO).performClick()
        esperarHasta { runBlocking { db.favoritoDao().existe(canalIds[0]) > 0 } }

        composeRule.onNodeWithTag(TAG_FILTRO_FAVORITOS).performClick()
        esperarTagAusente(PREFIJO_TAG_CANAL + 1)
        composeRule.onNodeWithTag(PREFIJO_TAG_CANAL + 0).assertExists()

        composeRule.onNodeWithTag(PREFIJO_TAG_CANAL + 0).performClick()
        esperarTag(TAG_ACCION_OCULTO)
        composeRule.onNodeWithTag(TAG_ACCION_OCULTO).performClick()
        esperarHasta { runBlocking { db.ocultacionCanalDao().existe(canalIds[0]) > 0 } }

        composeRule.onNodeWithTag(TAG_FILTRO_TODOS).performClick()
        esperarHasta {
            composeRule.onAllNodesWithText("Canal A").fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithTag(TAG_FILTRO_OCULTOS).performClick()
        esperarTag(PREFIJO_TAG_CANAL + 0)
        composeRule.onNodeWithText("Oculto").assertExists()

        composeRule.onNodeWithTag(PREFIJO_TAG_CANAL + 0).performClick()
        esperarTag(TAG_ACCION_OCULTO)
        composeRule.onNodeWithTag(TAG_ACCION_OCULTO).performClick()
        esperarHasta { runBlocking { db.ocultacionCanalDao().existe(canalIds[0]) == 0 } }
    }

    @Test
    fun creaListaYcanalEditaYEliminaDesdeLaUi() {
        composeRule.onNodeWithTag(TAG_NUEVA_LISTA).performScrollTo().performClick()
        esperarTag(TAG_DIALOGO_NOMBRE)
        composeRule.onNodeWithTag(TAG_DIALOGO_NOMBRE).performTextInput("Lista UI")
        composeRule.onNodeWithTag(TAG_DIALOGO_GUARDAR).performClick()
        esperarHasta {
            composeRule.onAllNodesWithText("Lista: Lista UI").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithTag(TAG_NUEVO_CANAL).performClick()
        esperarTag(TAG_DIALOGO_URL)
        composeRule.onNodeWithTag(TAG_DIALOGO_NOMBRE).performTextInput("Canal UI")
        composeRule.onNodeWithTag(TAG_DIALOGO_URL).performTextInput("https://example.com/ui.m3u8")
        composeRule.onNodeWithTag(TAG_DIALOGO_GUARDAR).performClick()
        esperarTexto(0, "Canal UI")

        composeRule.onNodeWithTag(PREFIJO_TAG_CANAL + 0).performClick()
        esperarTag(TAG_ACCION_EDITAR)
        composeRule.onNodeWithTag(TAG_ACCION_EDITAR).performClick()
        esperarTag(TAG_DIALOGO_NOMBRE)
        composeRule.onNodeWithTag(TAG_DIALOGO_NOMBRE).performTextClearance()
        composeRule.onNodeWithTag(TAG_DIALOGO_NOMBRE).performTextInput("Canal editado")
        composeRule.onNodeWithTag(TAG_DIALOGO_GUARDAR).performClick()
        esperarTexto(0, "Canal editado")

        composeRule.onNodeWithTag(PREFIJO_TAG_CANAL + 0).performClick()
        esperarTag(TAG_ACCION_ELIMINAR)
        composeRule.onNodeWithTag(TAG_ACCION_ELIMINAR).performClick()
        esperarTag(TAG_CONFIRMAR_ELIMINAR)
        composeRule.onNodeWithTag(TAG_CONFIRMAR_ELIMINAR).performClick()
        esperarTagAusente(PREFIJO_TAG_CANAL + 0)

        composeRule.onNodeWithTag(TAG_ELIMINAR_LISTA).performScrollTo().performClick()
        esperarTag(TAG_CONFIRMAR_ELIMINAR)
        composeRule.onNodeWithTag(TAG_CONFIRMAR_ELIMINAR).performClick()
        esperarHasta { runBlocking { db.listaDao().contar() == 0 } }
        esperarHasta {
            composeRule.onAllNodesWithText("Sin lista").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun laAccionBajarReordenaYpersisteEnRoom() {
        sembrarCanales()

        composeRule.onNodeWithTag(PREFIJO_TAG_CANAL + 0).performClick()
        esperarTag(TAG_ACCION_BAJAR)
        composeRule.onNodeWithTag(TAG_ACCION_BAJAR).performClick()

        esperarTexto(0, "Canal B")
        esperarTexto(1, "Canal A")
        val nombres =
            runBlocking {
                db.aparienciaDao().porLista(listaId).mapNotNull { apariencia ->
                    db.canalDao().porId(apariencia.canalId)?.nombre
                }
            }
        assertEquals(listOf("Canal B", "Canal A", "Canal C"), nombres)
    }

    @Test
    fun modoNombreOrdenaYlaDireccionSeInvierte() {
        sembrarCanales(nombres = listOf("Canal C", "Canal A", "Canal B"))

        composeRule.onNodeWithTag(PREFIJO_TAG_MODO_OPCION + "nombre").performScrollTo().performClick()
        esperarTexto(0, "Canal A")
        esperarTexto(1, "Canal B")
        esperarTexto(2, "Canal C")

        composeRule.onNodeWithTag(TAG_DIRECCION).performScrollTo().performClick()
        esperarHasta {
            composeRule.onAllNodesWithText("Descendente").fetchSemanticsNodes().isNotEmpty()
        }
        esperarTexto(0, "Canal C")
        esperarTexto(1, "Canal B")
        esperarTexto(2, "Canal A")
    }

    @Test
    fun modoManualInvierteElOrdenSinTocarLaApariencia() {
        sembrarCanales()

        composeRule.onNodeWithTag(TAG_DIRECCION).performScrollTo().performClick()
        esperarTexto(0, "Canal C")
        esperarTexto(1, "Canal B")
        esperarTexto(2, "Canal A")

        val ordenPersistido =
            runBlocking {
                db.aparienciaDao().porLista(listaId).sortedBy { it.id }.map { it.orden }
            }
        assertEquals(listOf(0, 1, 2), ordenPersistido)
    }

    @Test
    fun modoGrupoMuestraCabecerasYSinGrupoAlFinal() {
        sembrarCanales(
            nombres = listOf("Canal C", "Canal A", "Canal B"),
            grupos = listOf("Grupo Z", "Grupo A", null),
        )

        composeRule.onNodeWithTag(PREFIJO_TAG_MODO_OPCION + "grupo").performScrollTo().performClick()
        esperarTag(PREFIJO_TAG_SECCION_GRUPO + "Grupo A")
        esperarTag(PREFIJO_TAG_SECCION_GRUPO + "Grupo Z")
        esperarTag(PREFIJO_TAG_SECCION_GRUPO + "sin-grupo")
        esperarTexto(0, "Canal A")
        esperarTexto(1, "Canal C")
        esperarTexto(2, "Canal B")
    }

    @Test
    fun modoRecientesDejaNuncaReproducidosAlFinal() {
        sembrarCanales(
            nombres = listOf("Canal C", "Canal A", "Canal B"),
            fechas =
                listOf(
                    Instant.ofEpochMilli(3_000),
                    Instant.ofEpochMilli(1_000),
                    null,
                ),
        )

        composeRule.onNodeWithTag(PREFIJO_TAG_MODO_OPCION + "recientes").performScrollTo().performClick()
        esperarTexto(0, "Canal A")
        esperarTexto(1, "Canal C")
        esperarTexto(2, "Canal B")

        composeRule.onNodeWithTag(TAG_DIRECCION).performScrollTo().performClick()
        esperarTexto(0, "Canal C")
        esperarTexto(1, "Canal A")
        esperarTexto(2, "Canal B")
    }

    @Test
    fun laPreferenciaDeOrdenSobreviveARecrearLaActividad() {
        sembrarCanales()

        composeRule.onNodeWithTag(PREFIJO_TAG_MODO_OPCION + "nombre").performScrollTo().performClick()
        esperarHasta {
            runCatching {
                composeRule.onNodeWithTag(PREFIJO_TAG_MODO_OPCION + "nombre").assertIsSelected()
            }.isSuccess
        }
        esperarHasta {
            runBlocking { preferencias.preferencia(listaId).first().modo == ModoOrden.NOMBRE }
        }

        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()

        esperarHasta {
            runCatching {
                composeRule.onNodeWithTag(PREFIJO_TAG_MODO_OPCION + "nombre").assertIsSelected()
            }.isSuccess
        }
    }

    private fun sembrarCanales(
        nombres: List<String> = listOf("Canal A", "Canal B", "Canal C"),
        grupos: List<String?> = listOf(null, null, null),
        fechas: List<Instant?> = listOf(null, null, null),
    ) {
        runBlocking {
            val ahora = Instant.parse("2026-01-01T00:00:00Z")
            val fuenteId =
                db.fuenteDao().insertar(
                    FuenteEntity(
                        nombre = "Fuente UI",
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
                        nombre = "Lista UI",
                        ubicacionCifrada = byteArrayOf(0x01),
                        ubicacionHmac = byteArrayOf(0x02),
                        esquema = "content",
                        orden = 0,
                        estado = "activa",
                        creadoEn = ahora,
                        actualizadoEn = ahora,
                    ),
                )
            val grupoIds =
                grupos.map { nombre ->
                    if (nombre == null) {
                        null
                    } else {
                        db.grupoDao().insertar(
                            GrupoEntity(listaId = listaId, nombre = nombre),
                        )
                    }
                }
            canalIds =
                db.canalDao().insertarTodos(
                    nombres.map { nombre ->
                        CanalEntity(
                            fuenteId = fuenteId,
                            nombre = nombre,
                            urlStreamCifrada = byteArrayOf(0x01),
                            urlStreamHmac = byteArrayOf(0x02),
                            claveCanonica = "canon:$nombre",
                            claveUnicidad = "canal:$nombre",
                            claveDerivable = true,
                            creadoEn = ahora,
                            actualizadoEn = ahora,
                        )
                    },
                )
            db.aparienciaDao().insertarTodas(
                canalIds.mapIndexed { orden, canalId ->
                    AparienciaEntity(
                        listaId = listaId,
                        canalId = canalId,
                        grupoId = grupoIds[orden],
                        orden = orden,
                        clavePresentacion = ClavePresentacion.clave(null, null),
                        repeticiones = 1,
                        creadoEn = ahora,
                        actualizadoEn = ahora,
                    )
                },
            )
            fechas.forEachIndexed { indice, fecha ->
                if (fecha != null) {
                    db.historialDao().insertar(
                        HistorialEntity(canalId = canalIds[indice], fecha = fecha),
                    )
                }
            }
        }
        esperarHasta {
            composeRule.onAllNodesWithText("Lista: Lista UI").fetchSemanticsNodes().isNotEmpty()
        }
        esperarTag(PREFIJO_TAG_CANAL + 0)
    }

    private fun esperarTag(tag: String) {
        esperarHasta {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun esperarTagAusente(tag: String) {
        esperarHasta {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()
        }
    }

    private fun esperarTexto(
        indice: Int,
        texto: String,
    ) {
        esperarHasta {
            runCatching {
                composeRule.onNodeWithTag(PREFIJO_TAG_CANAL + indice).assertTextEquals(texto)
            }.isSuccess
        }
    }

    private fun esperarHasta(condicion: () -> Boolean) {
        val fallo =
            runCatching {
                composeRule.waitUntil(TIEMPO_ESPERA, condicion)
            }.exceptionOrNull()
        if (fallo != null) {
            throw AssertionError("La condición de UI no se cumplió a tiempo", fallo)
        }
    }

    private companion object {
        const val TIEMPO_ESPERA = 20_000L
    }
}
