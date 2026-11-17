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
 * Migraciones del diagnóstico persistente (E7-T10, S6a y T7).
 *
 * Valida el esquema migrado contra los JSON exportados y comprueba que las
 * filas de las versiones previas sobreviven intactas.
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

    @Test
    fun laMigracion2a3AnadeLasColumnasDeVideoDetectadoSinPerderDatos() {
        val nombre = "prueba-migracion-diagnostico-v3.db"
        helper.createDatabase(nombre, 2).use { v2 ->
            v2.execSQL(
                "INSERT INTO SesionDiagnostico (inicio, recuperacionesRed, recuperacionesImagen) " +
                    "VALUES (700, 0, 0)",
            )
        }
        helper.runMigrationsAndValidate(nombre, 3, true, Migraciones.MIGRACION_2_3).use { v3 ->
            // La fila v2 sobrevive y las columnas nuevas aceptan valores.
            v3.execSQL(
                "UPDATE SesionDiagnostico SET pistaVideoDetectadaMime = 'video/mpeg2', " +
                    "pistaVideoDetectadaCodec = 'video/mpeg2', pistaVideoSoportada = 0 " +
                    "WHERE inicio = 700",
            )
            v3.query(
                "SELECT pistaVideoDetectadaMime, pistaVideoSoportada " +
                    "FROM SesionDiagnostico WHERE inicio = 700",
            ).use { cursor ->
                assertEquals(1, cursor.count)
                cursor.moveToFirst()
                assertEquals("video/mpeg2", cursor.getString(0))
                assertEquals(0, cursor.getInt(1))
            }
        }
    }

    @Test
    fun laMigracion3a4AnadeLasColumnasDelInventarioDeDecodersSinPerderDatos() {
        val nombre = "prueba-migracion-diagnostico-v4.db"
        helper.createDatabase(nombre, 3).use { v3 ->
            v3.execSQL(
                "INSERT INTO SesionDiagnostico (inicio, recuperacionesRed, recuperacionesImagen) " +
                    "VALUES (900, 0, 0)",
            )
        }
        helper.runMigrationsAndValidate(nombre, 4, true, Migraciones.MIGRACION_3_4).use { v4 ->
            // La fila v3 sobrevive y las columnas nuevas aceptan valores.
            v4.execSQL(
                "UPDATE SesionDiagnostico SET decoderVideoSeleccionado = 'c2.exynos.h264.decoder', " +
                    "tipoDecoderVideo = 'HARDWARE', motivoSoporteVideo = 'SOPORTADO', " +
                    "perfilNivelVideo = 'perfil 0x64 nivel 0x28' WHERE inicio = 900",
            )
            v4.query(
                "SELECT decoderVideoSeleccionado, tipoDecoderVideo, motivoSoporteVideo, perfilNivelVideo " +
                    "FROM SesionDiagnostico WHERE inicio = 900",
            ).use { cursor ->
                assertEquals(1, cursor.count)
                cursor.moveToFirst()
                assertEquals("c2.exynos.h264.decoder", cursor.getString(0))
                assertEquals("HARDWARE", cursor.getString(1))
                assertEquals("SOPORTADO", cursor.getString(2))
                assertEquals("perfil 0x64 nivel 0x28", cursor.getString(3))
            }
        }
    }
}
