package com.copernicotv.app.ota

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.copernicotv.core.ota.Integridad
import com.copernicotv.core.ota.ManifiestoActualizacion
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Metadatos del APK que participan de la verificación. */
data class InfoApk(
    val paquete: String,
    val versionCode: Long,
    val minSdk: Int,
    val huellasCertificado: List<String>,
)

/** Motivo tipado por el que un APK descargado no se instala. */
enum class FalloVerificacionApk {
    NO_LEGIBLE,
    PAQUETE,
    VERSION_CODE,
    MIN_SDK,
    FIRMA,
}

/** Resultado de verificar el APK antes de instalarlo. */
sealed interface ResultadoVerificacionApk {
    data object Valido : ResultadoVerificacionApk

    data class Fallo(
        val tipo: FalloVerificacionApk,
        val detalle: String? = null,
    ) : ResultadoVerificacionApk
}

/**
 * Reglas puras de verificación del APK.
 *
 * El manifiesto ya trae la identidad esperada y su autenticidad está probada
 * por la firma. Estas reglas comprueban que el binario descargado es ese APK y
 * no otro: mismo paquete, mismo `versionCode`, compatible con el dispositivo y
 * firmado con el certificado fijado en la app.
 */
object ReglasVerificacionApk {
    fun verificar(
        info: InfoApk,
        manifiesto: ManifiestoActualizacion,
        sdkDispositivo: Int,
    ): ResultadoVerificacionApk =
        when {
            info.paquete != manifiesto.paquete -> ResultadoVerificacionApk.Fallo(FalloVerificacionApk.PAQUETE, info.paquete)
            info.versionCode != manifiesto.versionCode ->
                ResultadoVerificacionApk.Fallo(FalloVerificacionApk.VERSION_CODE, "${info.versionCode}")
            info.minSdk > sdkDispositivo -> ResultadoVerificacionApk.Fallo(FalloVerificacionApk.MIN_SDK, "${info.minSdk}")
            info.huellasCertificado.none { Integridad.iguales(it, manifiesto.huellaFirmaApk) } ->
                ResultadoVerificacionApk.Fallo(FalloVerificacionApk.FIRMA)
            else -> ResultadoVerificacionApk.Valido
        }
}

/** Extrae los metadatos del APK antes de instalarlo. */
interface VerificadorApk {
    fun verificar(
        archivo: File,
        manifiesto: ManifiestoActualizacion,
    ): ResultadoVerificacionApk
}

/**
 * Implementación Android: lee el APK del disco sin instalarlo.
 *
 * `GET_SIGNING_CERTIFICATES` es obligatorio desde API 28; antes se usa
 * `GET_SIGNATURES`. La huella se calcula sobre el DER del certificado, igual
 * que la publicada en el manifiesto.
 */
@Singleton
class VerificadorApkAndroid
    @Inject
    constructor(
        @ApplicationContext private val contexto: Context,
    ) : VerificadorApk {
        override fun verificar(
            archivo: File,
            manifiesto: ManifiestoActualizacion,
        ): ResultadoVerificacionApk {
            if (!archivo.isFile) return ResultadoVerificacionApk.Fallo(FalloVerificacionApk.NO_LEGIBLE)
            val info = leerInfo(archivo) ?: return ResultadoVerificacionApk.Fallo(FalloVerificacionApk.NO_LEGIBLE)
            return ReglasVerificacionApk.verificar(info, manifiesto, Build.VERSION.SDK_INT)
        }

        private fun leerInfo(archivo: File): InfoApk? {
            val gestor = contexto.packageManager
            val info =
                try {
                    gestor.getPackageArchiveInfo(archivo.absolutePath, banderas())
                } catch (_: Exception) {
                    null
                } ?: return null
            return InfoApk(
                paquete = info.packageName,
                versionCode = versionCodeDe(info),
                minSdk = info.applicationInfo?.minSdkVersion ?: 1,
                huellasCertificado = huellasDe(info),
            )
        }

        private fun banderas(): Int =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                @Suppress("DEPRECATION")
                PackageManager.GET_SIGNATURES
            }

        private fun versionCodeDe(info: PackageInfo): Long =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }

        private fun huellasDe(info: PackageInfo): List<String> {
            val firmas =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    info.signingInfo?.apkContentsSigners ?: emptyArray()
                } else {
                    @Suppress("DEPRECATION")
                    info.signatures ?: emptyArray()
                }
            return firmas.map { Integridad.sha256Hex(it.toByteArray()) }
        }
    }
