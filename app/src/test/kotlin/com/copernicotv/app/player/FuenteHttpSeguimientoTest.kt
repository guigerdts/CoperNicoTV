package com.copernicotv.app.player

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import com.copernicotv.core.player.TrazaRedireccion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * El envoltorio de la fuente HTTP (E7-T8, S4/S5) debe delegar el streaming
 * tal cual en Media3 y, solo ante un fallo, registrar en la trazadora la
 * evidencia de redirecciones: código inicial y final, saltos, esquema inicial
 * y final. Ante un fallo de red no debe repetir peticiones.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FuenteHttpSeguimientoTest {
    /** Fuente interna falsa que reproduce el comportamiento de Media3. */
    @Suppress("EmptyFunctionBlock") // los no-ops de la fuente falsa son intencionales
    private class FuenteInternaFalsa(
        private val uriFinal: Uri?,
        private val codigo: Int = -1,
        private val fallo: HttpDataSource.HttpDataSourceException? = null,
    ) : HttpDataSource {
        val oyentes = mutableListOf<TransferListener>()

        override fun addTransferListener(oyente: TransferListener) {
            oyentes.add(oyente)
        }

        override fun open(dataSpec: DataSpec): Long {
            fallo?.let { throw it }
            return 100L
        }

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int = -1

        override fun close() {}

        override fun getUri(): Uri? = uriFinal

        override fun getResponseCode(): Int = codigo

        override fun getResponseHeaders(): Map<String, List<String>> = emptyMap()

        override fun setRequestProperty(
            nombre: String,
            valor: String,
        ) {}

        override fun clearRequestProperty(nombre: String) {}

        override fun clearAllRequestProperties() {}
    }

    private fun dataSpec(): DataSpec = DataSpec(Uri.parse("https://origen.invalid/live/1.ts"))

    private fun falloHttp(
        codigo: Int,
        dataSpec: DataSpec,
    ): HttpDataSource.InvalidResponseCodeException =
        HttpDataSource.InvalidResponseCodeException(
            codigo,
            null,
            null as IOException?,
            mutableMapOf(),
            dataSpec,
            ByteArray(0),
        )

    private fun falloRed(dataSpec: DataSpec): HttpDataSource.HttpDataSourceException =
        HttpDataSource.HttpDataSourceException(
            dataSpec,
            androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            HttpDataSource.HttpDataSourceException.TYPE_OPEN,
        )

    private fun trazadora(): TrazadoraRed = TrazadoraRed(agenteUsuario = AGENTE_REPRODUCCION)

    @Test
    fun `un fallo http tras redirecciones deja traza con el salto real final`() {
        val trazadora = trazadora()
        val trazadas = mutableListOf<String>()
        val interna =
            FuenteInternaFalsa(
                uriFinal = Uri.parse("http://final.invalid/live/1.ts"),
                codigo = 404,
                fallo = falloHttp(302, dataSpec()),
            )
        val envoltorio =
            FuenteHttpSeguimiento(interna, trazadora) { urlOriginal ->
                trazadas.add(urlOriginal)
                TrazaRedireccion(302, 404, 2, "https", "http", false, false)
            }

        val error = runCatching { envoltorio.open(dataSpec()) }.exceptionOrNull()

        assertTrue(error is HttpDataSource.InvalidResponseCodeException)
        assertEquals(listOf("https://origen.invalid/live/1.ts"), trazadas)
        val traza = trazadora.traza
        assertEquals(302, traza?.codigoInicial)
        assertEquals(404, traza?.codigoFinal)
        assertEquals(2, traza?.redirecciones)
        assertEquals("https", traza?.esquemaInicial)
        assertEquals("http", traza?.esquemaFinal)
        assertEquals(AGENTE_REPRODUCCION, trazadora.agenteUsuario)
    }

    @Test
    fun `un fallo final sin saltos no repite el trazador y no hace peticiones extra`() {
        val trazadora = trazadora()
        var llamadasAlTrazador = 0
        val interna = FuenteInternaFalsa(uriFinal = null, fallo = falloHttp(403, dataSpec()))
        val envoltorio =
            FuenteHttpSeguimiento(interna, trazadora) {
                llamadasAlTrazador++
                TrazaRedireccion(302, 200, 1, "https", "https", false, false)
            }

        runCatching { envoltorio.open(dataSpec()) }

        assertEquals(0, llamadasAlTrazador)
        assertEquals(403, trazadora.traza?.codigoFinal)
        assertEquals(0, trazadora.traza?.redirecciones)
        assertEquals("https", trazadora.traza?.esquemaInicial)
        assertEquals("https", trazadora.traza?.esquemaFinal)
    }

    @Test
    fun `un fallo final al termino de saltos reales reconstruye la cadena`() {
        val trazadora = trazadora()
        var llamadasAlTrazador = 0
        val interna =
            FuenteInternaFalsa(
                uriFinal = Uri.parse("http://final.invalid/live/1.ts"),
                codigo = 404,
                fallo = falloHttp(404, dataSpec()),
            )
        val envoltorio =
            FuenteHttpSeguimiento(interna, trazadora) { urlOriginal ->
                llamadasAlTrazador++
                assertEquals("https://origen.invalid/live/1.ts", urlOriginal)
                TrazaRedireccion(302, 404, 2, "https", "http", false, false)
            }

        runCatching { envoltorio.open(dataSpec()) }

        assertEquals(1, llamadasAlTrazador)
        assertEquals(302, trazadora.traza?.codigoInicial)
        assertEquals(404, trazadora.traza?.codigoFinal)
        assertEquals(2, trazadora.traza?.redirecciones)
        assertEquals("http", trazadora.traza?.esquemaFinal)
    }

    @Test
    fun `la apertura exitosa no registra ninguna traza`() {
        val trazadora = trazadora()
        val envoltorio = FuenteHttpSeguimiento(FuenteInternaFalsa(uriFinal = null), trazadora) {
            TrazaRedireccion(302, 200, 1, "https", "https", false, false)
        }

        envoltorio.open(dataSpec())

        assertNull(trazadora.traza)
    }

    @Test
    fun `un fallo de red no repite el trazador y registra solo esquemas`() {
        val trazadora = trazadora()
        var llamadasAlTrazador = 0
        val interna =
            FuenteInternaFalsa(
                uriFinal = Uri.parse("http://salto.invalid/live/1.ts"),
                fallo = falloRed(dataSpec()),
            )
        val envoltorio =
            FuenteHttpSeguimiento(interna, trazadora) {
                llamadasAlTrazador++
                TrazaRedireccion(null, null, 0, null, null, false, false)
            }

        val error = runCatching { envoltorio.open(dataSpec()) }.exceptionOrNull()

        assertTrue(error is HttpDataSource.HttpDataSourceException)
        assertEquals(0, llamadasAlTrazador)
        assertNull(trazadora.traza?.codigoInicial)
        assertEquals(0, trazadora.traza?.redirecciones)
        assertEquals("https", trazadora.traza?.esquemaInicial)
        assertEquals("http", trazadora.traza?.esquemaFinal)
        assertFalse(trazadora.traza?.circular ?: true)
    }

    @Test
    fun `un fallo de red sin salto real no registra traza`() {
        val trazadora = trazadora()
        val envoltorio =
            FuenteHttpSeguimiento(
                FuenteInternaFalsa(uriFinal = null, fallo = falloRed(dataSpec())),
                trazadora,
            ) { TrazaRedireccion(null, null, 0, null, null, false, false) }

        runCatching { envoltorio.open(dataSpec()) }

        assertNull(trazadora.traza)
    }

    @Test
    fun `el oyente de transferencia se delega a la fuente interna`() {
        val interna = FuenteInternaFalsa(uriFinal = null)
        val envoltorio =
            FuenteHttpSeguimiento(interna, trazadora()) {
                TrazaRedireccion(null, null, 0, null, null, false, false)
            }
        @Suppress("EmptyFunctionBlock") // el oyente falso no necesita cuerpo
        val oyente =
            object : TransferListener {
                override fun onTransferInitializing(
                    source: DataSource,
                    dataSpec: DataSpec,
                    isNetwork: Boolean,
                ) {}

                override fun onTransferStart(
                    source: DataSource,
                    dataSpec: DataSpec,
                    isNetwork: Boolean,
                ) {}

                override fun onBytesTransferred(
                    source: DataSource,
                    dataSpec: DataSpec,
                    isNetwork: Boolean,
                    bytesTransferred: Int,
                ) {}

                override fun onTransferEnd(
                    source: DataSource,
                    dataSpec: DataSpec,
                    isNetwork: Boolean,
                ) {}
            }

        envoltorio.addTransferListener(oyente)

        assertEquals(listOf(oyente), interna.oyentes)
    }

    @Test
    fun `la fabrica entrega fuentes de seguimiento con el agente configurado`() {
        val trazadora = trazadora()
        val fabrica = FabricaFuenteSeguimiento(trazadora)

        val fuente = fabrica.createDataSource()

        assertTrue(fuente is FuenteHttpSeguimiento)
        assertEquals(AGENTE_REPRODUCCION, trazadora.agenteUsuario)
    }
}
