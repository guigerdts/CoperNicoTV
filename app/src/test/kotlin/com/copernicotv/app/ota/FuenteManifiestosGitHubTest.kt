package com.copernicotv.app.ota

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * Fuente de manifiestos: descubre releases, pero solo acepta los que tienen
 * firma válida con la clave embebida y hablan de este paquete. Todo lo demás se
 * ignora sin romper la comprobación.
 */
class FuenteManifiestosGitHubTest {
    @Test
    fun `devuelve el manifiesto firmado con sus bytes originales`() {
        val par = parDeClaves()
        val json = manifiestoJson()
        val firma = firmar(json, par)
        val cliente = clienteCon(listOf(release()), json, firma)

        val resultado = runBlocking { fuente(cliente, par.public).buscar() }

        assertTrue(resultado is ResultadoFuente.Manifiestos)
        val verificado = (resultado as ResultadoFuente.Manifiestos).verificados.single()
        assertEquals(2L, verificado.manifiesto.versionCode)
        assertEquals(json.toList(), verificado.json.toList())
        assertEquals(firma.toList(), verificado.firma.toList())
    }

    @Test
    fun `una firma de otra clave no se acepta`() {
        val ajeno = parDeClaves()
        val propio = parDeClaves()
        val json = manifiestoJson()
        val cliente = clienteCon(listOf(release()), json, firmar(json, ajeno))

        val resultado = runBlocking { fuente(cliente, propio.public).buscar() }

        assertEquals(ResultadoFuente.Fallo(FalloFuente.FIRMA_INVALIDA), resultado)
    }

    @Test
    fun `un release en borrador no se considera publicado`() {
        val par = parDeClaves()
        val json = manifiestoJson()
        val cliente = clienteCon(listOf(release(borrador = true)), json, firmar(json, par))

        val resultado = runBlocking { fuente(cliente, par.public).buscar() }

        assertEquals(ResultadoFuente.SinPublicaciones, resultado)
    }

    @Test
    fun `una publicacion sin activos no aporta manifiesto`() {
        val par = parDeClaves()

        val resultado =
            runBlocking {
                fuente(
                    ClienteMapa(mapOf(URL_API to releasesJson(release(sinActivos = true)))),
                    par.public,
                ).buscar()
            }

        assertEquals(ResultadoFuente.Fallo(FalloFuente.RESPUESTA_INVALIDA), resultado)
    }

    @Test
    fun `un manifiesto de otro paquete no se acepta aunque la firma sea valida`() {
        val par = parDeClaves()
        val json = manifiestoJson(paquete = "com.otro.app")
        val cliente = clienteCon(listOf(release()), json, firmar(json, par))

        val resultado = runBlocking { fuente(cliente, par.public).buscar() }

        assertEquals(ResultadoFuente.Fallo(FalloFuente.RESPUESTA_INVALIDA), resultado)
    }

    @Test
    fun `sin configuracion falla cerrado sin consultar la red`() {
        val cliente = ClienteMapa(emptyMap())
        val configuracion = configuracion(parDeClaves().public).copy(clavePublicaManifiesto = "")

        val resultado = runBlocking { FuenteManifiestosGitHub(cliente, configuracion).buscar() }

        assertEquals(ResultadoFuente.Fallo(FalloFuente.CONFIGURACION), resultado)
    }

    @Test
    fun `una lista de releases malformada se reporta como respuesta invalida`() {
        val par = parDeClaves()
        val cliente = ClienteMapa(mapOf(URL_API to "no es json".toByteArray()))

        val resultado = runBlocking { fuente(cliente, par.public).buscar() }

        assertEquals(ResultadoFuente.Fallo(FalloFuente.RESPUESTA_INVALIDA), resultado)
    }

    @Test
    fun `un error de servidor se reporta como servidor`() {
        val par = parDeClaves()
        val cliente = ClienteMapa(emptyMap())

        val resultado = runBlocking { fuente(cliente, par.public).buscar() }

        assertEquals(ResultadoFuente.Fallo(FalloFuente.SERVIDOR, "HTTP 404"), resultado)
    }

    @Test
    fun `devuelve todas las publicaciones validas para que el canal decida`() {
        val par = parDeClaves()
        val estable = manifiestoJson(versionCode = 2)
        val beta = manifiestoJson(versionCode = 3, canal = "beta")
        val firmaEstable = firmar(estable, par)
        val firmaBeta = firmar(beta, par)
        val publicaciones =
            releasesJson(
                release(),
                release(etiqueta = "v0.3.0-beta", jsonUrl = URL_JSON_BETA, firmaUrl = URL_FIRMA_BETA, preliminar = true),
            )
        val cliente =
            ClienteMapa(
                mapOf(
                    URL_API to publicaciones,
                    URL_JSON to estable,
                    URL_FIRMA to firmaEstable,
                    URL_JSON_BETA to beta,
                    URL_FIRMA_BETA to firmaBeta,
                ),
            )

        val resultado = runBlocking { fuente(cliente, par.public).buscar() }

        assertTrue(resultado is ResultadoFuente.Manifiestos)
        assertEquals(listOf(2L, 3L), (resultado as ResultadoFuente.Manifiestos).verificados.map { it.manifiesto.versionCode })
    }

