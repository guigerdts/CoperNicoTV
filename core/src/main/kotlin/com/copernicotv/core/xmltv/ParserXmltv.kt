package com.copernicotv.core.xmltv

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory
import java.io.IOException
import java.io.InputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Parser XMLTV incremental con XmlPullParser (kxml2), ADR-0003.
 *
 * El documento nunca se materializa completo: cada `<channel>` y cada
 * `<programme>` se entrega al consumidor en cuanto se cierra su etiqueta. Los
 * errores semánticos de un elemento son recuperables y no detienen el parseo;
 * un error global (raíz inválida, XML malformado, EOF abrupto, encoding o
 * lectura) se reporta con la cantidad de elementos recuperados y descartados.
 *
 * La cancelación es cooperativa: se consulta entre eventos y, al activarse,
 * el parseo se detiene sin publicar nada; el llamador descarta el staging.
 */
class ParserXmltv(
    private val maximoErroresRetenidos: Int = MAXIMO_ERRORES_RETENIDOS,
) {
    @Suppress(
        "LongMethod",
        "CyclomaticComplexMethod",
        "NestedBlockDepth",
        "LoopWithTooManyJumpStatements",
    )
    suspend fun parsear(
        entrada: InputStream,
        consumidor: ConsumidorXmltv = object : ConsumidorXmltv {},
        cancelacion: () -> Boolean = { false },
    ): ResultadoParseoXmltv {
        val registro = RegistroErrores(consumidor, maximoErroresRetenidos)
        var canales = 0
        var programas = 0
        var descartados = 0
        var raizValida = false
        var cancelado = false
        var eofAbrupto = false
        val idsCanal = mutableSetOf<String>()

        val parser =
            try {
                XmlPullParserFactory
                    .newInstance()
                    .apply { isNamespaceAware = false }
                    .newPullParser()
            } catch (error: XmlPullParserException) {
                return ResultadoParseoXmltv(
                    canales = 0,
                    programas = 0,
                    descartados = 0,
                    erroresTotales = 0,
                    errores = emptyList(),
                    errorGlobal = ErrorGlobalXmltv.CONFIGURACION,
                    detalleGlobal = error.message,
                )
            }

        try {
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
        } catch (_: XmlPullParserException) {
            // kxml2 puede no exponer la feature; el documento no se valida con DTD.
        }

        try {
            parser.setInput(entrada, null)
            var evento = parser.eventType
            if (evento == XmlPullParser.START_DOCUMENT) evento = parser.next()
            while (evento != XmlPullParser.START_TAG) {
                if (evento == XmlPullParser.END_DOCUMENT) {
                    return ResultadoParseoXmltv(
                        canales = 0,
                        programas = 0,
                        descartados = 0,
                        erroresTotales = 0,
                        errores = emptyList(),
                        errorGlobal = ErrorGlobalXmltv.RAIZ_INVALIDA,
                    )
                }
                evento = parser.next()
            }
            if (parser.name != "tv") {
                return ResultadoParseoXmltv(
                    canales = 0,
                    programas = 0,
                    descartados = 0,
                    erroresTotales = 0,
                    errores = emptyList(),
                    errorGlobal = ErrorGlobalXmltv.RAIZ_INVALIDA,
                )
            }
            raizValida = true

            var profundidad = 1
            while (profundidad > 0) {
                if (cancelacion()) {
                    cancelado = true
                    break
                }
                when (parser.next()) {
                    XmlPullParser.START_TAG -> {
                        profundidad++
                        val ubicacion = ubicacion(parser)
                        when (parser.name) {
                            "channel" -> {
                                val resultado =
                                    procesarCanal(
                                        parser = parser,
                                        ubicacion = ubicacion,
                                        idsCanal = idsCanal,
                                        consumidor = consumidor,
                                        registro = registro,
                                        cancelacion = cancelacion,
                                    )
                                when (resultado) {
                                    ResultadoElemento.Consumido -> canales++
                                    ResultadoElemento.Descartado -> descartados++
                                    ResultadoElemento.Cancelado -> cancelado = true
                                    ResultadoElemento.Eof -> eofAbrupto = true
                                }
                                profundidad--
                            }
                            "programme" -> {
                                val resultado =
                                    procesarPrograma(
                                        parser = parser,
                                        ubicacion = ubicacion,
                                        consumidor = consumidor,
                                        registro = registro,
                                        cancelacion = cancelacion,
                                    )
                                when (resultado) {
                                    ResultadoElemento.Consumido -> programas++
                                    ResultadoElemento.Descartado -> descartados++
                                    ResultadoElemento.Cancelado -> cancelado = true
                                    ResultadoElemento.Eof -> eofAbrupto = true
                                }
                                profundidad--
                            }
                            else -> {
                                val fin = saltarSubarbol(parser, cancelacion)
                                profundidad--
                                when (fin) {
                                    FinSubarbol.Cancelado -> cancelado = true
                                    FinSubarbol.Eof -> eofAbrupto = true
                                    FinSubarbol.Completo -> Unit
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> profundidad--
                    XmlPullParser.END_DOCUMENT -> {
                        eofAbrupto = true
                        break
                    }
                }
                if (cancelado || eofAbrupto) break
            }
        } catch (error: XmlPullParserException) {
            return registro.resultado(
                canales = canales,
                programas = programas,
                descartados = descartados,
                cancelado = cancelado,
                errorGlobal = clasificar(error, raizValida),
                detalleGlobal = error.message,
            )
        } catch (error: IOException) {
            return registro.resultado(
                canales = canales,
                programas = programas,
                descartados = descartados,
                cancelado = cancelado,
                errorGlobal = ErrorGlobalXmltv.LECTURA,
                detalleGlobal = error.message,
            )
        }

        return registro.resultado(
            canales = canales,
            programas = programas,
            descartados = descartados,
            cancelado = cancelado,
            errorGlobal = if (eofAbrupto) ErrorGlobalXmltv.EOF_ABRUPTO else null,
        )
    }

    @Suppress("NestedBlockDepth")
    private suspend fun procesarCanal(
        parser: XmlPullParser,
        ubicacion: String,
        idsCanal: MutableSet<String>,
        consumidor: ConsumidorXmltv,
        registro: RegistroErrores,
        cancelacion: () -> Boolean,
    ): ResultadoElemento {
        val id = parser.getAttributeValue(null, "id")
        val nombres = mutableListOf<String>()
        val iconos = mutableListOf<String>()
        var profundidad = 1
        while (profundidad > 0) {
            if (cancelacion()) return ResultadoElemento.Cancelado
            when (parser.next()) {
                XmlPullParser.START_TAG -> {
                    profundidad++
                    when (parser.name) {
                        "display-name" -> {
                            nombres += parser.nextText()
                            profundidad--
                        }
                        "icon" -> {
                            parser.getAttributeValue(null, "src")?.let { iconos += it }
                        }
                    }
                }
                XmlPullParser.END_TAG -> profundidad--
                XmlPullParser.END_DOCUMENT -> return ResultadoElemento.Eof
            }
        }

        if (id.isNullOrBlank()) {
            registro.agregar(ErrorXmltv(ErrorElementoXmltv.CANAL_SIN_ID, ubicacion))
            return ResultadoElemento.Descartado
        }
        val canalId = id.trim()
        if (!idsCanal.add(canalId)) {
            registro.agregar(ErrorXmltv(ErrorElementoXmltv.CANAL_ID_DUPLICADO, ubicacion, canalId))
            return ResultadoElemento.Descartado
        }
        consumidor.alCanal(
            CanalXmltv(
                id = canalId,
                nombres = nombres.map { it.trim() }.filter { it.isNotEmpty() },
                iconos = iconos.map { it.trim() }.filter { it.isNotEmpty() },
            ),
        )
        return ResultadoElemento.Consumido
    }

    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth", "ReturnCount")
    private suspend fun procesarPrograma(
        parser: XmlPullParser,
        ubicacion: String,
        consumidor: ConsumidorXmltv,
        registro: RegistroErrores,
        cancelacion: () -> Boolean,
    ): ResultadoElemento {
        val channelId = parser.getAttributeValue(null, "channel")
        val inicioTexto = parser.getAttributeValue(null, "start")
        val finTexto = parser.getAttributeValue(null, "stop")
        var titulo: String? = null
        var descripcion: String? = null
        var profundidad = 1
        while (profundidad > 0) {
            if (cancelacion()) return ResultadoElemento.Cancelado
            when (parser.next()) {
                XmlPullParser.START_TAG -> {
                    profundidad++
                    when (parser.name) {
                        "title" -> {
                            if (titulo == null) {
                                titulo = parser.nextText()
                                profundidad--
                            }
                        }
                        "desc" -> {
                            if (descripcion == null) {
                                descripcion = parser.nextText()
                                profundidad--
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> profundidad--
                XmlPullParser.END_DOCUMENT -> return ResultadoElemento.Eof
            }
        }

        if (channelId.isNullOrBlank()) {
            registro.agregar(ErrorXmltv(ErrorElementoXmltv.PROGRAMA_SIN_CANAL, ubicacion))
            return ResultadoElemento.Descartado
        }
        if (inicioTexto.isNullOrBlank()) {
            registro.agregar(ErrorXmltv(ErrorElementoXmltv.PROGRAMA_SIN_INICIO, ubicacion))
            return ResultadoElemento.Descartado
        }
        if (finTexto.isNullOrBlank()) {
            registro.agregar(ErrorXmltv(ErrorElementoXmltv.PROGRAMA_SIN_FIN, ubicacion))
            return ResultadoElemento.Descartado
        }
        val inicio =
            FechaXmltv
                .parsear(inicioTexto)
                ?: run {
                    registro.agregar(ErrorXmltv(ErrorElementoXmltv.FECHA_INVALIDA, ubicacion, "start"))
                    return ResultadoElemento.Descartado
                }
        val fin =
            FechaXmltv
                .parsear(finTexto)
                ?: run {
                    registro.agregar(ErrorXmltv(ErrorElementoXmltv.FECHA_INVALIDA, ubicacion, "stop"))
                    return ResultadoElemento.Descartado
                }
        if (!fin.isAfter(inicio)) {
            registro.agregar(ErrorXmltv(ErrorElementoXmltv.RANGO_INVALIDO, ubicacion))
            return ResultadoElemento.Descartado
        }
        val tituloLimpio = titulo?.trim().orEmpty()
        if (tituloLimpio.isEmpty()) {
            registro.agregar(ErrorXmltv(ErrorElementoXmltv.TITULO_AUSENTE, ubicacion))
        }
        val descripcionLimpia = descripcion?.trim()?.takeIf { it.isNotEmpty() }
        consumidor.alPrograma(
            ProgramaXmltv(
                channelIdTexto = channelId.trim(),
                titulo = tituloLimpio,
                inicio = inicio,
                fin = fin,
                descripcion = descripcionLimpia,
            ),
        )
        return ResultadoElemento.Consumido
    }

    private suspend fun saltarSubarbol(
        parser: XmlPullParser,
        cancelacion: () -> Boolean,
    ): FinSubarbol {
        var profundidad = 1
        while (profundidad > 0) {
            if (cancelacion()) return FinSubarbol.Cancelado
            when (parser.next()) {
                XmlPullParser.START_TAG -> profundidad++
                XmlPullParser.END_TAG -> profundidad--
                XmlPullParser.END_DOCUMENT -> return FinSubarbol.Eof
            }
        }
        return FinSubarbol.Completo
    }

    private fun clasificar(
        error: XmlPullParserException,
        raizValida: Boolean,
    ): ErrorGlobalXmltv {
        if (!raizValida) return ErrorGlobalXmltv.RAIZ_INVALIDA
        val mensaje = error.message.orEmpty().lowercase()
        return if ("encoding" in mensaje || "unsupported" in mensaje) {
            ErrorGlobalXmltv.ENCODING_INVALIDO
        } else {
            ErrorGlobalXmltv.XML_MALFORMADO
        }
    }

    private fun ubicacion(parser: XmlPullParser): String = "${parser.lineNumber}:${parser.columnNumber}"

    private enum class ResultadoElemento { Consumido, Descartado, Cancelado, Eof }

    private enum class FinSubarbol { Completo, Cancelado, Eof }

    private class RegistroErrores(
        private val consumidor: ConsumidorXmltv,
        private val maximo: Int,
    ) {
        private val retenidos = mutableListOf<ErrorXmltv>()
        private var total = 0

        suspend fun agregar(error: ErrorXmltv) {
            total++
            if (retenidos.size < maximo) {
                retenidos += error
                consumidor.alError(error)
            }
        }

        fun resultado(
            canales: Int,
            programas: Int,
            descartados: Int,
            cancelado: Boolean,
            errorGlobal: ErrorGlobalXmltv?,
            detalleGlobal: String? = null,
        ): ResultadoParseoXmltv =
            ResultadoParseoXmltv(
                canales = canales,
                programas = programas,
                descartados = descartados,
                erroresTotales = total,
                errores = retenidos.toList(),
                errorGlobal = errorGlobal,
                detalleGlobal = detalleGlobal,
                cancelado = cancelado,
            )
    }
}

/** Fechas XMLTV `YYYYMMDDhhmm[ss] [±HHMM]` (S28b). */
object FechaXmltv {
    private val PATRON =
        Regex("^\\s*(\\d{4})(\\d{2})(\\d{2})(\\d{2})(\\d{2})(\\d{2})?\\s*([+-]\\d{4})?\\s*$")

    /**
     * Convierte una fecha XMLTV a [Instant].
     *
     * Un documento sin offset se interpreta como UTC para que el resultado sea
     * determinista; XMLTV no garantiza la zona local del emisor.
     */
    fun parsear(texto: String): Instant? {
        val coincidencia = PATRON.matchEntire(texto) ?: return null
        val grupos = coincidencia.groupValues
        return try {
            val fecha =
                LocalDateTime.of(
                    grupos[1].toInt(),
                    grupos[2].toInt(),
                    grupos[3].toInt(),
                    grupos[4].toInt(),
                    grupos[5].toInt(),
                    grupos[6].ifEmpty { "0" }.toInt(),
                )
            val offset = grupos[7].ifEmpty { "+0000" }
            fecha.toInstant(ZoneOffset.of(offset))
        } catch (_: Exception) {
            null
        }
    }
}
