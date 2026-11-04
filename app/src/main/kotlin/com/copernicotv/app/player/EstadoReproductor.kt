package com.copernicotv.app.player

/** Fase observable de la sesión de reproducción (E6-T2, ampliada en E6-T4). */
enum class EstadoReproductor {
    CARGANDO,
    BUFFERING,
    REPRODUCIENDO,
    PAUSADO,
    TERMINADO,
    REINTENTANDO,
    ERROR,
}

/**
 * Estado de reproducción que la interfaz puede leer sin tocar el motor.
 *
 * `posicionMs`, `duracionMs` y `bufferMs` son milisegundos; `duracionMs` vale
 * cero cuando el contenido todavía no declara duración. El código de error es
 * propio y estable: nunca contiene la URL ni el mensaje de Media3 (S57a).
 *
 * `reintento` y `reintentosMaximos` solo tienen sentido en
 * [EstadoReproductor.REINTENTANDO]: indican el número de reintento en curso y
 * el total configurado por S20. Fuera de esa fase valen cero.
 */
data class EstadoSesion(
    val estado: EstadoReproductor = EstadoReproductor.CARGANDO,
    val posicionMs: Long = 0L,
    val duracionMs: Long = 0L,
    val bufferMs: Long = 0L,
    val puedeBuscar: Boolean = false,
    val codigoError: String? = null,
    val reintento: Int = 0,
    val reintentosMaximos: Int = 0,
)

/**
 * Nombre del estado del motor Media3 para la etiqueta de diagnóstico.
 *
 * Conserva la terminología de E6-T1 (`READY`, `ENDED`) para que la evidencia
 * anterior siga siendo comparable entre incrementos.
 */
fun nombreEstadoMotor(fase: EstadoReproductor): String =
    when (fase) {
        EstadoReproductor.CARGANDO,
        EstadoReproductor.REINTENTANDO,
        EstadoReproductor.ERROR,
        -> "IDLE"
        EstadoReproductor.BUFFERING -> "BUFFERING"
        EstadoReproductor.REPRODUCIENDO, EstadoReproductor.PAUSADO -> "READY"
        EstadoReproductor.TERMINADO -> "ENDED"
    }
