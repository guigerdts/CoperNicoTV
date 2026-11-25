package com.copernicotv.core.conexion

/** Método de alta de una conexión. */
enum class TipoConexion(val codigo: String) {
    /** Método A: panel Xtream Codes. */
    XTREAM("xtream"),

    /** Método B: lista M3U/M3U8 servida por URL. */
    M3U("m3u"),
    ;

    companion object {
        fun desdeCodigo(codigo: String?): TipoConexion? {
            val buscado = codigo?.trim() ?: return null
            return entries.firstOrNull { it.codigo.equals(buscado, ignoreCase = true) }
        }
    }
}

/**
 * Estado operativo de una conexión.
 *
 * Se persiste como [codigo]; el texto visible se deriva en la UI para no
 * acoplar la base a una traducción.
 */
enum class EstadoConexion(val codigo: String) {
    SIN_PROBAR("sin_probar"),
    CORRECTA("correcta"),
    ERROR("error"),
    ACTUALIZANDO("actualizando"),
    ;

    companion object {
        fun desdeCodigo(codigo: String?): EstadoConexion =
            entries.firstOrNull { it.codigo.equals(codigo?.trim(), ignoreCase = true) } ?: SIN_PROBAR
    }
}

/**
 * Datos de una conexión M3U/M3U8.
 *
 * La guía es independiente y opcional: si la URL no se declara, la aplicación
 * puede proponer la que la propia lista anuncie en su cabecera, pero nunca
 * asume que exista.
 */
data class DatosConexionM3U(
    val nombre: String,
    val urlLista: String,
    val urlGuia: String? = null,
    val actualizacionAutomatica: Boolean = true,
    val intervaloHoras: Long = INTERVALO_POR_DEFECTO,
    val activa: Boolean = true,
) {
    override fun toString(): String =
        "DatosConexionM3U(nombre=$nombre, urlLista=${ConexionXtream.redactar(urlLista)}, " +
            "urlGuia=${urlGuia?.let { ConexionXtream.redactar(it) } ?: "ausente"}, " +
            "actualizacionAutomatica=$actualizacionAutomatica, intervaloHoras=$intervaloHoras, activa=$activa)"

    companion object {
        const val INTERVALO_POR_DEFECTO: Long = 24
        const val INTERVALO_MINIMO_HORAS: Long = 1
        const val INTERVALO_MAXIMO_HORAS: Long = 720
    }
}

/** Validación pura del Método B; devuelve todos los errores, nunca lanza. */
object ConexionM3U {
    fun validar(datos: DatosConexionM3U): List<ErrorConexion> {
        val errores = mutableListOf<ErrorConexion>()
        if (datos.nombre.isBlank()) {
            errores += ErrorConexion(CodigoErrorConexion.NOMBRE_VACIO, "El nombre de la conexión es obligatorio.")
        }
        if (datos.urlLista.isBlank()) {
            errores += ErrorConexion(CodigoErrorConexion.LISTA_VACIA, "La URL de la lista es obligatoria.")
        } else if (ConexionXtream.validarDestinoHttp(datos.urlLista) == null) {
            errores +=
                ErrorConexion(
                    CodigoErrorConexion.LISTA_NO_HTTP,
                    "La URL de la lista debe ser http o https y no incluir credenciales.",
                )
        }
        datos.urlGuia?.takeIf { it.isNotBlank() }?.let { guia ->
            if (ConexionXtream.validarDestinoHttp(guia) == null) {
                errores +=
                    ErrorConexion(
                        CodigoErrorConexion.GUIA_NO_HTTP,
                        "La URL de la guía debe ser http o https y no incluir credenciales.",
                    )
            }
        }
        if (datos.intervaloHoras !in DatosConexionM3U.INTERVALO_MINIMO_HORAS..DatosConexionM3U.INTERVALO_MAXIMO_HORAS) {
            errores +=
                ErrorConexion(
                    CodigoErrorConexion.INTERVALO_FUERA_DE_RANGO,
                    "El intervalo de actualización debe estar entre 1 y 720 horas.",
                )
        }
        return errores
    }
}
