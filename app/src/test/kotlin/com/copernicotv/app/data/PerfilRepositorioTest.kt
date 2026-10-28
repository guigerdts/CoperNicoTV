package com.copernicotv.app.data

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.app.data.db.entities.PerfilEntity
import com.copernicotv.core.identity.CaseFolderCompleto
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
        repositorio = PerfilRepositorio(db.perfilDao(), CaseFolderCompleto)
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
