package com.copernicotv.core.conexion

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Protocolo del servidor Xtream Codes. No se admite otro esquema. */
enum class ProtocoloConexion(val esquema: String) {
    HTTP("http"),
    HTTPS("https"),
    ;

    companion object {
        fun desdeEsquema(esquema: String?): ProtocoloConexion? =
            entries.firstOrNull { it.esquema.equals(esquema?.trim()?.lowercase(), ignoreCase = true) }
    }
}

/**
 * Formato de salida de la lista M3U que sirve `get.php`.
 *
 * Solo se aceptan los dos valores documentados por el proveedor. Un formato no
 * reconocido no se traduce «al más parecido»: se informa como error tipado.
 */
enum class FormatoSalidaXtream(val valor: String) {
    M3U8("m3u8"),
    TS("ts"),
    ;

    companion object {
        fun desdeValor(valor: String?): FormatoSalidaXtream? =
            entries.firstOrNull { it.valor.equals(valor?.trim()?.lowercase(), ignoreCase = true) }
    }
}

/**
 * Datos de una conexión Xtream Codes.
 *
 * [contrasena] y [usuario] son secretos: [toString] los redacta para que un log
 * accidental de la instancia no filtre credenciales. El almacenamiento cifrado
 * los recibe por separado; esta clase no persiste nada.
 */
data class DatosConexionXtream(
    val nombre: String,
    val servidor: String,
    val puerto: Int?,
    val protocolo: ProtocoloConexion,
    val usuario: String,
    val contrasena: String,
    val formatoSalida: FormatoSalidaXtream,
    val urlGuia: String? = null,
) {
    override fun toString(): String =
        "DatosConexionXtream(nombre=$nombre, servidor=$servidor, puerto=$puerto, " +
            "protocolo=$protocolo, usuario=${redactar(usuario)}, contrasena=***, " +
            "formatoSalida=$formatoSalida, urlGuia=${urlGuia?.let { "presente" } ?: "ausente"})"

    companion object {
        private fun redactar(valor: String): String = if (valor.isEmpty()) "" else valor.take(1) + "***"
    }
}

/** Códigos estables de validación; se muestran al usuario sin datos sensibles. */
enum class CodigoErrorConexion(val codigo: String) {
    NOMBRE_VACIO("conexion_nombre_vacio"),
    SERVIDOR_VACIO("conexion_servidor_vacio"),
    SERVIDOR_INVALIDO("conexion_servidor_invalido"),
    SERVIDOR_CON_CREDENCIALES("conexion_servidor_con_credenciales"),
    ESQUEMA_NO_PERMITIDO("conexion_esquema_no_permitido"),
    PUERTO_FUERA_DE_RANGO("conexion_puerto_fuera_de_rango"),
    USUARIO_VACIO("conexion_usuario_vacio"),
    CONTRASENA_VACIA("conexion_contrasena_vacia"),
    FORMATO_NO_SOPORTADO("conexion_formato_no_soportado"),
    GUIA_NO_HTTP("conexion_guia_no_http"),
    LISTA_NO_HTTP("conexion_lista_no_http"),
    LISTA_VACIA("conexion_lista_vacia"),
    INTERVALO_FUERA_DE_RANGO("conexion_intervalo_fuera_de_rango"),
}

/** Error de validación: código estable y mensaje ya redactado. */
data class ErrorConexion(
    val codigo: CodigoErrorConexion,
    val mensaje: String,
)

/**
 * Construcción y validación de URLs de Xtream Codes.
 *
 * Rutas usadas, todas estándar del proveedor y ninguna inventada:
 * - `player_api.php` para comprobar credenciales y estado de la cuenta.
 * - `get.php?type=m3u_plus&output=…` para la lista de canales.
 * - `xmltv.php` para la guía que el propio panel expone.
 *
 * La clase es pura: no hace red. El adaptador de `:app` es quien ejecuta las
 * peticiones, aplica timeouts y decide sobre redirecciones.
 */
object ConexionXtream {
    private const val RUTA_PLAYER_API = "player_api.php"
    private const val RUTA_LISTA = "get.php"
    private const val RUTA_GUIA = "xmltv.php"

    private val ETIQUETA_SERVIDOR = Regex("^[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?$")
    private val IP_V4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")

