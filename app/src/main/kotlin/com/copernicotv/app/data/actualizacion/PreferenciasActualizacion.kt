package com.copernicotv.app.data.actualizacion

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.copernicotv.app.data.ActualizacionRepositorio
import com.copernicotv.core.actualizacion.IntervaloActualizacion
import com.copernicotv.core.actualizacion.ResultadoIntervalo
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Instant
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Preferencias de la Actualización automática (DC-07 punto 2).
 *
 * El global vive en DataStore y se persiste en minutos. No se agrega ninguna
 * columna ni tabla a Room: el override por Fuente ya existe en `Fuente`.
 */
interface PreferenciasActualizacion {
    /** Intervalo global vigente, en minutos. */
    fun intervaloGlobal(): Flow<Long>

    /** Guarda el global solo si es válido; devuelve el resultado de la validación. */
    suspend fun guardarIntervaloGlobal(minutos: Long): ResultadoIntervalo

    /** Intervalo con el que se programó por última vez cada Fuente. */
    suspend fun programadas(): Map<Long, Long>

    suspend fun registrarProgramada(
        fuenteId: Long,
        minutos: Long,
    )

    suspend fun olvidarProgramada(fuenteId: Long)
}

/**
 * Reclamo de una Fuente para impedir Actualizaciones en paralelo (DC-07 punto 6).
 *
 * El estado `en_curso` persiste con marca de tiempo. El reclamo es atómico
 * dentro del proceso porque DataStore serializa las escrituras; el `Mutex` en
 * memoria no sobreviviría a la muerte del proceso, y por eso el estado se
 * persiste y se limpia como huérfano al iniciar la app.
 */
interface ReclamoFuente {
    /** Devuelve `true` solo si la Fuente no estaba reclamada. */
    suspend fun reclamar(fuenteId: Long): Boolean

    suspend fun liberar(fuenteId: Long)

    /** Reclamos vigentes con su marca de tiempo. */
    suspend fun enCurso(): Map<Long, Instant>

    /** Libera un reclamo que WorkManager ya no respalda. */
    suspend fun liberarHuerfano(fuenteId: Long)
}

/** Calificador del DataStore propio de la Actualización. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DataStoreActualizacion

private val Context.dataStoreActualizacion: DataStore<Preferences> by preferencesDataStore(name = "actualizacion")

@Module
@InstallIn(SingletonComponent::class)
object ModuloActualizacion {
    @Provides
    @Singleton
    @DataStoreActualizacion
    fun dataStore(
        @ApplicationContext contexto: Context,
    ): DataStore<Preferences> = contexto.dataStoreActualizacion

    @Provides
    @Singleton
    fun preferencias(
        @DataStoreActualizacion dataStore: DataStore<Preferences>,
    ): PreferenciasActualizacion = PreferenciasActualizacionDataStore(dataStore)

    @Provides
    @Singleton
    fun reclamo(
        @DataStoreActualizacion dataStore: DataStore<Preferences>,
    ): ReclamoFuente = ReclamoFuenteDataStore(dataStore)

    /**
     * Actualización real de una Lista.
     *
     * Vive en este módulo, que no se reemplaza en las pruebas instrumentadas:
     * el doble de prueba reemplaza solo el planificador y sigue ejecutando el
     * pipeline real de E4-T1/T2/T6.
     */
    @Provides
    @Singleton
    fun actualizadorLista(
        repositorio: ActualizacionRepositorio,
    ): ActualizadorLista =
        ActualizadorLista { listaId, causa -> repositorio.actualizarLista(listaId, causa) }
}

