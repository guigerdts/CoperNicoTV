package com.copernicotv.app

import com.copernicotv.app.data.CatalogoRepositorio
import com.copernicotv.app.data.db.AppDatabase
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import javax.inject.Inject

/**
 * Camino SAF de E2-T2 que sí se puede ejecutar sin selección humana.
 *
 * Un `content://` sin grant persistible produce `permiso_saf_no_persistido` y
 * no debe tocar el catálogo ya importado. El camino feliz exige un
 * `ACTION_OPEN_DOCUMENT` resuelto por el usuario y queda en §10.4 como
 * verificación física.
 */
@HiltAndroidTest
class ImportacionSafTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var db: AppDatabase

    @Inject
    lateinit var repositorio: CatalogoRepositorio

    @Before
    fun preparar() {
        hiltRule.inject()
        db.clearAllTables()
    }

    @Test
    fun un_documento_sin_permiso_persistible_conserva_el_catalogo_previo() {
        runBlocking {
            val previa = repositorio.importarFixture()
            assertNull("La importación previa falló con ${previa.error}", previa.error)
            assertEquals(3, previa.canales)

            val resumen =
                repositorio.importarDesdeSaf(
                    "Fuente sin permiso",
                    "content://com.copernicotv.inexistente/lista.m3u",
                )

            assertEquals("permiso_saf_no_persistido", resumen.error)
            assertNull(resumen.listaId)
            assertEquals("Canales previos", 3, db.canalDao().contar())
            assertEquals("Fuentes previas", 1, db.fuenteDao().contar())
        }
    }

    @Test
    fun el_nombre_de_un_documento_sin_proveedor_cae_a_la_uri() {
        runBlocking {
            val nombre =
                repositorio.nombreDeDocumento(
                    "content://com.copernicotv.inexistente/Download/lista-mia.m3u",
                )

            assertEquals("lista-mia.m3u", nombre)
        }
    }
}
