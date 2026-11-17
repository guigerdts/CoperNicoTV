package com.copernicotv.core.player

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Política de recuperación de imagen de E7-T10 (S5) en `:core`: clasificación
 * determinista de la causa de «audio activo sin imagen», ventana de
 * confirmación acumulada solo en reproducción (congelada en buffering) y
 * escalera por niveles, del menos invasivo al más costoso, con gracia por
 * nivel y agotamiento terminal sin bucles. El tiempo entra inyectado por la
 * sonda, así que cada valor esperado es exacto.
 */
class ImagenTest {
    private val ventana = 6_000L

    /** Sonda de video que se reproduce bien: grupo video, pista elegida, decoder y primer frame. */
    private val imagenOk =
        EvidenciaImagen(
            fase = FaseBase.REPRODUCIENDO,
            audioActivo = true,
            hayGrupoVideo = true,
            pistaVideoSeleccionada = true,
            decoderVideoInicializado = true,
            primerFrameVisto = true,
            superficieActiva = true,
        )

    /** Sonda de video que aún no muestra imagen: sin primer frame. */
    private val sinImagen = imagenOk.copy(primerFrameVisto = false)

    private fun maquina() = MaquinaImagen(ventanaMs = ventana)

    // ------------------------------------------------------------------
    // Clasificación de causas (S5: diferenciar los escenarios).
    // ------------------------------------------------------------------

    @Test
    fun laCausaEsPistaNoSeleccionadaCuandoHayGrupoVideoPeroNingunaSeleccionada() {
        val causa =
            clasificarCausaImagen(
                sinImagen.copy(pistaVideoSeleccionada = false, decoderVideoInicializado = false),
            )
        assertEquals(CausaImagen.PISTA_NO_SELECCIONADA, causa)
    }

    @Test
    fun laCausaEsDecoderNoInicializadoConPistaSeleccionada() {
        val causa = clasificarCausaImagen(sinImagen.copy(decoderVideoInicializado = false))
        assertEquals(CausaImagen.DECODER_NO_INICIALIZADO, causa)
    }

    @Test
    fun laCausaEsSuperficiePerdidaCuandoLaSuperficieSeReportaInactiva() {
        val causa = clasificarCausaImagen(sinImagen.copy(superficieActiva = false))
        assertEquals(CausaImagen.SUPERFICIE_PERDIDA, causa)
    }

    @Test
    fun laCausaEsRendererSinFramesCuandoTodoLoDemasEstaBien() {
        assertEquals(CausaImagen.RENDERER_SIN_FRAMES, clasificarCausaImagen(sinImagen))
    }

    @Test
    fun laSuperficieDesconocidaNoSeAsumePerdida() {
        // Sin datos de superficie (ningún evento aún) no se declara perdida:
        // la evidencia falsa solo llega de `onSurfaceSizeChanged(0, 0)`.
        assertEquals(
            CausaImagen.RENDERER_SIN_FRAMES,
            clasificarCausaImagen(sinImagen.copy(superficieActiva = null)),
        )
    }

    @Test
    fun laCausaEsVideoNoElegibleCuandoNingunaPistaDeVideoEsSoportada() {
        // Grupo de video presente, ninguna pista seleccionada y ninguna
        // soportada: el contenedor declara video que el dispositivo no puede
        // decodificar (E7-T10 T7, validación física Telecaribe 2719).
        assertEquals(
            CausaImagen.VIDEO_NO_ELEGIBLE,
            clasificarCausaImagen(
                sinImagen.copy(pistaVideoSeleccionada = false, pistaVideoSoportada = false),
            ),
        )
    }

    @Test
    fun unaPistaSoportadaSinSeleccionarSigueSiendoPistaNoSeleccionada() {
        assertEquals(
            CausaImagen.PISTA_NO_SELECCIONADA,
            clasificarCausaImagen(
                sinImagen.copy(pistaVideoSeleccionada = false, pistaVideoSoportada = true),
            ),
        )
    }

    @Test
    fun elSoporteDesconocidoMantieneLaClasificacionPrevia() {
        // Sin dato de soporte (pistas no consultadas aún) el comportamiento
        // es exactamente el previo a T7: pista no seleccionada.
        assertEquals(
            CausaImagen.PISTA_NO_SELECCIONADA,
            clasificarCausaImagen(sinImagen.copy(pistaVideoSeleccionada = false)),
        )
    }

    @Test
    fun laClasificacionNoDeclaraCausaConImagenEstableOConFaseNoReproduciendo() {
        assertNull(clasificarCausaImagen(imagenOk))
        assertNull(clasificarCausaImagen(sinImagen.copy(fase = FaseBase.BUFFERING)))
        assertNull(clasificarCausaImagen(sinImagen.copy(audioActivo = false)))
    }