    /** Valida los datos y devuelve todos los errores encontrados, sin lanzar. */
    fun validar(datos: DatosConexionXtream): List<ErrorConexion> {
        val errores = mutableListOf<ErrorConexion>()
        if (datos.nombre.isBlank()) {
            errores += ErrorConexion(CodigoErrorConexion.NOMBRE_VACIO, "El nombre de la conexión es obligatorio.")
        }
        if (datos.servidor.isBlank()) {
            errores += ErrorConexion(CodigoErrorConexion.SERVIDOR_VACIO, "La URL del servidor es obligatoria.")
        } else {
            errores += validarServidor(datos.servidor, datos.protocolo)
        }
        if (datos.puerto != null && datos.puerto !in 1..65535) {
            errores +=
                ErrorConexion(
                    CodigoErrorConexion.PUERTO_FUERA_DE_RANGO,
                    "El puerto debe estar entre 1 y 65535.",
                )
        }
        if (datos.usuario.isBlank()) {
            errores += ErrorConexion(CodigoErrorConexion.USUARIO_VACIO, "El usuario es obligatorio.")
        }
        if (datos.contrasena.isEmpty()) {
            errores += ErrorConexion(CodigoErrorConexion.CONTRASENA_VACIA, "La contraseña es obligatoria.")
        }
        datos.urlGuia?.takeIf { it.isNotBlank() }?.let { guia ->
            if (validarDestinoHttp(guia) == null) {
                errores +=
                    ErrorConexion(
                        CodigoErrorConexion.GUIA_NO_HTTP,
                        "La URL de la guía debe ser http o https y no incluir credenciales.",
                    )
            }
        }
        return errores
    }

    /**
     * Normaliza el servidor a su host, sin esquema, ruta ni puerto.
     *
     * Acepta lo que el usuario suele pegar (`https://host:8080/c/`), pero
     * rechaza credenciales embebidas (`usuario:clave@host`) y cualquier
     * carácter que no pertenezca a un nombre de host o a una IPv4. Devuelve
     * `null` si no es utilizable.
     */
    fun normalizarServidor(servidor: String): String? = descomponer(servidor)?.first

    /** Puerto escrito dentro del servidor (`host:8080`), si lo hay. */
    fun puertoDeclarado(servidor: String): Int? = descomponer(servidor)?.second

    /**
     * Comprueba que [url] es un destino `http`/`https` sin credenciales
     * embebidas. Se aplica a la URL de guía y a las redirecciones que el
     * adaptador decida seguir.
     */
    fun validarDestinoHttp(url: String): String? {
        val recortado = url.trim()
        val esquema =
            when {
                recortado.startsWith("https://", ignoreCase = true) -> "https"
                recortado.startsWith("http://", ignoreCase = true) -> "http"
                else -> return null
            }
        val resto = recortado.substring(esquema.length + 3)
        val autoridad = resto.substringBefore('/').substringBefore('?').substringBefore('#')
        if (autoridad.isEmpty() || autoridad.contains('@')) return null
        if (autoridad.contains(' ')) return null
        val host = autoridad.substringBefore(':')
        if (host.isEmpty()) return null
        return recortado
    }

    /** Base `protocolo://host[:puerto]`, o `null` si los datos no son válidos. */
    fun base(datos: DatosConexionXtream): String? {
        val (host, puertoEmbebido) = descomponer(datos.servidor) ?: return null
        val puerto = datos.puerto ?: puertoEmbebido
        val sufijo = puerto?.let { ":$it" }.orEmpty()
        return "${datos.protocolo.esquema}://$host$sufijo"
    }

    /** `player_api.php` con credenciales, para comprobar la cuenta. */
    fun urlPlayerApi(datos: DatosConexionXtream): String? = base(datos)?.let { "$it/$RUTA_PLAYER_API?${credenciales(datos)}" }

    /** `get.php` con credenciales y formato de salida, para la lista M3U. */
    fun urlLista(datos: DatosConexionXtream): String? =
        base(datos)?.let {
            "$it/$RUTA_LISTA?${credenciales(datos)}&type=m3u_plus&output=${datos.formatoSalida.valor}"
        }

    /** `xmltv.php` con credenciales, para la guía que expone el panel. */
    fun urlGuia(datos: DatosConexionXtream): String? = base(datos)?.let { "$it/$RUTA_GUIA?${credenciales(datos)}" }

