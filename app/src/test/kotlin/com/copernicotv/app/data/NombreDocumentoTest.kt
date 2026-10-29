package com.copernicotv.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Selección del nombre de Fuente para un documento SAF.
 *
 * Cubre la parte pura del camino: qué nombre se usa cuando el proveedor expone
 * uno, cuál cuando no y cuál cuando la URI tampoco aporta un segmento. El
 * permiso persistible real no se puede fabricar sin un dispositivo y queda en
 * la verificación física de §10.4.
 */
class NombreDocumentoTest {
    @Test
    fun `usa el nombre visible del documento y lo recorta`() {
        val nombre = nombreDeDocumento("  lista-mia.m3u  ", "content://com.example/document/100")

        assertEquals("lista-mia.m3u", nombre)
    }

    @Test
    fun `sin nombre visible cae al ultimo segmento de la uri`() {
        val nombre = nombreDeDocumento(null, "content://com.example/Download/lista-mia.m3u")

        assertEquals("lista-mia.m3u", nombre)
    }

    @Test
    fun `un nombre visible vacio no gana sobre la uri`() {
        val nombre = nombreDeDocumento("   ", "content://com.example/Download/lista-mia.m3u")

        assertEquals("lista-mia.m3u", nombre)
    }

    @Test
    fun `sin nombre visible ni segmento usa un valor estable`() {
        val nombre = nombreDeDocumento(null, "content://com.example/")

        assertEquals("Lista importada", nombre)
    }

    @Test
    fun `un query no forma parte del nombre`() {
        val nombre = nombreDeDocumento(null, "content://com.example/Download/lista-mia.m3u?v=1")

        assertEquals("lista-mia.m3u", nombre)
    }
}
