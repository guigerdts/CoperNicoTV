package com.copernicotv.app.ui

import com.copernicotv.app.ota.AlmacenManifiestoOta
import com.copernicotv.app.ota.ClienteHttpOta
import com.copernicotv.app.ota.ConfiguracionOta
import com.copernicotv.app.ota.DescargadorApk
import com.copernicotv.app.ota.EstadoInstalacionOta
import com.copernicotv.app.ota.FalloOta
import com.copernicotv.app.ota.InstaladorApk
import com.copernicotv.app.ota.ManifiestoGuardado
import com.copernicotv.app.ota.ManifiestoVerificado
import com.copernicotv.app.ota.PasadaOta
import com.copernicotv.app.ota.ResultadoDescargaHttp
import com.copernicotv.app.ota.ResultadoHttp
import com.copernicotv.app.ota.ResultadoInstalacion
import com.copernicotv.app.ota.ResultadoOta
import com.copernicotv.core.ota.CanalActualizacion
import com.copernicotv.core.ota.Integridad
import com.copernicotv.core.ota.ManifiestoActualizacion
import com.copernicotv.core.ota.VersionInstalada
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant

/**
 * Pantalla Ajustes > Actualizaciones (S9).
 *
 * Prueba el ViewModel con dobles de sus puertos: la pasada del OTA, el almacén
 * y el instalador. El descargador es el real porque solo se consulta para
 * recuperar un APK ya verificado, y el cliente falso nunca se usa en estos
 * casos. Todo corre en JVM: no hay `Context`, `WorkManager` ni recursos de
 * Android en la ruta probada.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AjustesViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun preparar() {
        Dispatchers.setMain(dispatcher)
        // El desenlace de instalación es de proceso: se limpia para que un caso
        // no arrastre el aviso del anterior.
        EstadoInstalacionOta.olvidar()
    }

    @After
    fun limpiar() {
        Dispatchers.resetMain()
        EstadoInstalacionOta.olvidar()
    }

    @Test
    fun `una version descargada queda lista para instalar`() =
        runTest(dispatcher) {
            val manifiesto = manifiesto(versionCode = 2)
            val archivo = File(directorioTemporal(), "ota-2.apk")
            val viewModel = viewModel(pasada = PasadaFalsa(ResultadoOta.Descargada(manifiesto, archivo)))

            viewModel.buscar()
            advanceUntilIdle()

            val estado = viewModel.estado.value
            assertEquals(manifiesto, estado.disponible)
            assertEquals(archivo, estado.archivoListo)
            assertTrue(estado.puedeInstalar)
            assertFalse(estado.comprobando)
            assertNull(estado.progreso)
        }

    @Test
    fun `sin novedad avisa que ya esta al dia y descarta la oferta`() =
        runTest(dispatcher) {
            val viewModel = viewModel(pasada = PasadaFalsa(ResultadoOta.SinNovedad(ultimaComprobacionMs = 5_000L)))

            viewModel.buscar()
            advanceUntilIdle()

            val estado = viewModel.estado.value
            assertEquals(AvisoAjustes.AlDia, estado.aviso)
            assertNull(estado.disponible)
            assertFalse(estado.puedeInstalar)
            assertEquals(5_000L, estado.ultimaComprobacionMs)
        }

    @Test
    fun `un fallo de la pasada se muestra tipado`() =
        runTest(dispatcher) {
            val viewModel = viewModel(pasada = PasadaFalsa(ResultadoOta.Fallo(FalloOta.SIN_RED)))

            viewModel.buscar()
            advanceUntilIdle()

            assertEquals(AvisoAjustes.Fallo(FalloOta.SIN_RED), viewModel.estado.value.aviso)
            assertFalse(viewModel.estado.value.comprobando)
        }

    @Test
    fun `cada clase de fallo llega a la UI sin colapsarse`() =
        runTest(dispatcher) {
            val vistos = mutableSetOf<AvisoAjustes>()

            FalloOta.values().forEach { tipo ->
                val viewModel = viewModel(pasada = PasadaFalsa(ResultadoOta.Fallo(tipo)))

                viewModel.buscar()
                advanceUntilIdle()

                val aviso = requireNotNull(viewModel.estado.value.aviso) { "sin aviso para $tipo" }
                assertEquals("clase para $tipo", AvisoAjustes.Fallo(tipo), aviso)
                vistos += aviso
            }

            assertEquals(FalloOta.values().size, vistos.size)
        }

    @Test
    fun `el progreso de la descarga llega al estado mientras descarga`() =
        runTest(dispatcher) {
            val pasada = PasadaConPausa(ResultadoOta.SinNovedad(1L))
            val viewModel = viewModel(pasada = pasada)

            viewModel.buscar()
            advanceUntilIdle()

            assertEquals(42, viewModel.estado.value.progreso)
            assertTrue(viewModel.estado.value.comprobando)

            pasada.continuar.complete(Unit)
            advanceUntilIdle()

            assertNull(viewModel.estado.value.progreso)
            assertFalse(viewModel.estado.value.comprobando)
        }

    @Test
    fun `una version incompatible no se ofrece para instalar`() =
        runTest(dispatcher) {
            val viewModel =
                viewModel(pasada = PasadaFalsa(ResultadoOta.Incompatible(manifiesto(versionCode = 9, minSdk = 34))))

            viewModel.buscar()
            advanceUntilIdle()

            val estado = viewModel.estado.value
            assertEquals(AvisoAjustes.Incompatible("0.9.0"), estado.aviso)
            assertNull(estado.disponible)
            assertFalse(estado.puedeInstalar)
        }

    @Test
    fun `sin autorizacion no se inicia la instalacion`() =
        runTest(dispatcher) {
            val manifiesto = manifiesto(versionCode = 2)
            val archivo = File(directorioTemporal(), "ota-2.apk")
            val instalador = InstaladorFalso(permiso = false)
            val viewModel =
                viewModel(
                    pasada = PasadaFalsa(ResultadoOta.Descargada(manifiesto, archivo)),
                    instalador = instalador,
                )

            viewModel.buscar()
            advanceUntilIdle()
            viewModel.instalar()
            advanceUntilIdle()

            assertEquals(0, instalador.llamadas)
            assertTrue(viewModel.estado.value.aviso is AvisoAjustes.SinPermiso)
            assertFalse(viewModel.estado.value.permisoInstalacion)
        }

    @Test
    fun `con autorizacion se pide la instalacion del archivo descargado`() =
        runTest(dispatcher) {
            val manifiesto = manifiesto(versionCode = 2)
            val archivo = File(directorioTemporal(), "ota-2.apk")
            val instalador = InstaladorFalso(permiso = true)
            val viewModel =
                viewModel(
                    pasada = PasadaFalsa(ResultadoOta.Descargada(manifiesto, archivo)),
                    instalador = instalador,
                )

            viewModel.buscar()
            advanceUntilIdle()
            viewModel.instalar()
            advanceUntilIdle()

            assertEquals(1, instalador.llamadas)
            assertEquals(archivo, instalador.archivo)
            assertEquals(manifiesto, instalador.manifiesto)
            assertEquals(AvisoAjustes.ConfirmacionPendiente, viewModel.estado.value.aviso)
        }

    @Test
    fun `cambiar de canal lo persiste y descarta la oferta`() =
        runTest(dispatcher) {
            val almacen = AlmacenFalso()
            val manifiesto = manifiesto(versionCode = 2)
            val viewModel =
                viewModel(
                    pasada = PasadaFalsa(ResultadoOta.Descargada(manifiesto, File(directorioTemporal(), "ota-2.apk"))),
                    almacen = almacen,
                )

            viewModel.buscar()
            advanceUntilIdle()
            viewModel.cambiarCanal(CanalActualizacion.BETA)
            advanceUntilIdle()

            assertEquals(CanalActualizacion.BETA, almacen.canalFijado)
            assertEquals(CanalActualizacion.BETA, viewModel.estado.value.canal)
            assertNull(viewModel.estado.value.disponible)
        }

    private fun viewModel(
        pasada: PasadaOta,
        almacen: AlmacenFalso = AlmacenFalso(),
        instalador: InstaladorFalso = InstaladorFalso(permiso = true),
        version: VersionInstalada = VersionInstalada(1, "0.1.0"),
    ): AjustesViewModel =
        AjustesViewModel(
            pasada = pasada,
            almacen = almacen,
            instalador = instalador,
            descargador = DescargadorApk(ClienteNulo(), directorioTemporal()),
            version = version,
            configuracion = CONFIGURACION,
        )

    private class PasadaFalsa(
        private val resultado: ResultadoOta,
    ) : PasadaOta {
        override suspend fun ejecutar(alProgreso: (Int) -> Unit): ResultadoOta = resultado
    }

    private class PasadaConPausa(
        private val resultado: ResultadoOta,
    ) : PasadaOta {
        val continuar = CompletableDeferred<Unit>()

        override suspend fun ejecutar(alProgreso: (Int) -> Unit): ResultadoOta {
            alProgreso(42)
            continuar.await()
            return resultado
        }
    }

    private class ClienteNulo : ClienteHttpOta {
        override suspend fun obtener(
            url: String,
            maxBytes: Int,
        ): ResultadoHttp = error("el cliente no se usa en la pantalla")

        override suspend fun descargar(
            url: String,
            destino: File,
            desdeBytes: Long,
            alProgresar: (escritos: Long, totales: Long?) -> Unit,
        ): ResultadoDescargaHttp = error("el cliente no se usa en la pantalla")
    }

    private class AlmacenFalso : AlmacenManifiestoOta {
        private val canal = MutableStateFlow(CanalActualizacion.ESTABLE)
        var canalFijado: CanalActualizacion? = null
            private set

        override fun canal(): Flow<CanalActualizacion> = canal

        override suspend fun canalActual(): CanalActualizacion = canal.value

        override suspend fun fijarCanal(canal: CanalActualizacion) {
            canalFijado = canal
            this.canal.value = canal
        }

        override fun ultimaComprobacionMs(): Flow<Long?> = MutableStateFlow(null)

        override suspend fun marcarComprobacion(instanteMs: Long) = Unit

        override suspend fun guardarComprobacion(
            verificado: ManifiestoVerificado,
            instanteMs: Long,
        ) = Unit

        override suspend fun cargarManifiesto(): ManifiestoGuardado? = null

        override suspend fun olvidarManifiesto() = Unit

        override fun versionOmitida(): Flow<Long?> = MutableStateFlow(null)

        override suspend fun omitirVersion(versionCode: Long) = Unit

        override suspend fun olvidarVersionOmitida() = Unit
    }

    private class InstaladorFalso(
        private val permiso: Boolean,
    ) : InstaladorApk {
        var llamadas = 0
            private set
        var archivo: File? = null
            private set
        var manifiesto: ManifiestoActualizacion? = null
            private set

        override fun permisoConcedido(): Boolean = permiso

        override suspend fun instalar(
            archivo: File,
            manifiesto: ManifiestoActualizacion,
        ): ResultadoInstalacion {
            llamadas++
            this.archivo = archivo
            this.manifiesto = manifiesto
            return ResultadoInstalacion.ConfirmacionPendiente(1)
        }
    }

    private fun manifiesto(
        versionCode: Long,
        minSdk: Int = 26,
    ): ManifiestoActualizacion {
        val contenido = "apk".toByteArray()
        return ManifiestoActualizacion(
            esquema = ManifiestoActualizacion.ESQUEMA_ACTUAL,
            paquete = PAQUETE,
            versionCode = versionCode,
            versionName = "0.$versionCode.0",
            canal = CanalActualizacion.ESTABLE,
            minSdk = minSdk,
            publicadoEn = Instant.parse("2026-11-20T00:00:00Z"),
            tamanoBytes = contenido.size.toLong(),
            sha256 = Integridad.sha256Hex(contenido),
            urlDescarga = "https://github.com/guigerdts/CoperNicoTV/releases/download/v0.$versionCode.0/app-release.apk",
            urlRelease = "https://github.com/guigerdts/CoperNicoTV/releases/tag/v0.$versionCode.0",
            notas = "Notas",
            obligatoria = false,
            huellaFirmaApk = HUELLA,
        )
    }

    private fun directorioTemporal(): File {
        val directorio = Files.createTempDirectory("ota-ajustes").toFile()
        directorio.deleteOnExit()
        return directorio
    }

    private companion object {
        const val PAQUETE = "com.copernicotv.app"
        val HUELLA = "c".repeat(64)
        val CONFIGURACION = ConfiguracionOta("guigerdts/CoperNicoTV", PAQUETE, "clave", HUELLA)
    }
}
