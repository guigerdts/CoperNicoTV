package com.copernicotv.app.data.db

import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.copernicotv.app.data.db.entities.EstadoIndiceHmacEntity
import com.copernicotv.core.hmac.FaseIndiceHmac
import com.copernicotv.core.hmac.GeneracionHmac
import java.time.Instant

/**
 * Semilla única de la fila singleton `EstadoIndiceHmac` (`id = 1`).
 *
 * La consumen dos caminos que antes duplicaban los valores:
 *
 * - el callback de creación de Room, que siembra la fila al abrir una base nueva;
 * - [com.copernicotv.app.data.IndiceHmacRepositorio], que crea la misma fila si
 *   falta antes de una transición.
 *
 * [fila] es la fuente única del contenido y del `id`; [insertarSiFalta] conserva
 * el `INSERT OR IGNORE` que impide pisar un estado ya persistido.
 */
object SembrarEstadoIndiceHmac {
    /** Fila singleton del estado del índice HMAC. */
    const val ID_SINGLETON: Long = 1L

    /** Generación HMAC inicial del contrato: `hmac:v1`. */
    const val GENERACION_INICIAL: Int = 1

    /** Fila inicial: índice operativo con la generación inicial y sin pendientes. */
    fun fila(ahora: Instant): EstadoIndiceHmacEntity =
        EstadoIndiceHmacEntity(
            id = ID_SINGLETON,
            estado = FaseIndiceHmac.OPERATIVO.codigo,
            versionActiva = GeneracionHmac.formatear(GENERACION_INICIAL),
            versionObjetivo = null,
            iniciadoEn = ahora,
            actualizadoEn = ahora,
            filasTotales = 0,
            filasReconstruidas = 0,
            filasPendientes = 0,
            ultimoErrorTipo = null,
        )

    /** Callback de Room: siembra la fila al crear la base, sin pisar una existente. */
    fun callback(): RoomDatabase.Callback =
        object : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                insertarSiFalta(db, Instant.now())
            }
        }

    /** Inserta la fila inicial solo si no existe (`INSERT OR IGNORE`). */
    fun insertarSiFalta(
        db: SupportSQLiteDatabase,
        ahora: Instant,
    ) {
        val fila = fila(ahora)
        db.execSQL(
            """
            INSERT OR IGNORE INTO EstadoIndiceHmac
                (id, estado, versionActiva, versionObjetivo, iniciadoEn, actualizadoEn,
                 filasTotales, filasReconstruidas, filasPendientes, ultimoErrorTipo)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf(
                fila.id,
                fila.estado,
                fila.versionActiva,
                fila.versionObjetivo,
                fila.iniciadoEn.toEpochMilli(),
                fila.actualizadoEn.toEpochMilli(),
                fila.filasTotales,
                fila.filasReconstruidas,
                fila.filasPendientes,
                fila.ultimoErrorTipo,
            ),
        )
    }
}
