package com.copernicotv.app

import android.content.Context
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.copernicotv.app.data.CatalogoRepositorio
import com.copernicotv.app.data.HistorialRepositorio
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.app.ui.PREFIJO_TAG_CANAL
import com.copernicotv.app.ui.TAG_ACCION_FAVORITO
import com.copernicotv.app.ui.TAG_BORRAR_HISTORIAL
import com.copernicotv.app.ui.TAG_CONFIRMAR_BORRAR
import com.copernicotv.app.ui.TAG_PRIVACIDAD
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import javax.inject.Inject

/**
 * E7-T6 (S47a/S47b), fase 1: siembra un Canal, lo marca como Favorito desde
 * la UI y registra una entrada de Historial; después borra el Historial desde
 * el diálogo de Privacidad con su confirmación y deja la marca que leerá la
 * fase 2 después de `am force-stop`.
 */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
class S47ProcesoFase1Test {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Inject
    lateinit var db: AppDatabase

    @Inject
    lateinit var catalogo: CatalogoRepositorio

    @Inject
    lateinit var historial: HistorialRepositorio

    private val contexto: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun preparar() {
        hiltRule.inject()
        limpiarBaseS47(db)
        borrarMarcaS47(contexto)
    }

    @Test
    fun borraElHistorialDesdeLaUiYConservaElFavorito() {
        sembrarCanalS47(catalogo)
        composeRule.esperarTag(PREFIJO_TAG_CANAL + 0)
        composeRule.onNodeWithTag(PREFIJO_TAG_CANAL + 0).performClick()
        composeRule.esperarTag(TAG_ACCION_FAVORITO)
        composeRule.onNodeWithTag(TAG_ACCION_FAVORITO).performClick()

        composeRule.esperarHasta {
            runBlocking { db.favoritoDao().contar() == 1 }
        }
        runBlocking {
            val canal = db.canalDao().todos().single()
            historial.registrar(canal.id)
        }
        composeRule.esperarHasta {
            runBlocking { db.historialDao().contar() == 1 }
        }

        composeRule.onNodeWithTag(TAG_PRIVACIDAD).performScrollTo().performClick()
        composeRule.esperarTag(TAG_BORRAR_HISTORIAL)
        composeRule.onNodeWithTag(TAG_BORRAR_HISTORIAL).performClick()
        composeRule.esperarTag(TAG_CONFIRMAR_BORRAR)
        composeRule.onNodeWithTag(TAG_CONFIRMAR_BORRAR).performClick()

        composeRule.esperarHasta {
            runBlocking { db.historialDao().contar() == 0 }
        }
        runBlocking {
            assertEquals(0, db.historialDao().contar())
            assertEquals(1, db.favoritoDao().contar())
        }

        escribirMarcaS47(
            contexto,
            MarcaS47(
                identidad = identidadProceso(),
                favoritos = 1,
                historial = 0,
            ),
        )
    }
}
