package com.copernicotv.app.data.actualizacion

import androidx.work.Data
import com.copernicotv.app.data.ResumenActualizacion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * DC-07 puntos 5, 6 y 9: el formato de `Data` conserva la Fuente, la causa y el
 * resultado por Lista sin depender de una tabla nueva.
 *
 * La codificación es tolerante: un texto que no reconoce se descarta en lugar
 * de romper la lectura del trabajo.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DatosActualizacionTest {
    @Test
    fun `un resumen completo sobrevive al viaje de ida y vuelta`() {
        val original =
            ResumenActualizacion(
                listaId = 7L,
                resultado = "fallida",
                nuevas = 1,
                actualizadas = 2,
                ausentes = 3,
                ambiguas = 4,
                observaciones = 5,
                error = "archivo_inaccesible",
            )

        val datos = DatosActualizacion.de(11L, CausasActualizacion.MANUAL, listOf(original))

        assertEquals(11L, DatosActualizacion.fuenteId(datos))
        assertEquals(CausasActualizacion.MANUAL, DatosActualizacion.causa(datos))
        assertEquals(listOf(original), DatosActualizacion.resumenes(datos))
    }

    @Test
    fun `varios resumenes se conservan en orden`() {
        val resumenes =
            listOf(
                ResumenActualizacion(listaId = 1L, resultado = "completada"),
                ResumenActualizacion(listaId = 2L, resultado = "fallida", error = "timeout"),
            )

        val datos = DatosActualizacion.de(3L, CausasActualizacion.AUTOMATICA, resumenes)

        assertEquals(resumenes, DatosActualizacion.resumenes(datos))
    }

    @Test
    fun `una ejecucion sin resumenes devuelve una lista vacia`() {
        val datos = DatosActualizacion.de(3L, CausasActualizacion.AUTOMATICA, emptyList())

        assertTrue("Sin resumenes no hay registros", DatosActualizacion.resumenes(datos).isEmpty())
    }

    @Test
    fun `una fuente ausente o invalida no se interpreta`() {
        assertNull(DatosActualizacion.fuenteId(Data.EMPTY))
        assertNull(DatosActualizacion.fuenteId(DatosActualizacion.de(0L, CausasActualizacion.MANUAL, emptyList())))
    }

    @Test
    fun `una version desconocida no se interpreta como resumen`() {
        val datos =
            Data
                .Builder()
                .putLong(DatosActualizacion.CLAVE_FUENTE, 1L)
                .putString(DatosActualizacion.CLAVE_RESUMENES, "v9\n1,completada,0,0,0,0,0,")
                .build()

        assertTrue("Un formato futuro no se adivina", DatosActualizacion.resumenes(datos).isEmpty())
    }

    @Test
    fun `un registro corrupto se descarta sin perder los validos`() {
        val datos =
            Data
                .Builder()
                .putLong(DatosActualizacion.CLAVE_FUENTE, 1L)
                .putString(
                    DatosActualizacion.CLAVE_RESUMENES,
                    "v1\nroto,completada\n2,completada,0,0,0,0,0,",
                )
                .build()

        val resumenes = DatosActualizacion.resumenes(datos)

        assertEquals(1, resumenes.size)
        assertEquals(2L, resumenes.single().listaId)
    }

    @Test
    fun `un cierre sin trabajo se distingue de una actualizacion completada`() {
        val sinTrabajo = DatosActualizacion.sinTrabajo(7L, CausasActualizacion.AUTOMATICA)

        assertEquals(DatosActualizacion.RESULTADO_SIN_TRABAJO, DatosActualizacion.resultado(sinTrabajo))
        assertEquals(7L, DatosActualizacion.fuenteId(sinTrabajo))
        assertTrue("Sin trabajo no hay resumenes", DatosActualizacion.resumenes(sinTrabajo).isEmpty())

        val completada = DatosActualizacion.de(7L, CausasActualizacion.AUTOMATICA, emptyList())
        assertEquals(DatosActualizacion.RESULTADO_COMPLETADO, DatosActualizacion.resultado(completada))
        assertNotEquals(
            DatosActualizacion.resultado(completada),
            DatosActualizacion.resultado(sinTrabajo),
        )
    }

    @Test
    fun `un cierre fallido conserva sus resumenes parciales`() {
        val resumenes = listOf(ResumenActualizacion(5L, "fallida", error = "timeout"))
        val datos = DatosActualizacion.fallo(7L, CausasActualizacion.MANUAL, resumenes)

        assertEquals(DatosActualizacion.RESULTADO_FALLIDO, DatosActualizacion.resultado(datos))
        assertEquals(resumenes, DatosActualizacion.resumenes(datos))
    }

    @Test
    fun `el avance viaja como dos enteros y no filtra texto`() {
        val datos = DatosActualizacion.progreso(3, 9)

        assertEquals(3 to 9, DatosActualizacion.progreso(datos))
        assertNull("Sin claves de avance no se adivina", DatosActualizacion.progreso(Data.EMPTY))
    }
}
