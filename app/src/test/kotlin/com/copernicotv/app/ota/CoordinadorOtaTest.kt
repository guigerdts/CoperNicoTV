package com.copernicotv.app.ota

import com.copernicotv.core.ota.CanalActualizacion
import com.copernicotv.core.ota.Integridad
import com.copernicotv.core.ota.ManifiestoActualizacion
import com.copernicotv.core.ota.VersionInstalada
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant

/**
 * Pasada completa del OTA: comprobar, descargar, verificar y avisar.
 *
 * Usa el comprobador real (con fuente y almacén falsos) y el descargador real
 * (con cliente falso): así se prueba la integración entre las capas y no solo
 * el coordinador aislado.
 */
class CoordinadorOtaTest {
    @Test
    fun `descarga la version nueva y avisa que esta lista`() =
        runBlocking {
            val contenido = "apk".toByteArray()
            val manifiesto = manifiesto(versionCode = 2, contenido = contenido)
            val notificador = NotificadorFalso()
            val cliente = ClienteFalso(contenido)

            val resultado = coordinador(manifiesto, cliente, notificador).ejecutar()

            assertTrue(resultado is ResultadoOta.Descargada)
            assertEquals(1, cliente.descargas)
            assertTrue(notificador.listo)
            assertTrue(notificador.disponible)
        }

    @Test
    fun `no vuelve a descargar un apk ya verificado`() =
        runBlocking {
            val contenido = "apk".toByteArray()
            val manifiesto = manifiesto(versionCode = 2, contenido = contenido)
            val directorio = directorioTemporal()
            val cliente = ClienteFalso(contenido)
            File(directorio, "ota-2.apk").writeBytes(contenido)

            val resultado =
                coordinador(manifiesto, cliente, NotificadorFalso(), directorio).ejecutar()

            assertTrue(resultado is ResultadoOta.Descargada)
            assertEquals(0, cliente.descargas)
        }

    @Test
    fun `si la firma del apk no coincide descarta el archivo y falla`() =
        runBlocking {
            val contenido = "apk".toByteArray()
            val manifiesto = manifiesto(versionCode = 2, contenido = contenido)
            val directorio = directorioTemporal()

            val resultado =
                coordinador(
                    manifiesto = manifiesto,
                    cliente = ClienteFalso(contenido),
                    notificador = NotificadorFalso(),
                    directorio = directorio,
                    verificacion = ResultadoVerificacionApk.Fallo(FalloVerificacionApk.FIRMA),
                ).ejecutar()

            assertEquals(FalloOta.VERIFICACION, (resultado as ResultadoOta.Fallo).tipo)
            assertFalse(File(directorio, "ota-2.apk").exists())
        }

    @Test
    fun `al dia no descarga y borra apks de otras versiones`() =
        runBlocking {
            val directorio = directorioTemporal()
            val viejo = File(directorio, "ota-1.apk").apply { writeBytes("viejo".toByteArray()) }
            val cliente = ClienteFalso("apk".toByteArray())

            val resultado =
                coordinador(
                    manifiesto = manifiesto(versionCode = 1, contenido = "apk".toByteArray()),
                    cliente = cliente,
                    notificador = NotificadorFalso(),
                    directorio = directorio,
                ).ejecutar()

            assertTrue(resultado is ResultadoOta.SinNovedad)
            assertEquals(0, cliente.descargas)
            assertFalse(viejo.exists())
        }

    @Test
    fun `sin red reporta el fallo tipado`() =
        runBlocking {
            val resultado =
                CoordinadorOta(
                    comprobador = comprobador(FuenteFalsa(ResultadoFuente.Fallo(FalloFuente.SIN_RED)), AlmacenFalso()),
                    descargador = DescargadorApk(ClienteFalso(ByteArray(0)), directorioTemporal()),
                    verificador = VerificadorFalso(ResultadoVerificacionApk.Valido),
                    notificador = NotificadorFalso(),
                    almacen = AlmacenFalso(),
                ).ejecutar()

            assertEquals(FalloOta.SIN_RED, (resultado as ResultadoOta.Fallo).tipo)
        }

    @Test
    fun `un fallo de firma del manifiesto no descarga el apk`() =
        runBlocking {
            val cliente = ClienteFalso("apk".toByteArray())

            val resultado =
                CoordinadorOta(
                    comprobador = comprobador(FuenteFalsa(ResultadoFuente.Fallo(FalloFuente.FIRMA_INVALIDA)), AlmacenFalso()),
                    descargador = DescargadorApk(cliente, directorioTemporal(), intentosMaximos = 1, espera = {}),
                    verificador = VerificadorFalso(ResultadoVerificacionApk.Valido),
                    notificador = NotificadorFalso(),
                    almacen = AlmacenFalso(),
                ).ejecutar()

            assertEquals(FalloOta.FIRMA, (resultado as ResultadoOta.Fallo).tipo)
            assertEquals(0, cliente.descargas)
        }

