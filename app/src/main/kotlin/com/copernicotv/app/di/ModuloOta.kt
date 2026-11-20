package com.copernicotv.app.di

import android.content.Context
import com.copernicotv.app.BuildConfig
import com.copernicotv.app.ota.AlmacenManifiestoOta
import com.copernicotv.app.ota.ClienteHttpOta
import com.copernicotv.app.ota.ClienteHttpOtaUrlConnection
import com.copernicotv.app.ota.ComprobadorActualizaciones
import com.copernicotv.app.ota.ConfiguracionOta
import com.copernicotv.app.ota.DescargadorApk
import com.copernicotv.app.ota.FuenteManifiestos
import com.copernicotv.app.ota.FuenteManifiestosGitHub
import com.copernicotv.app.ota.PerfilApp
import com.copernicotv.app.ota.VerificadorApk
import com.copernicotv.app.ota.VerificadorApkAndroid
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Named
import javax.inject.Singleton

/**
 * Grafo del sistema de actualizaciones.
 *
 * `ConfiguracionOta` se arma desde `BuildConfig`: el workflow de release
 * inyecta la clave pública y la huella del certificado. Sin ellas la app
 * compila pero el comprobador falla cerrado, que es el comportamiento
 * buscado en debug y en compilaciones no oficiales.
 */
@Module
@InstallIn(SingletonComponent::class)
object ModuloOta {
    @Provides
    @Singleton
    fun configuracion(perfil: PerfilApp): ConfiguracionOta =
        ConfiguracionOta(
            repositorio = BuildConfig.OTA_REPOSITORIO,
            paquete = perfil.paquete(),
            clavePublicaManifiesto = BuildConfig.OTA_MANIFIESTO_PUBKEY,
            huellaFirmaApk = BuildConfig.OTA_CERT_SHA256,
        )

    @Provides
    @Singleton
    fun cliente(): ClienteHttpOta = ClienteHttpOtaUrlConnection()

    @Provides
    @Singleton
    fun fuente(
        cliente: ClienteHttpOta,
        configuracion: ConfiguracionOta,
    ): FuenteManifiestos = FuenteManifiestosGitHub(cliente, configuracion)

    @Provides
    @Singleton
    fun verificador(
        @ApplicationContext contexto: Context,
    ): VerificadorApk = VerificadorApkAndroid(contexto)

    @Provides
    @Singleton
    @Named("directorioOta")
    fun directorio(
        @ApplicationContext contexto: Context,
    ): File = File(contexto.filesDir, "ota")

    @Provides
    @Singleton
    fun descargador(
        cliente: ClienteHttpOta,
        @Named("directorioOta") directorio: File,
    ): DescargadorApk = DescargadorApk(cliente, directorio)

    @Provides
    @Singleton
    fun comprobador(
        fuente: FuenteManifiestos,
        almacen: AlmacenManifiestoOta,
        configuracion: ConfiguracionOta,
        perfil: PerfilApp,
    ): ComprobadorActualizaciones =
        ComprobadorActualizaciones(
            fuente = fuente,
            almacen = almacen,
            configuracion = configuracion,
            versionInstalada = perfil.versionInstalada(),
            sdkInt = perfil.sdkInt(),
        )
}
