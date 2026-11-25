package com.copernicotv.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.copernicotv.app.AlmacenClaveDatosEnMemoria
import com.copernicotv.app.ClavesEnMemoria
import com.copernicotv.app.crypto.ClavesDatos
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.core.conexion.FormatoSalidaXtream
import com.copernicotv.core.conexion.ProtocoloConexion
import com.copernicotv.core.conexion.TipoConexion
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * S4, S5 y S6: administración de conexiones sobre el esquema v5.
 *
 * Verifica lo que el contrato promete y una prueba de UI no puede ver: que la
 * conexión materialice una sola Fuente/Lista, que la edición conserve la misma
 * Lista (favoritos, historial, ocultos y asociaciones), que los secretos no
 * queden en claro en la base y que borrar el perfil borre sus BLOB.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConexionRepositorioTest {
    private lateinit var db: AppDatabase
    private val claves = ClavesEnMemoria()
    private val clavesDatos = ClavesDatos(claves, AlmacenClaveDatosEnMemoria())
    private val contexto: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun abrir() {
        contexto.deleteDatabase(NOMBRE_BD)
        db =
            Room
                .databaseBuilder(contexto, AppDatabase::class.java, NOMBRE_BD)
                .allowMainThreadQueries()
                .build()
    }

    @After
    fun cerrar() {
        runCatching { db.close() }
        contexto.deleteDatabase(NOMBRE_BD)
    }

    @Test
    fun `crea xtream y materializa fuente lista y guia`() {
        runBlocking {
            val resultado = repositorio().crearXtream(xtream("Casa"))
            val id = (resultado as ResultadoConexion.Creada).id

            val conexion = db.conexionDao().porId(id)
            assertNotNull(conexion)
            assertNotNull(conexion?.fuenteId)
            assertNotNull(conexion?.listaId)

            val fuente = db.fuenteDao().porId(conexion!!.fuenteId!!)
            assertEquals("Casa", fuente?.nombre)

            val lista = db.listaDao().porId(conexion.listaId!!)
            assertEquals("Casa", lista?.nombre)
            assertEquals("activa", lista?.estado)
            assertEquals("https", lista?.esquema)

            val guia = db.fuenteGuiaDao().porConexion(id)
            assertNotNull(guia)
            assertEquals("xtream", guia?.origen)
            assertEquals(id, guia?.conexionId)
        }
    }

    @Test
    fun `no guarda la contrasena ni la url de lista en claro`() {
        runBlocking {
            val resultado = repositorio().crearXtream(xtream("Casa"))
            val id = (resultado as ResultadoConexion.Creada).id

            val crudo = leerCrudo()
            assertFalse("La contraseña no debe aparecer en claro", crudo.contains(CONTRASENA))
            assertFalse("La URL de lista no debe aparecer en claro", crudo.contains("get.php"))
            assertFalse("El usuario viaja dentro de la URL cifrada", crudo.contains("get.php?username="))

            val detalle = repositorio().detalle(id)
            assertNotNull(detalle)
            assertTrue(detalle!!.passwordPresente)
            assertTrue(detalle.urlListaPresente)
            assertEquals("https://panel.example.com:8080/…", detalle.urlListaRedactada)
            assertFalse(detalle.urlListaRedactada!!.contains(CONTRASENA))
        }
    }

    @Test
    fun `editar conserva la lista y no duplica el catalogo`() {
        runBlocking {
            val repositorio = repositorio()
            val creada = repositorio.crearXtream(xtream("Casa")) as ResultadoConexion.Creada
            val antes = db.conexionDao().porId(creada.id)

            val editada =
                repositorio.editarXtream(
                    creada.id,
                    xtream("Casa nueva", servidor = "otro.example.com"),
                )
            assertEquals(ResultadoConexion.Actualizada, editada)

            val despues = db.conexionDao().porId(creada.id)
            assertEquals(antes?.fuenteId, despues?.fuenteId)
            assertEquals(antes?.listaId, despues?.listaId)
            assertEquals(1, db.fuenteDao().contar())
            assertEquals(1, db.listaDao().contar())
            assertEquals("Casa nueva", db.fuenteDao().porId(despues!!.fuenteId!!)?.nombre)
            assertEquals("otro.example.com", despues.servidor)
        }
    }

    @Test
    fun `editar sin contrasena conserva la guardada`() {
        runBlocking {
            val repositorio = repositorio()
            val creada = repositorio.crearXtream(xtream("Casa")) as ResultadoConexion.Creada

            val editada = repositorio.editarXtream(creada.id, xtream("Casa", contrasena = null))
            assertEquals(ResultadoConexion.Actualizada, editada)
            assertEquals(CONTRASENA, repositorio.datosXtream(creada.id)?.contrasena)
        }
    }

    @Test
    fun `rechaza un nombre de conexion duplicado`() {
        runBlocking {
            val repositorio = repositorio()
            repositorio.crearXtream(xtream("Casa"))

            val repetida = repositorio.crearXtream(xtream("Casa"))
            assertEquals(ResultadoConexion.Error(CodigosConexion.NOMBRE_DUPLICADO), repetida)
            assertEquals(1, db.conexionDao().contar())
        }
    }

    @Test
    fun `eliminar borra secretos materializacion y guia propia`() {
        runBlocking {
            val repositorio = repositorio()
            val creada = repositorio.crearXtream(xtream("Casa")) as ResultadoConexion.Creada

            assertEquals(ResultadoConexion.Eliminada, repositorio.eliminar(creada.id))

            assertEquals(0, db.conexionDao().contar())
            assertEquals(0, db.fuenteDao().contar())
            assertEquals(0, db.listaDao().contar())
            assertEquals(0, db.fuenteGuiaDao().contar())
            assertFalse(leerCrudo().contains(CONTRASENA))
        }
    }

    @Test
    fun `activar una conexion desactiva las demas y su lista`() {
        runBlocking {
            val repositorio = repositorio()
            val primera = repositorio.crearXtream(xtream("Casa")) as ResultadoConexion.Creada
            val segunda = repositorio.crearXtream(xtream("Playa", servidor = "playa.example.com")) as ResultadoConexion.Creada

            assertEquals(ResultadoConexion.Actualizada, repositorio.activar(segunda.id))

            val vistas = repositorio.observarTodas().first().associateBy { it.id }
            assertFalse(vistas.getValue(primera.id).activa)
            assertTrue(vistas.getValue(segunda.id).activa)

            val listas = db.listaDao().listar()
            assertEquals(1, listas.count { it.estado == "activa" })
            assertEquals(db.conexionDao().porId(segunda.id)?.listaId, listas.first { it.estado == "activa" }.id)
        }
    }

    @Test
    fun `desactivar la conexion activa activa otra lista`() {
        runBlocking {
            val repositorio = repositorio()
            repositorio.crearXtream(xtream("Casa"))
            val segunda = repositorio.crearXtream(xtream("Playa", servidor = "playa.example.com")) as ResultadoConexion.Creada

            assertEquals(ResultadoConexion.Actualizada, repositorio.desactivar(segunda.id))

            assertFalse(db.conexionDao().porId(segunda.id)!!.activa)
            val activas = db.listaDao().listar().filter { it.estado == "activa" }
            assertEquals(1, activas.size)
            assertTrue(activas.single().id != db.conexionDao().porId(segunda.id)?.listaId)
        }
    }

    @Test
    fun `m3u redacta la url y conserva la guia declarada`() {
        runBlocking {
            val repositorio = repositorio()
            val creada =
                repositorio.crearM3U(
                    FormularioM3U(
                        nombre = "Lista",
                        urlLista = "https://lists.example.com/playlist.m3u8?token=secreto-123",
                        urlGuia = "https://lists.example.com/guia.xml",
                    ),
                ) as ResultadoConexion.Creada

            val detalle = repositorio.detalle(creada.id)
            assertEquals(TipoConexion.M3U, detalle?.tipo)
            assertEquals("https://lists.example.com/…", detalle?.urlListaRedactada)
            assertEquals("https://lists.example.com/…", detalle?.urlGuiaRedactada)
            assertFalse(detalle?.urlListaRedactada!!.contains("secreto-123"))
            assertNotNull(db.fuenteGuiaDao().porConexion(creada.id))
            assertEquals("m3u", db.fuenteGuiaDao().porConexion(creada.id)?.origen)
            assertFalse(leerCrudo().contains("secreto-123"))
        }
    }

    private fun repositorio(): ConexionRepositorio {
        val indice = IndiceHmacRepositorio(db, claves)
        return ConexionRepositorio(db, claves, clavesDatos, indice)
    }

    private fun xtream(
        nombre: String,
        servidor: String = "panel.example.com",
        contrasena: String? = CONTRASENA,
    ): FormularioXtream =
        FormularioXtream(
            nombre = nombre,
            servidor = servidor,
            puerto = 8080,
            protocolo = ProtocoloConexion.HTTPS,
            usuario = "usuario-demo",
            contrasena = contrasena,
            formatoSalida = FormatoSalidaXtream.M3U8,
        )

    /**
     * Lee las columnas sensibles como bytes.
     *
     * Es la única forma de comprobar que el secreto no quedó en claro: `detalle`
     * ya lo redacta, así que una prueba sobre la API no probaría nada.
     */
    private fun leerCrudo(): String {
        val cursor =
            db.openHelper.readableDatabase.query(
                "SELECT nombre, servidor, usuario, CAST(passwordCifrada AS TEXT), " +
                    "CAST(urlListaCifrada AS TEXT), CAST(urlGuiaCifrada AS TEXT) FROM Conexion",
            )
        val texto = StringBuilder()
        cursor.use {
            while (it.moveToNext()) {
                for (columna in 0 until it.columnCount) {
                    texto.append(it.getString(columna) ?: "")
                    texto.append('|')
                }
            }
        }
        return texto.toString()
    }

    private companion object {
        const val NOMBRE_BD = "conexiones.db"
        const val CONTRASENA = "s3cr3t0-unica-9f"
    }
}
