package com.copernicotv.core.epg

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EstabilidadAsociacionTest {
    private val ahora = Instant.parse("2026-01-10T12:00:00Z")
    private val clave = "clave-de-prueba-epg".toByteArray()

    private fun huella(
        nombres: List<String>,
        iconos: List<String> = emptyList(),
        claveHmac: ByteArray? = clave,
    ): ResultadoHuella = HuellaAsociacion.calcular(nombres, iconos, claveHmac)

    private fun asociacion(
        id: Long = 1,
        canalId: Long = 10,
        channelId: String = "canal-a",
        estado: EstadoAsociacion = EstadoAsociacion.CONFIRMADA,
        resultado: ResultadoHuella = huella(listOf("Canal A")),
        huellaVersion: Int = 2,
        origen: OrigenAsociacion = OrigenAsociacion.HEURISTICA,
    ): AsociacionActual =
        AsociacionActual(
            id = id,
            canalId = canalId,
            fuenteGuiaId = 7,
            channelIdTexto = channelId,
            estado = estado,
            origen = origen,
            huellaVersion = huellaVersion,
            huellaValor = resultado.huella,
            senales = resultado.senales,
            confirmadaEn = ahora,
        )

    private fun observacion(
        channelId: String = "canal-a",
        resultado: ResultadoHuella = huella(listOf("Canal A")),
    ): ObservacionCanalGuia = ObservacionCanalGuia(channelId, resultado)

    private fun unico(
        existentes: List<AsociacionActual>,
        observados: List<ObservacionCanalGuia>,
    ): ActualizacionAsociacion = EstabilidadAsociacion.aplicar(existentes, observados, ahora).single()

    @Test
    fun `huella exacta mantiene confirmada y actualiza observacion`() {
        val cambio = unico(listOf(asociacion()), listOf(observacion()))
        assertEquals(EstadoAsociacion.CONFIRMADA, cambio.estado)
        assertEquals("sin_cambios", cambio.motivo)
        assertEquals(ahora, cambio.ultimaObservacion)
    }

    @Test
    fun `cambio solo de iconos mantiene confirmada`() {
        val anterior = huella(listOf("Canal A"), listOf("https://icons.example/a.png"))
        val actual = huella(listOf("Canal A"), listOf("https://icons.example/b.png"))
        val cambio = unico(listOf(asociacion(resultado = anterior)), listOf(observacion(resultado = actual)))
        assertEquals(EstadoAsociacion.CONFIRMADA, cambio.estado)
        assertEquals("cambio_parcial_informativo", cambio.motivo)
        assertEquals(actual.huella, cambio.huellaValor)
    }

    @Test
    fun `cambio de ambos componentes pasa a sospechosa`() {
        val anterior = huella(listOf("Canal A"), listOf("https://icons.example/a.png"))
        val actual = huella(listOf("Canal B"), listOf("https://icons.example/b.png"))
        val cambio = unico(listOf(asociacion(resultado = anterior)), listOf(observacion(resultado = actual)))
        assertEquals(EstadoAsociacion.SOSPECHOSA, cambio.estado)
        assertEquals("huella_contradictoria", cambio.motivo)
        assertEquals(anterior.huella, cambio.huellaValor)
    }

    @Test
    fun `canal ausente conserva fila y pasa a ausente`() {
        val cambio = unico(listOf(asociacion()), emptyList())
        assertEquals(EstadoAsociacion.AUSENTE, cambio.estado)
        assertEquals("canal_ausente", cambio.motivo)
        assertTrue(cambio.huellaValor != null)
    }

    @Test
    fun `propuesta ausente se elimina`() {
        val cambio = unico(listOf(asociacion(estado = EstadoAsociacion.PROPUESTA)), emptyList())
        assertTrue(cambio.eliminar)
    }

    @Test
    fun `ausente reaparece compatible vuelve a confirmada`() {
        val existente = asociacion(estado = EstadoAsociacion.AUSENTE)
        val cambio = unico(listOf(existente), listOf(observacion()))
        assertEquals(EstadoAsociacion.CONFIRMADA, cambio.estado)
        assertEquals("reaparece_compatible", cambio.motivo)
    }

    @Test
    fun `ausente reaparece contradictoria pasa a sospechosa`() {
        val existente =
            asociacion(
                estado = EstadoAsociacion.AUSENTE,
                resultado = huella(listOf("Canal A"), listOf("https://icons.example/a.png")),
            )
        val actual = huella(listOf("Canal B"), listOf("https://icons.example/b.png"))
        val cambio = unico(listOf(existente), listOf(observacion(resultado = actual)))
        assertEquals(EstadoAsociacion.SOSPECHOSA, cambio.estado)
        assertEquals("reaparece_contradictoria", cambio.motivo)
    }

    @Test
    fun `huella v1 confirmada pasa a sospechosa sin recalcular`() {
        val existente = asociacion(huellaVersion = 1)
        val cambio = unico(listOf(existente), listOf(observacion()))
        assertEquals(EstadoAsociacion.SOSPECHOSA, cambio.estado)
        assertEquals("huella_v1_requiere_reconfirmacion", cambio.motivo)
        assertEquals(1, cambio.huellaVersion)
        assertEquals(existente.huellaValor, cambio.huellaValor)
    }

    @Test
    fun `iconos sin clave hmac pasan a sospechosa`() {
        val existente = asociacion()
        val sinClave = huella(listOf("Canal A"), listOf("https://icons.example/a.png"), claveHmac = null)
        val cambio = unico(listOf(existente), listOf(observacion(resultado = sinClave)))
        assertEquals(EstadoAsociacion.SOSPECHOSA, cambio.estado)
        assertEquals("hmac_no_disponible", cambio.motivo)
        assertEquals(existente.huellaValor, cambio.huellaValor)
    }

    @Test
    fun `colision de huella entre dos canales pasa a sospechosa`() {
        val compartida = huella(listOf("Canal A"))
        val uno = asociacion(id = 1, canalId = 10, channelId = "canal-a", resultado = compartida)
        val dos = asociacion(id = 2, canalId = 11, channelId = "canal-b", resultado = huella(listOf("Canal B")))
        val cambios =
            EstabilidadAsociacion.aplicar(
                listOf(uno, dos),
                listOf(observacion("canal-a", compartida), observacion("canal-b", compartida)),
                ahora,
            )
        assertEquals(2, cambios.size)
        assertTrue(cambios.all { it.estado == EstadoAsociacion.SOSPECHOSA && it.motivo == "colision_huella" })
    }

    @Test
    fun `revocada no se toca`() {
        val cambios =
            EstabilidadAsociacion.aplicar(
                listOf(asociacion(estado = EstadoAsociacion.REVOCADA)),
                listOf(observacion()),
                ahora,
            )
        assertTrue(cambios.isEmpty())
    }

    @Test
    fun `sospechosa no se reconfirma automaticamente`() {
        val cambio = unico(listOf(asociacion(estado = EstadoAsociacion.SOSPECHOSA)), listOf(observacion()))
        assertEquals(EstadoAsociacion.SOSPECHOSA, cambio.estado)
        assertEquals("requiere_reconfirmacion", cambio.motivo)
    }

    @Test
    fun `heuristica propone cuando tvg id coincide`() {
        val propuestas =
            HeuristicaAsociacionEpg.proponer(
                fuenteGuiaId = 7,
                canales = listOf(CanalAsociable(10, "canal-a"), CanalAsociable(11, "canal-b")),
                observados = listOf(observacion("canal-a"), observacion("canal-c")),
                existentes = emptyList(),
                ahora = ahora,
            )
        assertEquals(1, propuestas.size)
        assertEquals(10, propuestas.single().canalId)
        assertEquals("canal-a", propuestas.single().channelIdTexto)
        assertEquals(EstadoAsociacion.PROPUESTA, propuestas.single().estado)
        assertEquals(OrigenAsociacion.HEURISTICA, propuestas.single().origen)
    }

    @Test
    fun `heuristica no propone sin coincidencia ni con tvg id ambiguo`() {
        val sinCoincidencia =
            HeuristicaAsociacionEpg.proponer(
                fuenteGuiaId = 7,
                canales = listOf(CanalAsociable(10, "otro")),
                observados = listOf(observacion("canal-a")),
                existentes = emptyList(),
                ahora = ahora,
            )
        assertTrue(sinCoincidencia.isEmpty())
        val ambiguo =
            HeuristicaAsociacionEpg.proponer(
                fuenteGuiaId = 7,
                canales = listOf(CanalAsociable(10, "canal-a"), CanalAsociable(11, "canal-a")),
                observados = listOf(observacion("canal-a")),
                existentes = emptyList(),
                ahora = ahora,
            )
        assertTrue(ambiguo.isEmpty())
    }

    @Test
    fun `heuristica no recrea una asociacion revocada`() {
        val propuestas =
            HeuristicaAsociacionEpg.proponer(
                fuenteGuiaId = 7,
                canales = listOf(CanalAsociable(10, "canal-a")),
                observados = listOf(observacion("canal-a")),
                existentes = listOf(asociacion(estado = EstadoAsociacion.REVOCADA)),
                ahora = ahora,
            )
        assertTrue(propuestas.isEmpty())
    }

    @Test
    fun `confirmar editar y revocar operaciones manuales`() {
        val existente = asociacion(estado = EstadoAsociacion.SOSPECHOSA)
        val actual = huella(listOf("Canal A"), listOf("https://icons.example/a.png"))
        val confirmada = OperacionesAsociacion.confirmar(existente, observacion(resultado = actual), ahora)
        assertEquals(EstadoAsociacion.CONFIRMADA, confirmada.estado)
        assertEquals(2, confirmada.huellaVersion)
        assertEquals(actual.huella, confirmada.huellaValor)
        assertEquals(ahora, confirmada.confirmadaEn)

        val editada = OperacionesAsociacion.editar(existente, 99, observacion(resultado = actual), ahora)
        assertEquals(99, editada.canalId)
        assertEquals(OrigenAsociacion.MANUAL, editada.origen)
        assertEquals(EstadoAsociacion.CONFIRMADA, editada.estado)

        val revocada = OperacionesAsociacion.revocar(existente, ahora)
        assertEquals(EstadoAsociacion.REVOCADA, revocada.estado)
        assertEquals("revocada_manual", revocada.motivo)
    }
}