    // ------------------------------------------------------------------
    // Detección con ventana de confirmación (S5: no falsos positivos).
    // ------------------------------------------------------------------

    @Test
    fun unStreamSoloAudioNuncaDeclaraFalloDeImagen() {
        val m = maquina()
        // Reproduciendo durante mucho tiempo con un stream sin video: nunca
        // se abre un caso ni se ejecuta un nivel; solo se publica SIN_VIDEO.
        repeat(6) { indice ->
            val ahora = indice * 10_000L
            val estado =
                m.tick(
                    EvidenciaImagen(
                        fase = FaseBase.REPRODUCIENDO,
                        audioActivo = true,
                        hayGrupoVideo = false,
                    ),
                    ahora,
                    permitirReconstruccion = true,
                )
            assertEquals(EstadoImagen.SIN_VIDEO, estado)
        }
        assertNull(m.nivelPendiente(permitirReconstruccion = true))
        assertFalse(m.debeEjecutarNivel(permitirReconstruccion = true))
    }

    @Test
    fun laPreparacionNormalNoDeclaraAudioSinImagen() {
        val m = maquina()
        // Antes de la ventana: solo estados de preparación/espera. La ventana
        // se acumula solo durante la reproducción (0 + 2 s + 3,999 s).
        assertEquals(
            EstadoImagen.PREPARANDO_VIDEO,
            m.tick(sinImagen.copy(decoderVideoInicializado = false), 0L, true),
        )
        assertEquals(EstadoImagen.ESPERANDO_PRIMER_FRAME, m.tick(sinImagen, 2_000L, true))
        assertEquals(EstadoImagen.ESPERANDO_PRIMER_FRAME, m.tick(sinImagen, 5_999L, true))
        // La confirmación llega exactamente al vencer la ventana (6 s).
        assertEquals(EstadoImagen.RECUPERANDO_IMAGEN, m.tick(sinImagen, 6_000L, true))
    }

    @Test
    fun elBufferingCongelaLaVentanaDeConfirmacion() {
        val m = maquina()
        // 5 s reproduciendo sin imagen (ventana acumulada: 5 s)…
        assertEquals(EstadoImagen.ESPERANDO_PRIMER_FRAME, m.tick(sinImagen, 0L, true))
        assertEquals(EstadoImagen.ESPERANDO_PRIMER_FRAME, m.tick(sinImagen, 5_000L, true))
        // …10 s en buffering (no cuentan para la ventana)…
        assertEquals(
            EstadoImagen.PREPARANDO_VIDEO,
            m.tick(sinImagen.copy(fase = FaseBase.BUFFERING), 15_000L, true),
        )
        // …y al volver, con 5,999 s acumulados sigue esperando: el tiempo de
        // buffering no empujó la ventana.
        assertEquals(EstadoImagen.ESPERANDO_PRIMER_FRAME, m.tick(sinImagen, 15_999L, true))
        assertEquals(EstadoImagen.RECUPERANDO_IMAGEN, m.tick(sinImagen, 16_000L, true))
    }

    @Test
    fun laFaseDetenidaCierraLaVentanaPeroConservaElCaso() {
        val m = maquina()
        assertEquals(EstadoImagen.ESPERANDO_PRIMER_FRAME, m.tick(sinImagen, 0L, true))
        assertEquals(EstadoImagen.RECUPERANDO_IMAGEN, m.tick(sinImagen, 6_000L, true))
        // Reintento T3 en curso: la ventana se congela, el caso no se cancela.
        assertNull(m.tick(sinImagen.copy(fase = FaseBase.DETENIDA), 30_000L, true))
        assertEquals(EstadoImagen.RECUPERANDO_IMAGEN, m.tick(sinImagen, 31_000L, true))
        // El primer nivel sigue pendiente de ejecución.
        assertTrue(m.debeEjecutarNivel(permitirReconstruccion = true))
    }

    @Test
    fun elVideoQueSePierdeTrasRenderizarSeDetectaComoPistaNoSeleccionada() {
        // El primer frame ya se vio (sticky por sesión) pero la pista de
        // video quedó deseleccionada: la imagen se perdió y hay que repone.
        val perdido = imagenOk.copy(primerFrameVisto = false, pistaVideoSeleccionada = false)
        val m = maquina()
        assertEquals(EstadoImagen.PREPARANDO_VIDEO, m.tick(perdido, 0L, true))
        assertEquals(EstadoImagen.RECUPERANDO_IMAGEN, m.tick(perdido, ventana, true))
        assertEquals(CausaImagen.PISTA_NO_SELECCIONADA, m.causa)
    }

