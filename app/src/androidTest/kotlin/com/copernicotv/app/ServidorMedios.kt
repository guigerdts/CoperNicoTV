package com.copernicotv.app

import android.content.res.AssetManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Servidor HTTP mínimo con soporte de `Range` sobre los fixtures del APK de
 * pruebas. Atiende cada conexión en una corrutina de IO; no usa hilos
 * manuales. Lo comparten las pruebas de reproducción móvil y de Android TV.
 *
 * [decidirFallo] permite simular respuestas de error por ruta y número de
 * petición (E6-T4): devolver un estado fuerza esa respuesta sin servir el
 * fixture; devolver `null` deja el comportamiento normal. [peticionesDe] cuenta
 * las peticiones de una ruta para comprobar cuántos reintentos hubo.
 */
internal class ServidorMedios(
    private val assets: AssetManager,
    private val decidirFallo: (ruta: String, peticion: Int) -> Int? = { _, _ -> null },
    puertoFijo: Int? = null,
) : Closeable {
    /**
     * [puertoFijo] reabre el mismo puerto en dos procesos distintos (dos
     * fases de un arnés de reinicio): la URL persistida en Room del proceso
     * anterior sigue resolviendo contra el servidor del proceso nuevo.
     */
    private val socket =
        if (puertoFijo != null) {
            ServerSocket(puertoFijo, 0, InetAddress.getByName("127.0.0.1"))
        } else {
            ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))
        }
    private val alcance = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val contadores = ConcurrentHashMap<String, AtomicInteger>()
    val puerto: Int = socket.localPort

    /** Peticiones recibidas para [ruta] desde que se creó el servidor. */
    fun peticionesDe(ruta: String): Int = contadores[ruta]?.get() ?: 0

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
            val numero = contadores.computeIfAbsent(ruta) { AtomicInteger() }.incrementAndGet()
            val estadoForzado = decidirFallo(ruta, numero)
            if (estadoForzado != null) {
                responder(abierta, metodo, estadoForzado, "text/plain", "fallo simulado".toByteArray(), null)
                return
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
            400 -> "Bad Request"
            403 -> "Forbidden"
            404 -> "Not Found"
            500 -> "Internal Server Error"
            503 -> "Service Unavailable"
            else -> if (estado >= 500) "Server Error" else "Error"
        }

    private companion object {
        const val TIEMPO_SOCKET_MS = 10_000
    }
}

/** Fixtures de E6-T1/E6-T2 y sus rutas HTTP locales. */
internal object FixturesF15 {
    const val MP4 = "f15/f15.mp4"
    const val HLS = "f15/f15.m3u8"

    /**
     * Comprueba que los fixtures viajaron en el APK de pruebas.
     *
     * Sin esto, una ruta de asset equivocada se manifiesta como un `http_error`
     * genérico tras 30 s de espera; aquí el fallo dice exactamente qué ruta
     * falta.
     */
    fun verificar(assets: AssetManager) {
        listOf("f15/f15.mp4", "f15/f15.m3u8", "f15/f15_000.ts").forEach { ruta ->
            val primerByte = runCatching { assets.open(ruta).use { it.read() } }.getOrNull()
            if (primerByte == null || primerByte < 0) {
                throw AssertionError("Fixture ausente o vacío en el APK de pruebas: $ruta")
            }
        }
    }

    fun url(
        servidor: ServidorMedios,
        ruta: String,
    ): String = "http://127.0.0.1:${servidor.puerto}/$ruta"
}

/** Fixtures de E6-T3: audio y subtítulos múltiples, uno solo y ninguno. */
internal object FixturesF19 {
    /** Dos pistas de audio (spa/eng) y dos subtítulos (spa/eng). */
    const val PISTAS = "f19/f19_pistas.mp4"

    /** Un audio y un subtítulo en español; no contiene inglés. */
    const val SOLO_ESPANOL = "f19/f19_solo_espanol.mp4"

    /** Solo vídeo: sin pistas que ofrecer. */
    const val SIN_PISTAS = "f19/f19_sin_pistas.mp4"

    fun verificar(assets: AssetManager) {
        listOf(PISTAS, SOLO_ESPANOL, SIN_PISTAS).forEach { ruta ->
            val primerByte = runCatching { assets.open(ruta).use { it.read() } }.getOrNull()
            if (primerByte == null || primerByte < 0) {
                throw AssertionError("Fixture ausente o vacío en el APK de pruebas: $ruta")
            }
        }
    }

    fun url(
        servidor: ServidorMedios,
        ruta: String,
    ): String = "http://127.0.0.1:${servidor.puerto}/$ruta"
}
