package com.copernicotv.core.m3u

import com.copernicotv.core.identity.AtributoExtra
import com.copernicotv.core.identity.AvanceEtapaImportacion
import com.copernicotv.core.identity.CodigoErrorUrl
import com.copernicotv.core.identity.EtapaImportacion
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ImportadorM3UTest {
    private val clave = SecretKeySpec(ByteArray(32) { (it * 7).toByte() }, "HmacSHA256")

    @Test
    fun `importa metadatos logo y atributos extra`() {
        val texto =
            """
            #EXTM3U
            #EXTINF:-1 tvg-id="uno" tvg-name="Uno HD" group-title="Noticias" tvg-logo="https://example.com/uno.png" custom="x",Canal Uno
            https://example.com/uno.m3u8
            """.trimIndent()
        val resultado = ImportadorM3U.importar(texto, clave)
        assertEquals(1, resultado.canales.size)
        val canal = resultado.canales.single()
        assertEquals("Canal Uno", canal.nombre)
        assertEquals("uno", canal.tvgId)
        assertEquals("https://example.com/uno.m3u8", canal.urlOriginal)
        assertTrue(canal.urlStreamHmac.startsWith("hmac:v1:cv1:"))
        assertNotNull(canal.logoOriginal)
        assertNotNull(canal.logoHmac)
        assertEquals(listOf(AtributoExtra("custom", "x")), canal.atributosExtra)
        assertEquals(1, canal.apariencias.size)
        assertEquals("Noticias", canal.apariencias.single().groupTitle)
        assertEquals("Uno HD", canal.apariencias.single().tvgName)
    }

    /** E7-T7: la sobrecarga por parseo previo produce el mismo resultado que el texto. */
    @Test
    fun `la sobrecarga con parseo previo equivale al camino por texto`() {
        val texto =
            "#EXTM3U\n" +
                "#EXTINF:-1 tvg-id=\"a\" group-title=\"G\",Canal A\n" +
                "https://example.com/a.ts\n" +
                "#EXTINF:-1,Canal B\n" +
                "https://example.com/b.m3u8\n"
        val porTexto = ImportadorM3U.importar(texto, clave)
        val porParseo = ImportadorM3U.importar(ParserM3U.parsear(texto), clave)

        assertEquals(porTexto.canales.size, porParseo.canales.size)
        assertEquals(
            porTexto.canales.map { Triple(it.nombre, it.urlOriginal, it.urlStreamHmac) },
            porParseo.canales.map { Triple(it.nombre, it.urlOriginal, it.urlStreamHmac) },
        )
        assertEquals(porTexto.diagnosticos, porParseo.diagnosticos)
    }

    @Test
    fun `una linea excedida descarta solo esa entrada`() {
        val larga = "x".repeat(200)
        val texto =
            "#EXTM3U\n" +
                "#EXTINF:-1,$larga\n" +
                "https://example.com/a\n" +
                "#EXTINF:-1,Bien\n" +
                "https://example.com/b\n"
        val resultado =
            ImportadorM3U.importar(
                texto,
                clave,
                LimitesParserM3U(maxLongitudLinea = 64),
            )
        assertEquals(1, resultado.canales.size)
        assertEquals("Bien", resultado.canales.single().nombre)
        assertEquals(1, resultado.diagnosticos.count { it.codigo == CodigoM3U.LINEA_EXCEDIDA })
    }

    @Test
    fun `declaraciones del mismo canal se agrupan en una apariencia por grupo`() {
        val texto =
            "#EXTM3U\n" +
                "#EXTINF:-1 group-title=\"A\",Canal\n" +
                "https://example.com/x\n" +
                "#EXTINF:-1 group-title=\"B\",Canal\n" +
                "https://example.com/x\n"
        val resultado = ImportadorM3U.importar(texto, clave)
        assertEquals(1, resultado.canales.size)
        assertEquals(2, resultado.canales.single().apariencias.size)
    }

    @Test
    fun `una url invalida se rechaza con codigo tipado sin perder el resto`() {
        val texto =
            "#EXTM3U\n" +
                "#EXTINF:-1,Roto\n" +
                "no-es-url\n" +
                "#EXTINF:-1,Bien\n" +
                "https://example.com/b\n"
        val resultado = ImportadorM3U.importar(texto, clave)
        assertEquals(1, resultado.canales.size)
        assertEquals(1, resultado.rechazadas.size)
        assertEquals(CodigoErrorUrl.URL_MALFORMADA, resultado.rechazadas.single().error.codigo)
    }

    @Test
    fun `un tvg id unico habilita la clave de unicidad`() {
        val texto =
            "#EXTM3U\n" +
                "#EXTINF:-1 tvg-id=\"unico\",Canal\n" +
                "https://example.com/x\n"
        val canal = ImportadorM3U.importar(texto, clave).canales.single()
        assertEquals("tvg:v1:unico", canal.claveUnicidad)
        assertTrue(canal.claveDerivable)
    }

    @Test
    fun `una lista sin entradas produce un resultado vacio`() {
        val resultado = ImportadorM3U.importar("#EXTM3U\n", clave)
        assertEquals(0, resultado.canales.size)
        assertEquals(0, resultado.rechazadas.size)
        assertTrue(resultado.errores.isEmpty())
    }

    @Test
    fun `puede sellar con una generacion hmac distinta de la inicial`() {
        val texto =
            "#EXTM3U\n" +
                "#EXTINF:-1 tvg-logo=\"https://example.com/l.png\",Canal\n" +
                "https://example.com/x\n"
        val canal = ImportadorM3U.importar(texto, clave, generacionHmac = 7).canales.single()
        assertTrue(canal.urlStreamHmac.startsWith("hmac:v7:cv1:"))
        assertNotNull(canal.logoHmac)
        assertTrue(canal.logoHmac.startsWith("hmac:v7:cv1:"))
    }

    /** E7-T7: lectura por pasos equivale al camino por texto, diagnósticos incluidos. */
    @Test
    fun `leerDeclaraciones e importarDeclaraciones equivalen al camino por texto`() {
        val texto =
            "#EXTM3U\n" +
                "#EXTINF:-1 tvg-id=\"a\" group-title=\"G\",Canal A\n" +
                "https://example.com/a.ts\n" +
                "#EXTINF:-1,Canal B\n" +
                "https://example.com/b.m3u8\n" +
                "#EXTINF:-1,Rechazada\n" +
                "no-es-una-url\n"
        val lectura = ImportadorM3U.leerDeclaraciones(texto.byteInputStream())
        val porPasos =
            ImportadorM3U
                .importarDeclaraciones(lectura.declaraciones, clave)
                .copy(diagnosticos = lectura.diagnosticos)
        val porTexto = ImportadorM3U.importar(texto, clave)

        // Los tiempos por etapa (S28) son mediciones de la corrida: se comparan
        // los campos semánticos, no el objeto completo.
        assertEquals(porTexto.canales, porPasos.canales)
        assertEquals(porTexto.rechazadas, porPasos.rechazadas)
        assertEquals(porTexto.colisiones, porPasos.colisiones)
        assertEquals(porTexto.diagnosticos, porPasos.diagnosticos)
        assertEquals(porTexto.repeticionesInformativas, porPasos.repeticionesInformativas)
        assertEquals(porTexto.declaracionesRecibidas, porPasos.declaracionesRecibidas)
        assertEquals(porTexto.operacionesCriptograficas, porPasos.operacionesCriptograficas)
    }

    @Test
    fun `el avance reporta entradas emitidas y canales procesados`() {
        val texto =
            "#EXTM3U\n" +
                "#EXTINF:-1,Canal A\nhttps://example.com/a\n" +
                "#EXTINF:-1,Canal B\nhttps://example.com/b\n" +
                "#EXTINF:-1,Canal C\nhttps://example.com/c\n"
        val avancesEntradas = mutableListOf<Int>()
        val lectura =
            ImportadorM3U.leerDeclaraciones(texto.byteInputStream()) { total ->
                avancesEntradas += total
            }
        assertEquals(listOf(1, 2, 3), avancesEntradas)
        assertEquals(3, lectura.entradas)

        val avancesCanales = mutableListOf<Int>()
        ImportadorM3U.importarDeclaraciones(lectura.declaraciones, clave) { total ->
            avancesCanales += total
        }
        assertEquals(listOf(1, 2, 3), avancesCanales)
    }

    @Test
    fun `el avance puede abortar la lectura de forma cooperativa`() {
        val texto =
            "#EXTM3U\n" +
                "#EXTINF:-1,Canal A\nhttps://example.com/a\n" +
                "#EXTINF:-1,Canal B\nhttps://example.com/b\n"
        val error =
            assertFailsWith<CancelacionPrueba> {
                ImportadorM3U.leerDeclaraciones(texto.byteInputStream()) { total ->
                    if (total >= 1) throw CancelacionPrueba()
                }
            }
        assertEquals("cancelada", error.message)
    }

    @Test
    fun `el avance puede abortar el procesamiento de forma cooperativa`() {
        val texto =
            "#EXTM3U\n" +
                "#EXTINF:-1,Canal A\nhttps://example.com/a\n" +
                "#EXTINF:-1,Canal B\nhttps://example.com/b\n"
        val lectura = ImportadorM3U.leerDeclaraciones(texto.byteInputStream())
        val error =
            assertFailsWith<CancelacionPrueba> {
                ImportadorM3U.importarDeclaraciones(lectura.declaraciones, clave) { total ->
                    if (total >= 1) throw CancelacionPrueba()
                }
            }
        assertEquals("cancelada", error.message)
    }

    @Test
    fun `una lista extensa procesa completa con avance final`() {
        val texto =
            buildString {
                append("#EXTM3U\n")
                repeat(2000) { indice ->
                    append("#EXTINF:-1 tvg-id=\"c$indice\" group-title=\"G\",Canal $indice\n")
                    append("https://example.com/$indice.ts\n")
                }
            }
        val lectura = ImportadorM3U.leerDeclaraciones(texto.byteInputStream())
        assertEquals(2000, lectura.entradas)
        var ultimoAvance = 0
        val resultado =
            ImportadorM3U.importarDeclaraciones(lectura.declaraciones, clave) { total ->
                ultimoAvance = total
            }
        assertEquals(2000, ultimoAvance)
        assertEquals(2000, resultado.canales.size)
    }

    /** Fixture Xtream M3U Plus (S31): TS, M3U8, tvg-id+logo, group-title, VOD, serie, metadatos incompletos y atributos extra. */
    private fun fixtureM3UPlus(): String =
        """
        #EXTM3U
        #EXTINF:-1 tvg-id="tv1" tvg-name="TV Uno" tvg-logo="https://example.com/tv1.png" group-title="Noticias" custom="x",TV Uno
        https://panel.example:8080/live/demo/demo/1001.ts
        #EXTINF:-1 tvg-id="tv2" group-title="Deportes",TV Dos
        https://panel.example:8080/live/demo/demo/1002.m3u8
        #EXTINF:-1 tvg-id="pel1" tvg-logo="https://example.com/pel1.png" group-title="Peliculas",Pelicula Uno
        https://panel.example:8080/movie/demo/demo/2001.mp4
        #EXTINF:-1 tvg-id="ser1" group-title="Series",Serie Uno S01E01
        https://panel.example:8080/series/demo/demo/3001/300101.mp4
        #EXTINF:-1,Sin Metadatos
        https://panel.example:8080/otro/demo/4001.ts
        """.trimIndent()

    @Test
    fun `el parser clasifica TV VOD y cuenta lineas y bloques EXTINF`() {
        val lectura = ImportadorM3U.leerDeclaraciones(fixtureM3UPlus().byteInputStream())
        assertEquals(5, lectura.entradas)
        assertEquals(5, lectura.declaraciones.size)
        assertEquals(11, lectura.lineasLeidas)
        assertEquals(5, lectura.extinfEncontrados)
        assertEquals(2, lectura.entradasTv)
        assertEquals(2, lectura.entradasVod)
        assertEquals(1, lectura.entradasOtro)
    }

    @Test
    fun `un EXTINF sin URL cuenta en extinf pero no emite entrada`() {
        val texto =
            "#EXTM3U\n" +
                "#EXTINF:-1,Huerfano\n" +
                "#EXTINF:-1,Valido\n" +
                "https://panel.example:8080/live/demo/demo/1.ts\n"
        val lectura = ImportadorM3U.leerDeclaraciones(texto.byteInputStream())
        assertEquals(1, lectura.entradas)
        assertEquals(2, lectura.extinfEncontrados)
        assertEquals(
            1,
            lectura.diagnosticos.first { it.codigo == CodigoM3U.EXTINF_SIN_URL }.conteoTotal,
        )
    }

    @Test
    fun `la agrupacion reporta avance por lote y el resultado registra tiempos y ops`() {
        val lectura = ImportadorM3U.leerDeclaraciones(fixtureM3UPlus().byteInputStream())
        val avancesAgrupacion = mutableListOf<Int>()
        val resultado =
            ImportadorM3U.importarDeclaraciones(lectura.declaraciones, clave, alAvanceAgrupacion = { total ->
                avancesAgrupacion += total
            })
        // LOTE_AVANCE=1000: una lista de 5 declaraciones emite solo el tick final.
        assertEquals(listOf(5), avancesAgrupacion)
        assertEquals(5, resultado.declaracionesRecibidas)
        assertEquals(5, resultado.canales.size)
        // Ops: 2 por canal con logo (stream + logo) y 1 por canal sin logo.
        assertEquals(7, resultado.operacionesCriptograficas)
        assertTrue(resultado.tiempoAgrupacionMs >= 0)
        assertTrue(resultado.tiempoHmacMs >= 0)
    }

    @Test
    fun `las etapas del pipeline se emiten en orden con sus campos`() {
        val lectura = ImportadorM3U.leerDeclaraciones(fixtureM3UPlus().byteInputStream())
        val etapas = mutableListOf<AvanceEtapaImportacion>()
        ImportadorM3U.importarDeclaraciones(
            lectura.declaraciones,
            clave,
            alEtapa = { etapa -> etapas += etapa },
        )
        assertEquals(
            listOf(
                EtapaImportacion.AGRUPACION_INICIADA,
                EtapaImportacion.CONVERSION_IDENTIDADES,
                EtapaImportacion.DERIVACION_CLAVES,
                EtapaImportacion.VALIDACION_CATALOGO,
                EtapaImportacion.AGRUPACION_COMPLETADA,
                EtapaImportacion.SOBRES_HMAC,
            ),
            etapas.map { it.etapa },
        )
        val completada = etapas.last { it.etapa == EtapaImportacion.AGRUPACION_COMPLETADA }
        assertEquals(5, completada.elementos)
        assertEquals(5, completada.canalesValidos)
        val sobres = etapas.last { it.etapa == EtapaImportacion.SOBRES_HMAC }
        // Ops: 2 por canal con logo y 1 por canal sin logo (mismo criterio del resultado).
        assertEquals(7, sobres.opsCriptograficas)
        assertEquals(5, sobres.canalesValidos)
        etapas.forEach { etapa ->
            assertTrue(etapa.elementos >= 0)
            assertTrue(etapa.msEtapa >= 0)
            assertTrue(etapa.msDesdeUltimoAvance >= 0)
        }
    }

    @Test
    fun `el flujo sigue emitiendo avances tras completar la canonizacion`() {
        // Regresión ronda 4 (S35): la segunda ventana muda era el tramo
        // post-canonización de la agrupación (deduplicación, derivación de
        // identidad y construcción de Canales). El flujo debe seguir emitiendo
        // avances después del último lote de conversion_identidades.
        val texto =
            buildString {
                append("#EXTM3U\n")
                repeat(2000) { indice ->
                    append("#EXTINF:-1 tvg-id=\"c$indice\" group-title=\"G\",Canal $indice\n")
                    append("https://example.com/$indice.ts\n")
                }
            }
        val lectura = ImportadorM3U.leerDeclaraciones(texto.byteInputStream())
        val etapas = mutableListOf<AvanceEtapaImportacion>()
        ImportadorM3U.importarDeclaraciones(
            lectura.declaraciones,
            clave,
            alEtapa = { etapa -> etapas += etapa },
        )
        val indiceUltimaConversion = etapas.indexOfLast { it.etapa == EtapaImportacion.CONVERSION_IDENTIDADES }
        val posteriores = etapas.drop(indiceUltimaConversion + 1)
        assertEquals(EtapaImportacion.CONVERSION_IDENTIDADES, etapas[indiceUltimaConversion].etapa)
        assertEquals(2000, etapas[indiceUltimaConversion].elementos)
        val codigosPosteriores = posteriores.map { it.etapa }.toSet()
        assertTrue(
            EtapaImportacion.DERIVACION_CLAVES in codigosPosteriores,
            "sin avances de derivacion_claves tras la canonización: $codigosPosteriores",
        )
        assertTrue(
            EtapaImportacion.VALIDACION_CATALOGO in codigosPosteriores,
            "sin avances de validacion_catalogo tras la canonización: $codigosPosteriores",
        )
        assertTrue(
            EtapaImportacion.AGRUPACION_COMPLETADA in codigosPosteriores,
            "sin agrupacion_completada tras la canonización: $codigosPosteriores",
        )
        assertTrue(
            EtapaImportacion.SOBRES_HMAC in codigosPosteriores,
            "sin avances de sobres_hmac tras la canonización: $codigosPosteriores",
        )
    }

    @Test
    fun `la lista xtream de decenas de miles demuestra el reparto de etapas`() {
        val total = 146_729
        val repetidas = 1_000
        val unicas = total - repetidas

        /** Bloque Xtream M3U Plus de la entrada `i`; idéntico para repetirla. */
        fun bloque(i: Int): String {
            val tipo =
                when {
                    i < 117_383 -> "live"
                    i < 139_392 -> "movie"
                    else -> "series"
                }
            val extension = if (tipo == "live") "ts" else "mp4"
            val logo = if (i % 4 == 0) " tvg-logo=\"https://panel.example/logos/$i.png\"" else ""
            return "#EXTINF:-1 tvg-id=\"c$i\" tvg-name=\"Canal $i\"$logo group-title=\"G${i % 50}\",Canal $i\n" +
                "https://panel.example:8080/$tipo/demo/demo/$i.$extension\n"
        }

        val texto =
            buildString {
                append("#EXTM3U\n")
                for (i in 0 until unicas) append(bloque(i))
                for (i in 0 until repetidas) append(bloque(i))
            }

        val avancesAgrupacion = mutableListOf<Int>()
        val etapas = mutableListOf<AvanceEtapaImportacion>()
        val lectura = ImportadorM3U.leerDeclaraciones(texto.byteInputStream())
        assertEquals(total, lectura.entradas)
        assertEquals(total.toLong() * 2, lectura.lineasLeidas - 1)
        assertEquals(118_383, lectura.entradasTv)
        assertEquals(28_346, lectura.entradasVod)
        val resultado =
            ImportadorM3U.importarDeclaraciones(
                lectura.declaraciones,
                clave,
                alAvanceAgrupacion = { acumulado ->
                    avancesAgrupacion += acumulado
                },
                alEtapa = { etapa -> etapas += etapa },
            )
        assertEquals(unicas, resultado.canales.size)
        assertEquals(total, avancesAgrupacion.last())
        // 36433 canales con logo × 2 ops + 109296 sin logo × 1 op.
        assertEquals(182_162, resultado.operacionesCriptograficas)
        // Regresión ronda 4 (S35): el flujo sigue emitiendo avances después de
        // la canonización, también con la lista completa.
        val indiceUltimaConversion = etapas.indexOfLast { it.etapa == EtapaImportacion.CONVERSION_IDENTIDADES }
        val posteriores = etapas.drop(indiceUltimaConversion + 1).map { it.etapa }.toSet()
        assertTrue(EtapaImportacion.DERIVACION_CLAVES in posteriores)
        assertTrue(EtapaImportacion.AGRUPACION_COMPLETADA in posteriores)

        /** Último tiempo acumulado de cada etapa, para el reparto por tramo. */
        fun ultimoMs(etapa: EtapaImportacion): Long = etapas.last { it.etapa == etapa }.msEtapa
        println(
            "MEDICION_ETAPAS entradas=$total unicos=${resultado.canales.size} " +
                "agrupacion=${resultado.tiempoAgrupacionMs}ms hmac=${resultado.tiempoHmacMs}ms " +
                "ops=${resultado.operacionesCriptograficas} " +
                "conversion=${ultimoMs(EtapaImportacion.CONVERSION_IDENTIDADES)}ms " +
                "derivacion=${ultimoMs(EtapaImportacion.DERIVACION_CLAVES)}ms " +
                "validacion=${ultimoMs(EtapaImportacion.VALIDACION_CATALOGO)}ms " +
                "sobres=${ultimoMs(EtapaImportacion.SOBRES_HMAC)}ms",
        )
    }

    /** Excepción de prueba para la cancelación cooperativa por avance. */
    private class CancelacionPrueba : RuntimeException("cancelada")
}
