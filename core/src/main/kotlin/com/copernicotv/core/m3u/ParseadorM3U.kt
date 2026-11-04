package com.copernicotv.core.m3u

import java.io.InputStream
import java.util.Locale

/**
 * Máquina de estados del parser streaming.
 *
 * Una entrada se abre con `#EXTINF`, admite directivas intermedias (`#EXTGRP`
 * entre ellas) y se cierra con la primera línea que no es comentario ni vacía:
 * esa línea es la URL. Al abrir otra entrada o al llegar al final, una entrada
 * sin URL produce un error tipado y no se emite.
 */
internal class ParseadorM3U(
    private val limites: LimitesParserM3U,
    private val alEntrada: ((EntradaM3U) -> Unit)?,
) {
    private val diagnosticos = AgregadorDiagnosticos(limites.maxMuestrasUbicacion)
    private val entradas = ArrayList<EntradaM3U>()
    private var pendiente: Pendiente? = null
    private var descartada = false
    private var cabeceraEvaluada = false
    private var numeroLinea = 0L
    private var ordinal = 0L

    fun parsear(entrada: InputStream): ResultadoParserM3U {
        val lector = LectorLineas.crear(entrada, limites.maxLongitudLinea)
        while (true) {
            val leida = lector.siguiente()
            if (leida is LineaLeida.Fin) break
            numeroLinea++
            procesar(leida)
        }
        cerrarPendiente()
        return ResultadoParserM3U(entradas.toList(), diagnosticos.resultado())
    }

    private fun procesar(leida: LineaLeida) {
        when (leida) {
            is LineaLeida.Excedida -> descartarPorLineaExcedida(leida.prefijo)
            is LineaLeida.Ok -> procesarTexto(leida.texto)
            is LineaLeida.Fin -> Unit
        }
    }

    private fun descartarPorLineaExcedida(prefijo: String) {
        diagnosticos.registrar(CodigoM3U.LINEA_EXCEDIDA, UbicacionM3U(numeroLinea, pendiente?.ordinal))
        if (pendiente != null || prefijo.startsWith(EXTINF, ignoreCase = true)) {
            pendiente = null
            descartada = true
        }
    }

    private fun procesarTexto(texto: String) {
        if (texto.isBlank()) return
        evaluarCabecera(texto)
        if (esExtinf(texto)) {
            abrirEntrada(texto)
            return
        }
        if (descartada) return
        if (texto.startsWith("#")) {
            aplicarDirectiva(texto)
            return
        }
        cerrarConUrl(texto)
    }

    private fun evaluarCabecera(texto: String) {
        if (cabeceraEvaluada) return
        cabeceraEvaluada = true
        if (!texto.startsWith(CABECERA, ignoreCase = true)) {
            diagnosticos.registrar(CodigoM3U.SIN_CABECERA, UbicacionM3U(numeroLinea))
        }
    }

    private fun esExtinf(texto: String): Boolean = texto.startsWith(EXTINF, ignoreCase = true)

    private fun abrirEntrada(texto: String) {
        cerrarPendiente()
        descartada = false
        ordinal++
        pendiente = interpretarExtinf(texto)
    }

    private fun cerrarConUrl(url: String) {
        val enCurso = pendiente
        if (enCurso == null) {
            diagnosticos.registrar(CodigoM3U.LINEA_DESCONOCIDA, UbicacionM3U(numeroLinea))
            return
        }
        emitir(enCurso.conUrl(url))
        pendiente = null
    }

    private fun aplicarDirectiva(texto: String) {
        if (!texto.startsWith(EXTGRP, ignoreCase = true)) return
        val enCurso = pendiente ?: return
        if (enCurso.groupTitle != null) return
        pendiente = enCurso.conGrupo(texto.substringAfter(':', ""))
    }

    private fun cerrarPendiente() {
        val enCurso = pendiente ?: return
        diagnosticos.registrar(CodigoM3U.EXTINF_SIN_URL, UbicacionM3U(enCurso.linea, enCurso.ordinal))
        pendiente = null
    }

    private fun emitir(entrada: EntradaM3U) {
        if (alEntrada != null) alEntrada(entrada) else entradas.add(entrada)
    }

    private fun interpretarExtinf(texto: String): Pendiente {
        val resto = texto.substring(EXTINF.length)
        val contenido = if (resto.startsWith(":")) resto.substring(1) else resto
        val cursor = CursorExtinf(contenido)
        val duracion = cursor.leerDuracion()
        val aceptados = ArrayList<ParAtributoM3U>()
        var reconocidos = 0
        var desbordado = false
        while (cursor.puedeLeerPar()) {
            val par = cursor.leerPar()
            if (par != null) {
                reconocidos++
                if (reconocidos <= limites.maxAtributosPorEntrada) {
                    aceptados.add(par)
                } else {
                    desbordado = true
                }
            }
        }
        if (desbordado) {
            diagnosticos.registrar(CodigoM3U.ATRIBUTOS_DESBORDADOS, UbicacionM3U(numeroLinea, ordinal))
        }
        return Pendiente(
            linea = numeroLinea,
            ordinal = ordinal,
            duracion = duracion,
            nombre = cursor.restoTrasComa(),
            atributos = aceptados,
        )
    }

    private companion object {
        const val CABECERA = "#EXTM3U"
        const val EXTINF = "#EXTINF"
        const val EXTGRP = "#EXTGRP"
    }
}

