package com.copernicotv.app.data

import com.copernicotv.core.conexion.DatosConexionM3U
import com.copernicotv.core.conexion.ErrorConexion
import com.copernicotv.core.conexion.EstadoConexion
import com.copernicotv.core.conexion.FormatoSalidaXtream
import com.copernicotv.core.conexion.ProtocoloConexion
import com.copernicotv.core.conexion.TipoConexion
import java.time.Instant

/**
 * Códigos estables de las acciones de conexión.
 *
 * Se muestran al usuario a través de `strings.xml`; nunca contienen datos del
 * servidor, del usuario ni de la URL.
 */
object CodigosConexion {
    const val NOMBRE_DUPLICADO: String = "conexion_nombre_duplicado"
    const val NO_EXISTE: String = "conexion_no_existe"
    const val INDICE_HMAC_NO_OPERATIVO: String = "conexion_indice_hmac_no_operativo"
    const val URL_INVALIDA: String = "conexion_url_invalida"
    const val SIN_PASSWORD: String = "conexion_sin_password"
    const val SIN_URL_LISTA: String = "conexion_sin_url_lista"
    const val GUARDAR_FALLO: String = "conexion_guardar_fallo"
    const val ELIMINAR_FALLO: String = "conexion_eliminar_fallo"
}

/** Resultado de crear, editar o eliminar una conexión. */
sealed interface ResultadoConexion {
    data class Creada(val id: Long) : ResultadoConexion

    data object Actualizada : ResultadoConexion

    data object Eliminada : ResultadoConexion

    /** Los datos no pasaron la validación de dominio; se listan todos. */
    data class Invalida(val errores: List<ErrorConexion>) : ResultadoConexion

    data class Error(val codigo: String) : ResultadoConexion
}

/**
 * Formulario del Método A (Xtream Codes).
 *
 * [contrasena] nula significa «conservar la guardada»: la UI nunca muestra la
 * contraseña almacenada, así que editar el resto del perfil no exige volver a
 * escribirla. En el alta es obligatoria.
 */
data class FormularioXtream(
    val nombre: String,
    val servidor: String,
    val puerto: Int?,
    val protocolo: ProtocoloConexion,
    val usuario: String,
    val contrasena: String?,
    val formatoSalida: FormatoSalidaXtream,
    val urlGuia: String? = null,
    val actualizacionAutomatica: Boolean = true,
    val intervaloHoras: Long = DatosConexionM3U.INTERVALO_POR_DEFECTO,
    val activa: Boolean = true,
) {
    override fun toString(): String =
        "FormularioXtream(nombre=$nombre, servidor=$servidor, puerto=$puerto, " +
            "protocolo=$protocolo, usuario=***, contrasena=***, formatoSalida=$formatoSalida, " +
            "urlGuia=${urlGuia?.let { "presente" } ?: "ausente"}, " +
            "actualizacionAutomatica=$actualizacionAutomatica, intervaloHoras=$intervaloHoras, activa=$activa)"
}

/**
 * Formulario del Método B (M3U/M3U8).
 *
 * [urlLista] nula significa «conservar la guardada» en una edición. La URL de la
 * lista puede incluir credenciales, así que no se expone en claro en la UI.
 */
data class FormularioM3U(
    val nombre: String,
    val urlLista: String?,
    val urlGuia: String? = null,
    val actualizacionAutomatica: Boolean = true,
    val intervaloHoras: Long = DatosConexionM3U.INTERVALO_POR_DEFECTO,
    val activa: Boolean = true,
) {
    override fun toString(): String =
        "FormularioM3U(nombre=$nombre, urlLista=${urlLista?.let { "presente" } ?: "ausente"}, " +
            "urlGuia=${urlGuia?.let { "presente" } ?: "ausente"}, " +
            "actualizacionAutomatica=$actualizacionAutomatica, intervaloHoras=$intervaloHoras, activa=$activa)"
}

/** Fila de la pantalla de administración; sin secretos ni BLOB. */
data class ConexionVista(
    val id: Long,
    val nombre: String,
    val tipo: TipoConexion,
    val activa: Boolean,
    val estado: EstadoConexion,
    val ultimoCodigo: String?,
    val listaActualizadaEn: Instant?,
    val guiaActualizadaEn: Instant?,
)

/**
 * Detalle editable de una conexión.
 *
 * [passwordPresente] y [urlListaPresente] informan que el secreto existe sin
 * revelarlo. [urlListaRedactada] muestra solo esquema y host: en M3U las
 * credenciales suelen viajar en la ruta, así que se recorta.
 */
data class DetalleConexion(
    val id: Long,
    val nombre: String,
    val tipo: TipoConexion,
    val activa: Boolean,
    val protocolo: ProtocoloConexion,
    val servidor: String?,
    val puerto: Int?,
    val usuario: String?,
    val formatoSalida: FormatoSalidaXtream?,
    val actualizacionAutomatica: Boolean,
    val intervaloHoras: Long,
    val passwordPresente: Boolean,
    val urlListaPresente: Boolean,
    val urlListaRedactada: String?,
    val urlGuiaPresente: Boolean,
    val urlGuiaRedactada: String?,
)
