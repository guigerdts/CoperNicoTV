package com.copernicotv.app.data

import com.copernicotv.core.hmac.RechazoIndiceHmac
import javax.crypto.SecretKey

/**
 * Resultado del guardia de sello del índice HMAC (DC-04 §7, Q2/Q3).
 *
 * Toda escritura que produce un sobre `hmac:vN:...` pasa por
 * [IndiceHmacRepositorio.selloHmac]. El guardia responde con la clave de la
 * generación activa, o con el bloqueo tipado cuando el índice no está
 * operativo o la clave activa desapareció — nunca crea una clave sustituta.
 */
sealed interface SelloIndiceHmac {
    /** Se puede sellar con la generación indicada. */
    data class Disponible(
        val clave: SecretKey,
        val generacion: Int,
    ) : SelloIndiceHmac

    /** Escritura e igualdad dependientes del índice bloqueadas con su motivo. */
    data class Bloqueada(val motivo: RechazoIndiceHmac) : SelloIndiceHmac {
        val codigo: String
            get() = motivo.codigo
    }
}
