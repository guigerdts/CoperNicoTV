package com.copernicotv.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/** Aplicación raíz. El grafo Hilt se crea aquí y vive todo el proceso. */
@HiltAndroidApp
class CoperNicoApplication : Application()
