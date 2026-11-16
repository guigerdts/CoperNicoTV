package com.copernicotv.core.player

/**
 * Estado visible de la imagen de una sesión (E7-T10, S5/F7). Solo combina
 * señales que Media3 entrega; la UI lo traduce a mensajes comprensibles y
 * evita avisos continuos cuando la recuperación funciona.
 */
enum class EstadoImagen {
    /** El stream no declara pista de video: no es un fallo de imagen. */
    SIN_VIDEO,

    /** Hay grupo de video pero la sesión todavía no decodifica imagen. */
    PREPARANDO_VIDEO,

    /** Video seleccionado y decoder listo, esperando el primer frame. */
    ESPERANDO_PRIMER_FRAME,

    /** Un caso de audio-sin-imagen está confirmado y en recuperación. */
    RECUPERANDO_IMAGEN,

    /** El nivel en curso intenta volver a conectar la superficie. */
    RESTAURANDO_SUPERFICIE,

    /** El nivel en curso reinicializa el pipeline de video. */
    REINICIALIZANDO_RENDERER,

    /** La imagen volvió tras una recuperación (aviso transitorio). */
    IMAGEN_RECUPERADA,

    /** La escalera se agotó con causa de decodificación; el audio sigue. */
    ERROR_DECODIFICACION,

    /** La escalera se agotó con causa de renderizado; el audio sigue. */
    ERROR_RENDERIZADO,
}

/**
 * Causa confirmada de «audio activo sin imagen» (E7-T10, S5).
 *
 * Se deriva solo de evidencia observada: pista seleccionada, decoder
 * inicializado y superficie reportada. Nada de esto se asume.
 */
enum class CausaImagen {
    /** Hay grupo de video pero ninguna pista de video seleccionada. */
    PISTA_NO_SELECCIONADA,

    /** Hay pista de video seleccionada pero el decoder no arrancó. */
    DECODER_NO_INICIALIZADO,

    /** La superficie reportada está inactiva (0x0 o perdida). */
    SUPERFICIE_PERDIDA,

    /** Selección, decoder y superficie correctos y aun así sin frames. */
    RENDERER_SIN_FRAMES,
}

/** Niveles de la escalera de recuperación de imagen, del menos invasivo al más costoso (S5). */
enum class NivelImagen {
    /** Verificar y volver a conectar la superficie de la vista. */
    RESTAURAR_SUPERFICIE,

    /** Reponer la selección y habilitación de la pista de video. */
    REPONER_SELECCION,

    /** Repreparar el recurso preservando la posición. */
    REPREPARAR,

    /** Reconstruir la sesión completa (solo si las anteriores no bastaron). */
    RECONSTRUIR,
}

/**
 * Evidencia audiovisual que alimenta la política de imagen (S5).
 *
 * Cada campo es opcional porque la sesión solo publica lo que Media3
 * reportó; los valores nulos nunca se asumen como fallo.
 */
data class EvidenciaImagen(
    val fase: FaseBase,
    val audioActivo: Boolean? = null,
    val hayGrupoVideo: Boolean? = null,
    val pistaVideoSeleccionada: Boolean? = null,
    val decoderVideoInicializado: Boolean? = null,
    val primerFrameVisto: Boolean? = null,
    val superficieActiva: Boolean? = null,
)

/**
 * Clasifica la causa del caso «audio activo sin imagen» con la evidencia
 * dada (E7-T10, S5). Devuelve `null` cuando no hay caso: imagen estable,
 * fase que no reproduce, o audio inactivo.
 *
 * Reglas, en orden:
 * 1. Sin grupo de video (o sin audio activo) no hay nada que recuperar.
 * 2. Con imagen estable no hay caso.
 * 3. Grupo de video sin pista seleccionada → pista no seleccionada.
 * 4. Pista seleccionada sin decoder → decoder no inicializado.
 * 5. Decoder listo con superficie reportada inactiva → superficie perdida.
 * 6. Todo lo demás correcto y sin primer frame → renderer sin frames.
 */
fun clasificarCausaImagen(evidencia: EvidenciaImagen): CausaImagen? {
    if (!hayCasoImagen(evidencia)) return null
    return causaDeCaso(evidencia)
}

/** «Hay caso» (S5): grupo video, audio activo, reproduciendo y sin imagen estable. */
private fun hayCasoImagen(evidencia: EvidenciaImagen): Boolean =
    evidencia.hayGrupoVideo == true &&
        evidencia.audioActivo == true &&
        evidencia.fase == FaseBase.REPRODUCIENDO &&
        !(evidencia.primerFrameVisto == true && evidencia.pistaVideoSeleccionada == true)

/** Causa del caso confirmado, por la primera evidencia que falta (S5). */
private fun causaDeCaso(evidencia: EvidenciaImagen): CausaImagen =
    when {
        evidencia.pistaVideoSeleccionada != true -> CausaImagen.PISTA_NO_SELECCIONADA
        evidencia.decoderVideoInicializado != true -> CausaImagen.DECODER_NO_INICIALIZADO
        evidencia.superficieActiva == false -> CausaImagen.SUPERFICIE_PERDIDA
        else -> CausaImagen.RENDERER_SIN_FRAMES
    }

