package com.copernicotv.app.ota

import com.copernicotv.core.ota.CanalActualizacion
import com.copernicotv.core.ota.Integridad
import com.copernicotv.core.ota.ManifiestoActualizacion
import com.copernicotv.core.ota.VersionInstalada
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Decisión de la comprobación: qué se ofrece, qué se reporta como al día y qué
 * se persiste. La autenticidad ya la resolvió la fuente; acá se prueban las
 * reglas de canal, versión, compatibilidad y versión omitida.
 */
class ComprobadorActualizacionesTest {
    @Test
    fun `ofrece la version nueva del canal y guarda el manifiesto verificado`() {
        val manifiesto = manifiesto(versionCode = 2)
        val almacen = AlmacenFalso()

        val resultado = comprobar(FuenteFalsa(ResultadoFuente.Manifiestos(listOf(verificado(manifiesto)))), almacen)

        assertEquals(ResultadoComprobacion.Disponible(manifiesto), resultado)
        assertEquals(2L, almacen.guardado?.manifiesto?.versionCode)
        assertTrue(almacen.marcas >= 1)
    }

    @Test
    fun `esta al dia cuando la publicada no supera la instalada`() {
        val almacen = AlmacenFalso()

        val resultado = comprobar(FuenteFalsa(ResultadoFuente.Manifiestos(listOf(verificado(manifiesto(versionCode = 1))))), almacen)

        assertEquals(ResultadoComprobacion.AlDia, resultado)
        assertEquals(null, almacen.guardado)
        assertTrue(almacen.marcas >= 1)
    }

    @Test
    fun `reporta incompatible sin descartar el candidato`() {
        val manifiesto = manifiesto(versionCode = 2, minSdk = 34)

        val resultado = comprobar(FuenteFalsa(ResultadoFuente.Manifiestos(listOf(verificado(manifiesto)))), AlmacenFalso())

        assertEquals(ResultadoComprobacion.Incompatible(manifiesto), resultado)
    }

    @Test
    fun `el canal beta recibe una beta mas nueva`() {
        val manifiesto = manifiesto(versionCode = 3, canal = CanalActualizacion.BETA)
        val almacen = AlmacenFalso(canal = CanalActualizacion.BETA)

        val resultado = comprobar(FuenteFalsa(ResultadoFuente.Manifiestos(listOf(verificado(manifiesto)))), almacen)

        assertEquals(ResultadoComprobacion.Disponible(manifiesto), resultado)
    }

    @Test
    fun `el canal estable ignora una beta`() {
        val manifiesto = manifiesto(versionCode = 3, canal = CanalActualizacion.BETA)

        val resultado = comprobar(FuenteFalsa(ResultadoFuente.Manifiestos(listOf(verificado(manifiesto)))), AlmacenFalso())

        assertEquals(ResultadoComprobacion.AlDia, resultado)
    }

    @Test
    fun `una version omitida no se vuelve a ofrecer`() {
        val almacen = AlmacenFalso(omitida = 2L)

        val resultado = comprobar(FuenteFalsa(ResultadoFuente.Manifiestos(listOf(verificado(manifiesto(versionCode = 2))))), almacen)

        assertEquals(ResultadoComprobacion.AlDia, resultado)
    }

    @Test
    fun `una actualizacion obligatoria ignora la version omitida`() {
        val manifiesto = manifiesto(versionCode = 2, obligatoria = true)
        val almacen = AlmacenFalso(omitida = 2L)

        val resultado = comprobar(FuenteFalsa(ResultadoFuente.Manifiestos(listOf(verificado(manifiesto)))), almacen)

        assertEquals(ResultadoComprobacion.Disponible(manifiesto), resultado)
    }

    @Test
    fun `sin configuracion falla cerrado`() {
        val configuracion = CONFIGURACION.copy(clavePublicaManifiesto = "")

        val resultado = comprobar(FuenteFalsa(ResultadoFuente.SinPublicaciones), AlmacenFalso(), configuracion)

        assertTrue(resultado is ResultadoComprobacion.Fallo)
        assertEquals(FalloComprobacion.CONFIGURACION, (resultado as ResultadoComprobacion.Fallo).tipo)
    }

    @Test
    fun `propaga el fallo de red de la fuente`() {
        val resultado = comprobar(FuenteFalsa(ResultadoFuente.Fallo(FalloFuente.SIN_RED)), AlmacenFalso())

        assertTrue(resultado is ResultadoComprobacion.Fallo)
        assertEquals(FalloComprobacion.SIN_RED, (resultado as ResultadoComprobacion.Fallo).tipo)
    }

