package com.copernicotv.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.copernicotv.app.ui.CatalogoScreen
import com.copernicotv.app.ui.CatalogoViewModel
import com.copernicotv.app.ui.CoperNicoTheme
import dagger.hilt.android.AndroidEntryPoint

/** Única actividad del canario de E1-C. */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CoperNicoTheme {
                val viewModel: CatalogoViewModel = hiltViewModel()
                val estado by viewModel.estado.collectAsStateWithLifecycle()
                CatalogoScreen(estado = estado, onImportar = viewModel::importarFixture)
            }
        }
    }
}
