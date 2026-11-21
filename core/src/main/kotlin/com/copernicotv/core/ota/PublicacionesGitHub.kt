package com.copernicotv.core.ota

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray

/** Activo publicado en un release de GitHub. */
data class ActivoGitHub(
    val nombre: String,
    val urlDescarga: String,
)

/**
 * Publicación de GitHub tal como la devuelve la API de releases.
 *
 * No se confía en estos datos para autenticidad: solo sirven para localizar el
 * manifiesto y su firma. El contenido que decide la actualización es el
 * manifiesto firmado, no la respuesta de la API.
 */
data class PublicacionGitHub(
    val etiqueta: String,
    val borrador: Boolean,
    val preliminar: Boolean,
    val urlHtml: String,
    val activos: List<ActivoGitHub>,
) {
    /** Activo por nombre exacto, por ejemplo `update.json`. */
    fun activo(nombre: String): ActivoGitHub? = activos.firstOrNull { it.nombre == nombre }
}

/** Parser tolerante de la lista de releases de GitHub. */
object PublicacionesGitHub {
    /** Tope del documento de la API (20 releases con metadatos). */
    const val MAX_BYTES: Int = 512 * 1024

    /** Nombre del manifiesto publicado junto al APK. */
    const val NOMBRE_MANIFIESTO: String = "update.json"

    /** Nombre de la firma separada del manifiesto. */
    const val NOMBRE_FIRMA: String = "update.json.sig"

    /**
     * Devuelve `null` si el documento no es una lista JSON.
     *
     * Un elemento con forma inválida se omite: la API puede crecer con campos
     * nuevos y una publicación rota no debe impedir leer las demás. La
     * autenticidad de lo que sí se lee la decide la firma del manifiesto.
     */
    fun parse(texto: String): List<PublicacionGitHub>? {
        if (texto.isEmpty() || texto.length > MAX_BYTES) return null
        val arreglo = arregloDe(texto) ?: return null
        return arreglo.mapNotNull(::publicacionDe)
    }

    private fun arregloDe(texto: String): JsonArray? =
        try {
            Json.parseToJsonElement(texto).jsonArray
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: SerializationException) {
            null
        }

    private fun publicacionDe(elemento: JsonElement): PublicacionGitHub? {
        val objeto = elemento as? JsonObject ?: return null
        val etiqueta = objeto.texto("tag_name") ?: return null
        val urlHtml = objeto.texto("html_url") ?: return null
        val borrador = objeto.booleano("draft") ?: false
        val preliminar = objeto.booleano("prerelease") ?: false
        val activos = objeto.activos()
        return PublicacionGitHub(
            etiqueta = etiqueta,
            borrador = borrador,
            preliminar = preliminar,
            urlHtml = urlHtml,
            activos = activos,
        )
    }

    private fun JsonObject.activos(): List<ActivoGitHub> {
        val arreglo = this["assets"] as? JsonArray ?: return emptyList()
        return arreglo.mapNotNull { elemento ->
            val activo = elemento as? JsonObject ?: return@mapNotNull null
            val nombre = activo.texto("name") ?: return@mapNotNull null
            val url = activo.texto("browser_download_url") ?: return@mapNotNull null
            ActivoGitHub(nombre, url)
        }
    }

    private fun JsonObject.texto(nombre: String): String? {
        val primitivo = this[nombre] as? JsonPrimitive ?: return null
        return if (primitivo.isString) primitivo.content else null
    }

    private fun JsonObject.booleano(nombre: String): Boolean? {
        val primitivo = this[nombre] as? JsonPrimitive ?: return null
        return if (primitivo.isString) null else primitivo.booleanOrNull
    }
}
