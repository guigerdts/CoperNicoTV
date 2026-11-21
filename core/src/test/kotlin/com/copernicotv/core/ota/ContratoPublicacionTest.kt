package com.copernicotv.core.ota

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Contrato completo entre el workflow de publicación y la app (S3, S11).
 *
 * El documento de abajo es el JSON que emite `.github/workflows/release.yml` con
 * `jq`, campo por campo y con el mismo formato. La app lo verifica y lo parsea
 * sin adaptaciones. Si un campo se renombra, se cambia de tipo o se reordena el
 * contrato, esta prueba falla antes de publicar.
 */
class ContratoPublicacionTest {
    @Test
    fun `el manifiesto que publica el workflow se verifica y se parsea`() {
        val par = parDeClaves()
        val documento = DOCUMENTO.toByteArray(Charsets.UTF_8)

        assertTrue(FirmaManifiesto.verificar(documento, firmar(par, documento), publica(par)))

        val resultado = ManifiestoActualizacion.parse(DOCUMENTO, PAQUETE, HUELLA)
        val manifiesto = assertIs<ResultadoManifiesto.Valido>(resultado).manifiesto
        assertEquals(ManifiestoActualizacion.ESQUEMA_ACTUAL, manifiesto.esquema)
        assertEquals(PAQUETE, manifiesto.paquete)
        assertEquals(214L, manifiesto.versionCode)
        assertEquals("1.2.0", manifiesto.versionName)
        assertEquals(CanalActualizacion.ESTABLE, manifiesto.canal)
        assertEquals(26, manifiesto.minSdk)
        assertEquals(48_231_456L, manifiesto.tamanoBytes)
        assertEquals(SHA, manifiesto.sha256)
        assertEquals(HUELLA, manifiesto.huellaFirmaApk)
        assertEquals("Corrección de reproducción\nAjustes de OTA", manifiesto.notas)
        assertEquals(
            "https://github.com/guigerdts/CoperNicoTV/releases/download/v1.2.0/copernicotv-1.2.0.apk",
            manifiesto.urlDescarga,
        )
        assertFalse(manifiesto.obligatoria)
    }

    @Test
    fun `alterar un byte del documento publicado invalida la firma`() {
        val par = parDeClaves()
        val firma = firmar(par, DOCUMENTO.toByteArray(Charsets.UTF_8))
        val alterado = DOCUMENTO.replace("\"versionCode\": 214", "\"versionCode\": 215")

        assertFalse(FirmaManifiesto.verificar(alterado.toByteArray(Charsets.UTF_8), firma, publica(par)))
        assertTrue(FirmaManifiesto.verificar(DOCUMENTO.toByteArray(Charsets.UTF_8), firma, publica(par)))
    }

    @Test
    fun `renombrar un campo rompe el contrato en vez de inventar un valor`() {
        val renombrado = DOCUMENTO.replace("\"sizeBytes\"", "\"tamano\"")

        val resultado = ManifiestoActualizacion.parse(renombrado, PAQUETE, HUELLA)

        assertEquals(MotivoManifiesto.CAMPO_FALTANTE, assertIs<ResultadoManifiesto.Invalido>(resultado).motivo)
    }

    @Test
    fun `un canal beta publicado se identifica como beta`() {
        val beta = DOCUMENTO.replace("\"channel\": \"stable\"", "\"channel\": \"beta\"")
        val resultado = ManifiestoActualizacion.parse(beta, PAQUETE, HUELLA)

        val manifiesto = assertIs<ResultadoManifiesto.Valido>(resultado).manifiesto
        assertEquals(CanalActualizacion.BETA, manifiesto.canal)
    }

    private fun parDeClaves(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()

    private fun firmar(
        par: KeyPair,
        documento: ByteArray,
    ): ByteArray =
        Signature.getInstance("SHA256withECDSA").run {
            initSign(par.private)
            update(documento)
            sign()
        }

    private fun publica(par: KeyPair): String = Base64.getEncoder().encodeToString(par.public.encoded)

    private companion object {
        const val PAQUETE = "com.copernicotv.app"
        const val SHA = "a3f1c9d27b5e4c8a1f6d0b3e9c7a2d5f8b1e4c7a0d3f6b9e2c5a8d1f4b7e0c3a"
        const val HUELLA = "0f2d4b6a8c1e3f5b7d9a0c2e4f6b8d1a3c5e7f9b0d2a4c6e8f1b3d5a7c9e0f2b"

        /** Salida real de `jq -n` en el paso "Componer y firmar el manifiesto". */
        const val DOCUMENTO = """{
  "schema": 1,
  "packageName": "com.copernicotv.app",
  "versionCode": 214,
  "versionName": "1.2.0",
  "channel": "stable",
  "minSdk": 26,
  "publishedAt": "2026-11-21T12:00:00Z",
  "sizeBytes": 48231456,
  "sha256": "$SHA",
  "downloadUrl": "https://github.com/guigerdts/CoperNicoTV/releases/download/v1.2.0/copernicotv-1.2.0.apk",
  "releaseUrl": "https://github.com/guigerdts/CoperNicoTV/releases/tag/v1.2.0",
  "releaseNotes": "Corrección de reproducción\nAjustes de OTA",
  "mandatory": false,
  "signingCertSha256": "$HUELLA"
}"""
    }
}
