package com.copernicotv.core.ota

import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Verificación de la firma separada del manifiesto (`update.json.sig`).
 *
 * El SHA-256 del APK no prueba autenticidad: manifiesto y APK viajan por el
 * mismo canal y cualquiera con acceso de escritura al release puede sustituir
 * ambos. La firma ECDSA sobre los bytes exactos del manifiesto, verificada con
 * una clave pública embebida en la app, sí lo hace.
 */
object FirmaManifiesto {
    private const val ALGORITMO = "SHA256withECDSA"
    private const val ALGORITMO_CLAVE = "EC"

    /**
     * True solo si [firma] es una firma ECDSA P-256/SHA-256 válida de
     * [manifiesto] con la clave pública X.509 codificada en [clavePublicaBase64].
     *
     * Un fallo de formato o una clave inválida se traducen a `false`: nunca se
     * propagan excepciones de criptografía a la capa de UI.
     */
    fun verificar(
        manifiesto: ByteArray,
        firma: ByteArray,
        clavePublicaBase64: String,
    ): Boolean {
        if (manifiesto.isEmpty() || firma.isEmpty() || clavePublicaBase64.isBlank()) return false
        return try {
            val clave = clavePublica(clavePublicaBase64) ?: return false
            val verificador = Signature.getInstance(ALGORITMO)
            verificador.initVerify(clave)
            verificador.update(manifiesto)
            verificador.verify(firma)
        } catch (_: GeneralSecurityException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    private fun clavePublica(base64: String) =
        try {
            KeyFactory
                .getInstance(ALGORITMO_CLAVE)
                .generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(base64)))
        } catch (_: GeneralSecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
}