    private fun fuente(
        cliente: ClienteHttpOta,
        clave: PublicKey,
    ): FuenteManifiestosGitHub = FuenteManifiestosGitHub(cliente, configuracion(clave))

    private fun clienteCon(
        publicaciones: List<ReleasePrueba>,
        json: String,
        firma: ByteArray,
    ): ClienteMapa =
        ClienteMapa(
            mapOf(
                URL_API to releasesJson(*publicaciones.toTypedArray()),
                URL_JSON to json.toByteArray(),
                URL_FIRMA to firma,
            ),
        )

    private fun configuracion(clave: PublicKey): ConfiguracionOta =
        ConfiguracionOta(
            repositorio = REPOSITORIO,
            paquete = PAQUETE,
            clavePublicaManifiesto = Base64.getEncoder().encodeToString(clave.encoded),
            huellaFirmaApk = HUELLA,
        )

    private fun parDeClaves(): KeyPair =
        KeyPairGenerator
            .getInstance("EC")
            .apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair()

    private fun firmar(
        json: String,
        par: KeyPair,
    ): ByteArray =
        Signature
            .getInstance("SHA256withECDSA")
            .apply {
                initSign(par.private)
                update(json.toByteArray())
            }.sign()

    private fun manifiestoJson(
        versionCode: Long = 2,
        canal: String = "stable",
        paquete: String = PAQUETE,
    ): String =
        """
        {"schema":1,"packageName":"$paquete","versionCode":$versionCode,"versionName":"0.$versionCode.0",
        "channel":"$canal","minSdk":26,"publishedAt":"2026-11-20T00:00:00Z","sizeBytes":1024,
        "sha256":"${"a".repeat(64)}",
        "downloadUrl":"https://github.com/$REPOSITORIO/releases/download/v0.2.0/app-release.apk",
        "releaseUrl":"https://github.com/$REPOSITORIO/releases/tag/v0.2.0",
        "releaseNotes":"Notas","mandatory":false,"signingCertSha256":"$HUELLA"}
        """.trimIndent()

    private fun release(
        borrador: Boolean = false,
        preliminar: Boolean = false,
        etiqueta: String = "v0.2.0",
        jsonUrl: String = URL_JSON,
        firmaUrl: String = URL_FIRMA,
        sinActivos: Boolean = false,
    ): ReleasePrueba =
        ReleasePrueba(
            etiqueta = etiqueta,
            borrador = borrador,
            preliminar = preliminar,
            activos =
                if (sinActivos) {
                    emptyList()
                } else {
                    listOf("update.json" to jsonUrl, "update.json.sig" to firmaUrl)
                },
        )

    private fun releasesJson(vararg releases: ReleasePrueba): ByteArray {
        val cuerpo =
            releases.joinToString(",") { release ->
                val activos =
                    release.activos.joinToString(",") { (nombre, url) ->
                        """{"name":"$nombre","browser_download_url":"$url"}"""
                    }
                """{"tag_name":"${release.etiqueta}","draft":${release.borrador},"prerelease":${release.preliminar},""" +
                    """"html_url":"https://github.com/$REPOSITORIO/releases/tag/${release.etiqueta}","assets":[$activos]}"""
            }
        return "[$cuerpo]".toByteArray()
    }

    private data class ReleasePrueba(
        val etiqueta: String,
        val borrador: Boolean,
        val preliminar: Boolean,
        val activos: List<Pair<String, String>>,
    )

    private class ClienteMapa(
        private val respuestas: Map<String, ByteArray>,
    ) : ClienteHttpOta {
        override suspend fun obtener(
            url: String,
            maxBytes: Int,
        ): ResultadoHttp {
            if (url == URL_API && respuestas[URL_API] == null) {
                return ResultadoHttp.Fallo(FalloHttp.ESTADO_HTTP, "HTTP 404")
            }
            val cuerpo = respuestas[url] ?: return ResultadoHttp.Fallo(FalloHttp.ESTADO_HTTP, "HTTP 404")
            return ResultadoHttp.Exito(cuerpo, url)
        }

        override suspend fun descargar(
            url: String,
            destino: File,
            desdeBytes: Long,
            alProgresar: (Long, Long?) -> Unit,
        ): ResultadoDescargaHttp = ResultadoDescargaHttp.Fallo(FalloHttp.IO)
    }

    private companion object {
        const val REPOSITORIO = "guigerdts/CoperNicoTV"
        const val PAQUETE = "com.copernicotv.app"
        val HUELLA = "d".repeat(64)
        const val URL_API = "https://api.github.com/repos/guigerdts/CoperNicoTV/releases?per_page=20"
        const val URL_JSON = "https://github.com/guigerdts/CoperNicoTV/releases/download/v0.2.0/update.json"
        const val URL_FIRMA = "https://github.com/guigerdts/CoperNicoTV/releases/download/v0.2.0/update.json.sig"
        const val URL_JSON_BETA = "https://github.com/guigerdts/CoperNicoTV/releases/download/v0.3.0-beta/update.json"
        const val URL_FIRMA_BETA = "https://github.com/guigerdts/CoperNicoTV/releases/download/v0.3.0-beta/update.json.sig"
    }
}
