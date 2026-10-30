package com.copernicotv.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.copernicotv.core.orden.DireccionOrden
import com.copernicotv.core.orden.ModoOrden
import com.copernicotv.core.orden.PreferenciaOrden
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Singleton

/**
 * Preferencia de ordenación por Lista (DC-08).
 *
 * La clave es `orden_lista:<id>` y el valor es `modo:direccion`. No se agrega
 * ninguna columna ni tabla a Room.
 */
interface PreferenciasOrden {
    fun preferencia(listaId: Long): Flow<PreferenciaOrden>

    suspend fun guardar(
        listaId: Long,
        preferencia: PreferenciaOrden,
    )

    suspend fun limpiar(listaId: Long)

    suspend fun barrerHuerfanas(idsValidos: Set<Long>)
}

private val Context.dataStoreOrden: DataStore<Preferences> by preferencesDataStore(name = "orden_catalogo")

@Module
@InstallIn(SingletonComponent::class)
object ModuloPreferenciasOrden {
    @Provides
    @Singleton
    fun preferencias(
        @ApplicationContext contexto: Context,
    ): PreferenciasOrden = PreferenciasOrdenDataStore(contexto.dataStoreOrden)
}

/** Implementación DataStore de [PreferenciasOrden]. */
class PreferenciasOrdenDataStore(
    private val dataStore: DataStore<Preferences>,
) : PreferenciasOrden {
    override fun preferencia(listaId: Long): Flow<PreferenciaOrden> =
        dataStore.data
            .map { prefs -> parsear(prefs[clave(listaId)]) ?: PreferenciaOrden() }
            .distinctUntilChanged()

    override suspend fun guardar(
        listaId: Long,
        preferencia: PreferenciaOrden,
    ) {
        dataStore.edit { prefs ->
            prefs[clave(listaId)] = "${preferencia.modo.codigo}:${preferencia.direccion.codigo}"
        }
    }

    override suspend fun limpiar(listaId: Long) {
        dataStore.edit { prefs -> prefs.remove(clave(listaId)) }
    }

    override suspend fun barrerHuerfanas(idsValidos: Set<Long>) {
        dataStore.edit { prefs ->
            val nombres = prefs.asMap().keys.map { it.name }.filter { it.startsWith(PREFIJO) }
            nombres.forEach { nombre ->
                val id = nombre.removePrefix(PREFIJO).toLongOrNull()
                if (id == null || id !in idsValidos) {
                    prefs.remove(stringPreferencesKey(nombre))
                }
            }
        }
    }

    private fun clave(listaId: Long) = stringPreferencesKey("$PREFIJO$listaId")

    private fun parsear(valor: String?): PreferenciaOrden? {
        if (valor == null) return null
        val partes = valor.split(':')
        if (partes.size != 2) return null
        val modo = ModoOrden.entries.firstOrNull { it.codigo == partes[0] } ?: return null
        val direccion = DireccionOrden.entries.firstOrNull { it.codigo == partes[1] } ?: return null
        return PreferenciaOrden(modo, direccion)
    }

    private companion object {
        const val PREFIJO = "orden_lista:"
    }
}