/** Implementación DataStore de [PreferenciasActualizacion]. */
class PreferenciasActualizacionDataStore(
    private val dataStore: DataStore<Preferences>,
) : PreferenciasActualizacion {
    override fun intervaloGlobal(): Flow<Long> =
        dataStore.data
            .map { prefs -> prefs[CLAVE_INTERVALO_GLOBAL] ?: IntervaloActualizacion.POR_DEFECTO }
            .distinctUntilChanged()

    override suspend fun guardarIntervaloGlobal(minutos: Long): ResultadoIntervalo {
        val resultado = IntervaloActualizacion.de(minutos)
        if (resultado is ResultadoIntervalo.Aceptado) {
            dataStore.edit { prefs -> prefs[CLAVE_INTERVALO_GLOBAL] = minutos }
        }
        return resultado
    }

    override suspend fun programadas(): Map<Long, Long> =
        dataStore.data.first().asMap().mapNotNull { (clave, valor) ->
            val id = idDe(clave.name, PREFIJO_PROGRAMADA) ?: return@mapNotNull null
            val minutos = valor as? Long ?: return@mapNotNull null
            id to minutos
        }.toMap()

    override suspend fun registrarProgramada(
        fuenteId: Long,
        minutos: Long,
    ) {
        dataStore.edit { prefs -> prefs[claveProgramada(fuenteId)] = minutos }
    }

    override suspend fun olvidarProgramada(fuenteId: Long) {
        dataStore.edit { prefs -> prefs.remove(claveProgramada(fuenteId)) }
    }

    private fun claveProgramada(fuenteId: Long) = longPreferencesKey("$PREFIJO_PROGRAMADA$fuenteId")

    private companion object {
        val CLAVE_INTERVALO_GLOBAL = longPreferencesKey("intervalo_global")
        const val PREFIJO_PROGRAMADA = "programada:"

        fun idDe(
            nombre: String,
            prefijo: String,
        ): Long? = if (nombre.startsWith(prefijo)) nombre.removePrefix(prefijo).toLongOrNull() else null
    }
}

/** Implementación DataStore de [ReclamoFuente]. */
class ReclamoFuenteDataStore(
    private val dataStore: DataStore<Preferences>,
) : ReclamoFuente {
    override suspend fun reclamar(fuenteId: Long): Boolean {
        var reclamada = false
        dataStore.edit { prefs ->
            val clave = claveEnCurso(fuenteId)
            val previa = prefs[clave]
            if (previa == null || Instant.now().toEpochMilli() - previa > VIGENCIA_RECLAMO_MILLIS) {
                prefs[clave] = Instant.now().toEpochMilli()
                reclamada = true
            }
        }
        return reclamada
    }

    override suspend fun liberar(fuenteId: Long) {
        dataStore.edit { prefs -> prefs.remove(claveEnCurso(fuenteId)) }
    }

    override suspend fun enCurso(): Map<Long, Instant> =
        dataStore.data.first().asMap().mapNotNull { (clave, valor) ->
            val nombre = clave.name
            if (!nombre.startsWith(PREFIJO_EN_CURSO)) return@mapNotNull null
            val id = nombre.removePrefix(PREFIJO_EN_CURSO).toLongOrNull() ?: return@mapNotNull null
            val millis = valor as? Long ?: return@mapNotNull null
            id to Instant.ofEpochMilli(millis)
        }.toMap()

    override suspend fun liberarHuerfano(fuenteId: Long) {
        dataStore.edit { prefs -> prefs.remove(claveEnCurso(fuenteId)) }
    }

    private fun claveEnCurso(fuenteId: Long) = longPreferencesKey("$PREFIJO_EN_CURSO$fuenteId")

    private companion object {
        const val PREFIJO_EN_CURSO = "en_curso:"

        /**
         * Vigencia de un reclamo.
         *
         * La limpieza de DC-07 punto 6 corre al iniciar la app, pero un proceso
         * que muere a mitad de una ejecución deja el reclamo y WorkManager
         * reprograma el trabajo en un proceso nuevo, sin pasar por esa
         * limpieza. La marca de tiempo permite además descartar un reclamo que
         * ninguna ejecución viva puede estar sosteniendo: una Actualización de
         * una Fuente nunca dura 30 minutos.
         */
        const val VIGENCIA_RECLAMO_MILLIS = 30L * 60L * 1000L
    }
}
