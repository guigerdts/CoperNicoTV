package com.copernicotv.app.ota

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageInstaller
import android.os.Build
import com.copernicotv.core.ota.ManifiestoActualizacion
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Motivo tipado de un fallo al preparar la instalación. */
enum class FalloInstalacion {
    PERMISO,
    VERIFICACION,
    SESION,
    ESCRITURA,
}

/** Resultado de pedir la instalación de un APK ya verificado. */
sealed interface ResultadoInstalacion {
    /** El sistema mostrará su diálogo de confirmación; el resultado llega a [EstadoInstalacionOta]. */
    data class ConfirmacionPendiente(val sesionId: Int) : ResultadoInstalacion

    data class Fallo(
        val tipo: FalloInstalacion,
        val detalle: String? = null,
    ) : ResultadoInstalacion
}

/** Desenlace final que reporta el sistema tras el diálogo de instalación. */
enum class DesenlaceInstalacion {
    INSTALADA,
    CANCELADA,
    FALLIDA,
}

/**
 * Estado de la última instalación pedida por la app.
 *
 * Lo escribe [ReceptorInstalacion] desde un `BroadcastReceiver` que el sistema
 * instancia fuera del grafo Hilt, por eso es un objeto de proceso y no una
 * dependencia inyectada. La UI de Ajustes lo observa para mostrar "instalada",
 * "cancelada" o el error.
 */
object EstadoInstalacionOta {
    private val mutable = MutableStateFlow<DesenlaceInstalacion?>(null)

    val estado: StateFlow<DesenlaceInstalacion?> = mutable.asStateFlow()

    /** Mensaje del sistema; solo para diagnóstico, nunca se muestra crudo. */
    var detalle: String? = null
        private set

    internal fun registrar(
        desenlace: DesenlaceInstalacion,
        mensaje: String?,
    ) {
        detalle = mensaje
        mutable.value = desenlace
    }

    fun olvidar() {
        detalle = null
        mutable.value = null
    }
}

/** Prepara e inicia la instalación de una actualización. */
interface InstaladorApk {
    /** True si la app tiene autorización para instalar paquetes. */
    fun permisoConcedido(): Boolean

    suspend fun instalar(
        archivo: File,
        manifiesto: ManifiestoActualizacion,
    ): ResultadoInstalacion
}

/**
 * Instalación con `PackageInstaller`.
 *
 * Nunca instala en silencio: abre una sesión, escribe el APK y la confirma con
 * el `IntentSender` del sistema, que muestra su propio diálogo. El paquete es
 * el mismo (`applicationId`) y la instalación es una actualización, por lo que
 * Room, DataStore y las URL cifradas se conservan.
 *
 * Se re-verifica el APK antes de escribir la sesión: aunque el descargador ya
 * lo hizo, entre la descarga y el toque del usuario el archivo pudo cambiar en
 * disco y la comprobación es barata comparada con instalar otro binario.
 */
@Singleton
class InstaladorApkAndroid
    @Inject
    constructor(
        @ApplicationContext private val contexto: Context,
        private val verificador: VerificadorApk,
    ) : InstaladorApk {
        override fun permisoConcedido(): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.O || contexto.packageManager.canRequestPackageInstalls()

        override suspend fun instalar(
            archivo: File,
            manifiesto: ManifiestoActualizacion,
        ): ResultadoInstalacion =
            withContext(Dispatchers.IO) {
                if (!permisoConcedido()) return@withContext ResultadoInstalacion.Fallo(FalloInstalacion.PERMISO)
                if (verificador.verificar(archivo, manifiesto) != ResultadoVerificacionApk.Valido) {
                    return@withContext ResultadoInstalacion.Fallo(FalloInstalacion.VERIFICACION)
                }
                val instalador = contexto.packageManager.packageInstaller
                val sesionId =
                    try {
                        instalador.createSession(parametros(archivo.length()))
                    } catch (fallo: Exception) {
                        return@withContext ResultadoInstalacion.Fallo(FalloInstalacion.SESION, fallo.message)
                    }
                escribirYConfirmar(instalador, sesionId, archivo)
            }

        private fun parametros(tamano: Long): PackageInstaller.SessionParams =
            PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                // La actualización reemplaza este mismo paquete; nada de otros.
                setAppPackageName(contexto.packageName)
                setSize(tamano)
            }

        private fun escribirYConfirmar(
            instalador: PackageInstaller,
            sesionId: Int,
            archivo: File,
        ): ResultadoInstalacion {
            val sesion =
                try {
                    instalador.openSession(sesionId)
                } catch (fallo: Exception) {
                    abandonar(instalador, sesionId)
                    return ResultadoInstalacion.Fallo(FalloInstalacion.SESION, fallo.message)
                }
            try {
                archivo.inputStream().use { entrada ->
                    sesion.openWrite(NOMBRE_APK, 0L, archivo.length()).use { salida ->
                        entrada.copyTo(salida)
                        sesion.fsync(salida)
                    }
                }
                EstadoInstalacionOta.olvidar()
                sesion.commit(intencionResultado(sesionId))
                return ResultadoInstalacion.ConfirmacionPendiente(sesionId)
            } catch (fallo: IOException) {
                abandonar(instalador, sesionId)
                return ResultadoInstalacion.Fallo(FalloInstalacion.ESCRITURA, fallo.message)
            } catch (fallo: SecurityException) {
                abandonar(instalador, sesionId)
                return ResultadoInstalacion.Fallo(FalloInstalacion.SESION, fallo.message)
            } finally {
                runCatching { sesion.close() }
            }
        }

        private fun abandonar(
            instalador: PackageInstaller,
            sesionId: Int,
        ) {
            runCatching { instalador.abandonSession(sesionId) }
        }

        private fun intencionResultado(sesionId: Int): IntentSender {
            val intencion =
                Intent(contexto, ReceptorInstalacion::class.java)
                    .setAction(ACCION_RESULTADO_INSTALACION)
                    .putExtra(EXTRA_SESION, sesionId)
            // El sistema completa el Intent con el estado: el PendingIntent debe
            // ser mutable o el resultado nunca llega al receptor.
            val pendiente =
                PendingIntent.getBroadcast(
                    contexto,
                    sesionId,
                    intencion,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
            return pendiente.intentSender
        }

        private companion object {
            const val NOMBRE_APK = "base.apk"
        }
    }

/** Recibe el desenlace de la sesión de `PackageInstaller`. */
class ReceptorInstalacion : BroadcastReceiver() {
    override fun onReceive(
        contexto: Context,
        intencion: Intent,
    ) {
        if (intencion.action != ACCION_RESULTADO_INSTALACION) return
        val codigo = intencion.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (codigo == PackageInstaller.STATUS_PENDING_USER_ACTION) return
        val desenlace =
            when (codigo) {
                PackageInstaller.STATUS_SUCCESS -> DesenlaceInstalacion.INSTALADA
                PackageInstaller.STATUS_FAILURE_ABORTED -> DesenlaceInstalacion.CANCELADA
                else -> DesenlaceInstalacion.FALLIDA
            }
        EstadoInstalacionOta.registrar(
            desenlace = desenlace,
            mensaje = intencion.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE),
        )
    }
}

internal const val ACCION_RESULTADO_INSTALACION = "com.copernicotv.app.ota.RESULTADO_INSTALACION"
internal const val EXTRA_SESION = "com.copernicotv.app.ota.SESION"
