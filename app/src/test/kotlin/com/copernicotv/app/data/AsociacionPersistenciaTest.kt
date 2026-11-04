package com.copernicotv.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.app.data.db.entities.AsociacionEntity
import com.copernicotv.app.data.db.entities.CanalEntity
import com.copernicotv.app.data.db.entities.FuenteEntity
import com.copernicotv.app.data.db.entities.FuenteGuiaEntity
import com.copernicotv.core.epg.EstadoAsociacion
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * S27b: confirmar, editar y revocar una Asociación sobrevive a reabrir la base.
 *
 * La prueba usa un archivo Room real, no una base en memoria: cerrar y volver a
 * abrir es la condición que S27b exige y una base `inMemoryDatabaseBuilder`
 * desaparecería al cerrar.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AsociacionPersistenciaTest {
    private val contexto: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun preparar() {
        contexto.deleteDatabase(NOMBRE)
    }

    @After
    fun limpiar() {
        contexto.deleteDatabase(NOMBRE)
    }

    @Test
    fun `confirmar editar y revocar persisten tras reabrir la base`() =
        runBlocking {
            var db = abrir()
            val semilla = sembrar(db)
            val repositorio = AsociacionRepositorio(db)
            repositorio.confirmar(semilla.asociacionId)
            db.close()

            db = abrir()
            var vista = AsociacionRepositorio(db).porId(semilla.asociacionId)
            assertEquals(EstadoAsociacion.CONFIRMADA, vista?.estado)
            assertEquals(semilla.canalAId, vista?.canalId)

            AsociacionRepositorio(db).editar(semilla.asociacionId, semilla.canalBId)
            db.close()

            db = abrir()
            vista = AsociacionRepositorio(db).porId(semilla.asociacionId)
            assertEquals(EstadoAsociacion.CONFIRMADA, vista?.estado)
            assertEquals(semilla.canalBId, vista?.canalId)

            AsociacionRepositorio(db).revocar(semilla.asociacionId)
            db.close()

            db = abrir()
            vista = AsociacionRepositorio(db).porId(semilla.asociacionId)
            assertEquals(EstadoAsociacion.REVOCADA, vista?.estado)
            db.close()
        }

    private fun abrir(): AppDatabase =
        Room
            .databaseBuilder(contexto, AppDatabase::class.java, NOMBRE)
            .allowMainThreadQueries()
            .build()

    private suspend fun sembrar(db: AppDatabase): Semilla {
        val fuenteId =
            db.fuenteDao().insertar(
                FuenteEntity(
                    nombre = "Fuente",
                    estado = "activa",
                    usarIntervaloGlobal = true,
                    creadoEn = AHORA,
                    actualizadoEn = AHORA,
                ),
            )
        val canalA =
            db.canalDao().insertar(
                CanalEntity(
                    fuenteId = fuenteId,
                    nombre = "Canal A",
                    urlStreamCifrada = byteArrayOf(1),
                    urlStreamHmac = byteArrayOf(2),
                    tvgId = "a",
                    claveDerivable = false,
                    creadoEn = AHORA,
                    actualizadoEn = AHORA,
                ),
            )
        val canalB =
            db.canalDao().insertar(
                CanalEntity(
                    fuenteId = fuenteId,
                    nombre = "Canal B",
                    urlStreamCifrada = byteArrayOf(3),
                    urlStreamHmac = byteArrayOf(4),
                    tvgId = "b",
                    claveDerivable = false,
                    creadoEn = AHORA,
                    actualizadoEn = AHORA,
                ),
            )
        val fuenteGuiaId =
            db.fuenteGuiaDao().insertar(
                FuenteGuiaEntity(
                    nombre = "Guía",
                    usarRetencionGlobal = true,
                    creadoEn = AHORA,
                    actualizadoEn = AHORA,
                ),
            )
        val asociacionId =
            db.asociacionDao().insertar(
                AsociacionEntity(
                    canalId = canalA,
                    fuenteGuiaId = fuenteGuiaId,
                    channelIdTexto = "a",
                    estado = EstadoAsociacion.PROPUESTA.valor,
                    origen = "heuristica",
                    huellaVersion = 2,
                    senalesFuertes = "{}",
                    ultimaObservacion = AHORA,
                    actualizadaEn = AHORA,
                ),
            )
        return Semilla(canalA, canalB, asociacionId)
    }

    private data class Semilla(
        val canalAId: Long,
        val canalBId: Long,
        val asociacionId: Long,
    )

    private companion object {
        const val NOMBRE = "epg-persistencia-test.db"
        val AHORA: Instant = Instant.parse("2026-01-01T12:00:00Z")
    }
}
