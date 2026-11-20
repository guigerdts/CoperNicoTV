package com.copernicotv.core.ota

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.net.URI
import java.time.Instant
import java.time.format.DateTimeParseException

/** Motivo tipado por el que un manifiesto no se acepta. */
enum class MotivoManifiesto {
    JSON_MALFORMADO,
    ESQUEMA_NO_SOPORTADO,
    CAMPO_FALTANTE,
    TIPO_INVALIDO,
    PAQUETE_INCORRECTO,
    VERSION_INVALIDA,
    CANAL_INVALIDO,
    MIN_SDK_INVALIDO,
    FECHA_INVALIDA,
    TAMANO_INVALIDO,
    HASH_INVALIDO,
    URL_INVALIDA,
    NOTAS_INVALIDAS,
    FIRMA_APK_NO_COINCIDE,
}

/** Resultado de leer un manifiesto de actualización. */
sealed interface ResultadoManifiesto {
    data class Valido(val manifiesto: ManifiestoActualizacion) : ResultadoManifiesto

    data class Invalido(val motivo: MotivoManifiesto) : ResultadoManifiesto
}

/**
 * Manifiesto publicado junto al APK de un release.
 *
 * Es el contrato entre el workflow de publicación y la app. Todos los campos
 * son obligatorios: un manifiesto incompleto no se completa con suposiciones.
 * La autenticidad no la da este objeto, sino la firma separada que se verifica
 * en [FirmaManifiesto] antes de llamar a [parse].
 */
