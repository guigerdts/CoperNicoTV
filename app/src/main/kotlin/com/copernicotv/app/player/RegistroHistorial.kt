package com.copernicotv.app.player

/**
 * Decide cuándo una sesión de reproducción gana su entrada en el Historial
 * (H-01).
 *
 * Antes de esta regla, `HistorialRepositorio.registrar()` existía pero ninguna
 * reproducción lo invocaba: el acceso rápido nunca se llenaba. La marca de
 * entrada se gana con el primer [EstadoReproductor.REPRODUCIENDO] real
 * (`STATE_READY` y `isPlaying`), no al abrir la sesión: un canal que nunca
 * llega a verse no ensucia el Historial.
 *
 * La instancia vive en el ViewModel, no en la composición, así que una
 * recomposición de la pantalla no puede duplicar el registro. Pausar y
 * reanudar tampoco: la marca solo se reinicia cuando se abre una sesión nueva
 * ([reiniciar]), incluida la reconstruida tras una recuperación, que sí es una
 * reproducción real.
 */
class RegistroHistorial {
    private var registrado = false

    /** Habilita de nuevo la entrada para la sesión que empieza. */
    fun reiniciar() {
        registrado = false
    }

    /**
     * `true` exactamente una vez por sesión, en el primer REPRODUCIENDO.
     *
     * Los estados previos (CARGANDO, BUFFERING), las pausas y los reintentos no
     * consumen ni repiten la entrada.
     */
    fun alReproducir(estado: EstadoReproductor): Boolean {
        if (registrado) return false
        if (estado != EstadoReproductor.REPRODUCIENDO) return false
        registrado = true
        return true
    }
}
