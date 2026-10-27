package com.copernicotv.core.crypto

import com.copernicotv.core.identity.CampoUrl
import com.copernicotv.core.identity.ClaseUrl
import com.copernicotv.core.identity.EntradaHmac
import com.copernicotv.core.identity.SobreHmac
import com.copernicotv.core.identity.hmacSha256
import javax.crypto.SecretKey

/**
 * Cálculo del sobre HMAC de una URL canónica (DC-04, L31).
 *
 * La clave es una [SecretKey] no exportable de Keystore. La generación (`vN`)
 * identifica la clave y `cvM` la versión de canonicalización; ambos viajan en
 * el sobre y son independientes.
 */
object HmacUrl {
    /** Versión de canonicalización vigente: `cv1`. */
    const val VERSION_CANONICA: Int = 1

    fun calcularSobre(
        clave: SecretKey,
        generacion: Int,
        campo: CampoUrl,
        clase: ClaseUrl,
        urlCanonica: String,
        versionCanonica: Int = VERSION_CANONICA,
    ): String {
        val entrada =
            EntradaHmac.serializar(
                versionCanonica = versionCanonica,
                campo = campo,
                clase = clase,
                urlCanonica = urlCanonica,
            )
        return SobreHmac.construir(
            generacion = generacion,
            versionCanonica = versionCanonica,
            mac = hmacSha256(clave, entrada),
        )
    }
}
