package com.copernicotv.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.copernicotv.app.ClavesEnMemoria
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.app.data.db.entities.AparienciaEntity
import com.copernicotv.app.data.db.entities.GrupoEntity
import com.copernicotv.app.data.db.entities.ListaEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * Ámbito de las entidades del catálogo (E2-T3, ADR-0002:18).
 *
 * El mismo Canal en varias Listas de la misma Fuente se persiste una sola vez y
 * la pertenencia se modela con `Apariencia`; el `Grupo` en cambio pertenece a
 * una Lista, así que el mismo nombre en Listas distintas son Grupos distintos.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CatalogoAmbitoTest {
    private lateinit var db: AppDatabase
    private lateinit var repositorio: CatalogoRepositorio
    private val claves = ClavesEnMemoria()
    private val contexto: Context = ApplicationProvider.getApplicationContext()
    private val ahora: Instant = Instant.parse("2026-01-01T00:00:00Z")

    @Before
    fun abrir() {
        db =
            Room
                .inMemoryDatabaseBuilder(contexto, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        repositorio = CatalogoRepositorio(db, claves, contexto)
    }

    @After
    fun cerrar() {
        db.close()
    }

    @Test
    fun `el mismo canal en dos listas de la misma fuente no se duplica`() =
        runBlocking {
            val resumen = repositorio.importarFixture()
            val fuente = db.fuenteDao().todas().single()
            val listaUno = requireNotNull(resumen.listaId)
            val listaDos = db.listaDao().insertar(lista(fuente.id, "Segunda lista"))
            val canal = db.aparienciaDao().porLista(listaUno).first().canalId

            db.aparienciaDao().insertar(
                AparienciaEntity(
                    listaId = listaDos,
                    canalId = canal,
                    orden = 0,
                    clavePresentacion = "otra-presentacion",
                    repeticiones = 1,
                    creadoEn = ahora,
                    actualizadoEn = ahora,
                ),
            )

            assertEquals(3, db.canalDao().contarPorFuente(fuente.id))
            assertEquals(3, db.aparienciaDao().contar(listaUno))
            assertEquals(1, db.aparienciaDao().contar(listaDos))
        }

    @Test
    fun `el grupo tiene ambito de lista`() =
        runBlocking {
            val resumen = repositorio.importarFixture()
            val fuente = db.fuenteDao().todas().single()
            val listaUno = requireNotNull(resumen.listaId)
            val listaDos = db.listaDao().insertar(lista(fuente.id, "Segunda lista"))

            // El fixture trae "Noticias" y "Deportes" en la primera Lista.
            assertEquals(2, db.grupoDao().contar(listaUno))
            db.grupoDao().insertar(GrupoEntity(listaId = listaDos, nombre = "Noticias"))

            assertEquals(1, db.grupoDao().contar(listaDos))

            val repetido =
                runCatching {
                    db.grupoDao().insertar(GrupoEntity(listaId = listaDos, nombre = "Noticias"))
                }
            assertTrue("el nombre debe ser unico dentro de su Lista", repetido.isFailure)
            assertEquals(1, db.grupoDao().contar(listaDos))
        }

    private fun lista(
        fuenteId: Long,
        nombre: String,
    ): ListaEntity =
        ListaEntity(
            fuenteId = fuenteId,
            nombre = nombre,
            ubicacionCifrada = byteArrayOf(0x01, 0x02),
            ubicacionHmac = byteArrayOf(0x03, 0x04),
            esquema = "https",
            orden = 1,
            estado = "activa",
            creadoEn = ahora,
            actualizadoEn = ahora,
        )
}
