package com.copernicotv.core.conexion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConexionXtreamTest {
    private fun datos(
        nombre: String = "Cuenta principal",
        servidor: String = "panel.ejemplo.tv",
        puerto: Int? = 8080,
        protocolo: ProtocoloConexion = ProtocoloConexion.HTTPS,
        usuario: String = "usuario1",
        contrasena: String = "secreto",
        formato: FormatoSalidaXtream = FormatoSalidaXtream.M3U8,
        urlGuia: String? = null,
    ) = DatosConexionXtream(nombre, servidor, puerto, protocolo, usuario, contrasena, formato, urlGuia)

    @Test
    fun `una conexion completa y valida no reporta errores`() {
        assertEquals(emptyList(), ConexionXtream.validar(datos()))
    }

    @Test
    fun `nombre usuario y contrasena vacios se informan por separado`() {
        val errores = ConexionXtream.validar(datos(nombre = " ", usuario = "", contrasena = ""))
        val codigos = errores.map { it.codigo }.toSet()
        assertTrue(CodigoErrorConexion.NOMBRE_VACIO in codigos)
        assertTrue(CodigoErrorConexion.USUARIO_VACIO in codigos)
        assertTrue(CodigoErrorConexion.CONTRASENA_VACIA in codigos)
    }

    @Test
    fun `el servidor acepta esquema ruta y puerto embebido`() {
        assertEquals("panel.ejemplo.tv", ConexionXtream.normalizarServidor("https://panel.ejemplo.tv:8080/c/"))
        assertEquals(8080, ConexionXtream.puertoDeclarado("https://panel.ejemplo.tv:8080/c/"))
        assertNull(ConexionXtream.puertoDeclarado("panel.ejemplo.tv"))
    }

    @Test
    fun `el servidor rechaza credenciales embebidas`() {
        assertNull(ConexionXtream.normalizarServidor("usuario:clave@panel.ejemplo.tv"))
        val errores = ConexionXtream.validar(datos(servidor = "usuario:clave@panel.ejemplo.tv"))
        assertTrue(errores.any { it.codigo == CodigoErrorConexion.SERVIDOR_CON_CREDENCIALES })
    }

    @Test
    fun `el esquema declarado debe coincidir con el protocolo elegido`() {
        val errores = ConexionXtream.validar(datos(servidor = "http://panel.ejemplo.tv", protocolo = ProtocoloConexion.HTTPS))
        assertTrue(errores.any { it.codigo == CodigoErrorConexion.ESQUEMA_NO_PERMITIDO })
    }

    @Test
    fun `un esquema no permitido se rechaza`() {
        val errores = ConexionXtream.validar(datos(servidor = "ftp://panel.ejemplo.tv"))
        assertTrue(errores.any { it.codigo == CodigoErrorConexion.ESQUEMA_NO_PERMITIDO })
    }

    @Test
    fun `el puerto explicito fuera de rango se informa como tal`() {
        val errores = ConexionXtream.validar(datos(puerto = 70000))
        assertTrue(errores.any { it.codigo == CodigoErrorConexion.PUERTO_FUERA_DE_RANGO })
    }

    @Test
    fun `el puerto embebido fuera de rango se informa como tal`() {
        val errores = ConexionXtream.validar(datos(servidor = "panel.ejemplo.tv:99999", puerto = null))
        assertTrue(errores.any { it.codigo == CodigoErrorConexion.PUERTO_FUERA_DE_RANGO })
    }

    @Test
    fun `una IPv4 con octeto invalido se rechaza`() {
        assertNull(ConexionXtream.normalizarServidor("192.168.1.999"))
        assertNotNull(ConexionXtream.normalizarServidor("192.168.1.3"))
    }

    @Test
    fun `la base usa protocolo host y puerto`() {
        assertEquals("https://panel.ejemplo.tv:8080", ConexionXtream.base(datos()))
        assertEquals("https://panel.ejemplo.tv", ConexionXtream.base(datos(puerto = null)))
        assertEquals("http://panel.ejemplo.tv:9000", ConexionXtream.base(datos(protocolo = ProtocoloConexion.HTTP, puerto = 9000)))
    }

    @Test
    fun `el puerto explicito gana sobre el embebido`() {
        assertEquals(
            "https://panel.ejemplo.tv:2095",
            ConexionXtream.base(datos(servidor = "panel.ejemplo.tv:8080", puerto = 2095)),
        )
    }

    @Test
    fun `las urls estandar usan rutas documentadas y credenciales codificadas`() {
        val conexion = datos(usuario = "us er", contrasena = "p&w")
        val player = requireNotNull(ConexionXtream.urlPlayerApi(conexion))
        val lista = requireNotNull(ConexionXtream.urlLista(conexion))
        val guia = requireNotNull(ConexionXtream.urlGuia(conexion))
        assertTrue(player.startsWith("https://panel.ejemplo.tv:8080/player_api.php?"))
        assertTrue(player.contains("username=us+er"))
        assertTrue(player.contains("password=p%26w"))
        assertFalse(player.contains("p&w"))
        assertTrue(lista.contains("type=m3u_plus"))
        assertTrue(lista.contains("output=m3u8"))
        assertTrue(guia.contains("/xmltv.php?"))
    }

    @Test
    fun `la url de guia declarada tiene prioridad sobre la del panel`() {
        val conOverride = datos(urlGuia = "https://guia.ejemplo.tv/epg.xml")
        assertEquals("https://guia.ejemplo.tv/epg.xml", ConexionXtream.urlGuiaEfectiva(conOverride))
        val sinOverride = datos()
        assertTrue(requireNotNull(ConexionXtream.urlGuiaEfectiva(sinOverride)).contains("/xmltv.php?"))
    }

    @Test
    fun `una guia que no es http se rechaza sin romper la conexion`() {
        val errores = ConexionXtream.validar(datos(urlGuia = "ftp://guia.ejemplo.tv/epg.xml"))
        assertEquals(listOf(CodigoErrorConexion.GUIA_NO_HTTP), errores.map { it.codigo })
        assertNull(ConexionXtream.validarDestinoHttp("https://usuario:clave@guia.ejemplo.tv/epg.xml"))
    }

    @Test
    fun `redactar oculta la contrasena en query y en userinfo`() {
        val query = ConexionXtream.redactar("https://host/get.php?username=ana&password=secreta")
        assertFalse(query.contains("secreta"))
        assertTrue(query.contains("password=***"))
        val userinfo = ConexionXtream.redactar("https://ana:secreta@host/x")
        assertFalse(userinfo.contains("secreta"))
    }

    @Test
    fun `toString de los datos no filtra la contrasena`() {
        val texto = datos(contrasena = "clave-supersecreta").toString()
        assertFalse(texto.contains("clave-supersecreta"))
        assertNotEquals("", texto)
    }

    @Test
    fun `el formato de salida solo admite los valores documentados`() {
        assertEquals(FormatoSalidaXtream.M3U8, FormatoSalidaXtream.desdeValor("M3U8"))
        assertEquals(FormatoSalidaXtream.TS, FormatoSalidaXtream.desdeValor(" ts "))
        assertNull(FormatoSalidaXtream.desdeValor("mkv"))
    }
}