data class ManifiestoActualizacion(
    val esquema: Int,
    val paquete: String,
    val versionCode: Long,
    val versionName: String,
    val canal: CanalActualizacion,
    val minSdk: Int,
    val publicadoEn: Instant,
    val tamanoBytes: Long,
    val sha256: String,
    val urlDescarga: String,
    val urlRelease: String,
    val notas: String,
    val obligatoria: Boolean,
    val huellaFirmaApk: String,
) {
    companion object {
        /** Versión de esquema que entiende esta compilación. */
        const val ESQUEMA_ACTUAL: Int = 1

        /** Tope del documento: el manifiesto real ronda 1 KiB. */
        const val MAX_BYTES: Int = 64 * 1024

        /** Tope de las notas, para no aceptar un documento desmedido. */
        const val MAX_CARACTERES_NOTAS: Int = 8000

        /** Tope del APK aceptado (500 MiB). */
        const val MAX_BYTES_APK: Long = 500L * 1024L * 1024L

        /**
         * Valida y construye el manifiesto.
         *
         * [paqueteEsperado] y [huellaFirmaEsperada] se fijan en la app: un
         * manifiesto que hable de otro paquete u otra identidad de firma es
         * inválido aunque su JSON sea correcto.
         */
        fun parse(
            texto: String,
            paqueteEsperado: String,
            huellaFirmaEsperada: String,
        ): ResultadoManifiesto {
            if (texto.isEmpty() || texto.length > MAX_BYTES) return ResultadoManifiesto.Invalido(MotivoManifiesto.JSON_MALFORMADO)
            val objeto = objetoDe(texto) ?: return ResultadoManifiesto.Invalido(MotivoManifiesto.JSON_MALFORMADO)
            if (CAMPOS_REQUERIDOS.any { !objeto.containsKey(it) }) return ResultadoManifiesto.Invalido(MotivoManifiesto.CAMPO_FALTANTE)
            val crudos = camposDe(objeto) ?: return ResultadoManifiesto.Invalido(MotivoManifiesto.TIPO_INVALIDO)
            return construir(crudos, paqueteEsperado, huellaFirmaEsperada)
        }

        private fun objetoDe(texto: String): JsonObject? =
            try {
                Json.parseToJsonElement(texto).jsonObject
            } catch (_: IllegalArgumentException) {
                null
            } catch (_: SerializationException) {
                null
            }

        private fun camposDe(objeto: JsonObject): CamposCrudos? {
            val identidad = identidadDe(objeto) ?: return null
            val compatibilidad = compatibilidadDe(objeto) ?: return null
            val archivo = archivoDe(objeto) ?: return null
            val publicacion = publicacionDe(objeto) ?: return null
            return CamposCrudos(
                esquema = identidad.esquema,
                paquete = identidad.paquete,
                versionCode = identidad.versionCode,
                versionName = identidad.versionName,
                canal = compatibilidad.canal,
                minSdk = compatibilidad.minSdk,
                publicadoEn = publicacion.publicadoEn,
                tamanoBytes = archivo.tamanoBytes,
                sha256 = archivo.sha256,
                urlDescarga = archivo.urlDescarga,
                urlRelease = publicacion.urlRelease,
                notas = publicacion.notas,
                obligatoria = publicacion.obligatoria,
                huellaFirma = publicacion.huellaFirma,
            )
        }

        private fun identidadDe(objeto: JsonObject): IdentidadCruda? {
            val esquema = objeto.entero("schema")?.toInt() ?: return null
            val paquete = objeto.texto("packageName") ?: return null
            val versionCode = objeto.entero("versionCode") ?: return null
            val versionName = objeto.texto("versionName") ?: return null
            return IdentidadCruda(esquema, paquete, versionCode, versionName)
        }

        private fun compatibilidadDe(objeto: JsonObject): CompatibilidadCruda? {
            val canal = objeto.texto("channel") ?: return null
            val minSdk = objeto.entero("minSdk")?.toInt() ?: return null
            return CompatibilidadCruda(canal, minSdk)
        }

        private fun archivoDe(objeto: JsonObject): ArchivoCrudo? {
            val tamano = objeto.entero("sizeBytes") ?: return null
            val sha256 = objeto.texto("sha256") ?: return null
            val urlDescarga = objeto.texto("downloadUrl") ?: return null
            return ArchivoCrudo(tamano, sha256, urlDescarga)
        }

        private fun publicacionDe(objeto: JsonObject): PublicacionCruda? {
            val publicadoEn = objeto.texto("publishedAt") ?: return null
            val urlRelease = objeto.texto("releaseUrl") ?: return null
            val notas = objeto.texto("releaseNotes") ?: return null
            val obligatoria = objeto.booleano("mandatory") ?: return null
            val huella = objeto.texto("signingCertSha256") ?: return null
            return PublicacionCruda(publicadoEn, urlRelease, notas, obligatoria, huella)
        }

        private fun construir(
            crudos: CamposCrudos,
            paqueteEsperado: String,
            huellaEsperada: String,
        ): ResultadoManifiesto {
            val motivo = validarIdentidad(crudos, paqueteEsperado) ?: validarDistribucion(crudos)
            if (motivo != null) return ResultadoManifiesto.Invalido(motivo)
            if (!crudos.huellaFirma.equals(huellaEsperada, ignoreCase = true)) {
                return ResultadoManifiesto.Invalido(MotivoManifiesto.FIRMA_APK_NO_COINCIDE)
            }
            val canal = CanalActualizacion.desde(crudos.canal) ?: return ResultadoManifiesto.Invalido(MotivoManifiesto.CANAL_INVALIDO)
            val fecha = fechaDe(crudos.publicadoEn) ?: return ResultadoManifiesto.Invalido(MotivoManifiesto.FECHA_INVALIDA)
            return ResultadoManifiesto.Valido(
                ManifiestoActualizacion(
                    esquema = crudos.esquema,
                    paquete = crudos.paquete,
                    versionCode = crudos.versionCode,
                    versionName = crudos.versionName,
                    canal = canal,
                    minSdk = crudos.minSdk,
                    publicadoEn = fecha,
                    tamanoBytes = crudos.tamanoBytes,
                    sha256 = crudos.sha256.lowercase(),
                    urlDescarga = crudos.urlDescarga,
                    urlRelease = crudos.urlRelease,
                    notas = crudos.notas,
                    obligatoria = crudos.obligatoria,
                    huellaFirmaApk = crudos.huellaFirma.lowercase(),
                ),
            )
        }

        private fun validarIdentidad(
            crudos: CamposCrudos,
            paqueteEsperado: String,
        ): MotivoManifiesto? =
            when {
                crudos.esquema != ESQUEMA_ACTUAL -> MotivoManifiesto.ESQUEMA_NO_SOPORTADO
                crudos.paquete != paqueteEsperado -> MotivoManifiesto.PAQUETE_INCORRECTO
                crudos.versionCode <= 0L || crudos.versionCode > VERSION_CODE_MAXIMO -> MotivoManifiesto.VERSION_INVALIDA
                !PATRON_VERSION.matches(crudos.versionName) || crudos.versionName.length > 64 -> MotivoManifiesto.VERSION_INVALIDA
                crudos.minSdk !in SDK_MINIMO..SDK_MAXIMO -> MotivoManifiesto.MIN_SDK_INVALIDO
                crudos.notas.length > MAX_CARACTERES_NOTAS -> MotivoManifiesto.NOTAS_INVALIDAS
                else -> null
            }

        private fun validarDistribucion(crudos: CamposCrudos): MotivoManifiesto? =
            when {
                crudos.tamanoBytes <= 0L || crudos.tamanoBytes > MAX_BYTES_APK -> MotivoManifiesto.TAMANO_INVALIDO
                !HEX_SHA256.matches(crudos.sha256) -> MotivoManifiesto.HASH_INVALIDO
                !HEX_SHA256.matches(crudos.huellaFirma) -> MotivoManifiesto.HASH_INVALIDO
                !urlHttpsDeGithub(crudos.urlDescarga, HOSTS_DESCARGA) -> MotivoManifiesto.URL_INVALIDA
                !urlHttpsDeGithub(crudos.urlRelease, HOSTS_RELEASE) -> MotivoManifiesto.URL_INVALIDA
                else -> null
            }

        private fun fechaDe(texto: String): Instant? =
            try {
                Instant.parse(texto)
            } catch (_: DateTimeParseException) {
                null
            }

        private fun urlHttpsDeGithub(
            url: String,
            hosts: Set<String>,
        ): Boolean {
            if (url.length > 2048) return false
            val uri =
                try {
                    URI(url)
                } catch (_: IllegalArgumentException) {
                    return false
                }
            val host = uri.host?.lowercase()
            return uri.scheme.equals("https", ignoreCase = true) &&
                uri.rawUserInfo == null &&
                (uri.port == -1 || uri.port == 443) &&
                host != null &&
                host in hosts
        }

        private val PATRON_VERSION = Regex("""^[0-9]+(\.[0-9]+){0,3}([-+][0-9A-Za-z.\-]+)?$""")
        private val HEX_SHA256 = Regex("^[0-9a-fA-F]{64}$")
        private val CAMPOS_REQUERIDOS =
            listOf(
                "schema",
                "packageName",
                "versionCode",
                "versionName",
                "channel",
                "minSdk",
                "publishedAt",
                "sizeBytes",
                "sha256",
                "downloadUrl",
                "releaseUrl",
                "releaseNotes",
                "mandatory",
                "signingCertSha256",
            )
        private const val VERSION_CODE_MAXIMO = 2_100_000_000L
        private const val SDK_MINIMO = 1
        private const val SDK_MAXIMO = 1000
        private val HOSTS_DESCARGA =
            setOf("github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com")
        private val HOSTS_RELEASE = setOf("github.com")
    }
}

