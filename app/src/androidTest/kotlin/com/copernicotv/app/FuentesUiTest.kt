package com.copernicotv.app

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.printToString
import com.copernicotv.app.crypto.ClavesApp
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.app.data.db.entities.CanalEntity
import com.copernicotv.app.data.db.entities.FuenteEntity
import com.copernicotv.app.data.db.entities.ListaEntity
import com.copernicotv.app.ui.TAG_FUENTES
import com.copernicotv.app.ui.TAG_FUENTE_ACTUALIZACION
import com.copernicotv.app.ui.TAG_FUENTE_ACTUALIZAR
import com.copernicotv.app.ui.TAG_FUENTE_CARGA
import com.copernicotv.app.ui.TAG_FUENTE_DISPONIBILIDAD
import com.copernicotv.app.ui.tagFuente
import com.copernicotv.core.crypto.CifradoAes
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import javax.inject.Inject

/**
 * E4-T8/S7d: la pantalla de Fuentes muestra los tres indicadores sin
 * reutilizar uno por otro, y una Actualización correcta no cambia la
 * disponibilidad.
 *
 * El catálogo se siembra directamente, sin intentos previos: la importación de
 * `CatalogoRepositorio.importarFixture` ya registra un intento completado y
 * dejaría la pantalla en "última correcta" antes de pulsar Actualizar, sin
 * transición observable. La Lista apunta al proveedor de fixture de `debug`,
 * así que la Actualización recorre el camino real de lectura.
 */
@HiltAndroidTest
class FuentesUiTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Inject
    lateinit var db: AppDatabase

    @Inject
    lateinit var claves: ClavesApp

    @Before
    fun preparar() {
        hiltRule.inject()
        db.clearAllTables()
    }

    @Test
    fun laPantallaDeFuentesMuestraLosTresIndicadoresYActualizaSinCambiarLaDisponibilidad() {
        val fuenteId = sembrarFuenteSinIntentos()

        esperarTag(TAG_FUENTES)
        composeRule.onNodeWithTag(TAG_FUENTES).performScrollTo().performClick()

        val tagCarga = tagFuente(TAG_FUENTE_CARGA, fuenteId)
        val tagActualizacion = tagFuente(TAG_FUENTE_ACTUALIZACION, fuenteId)
        val tagDisponibilidad = tagFuente(TAG_FUENTE_DISPONIBILIDAD, fuenteId)
        val tagActualizar = tagFuente(TAG_FUENTE_ACTUALIZAR, fuenteId)

        esperarTag(tagDisponibilidad)
        composeRule.onNodeWithTag(tagCarga).assertTextEquals("Carga: en reposo")
        composeRule.onNodeWithTag(tagActualizacion).assertTextEquals("Actualización: sin intentos")
        composeRule.onNodeWithTag(tagDisponibilidad).assertTextEquals("Disponibilidad: disponible")

        composeRule.onNodeWithTag(tagActualizar).performScrollTo().performClick()

        esperarTexto(tagActualizacion, "Actualización: última correcta")
        esperarTexto(tagCarga, "Carga: en reposo")
        composeRule.onNodeWithTag(tagDisponibilidad).assertTextEquals("Disponibilidad: disponible")
    }

    /**
     * Fuente con una Lista legible y un Canal, sin ningún intento registrado.
     *
     * La disponibilidad se deriva del catálogo (`Canal` por Fuente), no del
     * resultado de la Actualización: por eso la semilla trae un Canal y la
     * Actualización posterior no la altera.
     */
    private fun sembrarFuenteSinIntentos(): Long =
        runBlocking {
            val ahora = Instant.parse("2026-01-01T00:00:00Z")
            val fuenteId =
                db.fuenteDao().insertar(
                    FuenteEntity(
                        nombre = NOMBRE_FUENTE,
                        estado = "activa",
                        usarIntervaloGlobal = true,
                        creadoEn = ahora,
                        actualizadoEn = ahora,
                    ),
                )
            db.listaDao().insertar(
                ListaEntity(
                    fuenteId = fuenteId,
                    nombre = "Lista",
                    ubicacionCifrada =
                        CifradoAes.cifrar(
                            claves.claveAes,
                            CifradoAes.aad("Lista", "ubicacion_cifrada"),
                            UBICACION_FIXTURE.toByteArray(Charsets.UTF_8),
                        ),
                    ubicacionHmac = ByteArray(32),
                    esquema = "content",
                    orden = 0,
                    estado = "activa",
                    creadoEn = ahora,
                    actualizadoEn = ahora,
                ),
            )
            db.canalDao().insertarTodos(
                listOf(
                    CanalEntity(
                        fuenteId = fuenteId,
                        nombre = "Canal semilla",
                        urlStreamCifrada = byteArrayOf(0x01),
                        urlStreamHmac = byteArrayOf(0x02),
                        claveCanonica = "canon:semilla",
                        claveUnicidad = "canal:semilla",
                        claveDerivable = true,
                        creadoEn = ahora,
                        actualizadoEn = ahora,
                    ),
                ),
            )
            fuenteId
        }

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

    private fun esperarTexto(
        tag: String,
        texto: String,
    ) {
        val fallo =
            runCatching {
                composeRule.waitUntil(TIEMPO_ESPERA) {
                    runCatching {
                        composeRule.onNodeWithTag(tag).assertTextEquals(texto)
                    }.isSuccess
                }
            }.exceptionOrNull()
        if (fallo != null) {
            throw AssertionError(
                "El nodo '$tag' no mostró '$texto'.\nÁrbol de semántica:\n${arbol()}",
                fallo,
            )
        }
    }

    private fun arbol(): String =
        runCatching { composeRule.onRoot().printToString() }
            .getOrElse { "no disponible: ${it::class.simpleName}" }

    private companion object {
        const val TIEMPO_ESPERA = 15_000L
        const val NOMBRE_FUENTE = "Fuente fixture"

        /** Sirve el asset canario desde el proveedor de la variante `debug`. */
        const val UBICACION_FIXTURE = "content://com.copernicotv.app.fixture/fixture-canario.m3u"
    }
}
