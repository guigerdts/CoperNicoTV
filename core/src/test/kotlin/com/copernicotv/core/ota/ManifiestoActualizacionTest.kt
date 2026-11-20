package com.copernicotv.core.ota

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Contrato del manifiesto OTA (S2, S4).
 *
 * El manifiesto es la frontera entre el workflow de publicación y la app: un
 * documento incompleto, con tipos dudosos o con una identidad de firma distinta
 * a la fijada no se corrige por inferencia, se rechaza.
 */
class ManifiestoActualizacionTest {
    @Test
    fun `un manifiesto completo y valido se convierte en modelo`() {
        val resultado = ManifiestoActualizacion.parse(json(), PAQUETE, HUELLA)

        val manifiesto = assertIs<ResultadoManifiesto.Valido>(resultado).manifiesto
        assertEquals(1, manifiesto.esquema)
        assertEquals(PAQUETE, manifiesto.paquete)
        assertEquals(2L, manifiesto.versionCode)
        assertEquals("0.2.0", manifiesto.versionName)
        assertEquals(CanalActualizacion.ESTABLE, manifiesto.canal)
        assertEquals(26, manifiesto.minSdk)
        assertEquals(1024L, manifiesto.tamanoBytes)
        assertEquals(SHA, manifiesto.sha256)
        assertEquals(HUELLA, manifiesto.huellaFirmaApk)
        assertEquals("Correcciones de la actualización", manifiesto.notas)
    }

    @Test
    fun `un json malformado se rechaza`() {
        assertInvalido("{\"schema\":", MotivoManifiesto.JSON_MALFORMADO)
        assertInvalido("", MotivoManifiesto.JSON_MALFORMADO)
        assertInvalido("[]", MotivoManifiesto.JSON_MALFORMADO)
    }

    @Test
    fun `un campo faltante se rechaza con su motivo`() {
        val sinHash = camposValidos().filterNot { it.first == "sha256" }
        assertEquals(MotivoManifiesto.CAMPO_FALTANTE, motivo(json(sinHash)))
    }

    @Test
    fun `un tipo incorrecto se rechaza aunque el campo exista`() {
        val versionComoTexto = camposValidos().map { if (it.first == "versionCode") it.first to "\"2\"" else it }
        assertEquals(MotivoManifiesto.TIPO_INVALIDO, motivo(json(versionComoTexto)))
    }

    @Test
    fun `un esquema desconocido no se interpreta`() {
        assertEquals(MotivoManifiesto.ESQUEMA_NO_SOPORTADO, motivo(json(camposValidos().map { cambiar(it, "schema", "2") })))
    }

    @Test
    fun `un paquete distinto al instalado se rechaza`() {
        assertEquals(MotivoManifiesto.PAQUETE_INCORRECTO, motivo(json(camposValidos().map { cambiar(it, "packageName", "\"otra.app\"") })))
    }

    @Test
    fun `un versionCode no positivo se rechaza`() {
        assertEquals(MotivoManifiesto.VERSION_INVALIDA, motivo(json(camposValidos().map { cambiar(it, "versionCode", "0") })))
    }

    @Test
    fun `un versionName que no es version se rechaza`() {
        assertEquals(MotivoManifiesto.VERSION_INVALIDA, motivo(json(camposValidos().map { cambiar(it, "versionName", "\"ultima\"") })))
    }

    @Test
    fun `un canal desconocido se rechaza`() {
        assertEquals(MotivoManifiesto.CANAL_INVALIDO, motivo(json(camposValidos().map { cambiar(it, "channel", "\"nightly\"") })))
    }

    @Test
    fun `una fecha que no es iso 8601 se rechaza`() {
        assertEquals(MotivoManifiesto.FECHA_INVALIDA, motivo(json(camposValidos().map { cambiar(it, "publishedAt", "\"ayer\"") })))
    }

    @Test
    fun `un tamano no positivo o desmedido se rechaza`() {
        assertEquals(MotivoManifiesto.TAMANO_INVALIDO, motivo(json(camposValidos().map { cambiar(it, "sizeBytes", "0") })))
        assertEquals(
            MotivoManifiesto.TAMANO_INVALIDO,
            motivo(json(camposValidos().map { cambiar(it, "sizeBytes", (ManifiestoActualizacion.MAX_BYTES_APK + 1).toString()) })),
        )
    }

