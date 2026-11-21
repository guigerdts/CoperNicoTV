package com.copernicotv.app.ota

import com.copernicotv.core.ota.CanalActualizacion
import com.copernicotv.core.ota.Integridad
import com.copernicotv.core.ota.ManifiestoActualizacion
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.time.Instant

/**
 * Descarga reanudable y verificación de integridad del APK.
 *
 * El cliente HTTP se reemplaza por un doble que escribe en el archivo igual que
 * el real: lo que se prueba acá es la política del descargador (reanudar,
 * comprobar tamaño y hash, no repetir un hash inválido).
 */
class DescargadorApkTest {
    @Test
    fun `descarga completa mueve el parcial al archivo final`() {
        val contenido = "APK-de-prueba".toByteArray()
        val directorio = directorioTemporal()
        val manifiesto = manifiesto(contenido)
        val cliente = ClienteFalso { desde, destino -> escribirResto(contenido, desde, destino) }

        val resultado = runBlocking { descargador(cliente, directorio).descargar(manifiesto) }

        assertTrue(resultado is ResultadoDescargaApk.Exito)
        val archivo = (resultado as ResultadoDescargaApk.Exito).archivo
        assertEquals("ota-2.apk", archivo.name)
        assertEquals(contenido.toList(), archivo.readBytes().toList())
        assertFalse(File("${archivo.path}.part").exists())
    }

    @Test
    fun `reanuda desde el parcial existente`() {
        val contenido = "0123456789".toByteArray()
        val directorio = directorioTemporal()
        val manifiesto = manifiesto(contenido)
        val parcial = File(directorio, "ota-2.apk.part")
        parcial.writeBytes(contenido.copyOfRange(0, 4))
        val cliente = ClienteFalso { desde, destino -> escribirResto(contenido, desde, destino) }

        val resultado = runBlocking { descargador(cliente, directorio).descargar(manifiesto) }

        assertTrue(resultado is ResultadoDescargaApk.Exito)
        assertEquals(4L, cliente.ultimoDesde)
        assertEquals(contenido.toList(), (resultado as ResultadoDescargaApk.Exito).archivo.readBytes().toList())
    }

    @Test
    fun `reutiliza el archivo final ya verificado sin descargar`() {
        val contenido = "APK-de-prueba".toByteArray()
        val directorio = directorioTemporal()
        val manifiesto = manifiesto(contenido)
        File(directorio, "ota-2.apk").writeBytes(contenido)
        val cliente = ClienteFalso { _, _ -> ResultadoDescargaHttp.Fallo(FalloHttp.IO, reintentable = true) }

        val resultado = runBlocking { descargador(cliente, directorio).descargar(manifiesto) }

        assertTrue(resultado is ResultadoDescargaApk.Exito)
        assertEquals(0, cliente.llamadas)
    }

    @Test
    fun `hash incorrecto elimina el parcial y no reintenta`() {
        val contenido = "APK!".toByteArray()
        val directorio = directorioTemporal()
        val manifiesto = manifiesto(contenido).copy(sha256 = Integridad.sha256Hex("otro".toByteArray()))
        val cliente = ClienteFalso { _, destino -> escribirResto(contenido, 0L, destino) }

        val resultado = runBlocking { descargador(cliente, directorio).descargar(manifiesto) }

        assertTrue(resultado is ResultadoDescargaApk.Fallo)
        assertEquals(FalloDescargaApk.HASH, (resultado as ResultadoDescargaApk.Fallo).tipo)
        assertFalse(File(directorio, "ota-2.apk.part").exists())
        assertEquals(1, cliente.llamadas)
    }

    @Test
    fun `tamano incompleto reintenta y termina reportando tamano`() {
        val contenido = "APK".toByteArray()
        val directorio = directorioTemporal()
        val manifiesto = manifiesto(contenido)
        var intento = 0
        val cliente =
            ClienteFalso { _, destino ->
                intento++
                if (intento == 1) escribirResto(contenido.copyOfRange(0, 2), 0L, destino)
                ResultadoDescargaHttp.Completa(2, contenido.size.toLong())
            }

        val resultado = runBlocking { descargador(cliente, directorio).descargar(manifiesto) }

        assertTrue(resultado is ResultadoDescargaApk.Fallo)
        assertEquals(FalloDescargaApk.TAMANO, (resultado as ResultadoDescargaApk.Fallo).tipo)
        assertEquals(2, cliente.llamadas)
    }