/** Campos aún sin validar semánticamente. */
private data class CamposCrudos(
    val esquema: Int,
    val paquete: String,
    val versionCode: Long,
    val versionName: String,
    val canal: String,
    val minSdk: Int,
    val publicadoEn: String,
    val tamanoBytes: Long,
    val sha256: String,
    val urlDescarga: String,
    val urlRelease: String,
    val notas: String,
    val obligatoria: Boolean,
    val huellaFirma: String,
)

/** Identidad mínima del manifiesto. */
private data class IdentidadCruda(
    val esquema: Int,
    val paquete: String,
    val versionCode: Long,
    val versionName: String,
)

/** Compatibilidad declarada por el manifiesto. */
private data class CompatibilidadCruda(
    val canal: String,
    val minSdk: Int,
)

/** Datos del APK publicado. */
private data class ArchivoCrudo(
    val tamanoBytes: Long,
    val sha256: String,
    val urlDescarga: String,
)

/** Metadatos de la publicación. */
private data class PublicacionCruda(
    val publicadoEn: String,
    val urlRelease: String,
    val notas: String,
    val obligatoria: Boolean,
    val huellaFirma: String,
)

private fun JsonObject.texto(nombre: String): String? {
    val primitivo = this[nombre] as? JsonPrimitive ?: return null
    return if (primitivo.isString) primitivo.content else null
}

private fun JsonObject.entero(nombre: String): Long? {
    val primitivo = this[nombre] as? JsonPrimitive ?: return null
    return if (primitivo.isString) null else primitivo.longOrNull
}

private fun JsonObject.booleano(nombre: String): Boolean? {
    val primitivo = this[nombre] as? JsonPrimitive ?: return null
    return if (primitivo.isString) null else primitivo.booleanOrNull
}
