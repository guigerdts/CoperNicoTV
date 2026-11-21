package com.copernicotv.app.ota

import com.copernicotv.core.ota.Integridad
import com.copernicotv.core.ota.ManifiestoActualizacion
import kotlinx.coroutines.delay
import java.io.File
import java.io.FileInputStream

/** Motivo tipado de un fallo al obtener el APK. */
enum class FalloDescargaApk {
    HASH,
    TAMANO,
    RED,
    ESCRITURA,
}

/** Resultado de obtener el APK verificado de una actualización. */
sealed interface ResultadoDescargaApk {
    data class Exito(val archivo: File) : ResultadoDescargaApk

    data class Fallo(
        val tipo: FalloDescargaApk,
        val detalle: String? = null,
    ) : ResultadoDescargaApk
}

/**
 * Descarga el APK del manifiesto y lo deja verificado en disco.
 *
 * La descarga es reanudable: el archivo parcial sobrevive y la siguiente
 * ejecución pide el resto con `Range`. Un hash incorrecto elimina el parcial
 * (no se reintenta un contenido que ya no coincide) y un tamaño incorrecto
 * reintenta desde donde quedó. Nunca se sobrescribe con otro `versionCode`
 * distinto: cada versión usa su propio nombre.
 *
 * [espera] se inyecta para que las pruebas no duerman de verdad.
 */
class DescargadorApk(
    private val cliente: ClienteHttpOta,
    private val directorio: File,
    private val intentosMaximos: Int = 3,
    private val espera: suspend (Int) -> Unit = { intento -> delay(intento.toLong() * 1000L) },
) {
    /** Ruta final del APK de [manifiesto]. */
    fun archivoFinal(manifiesto: ManifiestoActualizacion): File = File(directorio, "ota-${manifiesto.versionCode}.apk")

    /** Ruta del archivo parcial reanudable de [manifiesto]. */
    fun archivoParcial(manifiesto: ManifiestoActualizacion): File = File(archivoFinal(manifiesto).path + ".part")

    /** True si el archivo ya está descargado y coincide con el manifiesto. */
    fun estaVerificado(manifiesto: ManifiestoActualizacion): Boolean {
        val final = archivoFinal(manifiesto)
        return final.isFile && verificarHash(final, manifiesto.sha256)
    }

    /**
     * Obtiene el APK verificado.
     *
     * Si ya existe y su hash coincide no se descarga nada. Si el servidor
     * ignora `Range` y responde el archivo completo, el cliente reinicia el
     * parcial; la comprobación de tamaño evita aceptar un archivo incompleto.
     */
    suspend fun descargar(
        manifiesto: ManifiestoActualizacion,
        alProgresar: (escritos: Long, totales: Long) -> Unit = { _, _ -> },
    ): ResultadoDescargaApk {
        directorio.mkdirs()
        val final = archivoFinal(manifiesto)
        if (final.isFile) {
            if (verificarHash(final, manifiesto.sha256)) return ResultadoDescargaApk.Exito(final)
            final.delete()
        }
        val parcial = archivoParcial(manifiesto)
        if (parcial.isFile && parcial.length() > manifiesto.tamanoBytes) parcial.delete()
        return reintentar(manifiesto, parcial, final, alProgresar)
    }

    private suspend fun reintentar(
        manifiesto: ManifiestoActualizacion,
        parcial: File,
        final: File,
        alProgresar: (escritos: Long, totales: Long) -> Unit,
    ): ResultadoDescargaApk {
        var desde = parcial.takeIf { it.isFile }?.length() ?: 0L
        var ultimo = FalloDescargaApk.RED
        for (intento in 1..intentosMaximos) {
            when (val paso = unIntento(manifiesto, parcial, final, desde, alProgresar)) {
                is Paso.Terminado -> return paso.resultado
                is Paso.Reanudar -> {
                    desde = paso.desde
                    ultimo = paso.fallo
                    if (intento < intentosMaximos) espera(intento)
                }
            }
        }
        return ResultadoDescargaApk.Fallo(ultimo)
    }

    private suspend fun unIntento(
        manifiesto: ManifiestoActualizacion,
        parcial: File,
        final: File,
        desde: Long,
        alProgresar: (escritos: Long, totales: Long) -> Unit,
    ): Paso {
        val resultado =
            cliente.descargar(manifiesto.urlDescarga, parcial, desde) { escritos, _ ->
                alProgresar(escritos, manifiesto.tamanoBytes)
            }
        return when (resultado) {
            is ResultadoDescargaHttp.Completa -> alCompletar(manifiesto, parcial, final)
            is ResultadoDescargaHttp.Fallo -> alFallar(parcial, resultado)
        }
    }

    private fun alCompletar(
        manifiesto: ManifiestoActualizacion,
        parcial: File,
        final: File,
    ): Paso {
        if (parcial.length() != manifiesto.tamanoBytes) {
            return Paso.Reanudar(parcial.length(), FalloDescargaApk.TAMANO)
        }
        if (!verificarHash(parcial, manifiesto.sha256)) {
            parcial.delete()
            return Paso.Terminado(ResultadoDescargaApk.Fallo(FalloDescargaApk.HASH))
        }
        return if (mover(parcial, final)) {
            Paso.Terminado(ResultadoDescargaApk.Exito(final))
        } else {
            Paso.Terminado(ResultadoDescargaApk.Fallo(FalloDescargaApk.ESCRITURA))
        }
    }

    private fun alFallar(
        parcial: File,
        resultado: ResultadoDescargaHttp.Fallo,
    ): Paso {
        var reinicio = parcial.takeIf { it.isFile }?.length() ?: 0L
        if (resultado.tipo == FalloHttp.ESTADO_HTTP && reinicio > 0L) {
            parcial.delete()
            reinicio = 0L
        }
        return if (resultado.reintentable) {
            Paso.Reanudar(reinicio, FalloDescargaApk.RED)
        } else {
            Paso.Terminado(ResultadoDescargaApk.Fallo(FalloDescargaApk.RED, resultado.detalle))
        }
    }

    /** Un intento termina la descarga o deja el próximo punto de reanudación. */
    private sealed interface Paso {
        data class Terminado(val resultado: ResultadoDescargaApk) : Paso

        data class Reanudar(val desde: Long, val fallo: FalloDescargaApk) : Paso
    }

    /**
     * Borra APK y parciales de versiones distintas a [versionCodeConservado].
     *
     * Se ejecuta al arrancar para que una actualización vieja no ocupe espacio
     * indefinidamente ni se ofrece una versión que ya no corresponde.
     */
    fun limpiarExcepto(versionCodeConservado: Long?) {
        val archivos = directorio.listFiles() ?: return
        archivos.forEach { archivo ->
            val nombre = archivo.name
            if (!nombre.startsWith("ota-") || (!nombre.endsWith(".apk") && !nombre.endsWith(".apk.part"))) return@forEach
            val version = nombre.removePrefix("ota-").substringBefore(".").toLongOrNull() ?: return@forEach
            if (version != versionCodeConservado) archivo.delete()
        }
    }

    private fun verificarHash(
        archivo: File,
        esperado: String,
    ): Boolean =
        try {
            val actual = FileInputStream(archivo).use { Integridad.sha256Hex(it) }
            Integridad.iguales(actual, esperado)
        } catch (_: Exception) {
            false
        }

    private fun mover(
        parcial: File,
        final: File,
    ): Boolean {
        if (parcial.renameTo(final)) return true
        return try {
            parcial.copyTo(final, overwrite = true)
            parcial.delete()
            true
        } catch (_: Exception) {
            false
        }
    }
}
