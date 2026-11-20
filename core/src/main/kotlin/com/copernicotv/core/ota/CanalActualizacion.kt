package com.copernicotv.core.ota

/**
 * Canal de distribución de la aplicación.
 *
 * `STABLE` acepta solo publicaciones estables; `BETA` acepta estables y beta,
 * porque un usuario de beta debe poder recibir una estable más nueva. El canal
 * nunca permite bajar de versión: la comparación es por `versionCode`.
 */
enum class CanalActualizacion(val codigo: String) {
    ESTABLE("stable"),
    BETA("beta"),
    ;

    /** True si una publicación de [publicadoEn] puede instalarse desde este canal. */
    fun acepta(publicadoEn: CanalActualizacion): Boolean = this == BETA || publicadoEn == ESTABLE

    companion object {
        /** Devuelve `null` para un código desconocido: el manifiesto se rechaza. */
        fun desde(codigo: String?): CanalActualizacion? = entries.firstOrNull { it.codigo == codigo }

        /** Canal por defecto de una instalación nueva. */
        val POR_DEFECTO: CanalActualizacion = ESTABLE
    }
}
