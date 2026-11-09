package com.copernicotv.app.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.copernicotv.app.ClavesEnMemoria
import com.copernicotv.app.data.IndiceHmacRepositorio
import com.copernicotv.core.hmac.FaseIndiceHmac
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * Semilla compartida de `EstadoIndiceHmac` (H1 de la revisión de E2-T6).
 *
 * Fija el contrato de la fila inicial y demuestra que el callback de Room y
 * [IndiceHmacRepositorio] producen exactamente la misma fila: el primero al
 * crear la base y el segundo cuando la fila falta antes de una transición.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SembrarEstadoIndiceHmacTest {
    private val contexto: Context = ApplicationProvider.getApplicationContext()
    private val ahora: Instant = Instant.parse("2026-01-01T00:00:00Z")

    @Test
    fun `la semilla crea la fila operativa canonica`() {
        val db = abrirConCallback()
        try {
            val fila = requireNotNull(runBlocking { db.estadoIndiceHmacDao().actual() })
            assertEquals(SembrarEstadoIndiceHmac.ID_SINGLETON, fila.id)
            assertEquals(FaseIndiceHmac.OPERATIVO.codigo, fila.estado)
            assertEquals("hmac:v1", fila.versionActiva)
            assertNull(fila.versionObjetivo)
            assertEquals(0L, fila.filasTotales)
            assertEquals(0L, fila.filasReconstruidas)
            assertEquals(0L, fila.filasPendientes)
            assertNull(fila.ultimoErrorTipo)
            assertEquals(fila.iniciadoEn, fila.actualizadoEn)
        } finally {
            db.close()
        }
    }

    @Test
    fun `la semilla no pisa un estado persistido`() {
        val db = abrirConCallback()
        try {
            val semilla = requireNotNull(runBlocking { db.estadoIndiceHmacDao().actual() })
            val persistido =
                semilla.copy(
                    estado = FaseIndiceHmac.RECUPERACION_PENDIENTE.codigo,
                    versionObjetivo = "hmac:v2",
                    actualizadoEn = ahora.plusSeconds(30),
                )
            runBlocking { db.estadoIndiceHmacDao().guardar(persistido) }

            SembrarEstadoIndiceHmac.insertarSiFalta(db.openHelper.writableDatabase, ahora.plusSeconds(60))

            val leido = requireNotNull(runBlocking { db.estadoIndiceHmacDao().actual() })
            assertEquals(FaseIndiceHmac.RECUPERACION_PENDIENTE.codigo, leido.estado)
            assertEquals("hmac:v2", leido.versionObjetivo)
            assertEquals(ahora.plusSeconds(30), leido.actualizadoEn)
        } finally {
            db.close()
        }
    }

    @Test
    fun `la fila del repositorio coincide con la semilla`() {
        val db =
            Room
                .inMemoryDatabaseBuilder(contexto, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        try {
            val indice = IndiceHmacRepositorio(db, ClavesEnMemoria())
            val estado = runBlocking { indice.estado(ahora) }
            val semilla = SembrarEstadoIndiceHmac.fila(ahora)

            assertEquals(FaseIndiceHmac.desde(semilla.estado), estado.fase)
            assertEquals(semilla.id, SembrarEstadoIndiceHmac.ID_SINGLETON)
            assertEquals(semilla.versionActiva, estado.versionActiva)
            assertEquals(semilla.versionObjetivo, estado.versionObjetivo)
            assertEquals(semilla.iniciadoEn, estado.iniciadoEn)
            assertEquals(semilla.actualizadoEn, estado.actualizadoEn)
            assertEquals(semilla.filasTotales, estado.filasTotales)
            assertEquals(semilla.filasReconstruidas, estado.filasReconstruidas)
            assertEquals(semilla.filasPendientes, estado.filasPendientes)
            assertEquals(semilla.ultimoErrorTipo, estado.ultimoErrorTipo)
        } finally {
            db.close()
        }
    }

    private fun abrirConCallback(): AppDatabase =
        Room
            .inMemoryDatabaseBuilder(contexto, AppDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(SembrarEstadoIndiceHmac.callback())
            .build()
}
