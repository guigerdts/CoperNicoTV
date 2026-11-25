package com.copernicotv.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.copernicotv.app.AlmacenClaveDatosEnMemoria
import com.copernicotv.app.ClavesEnMemoria
import com.copernicotv.app.crypto.ClavesDatos
import com.copernicotv.app.data.cache.Descargador
import com.copernicotv.app.data.cache.ResultadoDescarga
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.core.epg.LimitesRetencion
import com.copernicotv.core.epg.PoliticaReintentoGuia
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.zip.GZIPOutputStream

/**
 * S7 (H-06): alta, edición, baja, activación y actualización de guías EPG.
 *
 * Verifica la ruta que faltaba en producción: hasta ahora `FuenteGuia` solo se
 * sembraba desde pruebas, así que la guía nunca podía publicarse. Aquí se
 * ejercita la interfaz pública del repositorio con un descargador falso: la URL
 * guardada nunca se lee en claro, el XMLTV comprimido se importa, y un fallo de
 * red no borra la guía publicada ni se reintenta antes de tiempo.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AdministracionGuiaRepositorioTest {
    private lateinit var db: AppDatabase
    private val claves = ClavesEnMemoria()
    private val clavesDatos = ClavesDatos(claves, AlmacenClaveDatosEnMemoria())
    private val contexto: Context = ApplicationProvider.getApplicationContext()
    private val descargador = DescargadorFalso()
    private val politica = PoliticaReintentoGuia.PREDETERMINADA

    @Before
    fun abrir() {
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
    fun `el alta guarda la url cifrada y no en claro`() {
        runBlocking {
            val resultado = repositorio().crear(FormularioGuia(nombre = "Casa", url = URL_GUIA))
            val id = (resultado as ResultadoGuia.Ok).id

            val fila = db.fuenteGuiaDao().porId(id)
            assertNotNull(fila)
            assertEquals("manual", fila?.origen)
            assertEquals("sin_actualizar", fila?.estado)
            assertEquals(true, fila?.activa)
            assertNotNull(fila?.urlHmac)

            val enClaro = textoCrudo(id)
            assertFalse("La URL no puede quedar en claro", enClaro.contains(URL_GUIA))
        }
    }

    @Test
    fun `rechaza nombre vacio y url invalida`() {
        runBlocking {
            val sinNombre = repositorio().crear(FormularioGuia(nombre = "  ", url = URL_GUIA))
            assertEquals(ResultadoGuia.Error("nombre_vacio"), sinNombre)

            val sinUrl = repositorio().crear(FormularioGuia(nombre = "Casa", url = " "))
            assertEquals(ResultadoGuia.Error("url_requerida"), sinUrl)

            val mala = repositorio().crear(FormularioGuia(nombre = "Casa", url = "ftp://host/guia.xml"))
            assertEquals(ResultadoGuia.Error("url_invalida"), mala)
        }
    }

    @Test
    fun `editar sin url conserva la guardada`() {
        runBlocking {
            val id = (repositorio().crear(FormularioGuia(nombre = "Casa", url = URL_GUIA)) as ResultadoGuia.Ok).id
            val editado =
                repositorio().editar(
                    id,
                    FormularioGuia(nombre = "Casa 2", url = "", actualizacionAutomatica = true, intervaloHoras = 6),
                )
            assertTrue(editado is ResultadoGuia.Ok)

            descargador.respuesta = { destino -> escribir(destino, XMLTV) }
            repositorio().actualizarAhora(id)

            assertEquals(listOf(URL_GUIA), descargador.urls)
            val fila = db.fuenteGuiaDao().porId(id)
            assertEquals("Casa 2", fila?.nombre)
            assertEquals(true, fila?.actualizacionAutomatica)
            assertEquals(6L, fila?.intervaloHoras)
        }
    }

    @Test
    fun `importa xmltv comprimido y persiste la guia enriquecida`() {
        runBlocking {
            val id = (repositorio().crear(FormularioGuia(nombre = "Casa", url = URL_GUIA)) as ResultadoGuia.Ok).id
            descargador.respuesta = { destino -> escribirComprimido(destino, XMLTV) }

            val resultado = repositorio().actualizarAhora(id)
            assertTrue(resultado is ResultadoActualizacionGuia.Exitosa)
            assertEquals(1, (resultado as ResultadoActualizacionGuia.Exitosa).programas)

            val fila = db.fuenteGuiaDao().porId(id)
            assertEquals("exitosa", fila?.estado)
            assertNotNull(fila?.generacionPublicadaId)
            assertEquals(0L, fila?.intentosFallidos)

            val programas =
                db.programaDao().enIntervalo(
                    fila!!.generacionPublicadaId!!,
                    "c1",
                    Instant.parse("2026-11-25T00:00:00Z"),
                    Instant.parse("2026-11-26T00:00:00Z"),
                )
            assertEquals(1, programas.size)
            assertEquals("Edición mediodía", programas[0].subtitulo)
            assertEquals("Informativo", programas[0].categoria)
            assertEquals("S1E2", programas[0].episodeNum)
            assertEquals("es", programas[0].idioma)
        }
    }

    @Test
    fun `un fallo de red registra diagnostico y no borra la guia publicada`() {
        runBlocking {
            val id = (repositorio().crear(FormularioGuia(nombre = "Casa", url = URL_GUIA)) as ResultadoGuia.Ok).id
            descargador.respuesta = { destino -> escribir(destino, XMLTV) }
            repositorio().actualizarAhora(id)
            val publicada = db.fuenteGuiaDao().porId(id)?.generacionPublicadaId
            assertNotNull(publicada)

            descargador.respuesta = { ResultadoDescarga.Fallo("http_5xx") }
            val fallo = repositorio().actualizarAhora(id)
            assertTrue(fallo is ResultadoActualizacionGuia.Reintentar)
            assertEquals("http_5xx", (fallo as ResultadoActualizacionGuia.Reintentar).codigo)

            val fila = db.fuenteGuiaDao().porId(id)
            assertEquals("fallida", fila?.estado)
            assertEquals("http_5xx", fila?.ultimoErrorCodigo)
            assertNotNull(fila?.ultimoErrorMensaje)
            assertEquals(1L, fila?.intentosFallidos)
            assertEquals("La última guía válida no se toca", publicada, fila?.generacionPublicadaId)
        }
    }

    @Test
    fun `un rechazo del servidor es fallo permanente`() {
        runBlocking {
            val id = (repositorio().crear(FormularioGuia(nombre = "Casa", url = URL_GUIA)) as ResultadoGuia.Ok).id
            descargador.respuesta = { ResultadoDescarga.Fallo("http_4xx") }

            val resultado = repositorio().actualizarAhora(id)
            assertTrue(resultado is ResultadoActualizacionGuia.Fallo)
        }
    }

    @Test
    fun `una guia desactivada no entra en la pasada automatica`() {
        runBlocking {
            val id =
                crearGuia(
                    FormularioGuia(nombre = "Casa", url = URL_GUIA, activa = false, actualizacionAutomatica = true),
                )
            assertTrue(repositorio().cambiarActiva(id, true))
            assertEquals(true, db.fuenteGuiaDao().porId(id)?.activa)
            assertTrue(repositorio().cambiarActiva(id, false))
            assertEquals(false, db.fuenteGuiaDao().porId(id)?.activa)

            val resumen = repositorio().procesarPendientes()
            assertEquals(0, resumen.ejecutadas)
            assertTrue(descargador.urls.isEmpty())
        }
    }

    @Test
    fun `la pasada automatica respeta el intervalo y la espera del reintento`() {
        runBlocking {
            val id =
                crearGuia(
                    FormularioGuia(
                        nombre = "Casa",
                        url = URL_GUIA,
                        actualizacionAutomatica = true,
                        intervaloHoras = 1,
                    ),
                )
            descargador.respuesta = { ResultadoDescarga.Fallo("timeout") }

            val primera = repositorio().procesarPendientes()
            assertEquals(1, primera.ejecutadas)
            assertEquals(1, primera.reintentables)

            val segunda = repositorio().procesarPendientes()
            assertEquals("La espera del reintento evita golpear al servidor", 0, segunda.ejecutadas)

            val fila = db.fuenteGuiaDao().porId(id)
            assertEquals(1L, fila?.intentosFallidos)
        }
    }

    @Test
    fun `eliminar borra la guia y sus generaciones`() {
        runBlocking {
            val id = (repositorio().crear(FormularioGuia(nombre = "Casa", url = URL_GUIA)) as ResultadoGuia.Ok).id
            descargador.respuesta = { destino -> escribir(destino, XMLTV) }
            repositorio().actualizarAhora(id)

            assertTrue(repositorio().eliminar(id))
            assertNull(db.fuenteGuiaDao().porId(id))
            assertEquals(0, db.fuenteGuiaDao().contar())
        }
    }

    @Test
    fun `la vista no expone la url ni los intentos crudos`() {
        runBlocking {
            val id = (repositorio().crear(FormularioGuia(nombre = "Casa", url = URL_GUIA)) as ResultadoGuia.Ok).id
            val vista = repositorio().observarTodas().first().single()

            assertEquals(id, vista.id)
            assertEquals("Casa", vista.nombre)
            assertTrue(vista.activa)
            assertTrue(vista.urlPresente)
            assertFalse(vista.publicada)
            assertTrue(vista.puedeReintentar)
            assertNull(vista.ultimoErrorCodigo)
        }
    }

    @Test
    fun `una guia agotada no se reintenta automaticamente`() {
        runBlocking {
            val id =
                crearGuia(
                    FormularioGuia(nombre = "Casa", url = URL_GUIA, actualizacionAutomatica = true, intervaloHoras = 1),
                )
            descargador.respuesta = { ResultadoDescarga.Fallo("http_4xx") }
            repositorio().actualizarAhora(id)
            db.fuenteGuiaDao().fijarEstado(
                id = id,
                estado = "fallida",
                ultimaActualizacionEn = null,
                codigo = "http_4xx",
                mensaje = "rechazada",
                intentos = politica.maxIntentos.toLong(),
                ahora = Instant.now().minus(Duration.ofDays(30)),
            )

            assertEquals(0, repositorio().procesarPendientes().ejecutadas)
            assertFalse(repositorio().observarTodas().first().single().puedeReintentar)
        }
    }

    private suspend fun crearGuia(formulario: FormularioGuia): Long = (repositorio().crear(formulario) as ResultadoGuia.Ok).id

    private fun repositorio(): AdministracionGuiaRepositorio {
        val indice = IndiceHmacRepositorio(db, claves)
        return AdministracionGuiaRepositorio(
            db = db,
            claves = claves,
            clavesDatos = clavesDatos,
            indice = indice,
            epg = EpgRepositorio(db, indice, PreferenciasRetencionFijas()),
            contexto = contexto,
            descargador = descargador,
            politica = politica,
        )
    }

    /** Consulta cruda: ninguna columna de la fila puede contener la URL en claro. */
    private fun textoCrudo(id: Long): String {
        val cursor = db.query("SELECT * FROM FuenteGuia WHERE id = ?", arrayOf<Any>(id))
        return cursor.use {
            it.moveToFirst()
            buildString {
                for (columna in 0 until it.columnCount) {
                    append(runCatching { it.getString(columna) }.getOrNull().orEmpty())
                    append('|')
                }
            }
        }
    }

    private fun escribir(
        destino: File,
        texto: String,
    ): ResultadoDescarga {
        destino.writeText(texto)
        return ResultadoDescarga.Ok(destino.length())
    }

    private fun escribirComprimido(
        destino: File,
        texto: String,
    ): ResultadoDescarga {
        GZIPOutputStream(destino.outputStream()).use { salida -> salida.write(texto.toByteArray()) }
        return ResultadoDescarga.Ok(destino.length())
    }

    private class DescargadorFalso : Descargador {
        val urls = mutableListOf<String>()
        var respuesta: (File) -> ResultadoDescarga = { ResultadoDescarga.Fallo("sin_conexion") }

        override suspend fun descargar(
            url: String,
            destino: File,
        ): ResultadoDescarga {
            urls += url
            return respuesta(destino)
        }
    }

    private class PreferenciasRetencionFijas : PreferenciasRetencion {
        override fun limites(): Flow<LimitesRetencion> = flowOf(LimitesRetencion.GLOBAL)

        override suspend fun actuales(): LimitesRetencion = LimitesRetencion.GLOBAL

        override suspend fun guardar(limites: LimitesRetencion) = Unit

        override suspend fun restablecer() = Unit
    }

    private companion object {
        const val URL_GUIA = "https://guia.example.com/xmltv.xml"

        val XMLTV: String =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv>
              <channel id="c1"><display-name>Uno</display-name></channel>
              <programme start="20261125120000 +0000" stop="20261125130000 +0000" channel="c1">
                <title lang="es">Noticias</title>
                <sub-title>Edición mediodía</sub-title>
                <desc>Resumen</desc>
                <category>Informativo</category>
                <episode-num system="onscreen">S1E2</episode-num>
              </programme>
            </tv>
            """.trimIndent()
    }
}