    // ------------------------------------------------------------------
    // Escalera por niveles (S5: del menos invasivo al más costoso).
    // ------------------------------------------------------------------

    @Test
    fun sinPistaDeVideoSoportadaLaEscaleraNoEjecutaNivelesYTerminaEnDecodificacion() {
        val m = maquina()
        val evidencia = sinImagen.copy(pistaVideoSeleccionada = false, pistaVideoSoportada = false)
        // El caso se abre al vencer la ventana…
        assertEquals(EstadoImagen.PREPARANDO_VIDEO, m.tick(evidencia, 0L, true))
        assertEquals(EstadoImagen.RECUPERANDO_IMAGEN, m.tick(evidencia, ventana, true))
        // …pero no hay ningún nivel que ejecutar: forzar la selección de una
        // pista sin decoder dispararía un error de decoder y mataría el audio.
        assertNull(m.nivelPendiente(permitirReconstruccion = true))
        assertFalse(m.debeEjecutarNivel(permitirReconstruccion = true))
        // Con la gracia vencida, terminal pegajoso de decodificación, sin bucle.
        assertEquals(EstadoImagen.ERROR_DECODIFICACION, m.tick(evidencia, 2 * ventana, true))
        assertEquals(EstadoImagen.ERROR_DECODIFICACION, m.tick(evidencia, 3 * ventana, true))
        assertNull(m.nivelPendiente(permitirReconstruccion = true))
    }

    @Test
    fun laEscaleraEmpiezaPorRestaurarLaSuperficie() {
        val m = maquina()
        m.tick(sinImagen, 0L, true)
        m.tick(sinImagen, ventana, true)
        assertEquals(NivelImagen.RESTAURAR_SUPERFICIE, m.nivelPendiente(true))
        assertTrue(m.debeEjecutarNivel(true))
    }

    @Test
    fun laSeleccionSeReponeSoloParaPistasNoSeleccionadas() {
        val m = maquina()
        m.tick(sinImagen, 0L, true)
        m.tick(sinImagen, ventana, true) // caso RENDERER_SIN_FRAMES
        m.registrarNivel(NivelImagen.RESTAURAR_SUPERFICIE, ventana)
        // Para un renderer sin frames la selección no es la causa: se salta.
        assertEquals(NivelImagen.REPREPARAR, m.nivelPendiente(true))
    }

    @Test
    fun reponerSeleccionSigueARestaurarCuandoLaPistaNoEstaSeleccionada() {
        val m = maquina()
        m.tick(sinImagen.copy(pistaVideoSeleccionada = false), 0L, true)
        m.tick(sinImagen.copy(pistaVideoSeleccionada = false), ventana, true)
        m.registrarNivel(NivelImagen.RESTAURAR_SUPERFICIE, ventana)
        assertEquals(NivelImagen.REPONER_SELECCION, m.nivelPendiente(true))
    }

    @Test
    fun reconstruirEsElUltimoNivelYRespetaElPermiso() {
        val m = maquina()
        m.tick(sinImagen, 0L, true)
        m.tick(sinImagen, ventana, true)
        m.registrarNivel(NivelImagen.RESTAURAR_SUPERFICIE, ventana)
        m.registrarNivel(NivelImagen.REPREPARAR, 2 * ventana)
        // Sin permiso no hay reconstrucción: la escalera terminó.
        assertNull(m.nivelPendiente(permitirReconstruccion = false))
        // Con permiso, la reconstrucción es el nivel final.
        assertEquals(NivelImagen.RECONSTRUIR, m.nivelPendiente(permitirReconstruccion = true))
    }

    @Test
    fun agotadaLaEscaleraSeTerminaSinBucle() {
        val m = maquina()
        m.tick(sinImagen, 0L, true)
        m.tick(sinImagen, ventana, true)
        m.registrarNivel(NivelImagen.RESTAURAR_SUPERFICIE, ventana)
        m.registrarNivel(NivelImagen.REPREPARAR, 2 * ventana)
        m.registrarNivel(NivelImagen.RECONSTRUIR, 3 * ventana)
        // Todos los niveles usados y la imagen sigue rota tras la gracia del
        // último nivel: estado terminal.
        assertEquals(
            EstadoImagen.ERROR_RENDERIZADO,
            m.tick(sinImagen, 4 * ventana, true),
        )
        // El terminal es pegajoso: no vuelve a abrir casos ni a ejecutar niveles.
        assertEquals(EstadoImagen.ERROR_RENDERIZADO, m.tick(sinImagen, 10 * ventana, true))
        assertNull(m.nivelPendiente(true))
        assertFalse(m.debeEjecutarNivel(true))
    }

