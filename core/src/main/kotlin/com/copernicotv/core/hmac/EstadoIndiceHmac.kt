package com.copernicotv.core.hmac

import java.time.Instant

/** Fase del índice HMAC (DC-04 §7). Los códigos son los persistidos en `EstadoIndiceHmac.estado`. */
enum class FaseIndiceHmac(val codigo: String) {
    OPERATIVO("operativo"),
    RECUPERACION_PENDIENTE("recuperacion_pendiente"),
    RECUPERACION_EN_CURSO("recuperacion_en_curso"),
    BLOQUEADO_PARCIAL("bloqueado_parcial"),
    ;

    companion object {
        fun desde(codigo: String): FaseIndiceHmac? = entries.firstOrNull { it.codigo == codigo }
    }
}

/**
 * Generación de la clave HMAC (`hmac:vN`).
 *
 * Identifica solo la clave, nunca la versión de canonicalización. Se mantiene
 * separada de `cvM`, que viaja en cada sobre.
 */
object GeneracionHmac {
    private val FORMATO = Regex("^hmac:v([1-9][0-9]*)$")

    fun formatear(numero: Int): String {
        require(numero >= 1) { "generación HMAC inválida" }
        return "hmac:v$numero"
    }

    fun parsear(texto: String): Int? = FORMATO.matchEntire(texto)?.groupValues?.get(1)?.toIntOrNull()
}

/**
 * Estado del índice HMAC.
 *
 * `versionObjetivo` solo existe durante `recuperacion_en_curso` y
 * `bloqueado_parcial`: es la generación que se está construyendo y no se usa
 * para igualdad hasta volver a `operativo`.
 */
data class EstadoIndiceHmac(
    val fase: FaseIndiceHmac,
    val versionActiva: String,
    val versionObjetivo: String? = null,
    val iniciadoEn: Instant? = null,
    val actualizadoEn: Instant,
    val filasTotales: Long = 0,
    val filasReconstruidas: Long = 0,
    val filasPendientes: Long = 0,
    val ultimoErrorTipo: String? = null,
) {
    init {
        require(GeneracionHmac.parsear(versionActiva) != null) { "versionActiva inválida" }
        require(versionObjetivo == null || GeneracionHmac.parsear(versionObjetivo) != null) {
            "versionObjetivo inválida"
        }
        require(versionObjetivo == null || versionObjetivo != versionActiva) {
            "versionObjetivo no puede coincidir con versionActiva"
        }
        require(filasTotales >= 0 && filasReconstruidas >= 0 && filasPendientes >= 0) {
            "los conteos no pueden ser negativos"
        }
        require(filasReconstruidas + filasPendientes <= filasTotales) {
            "los conteos exceden el total de filas"
        }
        val exigeObjetivo = fase == FaseIndiceHmac.RECUPERACION_EN_CURSO || fase == FaseIndiceHmac.BLOQUEADO_PARCIAL
        require((versionObjetivo != null) == exigeObjetivo) {
            "versionObjetivo solo existe durante la reconstrucción"
        }
    }

    val generacionActiva: Int
        get() = GeneracionHmac.parsear(versionActiva) ?: error("versionActiva validada en el constructor")

    val generacionObjetivo: Int?
        get() = versionObjetivo?.let { GeneracionHmac.parsear(it) ?: error("versionObjetivo validada") }

    val esOperativo: Boolean
        get() = fase == FaseIndiceHmac.OPERATIVO
}

/** Motivo tipado de una transición rechazada. */
enum class MotivoTransicion(val codigo: String) {
    TRANSICION_NO_PERMITIDA("transicion_no_permitida"),
    GENERACION_OBJETIVO_INVALIDA("generacion_objetivo_invalida"),
    RECONSTRUCCION_INCOMPLETA("reconstruccion_incompleta"),
    FILAS_PENDIENTES_NO_RESUELTAS("filas_pendientes_no_resueltas"),
    CONTEOS_INCOHERENTES("conteos_incoherentes"),
}

