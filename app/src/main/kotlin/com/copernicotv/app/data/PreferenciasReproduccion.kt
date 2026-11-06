package com.copernicotv.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.copernicotv.app.player.ModoAspecto
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import javax.inject.Singleton

/**
 * Preferencias globales de reproducción persistidas por E7-T5 (S44b, S17b).
 *
 * Usan el mismo almacén que las preferencias de pista de E6-T3 (L72): son
 * globales, no pertenecen a una Lista, Fuente o Perfil, y nunca inventan
 * valores que los contratos no aprobados.
 *
 * Los valores predeterminados preservan el comportamiento previo a E7-T5:
 * `Ajustar` y controles secundarios visibles. No existe un modo «techo»: S44a
 * queda pendiente de definición funcional del propietario del producto.
 */
data class PreferenciasReproduccion(
    val modoAspecto: ModoAspecto = ModoAspecto.AJUSTAR,
    val controlesSecundariosVisibles: Boolean = true,
)

interface RepositorioPreferenciasReproduccion {
    suspend fun leer(): PreferenciasReproduccion

    suspend fun guardar(preferencias: PreferenciasReproduccion)
}

/** Implementación DataStore de [RepositorioPreferenciasReproduccion] (S44b, S17b). */
class RepositorioPreferenciasReproduccionDataStore(
    private val dataStore: DataStore<Preferences>,
) : RepositorioPreferenciasReproduccion {
    override suspend fun leer(): PreferenciasReproduccion {
        val prefs = dataStore.data.first()
        val modo =
            prefs[CLAVE_MODO_ASPECTO]?.let { nombre ->
                // Un valor ajeno al enum (instalación anterior, edición a mano,
                // S44c) no detiene la reproducción: cae en `Ajustar`.
                runCatching { ModoAspecto.valueOf(nombre) }.getOrDefault(ModoAspecto.AJUSTAR)
            } ?: ModoAspecto.AJUSTAR
        return PreferenciasReproduccion(
            modoAspecto = modo,
            controlesSecundariosVisibles = prefs[CLAVE_SECUNDARIOS] ?: true,
        )
    }

    override suspend fun guardar(preferencias: PreferenciasReproduccion) {
        dataStore.edit { prefs ->
            prefs[CLAVE_MODO_ASPECTO] = preferencias.modoAspecto.name
            prefs[CLAVE_SECUNDARIOS] = preferencias.controlesSecundariosVisibles
        }
    }

    private companion object {
        val CLAVE_MODO_ASPECTO = stringPreferencesKey("modo_aspecto")

        val CLAVE_SECUNDARIOS = booleanPreferencesKey("controles_secundarios")
    }
}

@Module
@InstallIn(SingletonComponent::class)
object ModuloPreferenciasReproduccion {
    @Provides
    @Singleton
    fun repositorio(
        @ApplicationContext contexto: Context,
    ): RepositorioPreferenciasReproduccion =
        RepositorioPreferenciasReproduccionDataStore(contexto.dataStorePistas)
}