    /**
     * URL de guía efectiva: la declarada por el usuario tiene prioridad sobre
     * la generada por el panel, tal como exige el contrato de conexiones.
     */
    fun urlGuiaEfectiva(datos: DatosConexionXtream): String? =
        datos.urlGuia?.takeIf { it.isNotBlank() }?.let { validarDestinoHttp(it) } ?: urlGuia(datos)

    /** Redacta `usuario`, `password` y `username` de un texto para logs y errores. */
    fun redactar(texto: String): String =
        texto
            .replace(Regex("(?i)(password|username|usuario|contrasena)=([^&\\s]*)"), "$1=***")
            .replace(Regex("(?i)://([^:/@\\s]+):([^@/\\s]+)@"), "://***:***@")

    /**
     * Separa `host` y puerto embebido, validando ambos. Devuelve `null` si el
     * servidor no es utilizable.
     */
    @Suppress("ReturnCount")
    private fun descomponer(servidor: String): Pair<String, Int?>? {
        val recortado = servidor.trim()
        if (recortado.isEmpty() || recortado.contains('@')) return null
        val sinEsquema =
            when {
                recortado.startsWith("http://", ignoreCase = true) -> recortado.substring(7)
                recortado.startsWith("https://", ignoreCase = true) -> recortado.substring(8)
                recortado.contains("://") -> return null
                else -> recortado
            }
        val autoridad = sinEsquema.substringBefore('/').substringBefore('?').substringBefore('#')
        if (autoridad.isEmpty()) return null
        val host = autoridad.substringBefore(':')
        val puertoTexto = autoridad.substringAfter(':', missingDelimiterValue = "")
        val puerto =
            if (puertoTexto.isEmpty()) {
                null
            } else {
                puertoTexto.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            }
        if (host.isEmpty() || !ETIQUETA_SERVIDOR.matches(host)) return null
        val ip = IP_V4.matchEntire(host)
        if (ip != null && ip.groupValues.drop(1).any { it.toIntOrNull() !in 0..255 }) return null
        return host to puerto
    }

    private fun validarServidor(
        servidor: String,
        protocolo: ProtocoloConexion,
    ): List<ErrorConexion> {
        if (servidor.contains('@')) {
            return listOf(
                ErrorConexion(
                    CodigoErrorConexion.SERVIDOR_CON_CREDENCIALES,
                    "La URL del servidor no debe incluir usuario ni contraseña.",
                ),
            )
        }
        val esquemaDeclarado =
            when {
                servidor.startsWith("http://", ignoreCase = true) -> ProtocoloConexion.HTTP
                servidor.startsWith("https://", ignoreCase = true) -> ProtocoloConexion.HTTPS
                servidor.contains("://") -> null
                else -> protocolo
            }
        if (esquemaDeclarado == null) {
            return listOf(
                ErrorConexion(CodigoErrorConexion.ESQUEMA_NO_PERMITIDO, "El esquema debe ser http o https."),
            )
        }
        if (esquemaDeclarado != protocolo) {
            return listOf(
                ErrorConexion(
                    CodigoErrorConexion.ESQUEMA_NO_PERMITIDO,
                    "El esquema de la URL no coincide con el protocolo elegido.",
                ),
            )
        }
        if (normalizarServidor(servidor) == null) {
            val autoridad =
                servidor
                    .substringAfter("://", servidor)
                    .substringBefore('/')
                    .substringBefore('?')
            val puertoTexto = autoridad.substringAfter(':', missingDelimiterValue = "")
            val puertoInvalido = puertoTexto.isNotEmpty() && puertoTexto.toIntOrNull() !in 1..65535
            return listOf(
                if (puertoInvalido) {
                    ErrorConexion(
                        CodigoErrorConexion.PUERTO_FUERA_DE_RANGO,
                        "El puerto debe estar entre 1 y 65535.",
                    )
                } else {
                    ErrorConexion(
                        CodigoErrorConexion.SERVIDOR_INVALIDO,
                        "El servidor no es un nombre de host ni una dirección IPv4 válida.",
                    )
                },
            )
        }
        return emptyList()
    }

    private fun credenciales(datos: DatosConexionXtream): String =
        "username=${codificar(datos.usuario)}&password=${codificar(datos.contrasena)}"

    private fun codificar(valor: String): String = URLEncoder.encode(valor, StandardCharsets.UTF_8)
}
