package com.copernicotv.core.hmac

import com.copernicotv.core.crypto.MotivoCifrado
import com.copernicotv.core.crypto.ResultadoDescifrado
import com.copernicotv.core.identity.ResultadoSobre
import com.copernicotv.core.identity.SobreHmac

/**
 * Motivos por los que una fila queda pendiente de reconstrucción (DC-04 §7).
 *
 * El motivo es diagnóstico y determinista: nunca incluye la URL ni el valor
 * cifrado. [codigo] es el texto persistido en `HmacPendiente.motivo`.
 */
enum class MotivoHmacPendiente(val codigo: String) {
    CIPHERTEXT_CORRUPTO("ciphertext_corrupto"),
    AAD_INCORRECTO("aad_incorrecto"),
    VALOR_ORIGINAL_AUSENTE("valor_original_ausente"),
    CLAVE_AES_NO_DISPONIBLE("clave_aes_no_disponible"),
    VERSION_CANONICA_DESCONOCIDA("version_canonica_desconocida"),
    VERSION_CANONICA_NO_SOPORTADA("version_canonica_no_soportada"),
    HMAC_FORMATO_INVALIDO("hmac_formato_invalido"),
    CANONICALIZACION_FALLIDA("canonicalizacion_fallida"),
    ;

    companion object {
        fun desde(codigo: String): MotivoHmacPendiente? = entries.firstOrNull { it.codigo == codigo }
    }
}

/**
 * Versión de canonicalización declarada por un sobre HMAC pendiente.
 *
 * Un sobre legacy no declara `cvM`, así que su versión de origen es
 * [Desconocida]; nunca se asume `cv1`.
 */
sealed interface VersionOrigenHmac {
    val texto: String

    data class Cv(val numero: Int) : VersionOrigenHmac {
        init {
            require(numero in 1..255) { "cvM fuera de rango" }
        }

        override val texto: String
            get() = "cv$numero"
    }

    data object Desconocida : VersionOrigenHmac {
        override val texto: String
            get() = "desconocida"
    }

    companion object {
        private val FORMATO = Regex("^cv([1-9][0-9]{0,2})$")

        fun parsear(texto: String): VersionOrigenHmac? {
            if (texto == Desconocida.texto) return Desconocida
            val numero = FORMATO.matchEntire(texto)?.groupValues?.get(1)?.toIntOrNull() ?: return null
            return if (numero in 1..255) Cv(numero) else null
        }
    }
}

/**
 * Fila pendiente de reconstrucción HMAC.
 *
 * La clave única es `(entidad, entidadId, columna)`: una misma fila puede tener
 * varias columnas HMAC pendientes y cada una se resuelve por separado.
 */
data class HmacPendiente(
    val entidad: String,
    val entidadId: Long,
    val columna: String,
    val versionOrigen: VersionOrigenHmac,
    val motivo: MotivoHmacPendiente,
) {
    val claveUnica: Triple<String, Long, String>
        get() = Triple(entidad, entidadId, columna)
}

/** Decisión por fila durante la reconstrucción. */
sealed interface DecisionReconstruccion {
    /** No hay valor HMAC persistido para esa columna: no hay nada que reconstruir. */
    data object SinValor : DecisionReconstruccion

    /** El sobre ya usa la generación objetivo: la fila no se toca. */
    data object YaActualizada : DecisionReconstruccion

    /** Hay que recalcular el HMAC con la generación objetivo conservando `cvM`. */
    data class Reconstruir(val versionCanonica: Int) : DecisionReconstruccion

    /** No es reconstruible de forma segura: se registra como pendiente. */
    data class Pendiente(
        val versionOrigen: VersionOrigenHmac,
        val motivo: MotivoHmacPendiente,
    ) : DecisionReconstruccion
}

/** Bloqueo tipado de las operaciones de igualdad HMAC. */
data class RechazoIndiceHmac(val codigo: String = CODIGO) {
    companion object {
        const val CODIGO: String = "indice_hmac_no_operativo"
    }
}

/**
 * Plan de reconstrucción del índice HMAC (DC-04 §7).
 *
 * Decide, para un sobre persistido, si hay que reconstruirlo, si ya está en la
 * generación objetivo o si debe quedar pendiente con su motivo tipado. No toca
 * Room, no descifra y no genera claves: la pasada real vive en la capa de
 * persistencia, que reutiliza estas decisiones.
 */
