package com.copernicotv.app.data.db

import android.content.Context
import android.database.Cursor
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Conformidad campo por campo del esquema v1 contra §6.3 de U0.
 *
 * La fuente de verdad es la base real que crea Room: se consultan
 * `PRAGMA table_info`, `PRAGMA foreign_key_list` y `PRAGMA index_list`/
 * `index_info`, no el JSON exportado ni las anotaciones del código. Así una
 * columna ausente, sobrante, renombrada, con otro tipo, otra nulabilidad, otra
 * clave foránea u otro índice falla con el nombre exacto.
 *
 * Cobertura declarada: §6.3.1 fija nombres, tipos y parte de la nulabilidad;
 * §6.3.2 fija claves primarias, claves foráneas con `ON DELETE`, índices y
 * prohibiciones. Donde §6.3 no fija un atributo, no se asevera: inventarlo
 * convertiría la prueba en una segunda fuente de requisitos.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EsquemaConformidadTest {
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
    fun `la transcripcion cubre las 26 tablas de 6_3_1`() {
        assertEquals(26, ESQUEMA_NORMATIVO_V1.size)
    }

    @Test
    fun `cada tabla declara exactamente las columnas de 6_3_1`() {
        ESQUEMA_NORMATIVO_V1.forEach { (tabla, normativa) ->
            val esperadas = normativa.columnas.map { nombreFisico(it.nombre) }.toSet()
            assertEquals("columnas de $tabla", esperadas, columnas(tabla).keys)
        }
    }

    @Test
    fun `cada columna tiene el tipo que fija 6_3_1`() {
        ESQUEMA_NORMATIVO_V1.forEach { (tabla, normativa) ->
            val reales = columnas(tabla)
            normativa.columnas.forEach { columna ->
                val real =
                    checkNotNull(reales[nombreFisico(columna.nombre)]) {
                        "falta la columna $tabla.${columna.nombre}"
                    }
                assertEquals("tipo de $tabla.${columna.nombre}", columna.tipo, real.tipo)
            }
        }
    }

    @Test
    fun `la nulabilidad que fija 6_3_1 se respeta`() {
        ESQUEMA_NORMATIVO_V1.forEach { (tabla, normativa) ->
            val reales = columnas(tabla)
            normativa.columnas.filter { it.nula != null }.forEach { columna ->
                val real = checkNotNull(reales[nombreFisico(columna.nombre)])
                assertEquals("nulabilidad de $tabla.${columna.nombre}", columna.nula, real.nula)
            }
        }
    }

    @Test
    fun `las claves primarias coinciden con 6_3_1`() {
        ESQUEMA_NORMATIVO_V1.forEach { (tabla, normativa) ->
            val esperadas =
                normativa.columnas
                    .filter { it.pk > 0 }
                    .associate { nombreFisico(it.nombre) to it.pk }
            val reales = columnas(tabla).values.filter { it.pk > 0 }.associate { it.nombre to it.pk }
            assertEquals("clave primaria de $tabla", esperadas, reales)
        }
    }

    @Test
    fun `las claves foraneas coinciden en columna, tabla padre y borrado`() {
        ESQUEMA_NORMATIVO_V1.forEach { (tabla, normativa) ->
            val reales = clavesForaneas(tabla)
            normativa.fks.forEach { fk ->
                val columna = nombreFisico(fk.columna)
                val real =
                    checkNotNull(reales.find { it.columna == columna }) {
                        "falta la clave foranea $tabla.$columna"
                    }
                assertEquals("tabla padre de $tabla.$columna", fk.tablaPadre, real.tablaPadre)
                assertEquals("columna padre de $tabla.$columna", fk.columnaPadre, real.columnaPadre)
                if (fk.borrado != null) {
                    assertEquals("borrado de $tabla.$columna", fk.borrado, real.borrado)
                }
            }
        }
    }

    @Test
    fun `los indices requeridos existen con sus columnas y unicidad`() {
        ESQUEMA_NORMATIVO_V1.forEach { (tabla, normativa) ->
            val reales = indices(tabla)
            normativa.indices.forEach { esperado ->
                val columnasEsperadas = esperado.columnas.map { nombreFisico(it) }
                val real =
                    checkNotNull(reales.find { it.columnas == columnasEsperadas }) {
                        "falta el indice $tabla$columnasEsperadas"
                    }
                assertEquals(
                    "unicidad del indice $tabla$columnasEsperadas",
                    esperado.unico,
                    real.unico,
                )
            }
        }
    }

    @Test
    fun `Apariencia no es unica por lista y canal`() {
        val reales = indices("Apariencia")
        assertTrue(
            "Apariencia no debe tener unicidad (listaId, canalId)",
            reales.none { it.columnas == listOf("listaId", "canalId") && it.unico },
        )
    }

    @Test
    fun `ColeccionCanal no es unica por coleccion y posicion`() {
        val reales = indices("ColeccionCanal")
        assertTrue(
            "ColeccionCanal no debe tener unicidad (coleccionId, posicion)",
            reales.none { it.columnas == listOf("coleccionId", "posicion") && it.unico },
        )
    }

    @Test
    fun `Fuente no guarda ubicacion, URL, credenciales ni retencion de EPG`() {
        val reales = columnas("Fuente").keys.map { it.lowercase() }
        listOf("ubicacion", "url", "credencial", "retencion").forEach { prohibido ->
            assertTrue(
                "Fuente no debe declarar $prohibido",
                reales.none { it.contains(prohibido) },
            )
        }
    }

    @Test
    fun `ObservacionPendienteImportacion no referencia Canal`() {
        val reales = clavesForaneas("ObservacionPendienteImportacion")
        assertTrue(
            "ObservacionPendienteImportacion no debe tener FK a Canal",
            reales.none { it.tablaPadre == "Canal" },
        )
    }

    @Test
    fun `el indice unico de Perfil es binario y no NOCASE`() {
        val ddl =
            filas("SELECT sql FROM sqlite_master WHERE type = 'index' AND tbl_name = 'Perfil'") { cursor ->
                cursor.getString(0)
            }.filterNotNull()
        assertTrue("Perfil debe tener un indice declarado", ddl.isNotEmpty())
        assertTrue(
            "el indice de Perfil no debe usar NOCASE",
            ddl.none { it.uppercase().contains("NOCASE") },
        )
    }

    @Test
    fun `ninguna tabla guarda generacion HMAC ni version de canonicalizacion por fila`() {
        val prohibidas = setOf("hmacversion", "cv", "cvid", "versioncanonica")
        ESQUEMA_NORMATIVO_V1.keys.forEach { tabla ->
            val reales = columnas(tabla).keys.map { it.lowercase() }
            assertEquals(
                "columnas prohibidas en $tabla",
                emptySet<String>(),
                reales.filter { it in prohibidas }.toSet(),
            )
        }
    }

    private fun <T> filas(
        sql: String,
        leer: (Cursor) -> T,
    ): List<T> =
        db.openHelper.readableDatabase.query(sql).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(leer(cursor))
                }
            }
        }

    private fun columnas(tabla: String): Map<String, ColumnaReal> =
        filas("PRAGMA table_info(\"$tabla\")") { cursor ->
            ColumnaReal(
                nombre = checkNotNull(cursor.getString(1)),
                tipo = checkNotNull(cursor.getString(2)).uppercase(),
                nula = cursor.getInt(3) == 0,
                pk = cursor.getInt(5),
            )
        }.associateBy { it.nombre }

    private fun clavesForaneas(tabla: String): List<FkReal> =
        filas("PRAGMA foreign_key_list(\"$tabla\")") { cursor ->
            FkReal(
                columna = checkNotNull(cursor.getString(3)),
                tablaPadre = checkNotNull(cursor.getString(2)),
                columnaPadre = checkNotNull(cursor.getString(4)),
                borrado = checkNotNull(cursor.getString(6)).uppercase(),
            )
        }

    private fun indices(tabla: String): List<IndiceReal> {
        val declarados =
            filas("PRAGMA index_list(\"$tabla\")") { cursor ->
                IndiceReal(
                    nombre = cursor.getString(1),
                    unico = cursor.getInt(2) == 1,
                    columnas = emptyList(),
                )
            }.filterNot { it.nombre.startsWith("sqlite_autoindex_") }

        return declarados.map { indice ->
            indice.copy(
                columnas =
                    filas("PRAGMA index_info(\"${indice.nombre}\")") { cursor ->
                        checkNotNull(cursor.getString(2))
                    },
            )
        }
    }

    private data class ColumnaReal(
        val nombre: String,
        val tipo: String,
        val nula: Boolean,
        val pk: Int,
    )

    private data class FkReal(
        val columna: String,
        val tablaPadre: String,
        val columnaPadre: String,
        val borrado: String,
    )

    private data class IndiceReal(
        val nombre: String,
        val unico: Boolean,
        val columnas: List<String>,
    )
}
