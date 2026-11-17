package com.copernicotv.app

import android.content.res.AssetManager

/**
 * Fixtures de la matriz de decodificación software (E7-T12, Fase C).
 *
 * Generados con FFmpeg local (`testsrc` + `sine`, tres segundos): MPEG-2
 * Video + MP2 en MPEG-TS, MPEG-4 Part 2 + AAC en MP4 y H.263 + AAC en 3GP.
 * La regresión H.264 reutiliza [FixturesF19.PISTAS], que ya es `h264`.
 */
internal object FixturesE7T12 {
    const val MPEG2 = "e7t12/mpeg2.ts"

    const val MPEG4 = "e7t12/mpeg4.mp4"

    const val H263 = "e7t12/h263.3gp"

    fun verificar(assets: AssetManager) {
        listOf(MPEG2, MPEG4, H263).forEach { ruta ->
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
