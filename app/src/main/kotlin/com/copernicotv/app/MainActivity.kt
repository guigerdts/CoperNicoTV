package com.copernicotv.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
                // `ACTION_OPEN_DOCUMENT` es el único mecanismo que produce un
                // grant persistible; `GET_CONTENT` no lo hace y rompería la
                // reapertura de la Lista tras reiniciar el proceso.
                val selector =
                    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                        if (uri != null) viewModel.importarDesdeSaf(uri.toString())
                    }
                CatalogoScreen(
                    estado = estado,
                    onImportar = viewModel::importarFixture,
                    onAbrirDocumento = { selector.launch(arrayOf("*/*")) },
                )
            }
        }
    }
}
