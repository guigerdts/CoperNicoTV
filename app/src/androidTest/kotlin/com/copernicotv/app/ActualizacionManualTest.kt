package com.copernicotv.app

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import com.copernicotv.app.data.CatalogoRepositorio
import com.copernicotv.app.data.PreferenciasOrden
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.app.data.db.entities.ColeccionCanalEntity
import com.copernicotv.app.data.db.entities.ColeccionEntity
import com.copernicotv.app.data.db.entities.FavoritoEntity
import com.copernicotv.app.data.db.entities.HistorialEntity
import com.copernicotv.app.data.db.entities.OcultacionCanalEntity
import com.copernicotv.app.ui.TAG_ACTUALIZAR
import com.copernicotv.app.ui.TAG_RESULTADO_ACTUALIZACION
import com.copernicotv.core.orden.DireccionOrden
import com.copernicotv.core.orden.ModoOrden
import com.copernicotv.core.orden.PreferenciaOrden
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import javax.inject.Inject

/**
 * E4-T6: la Actualización manual se ejecuta de extremo a extremo en Android.
 *
 * El fixture se importa por el repositorio real y luego se pulsa «Actualizar».
 * El proveedor de fixture de la variante `debug` responde en la URI guardada
 * como origen de la Lista, así que la Actualización recorre descarga,
 * reconciliación y transacción reales en el dispositivo, no un doble de prueba.
 *
 * La prueba comprueba el resumen visible y que Favoritos, Historial,
 * Ocultaciones, Colecciones y preferencias sobreviven a la Actualización.
 */
@HiltAndroidTest
class ActualizacionManualTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Inject
    lateinit var db: AppDatabase

    @Inject
    lateinit var repositorio: CatalogoRepositorio

    @Inject
    lateinit var preferencias: PreferenciasOrden

    @Before
    fun preparar() {
        hiltRule.inject()
        db.clearAllTables()
    }

    @Test
    fun actualizarEjecutaElCaminoRealYConservaRelacionesYPreferencias() {
        var canalId = 0L
        var coleccionId = 0L
        val listaId =
            runBlocking {
                val resumen = repositorio.importarFixture()
                assertNull("La importación previa falló con ${resumen.error}", resumen.error)
                assertEquals("Canales importados", 3, resumen.canales)
                val id = requireNotNull(resumen.listaId)
                val fuenteId = db.fuenteDao().todas().single().id
                val canal = requireNotNull(db.canalDao().porTvgId(fuenteId, "canario-1"))
                canalId = canal.id
                val ahora = Instant.now()
                db.favoritoDao().insertar(FavoritoEntity(canalId = canalId, creadoEn = ahora))
                db.historialDao().insertar(HistorialEntity(canalId = canalId, fecha = ahora))
                db.ocultacionCanalDao().insertar(OcultacionCanalEntity(canalId = canalId, creadoEn = ahora))
                coleccionId =
                    db.coleccionDao().insertar(
                        ColeccionEntity(nombre = "Mixta", creadoEn = ahora, actualizadoEn = ahora),
                    )
                db.coleccionCanalDao().insertar(
                    ColeccionCanalEntity(coleccionId = coleccionId, canalId = canalId, posicion = 1),
                )
                preferencias.guardar(
                    id,
                    PreferenciaOrden(ModoOrden.NOMBRE, DireccionOrden.DESCENDENTE),
                )
                id
            }

        esperarActualizarHabilitado()
        composeRule.onNodeWithTag(TAG_ACTUALIZAR).performClick()
        esperarResultado()

        composeRule
            .onNodeWithTag(TAG_RESULTADO_ACTUALIZACION)
            .assertTextEquals(
                "Actualización: 0 nuevas, 3 actualizadas, 0 ausentes, 0 ambiguas, 0 observaciones",
            )

        runBlocking {
            assertEquals("Canales tras actualizar", 3, db.canalDao().contar())
            assertNotNull("Favorito", db.favoritoDao().porCanal(canalId))
            assertNotNull("Historial", db.historialDao().porCanal(canalId))
            assertNotNull("Ocultación", db.ocultacionCanalDao().porCanal(canalId))
            assertEquals(
                "Colección",
                1,
                db.coleccionCanalDao().porCanal(coleccionId, canalId).size,
            )
            assertEquals(
                "Preferencia de orden",
                PreferenciaOrden(ModoOrden.NOMBRE, DireccionOrden.DESCENDENTE),
                preferencias.preferencia(listaId).first(),
            )
        }
    }

    private fun esperarActualizarHabilitado() {
        val fallo =
            runCatching {
                composeRule.waitUntil(TIEMPO_ESPERA) {
                    runCatching {
                        composeRule.onNodeWithTag(TAG_ACTUALIZAR).assertIsEnabled()
                    }.isSuccess
                }
            }.exceptionOrNull()
        if (fallo != null) {
            throw AssertionError(
                "El botón Actualizar no se habilitó tras importar el fixture.\nÁrbol de semántica:\n${arbol()}",
                fallo,
            )
        }
    }

    private fun esperarResultado() {
        val fallo =
            runCatching {
                composeRule.waitUntil(TIEMPO_ESPERA) {
                    composeRule
                        .onAllNodesWithTag(TAG_RESULTADO_ACTUALIZACION)
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }
            }.exceptionOrNull()
        if (fallo != null) {
            throw AssertionError(
                "No apareció el resumen de la Actualización manual.\nÁrbol de semántica:\n${arbol()}",
                fallo,
            )
        }
    }

    private fun arbol(): String =
        runCatching { composeRule.onRoot().printToString() }
            .getOrElse { "no disponible: ${it::class.simpleName}" }

    private companion object {
        const val TIEMPO_ESPERA = 15_000L
    }
}
