package com.copernicotv.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.copernicotv.core.tema.TemaUi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Singleton

/** Clave única de la preferencia de tema (S43). */
const val CLAVE_TEMA = "tema_ui"

private val Context.dataStoreTema: DataStore<Preferences> by preferencesDataStore(name = "tema_ui")

/**
 * Preferencia de tema claro/oscuro (S43, E7-T4). No se agrega ninguna columna
 * ni tabla a Room; el valor persistido es el código de [TemaUi].
 */
interface PreferenciasTema {
    fun tema(): Flow<TemaUi>

    /** Invierte el tema persistido y devuelve el nuevo. */
    suspend fun alternar(): TemaUi
}

@Module
@InstallIn(SingletonComponent::class)
object ModuloPreferenciasTema {
    @Provides
    @Singleton
    fun preferencias(
        @ApplicationContext contexto: Context,
    ): PreferenciasTema = PreferenciasTemaDataStore(contexto.dataStoreTema)
}

/** Implementación DataStore de [PreferenciasTema]. */
class PreferenciasTemaDataStore(
    private val dataStore: DataStore<Preferences>,
) : PreferenciasTema {
    override fun tema(): Flow<TemaUi> =
        dataStore.data
            .map { prefs -> TemaUi.desde(prefs[stringPreferencesKey(CLAVE_TEMA)]) }
            .distinctUntilChanged()

    override suspend fun alternar(): TemaUi {
        val nuevo = TemaUi.desde(dataStore.data.first()[stringPreferencesKey(CLAVE_TEMA)]).alternar()
        dataStore.edit { prefs -> prefs[stringPreferencesKey(CLAVE_TEMA)] = nuevo.codigo }
        return nuevo
    }
}
