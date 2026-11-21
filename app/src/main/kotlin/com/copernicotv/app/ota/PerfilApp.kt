package com.copernicotv.app.ota

import android.content.Context
import android.os.Build
import com.copernicotv.app.BuildConfig
import com.copernicotv.core.ota.VersionInstalada
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Identidad de la instalación que se está ejecutando.
 *
 * La versión instalada sale de `BuildConfig`, que refleja el APK compilado; el
 * paquete sale del contexto para no confundirlo con un renombrado de la
 * aplicación en un sabor futuro.
 */
@Singleton
class PerfilApp
    @Inject
    constructor(
        @ApplicationContext private val contexto: Context,
    ) {
        fun versionInstalada(): VersionInstalada =
            VersionInstalada(
                versionCode = BuildConfig.VERSION_CODE.toLong(),
                versionName = BuildConfig.VERSION_NAME,
            )

        fun paquete(): String = contexto.packageName

        fun sdkInt(): Int = Build.VERSION.SDK_INT
    }