    @Test
    fun `sin publicaciones se reporta al dia`() {
        val resultado = comprobar(FuenteFalsa(ResultadoFuente.SinPublicaciones), AlmacenFalso())

        assertEquals(ResultadoComprobacion.AlDia, resultado)
    }

    @Test
    fun `un fallo de la fuente no se reporta como al dia ni marca la comprobacion`() {
        listOf(
            FalloFuente.SIN_RED to FalloComprobacion.SIN_RED,
            FalloFuente.SERVIDOR to FalloComprobacion.SERVIDOR,
            FalloFuente.FIRMA_INVALIDA to FalloComprobacion.FIRMA_INVALIDA,
            FalloFuente.RESPUESTA_INVALIDA to FalloComprobacion.MANIFIESTO_INVALIDO,
        ).forEach { (falloFuente, esperado) ->
            val almacen = AlmacenFalso()

            val resultado = comprobar(FuenteFalsa(ResultadoFuente.Fallo(falloFuente)), almacen)

            assertEquals("clase para $falloFuente", esperado, (resultado as ResultadoComprobacion.Fallo).tipo)
            assertEquals("no debe marcar comprobación para $falloFuente", 0, almacen.marcas)
        }
    }

    private fun comprobar(
        fuente: FuenteManifiestos,
        almacen: AlmacenManifiestoOta,
        configuracion: ConfiguracionOta = CONFIGURACION,
    ): ResultadoComprobacion =
        runBlocking {
            ComprobadorActualizaciones(
                fuente = fuente,
                almacen = almacen,
                configuracion = configuracion,
                versionInstalada = VersionInstalada(1, "0.1.0"),
                sdkInt = 30,
                ahora = { 1_000L },
            ).comprobar()
        }

    private fun verificado(manifiesto: ManifiestoActualizacion): ManifiestoVerificado =
        ManifiestoVerificado(manifiesto, ByteArray(0), ByteArray(0))

    private fun manifiesto(
        versionCode: Long,
        canal: CanalActualizacion = CanalActualizacion.ESTABLE,
        minSdk: Int = 26,
        obligatoria: Boolean = false,
    ): ManifiestoActualizacion =
        ManifiestoActualizacion(
            esquema = ManifiestoActualizacion.ESQUEMA_ACTUAL,
            paquete = PAQUETE,
            versionCode = versionCode,
            versionName = "0.$versionCode.0",
            canal = canal,
            minSdk = minSdk,
            publicadoEn = Instant.parse("2026-11-20T00:00:00Z"),
            tamanoBytes = 1024,
            sha256 = Integridad.sha256Hex("apk".toByteArray()),
            urlDescarga = "https://github.com/guigerdts/CoperNicoTV/releases/download/v0.2.0/app-release.apk",
            urlRelease = "https://github.com/guigerdts/CoperNicoTV/releases/tag/v0.2.0",
            notas = "Notas",
            obligatoria = obligatoria,
            huellaFirmaApk = HUELLA,
        )

    private class FuenteFalsa(
        private val resultado: ResultadoFuente,
    ) : FuenteManifiestos {
        override suspend fun buscar(): ResultadoFuente = resultado
    }

    private class AlmacenFalso(
        var canal: CanalActualizacion = CanalActualizacion.ESTABLE,
        var omitida: Long? = null,
    ) : AlmacenManifiestoOta {
        var guardado: ManifiestoVerificado? = null
        var marcas = 0

        override fun canal(): Flow<CanalActualizacion> = flowOf(canal)

        override suspend fun canalActual(): CanalActualizacion = canal

        override suspend fun fijarCanal(canal: CanalActualizacion) {
            this.canal = canal
        }

        override fun ultimaComprobacionMs(): Flow<Long?> = flowOf(null)

        override suspend fun marcarComprobacion(instanteMs: Long) {
            marcas++
        }

        override suspend fun guardarComprobacion(
            verificado: ManifiestoVerificado,
            instanteMs: Long,
        ) {
            guardado = verificado
            marcas++
        }

        override suspend fun cargarManifiesto(): ManifiestoGuardado? = null

        override suspend fun olvidarManifiesto() = Unit

        override fun versionOmitida(): Flow<Long?> = flowOf(omitida)

        override suspend fun omitirVersion(versionCode: Long) {
            omitida = versionCode
        }

        override suspend fun olvidarVersionOmitida() {
            omitida = null
        }
    }

    private companion object {
        const val PAQUETE = "com.copernicotv.app"
        val HUELLA = "c".repeat(64)
        val CONFIGURACION = ConfiguracionOta("guigerdts/CoperNicoTV", PAQUETE, "clave", HUELLA)
    }
}
