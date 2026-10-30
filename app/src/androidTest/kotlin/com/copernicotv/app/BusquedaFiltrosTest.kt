package com.copernicotv.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.app.data.db.entities.AparienciaEntity
import com.copernicotv.app.data.db.entities.CanalEntity
import com.copernicotv.app.data.db.entities.FavoritoEntity
import com.copernicotv.app.data.db.entities.FuenteEntity
import com.copernicotv.app.data.db.entities.GrupoEntity
import com.copernicotv.app.data.db.entities.ListaEntity
import com.copernicotv.app.ui.PREFIJO_TAG_CANAL
import com.copernicotv.app.ui.PREFIJO_TAG_GRUPO
import com.copernicotv.app.ui.TAG_BUSCAR
import com.copernicotv.app.ui.TAG_FILTRO_FAVORITOS
import com.copernicotv.app.ui.TAG_FILTRO_TODOS
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import javax.inject.Inject

/**
 * Búsqueda y filtros combinables de E3-T1 en un emulador móvil.
 *
 * Carga 10 000 Canales, busca `Canal 42` y combina Favorito y Grupo. La prueba
 * usa el camino real de UI → ViewModel → Paging → Room, no una lista simulada.
 */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
class BusquedaFiltrosTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Inject
    lateinit var db: AppDatabase

    private var grupoUno: Long = 0

    @Before
    fun preparar() {
        hiltRule.inject()
        runBlocking {
            db.clearAllTables()
            sembrar()
        }
    }

    @Test
    fun buscaYCombinaGrupoYFavorito() {
        composeRule.onNodeWithTag(TAG_BUSCAR).performClick()
        composeRule.onNodeWithTag(TAG_BUSCAR).performTextInput(TEXTO)
        esperarTexto(indice = 0, texto = TEXTO)

        composeRule.onNodeWithTag(TAG_FILTRO_FAVORITOS).performClick()
        esperarTexto(indice = 0, texto = TEXTO)
        composeRule.onNodeWithTag(PREFIJO_TAG_GRUPO + grupoUno).performScrollTo().performClick()
        esperarTexto(indice = 0, texto = TEXTO)
        composeRule.waitUntil(TIEMPO_ESPERA) {
            composeRule.onAllNodesWithTag(PREFIJO_TAG_CANAL + 1).fetchSemanticsNodes().isEmpty()
        }

        composeRule.onNodeWithTag(TAG_FILTRO_TODOS).performClick()
        composeRule.waitUntil(TIEMPO_ESPERA) {
            composeRule.onAllNodesWithTag(PREFIJO_TAG_CANAL + 1).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun esperarTexto(
        indice: Int,
        texto: String,
    ) {
        val fallo =
            runCatching {
                composeRule.waitUntil(TIEMPO_ESPERA) {
                    runCatching {
                        composeRule.onNodeWithTag(PREFIJO_TAG_CANAL + indice).assertTextEquals(texto)
                    }.isSuccess
                }
            }.exceptionOrNull()
        if (fallo != null) {
            throw AssertionError("No apareció '$texto' en la posición $indice", fallo)
        }
    }

    private suspend fun sembrar() {
        val ahora = Instant.parse("2026-01-01T00:00:00Z")
        val fuenteId =
            db.fuenteDao().insertar(
                FuenteEntity(
                    nombre = "Fuente P1",
                    estado = "activa",
                    usarIntervaloGlobal = true,
                    creadoEn = ahora,
                    actualizadoEn = ahora,
                ),
            )
        val listaId =
            db.listaDao().insertar(
                ListaEntity(
                    fuenteId = fuenteId,
                    nombre = "Lista activa",
                    ubicacionCifrada = byteArrayOf(0x01),
                    ubicacionHmac = byteArrayOf(0x02),
                    esquema = "content",
                    orden = 0,
                    estado = "activa",
                    creadoEn = ahora,
                    actualizadoEn = ahora,
                ),
            )
        val grupos =
            db.grupoDao().insertarTodos(
                (1..GRUPOS).map { numero ->
                    GrupoEntity(listaId = listaId, nombre = "Grupo ${numero.toString().padStart(3, '0')}")
                },
            )
        grupoUno = grupos.first()

        val canalIds =
            db.canalDao().insertarTodos(
                (1..ENTRADAS).map { numero ->
                    CanalEntity(
                        fuenteId = fuenteId,
                        nombre = if (numero % 100 == 42) TEXTO else "Canal ${numero.toString().padStart(5, '0')}",
                        urlStreamCifrada = byteArrayOf(0x01),
                        urlStreamHmac = byteArrayOf(0x02),
                        claveCanonica = "canon:$numero",
                        claveUnicidad = "canal:$numero",
                        claveDerivable = true,
                        creadoEn = ahora,
                        actualizadoEn = ahora,
                    )
                },
            )

        db.aparienciaDao().insertarTodas(
            (1..ENTRADAS).map { numero ->
                AparienciaEntity(
                    listaId = listaId,
                    canalId = canalIds[numero - 1],
                    grupoId = grupos[((numero - 1) / 100)],
                    orden = numero,
                    clavePresentacion = "presentacion:v2:$numero",
                    repeticiones = 1,
                    creadoEn = ahora,
                    actualizadoEn = ahora,
                )
            },
        )

        (42..942 step 100).forEach { numero ->
            db.favoritoDao().insertar(
                FavoritoEntity(canalId = canalIds[numero - 1], creadoEn = ahora),
            )
        }
    }

    private companion object {
        const val ENTRADAS = 10_000
        const val GRUPOS = 100
        const val TEXTO = "Canal 42"
        const val TIEMPO_ESPERA = 20_000L
    }
}
