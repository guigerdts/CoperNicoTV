package com.copernicotv.app.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifica que el esquema v1 contiene exactamente las 26 tablas de §6.3.1.
 *
 * No se apoya en el JSON exportado: consulta `sqlite_master` de una base real
 * creada por Room, que es la fuente de verdad del esquema efectivo.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EsquemaRoomTest {
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
    fun `el esquema v1 tiene exactamente las 26 tablas normativas`() {
        val tablas = mutableSetOf<String>()
        db.openHelper.readableDatabase
            .query("SELECT name FROM sqlite_master WHERE type = 'table'")
            .use { cursor ->
                while (cursor.moveToNext()) {
                    tablas += cursor.getString(0)
                }
            }
        val deUsuario = tablas - TABLAS_INTERNAS
        assertEquals(TABLAS_ESPERADAS, deUsuario)
    }

    @Test
    fun `la version de esquema es 1`() {
        assertEquals(1, db.openHelper.readableDatabase.version)
    }

    private companion object {
        val TABLAS_INTERNAS = setOf("android_metadata", "room_master_table", "sqlite_sequence")

        val TABLAS_ESPERADAS =
            setOf(
                "Apariencia",
                "AparienciaClavePendiente",
                "Asociacion",
                "CacheM3U",
                "CacheXmltv",
                "Canal",
                "Coleccion",
                "ColeccionCanal",
                "DetalleAusencia",
                "EstadoActualizacion",
                "EstadoIndiceHmac",
                "EvidenciaColision",
                "Favorito",
                "Fuente",
                "FuenteGuia",
                "GeneracionGuia",
                "Grupo",
                "Historial",
                "HmacPendiente",
                "Lista",
                "ObservacionPendienteImportacion",
                "OcultacionCanal",
                "OcultacionGrupo",
                "Perfil",
                "PerfilLista",
                "Programa",
            )
    }
}
