package com.copernicotv.core.m3u

import com.copernicotv.core.identity.CodigoErrorUrl
import com.copernicotv.core.identity.ResultadoSobre
import com.copernicotv.core.identity.SobreHmac
import com.copernicotv.core.modelo.FilaCanal
import com.copernicotv.core.modelo.InvariantesCanal
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImportadorCanarioTest {
    private val clave = SecretKeySpec(ByteArray(32) { (it * 7).toByte() }, "HmacSHA256")
    private val fixture =
        """
        #EXTM3U
        #EXTINF:-1 tvg-id="canal-1" group-title="Noticias",Canal Uno
        http://example.com/uno.m3u8
        #EXTINF:-1 group-title="Deportes",Canal Dos
        https://example.com/dos.m3u8
        #EXTINF:-1 group-title="Sin ID",Canal Tres
        https://example.com/tres.m3u8
        """.trimIndent()

    @Test
    fun `fixture produce tres canales en orden`() {
        val resultado = ImportadorCanario.importar(fixture, clave)
        assertNull(resultado.error)
        assertEquals(3, resultado.canales.size)
        assertEquals(listOf("Canal Uno", "Canal Dos", "Canal Tres"), resultado.canales.map { it.nombre })
        assertTrue(resultado.rechazadas.isEmpty())
    }

    @Test
    fun `tvg id unico produce clave de unicidad tvg`() {
        val canal = ImportadorCanario.importar(fixture, clave).canales.first()
        assertEquals("tvg:v1:canal-1", canal.claveUnicidad)
        assertNotNull(canal.claveCanonica)
        assertTrue(canal.claveDerivable)
    }

    @Test
    fun `sin tvg id la clave de unicidad es la canonica`() {
        val canal = ImportadorCanario.importar(fixture, clave).canales[1]
        assertEquals(canal.claveCanonica, canal.claveUnicidad)
    }

    @Test
    fun `hmac usa sobre versionado cv1`() {
        val canal = ImportadorCanario.importar(fixture, clave).canales.first()
        val sobre = SobreHmac.parsear(canal.urlStreamHmac)
        assertTrue(sobre is ResultadoSobre.Versionado)
        assertEquals(ImportadorCanario.GENERACION_HMAC, (sobre as ResultadoSobre.Versionado).generacion)
        assertEquals(1, sobre.versionCanonica)
    }

    @Test
    fun `url no admitida se rechaza y no produce canal`() {
        val resultado = ImportadorCanario.importar("#EXTM3U\n#EXTINF:-1,Canal\nftp://example.com/a", clave)
        assertTrue(resultado.canales.isEmpty())
        assertEquals(CodigoErrorUrl.URL_ESQUEMA_NO_ADMITIDO, resultado.rechazadas.single().error.codigo)
    }

    @Test
    fun `mismo nombre y distinta url producen claves distintas`() {
        val texto =
            "#EXTM3U\n" +
                "#EXTINF:-1,Canal\nhttp://example.com/a\n" +
                "#EXTINF:-1,Canal\nhttp://example.com/b"
        val canales = ImportadorCanario.importar(texto, clave).canales
        assertEquals(2, canales.size)
        assertEquals(2, canales.mapNotNull { it.claveCanonica }.toSet().size)
    }

    @Test
    fun `canal sin nombre ni tvg no inventa identidad y cumple invariantes`() {
        val resultado = ImportadorCanario.importar("#EXTM3U\n#EXTINF:-1,   \nhttp://example.com/a", clave)
        val canal = resultado.canales.single()
        assertNull(canal.nombre)
        assertNull(canal.claveCanonica)
        assertNull(canal.claveUnicidad)
        assertEquals(false, canal.claveDerivable)
        val violaciones =
            InvariantesCanal.validar(
                FilaCanal(
                    nombre = canal.nombre,
                    estado = com.copernicotv.core.identity.EstadoCanal.A,
                    claveDerivable = canal.claveDerivable,
                    claveCanonica = canal.claveCanonica,
                    claveUnicidad = canal.claveUnicidad,
                    tvgId = canal.tvgId,
                    urlStreamCifradaPresente = true,
                    urlStreamHmacPresente = true,
                ),
                tvgIdUnicoEnFuente = false,
            )
        assertTrue(violaciones.isEmpty(), "violaciones: $violaciones")
    }

    @Test
    fun `url canonica es idempotente`() {
        val canal = ImportadorCanario.importar(fixture, clave).canales.first()
        val texto = "#EXTM3U\n#EXTINF:-1,Canal Uno\n${canal.urlCanonica}"
        val otra = ImportadorCanario.importar(texto, clave).canales.single()
        assertEquals(canal.urlCanonica, otra.urlCanonica)
    }

    @Test
    fun `declaraciones identicas colapsan en un canal y una apariencia`() {
        val texto =
            "#EXTM3U\n" +
                "#EXTINF:-1 group-title=\"A\",Canal\nhttp://example.com/a\n" +
                "#EXTINF:-1 group-title=\"A\",Canal\nhttp://example.com/a"
        val resultado = ImportadorCanario.importar(texto, clave)
        assertEquals(1, resultado.canales.size)
        assertEquals(1, resultado.canales.single().apariencias.size)
        assertEquals(1, resultado.canales.single().apariencias.single().repeticiones)
    }
}
