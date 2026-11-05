package com.copernicotv.app

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.app.data.db.dao.HistorialConsultas
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verificación instrumentada de S11b sobre el SQLite real de Android API 34.
 *
 * Usa las mismas constantes SQL que `HistorialDao`, crea el esquema real de
 * Room y comprueba propiedades semánticas del plan, no una salida textual fija.
 */
@SdkSuppress(minSdkVersion = 34)
@RunWith(AndroidJUnit4::class)
class S11bPlanConsultaTest {
    private lateinit var db: AppDatabase
    private lateinit var sqliteVersion: String

    @Before
    fun prepararBase() {
        val contexto = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room.inMemoryDatabaseBuilder(contexto, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        val soporte = db.openHelper.writableDatabase
        sqliteVersion = leerVersionSqlite(soporte)
        sembrarDatosRepresentativos(soporte)
        Log.i(
            TAG,
            "S11b api=${Build.VERSION.SDK_INT} dispositivo=${Build.DEVICE} modelo=${Build.MODEL} sqlite=$sqliteVersion",
        )
    }

    @After
    fun cerrarBase() {
        if (::db.isInitialized) {
            db.close()
        }
    }

    @Test
    fun elPlanUsaElIndiceCompuestoYNoOrdenaConTempBTree() {
        val soporte = db.openHelper.readableDatabase

        assertEquals(
            "S11b: el índice de Historial debe cubrir (fecha, canalId)",
            listOf("fecha", "canalId"),
            columnasDelIndice(soporte, INDICE_HISTORIAL),
        )
        assertEquals(FILAS, contar(soporte, "Historial"))
        assertEquals(FILAS, contar(soporte, "Canal"))
        assertTrue(contar(soporte, "Favorito") > 0)
        assertTrue(contar(soporte, "OcultacionCanal") > 0)
        comprobarContratoS11b()

        val ultimos = runBlocking { db.historialDao().ultimos(LIMITE) }
        assertEquals(LIMITE, ultimos.size)
        assertEquals(
            "S11b: `ultimos` debe devolver el orden descendente por fecha y canal",
            (FILAS downTo FILAS - LIMITE + 1).toList(),
            ultimos.map { it.canalId.toInt() },
        )

        val resumen = runBlocking { db.historialDao().observarUltimos(LIMITE).first() }
        assertEquals(LIMITE, resumen.size)

        val planUltimos = plan(soporte, "ultimos", HistorialConsultas.ULTIMOS)
        registrarPlan("ultimos", planUltimos)
        comprobarPlanConIndice(planUltimos, "ultimos")

        val planObservar = plan(soporte, "observarUltimos", HistorialConsultas.OBSERVAR_ULTIMOS)
        registrarPlan("observarUltimos", planObservar)
        comprobarPlanConIndice(planObservar, "observarUltimos")
        comprobarAccesosRelacionados(planObservar)
    }

    private fun comprobarContratoS11b() {
        assertTrue(
            "S11b: falta el orden determinista de `ultimos`",
            HistorialConsultas.ULTIMOS.contains("ORDER BY fecha DESC, canalId DESC"),
        )
        assertTrue(
            "S11b: falta el límite de `ultimos`",
            HistorialConsultas.ULTIMOS.contains("LIMIT :limite"),
        )
        assertTrue(
            "S11b: `observarUltimos` debe usar el JOIN real de Historial con Canal",
            HistorialConsultas.OBSERVAR_ULTIMOS.contains("INNER JOIN Canal c ON c.id = h.canalId"),
        )
        assertTrue(
            "S11b: `observarUltimos` debe exponer las columnas canalId y nombre",
            HistorialConsultas.OBSERVAR_ULTIMOS.contains("c.id AS canalId") &&
                HistorialConsultas.OBSERVAR_ULTIMOS.contains("c.nombre AS nombre"),
        )
        assertEquals(
            "S11b: `observarUltimos` debe conservar sus dos EXISTS",
            2,
            Regex("EXISTS\\s*\\(").findAll(HistorialConsultas.OBSERVAR_ULTIMOS).count(),
        )
        assertTrue(
            "S11b: falta el orden determinista de `observarUltimos`",
            HistorialConsultas.OBSERVAR_ULTIMOS.contains("ORDER BY h.fecha DESC, h.canalId DESC"),
        )
        assertTrue(
            "S11b: falta el límite de `observarUltimos`",
            HistorialConsultas.OBSERVAR_ULTIMOS.contains("LIMIT :limite"),
        )
    }

    private fun comprobarPlanConIndice(
        plan: List<String>,
        nombre: String,
    ) {
        assertFalse(
            "S11b/$nombre: el plan ordena con TEMP B-TREE: $plan",
            plan.any { it.contains("USE TEMP B-TREE FOR ORDER BY") },
        )

        val filasHistorial = plan.filter { it.contains("Historial") }
        assertTrue(
            "S11b/$nombre: Historial debe recorrerse con $INDICE_HISTORIAL: $plan",
            filasHistorial.any {
                it.startsWith("SCAN") && it.contains(INDICE_HISTORIAL)
            },
        )
        assertFalse(
            "S11b/$nombre: Historial no debe escanearse sin índice: $plan",
            filasHistorial.any {
                it.startsWith("SCAN") && !it.contains("INDEX")
            },
        )
    }

    private fun comprobarAccesosRelacionados(plan: List<String>) {
        assertTrue(
            "S11b/observarUltimos: Canal debe resolverse por clave primaria: $plan",
            plan.any { it.startsWith("SEARCH") && it.contains("INTEGER PRIMARY KEY") },
        )
        assertTrue(
            "S11b/observarUltimos: Favorito debe resolverse por índice: $plan",
            plan.any {
                it.startsWith("SEARCH") && it.contains("index_Favorito_canalId")
            },
        )
        assertTrue(
            "S11b/observarUltimos: OcultacionCanal debe resolverse por índice: $plan",
            plan.any {
                it.startsWith("SEARCH") && it.contains("index_OcultacionCanal_canalId")
            },
        )
        assertFalse(
            "S11b/observarUltimos: no debe escanearse Favorito completo: $plan",
            plan.any { it.startsWith("SCAN") && it.contains("Favorito") },
        )
        assertFalse(
            "S11b/observarUltimos: no debe escanearse OcultacionCanal completo: $plan",
            plan.any { it.startsWith("SCAN") && it.contains("OcultacionCanal") },
        )
    }

    private fun plan(
        soporte: SupportSQLiteDatabase,
        nombre: String,
        consulta: String,
    ): List<String> {
        val consultaExplain = consulta.replace(":limite", "?")
        assertTrue(
            "S11b/$nombre: la consulta real debe usar :limite: $consulta",
            consultaExplain != consulta,
        )
        val filas = mutableListOf<String>()
        soporte
            .query("EXPLAIN QUERY PLAN $consultaExplain", arrayOf<Any?>(LIMITE))
            .use { cursor ->
                val columnaDetalle = cursor.getColumnIndexOrThrow("detail")
                while (cursor.moveToNext()) {
                    filas += cursor.getString(columnaDetalle)
                }
            }
        assertTrue("S11b/$nombre: EXPLAIN no devolvió filas", filas.isNotEmpty())
        return filas
    }

    private fun columnasDelIndice(
        soporte: SupportSQLiteDatabase,
        indice: String,
    ): List<String> {
        val existe =
            soporte
                .query(
                    "SELECT 1 FROM sqlite_master " +
                        "WHERE type = 'index' AND name = ? AND tbl_name = 'Historial'",
                    arrayOf<Any?>(indice),
                ).use { it.moveToFirst() }
        assertTrue("S11b: falta el índice $indice", existe)

        val columnas = mutableListOf<String>()
        soporte.query("PRAGMA index_info(`$indice`)").use { cursor ->
            val columnaNombre = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                columnas += cursor.getString(columnaNombre)
            }
        }
        return columnas
    }

