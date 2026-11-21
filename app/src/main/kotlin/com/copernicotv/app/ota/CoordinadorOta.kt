package com.copernicotv.app.ota

import com.copernicotv.core.ota.ManifiestoActualizacion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.io.File

/** Motivo tipado de un intento de actualización que no terminó descargado. */
enum class FalloOta {
    CONFIGURACION,
    SIN_RED,
    SERVIDOR,
    FIRMA,
    MANIFIESTO,
    DESCARGA,
    VERIFICACION,
}

/** Resultado de una pasada completa de comprobación y descarga. */
sealed interface ResultadoOta {
    data class SinNovedad(val ultimaComprobacionMs: Long?) : ResultadoOta

    data class Incompatible(val manifiesto: ManifiestoActualizacion) : ResultadoOta

    data class Descargada(
        val manifiesto: ManifiestoActualizacion,
        val archivo: File,
    ) : ResultadoOta

    data class Fallo(
        val tipo: FalloOta,
        val detalle: String? = null,
    ) : ResultadoOta
}

/**
 * Una pasada del OTA: comprobar, descargar, verificar y avisar.
 *
 * Vive fuera de WorkManager y del sistema de notificaciones para probarse con
 * dobles. El worker solo lo invoca y traduce el resultado a `Result`; la UI
 * reutiliza la misma pasada para "Buscar actualizaciones".
 *
 * Si ya hay un APK verificado en disco no vuelve a descargar. El aviso de
 * progreso se emite como mucho una vez por punto porcentual: el cliente avisa
 * cada bloque de 8 KiB y notificar cada bloque saturaría el sistema.
 */
class CoordinadorOta(
    private val comprobador: ComprobadorActualizaciones,
    private val descargador: DescargadorApk,
    private val verificador: VerificadorApk,
    private val notificador: NotificadorOta,
    private val almacen: AlmacenManifiestoOta,
) {
    /** Ejecuta una pasada completa. Nunca lanza: devuelve el fallo tipado. */
    suspend fun ejecutar(): ResultadoOta {
        val comprobacion =
            try {
                comprobador.comprobar()
            } catch (cancelacion: CancellationException) {
                throw cancelacion
            } catch (fallo: Exception) {
                return ResultadoOta.Fallo(FalloOta.SERVIDOR, fallo.message)
            }
        return when (comprobacion) {
            is ResultadoComprobacion.Fallo -> fallo(comprobacion)
            ResultadoComprobacion.AlDia -> {
                descargador.limpiarExcepto(null)
                ResultadoOta.SinNovedad(almacen.ultimaComprobacionMs().first())
            }
            is ResultadoComprobacion.Incompatible -> {
                descargador.limpiarExcepto(null)
                ResultadoOta.Incompatible(comprobacion.manifiesto)
            }
            is ResultadoComprobacion.Disponible -> descargar(comprobacion.manifiesto)
        }
    }

    private suspend fun descargar(manifiesto: ManifiestoActualizacion): ResultadoOta {
        val final = descargador.archivoFinal(manifiesto)
        if (descargador.estaVerificado(manifiesto)) {
            descargador.limpiarExcepto(manifiesto.versionCode)
            notificador.listaParaInstalar(manifiesto)
            return ResultadoOta.Descargada(manifiesto, final)
        }

        notificador.versionDisponible(manifiesto)
        var ultimoPorcentaje = -1
        val resultado =
            descargador.descargar(manifiesto) { escritos, totales ->
                if (totales > 0L) {
                    val porcentaje = ((escritos * 100L) / totales).toInt()
                    if (porcentaje != ultimoPorcentaje) {
                        ultimoPorcentaje = porcentaje
                        notificador.progreso(manifiesto, escritos, totales)
                    }
                }
            }
        return when (resultado) {
            is ResultadoDescargaApk.Fallo -> {
                notificador.olvidarProgreso()
                ResultadoOta.Fallo(FalloOta.DESCARGA, resultado.tipo.name)
            }
            is ResultadoDescargaApk.Exito -> verificar(manifiesto, resultado.archivo)
        }
    }

    private fun verificar(
        manifiesto: ManifiestoActualizacion,
        archivo: File,
    ): ResultadoOta =
        when (val resultado = verificador.verificar(archivo, manifiesto)) {
            ResultadoVerificacionApk.Valido -> {
                descargador.limpiarExcepto(manifiesto.versionCode)
                notificador.listaParaInstalar(manifiesto)
                ResultadoOta.Descargada(manifiesto, archivo)
            }
            is ResultadoVerificacionApk.Fallo -> {
                // El hash coincidió pero el APK no es el que declara el
                // manifiesto: se descarta para no ofrecerlo de nuevo.
                archivo.delete()
                notificador.olvidarProgreso()
                ResultadoOta.Fallo(FalloOta.VERIFICACION, resultado.tipo.name)
            }
        }

    private fun fallo(resultado: ResultadoComprobacion.Fallo): ResultadoOta =
        when (resultado.tipo) {
            FalloComprobacion.SIN_PUBLICACIONES -> ResultadoOta.SinNovedad(null)
            FalloComprobacion.CONFIGURACION -> ResultadoOta.Fallo(FalloOta.CONFIGURACION, resultado.detalle)
            FalloComprobacion.SIN_RED -> ResultadoOta.Fallo(FalloOta.SIN_RED, resultado.detalle)
            FalloComprobacion.SERVIDOR -> ResultadoOta.Fallo(FalloOta.SERVIDOR, resultado.detalle)
            FalloComprobacion.FIRMA_INVALIDA -> ResultadoOta.Fallo(FalloOta.FIRMA, resultado.detalle)
            FalloComprobacion.MANIFIESTO_INVALIDO -> ResultadoOta.Fallo(FalloOta.MANIFIESTO, resultado.detalle)
        }
}
