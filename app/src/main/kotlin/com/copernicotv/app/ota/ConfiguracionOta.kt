package com.copernicotv.app.ota

import com.copernicotv.core.ota.CanalActualizacion

/**
 * Configuración del canal oficial de actualizaciones.
 *
 * [clavePublicaManifiesto] y [huellaFirmaApk] llegan por `BuildConfig` desde
 * variables de entorno del workflow de release. En una compilación sin esas
 * variables (por ejemplo, debug local) quedan vacías y el comprobador falla
 * cerrado: nunca se acepta una actualización sin autenticidad demostrable.
 */
data class ConfiguracionOta(
    val repositorio: String,
    val paquete: String,
    val clavePublicaManifiesto: String,
    val huellaFirmaApk: String,
    val canalPorDefecto: CanalActualizacion = CanalActualizacion.POR_DEFECTO,
) {
    /** Endpoint de la API de releases, más nuevo primero. */
    val urlReleases: String = "https://api.github.com/repos/$repositorio/releases?per_page=20"

    /** True si la compilación trae la identidad necesaria para verificar. */
    val configurada: Boolean
        get() = repositorio.isNotBlank() && paquete.isNotBlank() && clavePublicaManifiesto.isNotBlank() && huellaFirmaApk.isNotBlank()
}
