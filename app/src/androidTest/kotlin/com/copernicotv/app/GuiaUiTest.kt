package com.copernicotv.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.printToString
import com.copernicotv.app.crypto.ClavesApp
import com.copernicotv.app.data.AsociacionRepositorio
import com.copernicotv.app.data.EpgRepositorio
import com.copernicotv.app.data.IndiceHmacRepositorio
import com.copernicotv.app.data.PreferenciasRetencion
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.app.data.db.entities.CanalEntity
import com.copernicotv.app.data.db.entities.FuenteEntity
import com.copernicotv.app.data.db.entities.FuenteGuiaEntity
import com.copernicotv.app.ui.TAG_DETALLE_GUIA
import com.copernicotv.app.ui.TAG_GUIA
import com.copernicotv.app.ui.tagCanalGuia
import com.copernicotv.app.ui.tagProgramaGuia
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/**
 * E5-T5/S25, S26 y S28 en el emulador: la Guía muestra actual/siguiente por
 * Canal, abre la vista temporal paginada y presenta el detalle del Programa.
 *
 * La semilla recorre el camino real de importación y confirmación de la
 * Asociación; la UI no recibe datos simulados.
 */
@HiltAndroidTest
class GuiaUiTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Inject
    lateinit var db: AppDatabase

    @Inject
    lateinit var claves: ClavesApp

    @Inject
    lateinit var preferenciasRetencion: PreferenciasRetencion

    @Before
    fun preparar() {
        hiltRule.inject()
        db.clearAllTables()
    }

    @Test
    fun laGuiaMuestraActualSiguienteAbreLaVistaTemporalYElDetalle() {
        val datos = runBlocking { sembrar() }

        esperarTag(TAG_GUIA)
        composeRule.onNodeWithTag(TAG_GUIA).performScrollTo().performClick()

        esperarTag(tagCanalGuia(datos.canalId))
        composeRule.onNodeWithTag(tagCanalGuia(datos.canalId)).performScrollTo().performClick()

        esperarTag(tagProgramaGuia(datos.programaId))
        composeRule.onNodeWithTag(tagProgramaGuia(datos.programaId)).performClick()

        esperarTag(TAG_DETALLE_GUIA)
        composeRule.onNodeWithTag(TAG_DETALLE_GUIA).assertIsDisplayed()
        composeRule.onAllNodesWithText("Programa actual").onFirst().assertIsDisplayed()
    }

    private suspend fun sembrar(): DatosGuia {
        val ahora = Instant.now()
        val canalId = sembrarCanal(ahora)
        val fuenteGuiaId = sembrarFuenteGuia(ahora)
        val repositorio = EpgRepositorio(db, IndiceHmacRepositorio(db, claves), preferenciasRetencion)
        val resumen =
            repositorio.importar(
                fuenteGuiaId = fuenteGuiaId,
                entrada =
                    ByteArrayInputStream(
                        xml(
                            canalId = "c1",
                            inicio = ahora.minusSeconds(600),
                            fin = ahora.plusSeconds(1800),
                            titulo = "Programa actual",
                        ).toByteArray(),
                    ),
            )
        val generacionId = requireNotNull(resumen.generacionId)
        val asociacion = db.asociacionDao().porFuente(fuenteGuiaId).single()
        AsociacionRepositorio(db).confirmar(asociacion.id)
        val programa =
            db.programaDao().enIntervalo(
                generacionId = generacionId,
                channelId = "c1",
                desde = ahora.minusSeconds(3600),
                hasta = ahora.plusSeconds(3600),
            ).single()
        return DatosGuia(canalId = canalId, programaId = programa.id)
    }

    private suspend fun sembrarCanal(ahora: Instant): Long {
        val fuenteId =
            db.fuenteDao().insertar(
                FuenteEntity(
                    nombre = "Fuente",
                    estado = "activa",
                    usarIntervaloGlobal = true,
                    creadoEn = ahora,
                    actualizadoEn = ahora,
                ),
            )
        return db.canalDao().insertar(
            CanalEntity(
                fuenteId = fuenteId,
                nombre = "Canal Guía",
                urlStreamCifrada = byteArrayOf(0x01),
                urlStreamHmac = byteArrayOf(0x02),
                tvgId = "c1",
                claveDerivable = false,
                creadoEn = ahora,
                actualizadoEn = ahora,
            ),
        )
    }

    private suspend fun sembrarFuenteGuia(ahora: Instant): Long =
        db.fuenteGuiaDao().insertar(
            FuenteGuiaEntity(
                nombre = "Guía",
                usarRetencionGlobal = true,
                creadoEn = ahora,
                actualizadoEn = ahora,
            ),
        )

    private fun xml(
        canalId: String,
        inicio: Instant,
        fin: Instant,
        titulo: String,
    ): String =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <tv>
          <channel id="$canalId"><display-name>Canal Guía</display-name></channel>
          <programme channel="$canalId" start="${FORMATO.format(inicio)}" stop="${FORMATO.format(fin)}">
            <title>$titulo</title>
          </programme>
        </tv>
        """.trimIndent()

    private fun esperarTag(tag: String) {
        val fallo =
            runCatching {
                composeRule.waitUntil(TIEMPO_ESPERA) {
                    composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
                }
            }.exceptionOrNull()
        if (fallo != null) {
            throw AssertionError(
                "No apareció el nodo '$tag'.\nÁrbol de semántica:\n${arbol()}",
                fallo,
            )
        }
    }

    private fun arbol(): String =
        runCatching { composeRule.onRoot().printToString() }
            .getOrElse { "no disponible: ${it::class.simpleName}" }

    private data class DatosGuia(
        val canalId: Long,
        val programaId: Long,
    )

    private companion object {
        const val TIEMPO_ESPERA = 15_000L
        val FORMATO: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC)
    }
}
