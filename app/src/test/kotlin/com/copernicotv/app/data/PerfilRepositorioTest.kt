package com.copernicotv.app.data

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.app.data.db.entities.FuenteEntity
import com.copernicotv.app.data.db.entities.ListaEntity
import com.copernicotv.app.data.db.entities.PerfilEntity
import com.copernicotv.core.identity.CaseFolderCompleto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
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
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit

/**
 * Persistencia de Perfiles con identidad lógica separada de la representación.
 *
 * Comprueba que un nombre recalculado nunca fusiona ni sobrescribe identidades:
 * el conflicto se detecta antes de escribir y el índice único de Room es la red
 * de seguridad para una carrera entre la comprobación y la inserción.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PerfilRepositorioTest {
    private lateinit var db: AppDatabase
    private lateinit var repositorio: PerfilRepositorio

    private val ahora: Instant = Instant.parse("2026-01-01T00:00:00Z")

    @Before
    fun abrir() {
        val contexto = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room
                .inMemoryDatabaseBuilder(contexto, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        repositorio = PerfilRepositorio(db.perfilDao(), db.listaDao(), CaseFolderCompleto)
    }

    @After
    fun cerrar() {
        db.close()
    }

    @Test
    fun `crea un perfil y conserva la representacion original`() {
        runBlocking {
            val resultado = repositorio.crear("  Jos\u00E9  ", ahora)

            assertTrue(resultado is ResultadoAltaPerfil.Creado)
            val perfil = (resultado as ResultadoAltaPerfil.Creado).perfil
            assertTrue(perfil.id > 0)
            assertEquals("  Jos\u00E9  ", perfil.nombre)
            assertEquals("jos\u00E9", perfil.nombreNormalizado)
            assertEquals(ahora, perfil.creadoEn)
        }
    }

    @Test
    fun `detecta conflicto antes de persistir y no sobrescribe la identidad existente`() {
        runBlocking {
            val primero = (repositorio.crear("Jos\u00E9", ahora) as ResultadoAltaPerfil.Creado).perfil
            val segundo = repositorio.crear("Jose\u0301", ahora)

            assertTrue(segundo is ResultadoAltaPerfil.Conflicto)
            val conflicto = segundo as ResultadoAltaPerfil.Conflicto
            assertEquals("jos\u00E9", conflicto.normalizado)
            assertEquals(primero.id, conflicto.existente.id)
            assertEquals(1, db.perfilDao().contar())
            assertEquals("Jos\u00E9", db.perfilDao().porId(primero.id)?.nombre)
        }
    }

    @Test
    fun `dos nombres con identidad distinta crean dos filas`() {
        runBlocking {
            val primero = repositorio.crear("Jos\u00E9", ahora)
            val segundo = repositorio.crear("Jose", ahora)

            assertTrue(primero is ResultadoAltaPerfil.Creado)
            assertTrue(segundo is ResultadoAltaPerfil.Creado)
            assertEquals(2, db.perfilDao().contar())
        }
    }

    @Test
    fun `renombrar detecta conflicto y deja la fila intacta`() {
        runBlocking {
            val jose = (repositorio.crear("Jos\u00E9", ahora) as ResultadoAltaPerfil.Creado).perfil
            val otro = (repositorio.crear("Trabajo", ahora) as ResultadoAltaPerfil.Creado).perfil

            val resultado = repositorio.renombrar(otro.id, "Jose\u0301", ahora)

            assertTrue(resultado is ResultadoRenombrado.Conflicto)
            val conflicto = resultado as ResultadoRenombrado.Conflicto
            assertEquals(jose.id, conflicto.existente.id)
            assertEquals("Trabajo", db.perfilDao().porId(otro.id)?.nombre)
            assertEquals("trabajo", db.perfilDao().porId(otro.id)?.nombreNormalizado)
        }
    }

    @Test
    fun `renombrar a la misma identidad logica no es conflicto`() {
        runBlocking {
            val perfil = (repositorio.crear("Jos\u00E9", ahora) as ResultadoAltaPerfil.Creado).perfil
            val despues = ahora.plusSeconds(60)

            val resultado = repositorio.renombrar(perfil.id, "JOSE\u0301", despues)

            assertTrue(resultado is ResultadoRenombrado.Renombrado)
            val renombrado = (resultado as ResultadoRenombrado.Renombrado).perfil
            assertEquals(perfil.id, renombrado.id)
            assertEquals("JOSE\u0301", renombrado.nombre)
            assertEquals("jos\u00E9", renombrado.nombreNormalizado)
            assertEquals(despues, renombrado.actualizadoEn)
            assertEquals(1, db.perfilDao().contar())
        }
    }

    @Test
    fun `renombrar un perfil inexistente devuelve NoExiste`() {
        runBlocking {
            assertEquals(ResultadoRenombrado.NoExiste, repositorio.renombrar(999L, "Trabajo", ahora))
        }
    }

    @Test
    fun `el indice unico rechaza una identidad duplicada escrita directamente`() {
        runBlocking { db.perfilDao().insertar(entidad("Jos\u00E9", "jos\u00E9")) }
        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking { db.perfilDao().insertar(entidad("Jose\u0301", "jos\u00E9")) }
        }
        runBlocking { assertNotNull(db.perfilDao().porNombreNormalizado("jos\u00E9")) }
    }

    @Test
    fun `crear rechaza un nombre vacio o solo espacios con nombre_perfil_vacio`() {
        runBlocking {
            val vacio = repositorio.crear("", ahora)
            val espacios = repositorio.crear("   \t\n ", ahora)

            assertEquals(ResultadoAltaPerfil.Rechazado(), vacio)
            assertEquals(ResultadoAltaPerfil.Rechazado(), espacios)
            assertEquals("nombre_perfil_vacio", (vacio as ResultadoAltaPerfil.Rechazado).codigo)
            assertEquals("nombre_perfil_vacio", (espacios as ResultadoAltaPerfil.Rechazado).codigo)
            assertEquals(0, db.perfilDao().contar())
        }
    }

    @Test
    fun `renombrar rechaza un nombre vacio con nombre_perfil_vacio y deja la fila intacta`() {
        runBlocking {
            val perfil = (repositorio.crear("Trabajo", ahora) as ResultadoAltaPerfil.Creado).perfil

            val resultado = repositorio.renombrar(perfil.id, "  ", ahora.plusSeconds(60))

            assertEquals(ResultadoRenombrado.Rechazado(), resultado)
            assertEquals("nombre_perfil_vacio", (resultado as ResultadoRenombrado.Rechazado).codigo)
            assertEquals("Trabajo", db.perfilDao().porId(perfil.id)?.nombre)
            assertEquals("trabajo", db.perfilDao().porId(perfil.id)?.nombreNormalizado)
        }
    }

    @Test
    fun `un conflicto de creacion informa nombre_perfil_duplicado`() {
        runBlocking {
            val primero = (repositorio.crear("Jos\u00E9", ahora) as ResultadoAltaPerfil.Creado).perfil

            val segundo = repositorio.crear("Jose\u0301", ahora)

            assertEquals("nombre_perfil_duplicado", (segundo as ResultadoAltaPerfil.Conflicto).codigo)
            assertEquals(primero.id, segundo.existente.id)
        }
    }

    @Test
    fun `un conflicto de renombrado informa nombre_perfil_duplicado`() {
        runBlocking {
            repositorio.crear("Jos\u00E9", ahora)
            val otro = (repositorio.crear("Trabajo", ahora) as ResultadoAltaPerfil.Creado).perfil

            val resultado = repositorio.renombrar(otro.id, "Jose\u0301", ahora)

            assertEquals("nombre_perfil_duplicado", (resultado as ResultadoRenombrado.Conflicto).codigo)
            assertEquals("Trabajo", db.perfilDao().porId(otro.id)?.nombre)
        }
    }

    @Test
    fun `la caja no depende del locale y tres formas del mismo nombre son la misma identidad`() {
        runBlocking {
            val primero = repositorio.crear("TRABAJO", ahora)

            val segundo = repositorio.crear("Trabajo", ahora)
            val tercero = repositorio.crear("trabajo", ahora)

            assertTrue(segundo is ResultadoAltaPerfil.Conflicto)
            assertTrue(tercero is ResultadoAltaPerfil.Conflicto)
            val creado = primero as ResultadoAltaPerfil.Creado
            assertEquals("trabajo", (segundo as ResultadoAltaPerfil.Conflicto).normalizado)
            assertEquals("trabajo", (tercero as ResultadoAltaPerfil.Conflicto).normalizado)
            assertEquals(1, db.perfilDao().contar())
            assertEquals("TRABAJO", db.perfilDao().porId(creado.perfil.id)?.nombre)
        }
    }

    @Test
    fun `dos creaciones concurrentes dejan una sola fila ganadora`() {
        val barrera = CyclicBarrier(2)
        val resultados =
            runBlocking {
                coroutineScope {
                    (1..2)
                        .map {
                            async(Dispatchers.IO) {
                                barrera.await(10, TimeUnit.SECONDS)
                                repositorio.crear("Trabajo", ahora)
                            }
                        }.awaitAll()
                }
            }

        val creados = resultados.filterIsInstance<ResultadoAltaPerfil.Creado>()
        val conflictos = resultados.filterIsInstance<ResultadoAltaPerfil.Conflicto>()
        assertEquals(1, creados.size)
        assertEquals(1, conflictos.size)
        assertEquals("nombre_perfil_duplicado", conflictos.single().codigo)
        assertEquals(creados.single().perfil.id, conflictos.single().existente.id)
        runBlocking {
            assertEquals(1, db.perfilDao().contar())
            val persistido = db.perfilDao().porId(creados.single().perfil.id)
            assertNotNull(persistido)
            assertEquals("Trabajo", persistido?.nombre)
            assertEquals("trabajo", persistido?.nombreNormalizado)
        }
    }

    @Test
    fun `dos inserciones directas concurrentes chocan con el indice unico`() {
        val barrera = CyclicBarrier(2)
        val resultados =
            runBlocking {
                coroutineScope {
                    (1..2)
                        .map { indice ->
                            async(Dispatchers.IO) {
                                barrera.await(10, TimeUnit.SECONDS)
                                runCatching {
                                    db.perfilDao().insertar(
                                        entidad(if (indice == 1) "Jos\u00E9" else "Jose\u0301", "jos\u00E9"),
                                    )
                                }
                            }
                        }.awaitAll()
                }
            }

        assertEquals(1, resultados.count { it.isSuccess })
        assertEquals(1, resultados.count { it.exceptionOrNull() is SQLiteConstraintException })
        runBlocking { assertEquals(1, db.perfilDao().contar()) }
    }

    @Test
    fun `asocia listas n a n y permite perfil vacio y lista sin perfil`() {
        runBlocking {
            val lista1 = sembrarLista("L1", 0)
            val lista2 = sembrarLista("L2", 1)
            val lista3 = sembrarLista("L3", 2)
            val perfilA = (repositorio.crear("A", ahora) as ResultadoAltaPerfil.Creado).perfil
            val perfilB = (repositorio.crear("B", ahora) as ResultadoAltaPerfil.Creado).perfil
            val perfilVacio = (repositorio.crear("Vacío", ahora) as ResultadoAltaPerfil.Creado).perfil

            assertEquals(ResultadoAccion.Ok, repositorio.asociarLista(perfilA.id, lista1))
            assertEquals(ResultadoAccion.Ok, repositorio.asociarLista(perfilA.id, lista2))
            assertEquals(ResultadoAccion.Ok, repositorio.asociarLista(perfilB.id, lista2))
            assertEquals(ResultadoAccion.Ok, repositorio.asociarLista(perfilB.id, lista3))
            assertEquals(ResultadoAccion.Ok, repositorio.asociarLista(perfilA.id, lista1))

            assertEquals(listOf(lista1, lista2), repositorio.listasDePerfil(perfilA.id).first())
            assertEquals(listOf(lista2, lista3), repositorio.listasDePerfil(perfilB.id).first())
            assertTrue(repositorio.listasDePerfil(perfilVacio.id).first().isEmpty())
            assertEquals(4, db.perfilDao().contarAsociaciones())
        }
    }

    @Test
    fun `eliminar perfil borra sus asociaciones y conserva las listas`() {
        runBlocking {
            val lista = sembrarLista("L1", 0)
            val perfil = (repositorio.crear("Trabajo", ahora) as ResultadoAltaPerfil.Creado).perfil
            repositorio.asociarLista(perfil.id, lista)

            assertEquals(ResultadoAccion.Ok, repositorio.eliminarPerfil(perfil.id))

            assertEquals(0, db.perfilDao().contar())
            assertEquals(0, db.perfilDao().contarAsociaciones())
            assertNotNull(db.listaDao().porId(lista))
            assertEquals(ResultadoAccion.Error(PerfilRepositorio.PERFIL_NO_EXISTE), repositorio.eliminarPerfil(perfil.id))
        }
    }

    @Test
    fun `eliminar lista borra su asociacion sin borrar el perfil`() {
        runBlocking {
            val lista = sembrarLista("L1", 0)
            val perfil = (repositorio.crear("Trabajo", ahora) as ResultadoAltaPerfil.Creado).perfil
            repositorio.asociarLista(perfil.id, lista)

            db.listaDao().eliminar(lista)

            assertEquals(1, db.perfilDao().contar())
            assertTrue(repositorio.listasDePerfil(perfil.id).first().isEmpty())
        }
    }

    @Test
    fun `asociar una lista inexistente no crea la asociacion`() {
        runBlocking {
            val perfil = (repositorio.crear("Trabajo", ahora) as ResultadoAltaPerfil.Creado).perfil

            assertEquals(
                ResultadoAccion.Error(PerfilRepositorio.LISTA_NO_EXISTE),
                repositorio.asociarLista(perfil.id, 999L),
            )
            assertEquals(0, db.perfilDao().contarAsociaciones())
        }
    }

    private suspend fun sembrarLista(
        nombre: String,
        orden: Int,
    ): Long {
        val fuenteId =
            db.fuenteDao().insertar(
                FuenteEntity(
                    nombre = "Fuente $nombre",
                    estado = "activa",
                    usarIntervaloGlobal = true,
                    creadoEn = ahora,
                    actualizadoEn = ahora,
                ),
            )
        return db.listaDao().insertar(
            ListaEntity(
                fuenteId = fuenteId,
                nombre = nombre,
                ubicacionCifrada = byteArrayOf(orden.toByte()),
                ubicacionHmac = byteArrayOf((orden + 1).toByte()),
                esquema = "content",
                orden = orden,
                estado = if (orden == 0) "activa" else "inactiva",
                creadoEn = ahora,
                actualizadoEn = ahora,
            ),
        )
    }

    private fun entidad(
        nombre: String,
        normalizado: String,
    ): PerfilEntity =
        PerfilEntity(
            nombre = nombre,
            nombreNormalizado = normalizado,
            creadoEn = ahora,
            actualizadoEn = ahora,
        )
}
