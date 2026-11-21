package com.copernicotv.app.ota

/**
 * Puerto de una pasada del OTA tal como la consume la pantalla de Ajustes.
 *
 * Existe para que el ViewModel dependa de una sola operación y no del grafo
 * completo del coordinador: la UI pide "buscar actualizaciones", recibe el
 * resultado y el progreso, y no conoce descarga, verificación, notificaciones
 * ni almacén. El grafo Hilt enlaza este puerto con [CoordinadorOta].
 */
interface PasadaOta {
    /**
     * Ejecuta la pasada. No lanza: un problema se devuelve como
     * [ResultadoOta.Fallo]. [alProgreso] recibe el porcentaje descargado.
     */
    suspend fun ejecutar(alProgreso: (Int) -> Unit): ResultadoOta
}
