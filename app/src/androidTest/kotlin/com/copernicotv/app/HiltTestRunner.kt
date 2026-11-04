package com.copernicotv.app

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import dagger.hilt.android.testing.HiltTestApplication

/**
 * Runner de pruebas instrumentadas.
 *
 * Sustituye la Application real por `HiltTestApplication` para que las pruebas
 * puedan inyectar la base de datos y limpiar el estado entre ejecuciones.
 */
class HiltTestRunner : AndroidJUnitRunner() {
    override fun newApplication(
        classLoader: ClassLoader?,
        nombre: String?,
        contexto: Context?,
    ): Application = super.newApplication(classLoader, HiltTestApplication::class.java.name, contexto)
}