    @Test
    fun `un manifiesto invalido no descarga el apk`() =
        runBlocking {
            val cliente = ClienteFalso("apk".toByteArray())

            val resultado =
                CoordinadorOta(
                    comprobador = comprobador(FuenteFalsa(ResultadoFuente.Fallo(FalloFuente.RESPUESTA_INVALIDA)), AlmacenFalso()),
                    descargador = DescargadorApk(cliente, directorioTemporal(), intentosMaximos = 1, espera = {}),
                    verificador = VerificadorFalso(ResultadoVerificacionApk.Valido),
                    notificador = NotificadorFalso(),
                    almacen = AlmacenFalso(),
                ).ejecutar()

            assertEquals(FalloOta.MANIFIESTO, (resultado as ResultadoOta.Fallo).tipo)
            assertEquals(0, cliente.descargas)
        }

    @Test
    fun `un corte de red al descargar el apk se informa como error de red`() =
        runBlocking {
            val resultado =
                CoordinadorOta(
                    comprobador =
                        comprobador(
                            FuenteFalsa(
                                ResultadoFuente.Manifiestos(listOf(verificado(manifiesto(2, "apk".toByteArray())))),
                            ),
                            AlmacenFalso(),
                        ),
                    descargador = DescargadorApk(ClienteQueFalla(), directorioTemporal(), intentosMaximos = 1, espera = {}),
                    verificador = VerificadorFalso(ResultadoVerificacionApk.Valido),
                    notificador = NotificadorFalso(),
                    almacen = AlmacenFalso(),
                ).ejecutar()

            assertEquals(FalloOta.SIN_RED, (resultado as ResultadoOta.Fallo).tipo)
        }

    @Test
    fun `un apk con hash distinto al del manifiesto se informa como integridad`() =
        runBlocking {
            val manifiesto = manifiesto(versionCode = 2, contenido = "esperado".toByteArray())

            val resultado = coordinador(manifiesto, ClienteFalso("otro contenido".toByteArray()), NotificadorFalso()).ejecutar()

            assertEquals(FalloOta.VERIFICACION, (resultado as ResultadoOta.Fallo).tipo)
        }

    @Test
    fun `una version incompatible no se descarga`() =
        runBlocking {
            // El comprobador simula API 34: un minSdk mayor es incompatible y
            // el descargador no debe ejecutarse.
            val manifiesto = manifiesto(versionCode = 2, contenido = ByteArray(0), minSdk = 35)
            val cliente = ClienteFalso(ByteArray(0))

            val resultado = coordinador(manifiesto, cliente, NotificadorFalso()).ejecutar()

            assertTrue(resultado is ResultadoOta.Incompatible)
            assertEquals(0, cliente.descargas)
        }

    private fun coordinador(
        manifiesto: ManifiestoActualizacion,
        cliente: ClienteHttpOta,
        notificador: NotificadorOta,
        directorio: File = directorioTemporal(),
        verificacion: ResultadoVerificacionApk = ResultadoVerificacionApk.Valido,
    ): CoordinadorOta {
        val almacen = AlmacenFalso()
        return CoordinadorOta(
            comprobador = comprobador(FuenteFalsa(ResultadoFuente.Manifiestos(listOf(verificado(manifiesto)))), almacen),
            descargador = DescargadorApk(cliente, directorio, intentosMaximos = 1, espera = {}),
            verificador = VerificadorFalso(verificacion),
            notificador = notificador,
            almacen = almacen,
        )
    }

    private fun comprobador(
        fuente: FuenteManifiestos,
        almacen: AlmacenManifiestoOta,
    ): ComprobadorActualizaciones =
        ComprobadorActualizaciones(
            fuente = fuente,
            almacen = almacen,
            configuracion = CONFIGURACION,
            versionInstalada = VersionInstalada(1, "0.1.0"),
            sdkInt = 34,
            ahora = { 1_000L },
        )

    private fun verificado(manifiesto: ManifiestoActualizacion): ManifiestoVerificado =
        ManifiestoVerificado(manifiesto, "{}".toByteArray(), ByteArray(64))

