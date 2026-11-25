package com.copernicotv.app.data.actualizacion

import kotlinx.coroutines.flow.Flow

/**
 * Programación de la actualización de guías EPG (S7).
 *
 * Es una interfaz para que las pruebas instrumentadas la sustituyan sin tocar
 * WorkManager: `HiltTestApplication` no implementa `Configuration.Provider`, así
 * que `WorkManager.getInstance` no puede inicializarse en ese entorno.
 *
 * La implementación real es [ProgramadorGuiasWorkManager].
 */
interface ProgramadorGuias {
    /**
     * Reconciliación de arranque.
     *
     * Si no queda ninguna guía activa con actualización automática, el trabajo
     * periódico se cancela en lugar de despertar al proceso para no hacer nada.
     */
    suspend fun reconciliar()

    /** Encola la actualización manual de una guía. */
    suspend fun solicitar(fuenteGuiaId: Long)

    /** Guías con una actualización manual en curso o pendiente. */
    fun observarEnCurso(): Flow<Set<Long>>
}
