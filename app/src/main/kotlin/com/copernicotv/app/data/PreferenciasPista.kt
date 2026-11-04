package com.copernicotv.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.copernicotv.app.player.PreferenciaPista
import com.copernicotv.app.player.PreferenciasPista
import com.copernicotv.app.player.codificarPreferencia
import com.copernicotv.app.player.decodificarPreferencia
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import javax.inject.Singleton

/**
 * Preferencias globales de audio y subtítulos (D3, E6-T3).
 *
 * Son globales: no pertenecen a una Lista ni a un Perfil. La preferencia
 * guardada solo ordena la selección; nunca crea pistas ni capacidades. E7-T5
 * consume este almacén para el resto de preferencias de reproducción.
 */
interface RepositorioPreferenciasPista {
    suspend fun leer(): PreferenciasPista

    suspend fun guardar(preferencias: PreferenciasPista)
}

private val Context.dataStorePistas: DataStore<Preferences> by preferencesDataStore(name = "preferencias_pistas")

@Module
@InstallIn(SingletonComponent::class)
object ModuloPreferenciasPista {
    @Provides
    @Singleton
    fun repositorio(
        @ApplicationContext contexto: Context,
    ): RepositorioPreferenciasPista = RepositorioPreferenciasPistaDataStore(contexto.dataStorePistas)
}

/** Implementación DataStore de [RepositorioPreferenciasPista]. */
class RepositorioPreferenciasPistaDataStore(
    private val dataStore: DataStore<Preferences>,
) : RepositorioPreferenciasPista {
    override suspend fun leer(): PreferenciasPista {
        val prefs = dataStore.data.first()
        return PreferenciasPista(
            audio = decodificarPreferencia(prefs[CLAVE_AUDIO]).paraAudio(),
            subtitulos = decodificarPreferencia(prefs[CLAVE_SUBTITULOS]).paraSubtitulos(),
        )
    }

    override suspend fun guardar(preferencias: PreferenciasPista) {
        dataStore.edit { prefs ->
            prefs[CLAVE_AUDIO] = codificarPreferencia(preferencias.audio.paraAudio())
            prefs[CLAVE_SUBTITULOS] = codificarPreferencia(preferencias.subtitulos.paraSubtitulos())
        }
    }

    private fun PreferenciaPista.paraAudio(): PreferenciaPista =
        if (this is PreferenciaPista.Desactivada) PreferenciaPista.Automatica else this

    private fun PreferenciaPista.paraSubtitulos(): PreferenciaPista = this

    private companion object {
        val CLAVE_AUDIO = stringPreferencesKey("audio")
        val CLAVE_SUBTITULOS = stringPreferencesKey("subtitulos")
    }
}