    @Test
    fun `un fallo no reintentable corta en el primer intento`() {
        val contenido = "APK".toByteArray()
        val directorio = directorioTemporal()
        val manifiesto = manifiesto(contenido)
        val cliente = ClienteFalso { _, _ -> ResultadoDescargaHttp.Fallo(FalloHttp.TLS, reintentable = false) }

        val resultado = runBlocking { descargador(cliente, directorio).descargar(manifiesto) }

        assertTrue(resultado is ResultadoDescargaApk.Fallo)
        assertEquals(FalloDescargaApk.RED, (resultado as ResultadoDescargaApk.Fallo).tipo)
        assertEquals(1, cliente.llamadas)
    }

    @Test
    fun `limpia versiones distintas y conserva la actual`() {
        val directorio = directorioTemporal()
        File(directorio, "ota-2.apk").writeText("actual")
        File(directorio, "ota-2.apk.part").writeText("parcial")
        File(directorio, "ota-3.apk").writeText("vieja")
        File(directorio, "otro.txt").writeText("no es apk")

        descargador(
            ClienteFalso { _, _ -> ResultadoDescargaHttp.Fallo(FalloHttp.IO) },
            directorio,
        ).limpiarExcepto(2L)

        val nombres = directorio.list()?.sorted().orEmpty()
        assertEquals(listOf("ota-2.apk", "ota-2.apk.part", "otro.txt"), nombres)
    }

    private fun descargador(
        cliente: ClienteHttpOta,
        directorio: File,
    ): DescargadorApk = DescargadorApk(cliente, directorio, intentosMaximos = 2, espera = {})

    private fun escribirResto(
        contenido: ByteArray,
        desde: Long,
        destino: File,
    ): ResultadoDescargaHttp {
        FileOutputStream(destino, desde > 0L).use { salida ->
            salida.write(contenido.copyOfRange(desde.toInt(), contenido.size))
        }
        return ResultadoDescargaHttp.Completa(contenido.size.toLong(), contenido.size.toLong())
    }

    private fun manifiesto(contenido: ByteArray): ManifiestoActualizacion =
        ManifiestoActualizacion(
            esquema = ManifiestoActualizacion.ESQUEMA_ACTUAL,
            paquete = PAQUETE,
            versionCode = 2,
            versionName = "0.2.0",
            canal = CanalActualizacion.ESTABLE,
            minSdk = 26,
            publicadoEn = Instant.parse("2026-11-20T00:00:00Z"),
            tamanoBytes = contenido.size.toLong(),
            sha256 = Integridad.sha256Hex(contenido),
            urlDescarga = "https://github.com/guigerdts/CoperNicoTV/releases/download/v0.2.0/app-release.apk",
            urlRelease = "https://github.com/guigerdts/CoperNicoTV/releases/tag/v0.2.0",
            notas = "Notas",
            obligatoria = false,
            huellaFirmaApk = HUELLA,
        )

    private fun directorioTemporal(): File {
        val directorio = Files.createTempDirectory("ota-descarga-test").toFile()
        directorio.deleteOnExit()
        return directorio
    }

    private class ClienteFalso(
        private val alDescargar: (Long, File) -> ResultadoDescargaHttp,
    ) : ClienteHttpOta {
        var llamadas = 0
        var ultimoDesde = -1L

        override suspend fun obtener(
            url: String,
            maxBytes: Int,
        ): ResultadoHttp = ResultadoHttp.Fallo(FalloHttp.IO)

        override suspend fun descargar(
            url: String,
            destino: File,
            desdeBytes: Long,
            alProgresar: (Long, Long?) -> Unit,
        ): ResultadoDescargaHttp {
            llamadas++
            ultimoDesde = desdeBytes
            return alDescargar(desdeBytes, destino)
        }
    }

    private companion object {
        const val PAQUETE = "com.copernicotv.app"
        val HUELLA = "b".repeat(64)
    }
}
