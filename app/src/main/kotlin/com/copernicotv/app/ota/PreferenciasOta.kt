package com.copernicotv.app.ota

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.copernicotv.core.ota.CanalActualizacion
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.Base64
import javax.inject.Singleton

/** Manifiesto persistido con sus bytes exactos y su firma. */
data class ManifiestoGuardado(
    val json: ByteArray,
    val firma: ByteArray,
    val comprobadoEnMs: Long,
)

/**
 * Estado persistente del OTA.
 *
 * Usa un DataStore propio (`ota_actualizaciones`), separado del de las Listas
 * M3U: los dos sistemas comparten el proceso pero nunca el mismo archivo ni
 * las mismas claves.
 *
 * El manifiesto se guarda con sus bytes y su firma para poder reverificarlo al
 * leerlo. Guardar solo campos ya parseados permitiría que un archivo alterado
 * se mostrara como si estuviera firmado.
 */
interface AlmacenManifiestoOta {
    fun canal(): Flow<CanalActualizacion>

    suspend fun canalActual(): CanalActualizacion

    suspend fun fijarCanal(canal: CanalActualizacion)

    fun ultimaComprobacionMs(): Flow<Long?>

    suspend fun marcarComprobacion(instanteMs: Long)

    suspend fun guardarComprobacion(
        verificado: ManifiestoVerificado,
        instanteMs: Long,
    )

    suspend fun cargarManifiesto(): ManifiestoGuardado?

    suspend fun olvidarManifiesto()

    fun versionOmitida(): Flow<Long?>

    suspend fun omitirVersion(versionCode: Long)

    suspend fun olvidarVersionOmitida()
}

private val Context.dataStoreOta: DataStore<Preferences> by preferencesDataStore(name = "ota_actualizaciones")

@Module
@InstallIn(SingletonComponent::class)
object ModuloAlmacenOta {
    @Provides
    @Singleton
    fun almacen(
        @ApplicationContext contexto: Context,
    ): AlmacenManifiestoOta = AlmacenManifiestoOtaDataStore(contexto.dataStoreOta)
}

/** Implementación DataStore de [AlmacenManifiestoOta]. */
class AlmacenManifiestoOtaDataStore(
    private val dataStore: DataStore<Preferences>,
) : AlmacenManifiestoOta {
    override fun canal(): Flow<CanalActualizacion> =
        dataStore.data
            .map { prefs -> CanalActualizacion.desde(prefs[CLAVE_CANAL]) ?: CanalActualizacion.POR_DEFECTO }
            .distinctUntilChanged()

    override suspend fun canalActual(): CanalActualizacion = canal().first()

    override suspend fun fijarCanal(canal: CanalActualizacion) {
        dataStore.edit { prefs -> prefs[CLAVE_CANAL] = canal.codigo }
    }

    override fun ultimaComprobacionMs(): Flow<Long?> =
        dataStore.data.map { prefs -> prefs[CLAVE_ULTIMA_COMPROBACION] }.distinctUntilChanged()

    override suspend fun marcarComprobacion(instanteMs: Long) {
        dataStore.edit { prefs -> prefs[CLAVE_ULTIMA_COMPROBACION] = instanteMs }
    }

    override suspend fun guardarComprobacion(
        verificado: ManifiestoVerificado,
        instanteMs: Long,
    ) {
        val json = Base64.getEncoder().encodeToString(verificado.json)
        val firma = Base64.getEncoder().encodeToString(verificado.firma)
        dataStore.edit { prefs ->
            prefs[CLAVE_MANIFIESTO] = json
            prefs[CLAVE_FIRMA] = firma
            prefs[CLAVE_ULTIMA_COMPROBACION] = instanteMs
        }
    }

    override suspend fun cargarManifiesto(): ManifiestoGuardado? {
        val prefs = dataStore.data.first()
        val json = prefs[CLAVE_MANIFIESTO] ?: return null
        val firma = prefs[CLAVE_FIRMA] ?: return null
        val comprobado = prefs[CLAVE_ULTIMA_COMPROBACION] ?: return null
        return try {
            ManifiestoGuardado(
                json = Base64.getDecoder().decode(json),
                firma = Base64.getDecoder().decode(firma),
                comprobadoEnMs = comprobado,
            )
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    override suspend fun olvidarManifiesto() {
        dataStore.edit { prefs ->
            prefs.remove(CLAVE_MANIFIESTO)
            prefs.remove(CLAVE_FIRMA)
        }
    }

    override fun versionOmitida(): Flow<Long?> =
        dataStore.data.map { prefs -> prefs[CLAVE_VERSION_OMITIDA] }.distinctUntilChanged()

    override suspend fun omitirVersion(versionCode: Long) {
        dataStore.edit { prefs -> prefs[CLAVE_VERSION_OMITIDA] = versionCode }
    }

    override suspend fun olvidarVersionOmitida() {
        dataStore.edit { prefs -> prefs.remove(CLAVE_VERSION_OMITIDA) }
    }

    private companion object {
        val CLAVE_CANAL = stringPreferencesKey("canal")
        val CLAVE_ULTIMA_COMPROBACION = longPreferencesKey("ultima_comprobacion_ms")
        val CLAVE_MANIFIESTO = stringPreferencesKey("manifiesto_base64")
        val CLAVE_FIRMA = stringPreferencesKey("firma_base64")
        val CLAVE_VERSION_OMITIDA = longPreferencesKey("version_omitida")
    }
}
