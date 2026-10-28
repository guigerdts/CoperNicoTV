package com.copernicotv.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.app.data.db.entities.CanalEntity
import com.copernicotv.app.data.db.entities.FuenteEntity
import com.copernicotv.core.identity.EstadoCanal
import com.copernicotv.core.modelo.ViolacionCanal
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * Rechazo del escritor de `Canal` por incumplir §6.3.2.
 *
 * `InvariantesCanal` define la regla; esta prueba demuestra que el camino de
 * escritura la convierte en rechazo efectivo y que ninguna fila inválida llega
 * a la base.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CanalEscritorTest {
    private lateinit var db: AppDatabase
    private lateinit var escritor: CanalEscritor
    private lateinit var fuenteId: Long

    private val ahora: Instant = Instant.parse("2026-01-01T00:00:00Z")
    private val canonica: String = "canon:v2:" + "a".repeat(64)

    @Before
    fun abrir() {
        val contexto = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room
                .inMemoryDatabaseBuilder(contexto, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        escritor = CanalEscritor(db)
        runBlocking {
            fuenteId =
                db.fuenteDao().insertar(
                    FuenteEntity(
                        nombre = "Fuente",
                        estado = "activa",
                        usarIntervaloGlobal = true,
                        creadoEn = ahora,
                        actualizadoEn = ahora,
                    ),
                )
        }
    }

    @After
    fun cerrar() {
        db.close()
    }

    @Test
    fun `acepta una fila conforme`() {
        runBlocking {
            val id = escritor.insertar(canal(), tvgIdUnicoEnFuente = false)

            assertTrue(id > 0)
            assertEquals(1, db.canalDao().contar())
        }
    }

    @Test
    fun `rechaza estado A derivable sin clave de unicidad`() {
        val rechazo =
            assertThrows(CanalRechazado::class.java) {
                runBlocking { escritor.insertar(canal(claveUnicidad = null), tvgIdUnicoEnFuente = false) }
            }

        assertTrue(ViolacionCanal.ESTADO_A_DERIVABLE_SIN_CLAVE_UNICIDAD in rechazo.violaciones)
        assertEquals("estado_a_derivable_sin_clave_unicidad", rechazo.codigo)
        runBlocking { assertEquals(0, db.canalDao().contar()) }
    }

    @Test
    fun `rechaza estado B con clave de unicidad`() {
        val rechazo =
            assertThrows(CanalRechazado::class.java) {
                runBlocking {
                    escritor.insertar(
                        canal(estado = EstadoCanal.B, claveDerivable = false),
                        tvgIdUnicoEnFuente = false,
                    )
                }
            }

        assertTrue(ViolacionCanal.CLAVE_UNICIDAD_EN_ESTADO_BC in rechazo.violaciones)
        runBlocking { assertEquals(0, db.canalDao().contar()) }
    }

    @Test
    fun `rechaza clave derivable falsa con clave de unicidad`() {
        val rechazo =
            assertThrows(CanalRechazado::class.java) {
                runBlocking {
                    escritor.insertar(
                        canal(claveDerivable = false),
                        tvgIdUnicoEnFuente = false,
                    )
                }
            }

        assertTrue(ViolacionCanal.CLAVE_UNICIDAD_CON_DERIVABLE_FALSO in rechazo.violaciones)
        runBlocking { assertEquals(0, db.canalDao().contar()) }
    }

    @Test
    fun `rechaza clave canonica sin nombre`() {
        val rechazo =
            assertThrows(CanalRechazado::class.java) {
                runBlocking { escritor.insertar(canal(nombre = null), tvgIdUnicoEnFuente = false) }
            }

        assertTrue(ViolacionCanal.CLAVE_CANONICA_SIN_NOMBRE in rechazo.violaciones)
        runBlocking { assertEquals(0, db.canalDao().contar()) }
    }

    @Test
    fun `rechaza url stream sin hmac`() {
        val rechazo =
            assertThrows(CanalRechazado::class.java) {
                runBlocking {
                    escritor.insertar(canal(hmac = byteArrayOf()), tvgIdUnicoEnFuente = false)
                }
            }

        assertTrue(ViolacionCanal.URL_STREAM_HMAC_AUSENTE in rechazo.violaciones)
        runBlocking { assertEquals(0, db.canalDao().contar()) }
    }

    private fun canal(
        nombre: String? = "Canal",
        estado: EstadoCanal = EstadoCanal.A,
        claveDerivable: Boolean = true,
        claveUnicidad: String? = canonica,
        hmac: ByteArray = byteArrayOf(0x02),
    ): CanalEntity =
        CanalEntity(
            fuenteId = fuenteId,
            nombre = nombre,
            urlStreamCifrada = byteArrayOf(0x01),
            urlStreamHmac = hmac,
            claveCanonica = canonica,
            claveUnicidad = claveUnicidad,
            claveDerivable = claveDerivable,
            estado = estado,
            creadoEn = ahora,
            actualizadoEn = ahora,
        )
}