/**
 * Configuración de la detección y escalera de imagen (E7-T10, S5).
 *
 * [ventanaConfirmacionMs] es cuánto tiempo de audio activo sin imagen debe
 * acumular la máquina en REPRODUCIENDO antes de abrir un caso: más corto
 * arriesga falsos positivos con buffering, más largo retrasa la recuperación.
 * Es configurable para las pruebas instrumentadas (ventana corta).
 */
data class ConfiguracionImagen(
    val ventanaConfirmacionMs: Long = 10_000L,
)

/**
 * Máquina de detección y escalera de recuperación de imagen (E7-T10, S5).
 *
 * Igual que la política de conexión, vive en `:core` como máquina pura: la
 * sesión la sondea con evidencia observada y un reloj inyectado por la
 * sonda. La ventana de confirmación se acumula solo mientras la reproducción
 * avanza (el buffering la congela), cada nivel ejecutado recibe su propia
 * gracia y el agotamiento publica un terminal pegajoso sin volver a abrir
 * casos: no hay bucles de reinicio. La reconstrucción de sesión solo entra
 * si la sesión declara `permitirReconstruccion`.
 */
class MaquinaImagen(
    private val ventanaMs: Long,
) {
    /** Causa del caso abierto; `null` mientras no haya caso. */
    var causa: CausaImagen? = null
        private set

    private var casoAbierto = false
    private var nivelesUsados = mutableSetOf<NivelImagen>()
    private var ultimoNivel: NivelImagen? = null
    private var terminal: EstadoImagen? = null
    private var esperaMs = 0L
    private var ultimoMs: Long? = null

    /**
     * Sonda de la sesión: avanza la máquina con la evidencia observada y
     * devuelve el estado a publicar (`null` cuando no hay nada que decir).
     */
    fun tick(
        evidencia: EvidenciaImagen,
        ahoraMs: Long,
        permitirReconstruccion: Boolean,
    ): EstadoImagen? =
        if (evidencia.hayGrupoVideo != true) {
            sondaSinVideo(evidencia)
        } else {
            sondaConVideo(evidencia, ahoraMs, permitirReconstruccion)
        }

    /** Sonda sin grupo de video: stream solo-audio nunca es un fallo de imagen. */
    private fun sondaSinVideo(evidencia: EvidenciaImagen): EstadoImagen? {
        if (evidencia.audioActivo != true) return null
        reiniciar()
        return EstadoImagen.SIN_VIDEO
    }

    /** Sonda con grupo de video: ventana, caso y escalera (S5). */
    private fun sondaConVideo(
        evidencia: EvidenciaImagen,
        ahoraMs: Long,
        permitirReconstruccion: Boolean,
    ): EstadoImagen? {
        if (evidencia.fase != FaseBase.REPRODUCIENDO) {
            return fueraDeReproduccion(evidencia, ahoraMs)
        }
        if (evidencia.audioActivo != true) return null
        if (evidencia.primerFrameVisto == true && evidencia.pistaVideoSeleccionada == true) {
            return imagenVuelta()
        }
        return avanzaEnReproduccion(evidencia, ahoraMs, permitirReconstruccion)
    }

    /** Fuera de reproducción: preparación visible y ventana congelada. */
    private fun fueraDeReproduccion(
        evidencia: EvidenciaImagen,
        ahoraMs: Long,
    ): EstadoImagen? {
        // El tiempo de buffering/carga no avanza la ventana: se marca el
        // reloj sin acumular (S5: no falsos positivos durante buffering).
        ultimoMs = ahoraMs
        val enCarga =
            evidencia.fase == FaseBase.BUFFERING || evidencia.fase == FaseBase.ESPERANDO
        if (!enCarga) return null
        return estadoPreparacion(evidencia, enReproduccion = false)
    }

    /** La imagen volvió: se publica el aviso una vez y la máquina queda limpia. */
    private fun imagenVuelta(): EstadoImagen? {
        val estabaRecuperando = casoAbierto || nivelesUsados.isNotEmpty() || terminal != null
        reiniciar()
        return if (estabaRecuperando) EstadoImagen.IMAGEN_RECUPERADA else null
    }

    /** Ventana acumulada con el tiempo realmente reproducido (S5). */
    private fun avanzaEnReproduccion(
        evidencia: EvidenciaImagen,
        ahoraMs: Long,
        permitirReconstruccion: Boolean,
    ): EstadoImagen? {
        esperaMs += maxOf(0L, ahoraMs - (ultimoMs ?: ahoraMs))
        ultimoMs = ahoraMs
        if (!casoAbierto) {
            if (esperaMs < ventanaMs) return estadoPreparacion(evidencia, enReproduccion = true)
            casoAbierto = true
            causa = clasificarCausaImagen(evidencia) ?: CausaImagen.RENDERER_SIN_FRAMES
            return EstadoImagen.RECUPERANDO_IMAGEN
        }
        // Caso abierto: si la escalera está agotada y la gracia del último
        // nivel venció, se publica el terminal pegajoso (sin bucle).
        if (escaleraAgotada(permitirReconstruccion) && esperaMs >= ventanaMs) {
            terminal =
                if (causa == CausaImagen.DECODER_NO_INICIALIZADO) {
                    EstadoImagen.ERROR_DECODIFICACION
                } else {
                    EstadoImagen.ERROR_RENDERIZADO
                }
        }
        terminal?.let { return it }
        return estadoDeNivel(ultimoNivel) ?: EstadoImagen.RECUPERANDO_IMAGEN
    }

    /**
     * Abre un caso con la causa dada sin esperar la ventana (E7-T10, S5).
     *
     * Es un gancho de pruebas instrumentadas: la evidencia real «audio
     * activo sin imagen» no siempre se puede provocar en un emulador, así
     * que la prueba fuerza la causa y observa cómo la sesión ejecuta la
     * escalera. Nada del camino de producción pasa por aquí.
     */
    fun abrirCaso(causa: CausaImagen) {
        casoAbierto = true
        this.causa = causa
        nivelesUsados.clear()
        ultimoNivel = null
        terminal = null
        esperaMs = 0L
        ultimoMs = null
    }

    /**
     * `true` cuando la sesión debe ejecutar el siguiente nivel ahora: hay
     * caso abierto, la escalera no está agotada y no queda gracia pendiente.
     */
    fun debeEjecutarNivel(permitirReconstruccion: Boolean): Boolean =
        terminal == null &&
            casoAbierto &&
            (nivelesUsados.isEmpty() || esperaMs >= ventanaMs) &&
            nivelPendiente(permitirReconstruccion) != null

    /** Siguiente nivel sin usar de la escalera; `null` cuando no hay caso o no queda ninguno. */
    fun nivelPendiente(permitirReconstruccion: Boolean): NivelImagen? {
        if (!casoAbierto || terminal != null) return null
        return escalera(causa, permitirReconstruccion).firstOrNull { it !in nivelesUsados }
    }

    /** Marca un nivel como ejecutado y le concede su ventana de gracia. */
    fun registrarNivel(
        nivel: NivelImagen,
        @Suppress("UNUSED_PARAMETER") ahoraMs: Long,
    ) {
        nivelesUsados.add(nivel)
        ultimoNivel = nivel
        esperaMs = 0L
    }

    /** Escalera ordenada para la causa dada: superficie, causa, repreparar, reconstruir. */
    private fun escalera(
        causa: CausaImagen?,
        permitirReconstruccion: Boolean,
    ): List<NivelImagen> {
        val niveles = mutableListOf(NivelImagen.RESTAURAR_SUPERFICIE)
        if (causa == CausaImagen.PISTA_NO_SELECCIONADA) {
            niveles.add(NivelImagen.REPONER_SELECCION)
        }
        niveles.add(NivelImagen.REPREPARAR)
        if (permitirReconstruccion) niveles.add(NivelImagen.RECONSTRUIR)
        return niveles
    }

    private fun escaleraAgotada(permitirReconstruccion: Boolean): Boolean = nivelPendiente(permitirReconstruccion) == null

    private fun reiniciar() {
        casoAbierto = false
        causa = null
        nivelesUsados = mutableSetOf()
        ultimoNivel = null
        terminal = null
        esperaMs = 0L
        ultimoMs = null
    }

    /** Estado previo a la confirmación, según fase y evidencia. */
    private fun estadoPreparacion(
        evidencia: EvidenciaImagen,
        enReproduccion: Boolean,
    ): EstadoImagen? {
        if (evidencia.hayGrupoVideo != true) return null
        val conImagen = evidencia.primerFrameVisto == true && evidencia.pistaVideoSeleccionada == true
        if (conImagen) return null
        if (!enReproduccion) return EstadoImagen.PREPARANDO_VIDEO
        val listoParaFrame =
            evidencia.pistaVideoSeleccionada == true && evidencia.decoderVideoInicializado == true
        return if (listoParaFrame) EstadoImagen.ESPERANDO_PRIMER_FRAME else EstadoImagen.PREPARANDO_VIDEO
    }

    /** Estado publicado mientras un nivel ejecutado espera su resultado. */
    private fun estadoDeNivel(nivel: NivelImagen?): EstadoImagen? =
        when (nivel) {
            NivelImagen.RESTAURAR_SUPERFICIE -> EstadoImagen.RESTAURANDO_SUPERFICIE
            NivelImagen.REPREPARAR, NivelImagen.RECONSTRUIR -> EstadoImagen.REINICIALIZANDO_RENDERER
            NivelImagen.REPONER_SELECCION -> EstadoImagen.RECUPERANDO_IMAGEN
            null -> null
        }
}