    private fun contar(
        soporte: SupportSQLiteDatabase,
        tabla: String,
    ): Int =
        soporte.query("SELECT COUNT(*) FROM $tabla").use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun leerVersionSqlite(soporte: SupportSQLiteDatabase): String =
        soporte.query("SELECT sqlite_version()").use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getString(0)
        }

    private fun sembrarDatosRepresentativos(soporte: SupportSQLiteDatabase) {
        soporte.beginTransaction()
        try {
            soporte.execSQL(
                "INSERT INTO Fuente " +
                    "(id, nombre, estado, intervaloMinutos, usarIntervaloGlobal, creadoEn, actualizadoEn) " +
                    "VALUES (1, 'Fuente S11b', 'A', NULL, 1, 0, 0)",
            )

            val canal =
                soporte.compileStatement(
                    "INSERT INTO Canal " +
                        "(id, fuenteId, nombre, urlStreamCifrada, urlStreamHmac, claveUnicidad, " +
                        "claveDerivable, estado, creadoEn, actualizadoEn) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                )
            repeat(FILAS) { indice ->
                val id = indice + 1L
                canal.clearBindings()
                canal.bindLong(1, id)
                canal.bindLong(2, 1L)
                canal.bindString(3, "Canal $id")
                canal.bindBlob(4, byteArrayOf(0))
                canal.bindBlob(5, byteArrayOf(0))
                canal.bindString(6, "clave:$id")
                canal.bindLong(7, 0L)
                canal.bindString(8, "A")
                canal.bindLong(9, 0L)
                canal.bindLong(10, 0L)
                canal.executeInsert()
            }

            val historial =
                soporte.compileStatement(
                    "INSERT INTO Historial (canalId, fecha) VALUES (?, ?)",
                )
            repeat(FILAS) { indice ->
                val id = indice + 1L
                historial.clearBindings()
                historial.bindLong(1, id)
                historial.bindLong(2, 1_700_000_000_000L + id)
                historial.executeInsert()
            }

            val favorito =
                soporte.compileStatement(
                    "INSERT INTO Favorito (canalId, creadoEn) VALUES (?, 0)",
                )
            val ocultacion =
                soporte.compileStatement(
                    "INSERT INTO OcultacionCanal (canalId, creadoEn) VALUES (?, 0)",
                )
            for (id in 1..FILAS) {
                if (id % 20 == 0) {
                    favorito.clearBindings()
                    favorito.bindLong(1, id.toLong())
                    favorito.executeInsert()
                }
                if (id % 25 == 0) {
                    ocultacion.clearBindings()
                    ocultacion.bindLong(1, id.toLong())
                    ocultacion.executeInsert()
                }
            }

            soporte.setTransactionSuccessful()
        } finally {
            soporte.endTransaction()
        }
    }

    private fun registrarPlan(
        nombre: String,
        plan: List<String>,
    ) {
        val detalle = plan.joinToString(separator = " | ")
        Log.i(TAG, "S11b/$nombre sqlite=$sqliteVersion plan=$detalle")
        println("S11b/$nombre sqlite=$sqliteVersion plan=$detalle")
    }

    private companion object {
        private const val TAG = "S11bPlanConsulta"
        private const val LIMITE = 10
        private const val FILAS = 10_000
        private const val INDICE_HISTORIAL = "index_Historial_fecha_canalId"
    }
}
