package com.copernicotv.core.identity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class UrlCanonicaTest {
    private fun canonizar(
        url: String?,
        campo: CampoUrl = CampoUrl.CANAL_URL_STREAM,
    ): ResultadoCanonizacion = CanonicalizadorUrl.canonizar(url, campo)

    private fun ok(
        url: String,
        campo: CampoUrl = CampoUrl.CANAL_URL_STREAM,
    ): String {
        val resultado = canonizar(url, campo)
        assertIs<ResultadoCanonizacion.Ok>(resultado, "se esperaba URL válida para $url")
        return resultado.urlCanonica
    }

    private fun error(
        url: String?,
        campo: CampoUrl = CampoUrl.CANAL_URL_STREAM,
    ): ErrorUrl {
        val resultado = canonizar(url, campo)
        assertIs<ResultadoCanonizacion.Rechazada>(resultado)
        return resultado.error
    }

    @Test
    fun `esquema y host en minusculas`() {
        assertEquals("http://example.com/ruta", ok("HTTP://Example.COM/ruta"))
        assertEquals("https://example.com/ruta", ok("HtTpS://EXAMPLE.com/ruta"))
    }

    @Test
    fun `puerto predeterminado se elimina y otro se conserva`() {
        assertEquals("http://example.com/", ok("http://example.com:80/"))
        assertEquals("https://example.com/", ok("https://example.com:443/"))
        assertEquals("http://example.com:8080/", ok("http://example.com:8080/"))
        assertEquals("http://example.com:0/", ok("http://example.com:00000/"))
        assertEquals("http://example.com:8080/", ok("http://example.com:08080/"))
    }

    @Test
    fun `path vacio se representa como barra`() {
        assertEquals("http://example.com/", ok("http://example.com"))
        assertEquals("http://example.com/", ok("http://example.com?x=1").substringBefore('?'))
    }

    @Test
    fun `barra final y segmentos se conservan sin resolver`() {
        assertEquals("http://example.com/a/./b/../c//d/", ok("http://example.com/a/./b/../c//d/"))
    }

    @Test
    fun `percent encoding se normaliza solo en no reservados`() {
        assertEquals("http://example.com/~user/%2F%3F%23%25", ok("http://example.com/%7euser/%2f%3f%23%25"))
        assertEquals("http://example.com/A", ok("http://example.com/%41"))
    }

    @Test
    fun `percent encoding invalido rechaza la url`() {
        assertEquals(CodigoErrorUrl.URL_PERCENT_ENCODING_INVALIDO, error("http://example.com/%2").codigo)
        assertEquals(CodigoErrorUrl.URL_PERCENT_ENCODING_INVALIDO, error("http://example.com/%ZZ").codigo)
    }

    @Test
    fun `query conserva orden duplicados vacios y parametros sin igual`() {
        assertEquals(
            "http://example.com/?b=2&a=1&a=1&vacio=&flag",
            ok("http://example.com/?b=2&a=1&a=1&vacio=&flag"),
        )
    }

    @Test
    fun `query y fragmento vacios presentes se conservan`() {
        assertEquals("http://example.com/?#", ok("http://example.com/?#"))
        assertEquals("http://example.com/?", ok("http://example.com/?"))
        assertEquals("http://example.com/#", ok("http://example.com/#"))
        assertEquals("http://example.com/", ok("http://example.com/"))
    }

    @Test
    fun `fragmento se normaliza con la misma regla`() {
        assertEquals("http://example.com/#/~", ok("http://example.com/#/%7e"))
    }

    @Test
    fun `userinfo se conserva byte a byte`() {
        assertEquals(
            "http://Usuario:Clave%2fX@example.com/ruta",
            ok("http://Usuario:Clave%2fX@example.com/ruta"),
        )
    }

    @Test
    fun `IPv6 conserva corchetes y forma textual`() {
        assertEquals("http://[2001:db8::1]:8080/x", ok("http://[2001:DB8::1]:8080/x"))
        assertEquals("http://[::1]/", ok("http://[::1]/"))
    }

    @Test
    fun `SAF conserva el resto byte a byte y normaliza el esquema`() {
        assertEquals(
            "content://com.android.providers/Descargas/Lista%2fPrueba.m3u?x=%7e#Frag",
            ok(
                "CONTENT://com.android.providers/Descargas/Lista%2fPrueba.m3u?x=%7e#Frag",
                CampoUrl.LISTA_UBICACION,
            ),
        )
    }

    @Test
    fun `content en url_stream se rechaza`() {
        assertEquals(
            CodigoErrorUrl.URL_ESQUEMA_NO_ADMITIDO,
            error("content://com.android.providers/lista.m3u", CampoUrl.CANAL_URL_STREAM).codigo,
        )
    }

    @Test
    fun `rtsp elimina puerto 554 y conserva path vacio`() {
        assertEquals("rtsp://camara.local", ok("RTSP://Camara.Local:554"))
        assertEquals("rtsp://camara.local:8554/stream", ok("rtsp://camara.local:8554/stream"))
        assertEquals("rtsp://camara.local", ok("rtsp://camara.local"))
    }

    @Test
    fun `rtmp elimina puerto 1935`() {
        assertEquals("rtmp://servidor.local/live/canal", ok("rtmp://servidor.local:1935/live/canal"))
        assertEquals("rtmp://servidor.local", ok("rtmp://servidor.local"))
    }

    @Test
    fun `esquemas no admitidos se rechazan`() {
        assertEquals(CodigoErrorUrl.URL_ESQUEMA_NO_ADMITIDO, error("rtsps://host/x").codigo)
        assertEquals(CodigoErrorUrl.URL_ESQUEMA_NO_ADMITIDO, error("rtmps://host/x").codigo)
        assertEquals(CodigoErrorUrl.URL_ESQUEMA_NO_ADMITIDO, error("file:///tmp/x.m3u").codigo)
        assertEquals(CodigoErrorUrl.URL_ESQUEMA_NO_ADMITIDO, error("ftp://host/x").codigo)
        assertEquals(CodigoErrorUrl.URL_ESQUEMA_NO_ADMITIDO, error("rtsp://host/x", CampoUrl.LISTA_UBICACION).codigo)
    }

    @Test
    fun `autoridad y host invalidos se rechazan`() {
        assertEquals(CodigoErrorUrl.URL_AUTORIDAD_INVALIDA, error("http:///x").codigo)
        assertEquals(CodigoErrorUrl.URL_AUTORIDAD_INVALIDA, error("http://:8080/x").codigo)
        assertEquals(CodigoErrorUrl.URL_MALFORMADA, error("http://host:abc/x").codigo)
        assertEquals(CodigoErrorUrl.URL_MALFORMADA, error("http://host:/x").codigo)
        assertEquals(CodigoErrorUrl.URL_MALFORMADA, error("http://host:70000/x").codigo)
    }

    @Test
    fun `url ausente o con espacios se rechaza`() {
        assertEquals(CodigoErrorUrl.URL_AUSENTE, error(null).codigo)
        assertEquals(CodigoErrorUrl.URL_AUSENTE, error("   ").codigo)
        assertEquals(CodigoErrorUrl.URL_MALFORMADA, error("http://example.com/a b").codigo)
    }

    @Test
    fun `error no contiene la url ni credenciales`() {
        val secreta = "http://usuario:clave@example.com/ruta?token=abc"
        val resultado = canonizar("$secreta con espacio")
        assertIs<ResultadoCanonizacion.Rechazada>(resultado)
        val texto = resultado.error.toString()
        assertFalse(texto.contains("usuario"))
        assertFalse(texto.contains("clave"))
        assertFalse(texto.contains("token"))
        assertFalse(texto.contains("example.com"))
        assertTrue(texto.startsWith("ErrorUrl("))
    }
}
