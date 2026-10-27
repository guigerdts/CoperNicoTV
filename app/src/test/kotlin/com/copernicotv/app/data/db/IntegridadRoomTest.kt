package com.copernicotv.app.data.db

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.copernicotv.app.data.db.entities.AparienciaEntity
import com.copernicotv.app.data.db.entities.CanalEntity
import com.copernicotv.app.data.db.entities.ColeccionCanalEntity
import com.copernicotv.app.data.db.entities.ColeccionEntity
import com.copernicotv.app.data.db.entities.FuenteEntity
import com.copernicotv.app.data.db.entities.GrupoEntity
import com.copernicotv.app.data.db.entities.HistorialEntity
import com.copernicotv.app.data.db.entities.ListaEntity
import com.copernicotv.app.data.db.entities.PerfilEntity
import com.copernicotv.app.data.db.entities.PerfilListaEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * Integridad del esquema v1 en una base Room real (§6.3.2).
 *
 * Cubre claves únicas, claves foráneas y acciones de borrado. Son las
 * invariantes que el esquema puede sostener por sí mismo; las invariantes de
 * identidad del escritor se validan en `:core` con `InvariantesCanal`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IntegridadRoomTest {
    private lateinit var db: AppDatabase

    @Before
    fun abrir() {
        val contexto = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room
                .inMemoryDatabaseBuilder(contexto, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }

    @After
    fun cerrar() {
        db.close()
    }

    @Test
    fun `canal exige una fuente existente`() {
        assertFalla {
            db.canalDao().insertar(canal(fuenteId = 999))
        }
    }

    @Test
    fun `clave de unicidad duplicada en la misma fuente se rechaza`() {
        val fuenteId = fuente()
        db.canalDao().insertar(canal(fuenteId, clave = "canon:v2:uno"))
        assertFalla {
            db.canalDao().insertar(canal(fuenteId, clave = "canon:v2:uno"))
        }
    }

    @Test
    fun `la misma clave de unicidad en fuentes distintas se permite`() {
        val primera = fuente("Fuente A")
        val segunda = fuente("Fuente B")
        db.canalDao().insertar(canal(primera, clave = "canon:v2:uno"))
        db.canalDao().insertar(canal(segunda, clave = "canon:v2:uno"))
        assertEquals(2, db.canalDao().contar())
    }

    @Test
    fun `claves de unicidad nulas no colisionan`() {
        val fuenteId = fuente()
        db.canalDao().insertar(canal(fuenteId, clave = null))
        db.canalDao().insertar(canal(fuenteId, clave = null))
        assertEquals(2, db.canalDao().contar())
    }

    @Test
    fun `historial admite una sola fila por canal`() {
        val fuenteId = fuente()
        val canalId = db.canalDao().insertar(canal(fuenteId, clave = "canon:v2:uno"))
        db.historialDao().insertar(HistorialEntity(canalId = canalId, fecha = AHORA))
        assertFalla {
            db.historialDao().insertar(HistorialEntity(canalId = canalId, fecha = AHORA.plusSeconds(60)))
        }
    }

    @Test
    fun `coleccion admite membresia duplicada y dos posiciones`() {
        val fuenteId = fuente()
        val canalId = db.canalDao().insertar(canal(fuenteId, clave = "canon:v2:uno"))
        val coleccionId =
            db.coleccionDao().insertar(
                ColeccionEntity(nombre = "Mixta", creadoEn = AHORA, actualizadoEn = AHORA),
            )
        db.coleccionCanalDao().insertar(ColeccionCanalEntity(coleccionId = coleccionId, canalId = canalId, posicion = 0))
        db.coleccionCanalDao().insertar(ColeccionCanalEntity(coleccionId = coleccionId, canalId = canalId, posicion = 0))
        db.coleccionCanalDao().insertar(ColeccionCanalEntity(coleccionId = coleccionId, canalId = canalId, posicion = 1))
        assertEquals(3, db.coleccionCanalDao().contar())
    }

    @Test
    fun `apariencia es unica por lista canal y clave de presentacion`() {
        val fuenteId = fuente()
        val listaId = lista(fuenteId)
        val canalId = db.canalDao().insertar(canal(fuenteId, clave = "canon:v2:uno"))
        db.aparienciaDao().insertar(apariencia(listaId, canalId, clave = "presentacion:v2:a"))
        assertFalla {
            db.aparienciaDao().insertar(apariencia(listaId, canalId, clave = "presentacion:v2:a"))
        }
        db.aparienciaDao().insertar(apariencia(listaId, canalId, clave = "presentacion:v2:b"))
        assertEquals(2, db.aparienciaDao().contar(listaId))
    }

    @Test
    fun `borrar la fuente arrastra listas canales y apariencias`() {
        val fuenteId = fuente()
        val listaId = lista(fuenteId)
        val canalId = db.canalDao().insertar(canal(fuenteId, clave = "canon:v2:uno"))
        db.aparienciaDao().insertar(apariencia(listaId, canalId, clave = "presentacion:v2:a"))

        db.fuenteDao().eliminar(fuenteId)

        assertEquals(0, db.fuenteDao().contar())
        assertEquals(0, db.listaDao().contar())
        assertEquals(0, db.canalDao().contar())
        assertEquals(0, db.aparienciaDao().porLista(listaId).size)
    }

    @Test
    fun `borrar el grupo deja la apariencia sin grupo`() {
        val fuenteId = fuente()
        val listaId = lista(fuenteId)
        val canalId = db.canalDao().insertar(canal(fuenteId, clave = "canon:v2:uno"))
        val grupoId = db.grupoDao().insertar(GrupoEntity(listaId = listaId, nombre = "Noticias"))
        db.aparienciaDao().insertar(apariencia(listaId, canalId, clave = "presentacion:v2:a", grupoId = grupoId))

        db.grupoDao().eliminar(grupoId)

        assertNull(db.aparienciaDao().porLista(listaId).single().grupoId)
    }

    @Test
    fun `borrar el perfil arrastra sus asociaciones de lista`() {
        val fuenteId = fuente()
        val listaId = lista(fuenteId)
        val perfilId =
            db.perfilDao().insertar(
                PerfilEntity(
                    nombre = "Ana",
                    nombreNormalizado = "ana",
                    creadoEn = AHORA,
                    actualizadoEn = AHORA,
                ),
            )
        db.perfilDao().asociarLista(PerfilListaEntity(perfilId = perfilId, listaId = listaId))
        assertEquals(1, db.perfilDao().contarAsociaciones())

        db.perfilDao().eliminar(perfilId)

        assertEquals(0, db.perfilDao().contarAsociaciones())
    }

    private fun assertFalla(bloque: suspend () -> Unit): SQLiteConstraintException =
        assertThrows(SQLiteConstraintException::class.java) { runBlocking { bloque() } }

    private suspend fun fuente(nombre: String = "Fuente"): Long =
        db.fuenteDao().insertar(
            FuenteEntity(
                nombre = nombre,
                estado = "activa",
                usarIntervaloGlobal = true,
                creadoEn = AHORA,
                actualizadoEn = AHORA,
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
                creadoEn = AHORA,
                actualizadoEn = AHORA,
            ),
        )

    private fun canal(
        fuenteId: Long,
        clave: String? = "canon:v2:uno",
    ): CanalEntity =
        CanalEntity(
            fuenteId = fuenteId,
            nombre = "Canal",
            urlStreamCifrada = byteArrayOf(0x01),
            urlStreamHmac = byteArrayOf(0x02),
            claveCanonica = clave,
            claveUnicidad = clave,
            claveDerivable = clave != null,
            creadoEn = AHORA,
            actualizadoEn = AHORA,
        )

    private fun apariencia(
        listaId: Long,
        canalId: Long,
        clave: String,
        grupoId: Long? = null,
    ): AparienciaEntity =
        AparienciaEntity(
            listaId = listaId,
            canalId = canalId,
            grupoId = grupoId,
            orden = 0,
            clavePresentacion = clave,
            repeticiones = 1,
            creadoEn = AHORA,
            actualizadoEn = AHORA,
        )

    private companion object {
        val AHORA: Instant = Instant.parse("2026-01-01T00:00:00Z")
    }
}
