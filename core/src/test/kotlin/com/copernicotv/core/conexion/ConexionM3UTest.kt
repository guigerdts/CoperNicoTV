package com.copernicotv.core.conexion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConexionM3UTest {
    private fun datos(
        nombre: String = "Mi lista",
        urlLista: String = "https://lista.ejemplo.tv/canales.m3u",
        urlGuia: String? = null,
        intervaloHoras: Long = DatosConexionM3U.INTERVALO_POR_DEFECTO,
    ) = DatosConexionM3U(
        nombre = nombre,
        urlLista = urlLista,
        urlGuia = urlGuia,
        intervaloHoras = intervaloHoras,
    )

    @Test
    fun `una lista https valida sin errores`() {
        assertTrue(ConexionM3U.validar(datos()).isEmpty())
    }

    @Test
    fun `una lista sin nombre se rechaza`() {
        val errores = ConexionM3U.validar(datos(nombre = "  "))
        assertTrue(errores.any { it.codigo == CodigoErrorConexion.NOMBRE_VACIO })
    }

    @Test
    fun `una lista vacia o sin esquema http se rechaza`() {
        assertTrue(
            ConexionM3U.validar(datos(urlLista = ""))
                .any { it.codigo == CodigoErrorConexion.LISTA_VACIA },
        )
        assertTrue(
            ConexionM3U.validar(datos(urlLista = "ftp://lista.ejemplo.tv/canales.m3u"))
                .any { it.codigo == CodigoErrorConexion.LISTA_NO_HTTP },
        )
        assertTrue(
            ConexionM3U.validar(datos(urlLista = "https://usuario:clave@lista.ejemplo.tv/a.m3u"))
                .any { it.codigo == CodigoErrorConexion.LISTA_NO_HTTP },
        )
    }

    @Test
    fun `una guia no http se rechaza y una http se acepta`() {
        assertTrue(
            ConexionM3U.validar(datos(urlGuia = "file:///guia.xml"))
                .any { it.codigo == CodigoErrorConexion.GUIA_NO_HTTP },
        )
        assertTrue(ConexionM3U.validar(datos(urlGuia = "http://guia.ejemplo.tv/x.xml")).isEmpty())
    }

    @Test
    fun `el intervalo fuera de rango se informa`() {
        assertTrue(
            ConexionM3U.validar(datos(intervaloHoras = 0))
                .any { it.codigo == CodigoErrorConexion.INTERVALO_FUERA_DE_RANGO },
        )
        assertTrue(
            ConexionM3U.validar(datos(intervaloHoras = 721))
                .any { it.codigo == CodigoErrorConexion.INTERVALO_FUERA_DE_RANGO },
        )
    }

    @Test
    fun `el toString no filtra credenciales embebidas`() {
        val texto = datos(urlLista = "https://usuario:secreto@lista.ejemplo.tv/a.m3u").toString()
        assertTrue(!texto.contains("secreto"))
        assertTrue(texto.contains("***"))
    }

    @Test
    fun `el tipo y el estado se leen desde su codigo sin inventar`() {
        assertEquals(TipoConexion.XTREAM, TipoConexion.desdeCodigo("xtream"))
        assertEquals(TipoConexion.M3U, TipoConexion.desdeCodigo("M3U"))
        assertEquals(null, TipoConexion.desdeCodigo("otro"))
        assertEquals(EstadoConexion.ERROR, EstadoConexion.desdeCodigo("error"))
        assertEquals(EstadoConexion.SIN_PROBAR, EstadoConexion.desdeCodigo("desconocido"))
    }
}
