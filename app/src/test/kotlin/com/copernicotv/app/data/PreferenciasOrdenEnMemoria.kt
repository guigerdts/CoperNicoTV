package com.copernicotv.app.data

import com.copernicotv.core.orden.PreferenciaOrden
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** Implementación en memoria de [PreferenciasOrden] para pruebas JVM. */
class PreferenciasOrdenEnMemoria : PreferenciasOrden {
    private val valores = MutableStateFlow<Map<Long, PreferenciaOrden>>(emptyMap())

    override fun preferencia(listaId: Long): Flow<PreferenciaOrden> =
        valores
            .map { actuales -> actuales[listaId] ?: PreferenciaOrden() }
            .distinctUntilChanged()

    override suspend fun guardar(
        listaId: Long,
        preferencia: PreferenciaOrden,
    ) {
        valores.update { actuales -> actuales + (listaId to preferencia) }
    }

    override suspend fun limpiar(listaId: Long) {
        valores.update { actuales -> actuales - listaId }
    }

    override suspend fun barrerHuerfanas(idsValidos: Set<Long>) {
        valores.update { actuales -> actuales.filterKeys { it in idsValidos } }
    }

    fun valor(listaId: Long): PreferenciaOrden? = valores.value[listaId]
}
