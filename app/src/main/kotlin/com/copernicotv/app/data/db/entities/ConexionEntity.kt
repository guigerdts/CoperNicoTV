package com.copernicotv.app.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant

/**
 * Conexión IPTV administrable por el usuario (esquema v5).
 *
 * Una conexión es el perfil que el usuario crea, edita y activa; de ella se
 * derivan la lista M3U que consume la importación existente y la guía EPG.
 * No duplica el catálogo: solo guarda cómo obtenerlo.
 *
 * Secretos:
 * - [passwordCifrada] es un sobre AES-GCM sellado con la clave de Android
 *   Keystore (`CifradoAes`), con AAD `Conexion.passwordCifrada`.
 * - [urlListaCifrada] y [urlGuiaCifrada] se cifran igual, porque la URL de
 *   `get.php` de Xtream incluye usuario y contraseña.
 * - [urlListaHmac] y [urlGuiaHmac] son los índices HMAC para comparar sin
 *   descifrar; nunca contienen la URL en claro.
 *
 * `toString` no existe aquí: la entidad se registra con
 * `RegistroConexion.redactar`, que omite los BLOB.
 */
@Entity(
    tableName = "Conexion",
    indices = [Index(value = ["nombre"], unique = true)],
)
data class ConexionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val nombre: String,
    /** `xtream` o `m3u`; ver `TipoConexion`. */
    val tipo: String,
    val activa: Boolean,
    /** `http` o `https`; para M3U se deriva de la URL de la lista. */
    val protocolo: String,
    val servidor: String? = null,
    val puerto: Int? = null,
    val usuario: String? = null,
    val passwordCifrada: ByteArray? = null,
    val formatoSalida: String? = null,
    val urlListaCifrada: ByteArray? = null,
    val urlListaHmac: ByteArray? = null,
    val urlGuiaCifrada: ByteArray? = null,
    val urlGuiaHmac: ByteArray? = null,
    val actualizacionAutomatica: Boolean,
    val intervaloHoras: Long,
    /** Código de `EstadoConexion`; nunca texto libre traducido. */
    val estado: String,
    val ultimoMensaje: String? = null,
    val ultimoCodigo: String? = null,
    val listaActualizadaEn: Instant? = null,
    val guiaActualizadaEn: Instant? = null,
    val creadoEn: Instant,
    val actualizadoEn: Instant,
)
