package com.copernicotv.app.data

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.app.data.db.entities.AparienciaEntity
import com.copernicotv.app.data.db.entities.CanalEntity
import com.copernicotv.app.data.db.entities.FuenteEntity
import com.copernicotv.app.data.db.entities.GrupoEntity
import com.copernicotv.app.data.db.entities.ListaEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * Persistencia de Favoritos, Historial y Ocultaciones sobre Room v1 real.
 *
 * Cubre los doce casos de E3-U1: idempotencia, conservación de la fecha de
 * última reproducción, orden y recorte a diez, atomicidad de la transacción,
 * separación Canal/Grupo, Grupos homónimos y ausencia de cambios de esquema.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EstadoUsuarioPersistenciaTest {
    private lateinit var db: AppDatabase
    private lateinit var favoritos: FavoritosRepositorio
    private lateinit var historial: HistorialRepositorio
    private lateinit var ocultaciones: OcultacionesRepositorio

    @Before
    fun abrir() {
        val contexto = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room
                .inMemoryDatabaseBuilder(contexto, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        favoritos = FavoritosRepositorio(db.favoritoDao())
        historial = HistorialRepositorio(db)
        ocultaciones = OcultacionesRepositorio(db.ocultacionCanalDao(), db.ocultacionGrupoDao())
    }

    @After
    fun cerrar() {
        db.close()
    }

    @Test
    fun `favorito se crea y se consulta`() {
        runBlocking {
            val fuenteId = fuente()
            val canalId = db.canalDao().insertar(canal(fuenteId))

            assertFalse(favoritos.esFavorito(canalId))
            assertTrue(favoritos.agregar(canalId, T1))
            assertTrue(favoritos.esFavorito(canalId))
            assertEquals(listOf(canalId), favoritos.canalesFavoritos())
            assertEquals(1, db.favoritoDao().contar())
            assertEquals(T1, db.favoritoDao().porCanal(canalId)?.creadoEn)
        }
    }

    @Test
    fun `alta repetida de favorito es idempotente`() {
        runBlocking {
            val fuenteId = fuente()
            val canalId = db.canalDao().insertar(canal(fuenteId))

            assertTrue(favoritos.agregar(canalId, T1))
            assertFalse(favoritos.agregar(canalId, T2))

            assertEquals(1, db.favoritoDao().contar())
            assertEquals(T1, db.favoritoDao().porCanal(canalId)?.creadoEn)
        }
    }

    @Test
    fun `quitar favorito no borra el canal ni su apariencia`() {
        runBlocking {
            val fuenteId = fuente()
            val listaId = lista(fuenteId)
            val canalId = db.canalDao().insertar(canal(fuenteId))
            db.aparienciaDao().insertar(apariencia(listaId, canalId))

            favoritos.agregar(canalId, T1)

            assertTrue(favoritos.quitar(canalId))
            assertFalse(favoritos.esFavorito(canalId))
            assertFalse(favoritos.quitar(canalId))
            assertNotNull(db.canalDao().porId(canalId))
            assertEquals(1, db.canalDao().contar())
            assertEquals(1, db.aparienciaDao().contar(listaId))
        }
    }

    @Test
    fun `reproducir el mismo canal conserva una fila con la fecha mas reciente`() {
        runBlocking {
            val fuenteId = fuente()
            val canalId = db.canalDao().insertar(canal(fuenteId))

            historial.registrar(canalId, T1)
            historial.registrar(canalId, T2)
            historial.registrar(canalId, T0)

            assertEquals(1, db.historialDao().contar())
            assertEquals(T2, db.historialDao().porCanal(canalId)?.fecha)
        }
    }

    @Test
    fun `historial ordena por fecha descendente y desempata por canal descendente`() {
        runBlocking {
            val fuenteId = fuente()
            val canal1 = db.canalDao().insertar(canal(fuenteId, clave = "canon:v2:1"))
            val canal2 = db.canalDao().insertar(canal(fuenteId, clave = "canon:v2:2"))
            val canal3 = db.canalDao().insertar(canal(fuenteId, clave = "canon:v2:3"))

            historial.registrar(canal1, T1)
            historial.registrar(canal2, T2)
            historial.registrar(canal3, T2)

            assertEquals(listOf(canal3, canal2, canal1), historial.ultimos().map { it.canalId })

            historial.registrar(canal1, T3)

            assertEquals(listOf(canal1, canal3, canal2), historial.ultimos().map { it.canalId })
        }
    }

    @Test
    fun `historial conserva solo los diez canales mas recientes`() {
        runBlocking {
            val fuenteId = fuente()
            val ids =
                (1..12).map { indice ->
                    val canalId = db.canalDao().insertar(canal(fuenteId, clave = "canon:v2:$indice"))
                    historial.registrar(canalId, T1.plusSeconds(indice.toLong()))
                    canalId
                }

            assertEquals(10, db.historialDao().contar())
            assertEquals(ids.drop(2).reversed(), historial.ultimos().map { it.canalId })
            assertNull(db.historialDao().porCanal(ids[0]))
            assertNull(db.historialDao().porCanal(ids[1]))
        }
    }

    @Test
    fun `si falla el recorte se revierte la reproduccion`() {
        runBlocking {
            val fuenteId = fuente()
            val ids =
                (1..10).map { indice ->
                    val canalId = db.canalDao().insertar(canal(fuenteId, clave = "canon:v2:$indice"))
                    historial.registrar(canalId, T1.plusSeconds(indice.toLong()))
                    canalId
                }

            db.openHelper.writableDatabase.execSQL(
                """
                CREATE TRIGGER fallo_recorte BEFORE DELETE ON Historial
                WHEN OLD.canalId = ${ids.first()}
                BEGIN SELECT RAISE(ABORT, 'fallo de recorte'); END
                """.trimIndent(),
            )
            val nuevo = db.canalDao().insertar(canal(fuenteId, clave = "canon:v2:11"))

            assertThrows(SQLiteException::class.java) {
                runBlocking { historial.registrar(nuevo, T1.plusSeconds(11)) }
            }

            assertEquals(10, db.historialDao().contar())
            assertNull(db.historialDao().porCanal(nuevo))
            assertEquals(T1.plusSeconds(10), db.historialDao().porCanal(ids.last())?.fecha)
        }
    }

    @Test
    fun `ocultar y mostrar un canal no altera apariencias ni borra el canal`() {
        runBlocking {
            val fuenteId = fuente()
            val listaId = lista(fuenteId)
            val canalId = db.canalDao().insertar(canal(fuenteId))
            db.aparienciaDao().insertar(apariencia(listaId, canalId))
            val aparienciasAntes = db.aparienciaDao().porLista(listaId)

            assertTrue(ocultaciones.ocultarCanal(canalId, T1))
            assertTrue(ocultaciones.canalOculto(canalId))
            assertNotNull(db.canalDao().porId(canalId))
            assertEquals(1, db.canalDao().contar())
            assertEquals(aparienciasAntes, db.aparienciaDao().porLista(listaId))

            assertTrue(ocultaciones.mostrarCanal(canalId))
            assertFalse(ocultaciones.canalOculto(canalId))
            assertFalse(ocultaciones.mostrarCanal(canalId))
            assertEquals(aparienciasAntes, db.aparienciaDao().porLista(listaId))
        }
    }

    @Test
    fun `ocultar y mostrar un grupo no altera apariencias ni canales`() {
        runBlocking {
            val fuenteId = fuente()
            val listaId = lista(fuenteId)
            val canalId = db.canalDao().insertar(canal(fuenteId))
            val grupoId = grupo(listaId, "Noticias")
            db.aparienciaDao().insertar(apariencia(listaId, canalId, grupoId = grupoId))
            val aparienciasAntes = db.aparienciaDao().porLista(listaId)

            assertTrue(ocultaciones.ocultarGrupo(grupoId, T1))
            assertTrue(ocultaciones.grupoOculto(grupoId))
            assertNotNull(db.canalDao().porId(canalId))
            assertEquals(aparienciasAntes, db.aparienciaDao().porLista(listaId))
            assertEquals(grupoId, db.aparienciaDao().porLista(listaId).single().grupoId)

            assertTrue(ocultaciones.mostrarGrupo(grupoId))
            assertFalse(ocultaciones.grupoOculto(grupoId))
            assertEquals(aparienciasAntes, db.aparienciaDao().porLista(listaId))
        }
    }

    @Test
    fun `grupos homonimos de listas distintas se ocultan por separado`() {
        runBlocking {
            val fuenteId = fuente()
            val lista1 = lista(fuenteId, nombre = "Lista 1", orden = 0)
            val lista2 = lista(fuenteId, nombre = "Lista 2", orden = 1)
            val grupo1 = grupo(lista1, "Noticias")
            val grupo2 = grupo(lista2, "Noticias")
            val canal1 = db.canalDao().insertar(canal(fuenteId, clave = "canon:v2:1"))
            val canal2 = db.canalDao().insertar(canal(fuenteId, clave = "canon:v2:2"))
            db.aparienciaDao().insertar(
                apariencia(lista1, canal1, grupoId = grupo1, clave = "presentacion:v2:a"),
            )
            db.aparienciaDao().insertar(
                apariencia(lista2, canal2, grupoId = grupo2, clave = "presentacion:v2:b"),
            )

            assertTrue(ocultaciones.ocultarGrupo(grupo1, T1))
            assertTrue(ocultaciones.grupoOculto(grupo1))
            assertFalse(ocultaciones.grupoOculto(grupo2))
            assertEquals(listOf(grupo1), ocultaciones.gruposOcultos())
            assertEquals(grupo1, db.aparienciaDao().porLista(lista1).single().grupoId)
            assertEquals(grupo2, db.aparienciaDao().porLista(lista2).single().grupoId)

            assertTrue(ocultaciones.ocultarGrupo(grupo2, T2))
            assertEquals(setOf(grupo1, grupo2), ocultaciones.gruposOcultos().toSet())

            assertTrue(ocultaciones.mostrarGrupo(grupo1))
            assertFalse(ocultaciones.grupoOculto(grupo1))
            assertTrue(ocultaciones.grupoOculto(grupo2))
        }
    }

    @Test
    fun `las restricciones de unicidad y las claves foraneas son reales`() {
        runBlocking {
            val fuenteId = fuente()
            val listaId = lista(fuenteId)
            val canalId = db.canalDao().insertar(canal(fuenteId))
            val grupoId = grupo(listaId, "Noticias")

            favoritos.agregar(canalId, T1)
            historial.registrar(canalId, T1)
            ocultaciones.ocultarCanal(canalId, T1)
            ocultaciones.ocultarGrupo(grupoId, T1)

            val sql = db.openHelper.writableDatabase
            assertThrows(SQLiteConstraintException::class.java) {
                sql.execSQL(
                    "INSERT INTO Favorito (canalId, creadoEn) VALUES (?, ?)",
                    arrayOf<Any?>(canalId, T2.toEpochMilli()),
                )
            }
            assertThrows(SQLiteConstraintException::class.java) {
                sql.execSQL(
                    "INSERT INTO Historial (canalId, fecha) VALUES (?, ?)",
                    arrayOf<Any?>(canalId, T2.toEpochMilli()),
                )
            }
            assertThrows(SQLiteConstraintException::class.java) {
                sql.execSQL(
                    "INSERT INTO OcultacionCanal (canalId, creadoEn) VALUES (?, ?)",
                    arrayOf<Any?>(canalId, T2.toEpochMilli()),
                )
            }
            assertThrows(SQLiteConstraintException::class.java) {
                sql.execSQL(
                    "INSERT INTO OcultacionGrupo (grupoId, creadoEn) VALUES (?, ?)",
                    arrayOf<Any?>(grupoId, T2.toEpochMilli()),
                )
            }
            assertThrows(SQLiteConstraintException::class.java) {
                sql.execSQL(
                    "INSERT INTO Favorito (canalId, creadoEn) VALUES (?, ?)",
                    arrayOf<Any?>(9999L, T2.toEpochMilli()),
                )
            }
            assertThrows(SQLiteConstraintException::class.java) {
                sql.execSQL(
                    "INSERT INTO Historial (canalId, fecha) VALUES (?, ?)",
                    arrayOf<Any?>(9999L, T2.toEpochMilli()),
                )
            }
            assertThrows(SQLiteConstraintException::class.java) {
                sql.execSQL(
                    "INSERT INTO OcultacionCanal (canalId, creadoEn) VALUES (?, ?)",
                    arrayOf<Any?>(9999L, T2.toEpochMilli()),
                )
            }
            assertThrows(SQLiteConstraintException::class.java) {
                sql.execSQL(
                    "INSERT INTO OcultacionGrupo (grupoId, creadoEn) VALUES (?, ?)",
                    arrayOf<Any?>(9999L, T2.toEpochMilli()),
                )
            }
        }
    }

    @Test
    fun `las operaciones no cambian el esquema`() {
        runBlocking {
            val antes = esquema()
            val fuenteId = fuente()
            val listaId = lista(fuenteId)
            val canalId = db.canalDao().insertar(canal(fuenteId))
            val grupoId = grupo(listaId, "Noticias")
            db.aparienciaDao().insertar(apariencia(listaId, canalId, grupoId = grupoId))

            favoritos.agregar(canalId, T1)
            favoritos.quitar(canalId)
            historial.registrar(canalId, T1)
            historial.registrar(canalId, T2)
            ocultaciones.ocultarCanal(canalId, T1)
            ocultaciones.mostrarCanal(canalId)
            ocultaciones.ocultarGrupo(grupoId, T1)
            ocultaciones.mostrarGrupo(grupoId)

            assertEquals(antes, esquema())
            assertEquals(1, db.openHelper.readableDatabase.version)
        }
    }

    private fun esquema(): List<String> {
        val filas = mutableListOf<String>()
        db.openHelper.readableDatabase
            .query(
                """
                SELECT type, name, tbl_name, sql FROM sqlite_master
                WHERE name NOT LIKE 'sqlite_%'
                  AND name NOT IN ('android_metadata', 'room_master_table')
                ORDER BY type, name
                """.trimIndent(),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    filas +=
                        listOf(
                            cursor.getString(0),
                            cursor.getString(1),
                            cursor.getString(2),
                            cursor.getString(3) ?: "",
                        ).joinToString("|")
                }
            }
        return filas
    }

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

    private suspend fun lista(
        fuenteId: Long,
        nombre: String = "Lista",
        orden: Int = 0,
    ): Long =
        db.listaDao().insertar(
            ListaEntity(
                fuenteId = fuenteId,
                nombre = nombre,
                ubicacionCifrada = byteArrayOf(0x01),
                ubicacionHmac = byteArrayOf(0x02),
                esquema = "content",
                orden = orden,
                estado = "activa",
                creadoEn = AHORA,
                actualizadoEn = AHORA,
            ),
        )

    private fun canal(
        fuenteId: Long,
        clave: String = "canon:v2:uno",
        nombre: String = "Canal",
    ): CanalEntity =
        CanalEntity(
            fuenteId = fuenteId,
            nombre = nombre,
            urlStreamCifrada = byteArrayOf(0x01),
            urlStreamHmac = byteArrayOf(0x02),
            claveCanonica = clave,
            claveUnicidad = clave,
            claveDerivable = true,
            creadoEn = AHORA,
            actualizadoEn = AHORA,
        )

    private suspend fun grupo(
        listaId: Long,
        nombre: String,
    ): Long = db.grupoDao().insertar(GrupoEntity(listaId = listaId, nombre = nombre))

    private fun apariencia(
        listaId: Long,
        canalId: Long,
        grupoId: Long? = null,
        clave: String = "presentacion:v2:a",
        orden: Int = 0,
    ): AparienciaEntity =
        AparienciaEntity(
            listaId = listaId,
            canalId = canalId,
            grupoId = grupoId,
            orden = orden,
            clavePresentacion = clave,
            repeticiones = 1,
            creadoEn = AHORA,
            actualizadoEn = AHORA,
        )

    private companion object {
        val AHORA: Instant = Instant.parse("2026-01-01T00:00:00Z")
        val T0: Instant = AHORA
        val T1: Instant = AHORA.plusSeconds(1)
        val T2: Instant = AHORA.plusSeconds(2)
        val T3: Instant = AHORA.plusSeconds(3)
    }
}
