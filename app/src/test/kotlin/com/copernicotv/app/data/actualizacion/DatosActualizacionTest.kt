package com.copernicotv.app.data.actualizacion

import com.copernicotv.app.data.ResumenActualizacion
import org.junit.Assert.assertEquals
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
        assertNull(DatosActualizacion.fuenteId(androidx.work.Data.EMPTY))
        assertNull(DatosActualizacion.fuenteId(DatosActualizacion.de(0L, CausasActualizacion.MANUAL, emptyList())))
    }

    @Test
    fun `una version desconocida no se interpreta como resumen`() {
        val datos =
            androidx.work.Data
                .Builder()
                .putLong(DatosActualizacion.CLAVE_FUENTE, 1L)
                .putString(DatosActualizacion.CLAVE_RESUMENES, "v9\n1,completada,0,0,0,0,0,")
                .build()

        assertTrue("Un formato futuro no se adivina", DatosActualizacion.resumenes(datos).isEmpty())
    }

    @Test
    fun `un registro corrupto se descarta sin perder los validos`() {
        val datos =
            androidx.work.Data
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
}