    @Test
    fun elTerminalDecodificacionSePublicaCuandoLaCausaEsElDecoder() {
        val m = maquina()
        m.tick(sinImagen.copy(decoderVideoInicializado = false), 0L, true)
        m.tick(sinImagen.copy(decoderVideoInicializado = false), ventana, true)
        m.registrarNivel(NivelImagen.RESTAURAR_SUPERFICIE, ventana)
        m.registrarNivel(NivelImagen.REPREPARAR, 2 * ventana)
        m.registrarNivel(NivelImagen.RECONSTRUIR, 3 * ventana)
        assertEquals(EstadoImagen.ERROR_DECODIFICACION, m.tick(sinImagen, 4 * ventana, true))
    }

    @Test
    fun cadaNivelRecibeSuPropiaVentanaDeGracia() {
        val m = maquina()
        m.tick(sinImagen, 0L, true)
        assertEquals(EstadoImagen.RECUPERANDO_IMAGEN, m.tick(sinImagen, ventana, true))
        m.registrarNivel(NivelImagen.RESTAURAR_SUPERFICIE, ventana)
        // Tras registrar el nivel, la ventana vuelve a cero: el nivel tiene
        // su propia gracia antes de subir al siguiente.
        assertEquals(
            EstadoImagen.RESTAURANDO_SUPERFICIE,
            m.tick(sinImagen, ventana + 1_000L, true),
        )
        assertFalse(m.debeEjecutarNivel(true))
        assertEquals(
            EstadoImagen.RESTAURANDO_SUPERFICIE,
            m.tick(sinImagen, 2 * ventana, true),
        )
        // Cumplida la gracia, el siguiente nivel queda pendiente.
        assertTrue(m.debeEjecutarNivel(true))
        assertEquals(NivelImagen.REPREPARAR, m.nivelPendiente(true))
        m.registrarNivel(NivelImagen.REPREPARAR, 2 * ventana)
        assertEquals(
            EstadoImagen.REINICIALIZANDO_RENDERER,
            m.tick(sinImagen, 2 * ventana + 1_000L, true),
        )
    }

    // ------------------------------------------------------------------
    // Recuperación y reposición (S5: sin reinicios innecesarios).
    // ------------------------------------------------------------------

    @Test
    fun laImagenRecuperadaReponeLaMaquinaYPublicaElAviso() {
        val m = maquina()
        m.tick(sinImagen, 0L, true)
        m.tick(sinImagen, ventana, true)
        m.registrarNivel(NivelImagen.RESTAURAR_SUPERFICIE, ventana)
        // La superficie se restauró y el frame llegó: se publica el aviso…
        assertEquals(
            EstadoImagen.IMAGEN_RECUPERADA,
            m.tick(imagenOk, ventana + 1_000L, true),
        )
        // …y la máquina queda limpia (sin caso, sin niveles ni terminal).
        assertNull(m.nivelPendiente(true))
        // Volver a fallar después abre un caso nuevo con presupuesto completo;
        // la ventana nueva arranca limpia.
        assertEquals(EstadoImagen.ESPERANDO_PRIMER_FRAME, m.tick(sinImagen, ventana + 2_000L, true))
        assertEquals(
            EstadoImagen.RECUPERANDO_IMAGEN,
            m.tick(sinImagen, ventana + 2_000L + ventana, true),
        )
        assertEquals(NivelImagen.RESTAURAR_SUPERFICIE, m.nivelPendiente(true))
    }

    @Test
    fun unaSesionEstableNoPublicaEstadoDeImagen() {
        // Reproducción normal con imagen: nada que publicar (evita avisos
        // continuos cuando la recuperación funciona, S5/F7).
        assertNull(maquina().tick(imagenOk, 10_000L, true))
    }

    @Test
    fun laMaquinaSobreviveUnaNuevaCausaTrasTerminalYRecuperacion() {
        val m = maquina()
        m.tick(sinImagen, 0L, true)
        m.tick(sinImagen, ventana, true)
        m.registrarNivel(NivelImagen.RESTAURAR_SUPERFICIE, ventana)
        m.registrarNivel(NivelImagen.REPREPARAR, 2 * ventana)
        m.registrarNivel(NivelImagen.RECONSTRUIR, 3 * ventana)
        assertEquals(EstadoImagen.ERROR_RENDERIZADO, m.tick(sinImagen, 4 * ventana, true))
        // Más tarde la imagen vuelve (p. ej., tras una recuperación T3):
        // el terminal se limpia y la máquina vuelve a estar sana.
        assertEquals(EstadoImagen.IMAGEN_RECUPERADA, m.tick(imagenOk, 5 * ventana, true))
        assertNull(m.nivelPendiente(true))
        assertNull(m.tick(imagenOk, 6 * ventana, true))
        assertNull(m.causa)
    }
}
