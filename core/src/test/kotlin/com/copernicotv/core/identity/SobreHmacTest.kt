package com.copernicotv.core.identity

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class SobreHmacTest {
    private fun mac(relleno: Byte = 0x2A): ByteArray = ByteArray(32) { relleno }

    @Test
    fun `sobre versionado se construye y se interpreta`() {
        val sobre = SobreHmac.construir(3, 1, mac())
        assertEquals("hmac:v3:cv1:", sobre.take(12))
        val resultado = SobreHmac.parsear(sobre)
        assertIs<ResultadoSobre.Versionado>(resultado)
        assertEquals(3, resultado.generacion)
        assertEquals(1, resultado.versionCanonica)
        assertContentEquals(mac(), resultado.mac)
    }

    @Test
    fun `sobre legacy no se asume cv1`() {
        val base64 = Base64.getEncoder().encodeToString(mac())
        val resultado = SobreHmac.parsear("hmac:v1:$base64")
        assertIs<ResultadoSobre.Legacy>(resultado)
        assertEquals(1, resultado.generacion)
    }

    @Test
    fun `sobre malformado se rechaza`() {
        val casos =
            listOf(
                "",
                "hmac:",
                "hmac:v0:cv1:AAAA",
                "hmac:v1:cv0:AAAA",
                "hmac:v1:cv256:AAAA",
                "hmac:v1:cv1:",
                "hmac:v1:cv1:AAA",
                "HMAC:v1:cv1:AAAA",
                "hmac:v1:cv1:AAAA ",
                "hmac:v01:cv1:AAAA",
            )
        for (caso in casos) {
            assertIs<ResultadoSobre.Invalido>(SobreHmac.parsear(caso), "caso: $caso")
        }
    }

    @Test
    fun `base64 no canonico se rechaza`() {
        val base64 = Base64.getEncoder().encodeToString(mac())
        val sinPadding = base64.trimEnd('=')
        assertIs<ResultadoSobre.Invalido>(SobreHmac.parsear("hmac:v1:cv1:$sinPadding"))
    }

    @Test
    fun `longitud distinta de 32 bytes se rechaza`() {
        val corto = Base64.getEncoder().encodeToString(ByteArray(16))
        val resultado = SobreHmac.parsear("hmac:v1:cv1:$corto")
        assertIs<ResultadoSobre.Invalido>(resultado)
        assertEquals(MotivoSobre.LONGITUD_INVALIDA, resultado.motivo)
    }

    @Test
    fun `version de canonicalizacion fuera de un byte se rechaza`() {
        val base64 = Base64.getEncoder().encodeToString(mac())
        val resultado = SobreHmac.parsear("hmac:v1:cv256:$base64")
        assertIs<ResultadoSobre.Invalido>(resultado)
        assertEquals(MotivoSobre.VERSION_FUERA_DE_RANGO, resultado.motivo)
    }

    @Test
    fun `construir valida rango y longitud`() {
        assertFailsWith<IllegalArgumentException> { SobreHmac.construir(0, 1, mac()) }
        assertFailsWith<IllegalArgumentException> { SobreHmac.construir(1, 0, mac()) }
        assertFailsWith<IllegalArgumentException> { SobreHmac.construir(1, 1, ByteArray(31)) }
    }

    @Test
    fun `prefijo de redaccion no expone el hmac completo`() {
        val sobre = SobreHmac.construir(1, 1, mac())
        val prefijo = Redaccion.prefijoHmac(sobre)
        assertEquals(8, prefijo?.length)
        assertNull(Redaccion.prefijoHmac("no-es-un-sobre"))
    }

    @Test
    fun `entrada hmac tiene serializacion exacta`() {
        val url = "http://example.com/x"
        val entrada =
            EntradaHmac.serializar(
                versionCanonica = 1,
                campo = CampoUrl.CANAL_URL_STREAM,
                clase = ClaseUrl.HTTP_HTTPS,
                urlCanonica = url,
            )
        val esperado =
            concatenar(
                listOf(
                    utf8("copernicotv/url/hmac"),
                    byteArrayOf(0x00),
                    byteArrayOf(0x02),
                    byteArrayOf(0x01),
                    byteArrayOf(0x02),
                    byteArrayOf(0x01),
                    uint32Be(url.toByteArray(Charsets.UTF_8).size),
                    utf8(url),
                ),
            )
        assertContentEquals(esperado, entrada)
    }

    @Test
    fun `entrada hmac rechaza clase no admitida para el campo`() {
        assertFailsWith<IllegalArgumentException> {
            EntradaHmac.serializar(1, CampoUrl.CANAL_LOGO_URL, ClaseUrl.RTSP, "rtsp://host/x")
        }
    }

    @Test
    fun `entrada hmac rechaza version fuera de rango`() {
        assertFailsWith<IllegalArgumentException> {
            EntradaHmac.serializar(0, CampoUrl.CANAL_URL_STREAM, ClaseUrl.HTTP_HTTPS, "http://h/x")
        }
        assertFailsWith<IllegalArgumentException> {
            EntradaHmac.serializar(256, CampoUrl.CANAL_URL_STREAM, ClaseUrl.HTTP_HTTPS, "http://h/x")
        }
    }
}