/** Resultado de una transición: estado nuevo o rechazo determinista. */
sealed interface ResultadoTransicion {
    data class Ok(val estado: EstadoIndiceHmac) : ResultadoTransicion

    data class Rechazada(val motivo: MotivoTransicion) : ResultadoTransicion
}

/**
 * Transiciones de `EstadoIndiceHmac` (DC-04 §7).
 *
 * Son funciones puras: no tocan Room, no generan claves y no comparan HMAC. El
 * llamador es responsable de persistir el estado aceptado y de ejecutar la
 * reconstrucción por lotes. Cada transición es determinista y repetirla con los
 * mismos datos produce el mismo resultado.
 */
object MaquinaIndiceHmac {
    /** `operativo → recuperacion_pendiente` al detectar la clave HMAC ausente o inválida. */
    fun detectarPerdidaClave(
        actual: EstadoIndiceHmac,
        ahora: Instant,
    ): ResultadoTransicion {
        if (actual.fase != FaseIndiceHmac.OPERATIVO) {
            return rechazo(MotivoTransicion.TRANSICION_NO_PERMITIDA)
        }
        return aceptado(
            actual.copy(
                fase = FaseIndiceHmac.RECUPERACION_PENDIENTE,
                versionObjetivo = null,
                iniciadoEn = ahora,
                actualizadoEn = ahora,
                ultimoErrorTipo = null,
            ),
        )
    }

    /**
     * `recuperacion_pendiente → recuperacion_en_curso` por acción explícita del usuario.
     *
     * La generación objetivo debe ser distinta de la activa: reutilizarla no es
     * una reconstrucción y dejaría valores viejos indistinguibles de nuevos.
     */
    fun iniciarReconstruccion(
        actual: EstadoIndiceHmac,
        generacionObjetivo: Int,
        ahora: Instant,
    ): ResultadoTransicion {
        if (actual.fase != FaseIndiceHmac.RECUPERACION_PENDIENTE) {
            return rechazo(MotivoTransicion.TRANSICION_NO_PERMITIDA)
        }
        if (generacionObjetivo < 1 || generacionObjetivo == actual.generacionActiva) {
            return rechazo(MotivoTransicion.GENERACION_OBJETIVO_INVALIDA)
        }
        return aceptado(
            actual.copy(
                fase = FaseIndiceHmac.RECUPERACION_EN_CURSO,
                versionObjetivo = GeneracionHmac.formatear(generacionObjetivo),
                iniciadoEn = ahora,
                actualizadoEn = ahora,
                filasReconstruidas = 0,
                filasPendientes = 0,
                ultimoErrorTipo = null,
            ),
        )
    }

    /**
     * Registra el avance de un lote o del final de la pasada.
     *
     * Con filas pendientes el estado pasa a `bloqueado_parcial`; con todas las
     * filas en la generación objetivo pasa a `operativo`; en otro caso sigue
     * `recuperacion_en_curso`. [filasReconstruidas] no puede retroceder.
     */
    fun registrarAvance(
        actual: EstadoIndiceHmac,
        filasTotales: Long,
        filasReconstruidas: Long,
        filasPendientes: Long,
        ahora: Instant,
    ): ResultadoTransicion {
        if (actual.fase != FaseIndiceHmac.RECUPERACION_EN_CURSO) {
            return rechazo(MotivoTransicion.TRANSICION_NO_PERMITIDA)
        }
        if (!conteosCoherentes(filasTotales, filasReconstruidas, filasPendientes)) {
            return rechazo(MotivoTransicion.CONTEOS_INCOHERENTES)
        }
        if (filasReconstruidas < actual.filasReconstruidas) {
            return rechazo(MotivoTransicion.CONTEOS_INCOHERENTES)
        }
        val base =
            actual.copy(
                filasTotales = filasTotales,
                filasReconstruidas = filasReconstruidas,
                filasPendientes = filasPendientes,
                actualizadoEn = ahora,
            )
        return when {
            filasPendientes > 0 -> aceptado(base.copy(fase = FaseIndiceHmac.BLOQUEADO_PARCIAL))
            filasReconstruidas == filasTotales -> aceptado(cerrarReconstruccion(base))
            else -> aceptado(base)
        }
    }

