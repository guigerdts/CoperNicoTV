package com.copernicotv.app.data

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.app.data.db.entities.AparienciaEntity
import com.copernicotv.app.data.db.entities.CanalEntity
import com.copernicotv.app.data.db.entities.FuenteEntity
import com.copernicotv.app.data.db.entities.ListaEntity
import com.copernicotv.core.identity.ClavePresentacion
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * Migración de `clave_presentacion` desde la fórmula anterior (DC-05:630–638).
 *
 * Cubre las tres propiedades que el contrato exige: una fila única se migra,
 * un grupo en conflicto no se fusiona ni se borra y bloquea el upsert de la
 * tupla con `apariencia_migracion_pendiente`, y la ejecución repetida no
 * duplica registros ni reescribe claves.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigracionAparienciaTest {
    private lateinit var db: AppDatabase
    private lateinit var migracion: MigracionApariencia
    private lateinit var escritor: AparienciaEscritor

    private val ahora: Instant = Instant.parse("2026-01-01T00:00:00Z")
    private val claveVieja: String = "v1:0000000000000000000000000000000000000000000000000000000000000000"

    @Before
    fun abrir() {
        val contexto = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room
                .inMemoryDatabaseBuilder(contexto, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        migracion = MigracionApariencia(db)
        escritor = AparienciaEscritor(db)
    }

    @After
    fun cerrar() {
        db.close()
    }

    @Test
    fun `migra una fila antigua unica a presentacion v2`() {
        runBlocking {
            val fuenteId = fuente()
            val listaId = lista(fuenteId)
            val canalId = canal(fuenteId)
            val id = apariencia(listaId, canalId, claveVieja, groupTitle = "Grupo", tvgName = "Canal")

            val resultado = migracion.ejecutar(ahora)

            assertEquals(1, resultado.actualizadas)
            assertEquals(0, resultado.pendientesNuevas)
            val fila = db.aparienciaDao().porLista(listaId).single()
            assertEquals(id, fila.id)
            assertEquals(ClavePresentacion.clave("Grupo", "Canal"), fila.clavePresentacion)
            assertTrue(fila.clavePresentacion.startsWith(ClavePresentacion.PREFIJO))
            assertTrue(db.aparienciaDao().pendientes().isEmpty())
        }
    }

    @Test
    fun `un grupo en conflicto no se fusiona ni se borra y queda pendiente`() {
        runBlocking {
            val fuenteId = fuente()
            val listaId = lista(fuenteId)
            val canalId = canal(fuenteId)
            val v2 = ClavePresentacion.clave("Grupo", "Canal")
            val vieja = apariencia(listaId, canalId, claveVieja, groupTitle = "Grupo", tvgName = "Canal")
            val nueva = apariencia(listaId, canalId, v2, groupTitle = "Grupo", tvgName = "Canal")
            val cifrada = checkNotNull(db.canalDao().porId(canalId)).urlStreamCifrada

            val resultado = migracion.ejecutar(ahora)

            assertEquals(0, resultado.actualizadas)
            assertEquals(1, resultado.pendientesNuevas)
            val filas = db.aparienciaDao().porLista(listaId)
            assertEquals(2, filas.size)
            assertEquals(setOf(vieja, nueva), filas.map { it.id }.toSet())
            assertEquals(claveVieja, db.aparienciaDao().porTupla(listaId, canalId, claveVieja)?.clavePresentacion)
            assertEquals(v2, db.aparienciaDao().porTupla(listaId, canalId, v2)?.clavePresentacion)
            val pendiente = db.aparienciaDao().pendienteDe(vieja)
            assertNotNull(pendiente)
            assertEquals(v2, pendiente?.clavePresentacionV2)
            assertEquals(MigracionApariencia.MOTIVO_COLISION, pendiente?.motivo)
            assertEquals(null, pendiente?.resueltaEn)
            assertArrayEquals(cifrada, checkNotNull(db.canalDao().porId(canalId)).urlStreamCifrada)
        }
    }

    @Test
    fun `el upsert de la tupla pendiente se rechaza y conserva los datos originales`() {
        runBlocking {
            val fuenteId = fuente()
            val listaId = lista(fuenteId)
            val canalId = canal(fuenteId)
            val v2 = ClavePresentacion.clave("Grupo", "Canal")
            val vieja = apariencia(listaId, canalId, claveVieja, groupTitle = "Grupo", tvgName = "Canal")
            apariencia(listaId, canalId, v2, groupTitle = "Grupo", tvgName = "Canal")
            migracion.ejecutar(ahora)
            val antes = db.aparienciaDao().porLista(listaId)

            val resultado =
                escritor.upsert(
                    AparienciaEntity(
                        listaId = listaId,
                        canalId = canalId,
                        orden = 99,
                        groupTitle = "Grupo",
                        tvgName = "Canal",
                        clavePresentacion = v2,
                        repeticiones = 3,
                        creadoEn = ahora,
                        actualizadoEn = ahora,
                    ),
                    ahora,
                )

            assertTrue(resultado is ResultadoUpsertApariencia.Rechazada)
            assertEquals(
                AparienciaEscritor.CODIGO_PENDIENTE,
                (resultado as ResultadoUpsertApariencia.Rechazada).codigo,
            )
            val despues = db.aparienciaDao().porLista(listaId)
            assertEquals(antes.map { it.id to it.clavePresentacion }, despues.map { it.id to it.clavePresentacion })
            assertEquals(antes.map { it.repeticiones }, despues.map { it.repeticiones })
            assertEquals(claveVieja, db.aparienciaDao().porId(vieja)?.clavePresentacion)
            assertEquals(1, db.aparienciaDao().pendientes().size)
        }
    }

    @Test
    fun `la migracion es idempotente`() {
        runBlocking {
            val fuenteId = fuente()
            val listaId = lista(fuenteId)
            val canalId = canal(fuenteId)
            val v2 = ClavePresentacion.clave("Grupo", "Canal")
            val vieja = apariencia(listaId, canalId, claveVieja, groupTitle = "Grupo", tvgName = "Canal")
            apariencia(listaId, canalId, v2, groupTitle = "Grupo", tvgName = "Canal")
            migracion.ejecutar(ahora)
            val claves = db.aparienciaDao().porLista(listaId).map { it.id to it.clavePresentacion }
            val pendientes = db.aparienciaDao().pendientes()

            val segunda = migracion.ejecutar(ahora.plusSeconds(60))

            assertEquals(0, segunda.actualizadas)
            assertEquals(0, segunda.pendientesNuevas)
            assertEquals(claves, db.aparienciaDao().porLista(listaId).map { it.id to it.clavePresentacion })
            assertEquals(pendientes.size, db.aparienciaDao().pendientes().size)
            assertEquals(claveVieja, db.aparienciaDao().porId(vieja)?.clavePresentacion)
        }
    }

    @Test
    fun `un fallo de la transaccion no deja migraciones parciales`() {
        runBlocking {
            val fuenteId = fuente()
            val listaId = lista(fuenteId)
            val canalId = canal(fuenteId)
            apariencia(listaId, canalId, claveVieja, groupTitle = "Grupo", tvgName = "Canal")

            assertThrows(IllegalStateException::class.java) {
                runBlocking {
                    db.withTransaction {
                        migracion.ejecutar(ahora)
                        error("fallo simulado")
                    }
                }
            }

            assertEquals(claveVieja, db.aparienciaDao().porLista(listaId).single().clavePresentacion)
            assertTrue(db.aparienciaDao().pendientes().isEmpty())
        }
    }

    private suspend fun fuente(): Long =
        db.fuenteDao().insertar(
            FuenteEntity(
                nombre = "Fuente",
                estado = "activa",
                usarIntervaloGlobal = true,
                creadoEn = ahora,
                actualizadoEn = ahora,
            ),
        )

    private suspend fun lista(fuenteId: Long): Long =
        db.listaDao().insertar(
            ListaEntity(
                fuenteId = fuenteId,
                nombre = "Lista",
                ubicacionCifrada = byteArrayOf(0x01),
                ubicacionHmac = byteArrayOf(0x02),
                esquema = "content",
                orden = 0,
                estado = "activa",
                creadoEn = ahora,
                actualizadoEn = ahora,
            ),
        )

    private suspend fun canal(fuenteId: Long): Long =
        db.canalDao().insertar(
            CanalEntity(
                fuenteId = fuenteId,
                nombre = "Canal",
                urlStreamCifrada = byteArrayOf(0x01),
                urlStreamHmac = byteArrayOf(0x02),
                claveCanonica = "canon:v2:uno",
                claveUnicidad = "canon:v2:uno",
                claveDerivable = true,
                creadoEn = ahora,
                actualizadoEn = ahora,
            ),
        )

    private suspend fun apariencia(
        listaId: Long,
        canalId: Long,
        clave: String,
        groupTitle: String?,
        tvgName: String?,
    ): Long =
        db.aparienciaDao().insertar(
            AparienciaEntity(
                listaId = listaId,
                canalId = canalId,
                orden = 0,
                groupTitle = groupTitle,
                tvgName = tvgName,
                clavePresentacion = clave,
                repeticiones = 1,
                creadoEn = ahora,
                actualizadoEn = ahora,
            ),
        )
}
