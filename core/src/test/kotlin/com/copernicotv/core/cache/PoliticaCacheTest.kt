package com.copernicotv.core.cache

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class PoliticaCacheTest {
    private val t0: Instant = Instant.parse("2026-01-01T00:00:00Z")
    private val presupuesto = PresupuestoCache()

    private fun entrada(
        id: Long,
        clave: ClaveCache = ClaveCache.M3U(fuenteId = 1, listaId = 1, urlHmac = "h$id"),
        bytes: Long = BYTES_POR_MIB,
        segundos: Long = 0,
        frescura: Frescura = Frescura.VALIDA,
        enUso: Boolean = false,
    ): EntradaCache =
        EntradaCache(
            id = id,
            clave = clave,
            tamanoBytes = bytes,
            fetchedAt = t0.plusSeconds(segundos),
            frescura = frescura,
            enUso = enUso,
        )

    @Test
    fun `la clave m3u usa fuente lista tipo y hmac`() {
        val clave = ClaveCache.M3U(fuenteId = 7, listaId = 9, urlHmac = "abc")
        assertEquals(TipoCache.M3U, clave.tipo)
        assertEquals("m3u", clave.tipo.codigo)
        assertEquals(9L, propietarioDe(clave))
    }

    @Test
    fun `la clave xmltv usa fuente guia tipo y hmac`() {
        val clave = ClaveCache.Xmltv(fuenteGuiaId = 4, urlHmac = "abc")
        assertEquals(TipoCache.XMLTV, clave.tipo)
        assertEquals("xmltv", clave.tipo.codigo)
        assertEquals(4L, propietarioDe(clave))
    }

    @Test
    fun `claves con distinto hmac no colisionan`() {
        val una = ClaveCache.M3U(fuenteId = 1, listaId = 2, urlHmac = "uno")
        val otra = ClaveCache.M3U(fuenteId = 1, listaId = 2, urlHmac = "dos")
        assertEquals(false, una == otra)
    }

    @Test
    fun `un archivo mayor al limite por archivo se omite`() {
        val decision =
            PoliticaCache.planificarEscritura(
                nueva = ClaveCache.M3U(1, 1, "h"),
                tamanoNuevo = 16L * BYTES_POR_MIB + 1,
                existentes = emptyList(),
                libreBytes = 1000L * BYTES_POR_MIB,
            )
        assertEquals(DecisionEscritura.OmitidaPorTamano, decision)
    }

    @Test
    fun `un archivo exactamente en el limite cabe`() {
        val decision =
            PoliticaCache.planificarEscritura(
                nueva = ClaveCache.M3U(1, 1, "h"),
                tamanoNuevo = 16L * BYTES_POR_MIB,
                existentes = emptyList(),
                libreBytes = 200L * BYTES_POR_MIB,
            )
        assertEquals(DecisionEscritura.Almacenar(emptyList()), decision)
    }

    @Test
    fun `sin entradas previas y con espacio suficiente no evicta`() {
        val decision =
            PoliticaCache.planificarEscritura(
                nueva = ClaveCache.Xmltv(3, "h"),
                tamanoNuevo = 10L * BYTES_POR_MIB,
                existentes = emptyList(),
                libreBytes = 500L * BYTES_POR_MIB,
            )
        assertEquals(DecisionEscritura.Almacenar(emptyList()), decision)
    }

    @Test
    fun `reemplazar la misma clave no exige evictar`() {
        val clave = ClaveCache.M3U(1, 1, "h")
        val decision =
            PoliticaCache.planificarEscritura(
                nueva = clave,
                tamanoNuevo = 16L * BYTES_POR_MIB,
                existentes = listOf(entrada(id = 1, clave = clave, bytes = 16L * BYTES_POR_MIB)),
                libreBytes = 200L * BYTES_POR_MIB,
            )
        assertEquals(DecisionEscritura.Almacenar(emptyList()), decision)
    }

    @Test
    fun `una copia en uso no se reemplaza`() {
        val clave = ClaveCache.M3U(1, 1, "h")
        val decision =
            PoliticaCache.planificarEscritura(
                nueva = clave,
                tamanoNuevo = BYTES_POR_MIB,
                existentes = listOf(entrada(id = 1, clave = clave, enUso = true)),
                libreBytes = 500L * BYTES_POR_MIB,
            )
        assertEquals(DecisionEscritura.SinEspacio, decision)
    }

    @Test
    fun `el limite por lista evicta la entrada mas antigua`() {
        val clave = ClaveCache.M3U(1, 1, "nueva")
        val existentes =
            listOf(
                entrada(id = 1, bytes = 8L * BYTES_POR_MIB, segundos = 0),
                entrada(id = 2, bytes = 8L * BYTES_POR_MIB, segundos = 1),
            )
        val decision =
            PoliticaCache.planificarEscritura(
                nueva = clave,
                tamanoNuevo = 8L * BYTES_POR_MIB,
                existentes = existentes,
                libreBytes = 500L * BYTES_POR_MIB,
            )
        assertEquals(DecisionEscritura.Almacenar(listOf(1L)), decision)
    }

    @Test
    fun `evicta stale antes que valida aunque sea mas nueva`() {
        val clave = ClaveCache.M3U(1, 1, "nueva")
        val existentes =
            listOf(
                entrada(id = 5, bytes = 8L * BYTES_POR_MIB, segundos = 0),
                entrada(id = 6, bytes = 8L * BYTES_POR_MIB, segundos = 10, frescura = Frescura.STALE),
            )
        val decision =
            PoliticaCache.planificarEscritura(
                nueva = clave,
                tamanoNuevo = 8L * BYTES_POR_MIB,
                existentes = existentes,
                libreBytes = 500L * BYTES_POR_MIB,
            )
        assertEquals(DecisionEscritura.Almacenar(listOf(6L)), decision)
    }

    @Test
    fun `ordena por fetched at y luego por id`() {
        val clave = ClaveCache.M3U(1, 1, "nueva")
        val existentes =
            listOf(
                entrada(id = 3, bytes = 8L * BYTES_POR_MIB, frescura = Frescura.INVALIDA),
                entrada(id = 2, bytes = 8L * BYTES_POR_MIB, frescura = Frescura.INVALIDA),
            )
        val decision =
            PoliticaCache.planificarEscritura(
                nueva = clave,
                tamanoNuevo = 8L * BYTES_POR_MIB,
                existentes = existentes,
                libreBytes = 500L * BYTES_POR_MIB,
            )
        assertEquals(DecisionEscritura.Almacenar(listOf(2L)), decision)
    }

    @Test
    fun `no evicta entradas en uso y falla la escritura`() {
        val clave = ClaveCache.M3U(1, 1, "nueva")
        val decision =
            PoliticaCache.planificarEscritura(
                nueva = clave,
                tamanoNuevo = 8L * BYTES_POR_MIB,
                existentes = listOf(entrada(id = 1, bytes = 16L * BYTES_POR_MIB, enUso = true)),
                libreBytes = 500L * BYTES_POR_MIB,
            )
        assertEquals(DecisionEscritura.SinEspacio, decision)
    }

    @Test
    fun `no hay eviccion parcial cuando aun no alcanza`() {
        val clave = ClaveCache.M3U(1, 1, "nueva")
        val existentes =
            listOf(
                entrada(id = 1, bytes = 8L * BYTES_POR_MIB, enUso = true),
                entrada(id = 2, bytes = 8L * BYTES_POR_MIB),
            )
        val decision =
            PoliticaCache.planificarEscritura(
                nueva = clave,
                tamanoNuevo = 16L * BYTES_POR_MIB,
                existentes = existentes,
                libreBytes = 500L * BYTES_POR_MIB,
            )
        assertEquals(DecisionEscritura.SinEspacio, decision)
    }

    @Test
    fun `la reserva libre obliga a evictar`() {
        val clave = ClaveCache.Xmltv(fuenteGuiaId = 1, urlHmac = "nueva")
        val decision =
            PoliticaCache.planificarEscritura(
                nueva = clave,
                tamanoNuevo = 60L * BYTES_POR_MIB,
                existentes = listOf(entrada(id = 4, clave = ClaveCache.Xmltv(1, "vieja"), bytes = 10L * BYTES_POR_MIB)),
                libreBytes = 150L * BYTES_POR_MIB,
            )
        assertEquals(DecisionEscritura.Almacenar(listOf(4L)), decision)
    }

    @Test
    fun `el limite global puede obligar a evictar`() {
        val ajustado = presupuesto.copy(global = 100L * BYTES_POR_MIB)
        val existentes =
            listOf(
                entrada(id = 1, bytes = 50L * BYTES_POR_MIB),
                entrada(id = 2, clave = ClaveCache.Xmltv(2, "vieja"), bytes = 40L * BYTES_POR_MIB),
            )
        val decision =
            PoliticaCache.planificarEscritura(
                nueva = ClaveCache.Xmltv(fuenteGuiaId = 9, urlHmac = "nueva"),
                tamanoNuevo = 20L * BYTES_POR_MIB,
                existentes = existentes,
                libreBytes = 500L * BYTES_POR_MIB,
                presupuesto = ajustado,
            )
        assertEquals(DecisionEscritura.Almacenar(listOf(1L)), decision)
    }

    @Test
    fun `el limite total de la cache m3u evicta la mas antigua`() {
        val existentes =
            listOf(
                entrada(id = 1, clave = ClaveCache.M3U(1, 1, "a"), bytes = 16L * BYTES_POR_MIB, segundos = 0),
                entrada(id = 2, clave = ClaveCache.M3U(1, 2, "b"), bytes = 16L * BYTES_POR_MIB, segundos = 1),
                entrada(id = 3, clave = ClaveCache.M3U(1, 3, "c"), bytes = 16L * BYTES_POR_MIB, segundos = 2),
                entrada(id = 4, clave = ClaveCache.M3U(1, 4, "d"), bytes = 16L * BYTES_POR_MIB, segundos = 3),
            )
        val decision =
            PoliticaCache.planificarEscritura(
                nueva = ClaveCache.M3U(fuenteId = 1, listaId = 5, urlHmac = "nueva"),
                tamanoNuevo = 16L * BYTES_POR_MIB,
                existentes = existentes,
                libreBytes = 500L * BYTES_POR_MIB,
            )
        assertEquals(DecisionEscritura.Almacenar(listOf(1L)), decision)
    }

    @Test
    fun `el limite por fuente guia no mezcla propietarios m3u`() {
        val clave = ClaveCache.Xmltv(fuenteGuiaId = 1, urlHmac = "nueva")
        val existentes =
            listOf(
                entrada(id = 1, clave = ClaveCache.Xmltv(1, "vieja"), bytes = 90L * BYTES_POR_MIB, segundos = 0),
                entrada(id = 2, clave = ClaveCache.M3U(1, 1, "m3u"), bytes = 16L * BYTES_POR_MIB),
            )
        val decision =
            PoliticaCache.planificarEscritura(
                nueva = clave,
                tamanoNuevo = 90L * BYTES_POR_MIB,
                existentes = existentes,
                libreBytes = 500L * BYTES_POR_MIB,
            )
        assertEquals(DecisionEscritura.Almacenar(listOf(1L)), decision)
    }

    @Test
    fun `la expiracion usa el intervalo vigente al descargar`() {
        val expiresAt = FrescuraCache.expiracion(t0, Duration.ofMinutes(60))
        assertEquals(t0.plus(Duration.ofMinutes(60)), expiresAt)
    }

    @Test
    fun `una copia es valida antes de expirar`() {
        val expiresAt = t0.plus(Duration.ofMinutes(30))
        assertEquals(Frescura.VALIDA, FrescuraCache.evaluar(expiresAt, t0.plus(Duration.ofMinutes(29))))
    }

    @Test
    fun `una copia es stale en el instante exacto de expiracion`() {
        val expiresAt = t0.plus(Duration.ofMinutes(30))
        assertEquals(Frescura.STALE, FrescuraCache.evaluar(expiresAt, expiresAt))
    }

    @Test
    fun `cambiar el intervalo no altera la frescura almacenada`() {
        val expiresAt = t0.plus(Duration.ofMinutes(30))
        assertEquals(Frescura.STALE, FrescuraCache.evaluar(expiresAt, t0.plus(Duration.ofMinutes(45))))
        assertEquals(t0.plus(Duration.ofMinutes(30)), expiresAt)
    }
}
