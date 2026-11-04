package com.copernicotv.app.data.cache

import com.copernicotv.app.crypto.ClavesApp
import com.copernicotv.core.cache.TipoCache
import com.copernicotv.core.crypto.CifradoAes
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.spec.GCMParameterSpec

/**
 * Almacén de contenido de la caché de transporte (DC-09, S51d, S51g).
 *
 * El contenido vive en archivos privados del directorio de la aplicación con
 * nombres opacos (`UUID`), nunca con la URL ni parte de ella. Cada archivo usa
 * el mismo sobre que [CifradoAes] —`0x01` + IV de 12 bytes + ciphertext + tag
 * GCM— pero escrito en streaming, porque una copia XMLTV puede llegar a
 * 96 MiB y no cabe razonablemente en memoria. El AAD es propio del tipo de
 * caché, de modo que un archivo M3U no puede servir como copia XMLTV.
 *
 * La escritura es temp + fsync + rename dentro del mismo directorio: una
 * lectura concurrente ve la copia anterior completa o la nueva, nunca un
 * archivo a medias.
 */
class AlmacenCache(
    private val directorio: File,
    private val claves: ClavesApp,
    /**
     * Borrado físico del archivo. Se inyecta solo para poder probar el camino
     * de error de borrado que DC-09 exige registrar; en producción borra.
     */
    private val borradoFisico: (File) -> Boolean = { it.delete() },
) {
    init {
        if (!directorio.isDirectory && !directorio.mkdirs() && !directorio.isDirectory) {
            error("no se pudo preparar el directorio de caché")
        }
    }

    /** Nombre opaco nuevo para un contenido o un temporal. */
    fun nombreNuevo(): String = "${UUID.randomUUID()}$SUFIJO"

    /** Temporal de descarga en el mismo directorio privado que el contenido. */
    fun temporalNuevo(): File = File(directorio, "${UUID.randomUUID()}$SUFIJO_TEMPORAL")

    /** Archivo de [nombre]; rechaza cualquier referencia con separadores de ruta. */
    fun archivo(nombre: String): File = File(directorio, validar(nombre))

    fun existe(nombre: String): Boolean = archivo(nombre).isFile

    fun tamano(nombre: String): Long = archivo(nombre).length()

    /** Nombres presentes en el directorio, en orden estable. */
    fun nombres(): List<String> =
        directorio
            .listFiles()
            ?.filter { it.isFile }
            ?.map { it.name }
            ?.sorted()
            .orEmpty()

    fun libreBytes(): Long = directorio.usableSpace

    /**
     * Cifra [origen] en [nombre] de forma atómica.
     *
     * El archivo temporal se sincroniza a disco antes del `rename`: si el
     * proceso muere, el directorio conserva la copia anterior o la nueva, y un
     * `.tmp` suelto queda como huérfano para el barrido.
     */
    fun escribir(
        nombre: String,
        origen: File,
        tipo: TipoCache,
    ) {
        val destino = archivo(nombre)
        val temporal = File(directorio, "$nombre$SUFIJO_TEMPORAL")
        try {
            cifrarEn(temporal, origen, tipo)
            sincronizar(temporal)
            if (!temporal.renameTo(destino)) {
                throw IOException("no se pudo publicar el archivo de caché")
            }
        } finally {
            temporal.delete()
        }
    }

    private fun cifrarEn(
        temporal: File,
        origen: File,
        tipo: TipoCache,
    ) {
        val iv = ByteArray(CifradoAes.LONGITUD_IV).also(aleatorio::nextBytes)
        val cifrador = Cipher.getInstance(TRANSFORMACION)
        cifrador.init(
            Cipher.ENCRYPT_MODE,
            claves.claveAes,
            GCMParameterSpec(CifradoAes.LONGITUD_TAG_BITS, iv),
        )
        cifrador.updateAAD(aad(tipo).toByteArray(Charsets.UTF_8))
        FileOutputStream(temporal).use { salida ->
            salida.write(CifradoAes.VERSION.toInt())
            salida.write(iv)
            CipherOutputStream(salida, cifrador).use { cifrado ->
                origen.inputStream().use { entrada ->
                    entrada.copyTo(cifrado, TAMANO_BUFFER)
                }
            }
        }
    }

    /**
     * Flujo de lectura descifrado, o `null` si el archivo no existe, no tiene
     * el sobre esperado o falla la autenticación GCM al consumirlo.
     */
    fun abrir(
        nombre: String,
        tipo: TipoCache,
    ): InputStream? {
        val archivo = archivo(nombre)
        if (!archivo.isFile) return null
        val entrada = archivo.inputStream()
        return try {
            val iv = leerCabecera(entrada)
            if (iv == null) {
                entrada.close()
                return null
            }
            val cifrador = Cipher.getInstance(TRANSFORMACION)
            cifrador.init(
                Cipher.DECRYPT_MODE,
                claves.claveAes,
                GCMParameterSpec(CifradoAes.LONGITUD_TAG_BITS, iv),
            )
            cifrador.updateAAD(aad(tipo).toByteArray(Charsets.UTF_8))
            CipherInputStream(entrada, cifrador)
        } catch (_: IOException) {
            entrada.close()
            null
        } catch (_: GeneralSecurityException) {
            entrada.close()
            null
        }
    }

    /** Borra el archivo y devuelve si dejó de existir. */
    fun borrar(nombre: String): Boolean {
        val archivo = archivo(nombre)
        if (!archivo.exists()) return true
        return borradoFisico(archivo)
    }

    /**
     * Lee la versión de formato y el IV, y deja el flujo listo para el
     * ciphertext. Devuelve `null` si el sobre no es reconocible.
     */
    private fun leerCabecera(entrada: InputStream): ByteArray? {
        if (entrada.read() != CifradoAes.VERSION.toInt()) return null
        val iv = ByteArray(CifradoAes.LONGITUD_IV)
        var leidos = 0
        while (leidos < iv.size) {
            val ahora = entrada.read(iv, leidos, iv.size - leidos)
            if (ahora < 0) return null
            leidos += ahora
        }
        return iv
    }

    /** Fuerza a disco el archivo ya cerrado, sin volver a escribirlo. */
    private fun sincronizar(archivo: File) {
        FileChannel.open(archivo.toPath(), StandardOpenOption.WRITE).use { canal ->
            canal.force(true)
        }
    }

    private fun validar(nombre: String): String {
        require(
            nombre.isNotBlank() &&
                '/' !in nombre &&
                '\\' !in nombre &&
                nombre != "." &&
                nombre != "..",
        ) { "referencia de archivo inválida" }
        return nombre
    }

    private fun aad(tipo: TipoCache): String =
        when (tipo) {
            TipoCache.M3U -> AAD_M3U
            TipoCache.XMLTV -> AAD_XMLTV
        }

    private companion object {
        const val TRANSFORMACION = "AES/GCM/NoPadding"
        const val TAMANO_BUFFER = 32 * 1024
        const val SUFIJO = ".bin"
        const val SUFIJO_TEMPORAL = ".tmp"
        const val AAD_M3U = "CacheM3U.archivo"
        const val AAD_XMLTV = "CacheXmltv.archivo"

        val aleatorio = SecureRandom()
    }
}
