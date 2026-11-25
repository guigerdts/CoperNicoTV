package com.copernicotv.core.conexion

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnalizadorRespuestaXtreamTest {
    private val ahora = Instant.parse("2026-11-25T00:00:00Z")

    private fun cuenta(
        auth: String = "1",
        status: String = "Active",
        expDate: String = "1798761600",
    ): String =
        "{\"user_info\":{\"username\":\"demo\",\"password\":\"demo\",\"message\":\"\"," +
            "\"auth\":$auth,\"status\":\"$status\",\"exp_date\":\"$expDate\"," +
            "\"is_trial\":\"0\",\"active_cons\":\"1\",\"max_connections\":\"2\"," +
            "\"allowed_output_formats\":[\"m3u8\",\"ts\"]}}"

    @Test
    fun `una cuenta activa se interpreta con sus datos reportados`() {
        val estado = AnalizadorRespuestaXtream.analizarCuenta(cuenta(), ahora)
        assertTrue(estado.respuestaValida)
        assertTrue(estado.autenticado)
        assertEquals("Active", estado.estado)
        assertEquals(1, estado.conexionesActivas)
        assertEquals(2, estado.maxConexiones)
        assertEquals(listOf("m3u8", "ts"), estado.formatosPermitidos)
        assertEquals(Instant.ofEpochSecond(1798761600), estado.expiraEn)
        assertTrue(estado.reportadoPorProveedor)
    }

    @Test
    fun `un auth distinto de uno no autentica`() {
        val estado = AnalizadorRespuestaXtream.analizarCuenta(cuenta(auth = "0"), ahora)
        assertTrue(estado.respuestaValida)
        assertFalse(estado.autenticado)
    }

    @Test
    fun `una cuenta vencida no se declara autenticada aunque el panel diga Active`() {
        val estado = AnalizadorRespuestaXtream.analizarCuenta(cuenta(expDate = "1600000000"), ahora)
        assertFalse(estado.autenticado)
        assertEquals("cuenta_vencida", estado.mensaje)
    }

    @Test
    fun `una expiracion ausente o cero no se interpreta como fecha`() {
        assertNull(AnalizadorRespuestaXtream.analizarCuenta(cuenta(expDate = "0"), ahora).expiraEn)
        val sinFecha = """{"user_info":{"auth":"1","status":"Active"}}"""
        assertNull(AnalizadorRespuestaXtream.analizarCuenta(sinFecha, ahora).expiraEn)
    }

    @Test
    fun `los numeros json sin comillas tambien se leen`() {
        val cuerpo = """{"user_info":{"auth":1,"status":"Active","max_connections":3,"active_cons":0}}"""
        val estado = AnalizadorRespuestaXtream.analizarCuenta(cuerpo, ahora)
        assertTrue(estado.autenticado)
        assertEquals(3, estado.maxConexiones)
        assertEquals(0, estado.conexionesActivas)
    }

    @Test
    fun `un portal html no se interpreta como cuenta`() {
        val estado = AnalizadorRespuestaXtream.analizarCuenta("<!DOCTYPE html><html><body>Portal</body></html>", ahora)
        assertFalse(estado.respuestaValida)
        assertEquals("respuesta_html", estado.mensaje)
    }

    @Test
    fun `un json invalido se informa sin lanzar`() {
        val estado = AnalizadorRespuestaXtream.analizarCuenta("{esto no es json", ahora)
        assertFalse(estado.respuestaValida)
        assertEquals("respuesta_no_json", estado.mensaje)
    }

    @Test
    fun `un json sin user_info no se acepta`() {
        val estado = AnalizadorRespuestaXtream.analizarCuenta("""{"algo":true}""", ahora)
        assertFalse(estado.respuestaValida)
        assertEquals("sin_user_info", estado.mensaje)
    }

    @Test
    fun `un cuerpo vacio se informa`() {
        assertEquals("respuesta_vacia", AnalizadorRespuestaXtream.analizarCuenta("  ", ahora).mensaje)
    }

    @Test
    fun `el codigo http se clasifica con reintentabilidad`() {
        assertEquals(ClaseRespuestaHttp.OK, AnalizadorRespuestaXtream.clasificarHttp(200).clase)
        assertEquals(ClaseRespuestaHttp.REDIRECCION, AnalizadorRespuestaXtream.clasificarHttp(302).clase)
        val noAutorizado = AnalizadorRespuestaXtream.clasificarHttp(401)
        assertEquals(ClaseRespuestaHttp.NO_AUTORIZADO, noAutorizado.clase)
        assertFalse(noAutorizado.reintentable)
        assertEquals(ClaseRespuestaHttp.NO_ENCONTRADO, AnalizadorRespuestaXtream.clasificarHttp(404).clase)
        assertEquals(ClaseRespuestaHttp.NO_AUTORIZADO, AnalizadorRespuestaXtream.clasificarHttp(403).clase)
        assertTrue(AnalizadorRespuestaXtream.clasificarHttp(429).reintentable)
        assertTrue(AnalizadorRespuestaXtream.clasificarHttp(503).reintentable)
        assertFalse(AnalizadorRespuestaXtream.clasificarHttp(400).reintentable)
    }
}
