package com.copernicotv.app.ota

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.copernicotv.app.MainActivity
import com.copernicotv.app.R
import com.copernicotv.core.ota.ManifiestoActualizacion
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Acción con la que se abre Ajustes > Actualizaciones desde una notificación. */
object ExtrasOta {
    const val ABRIR_ACTUALIZACIONES = "com.copernicotv.app.ota.ABRIR_ACTUALIZACIONES"
}

/** Identificadores de notificación del OTA, separados del resto de la app. */
object IdsNotificacionOta {
    const val DISPONIBLE = 4101
    const val PROGRESO = 4102
    const val LISTO = 4103
}

/**
 * Avisos del sistema sobre la actualización de la app.
 *
 * Es una interfaz para que el coordinador se pruebe sin Android. La
 * implementación usa un canal propio (`actualizaciones_app`) para no compartir
 * importancia ni preferencias del usuario con la actualización de listas IPTV.
 */
interface NotificadorOta {
    fun versionDisponible(manifiesto: ManifiestoActualizacion)

    fun progreso(
        manifiesto: ManifiestoActualizacion,
        escritos: Long,
        totales: Long,
    )

    fun listaParaInstalar(manifiesto: ManifiestoActualizacion)

    /** Cancela solo la notificación de progreso. */
    fun olvidarProgreso()

    /** Cancela todos los avisos del OTA. */
    fun limpiarTodo()
}

/**
 * Implementación con `NotificationCompat`.
 *
 * Sin `POST_NOTIFICATIONS` (API 33+) los avisos se omiten en silencio: la app
 * sigue funcionando y la pantalla de Ajustes muestra el estado. Hay un solo
 * canal, `actualizaciones_app`, con importancia por defecto; el progreso no
 * genera ruido porque se publica silencioso y con `setOnlyAlertOnce`.
 */
@Singleton
class NotificadorOtaAndroid
    @Inject
    constructor(
        @ApplicationContext private val contexto: Context,
    ) : NotificadorOta {
        override fun versionDisponible(manifiesto: ManifiestoActualizacion) {
            publicar(
                id = IdsNotificacionOta.DISPONIBLE,
                notificacion =
                    base(R.drawable.ic_ota_aviso)
                        .setContentTitle(contexto.getString(R.string.ota_notif_disponible_titulo))
                        .setContentText(
                            contexto.getString(R.string.ota_notif_disponible_texto, manifiesto.versionName),
                        )
                        .setContentIntent(abrirAjustes())
                        .setAutoCancel(true)
                        .build(),
            )
        }

        override fun progreso(
            manifiesto: ManifiestoActualizacion,
            escritos: Long,
            totales: Long,
        ) {
            val determinada = totales > 0L
            val porcentaje = if (determinada) ((escritos * 100L) / totales).toInt().coerceIn(0, 100) else 0
            publicar(
                id = IdsNotificacionOta.PROGRESO,
                notificacion =
                    base(R.drawable.ic_ota_descarga)
                        .setContentTitle(contexto.getString(R.string.ota_notif_progreso_titulo))
                        .setContentText(
                            if (determinada) {
                                contexto.getString(R.string.ota_notif_progreso_texto, manifiesto.versionName, porcentaje)
                            } else {
                                manifiesto.versionName
                            },
                        )
                        .setProgress(100, porcentaje, !determinada)
                        .setOngoing(true)
                        .setSilent(true)
                        .setOnlyAlertOnce(true)
                        .build(),
            )
        }

        override fun listaParaInstalar(manifiesto: ManifiestoActualizacion) {
            NotificationManagerCompat.from(contexto).cancel(IdsNotificacionOta.PROGRESO)
            NotificationManagerCompat.from(contexto).cancel(IdsNotificacionOta.DISPONIBLE)
            publicar(
                id = IdsNotificacionOta.LISTO,
                notificacion =
                    base(R.drawable.ic_ota_listo)
                        .setContentTitle(contexto.getString(R.string.ota_notif_listo_titulo))
                        .setContentText(contexto.getString(R.string.ota_notif_listo_texto, manifiesto.versionName))
                        .setContentIntent(abrirAjustes())
                        .setAutoCancel(true)
                        .build(),
            )
        }

        override fun olvidarProgreso() {
            NotificationManagerCompat.from(contexto).cancel(IdsNotificacionOta.PROGRESO)
        }

        override fun limpiarTodo() {
            val gestor = NotificationManagerCompat.from(contexto)
            gestor.cancel(IdsNotificacionOta.DISPONIBLE)
            gestor.cancel(IdsNotificacionOta.PROGRESO)
            gestor.cancel(IdsNotificacionOta.LISTO)
        }

        private fun base(icono: Int): NotificationCompat.Builder =
            NotificationCompat.Builder(contexto, CANAL).apply {
                setSmallIcon(icono)
                setPriority(NotificationCompat.PRIORITY_DEFAULT)
                setCategory(NotificationCompat.CATEGORY_STATUS)
                setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            }

        private fun abrirAjustes(): PendingIntent {
            val intencion =
                Intent(contexto, MainActivity::class.java).apply {
                    putExtra(ExtrasOta.ABRIR_ACTUALIZACIONES, true)
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
            return PendingIntent.getActivity(contexto, 0, intencion, BANDERAS_PENDIENTE)
        }

        private fun publicar(
            id: Int,
            notificacion: android.app.Notification,
        ) {
            if (!permisoNotificaciones()) return
            registrarCanal()
            NotificationManagerCompat.from(contexto).notify(id, notificacion)
        }

        private fun permisoNotificaciones(): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(contexto, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED

        private fun registrarCanal() {
            val gestor = contexto.getSystemService(NotificationManager::class.java) ?: return
            if (gestor.getNotificationChannel(CANAL) != null) return
            val canal =
                NotificationChannel(
                    CANAL,
                    contexto.getString(R.string.ota_canal_nombre),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = contexto.getString(R.string.ota_canal_descripcion)
                    setShowBadge(true)
                }
            gestor.createNotificationChannel(canal)
        }

        private companion object {
            const val CANAL = "actualizaciones_app"
            const val BANDERAS_PENDIENTE = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        }
    }
