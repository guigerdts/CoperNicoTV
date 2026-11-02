package com.copernicotv.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.copernicotv.core.epg.LimitesRetencion
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

/**
 * Valores globales de retención de EPG (E5-T3, ADR-0002:48–50).
 *
 * La retención efectiva de una `FuenteGuia` se resuelve con
 * `RetencionEpg.resolver`: si `usarRetencionGlobal` es `true`, se usan estos
 * valores; si es `false`, solo se sustituyen los campos explícitos y el resto
 * hereda el global. Cambiar el global no reescribe las excepciones persistidas.
 *
 * Los valores viven en DataStore, no en Room. El esquema v1 de `FuenteGuia`
 * solo guarda las excepciones por Fuente.
 */
interface PreferenciasRetencion {
    /** Límites globales vigentes; emite el default cuando no hay valores guardados. */
    fun limites(): Flow<LimitesRetencion>

    /** Lectura puntual para aplicar retención en una importación. */
    suspend fun actuales(): LimitesRetencion

    /** Persiste los límites globales; el objeto ya está validado por su constructor. */
    suspend fun guardar(limites: LimitesRetencion)

    /** Vuelve a los valores iniciales de especificación. */
    suspend fun restablecer()
}

private val Context.dataStoreRetencion: DataStore<Preferences> by preferencesDataStore(name = "retencion_epg")

@Module
@InstallIn(SingletonComponent::class)
object ModuloPreferenciasRetencion {
    @Provides
    @Singleton
    fun preferencias(
        @ApplicationContext contexto: Context,
    ): PreferenciasRetencion = PreferenciasRetencionDataStore(contexto.dataStoreRetencion)
}

/** Implementación DataStore de [PreferenciasRetencion]. */
class PreferenciasRetencionDataStore(
    private val dataStore: DataStore<Preferences>,
) : PreferenciasRetencion {
    override fun limites(): Flow<LimitesRetencion> =
        dataStore.data
            .map(::aLimites)
            .distinctUntilChanged()

    override suspend fun actuales(): LimitesRetencion = aLimites(dataStore.data.first())

    override suspend fun guardar(limites: LimitesRetencion) {
        dataStore.edit { prefs ->
            prefs[CLAVE_VENTANA_PASADO] = limites.ventanaPasadoDias
            prefs[CLAVE_VENTANA_FUTURO] = limites.ventanaFuturoDias
            prefs[CLAVE_MAX_PROGRAMAS] = limites.maxProgramasPorCanal
            prefs[CLAVE_PRESUPUESTO] = limites.presupuestoBytes
        }
    }

    override suspend fun restablecer() {
        dataStore.edit { prefs ->
            prefs.remove(CLAVE_VENTANA_PASADO)
            prefs.remove(CLAVE_VENTANA_FUTURO)
            prefs.remove(CLAVE_MAX_PROGRAMAS)
            prefs.remove(CLAVE_PRESUPUESTO)
        }
    }

    /**
     * Un valor persistido inválido no rompe la importación: se ignora ese campo
     * y se usa el default global. DataStore es editable por versiones futuras y
     * no debe bloquear la guía por una fila corrupta.
     */
    private fun aLimites(prefs: Preferences): LimitesRetencion {
        val base = LimitesRetencion.GLOBAL
        val maximo =
            (prefs[CLAVE_MAX_PROGRAMAS]?.toLong() ?: base.maxProgramasPorCanal.toLong())
                .coerceIn(1L, Int.MAX_VALUE.toLong())
                .toInt()
        return runCatching {
            LimitesRetencion(
                ventanaPasadoDias = prefs[CLAVE_VENTANA_PASADO] ?: base.ventanaPasadoDias,
                ventanaFuturoDias = prefs[CLAVE_VENTANA_FUTURO] ?: base.ventanaFuturoDias,
                maxProgramasPorCanal = maximo,
                presupuestoBytes = prefs[CLAVE_PRESUPUESTO] ?: base.presupuestoBytes,
            )
        }.getOrElse { base }
    }

    private companion object {
        val CLAVE_VENTANA_PASADO = longPreferencesKey("retencion_ventana_pasado_dias")
        val CLAVE_VENTANA_FUTURO = longPreferencesKey("retencion_ventana_futuro_dias")
        val CLAVE_MAX_PROGRAMAS = intPreferencesKey("retencion_max_programas_por_canal")
        val CLAVE_PRESUPUESTO = longPreferencesKey("retencion_presupuesto_bytes")
    }
}
