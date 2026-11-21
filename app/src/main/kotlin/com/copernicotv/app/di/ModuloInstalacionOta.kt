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
import com.copernicotv.app.ota.PasadaOta
import com.copernicotv.app.ota.PerfilApp
import com.copernicotv.app.ota.ResultadoOta
import com.copernicotv.app.ota.VerificadorApk
import com.copernicotv.core.ota.VersionInstalada
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

    /**
     * La UI de Ajustes consume una pasada, no el coordinador completo.
     *
     * Se declara con `object` explícito y no con lambda para no depender de la
     * conversión SAM de un método `suspend`, que el compilador resuelve de
     * forma distinta según la versión de Kotlin.
     */
    @Provides
    @Singleton
    fun pasada(coordinador: CoordinadorOta): PasadaOta =
        object : PasadaOta {
            override suspend fun ejecutar(alProgreso: (Int) -> Unit): ResultadoOta = coordinador.ejecutar(alProgreso)
        }

    /** Versión instalada como dato puro: la UI la muestra sin depender de Android. */
    @Provides
    @Singleton
    fun versionInstalada(perfil: PerfilApp): VersionInstalada = perfil.versionInstalada()
}
