package com.copernicotv.core.player

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Política de recuperación de E7-T10 (S4) en `:core`: cálculo determinista del
 * backoff exponencial con jitter, techo configurable y máquina de reintentos
 * con reposición por estabilidad. El azar es inyectable, así que cada valor
 * esperado es exacto y no depende del reloj.
 */
class RecuperacionTest {
    @Test
    fun elBackoffExponencialDuplicaLaEsperaBase() {
        val sinJitter = ConfiguracionRecuperacion(azar = { 0.0 })
        assertEquals(1_000L, esperaRecuperacion(1, sinJitter))
        assertEquals(2_000L, esperaRecuperacion(2, sinJitter))
        assertEquals(4_000L, esperaRecuperacion(3, sinJitter))
        assertEquals(8_000L, esperaRecuperacion(4, sinJitter))
        assertEquals(16_000L, esperaRecuperacion(5, sinJitter))
        // El sexto intento supera el techo y queda limitado al máximo.
        assertEquals(30_000L, esperaRecuperacion(6, sinJitter))
        assertEquals(30_000L, esperaRecuperacion(7, sinJitter))
        assertEquals(30_000L, esperaRecuperacion(20, sinJitter))
    }

    @Test
    fun elJitterAgregaUnaFraccionDelExponencial() {
        val mitad = ConfiguracionRecuperacion(azar = { 0.5 })
        // 1000 + 1000 * 0.5 * 0.5 = 1250; el segundo intento: 2000 + 500 = 2500.
        assertEquals(1_250L, esperaRecuperacion(1, mitad))
        assertEquals(2_500L, esperaRecuperacion(2, mitad))

        val completo = ConfiguracionRecuperacion(azar = { 1.0 })
        assertEquals(1_500L, esperaRecuperacion(1, completo))
    }

    @Test
    fun elJitterNuncaEsNegativoNiSuperaElTecho() {
        val techo =
            ConfiguracionRecuperacion(
                esperaBaseMs = 10_000L,
                esperaMaximaMs = 12_000L,
                azar = { 1.0 },
            )
        // 10_000 + 10_000 * 0.5 = 15_000 → limitado al techo 12_000.
        assertEquals(12_000L, esperaRecuperacion(1, techo))
        assertEquals(12_000L, esperaRecuperacion(3, techo))
    }

    @Test
    fun elCalculoSobreviveValoresExtremosSinDesbordamiento() {
        val extremo =
            ConfiguracionRecuperacion(
                esperaBaseMs = Long.MAX_VALUE / 3,
                esperaMaximaMs = Long.MAX_VALUE,
                azar = { 1.0 },
            )
        val espera = esperaRecuperacion(30, extremo)
        assertTrue(espera > 0L)
        assertEquals(Long.MAX_VALUE, espera)
    }

    @Test
    fun laMaquinaReintentaConEsperaCrecienteYAgotaElPresupuesto() {
        val maquina =
            MaquinaRecuperacion(
                ConfiguracionRecuperacion(azar = { 0.0 }),
            )
        assertEquals(0, maquina.realizados)
        assertEquals(6, maquina.maximo)

        (1..6).forEach { numero ->
            val decision = maquina.alFallar(ClaseFallo.TRANSITORIO)
            assertTrue(decision is DecisionReintento.Reintentar)
            assertEquals(numero, (decision as DecisionReintento.Reintentar).numero)
            maquina.alEjecutar()
            assertEquals(numero, maquina.realizados)
        }
        assertEquals(DecisionReintento.Definitivo, maquina.alFallar(ClaseFallo.TRANSITORIO))
    }

    @Test
    fun unFalloDuplicadoMientrasHayReintentoPendienteSeIgnora() {
        val maquina = MaquinaRecuperacion(ConfiguracionRecuperacion(azar = { 0.0 }))
        assertEquals(DecisionReintento.Reintentar(1, 1_000L), maquina.alFallar(ClaseFallo.TRANSITORIO))
        assertEquals(DecisionReintento.Ignorar, maquina.alFallar(ClaseFallo.TRANSITORIO))
        assertEquals(0, maquina.realizados)
        maquina.alEjecutar()
        assertEquals(1, maquina.realizados)
    }

    @Test
    fun unFalloDefinitivoNoConsumeReintentos() {
        val maquina = MaquinaRecuperacion(ConfiguracionRecuperacion(azar = { 0.0 }))
        assertEquals(DecisionReintento.Definitivo, maquina.alFallar(ClaseFallo.DEFINITIVO))
        assertEquals(0, maquina.realizados)
        // El presupuesto queda intacto para el próximo fallo transitorio.
        assertEquals(DecisionReintento.Reintentar(1, 1_000L), maquina.alFallar(ClaseFallo.TRANSITORIO))
    }

    @Test
    fun laEstabilidadReponeElPresupuestoDeReintentos() {
        val maquina =
            MaquinaRecuperacion(
                ConfiguracionRecuperacion(azar = { 0.0 }),
            )
        repeat(6) {
            maquina.alFallar(ClaseFallo.TRANSITORIO)
            maquina.alEjecutar()
        }
        assertEquals(DecisionReintento.Definitivo, maquina.alFallar(ClaseFallo.TRANSITORIO))

        maquina.alEstabilizar()
        assertEquals(0, maquina.realizados)
        val decision = maquina.alFallar(ClaseFallo.TRANSITORIO)
        assertEquals(DecisionReintento.Reintentar(1, 1_000L), decision)
    }

    @Test
    fun laEvidenciaEstableExigeFrameRenderizadoEnStreamsConVideo() {
        // READY con video y audio renderizados: estable.
        assertTrue(
            hayEvidenciaEstable(
                videoActivo = true,
                primerFrameVideoVisto = true,
                hayGrupoVideo = true,
                hayGrupoAudio = true,
                audioActivo = true,
            ),
        )
        // READY sin primer frame: no es estabilidad, aunque el motor diga READY.
        assertFalse(
            hayEvidenciaEstable(
                videoActivo = true,
                primerFrameVideoVisto = false,
                hayGrupoVideo = true,
                hayGrupoAudio = true,
                audioActivo = true,
            ),
        )
        // Video sin audio declarado: el video renderizado basta.
        assertTrue(
            hayEvidenciaEstable(
                videoActivo = true,
                primerFrameVideoVisto = true,
                hayGrupoVideo = true,
                hayGrupoAudio = false,
                audioActivo = false,
            ),
        )
        // Video activo sin frame y sin audio: no estable.
        assertFalse(
            hayEvidenciaEstable(
                videoActivo = true,
                primerFrameVideoVisto = false,
                hayGrupoVideo = true,
                hayGrupoAudio = false,
                audioActivo = false,
            ),
        )
    }

    @Test
    fun laEvidenciaEstableAceptaUnStreamSoloAudio() {
        // Stream sin grupo de video: la reproducción de audio es la evidencia
        // aplicable; no se clasifica como fallo de imagen (S4).
        assertTrue(
            hayEvidenciaEstable(
                videoActivo = false,
                primerFrameVideoVisto = false,
                hayGrupoVideo = false,
                hayGrupoAudio = true,
                audioActivo = true,
            ),
        )
        // Sin audio activo tampoco hay estabilidad.
        assertFalse(
            hayEvidenciaEstable(
                videoActivo = false,
                primerFrameVideoVisto = false,
                hayGrupoVideo = false,
                hayGrupoAudio = true,
                audioActivo = false,
            ),
        )
    }
}
