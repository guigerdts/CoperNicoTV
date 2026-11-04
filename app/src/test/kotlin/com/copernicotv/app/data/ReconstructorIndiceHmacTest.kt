package com.copernicotv.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.copernicotv.app.ClavesEnMemoria
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.app.data.db.entities.CanalEntity
import com.copernicotv.app.data.db.entities.FuenteEntity
import com.copernicotv.app.data.db.entities.ListaEntity
import com.copernicotv.core.crypto.CifradoAes
import com.copernicotv.core.crypto.HmacUrl
import com.copernicotv.core.hmac.FaseIndiceHmac
import com.copernicotv.core.hmac.MotivoHmacPendiente
import com.copernicotv.core.hmac.MotivoTransicion
import com.copernicotv.core.hmac.ResultadoTransicion
import com.copernicotv.core.hmac.VersionOrigenHmac
import com.copernicotv.core.identity.CampoUrl
import com.copernicotv.core.identity.CanonicalizadorUrl
import com.copernicotv.core.identity.ResultadoCanonizacion
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.Base64

/**
 * Pasada de reconstrucción del índice HMAC (E2-T6).
 *
 * Cada caso usa un valor cifrado y un sobre reales del mismo código de
 * producción: la reconstrucción se compara contra el sobre que `HmacUrl`
 * calcula con la generación objetivo y la misma `cvM` de origen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReconstructorIndiceHmacTest {
    private lateinit var db: AppDatabase
    private lateinit var indice: IndiceHmacRepositorio
    private lateinit var reconstructor: ReconstructorIndiceHmac
    private val claves = ClavesEnMemoria()

    private val ahora: Instant = Instant.parse("2026-01-01T00:00:00Z")

    @Before
    fun abrir() {
        val contexto = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room
                .inMemoryDatabaseBuilder(contexto, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        indice = IndiceHmacRepositorio(db)
        reconstructor = ReconstructorIndiceHmac(db, indice, claves)
    }

    @After
    fun cerrar() {
        db.close()
    }

    @Test
    fun `reconstruye Canal y Lista con la generacion objetivo conservando cvM`() =
        runBlocking {
            val fuenteId = insertarFuente()
            val listaId = insertarLista(fuenteId, URL_LISTA)
            val canalId = insertarCanal(fuenteId, URL_CANAL)
            enReconstruccion(2)

            val resultado = reconstructor.reconstruir(ahora)

            assertTrue(resultado is ResultadoReconstruccion.Ok)
            val estado = (resultado as ResultadoReconstruccion.Ok).estado
            assertEquals(FaseIndiceHmac.OPERATIVO, estado.fase)
            assertEquals("hmac:v2", estado.versionActiva)
            assertEquals(2L, estado.filasTotales)
            assertEquals(2L, estado.filasReconstruidas)
            assertEquals(0L, estado.filasPendientes)
            assertArrayEquals(sobreDe(2, CampoUrl.CANAL_URL_STREAM, URL_CANAL), hmacCanal(canalId))
            assertArrayEquals(sobreDe(2, CampoUrl.LISTA_UBICACION, URL_LISTA), hmacLista(listaId))
            assertEquals(0, indice.pendientes().size)
        }

    @Test
    fun `un sobre legacy queda pendiente con version canonica desconocida`() =
        runBlocking {
            val fuenteId = insertarFuente()
            val canalId = insertarCanal(fuenteId, URL_CANAL, sobre = sobreLegacy())
            enReconstruccion(2)

            val resultado = reconstructor.reconstruir(ahora)

            val estado = (resultado as ResultadoReconstruccion.Ok).estado
            assertEquals(FaseIndiceHmac.BLOQUEADO_PARCIAL, estado.fase)
            val pendiente = indice.pendientes().single()
            assertEquals(MotivoHmacPendiente.VERSION_CANONICA_DESCONOCIDA, pendiente.motivo)
            assertEquals(VersionOrigenHmac.Desconocida, pendiente.versionOrigen)
            assertArrayEquals(sobreLegacy(), hmacCanal(canalId))
        }

    @Test
    fun `un cvM no soportado queda pendiente`() =
        runBlocking {
            val fuenteId = insertarFuente()
            val sobre = sobreDe(1, CampoUrl.CANAL_URL_STREAM, URL_CANAL, cv = 9)
            insertarCanal(fuenteId, URL_CANAL, sobre = sobre)
            enReconstruccion(2)

            val resultado = reconstructor.reconstruir(ahora)

            val estado = (resultado as ResultadoReconstruccion.Ok).estado
            assertEquals(FaseIndiceHmac.BLOQUEADO_PARCIAL, estado.fase)
            val pendiente = indice.pendientes().single()
            assertEquals(MotivoHmacPendiente.VERSION_CANONICA_NO_SOPORTADA, pendiente.motivo)
            assertEquals(VersionOrigenHmac.Cv(9), pendiente.versionOrigen)
        }

    @Test
    fun `un sobre malformado queda pendiente con hmac formato invalido`() =
        runBlocking {
            val fuenteId = insertarFuente()
            val canalId = insertarCanal(fuenteId, URL_CANAL, sobre = "no-es-un-sobre".toByteArray())
            enReconstruccion(2)

            val resultado = reconstructor.reconstruir(ahora)

            assertEquals(
                FaseIndiceHmac.BLOQUEADO_PARCIAL,
                (resultado as ResultadoReconstruccion.Ok).estado.fase,
            )
            assertEquals(
                MotivoHmacPendiente.HMAC_FORMATO_INVALIDO,
                indice.pendientes().single().motivo,
            )
            assertEquals("no-es-un-sobre", hmacCanal(canalId).toString(Charsets.UTF_8))
        }

    @Test
    fun `un ciphertext corrupto queda pendiente sin borrar el Canal`() =
        runBlocking {
            val fuenteId = insertarFuente()
            val canalId = insertarCanal(fuenteId, URL_CANAL, cifrado = ByteArray(40) { 0x01 })
            enReconstruccion(2)

            val resultado = reconstructor.reconstruir(ahora)

            assertEquals(
                FaseIndiceHmac.BLOQUEADO_PARCIAL,
                (resultado as ResultadoReconstruccion.Ok).estado.fase,
            )
            assertEquals(
                MotivoHmacPendiente.CIPHERTEXT_CORRUPTO,
                indice.pendientes().single().motivo,
            )
            assertTrue(db.canalDao().porId(canalId) != null)
        }

    @Test
    fun `un logo sin valor original queda pendiente`() =
        runBlocking {
            val fuenteId = insertarFuente()
            val canalId = insertarCanal(fuenteId, URL_CANAL)
            db.reconstruccionHmacDao().actualizarLogoHmac(
                canalId,
                sobreDe(1, CampoUrl.CANAL_LOGO_URL, URL_LOGO),
            )
            enReconstruccion(2)

            val resultado = reconstructor.reconstruir(ahora)

            assertEquals(
                FaseIndiceHmac.BLOQUEADO_PARCIAL,
                (resultado as ResultadoReconstruccion.Ok).estado.fase,
            )
            val pendiente = indice.pendientes().single()
            assertEquals(MotivoHmacPendiente.VALOR_ORIGINAL_AUSENTE, pendiente.motivo)
            assertEquals("logoUrlHmac", pendiente.columna)
        }

    @Test
    fun `la pasada se rechaza fuera de recuperacion en curso`() =
        runBlocking {
            val fuenteId = insertarFuente()
            insertarCanal(fuenteId, URL_CANAL)

            val resultado = reconstructor.reconstruir(ahora)

            assertEquals(
                ResultadoReconstruccion.Rechazada(MotivoTransicion.TRANSICION_NO_PERMITIDA),
                resultado,
            )
            assertEquals(FaseIndiceHmac.OPERATIVO, indice.estado(ahora).fase)
        }

    @Test
    fun `una base sin filas cierra la reconstruccion en operativo`() =
        runBlocking {
            enReconstruccion(2)

            val resultado = reconstructor.reconstruir(ahora)

            val estado = (resultado as ResultadoReconstruccion.Ok).estado
            assertEquals(FaseIndiceHmac.OPERATIVO, estado.fase)
            assertEquals(0L, estado.filasTotales)
            assertEquals(0L, estado.filasPendientes)
        }

    @Test
    fun `los lotes recorren todas las filas`() =
        runBlocking {
            val fuenteId = insertarFuente()
            repeat(5) { posicion ->
                insertarCanal(fuenteId, "$URL_CANAL?canal=$posicion")
            }
            enReconstruccion(2)

            val resultado = reconstructor.reconstruir(ahora, tamanoLote = 2)

            val estado = (resultado as ResultadoReconstruccion.Ok).estado
            assertEquals(FaseIndiceHmac.OPERATIVO, estado.fase)
            assertEquals(5L, estado.filasTotales)
            assertEquals(5L, estado.filasReconstruidas)
            assertEquals(0, indice.pendientes().size)
        }

    @Test
    fun `reanudar tras resolver pendientes no reescribe lo ya reconstruido`() =
        runBlocking {
            val fuenteId = insertarFuente()
            val bueno = insertarCanal(fuenteId, URL_CANAL)
            val roto = insertarCanal(fuenteId, "$URL_CANAL?roto=1", cifrado = ByteArray(40) { 0x01 })
            val legacy = insertarCanal(fuenteId, "$URL_CANAL?legacy=1", sobre = sobreLegacy())
            enReconstruccion(2)

            val primera = reconstructor.reconstruir(ahora)

            val estadoPrimera = (primera as ResultadoReconstruccion.Ok).estado
            assertEquals(FaseIndiceHmac.BLOQUEADO_PARCIAL, estadoPrimera.fase)
            assertEquals(3L, estadoPrimera.filasTotales)
            assertEquals(1L, estadoPrimera.filasReconstruidas)
            assertEquals(2L, estadoPrimera.filasPendientes)
            val hmacBueno = hmacCanal(bueno)

            repararCifrado(roto, cifrar("Canal", "url_stream_cifrada", "$URL_CANAL?roto=1"))
            borrarCanal(legacy)
            indice.eliminarPendientes()
            assertTrue(indice.resolverPendientes(2, 1, 0, ahora) is ResultadoTransicion.Ok)

            val segunda = reconstructor.reconstruir(ahora)

            val estadoSegunda = (segunda as ResultadoReconstruccion.Ok).estado
            assertEquals(FaseIndiceHmac.OPERATIVO, estadoSegunda.fase)
            assertEquals("hmac:v2", estadoSegunda.versionActiva)
            assertEquals(2L, estadoSegunda.filasReconstruidas)
            assertArrayEquals(hmacBueno, hmacCanal(bueno))
            assertArrayEquals(
                sobreDe(2, CampoUrl.CANAL_URL_STREAM, "$URL_CANAL?roto=1"),
                hmacCanal(roto),
            )
        }

    private suspend fun enReconstruccion(generacion: Int) {
        indice.detectarPerdidaClave(ahora)
        indice.iniciarReconstruccion(generacion, ahora)
    }

    private suspend fun insertarFuente(): Long =
        db.fuenteDao().insertar(
            FuenteEntity(
                nombre = "Fuente de prueba",
                estado = "activa",
                usarIntervaloGlobal = true,
                creadoEn = ahora,
                actualizadoEn = ahora,
            ),
        )

    private suspend fun insertarLista(
        fuenteId: Long,
        url: String,
    ): Long =
        db.listaDao().insertar(
            ListaEntity(
                fuenteId = fuenteId,
                nombre = "Lista de prueba",
                ubicacionCifrada = cifrar("Lista", "ubicacion_cifrada", url),
                ubicacionHmac = sobreDe(1, CampoUrl.LISTA_UBICACION, url),
                esquema = "m3u",
                orden = 0,
                estado = "activa",
                creadoEn = ahora,
                actualizadoEn = ahora,
            ),
        )

    private suspend fun insertarCanal(
        fuenteId: Long,
        url: String,
        sobre: ByteArray = sobreDe(1, CampoUrl.CANAL_URL_STREAM, url),
        cifrado: ByteArray = cifrar("Canal", "url_stream_cifrada", url),
    ): Long =
        db.canalDao().insertar(
            CanalEntity(
                fuenteId = fuenteId,
                nombre = "Canal de prueba",
                urlStreamCifrada = cifrado,
                urlStreamHmac = sobre,
                claveDerivable = true,
                creadoEn = ahora,
                actualizadoEn = ahora,
            ),
        )

    /**
     * Simula la reparación externa de un valor cifrado: la pasada nunca escribe
     * esta columna, así que la prueba la corrige con SQL directo.
     */
    private fun repararCifrado(
        canalId: Long,
        cifrado: ByteArray,
    ) {
        db.openHelper.writableDatabase
            .compileStatement("UPDATE Canal SET urlStreamCifrada = ? WHERE id = ?")
            .use { sentencia ->
                sentencia.bindBlob(1, cifrado)
                sentencia.bindLong(2, canalId)
                sentencia.executeUpdateDelete()
            }
    }

    private fun borrarCanal(canalId: Long) {
        db.openHelper.writableDatabase
            .compileStatement("DELETE FROM Canal WHERE id = ?")
            .use { sentencia ->
                sentencia.bindLong(1, canalId)
                sentencia.executeUpdateDelete()
            }
    }

    private fun sobreDe(
        generacion: Int,
        campo: CampoUrl,
        url: String,
        cv: Int = 1,
    ): ByteArray {
        val canonica = CanonicalizadorUrl.canonizar(url, campo) as ResultadoCanonizacion.Ok
        return HmacUrl
            .calcularSobre(
                clave = claves.claveHmac,
                generacion = generacion,
                campo = campo,
                clase = canonica.clase,
                urlCanonica = canonica.urlCanonica,
                versionCanonica = cv,
            ).toByteArray(Charsets.UTF_8)
    }

    private fun sobreLegacy(): ByteArray =
        "hmac:v1:${Base64.getEncoder().encodeToString(ByteArray(32) { 0x0A })}".toByteArray(Charsets.UTF_8)

    private fun cifrar(
        tabla: String,
        columna: String,
        url: String,
    ): ByteArray =
        CifradoAes.cifrar(
            claves.claveAes,
            CifradoAes.aad(tabla, columna),
            url.toByteArray(Charsets.UTF_8),
        )

    private suspend fun hmacCanal(id: Long): ByteArray = requireNotNull(db.canalDao().porId(id)?.urlStreamHmac)

    private suspend fun hmacLista(id: Long): ByteArray =
        db.reconstruccionHmacDao().loteListas(0, Long.MAX_VALUE, 10).first { it.id == id }.ubicacionHmac

    private companion object {
        const val URL_CANAL = "https://example.com/canal.m3u8"
        const val URL_LISTA = "https://example.com/lista.m3u"
        const val URL_LOGO = "https://example.com/logo.png"
    }
}
