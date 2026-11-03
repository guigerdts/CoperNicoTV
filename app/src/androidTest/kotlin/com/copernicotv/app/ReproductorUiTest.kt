package com.copernicotv.app

import android.content.res.AssetManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import androidx.test.platform.app.InstrumentationRegistry
import com.copernicotv.app.data.CatalogoRepositorio
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.app.ui.PREFIJO_TAG_CANAL
import com.copernicotv.app.ui.TAG_ACCION_REPRODUCIR
import com.copernicotv.app.ui.TAG_REPRODUCTOR
import com.copernicotv.app.ui.TAG_REPRODUCTOR_ERROR
import com.copernicotv.app.ui.TAG_REPRODUCTOR_ESTADO
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import javax.inject.Inject

/**
 * E6-T1 en un emulador: un Canal real se abre desde el catálogo y Media3
 * reproduce un MP4 progresivo y un HLS servidos por HTTP local.
 *
 * No se simula el motor: se lee `playbackState` y `currentPosition` de la sesión
 * real a través de la etiqueta de estado de la pantalla. El caso de error usa
 * un recurso ausente y comprueba el código propio, sin URL (S57a).
 */
@HiltAndroidTest
class ReproductorUiTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Inject
    lateinit var db: AppDatabase

    @Inject
    lateinit var catalogo: CatalogoRepositorio

    private lateinit var servidor: ServidorMedios

    @Before
    fun preparar() {
        hiltRule.inject()
        runBlocking { db.clearAllTables() }
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        verificarFixtures(assets)
        servidor = ServidorMedios(assets).also { it.iniciar() }
    }

    /**
     * Comprueba que los fixtures viajaron en el APK de pruebas.
     *
     * Sin esto, una ruta de asset equivocada se manifiesta como un `http_error`
     * genérico tras 30 s de espera; aquí el fallo dice exactamente qué ruta
     * falta.
     */
    private fun verificarFixtures(assets: AssetManager) {
        listOf("f15/f15.mp4", "f15/f15.m3u8", "f15/f15_000.ts").forEach { ruta ->
            val primerByte = runCatching { assets.open(ruta).use { it.read() } }.getOrNull()
            if (primerByte == null || primerByte < 0) {
                throw AssertionError("Fixture ausente o vacío en el APK de pruebas: $ruta")
            }
        }
    }

    @After
    fun cerrar() {
        servidor.close()
    }

    @Test
    fun unMp4ProgresivoReproduceYAvanceLaPosicion() {
        sembrarCanal("http://127.0.0.1:${servidor.puerto}/f15/f15.mp4")

        abrirReproductor()

        esperarReproduccionAvanzando()
    }

    @Test
    fun unHlsReproduceYAvanceLaPosicion() {
        sembrarCanal("http://127.0.0.1:${servidor.puerto}/f15/f15.m3u8")

        abrirReproductor()

        esperarReproduccionAvanzando()
    }

    @Test
    fun unRecursoAusenteMuestraUnCodigoPropioSinUrl() {
        sembrarCanal("http://127.0.0.1:${servidor.puerto}/f15/ausente.mp4")

        abrirReproductor()

        esperarTag(TAG_REPRODUCTOR_ERROR)
        val texto = textoDe(TAG_REPRODUCTOR_ERROR)
        if (texto.contains("127.0.0.1") || texto.contains("ausente")) {
            throw AssertionError("El error expone la URL del flujo: $texto")
        }
    }

    private fun sembrarCanal(url: String) {
        val texto = "#EXTM3U\n#EXTINF:-1 tvg-id=\"e6\",Canal E6\n$url\n"
        runBlocking {
            catalogo.importar(texto, "Fuente Reproductor", "http://example.invalid/lista.m3u")
        }
        esperarTag(PREFIJO_TAG_CANAL + 0)
    }

    private fun abrirReproductor() {
        composeRule.onNodeWithTag(PREFIJO_TAG_CANAL + 0).performClick()
        esperarTag(TAG_ACCION_REPRODUCIR)
        composeRule.onNodeWithTag(TAG_ACCION_REPRODUCIR).performClick()
        esperarTag(TAG_REPRODUCTOR)
        esperarTag(TAG_REPRODUCTOR_ESTADO)
    }

    /**
     * Espera a que el motor informe un estado reproducible y una posición
     * mayor que cero. Acepta `READY` o `ENDED` porque el fixture dura dos
     * segundos y puede terminar antes de que el sondeo lea la etiqueta.
     */
    private fun esperarReproduccionAvanzando() {
        esperarHasta {
            val texto = textoDe(TAG_REPRODUCTOR_ESTADO)
            val estado = texto.substringBefore('|').substringAfterLast(' ')
            val posicion = texto.substringAfterLast('|').toLongOrNull() ?: 0L
            (estado == "READY" || estado == "ENDED") && posicion > 0L
        }
    }

    private fun textoDe(tag: String): String =
        composeRule
            .onAllNodesWithTag(tag)
            .fetchSemanticsNodes()
            .firstOrNull()
            ?.config
            ?.getOrNull(SemanticsProperties.Text)
            ?.joinToString("") { it.text }
            .orEmpty()

    private fun esperarTag(tag: String) {
        esperarHasta {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun esperarHasta(condicion: () -> Boolean) {
        val fallo =
            runCatching {
                composeRule.waitUntil(TIEMPO_ESPERA, condicion)
            }.exceptionOrNull()
        if (fallo != null) {
            val arbol =
                runCatching { composeRule.onRoot().printToString() }
                    .getOrElse { "Sin árbol de semántica: $it" }
            throw AssertionError("La condición de UI no se cumplió a tiempo\n$arbol", fallo)
        }
    }

    /**
     * Servidor HTTP mínimo con soporte de `Range` sobre los fixtures del APK de
     * pruebas. Atiende cada conexión en una corrutina de IO; no usa hilos
     * manuales.
     */
    private class ServidorMedios(
        private val assets: AssetManager,
    ) : Closeable {
        private val socket = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))
        private val alcance = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val puerto: Int = socket.localPort

        fun iniciar() {
            alcance.launch {
                while (true) {
                    val conexion = runCatching { socket.accept() }.getOrNull() ?: break
                    launch { atender(conexion) }
                }
            }
        }

        override fun close() {
            alcance.cancel()
            runCatching { socket.close() }
        }

        private fun atender(conexion: Socket) {
            conexion.use { abierta ->
                abierta.soTimeout = TIEMPO_SOCKET_MS
                val entrada = abierta.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                val peticion = entrada.readLine().orEmpty().split(' ')
                val metodo = peticion.getOrNull(0).orEmpty()
                val ruta = peticion.getOrNull(1).orEmpty().substringBefore('?').trimStart('/')
                var rango: String? = null
                while (true) {
                    val cabecera = entrada.readLine() ?: break
                    if (cabecera.isEmpty()) break
                    if (cabecera.startsWith("Range:", ignoreCase = true)) {
                        rango = cabecera.substringAfter(':').trim()
                    }
                }
                val contenido = leer(ruta)
                if (contenido == null) {
                    responder(abierta, metodo, 404, "text/plain", "no encontrado".toByteArray(), null)
                    return
                }
                val recorte = recortar(contenido, rango)
                responder(
                    abierta,
                    metodo,
                    if (recorte == null) 200 else 206,
                    tipoDe(ruta),
                    contenido,
                    recorte,
                )
            }
        }

        private fun leer(ruta: String): ByteArray? = runCatching { assets.open(ruta).use { it.readBytes() } }.getOrNull()

        private fun recortar(
            contenido: ByteArray,
            cabecera: String?,
        ): Recorte? {
            val valor = cabecera?.removePrefix("bytes=")?.trim() ?: return null
            val partes = valor.split('-', limit = 2)
            val desde = partes.getOrNull(0)?.toLongOrNull() ?: return null
            val hasta = partes.getOrNull(1)?.toLongOrNull() ?: (contenido.size - 1L)
            if (desde < 0 || hasta < desde || desde >= contenido.size) return null
            val fin = minOf(hasta, contenido.size - 1L).toInt()
            return Recorte(desde.toInt(), contenido.copyOfRange(desde.toInt(), fin + 1))
        }

        private fun responder(
            conexion: Socket,
            metodo: String,
            estado: Int,
            tipo: String,
            contenido: ByteArray,
            recorte: Recorte?,
        ) {
            val cuerpo = recorte?.cuerpo ?: contenido
            val cabeceras =
                buildString {
                    append("HTTP/1.1 $estado ${motivo(estado)}\r\n")
                    append("Content-Type: $tipo\r\n")
                    append("Content-Length: ${cuerpo.size}\r\n")
                    append("Accept-Ranges: bytes\r\n")
                    if (recorte != null) {
                        append("Content-Range: bytes ${recorte.desde}-")
                        append("${recorte.desde + recorte.cuerpo.size - 1}/${contenido.size}\r\n")
                    }
                    append("Connection: close\r\n\r\n")
                }
            val salida = conexion.getOutputStream()
            salida.write(cabeceras.toByteArray(Charsets.ISO_8859_1))
            if (metodo != "HEAD") salida.write(cuerpo)
            salida.flush()
        }

        private data class Recorte(
            val desde: Int,
            val cuerpo: ByteArray,
        )

        private fun tipoDe(ruta: String): String =
            when {
                ruta.endsWith(".m3u8") -> "application/vnd.apple.mpegurl"
                ruta.endsWith(".ts") -> "video/mp2t"
                ruta.endsWith(".mp4") -> "video/mp4"
                else -> "application/octet-stream"
            }

        private fun motivo(estado: Int): String =
            when (estado) {
                200 -> "OK"
                206 -> "Partial Content"
                else -> "Not Found"
            }

        private companion object {
            const val TIEMPO_SOCKET_MS = 10_000
        }
    }

    private companion object {
        const val TIEMPO_ESPERA = 30_000L
    }
}