    @Test
    fun `un hash que no es sha256 hexadecimal se rechaza`() {
        assertEquals(MotivoManifiesto.HASH_INVALIDO, motivo(json(camposValidos().map { cambiar(it, "sha256", "\"abc\"") })))
        assertEquals(MotivoManifiesto.HASH_INVALIDO, motivo(json(camposValidos().map { cambiar(it, "sha256", "\"${"z".repeat(64)}\"") })))
    }

    @Test
    fun `una url que no es https de github se rechaza`() {
        val http = camposValidos().map { cambiar(it, "downloadUrl", "\"http://github.com/a.apk\"") }
        assertEquals(MotivoManifiesto.URL_INVALIDA, motivo(json(http)))

        val ajeno = camposValidos().map { cambiar(it, "downloadUrl", "\"https://example.com/a.apk\"") }
        assertEquals(MotivoManifiesto.URL_INVALIDA, motivo(json(ajeno)))

        val credenciales = camposValidos().map { cambiar(it, "downloadUrl", "\"https://usuario:clave@github.com/a.apk\"") }
        assertEquals(MotivoManifiesto.URL_INVALIDA, motivo(json(credenciales)))
    }

    @Test
    fun `un release url que no es github se rechaza`() {
        val ajeno = camposValidos().map { cambiar(it, "releaseUrl", "\"https://example.com/release\"") }
        assertEquals(MotivoManifiesto.URL_INVALIDA, motivo(json(ajeno)))
    }

    @Test
    fun `unas notas desmedidas se rechazan`() {
        val notas =
            "\"${"x".repeat(ManifiestoActualizacion.MAX_CARACTERES_NOTAS + 1)}\""
        val largas = camposValidos().map { cambiar(it, "releaseNotes", notas) }
        assertEquals(MotivoManifiesto.NOTAS_INVALIDAS, motivo(json(largas)))
    }

    @Test
    fun `una huella de firma distinta a la fijada se rechaza`() {
        val otra = camposValidos().map { cambiar(it, "signingCertSha256", "\"${"b".repeat(64)}\"") }
        assertEquals(MotivoManifiesto.FIRMA_APK_NO_COINCIDE, motivo(json(otra)))
    }

    @Test
    fun `los campos extra se ignoran`() {
        val conExtra = camposValidos() + ("campoNuevo" to "\"ignorado\"")
        assertIs<ResultadoManifiesto.Valido>(ManifiestoActualizacion.parse(json(conExtra), PAQUETE, HUELLA))
    }

    private fun assertInvalido(
        texto: String,
        esperado: MotivoManifiesto,
    ) {
        assertEquals(esperado, motivo(texto))
    }

    private fun motivo(texto: String): MotivoManifiesto =
        assertIs<ResultadoManifiesto.Invalido>(ManifiestoActualizacion.parse(texto, PAQUETE, HUELLA)).motivo

    private fun cambiar(
        campo: Pair<String, String>,
        nombre: String,
        valor: String,
    ): Pair<String, String> = if (campo.first == nombre) nombre to valor else campo

    private fun json(campos: List<Pair<String, String>> = camposValidos()): String =
        campos.joinToString(prefix = "{", postfix = "}") { (nombre, valor) -> "\"$nombre\":$valor" }

    private fun camposValidos(): List<Pair<String, String>> =
        listOf(
            "schema" to "1",
            "packageName" to "\"$PAQUETE\"",
            "versionCode" to "2",
            "versionName" to "\"0.2.0\"",
            "channel" to "\"stable\"",
            "minSdk" to "26",
            "publishedAt" to "\"2026-11-20T18:00:00Z\"",
            "sizeBytes" to "1024",
            "sha256" to "\"$SHA\"",
            "downloadUrl" to
                "\"https://github.com/guigerdts/CoperNicoTV/releases/download/v0.2.0/app-release.apk\"",
            "releaseUrl" to "\"https://github.com/guigerdts/CoperNicoTV/releases/tag/v0.2.0\"",
            "releaseNotes" to "\"Correcciones de la actualización\"",
            "mandatory" to "false",
            "signingCertSha256" to "\"$HUELLA\"",
        )

    private companion object {
        const val PAQUETE = "com.copernicotv.app"
        const val SHA = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        const val HUELLA = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210"
    }
}
