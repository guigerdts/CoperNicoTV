package com.copernicotv.app

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.app.data.db.Migraciones
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Migración 1→2 del diagnóstico persistente (E7-T10, S6a).
 *
 * Valida el esquema migrado contra el JSON exportado de la v2 y comprueba
 * que las filas de la v1 sobreviven intactas.
 */
class MigracionDiagnosticoTest {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            AppDatabase::class.java,
            emptyList(),
            FrameworkSQLiteOpenHelperFactory(),
        )

    @Test
    fun laMigracion1a2CreaLasTablasDeDiagnosticoSinPerderDatos() {
        val nombre = "prueba-migracion-diagnostico.db"
        helper.createDatabase(nombre, 1).use { v1 ->
            v1.execSQL("INSERT INTO Historial (canalId, fecha) VALUES (1, 1000)")
        }
        helper.runMigrationsAndValidate(nombre, 2, true, Migraciones.MIGRACION_1_2).use { v2 ->
            v2.query("SELECT canalId, fecha FROM Historial").use { cursor ->
                assertEquals(1, cursor.count)
                cursor.moveToFirst()
                assertEquals(1, cursor.getInt(0))
                assertEquals(1000L, cursor.getLong(1))
            }
            // Las tablas nuevas aceptan filas y sus índices responden.
            v2.execSQL(
                "INSERT INTO SesionDiagnostico (inicio, recuperacionesRed, recuperacionesImagen) " +
                    "VALUES (500, 0, 0)",
            )
            v2.query("SELECT canalId FROM SesionDiagnostico WHERE canalId = 1").use { cursor ->
                assertEquals(0, cursor.count)
            }
        }
    }
}