    private fun manifiesto(
        versionCode: Long,
        contenido: ByteArray,
        minSdk: Int = 26,
    ): ManifiestoActualizacion =
        ManifiestoActualizacion(
            esquema = ManifiestoActualizacion.ESQUEMA_ACTUAL,
            paquete = PAQUETE,
            versionCode = versionCode,
            versionName = "0.$versionCode.0",
            canal = CanalActualizacion.ESTABLE,
            minSdk = minSdk,
            publicadoEn = Instant.parse("2026-11-20T00:00:00Z"),
            tamanoBytes = contenido.size.toLong(),
            sha256 = Integridad.sha256Hex(contenido),
            urlDescarga = "https://github.com/guigerdts/CoperNicoTV/releases/download/v0.$versionCode.0/app-release.apk",
            urlRelease = "https://github.com/guigerdts/CoperNicoTV/releases/tag/v0.$versionCode.0",
            notas = "Notas",
            obligatoria = false,
            huellaFirmaApk = HUELLA,
        )

    private fun directorioTemporal(): File {
        val directorio = Files.createTempDirectory("ota-coordinador").toFile()
        directorio.deleteOnExit()
        return directorio
    }

    private class FuenteFalsa(
        private val resultado: ResultadoFuente,
    ) : FuenteManifiestos {
        override suspend fun buscar(): ResultadoFuente = resultado
    }

    private class ClienteFalso(
        private val contenido: ByteArray,
    ) : ClienteHttpOta {
        var descargas = 0
            private set

        override suspend fun obtener(
            url: String,
            maxBytes: Int,
        ): ResultadoHttp = ResultadoHttp.Exito(ByteArray(0), url)

        override suspend fun descargar(
            url: String,
            destino: File,
            desdeBytes: Long,
            alProgresar: (escritos: Long, totales: Long?) -> Unit,
        ): ResultadoDescargaHttp {
            descargas++
            destino.parentFile?.mkdirs()
            destino.writeBytes(contenido)
            alProgresar(contenido.size.toLong(), contenido.size.toLong())
            return ResultadoDescargaHttp.Completa(contenido.size.toLong(), contenido.size.toLong())
        }
    }

    private class ClienteQueFalla : ClienteHttpOta {
        override suspend fun obtener(
            url: String,
            maxBytes: Int,
        ): ResultadoHttp = ResultadoHttp.Fallo(FalloHttp.SIN_RED, "sin ruta")

        override suspend fun descargar(
            url: String,
            destino: File,
            desdeBytes: Long,
            alProgresar: (escritos: Long, totales: Long?) -> Unit,
        ): ResultadoDescargaHttp = ResultadoDescargaHttp.Fallo(FalloHttp.IO, "conexion interrumpida", reintentable = false)
    }

    private class VerificadorFalso(
        private val resultado: ResultadoVerificacionApk,
    ) : VerificadorApk {
        override fun verificar(
            archivo: File,
            manifiesto: ManifiestoActualizacion,
        ): ResultadoVerificacionApk = resultado
    }

    private class NotificadorFalso : NotificadorOta {
        var disponible = false
        var listo = false

        override fun versionDisponible(manifiesto: ManifiestoActualizacion) {
            disponible = true
        }

        override fun progreso(
            manifiesto: ManifiestoActualizacion,
            escritos: Long,
            totales: Long,
        ) = Unit

        override fun listaParaInstalar(manifiesto: ManifiestoActualizacion) {
            listo = true
        }

        override fun olvidarProgreso() = Unit

        override fun limpiarTodo() = Unit
    }

    private class AlmacenFalso : AlmacenManifiestoOta {
        override fun canal(): Flow<CanalActualizacion> = flowOf(CanalActualizacion.ESTABLE)

        override suspend fun canalActual(): CanalActualizacion = CanalActualizacion.ESTABLE

        override suspend fun fijarCanal(canal: CanalActualizacion) = Unit

        override fun ultimaComprobacionMs(): Flow<Long?> = flowOf(1_000L)

        override suspend fun marcarComprobacion(instanteMs: Long) = Unit

        override suspend fun guardarComprobacion(
            verificado: ManifiestoVerificado,
            instanteMs: Long,
        ) = Unit

        override suspend fun cargarManifiesto(): ManifiestoGuardado? = null

        override suspend fun olvidarManifiesto() = Unit

        override fun versionOmitida(): Flow<Long?> = flowOf(null)

        override suspend fun omitirVersion(versionCode: Long) = Unit

        override suspend fun olvidarVersionOmitida() = Unit
    }

    private companion object {
        const val PAQUETE = "com.copernicotv.app"
        val HUELLA = "c".repeat(64)
        val CONFIGURACION = ConfiguracionOta("guigerdts/CoperNicoTV", PAQUETE, "clave", HUELLA)
    }
}
