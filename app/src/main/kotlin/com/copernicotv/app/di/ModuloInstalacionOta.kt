package com.copernicotv.app.di

import android.content.Context
import com.copernicotv.app.ota.AlmacenManifiestoOta
import com.copernicotv.app.ota.ComprobadorActualizaciones
import com.copernicotv.app.ota.CoordinadorOta
import com.copernicotv.app.ota.DescargadorApk
import com.copernicotv.app.ota.InstaladorApk
import com.copernicotv.app.ota.InstaladorApkAndroid
import com.copernicotv.app.ota.NotificadorOta
import com.copernicotv.app.ota.NotificadorOtaAndroid
import com.copernicotv.app.ota.VerificadorApk
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Grafo de instalación y avisos del OTA.
 *
 * Va en su propio módulo para que la infraestructura de consulta y descarga no
 * dependa de `PackageInstaller` ni del notificador. Las interfaces se enlazan
 * aquí para que el coordinador y la UI compartan una sola instancia.
 */
@Module
@InstallIn(SingletonComponent::class)
object ModuloInstalacionOta {
    @Provides
    @Singleton
    fun notificador(
        @ApplicationContext contexto: Context,
    ): NotificadorOta = NotificadorOtaAndroid(contexto)

    @Provides
    @Singleton
    fun instalador(
        @ApplicationContext contexto: Context,
        verificador: VerificadorApk,
    ): InstaladorApk = InstaladorApkAndroid(contexto, verificador)

    @Provides
    @Singleton
    fun coordinador(
        comprobador: ComprobadorActualizaciones,
        descargador: DescargadorApk,
        verificador: VerificadorApk,
        notificador: NotificadorOta,
        almacen: AlmacenManifiestoOta,
    ): CoordinadorOta = CoordinadorOta(comprobador, descargador, verificador, notificador, almacen)
}
