package com.copernicotv.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.copernicotv.app.ClavesEnMemoria
import com.copernicotv.app.crypto.ClavesApp
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.app.data.db.entities.CanalEntity
import com.copernicotv.core.crypto.CifradoAes
import com.copernicotv.core.crypto.HmacUrl
import com.copernicotv.core.hmac.FaseIndiceHmac
import com.copernicotv.core.identity.CampoUrl
import com.copernicotv.core.identity.CanonicalizadorUrl
import com.copernicotv.core.identity.ResultadoCanonizacion
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import javax.crypto.SecretKey

/**
 * Guardia de arranque del índice HMAC (DC-04 §7, E2-T6; Q2).
 *
 * Ninguna rama regenera la clave en silencio: la inicialización legítima es
 * explícita y la pérdida real se declara con la transición del estado
 * persistido, preservando fila y sobres.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GuardiaArranqueIndiceHmacTest {
    private lateinit var db: AppDatabase
    private val claves = ClavesEnMemoria()
    private lateinit var indice: IndiceHmacRepositorio
    private lateinit var guardia: GuardiaArranqueIndiceHmac

    private val ahora: Instant = Instant.parse("2026-01-01T00:00:00Z")

    @Before
    fun abrir() {
        val contexto = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room
                .inMemoryDatabaseBuilder(contexto, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        indice = IndiceHmacRepositorio(db, claves)
        guardia = GuardiaArranqueIndiceHmac(indice)
    }

    @After
    fun cerrar() {
        db.close()
    }

    @Test
    fun `una instalacion virgen se inicializa legitimamente con v1`() =
        runBlocking {
            val resultado = guardia.evaluar(ahora)

            assertEquals(GuardiaArranqueIndiceHmac.ResultadoArranque.Inicializado, resultado)
            val estado = indice.estado(ahora)
            assertEquals(FaseIndiceHmac.OPERATIVO, estado.fase)
            assertEquals("hmac:v1", estado.versionActiva)
            assertTrue(claves.contieneClaveHmac(1))
        }

    @Test
    fun `la segunda evaluacion sobre una instalacion inicializada es operativo`() =
        runBlocking {
            guardia.evaluar(ahora)

            assertEquals(GuardiaArranqueIndiceHmac.ResultadoArranque.Operativo, guardia.evaluar(ahora))
        }

    @Test
    fun `la perdida real de la clave declara recuperacion pendiente sin tocar datos`() =
        runBlocking {
            guardia.evaluar(ahora)
            val canalId = sembrarCanal()
            val oculta = ClavesOcultas(claves).apply { generacionOculta = 1 }
            val indiceOculta = IndiceHmacRepositorio(db, oculta)
            val guardiaOculta = GuardiaArranqueIndiceHmac(indiceOculta)

            val resultado = guardiaOculta.evaluar(ahora)

            assertEquals(GuardiaArranqueIndiceHmac.ResultadoArranque.PerdidaDetectada, resultado)
            val estado = indiceOculta.estado(ahora)
            assertEquals(FaseIndiceHmac.RECUPERACION_PENDIENTE, estado.fase)
            assertFalse(oculta.contieneClaveHmac(1))
            // Datos preservados: fila y sobres intactos.
            val canal = requireNotNull(db.canalDao().porId(canalId))
            assertTrue(canal.urlStreamHmac.contentEquals(sobreV1()))
        }

    @Test
    fun `la recuperacion ya abierta no se toca en el arranque`() =
        runBlocking {
            guardia.evaluar(ahora)
            sembrarCanal()
            val oculta = ClavesOcultas(claves).apply { generacionOculta = 1 }
            val indiceOculta = IndiceHmacRepositorio(db, oculta)
            GuardiaArranqueIndiceHmac(indiceOculta).evaluar(ahora)
            indiceOculta.iniciarReconstruccion(2, ahora)
            assertEquals(FaseIndiceHmac.RECUPERACION_EN_CURSO, indiceOculta.estado(ahora).fase)

            val resultado = GuardiaArranqueIndiceHmac(IndiceHmacRepositorio(db, claves)).evaluar(ahora)

            assertEquals(GuardiaArranqueIndiceHmac.ResultadoArranque.RecuperacionEnMarcha, resultado)
        }

    @Test
    fun `sobres sin fila de estado declaran la perdida`() =
        runBlocking {
            // Sobres sellados con su fila de estado borrada (anomalía externa).
            val fuenteId =
                db.fuenteDao().insertar(
                    com.copernicotv.app.data.db.entities.FuenteEntity(
                        nombre = "Fuente",
                        estado = "activa",
                        usarIntervaloGlobal = true,
                        creadoEn = ahora,
                        actualizadoEn = ahora,
                    ),
                )
            db.canalDao().insertar(
                CanalEntity(
                    fuenteId = fuenteId,
                    nombre = "Canal",
                    urlStreamCifrada =
                        CifradoAes.cifrar(
                            claves.claveAes,
                            CifradoAes.aad("Canal", "url_stream_cifrada"),
                            URL_CANAL.toByteArray(),
                        ),
                    urlStreamHmac = sobreV1(),
                    claveDerivable = true,
                    creadoEn = ahora,
                    actualizadoEn = ahora,
                ),
            )
            db.openHelper.writableDatabase
                .compileStatement("DELETE FROM EstadoIndiceHmac")
                .use { it.executeUpdateDelete() }

            val resultado = guardia.evaluar(ahora)

            assertEquals(GuardiaArranqueIndiceHmac.ResultadoArranque.PerdidaDetectada, resultado)
            assertEquals(FaseIndiceHmac.RECUPERACION_PENDIENTE, indice.estado(ahora).fase)
        }

    /** Siembra un canal con sobre v1 legítimo para los escenarios de pérdida. */
    private suspend fun sembrarCanal(): Long {
        val fuenteId =
            db
                .fuenteDao()
                .insertar(
                    com.copernicotv.app.data.db.entities.FuenteEntity(
                        nombre = "Fuente",
                        estado = "activa",
                        usarIntervaloGlobal = true,
                        creadoEn = ahora,
                        actualizadoEn = ahora,
                    ),
                )
        return db.canalDao().insertar(
            CanalEntity(
                fuenteId = fuenteId,
                nombre = "Canal",
                urlStreamCifrada =
                    CifradoAes.cifrar(
                        claves.claveAes,
                        CifradoAes.aad("Canal", "url_stream_cifrada"),
                        URL_CANAL.toByteArray(),
                    ),
                urlStreamHmac = sobreV1(),
                claveDerivable = true,
                creadoEn = ahora,
                actualizadoEn = ahora,
            ),
        )
    }

    private fun sobreV1(): ByteArray {
        val canonica = CanonicalizadorUrl.canonizar(URL_CANAL, CampoUrl.CANAL_URL_STREAM) as ResultadoCanonizacion.Ok
        return HmacUrl
            .calcularSobre(
                clave = claves.crearClaveHmac(1),
                generacion = 1,
                campo = CampoUrl.CANAL_URL_STREAM,
                clase = canonica.clase,
                urlCanonica = canonica.urlCanonica,
            ).toByteArray(Charsets.UTF_8)
    }

    private companion object {
        const val URL_CANAL = "https://example.com/canal.m3u8"
    }
}

/**
 * Claves de prueba que ocultan una generación concreta, para simular la
 * desaparición de la clave en la bóveda sin alterar el resto del material.
 */
private class ClavesOcultas(
    private val base: ClavesEnMemoria,
) : ClavesApp by base {
    /** Generación oculta; `null` no oculta ninguna. */
    var generacionOculta: Int? = null

    override fun contieneClaveHmac(generacion: Int): Boolean = generacion != generacionOculta && base.contieneClaveHmac(generacion)

    override fun claveHmac(generacion: Int): SecretKey? = if (generacion == generacionOculta) null else base.claveHmac(generacion)
}