    /**
     * Resuelve las filas pendientes por reingreso, reimportación o borrado deliberado.
     *
     * Solo se permite desde `bloqueado_parcial` y con cero pendientes. Si aún
     * quedan filas fuera de la generación objetivo, la reconstrucción se reanuda;
     * si no, el índice vuelve a `operativo`.
     */
    fun resolverPendientes(
        actual: EstadoIndiceHmac,
        filasTotales: Long,
        filasReconstruidas: Long,
        filasPendientes: Long,
        ahora: Instant,
    ): ResultadoTransicion {
        if (actual.fase != FaseIndiceHmac.BLOQUEADO_PARCIAL) {
            return rechazo(MotivoTransicion.TRANSICION_NO_PERMITIDA)
        }
        if (filasPendientes != 0L) {
            return rechazo(MotivoTransicion.FILAS_PENDIENTES_NO_RESUELTAS)
        }
        if (!conteosCoherentes(filasTotales, filasReconstruidas, filasPendientes)) {
            return rechazo(MotivoTransicion.CONTEOS_INCOHERENTES)
        }
        val base =
            actual.copy(
                filasTotales = filasTotales,
                filasReconstruidas = filasReconstruidas,
                filasPendientes = 0,
                actualizadoEn = ahora,
            )
        return if (filasReconstruidas == filasTotales) {
            aceptado(cerrarReconstruccion(base))
        } else {
            aceptado(base.copy(fase = FaseIndiceHmac.RECUPERACION_EN_CURSO))
        }
    }

    /**
     * La clave HMAC original reaparece intacta: no se crea generación nueva.
     *
     * Solo es válido si ninguna fila quedó fuera de la generación activa.
     */
    fun claveOriginalRecuperada(
        actual: EstadoIndiceHmac,
        filasFueraDeGeneracionActiva: Long,
        ahora: Instant,
    ): ResultadoTransicion {
        if (actual.fase != FaseIndiceHmac.RECUPERACION_PENDIENTE) {
            return rechazo(MotivoTransicion.TRANSICION_NO_PERMITIDA)
        }
        if (filasFueraDeGeneracionActiva != 0L) {
            return rechazo(MotivoTransicion.RECONSTRUCCION_INCOMPLETA)
        }
        return aceptado(
            actual.copy(
                fase = FaseIndiceHmac.OPERATIVO,
                versionObjetivo = null,
                iniciadoEn = null,
                actualizadoEn = ahora,
                ultimoErrorTipo = null,
            ),
        )
    }

    private fun cerrarReconstruccion(base: EstadoIndiceHmac): EstadoIndiceHmac {
        val objetivo = base.versionObjetivo ?: error("la reconstrucción en curso exige versionObjetivo")
        return base.copy(
            fase = FaseIndiceHmac.OPERATIVO,
            versionActiva = objetivo,
            versionObjetivo = null,
            iniciadoEn = null,
            filasPendientes = 0,
            ultimoErrorTipo = null,
        )
    }

    private fun conteosCoherentes(
        totales: Long,
        reconstruidas: Long,
        pendientes: Long,
    ): Boolean = totales >= 0 && reconstruidas >= 0 && pendientes >= 0 && reconstruidas + pendientes <= totales

    private fun aceptado(estado: EstadoIndiceHmac): ResultadoTransicion = ResultadoTransicion.Ok(estado)

    private fun rechazo(motivo: MotivoTransicion): ResultadoTransicion = ResultadoTransicion.Rechazada(motivo)
}
