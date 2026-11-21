package com.copernicotv.app.ota

import com.copernicotv.core.ota.CanalActualizacion
import com.copernicotv.core.ota.Integridad
import com.copernicotv.core.ota.ManifiestoActualizacion
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

/** Reglas puras que deciden si el APK descargado es el que declara el manifiesto. */
class ReglasVerificacionApkTest {
    @Test
    fun `acepta el apk con paquete version minSdk y certificado correctos`() {
        val resultado = verificar(info())

        assertEquals(ResultadoVerificacionApk.Valido, resultado)
    }

    @Test
    fun `rechaza un paquete distinto`() {
        val resultado = verificar(info(paquete = "com.otro.app"))

        assertEquals(ResultadoVerificacionApk.Fallo(FalloVerificacionApk.PAQUETE, "com.otro.app"), resultado)
    }

    @Test
    fun `rechaza un versionCode distinto al del manifiesto`() {
        val resultado = verificar(info(versionCode = 3))

        assertEquals(ResultadoVerificacionApk.Fallo(FalloVerificacionApk.VERSION_CODE, "3"), resultado)
    }

    @Test
    fun `rechaza un apk que pide un android mas nuevo`() {
        val resultado = verificar(info(minSdk = 34), sdkDispositivo = 30)

        assertEquals(ResultadoVerificacionApk.Fallo(FalloVerificacionApk.MIN_SDK, "34"), resultado)
    }

    @Test
    fun `rechaza un certificado no fijado aunque sea valido`() {
        val otra = "e".repeat(64)
        val resultado = verificar(info(huellas = listOf(otra)))

        assertEquals(ResultadoVerificacionApk.Fallo(FalloVerificacionApk.FIRMA), resultado)
    }

    @Test
    fun `compara la huella sin distinguir mayusculas`() {
        val resultado = verificar(info(huellas = listOf(HUELLA.uppercase())))

        assertEquals(ResultadoVerificacionApk.Valido, resultado)
    }

    private fun verificar(
        info: InfoApk,
        sdkDispositivo: Int = 30,
    ): ResultadoVerificacionApk = ReglasVerificacionApk.verificar(info, manifiesto(), sdkDispositivo)

    private fun info(
        paquete: String = PAQUETE,
        versionCode: Long = 2,
        minSdk: Int = 26,
        huellas: List<String> = listOf(HUELLA),
    ): InfoApk =
        InfoApk(
            paquete = paquete,
            versionCode = versionCode,
            minSdk = minSdk,
            huellasCertificado = huellas,
        )

    private fun manifiesto(): ManifiestoActualizacion =
        ManifiestoActualizacion(
            esquema = ManifiestoActualizacion.ESQUEMA_ACTUAL,
            paquete = PAQUETE,
            versionCode = 2,
            versionName = "0.2.0",
            canal = CanalActualizacion.ESTABLE,
            minSdk = 26,
            publicadoEn = Instant.parse("2026-11-20T00:00:00Z"),
            tamanoBytes = 1024,
            sha256 = Integridad.sha256Hex("apk".toByteArray()),
            urlDescarga = "https://github.com/guigerdts/CoperNicoTV/releases/download/v0.2.0/app-release.apk",
            urlRelease = "https://github.com/guigerdts/CoperNicoTV/releases/tag/v0.2.0",
            notas = "Notas",
            obligatoria = false,
            huellaFirmaApk = HUELLA,
        )

    private companion object {
        const val PAQUETE = "com.copernicotv.app"
        val HUELLA = "f".repeat(64)
    }
}