object PlanReconstruccionHmac {
    /** Versiones de canonicalización implementadas. Una no listada queda pendiente. */
    val VERSIONES_SOPORTADAS: Set<Int> = setOf(1)

    /**
     * Decide la acción para un sobre persistido.
     *
     * [sobre] `null` significa columna sin valor; un sobre en blanco es un sobre
     * malformado y queda pendiente. Un sobre legacy nunca se asume `cv1`.
     */
    fun decidir(
        sobre: String?,
        generacionObjetivo: Int,
    ): DecisionReconstruccion {
        if (sobre == null) return DecisionReconstruccion.SinValor
        return when (val resultado = SobreHmac.parsear(sobre)) {
            is ResultadoSobre.Versionado -> decidirVersionado(resultado, generacionObjetivo)
            is ResultadoSobre.Legacy ->
                DecisionReconstruccion.Pendiente(
                    VersionOrigenHmac.Desconocida,
                    MotivoHmacPendiente.VERSION_CANONICA_DESCONOCIDA,
                )
            is ResultadoSobre.Invalido ->
                DecisionReconstruccion.Pendiente(
                    VersionOrigenHmac.Desconocida,
                    MotivoHmacPendiente.HMAC_FORMATO_INVALIDO,
                )
        }
    }

    /**
     * Motivo pendiente que corresponde a un descifrado fallido, o `null` si el
     * descifrado fue correcto.
     *
     * Una autenticación GCM fallida se informa como [MotivoHmacPendiente.CIPHERTEXT_CORRUPTO]:
     * el AAD usado es siempre el contractual de la columna, así que la causa
     * observable es el valor alterado. [MotivoHmacPendiente.AAD_INCORRECTO] queda
     * reservado para una incoherencia de AAD detectada explícitamente por la capa
     * de persistencia (por ejemplo, un valor movido de columna).
     */
    fun motivoPorDescifrado(resultado: ResultadoDescifrado): MotivoHmacPendiente? =
        when (resultado) {
            is ResultadoDescifrado.Ok -> null
            is ResultadoDescifrado.Fallo ->
                when (resultado.motivo) {
                    MotivoCifrado.FORMATO_INVALIDO -> MotivoHmacPendiente.CIPHERTEXT_CORRUPTO
                    MotivoCifrado.CLAVE_INVALIDA -> MotivoHmacPendiente.CLAVE_AES_NO_DISPONIBLE
                    MotivoCifrado.FALLO_AUTENTICACION -> MotivoHmacPendiente.CIPHERTEXT_CORRUPTO
                }
        }

    /**
     * Indica si dos sobres pueden compararse por igualdad.
     *
     * Solo se comparan sobres versionados, de la generación activa y de la misma
     * versión de canonicalización implementada. Un sobre legacy, uno de otra
     * generación o uno con `cvM` distinto nunca se compara.
     */
    fun puedenCompararse(
        primero: String,
        segundo: String,
        generacionActiva: Int,
    ): Boolean {
        val a = SobreHmac.parsear(primero) as? ResultadoSobre.Versionado ?: return false
        val b = SobreHmac.parsear(segundo) as? ResultadoSobre.Versionado ?: return false
        return a.generacion == generacionActiva &&
            b.generacion == generacionActiva &&
            a.versionCanonica == b.versionCanonica &&
            a.versionCanonica in VERSIONES_SOPORTADAS
    }

    /**
     * Devuelve el bloqueo de igualdad HMAC si el índice no está operativo.
     *
     * Mientras el índice está en recuperación o bloqueado, ninguna operación de
     * igualdad puede apoyarse en HMAC: debe resolver por otra vía o esperar.
     */
    fun bloqueoIgualdad(estado: EstadoIndiceHmac): RechazoIndiceHmac? = if (estado.esOperativo) null else RechazoIndiceHmac()

    private fun decidirVersionado(
        sobre: ResultadoSobre.Versionado,
        generacionObjetivo: Int,
    ): DecisionReconstruccion {
        if (sobre.versionCanonica !in VERSIONES_SOPORTADAS) {
            return DecisionReconstruccion.Pendiente(
                VersionOrigenHmac.Cv(sobre.versionCanonica),
                MotivoHmacPendiente.VERSION_CANONICA_NO_SOPORTADA,
            )
        }
        if (sobre.generacion == generacionObjetivo) return DecisionReconstruccion.YaActualizada
        return DecisionReconstruccion.Reconstruir(sobre.versionCanonica)
    }
}
