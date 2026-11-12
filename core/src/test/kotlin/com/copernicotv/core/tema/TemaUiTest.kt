package com.copernicotv.core.tema

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * E7-T4 / S43: el tema del canario es claro u oscuro; el valor persistido es
 * un código y el desconocido o ausente cae al oscuro (comportamiento previo a
 * E7-T4). El alternado invierte el tema sin estado intermedio.
 */
class TemaUiTest {
    @Test
    fun `los codigos de los temas son estables`() {
        assertEquals("claro", TemaUi.CLARO.codigo)
        assertEquals("oscuro", TemaUi.OSCURO.codigo)
    }

    @Test
    fun `el tema por defecto es el oscuro`() {
        assertEquals(TemaUi.OSCURO, TemaUi.POR_DEFECTO)
        assertEquals(TemaUi.OSCURO, TemaUi.desde(null))
    }

    @Test
    fun `parsea los codigos conocidos`() {
        assertEquals(TemaUi.CLARO, TemaUi.desde("claro"))
        assertEquals(TemaUi.OSCURO, TemaUi.desde("oscuro"))
    }

    @Test
    fun `un valor desconocido cae al oscuro`() {
        assertEquals(TemaUi.OSCURO, TemaUi.desde("sepia"))
        assertEquals(TemaUi.OSCURO, TemaUi.desde(""))
    }

    @Test
    fun `alternar invierte el tema`() {
        assertEquals(TemaUi.CLARO, TemaUi.OSCURO.alternar())
        assertEquals(TemaUi.OSCURO, TemaUi.CLARO.alternar())
    }
}