/** Entrada en curso, todavía sin URL. */
private data class Pendiente(
    val linea: Long,
    val ordinal: Long,
    val duracion: String,
    val nombre: String?,
    val atributos: List<ParAtributoM3U>,
    val groupTitle: String? = null,
) {
    fun conGrupo(grupo: String): Pendiente = copy(groupTitle = grupo)

    fun conUrl(url: String): EntradaM3U =
        EntradaM3U(
            ordinal = ordinal,
            linea = linea,
            duracion = duracion,
            nombre = nombre,
            url = url,
            tvgId = primero(TVG_ID),
            tvgName = primero(TVG_NAME),
            logo = primero(TVG_LOGO),
            groupTitle = primero(GROUP_TITLE) ?: groupTitle,
            atributos = atributos,
            atributosExtra = atributos.filter { it.clave.lowercase(Locale.ROOT) !in CLAVES_DEDICADAS },
        )

    private fun primero(clave: String): String? = atributos.firstOrNull { it.clave.equals(clave, ignoreCase = true) }?.valor

    private companion object {
        const val TVG_ID = "tvg-id"
        const val TVG_NAME = "tvg-name"
        const val TVG_LOGO = "tvg-logo"
        const val GROUP_TITLE = "group-title"
        val CLAVES_DEDICADAS = setOf(TVG_ID, TVG_NAME, TVG_LOGO, GROUP_TITLE)
    }
}

/**
 * Cursor tolerante sobre el contenido de un `#EXTINF`.
 *
 * Un valor entre comillas (simples o dobles) admite comas; un valor sin comillas
 * termina en espacio o coma. Un token sin `=` se consume y se descarta: no
 * invalida la entrada ni impide leer los pares siguientes.
 */
private class CursorExtinf(private val texto: String) {
    private var indice = 0

    fun fin(): Boolean = indice >= texto.length

    fun esComa(): Boolean = !fin() && texto[indice] == ','

    /** Deja el cursor en el siguiente par, o indica que ya no hay más. */
    fun puedeLeerPar(): Boolean {
        saltarEspacios()
        return !fin() && !esComa()
    }

    fun saltarEspacios() {
        while (!fin() && texto[indice].isWhitespace()) indice++
    }

    fun leerDuracion(): String {
        val inicio = indice
        while (!fin() && !esFinDeValor(texto[indice])) indice++
        return texto.substring(inicio, indice)
    }

    /** Consume un par `clave=valor`; devuelve `null` si el token no es un par. */
    fun leerPar(): ParAtributoM3U? {
        val inicioClave = indice
        while (!fin() && !esFinDeClave(texto[indice])) indice++
        if (fin() || texto[indice] != '=') return null
        val clave = texto.substring(inicioClave, indice)
        indice++
        val valor = leerValor()
        return if (clave.isEmpty()) null else ParAtributoM3U(clave, valor)
    }

    /** Nombre tras la primera coma de nivel superior; `null` si no hay coma. */
    fun restoTrasComa(): String? {
        if (fin() || texto[indice] != ',') return null
        indice++
        return texto.substring(indice)
    }

    private fun leerValor(): String {
        if (fin()) return ""
        val comilla = texto[indice]
        if (comilla == '"' || comilla == '\'') {
            indice++
            val inicio = indice
            while (!fin() && texto[indice] != comilla) indice++
            val valor = texto.substring(inicio, indice)
            if (!fin()) indice++
            return valor
        }
        val inicio = indice
        while (!fin() && !esFinDeValor(texto[indice])) indice++
        return texto.substring(inicio, indice)
    }

    private fun esFinDeClave(caracter: Char): Boolean = caracter == '=' || caracter == ',' || caracter.isWhitespace()

    private fun esFinDeValor(caracter: Char): Boolean = caracter.isWhitespace() || caracter == ','
}

/** Agrega diagnósticos por código: conteo total y muestra acotada de ubicaciones. */
private class AgregadorDiagnosticos(private val maxMuestras: Int) {
    private val conteos = LinkedHashMap<CodigoM3U, Long>()
    private val muestras = LinkedHashMap<CodigoM3U, MutableList<UbicacionM3U>>()

    fun registrar(
        codigo: CodigoM3U,
        ubicacion: UbicacionM3U,
    ) {
        conteos[codigo] = (conteos[codigo] ?: 0L) + 1L
        val lista = muestras.getOrPut(codigo) { ArrayList() }
        if (lista.size < maxMuestras) lista.add(ubicacion)
    }

    fun resultado(): List<DiagnosticoM3U> =
        CodigoM3U.entries.mapNotNull { codigo ->
            val total = conteos[codigo] ?: return@mapNotNull null
            DiagnosticoM3U(
                codigo = codigo,
                severidad = codigo.severidad,
                conteoTotal = total,
                ubicaciones = muestras[codigo].orEmpty().toList(),
            )
        }
}
