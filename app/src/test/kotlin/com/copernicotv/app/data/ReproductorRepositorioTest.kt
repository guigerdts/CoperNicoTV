package com.copernicotv.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.copernicotv.app.ClavesEnMemoria
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.core.crypto.CifradoAes
import com.copernicotv.core.player.ProtocoloStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Resolución de la URL reproducible de un Canal (E6-T1) sobre Room real:
 * descifrado, canonicalización y clasificación sin exponer la URL en errores.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReproductorRepositorioTest {
    private lateinit var db: AppDatabase
    private lateinit var catalogo: CatalogoRepositorio
    private lateinit var reproductor: ReproductorRepositorio
    private val claves = ClavesEnMemoria()
    private val contexto: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun abrir() {
        db =
            Room
                .inMemoryDatabaseBuilder(contexto, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        catalogo = CatalogoRepositorio(db, claves, contexto)
        reproductor = ReproductorRepositorio(db, claves)
    }

    @After
    fun cerrar() {
        db.close()
    }

    @Test
    fun `un canal HLS devuelve su url descifrada y clasificada`() {
        runBlocking {
            val canalId = importarCanal("http://example.invalid/directo/canal.m3u8")

            val resultado = reproductor.resolver(canalId)

            val stream = (resultado as ResultadoStream.Ok).stream
            assertEquals("http://example.invalid/directo/canal.m3u8", stream.url)
            assertEquals(ProtocoloStream.HLS, stream.protocolo)
            assertEquals("Canal E6", stream.nombre)
        }
    }

    @Test
    fun `un MP4 progresivo conserva la query y no se confunde con HLS`() {
        runBlocking {
            val canalId = importarCanal("https://example.invalid/vod/pelicula.mp4?token=abc")

            val stream = (reproductor.resolver(canalId) as ResultadoStream.Ok).stream

            assertEquals("https://example.invalid/vod/pelicula.mp4?token=abc", stream.url)
            assertEquals(ProtocoloStream.PROGRESIVO, stream.protocolo)
        }
    }

    @Test
    fun `un canal RTSP se resuelve sin pasar por el motor HTTP`() {
        runBlocking {
            val canalId = importarCanal("rtsp://example.invalid:554/canal")

            val stream = (reproductor.resolver(canalId) as ResultadoStream.Ok).stream

            assertEquals(ProtocoloStream.RTSP, stream.protocolo)
        }
    }

    @Test
    fun `un canal RTMP se rechaza con codigo propio y sin declarar soporte`() {
        runBlocking {
            val canalId = importarCanal("rtmp://example.invalid/live/canal")

            val resultado = reproductor.resolver(canalId)

            assertEquals(
                "stream_rtmp_no_soportado",
                (resultado as ResultadoStream.Error).codigo,
            )
        }
    }

    @Test
    fun `un canal inexistente devuelve canal_no_existe`() {
        runBlocking {
            val resultado = reproductor.resolver(9999L)

            assertEquals("canal_no_existe", (resultado as ResultadoStream.Error).codigo)
        }
    }

    @Test
    fun `un sobre cifrado ilegible devuelve url_ilegible sin filtrar contenido`() {
        runBlocking {
            val canalId = importarCanal("http://example.invalid/canal.m3u8")
            val canal = requireNotNull(db.canalDao().porId(canalId))
            db.canalDao().actualizar(canal.copy(urlStreamCifrada = byteArrayOf(0x01)))

            val resultado = reproductor.resolver(canalId)

            assertEquals("url_ilegible", (resultado as ResultadoStream.Error).codigo)
        }
    }

    @Test
    fun `un esquema no admitido para canal se rechaza con el codigo canonico`() {
        runBlocking {
            val canalId = importarCanal("http://example.invalid/canal.m3u8")
            val canal = requireNotNull(db.canalDao().porId(canalId))
            val cifrada =
                CifradoAes.cifrar(
                    claves.claveAes,
                    CifradoAes.aad("Canal", "url_stream_cifrada"),
                    "content://ajeno/canal".toByteArray(),
                )
            db.canalDao().actualizar(canal.copy(urlStreamCifrada = cifrada))

            val resultado = reproductor.resolver(canalId)

            assertEquals("url_esquema_no_admitido", (resultado as ResultadoStream.Error).codigo)
        }
    }

    private suspend fun importarCanal(url: String): Long {
        val texto = "#EXTM3U\n#EXTINF:-1 tvg-id=\"e6\",Canal E6\n$url\n"
        catalogo.importar(texto, "Fuente E6", "http://example.invalid/lista.m3u")
        val fuente = requireNotNull(db.fuenteDao().porNombre("Fuente E6"))
        return db.canalDao().porFuente(fuente.id).single().id
    }
}
