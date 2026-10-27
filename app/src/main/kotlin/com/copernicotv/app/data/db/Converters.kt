package com.copernicotv.app.data.db

import androidx.room.TypeConverter
import com.copernicotv.core.identity.EstadoCanal
import java.time.Instant

/**
 * Conversores de Room v1.
 *
 * `Instant` se persiste como milisegundos UTC (suficiente para el esquema v1 y
 * sin perdida de orden). `EstadoCanal` se persiste como su nombre (`A`, `B`,
 * `C`), que es el valor normativo de la columna.
 */
class Converters {
    @TypeConverter
    fun instanteAMillis(valor: Instant?): Long? = valor?.toEpochMilli()

    @TypeConverter
    fun millisAInstante(valor: Long?): Instant? = valor?.let { Instant.ofEpochMilli(it) }

    @TypeConverter
    fun estadoCanalATexto(valor: EstadoCanal?): String? = valor?.name

    @TypeConverter
    fun textoAEstadoCanal(valor: String?): EstadoCanal? = valor?.let { EstadoCanal.valueOf(it) }
}
