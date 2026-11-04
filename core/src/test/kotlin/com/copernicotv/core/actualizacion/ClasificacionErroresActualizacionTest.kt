package com.copernicotv.core.actualizacion

import kotlin.test.Test
import kotlin.test.assertEquals

/** DC-07 punto 7: clasificación de errores para decidir reintento. */
class ClasificacionErroresActualizacionTest {
    @Test
    fun `los errores transitorios se reintentan`() {
        listOf("timeout", "http_5xx", "sin_conexion", "archivo_inaccesible").forEach { codigo ->
            assertEquals(ClaseErrorActualizacion.TRANSITORIO, ClasificacionErroresActualizacion.clasificar(codigo), codigo)
        }
    }

    @Test
    fun `las credenciales no disponibles no se reintentan en el ciclo`() {
        assertEquals(
            ClaseErrorActualizacion.CREDENCIALES_NO_DISPONIBLES,
            ClasificacionErroresActualizacion.clasificar("credenciales_no_disponibles"),
        )
    }

    @Test
    fun `los errores permanentes no se reintentan`() {
        listOf(
            "ubicacion_invalida",
            "esquema_no_admitido",
            "http_4xx",
            "sin_canales",
            "lista_inexistente",
            "fuente_inexistente",
            "actualizacion_fallida",
        ).forEach { codigo ->
            assertEquals(ClaseErrorActualizacion.PERMANENTE, ClasificacionErroresActualizacion.clasificar(codigo), codigo)
        }
    }

    @Test
    fun `un codigo desconocido se trata como permanente`() {
        assertEquals(
            ClaseErrorActualizacion.PERMANENTE,
            ClasificacionErroresActualizacion.clasificar("codigo_que_no_existe"),
        )
    }
}
