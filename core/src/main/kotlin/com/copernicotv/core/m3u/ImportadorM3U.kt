package com.copernicotv.core.m3u

import com.copernicotv.core.crypto.HmacUrl
import com.copernicotv.core.identity.AgrupadorImportacion
import com.copernicotv.core.identity.AparienciaDeclarada
import com.copernicotv.core.identity.AtributoExtra
import com.copernicotv.core.identity.AvanceEtapaImportacion
import com.copernicotv.core.identity.CampoUrl
import com.copernicotv.core.identity.CanalDeclarado
import com.copernicotv.core.identity.ClaseUrl
import com.copernicotv.core.identity.ColisionCanonica
import com.copernicotv.core.identity.DeclaracionCanal
import com.copernicotv.core.identity.DeclaracionRechazada
import com.copernicotv.core.identity.EtapaImportacion
import java.io.InputStream
import java.util.Locale
import javax.crypto.SecretKey

/** Canal listo para persistir, con URL y logo cifrables y sus HMAC. */
data class CanalImportadoM3U(
    val nombre: String?,
    val tvgId: String?,
    val urlOriginal: String,
    val urlStreamHmac: String,
    val logoOriginal: String?,
    val logoHmac: String?,
    val atributosExtra: List<AtributoExtra>,
    val claveCanonica: String?,
    val claveUnicidad: String?,
    val claveDerivable: Boolean,
    val apariencias: List<AparienciaDeclarada>,
)

/** Resultado de importar una lista M3U con el parser tolerante. */
data class ResultadoImportacionM3U(
    val canales: List<CanalImportadoM3U>,
    val rechazadas: List<DeclaracionRechazada>,
    val colisiones: List<ColisionCanonica>,
    val diagnosticos: List<DiagnosticoM3U>,
    val repeticionesInformativas: Int,
    /** Declaraciones de identidad recibidas antes de agrupar (S28). */
    val declaracionesRecibidas: Int = 0,
    /** Tiempo de la agrupación de identidad (canonización, dedup, derivar) (S28). */
    val tiempoAgrupacionMs: Long = 0,
    /** Tiempo del bucle de sobres HMAC por canal único (S28). */
    val tiempoHmacMs: Long = 0,
    /** Operaciones HMAC ejecutadas: 1 por canal único + 1 por canal con logo (S28). */
    val operacionesCriptograficas: Int = 0,
) {
    /** Diagnósticos de severidad error; no impiden persistir el catálogo recuperado. */
    val errores: List<DiagnosticoM3U>
        get() = diagnosticos.filter { it.severidad == SeveridadM3U.ERROR }
}

/**
 * Clasificación informativa de una entrada según su ruta (solo diagnóstico).
 *
 * Heurística del formato Xtream M3U Plus: la ruta del stream lleva `/live/`
 * para TV en directo y `/movie/` o `/series/` para VOD (película o episodio).
 * Solo clasifica para el informe (S28): nunca filtra ni descarta entradas
 * (S30) y todo lo no reconocido queda en `OTRO`.
 */
internal enum class TipoEntradaM3U {
    TV,
    VOD,
    OTRO,
}

/** Clasifica una URL de entrada por su ruta; solo informativo, nunca excluye. */
internal fun clasificarEntrada(url: String?): TipoEntradaM3U {
    if (url.isNullOrBlank()) return TipoEntradaM3U.OTRO
    val doblesBarra = url.indexOf("//")
    if (doblesBarra < 0) return TipoEntradaM3U.OTRO
    val inicioRuta = url.indexOf('/', doblesBarra + 2)
    if (inicioRuta < 0) return TipoEntradaM3U.OTRO
    var finRuta = url.length
    url.indexOf('?', inicioRuta).let { if (it in inicioRuta until finRuta) finRuta = it }
    url.indexOf('#', inicioRuta).let { if (it in inicioRuta until finRuta) finRuta = it }
    val ruta = url.substring(inicioRuta, finRuta).lowercase(Locale.ROOT)
    return when {
        ruta.contains("/live/") -> TipoEntradaM3U.TV
        ruta.contains("/movie/") || ruta.contains("/series/") -> TipoEntradaM3U.VOD
        else -> TipoEntradaM3U.OTRO
    }
}

/**
 * Lectura de un flujo M3U sin retener entradas del parser (E7-T7).
 *
 * `leerDeclaraciones` convierte cada `EntradaM3U` a [DeclaracionCanal] en el
 * momento en que se completa: el resultado nunca retiene la lista completa de
 * entradas del parser, solo las declaraciones de identidad. En listas con
 * VOD/series (decenas de miles de entradas) esto evita mantener dos copias del
 * catálogo en memoria durante la importación.
 *
 * Los contadores de clasificación (S28) son solo informativos: TV/VOD/OTRO
 * según la heurística de [clasificarEntrada]; la suma de los tres es
 * [entradas].
 */
data class LecturaM3U(
    val declaraciones: List<DeclaracionCanal>,
    val diagnosticos: List<DiagnosticoM3U>,
    /** Entradas emitidas por el parser, incluidas las rechazadas después. */
    val entradas: Int,
    /** Líneas físicas leídas por el parser, incluidas vacías o descartadas. */
    val lineasLeidas: Long = 0,
    /** Bloques `#EXTINF` encontrados, incluidos los sin URL o con línea excedida. */
    val extinfEncontrados: Long = 0,
    /** Entradas clasificadas como TV en directo por la ruta de su URL. */
    val entradasTv: Int = 0,
    /** Entradas clasificadas como VOD (película o serie) por la ruta de su URL. */
    val entradasVod: Int = 0,
    /** Entradas sin marcador de ruta reconocido; nunca se descartan por esto. */
    val entradasOtro: Int = 0,
)

/**
 * Importador real de E2: encadena el parser tolerante, la agrupación de
 * identidad y el cálculo de HMAC.
 *
 * El parser puede recuperar entradas parciales de una lista con errores; esas
 * entradas se importan y los diagnósticos se informan por separado. La
 * reconciliación entre versiones y las Observaciones pertenecen a E4.
 */
object ImportadorM3U {
    /** Versión de canonicalización vigente: `cv1`. */
    const val VERSION_CANONICA: Int = 1

    /** Generación HMAC inicial: `v1`. */
    const val GENERACION_HMAC: Int = 1

    /** Nanosegundos por milisegundo para los tiempos de etapa (S28). */
    private const val NANOS_POR_MS = 1_000_000L

    /** Frecuencia de emisión de la etapa [EtapaImportacion.SOBRES_HMAC] (S34). */
    private const val LOTE_ETAPA = 1000

    /**
     * Importa un flujo sin cerrarlo; el llamador conserva su propiedad.
     *
     * [generacionHmac] es la generación de la clave con la que se sellan los
     * sobres: por defecto la inicial `v1`; durante la reconstrucción del
     * índice (DC-04 §7) el llamador pasa la generación objetivo.
     */
    fun importar(
        entrada: InputStream,
        claveHmac: SecretKey,
        limites: LimitesParserM3U = LimitesParserM3U(),
        generacionHmac: Int = GENERACION_HMAC,
    ): ResultadoImportacionM3U = convertir(ParserM3U.parsear(entrada, limites), claveHmac, generacionHmac)

    /** Importa un texto ya decodificado como UTF-8. */
    fun importar(
        texto: String,
        claveHmac: SecretKey,
        limites: LimitesParserM3U = LimitesParserM3U(),
        generacionHmac: Int = GENERACION_HMAC,
    ): ResultadoImportacionM3U = convertir(ParserM3U.parsear(texto, limites), claveHmac, generacionHmac)

    /**
     * Importa un resultado de parser ya obtenido.
     *
     * Permite al llamador separar la lectura en streaming del parseo (fase de
     * descarga) de la conversión, la agrupación de identidad y el cálculo de
     * HMAC (fase de procesamiento), para informar el progreso con fases reales.
     */
    fun importar(
        parseo: ResultadoParserM3U,
        claveHmac: SecretKey,
        generacionHmac: Int = GENERACION_HMAC,
    ): ResultadoImportacionM3U =
        importarDeclaraciones(parseo.entradas.map(::declaracion), claveHmac, generacionHmac)
            .copy(diagnosticos = parseo.diagnosticos)

    /**
     * Lee un flujo M3U tolerante sin retener las entradas del parser: cada
     * `EntradaM3U` se convierte a [DeclaracionCanal] en cuanto se completa, de
     * modo que la memoria retiene solo las declaraciones de identidad y no dos
     * copias completas del catálogo (E7-T7). El [InputStream] no se cierra; el
     * llamador conserva su propiedad.
     *
     * [alAvance] recibe el total de entradas emitidas hasta el momento y se
     * invoca por entrada; puede lanzar `CancellationException` para abortar la
     * lectura de forma cooperativa.
     */
    fun leerDeclaraciones(
        entrada: InputStream,
        limites: LimitesParserM3U = LimitesParserM3U(),
        alAvance: ((entradas: Int) -> Unit)? = null,
    ): LecturaM3U {
        val declaraciones = ArrayList<DeclaracionCanal>()
        var emitidas = 0
        var entradasTv = 0
        var entradasVod = 0
        var entradasOtro = 0
        val parseo =
            ParserM3U.parsear(entrada, limites) { entradaM3u ->
                declaraciones += declaracion(entradaM3u)
                when (clasificarEntrada(entradaM3u.url)) {
                    TipoEntradaM3U.TV -> entradasTv++
                    TipoEntradaM3U.VOD -> entradasVod++
                    TipoEntradaM3U.OTRO -> entradasOtro++
                }
                emitidas++
                alAvance?.invoke(emitidas)
            }
        return LecturaM3U(
            declaraciones = declaraciones,
            diagnosticos = parseo.diagnosticos,
            entradas = emitidas,
            lineasLeidas = parseo.lineasLeidas,
            extinfEncontrados = parseo.extinfEncontrados,
            entradasTv = entradasTv,
            entradasVod = entradasVod,
            entradasOtro = entradasOtro,
        )
    }

    /**
     * Procesa declaraciones ya leídas: agrupación de identidad y cálculo de
     * sobres HMAC.
     *
     * [alAvanceAgrupacion] recibe el total de declaraciones canonizadas por
     * [AgrupadorImportacion.agrupar] y se invoca por lote: hace visible la
     * etapa de agrupación, que en listas de decenas de miles de entradas es la
     * más larga y era la ventana muda del vigilante de avance (L13).
     *
     * [alEtapa], si se aporta, emite [AvanceEtapaImportacion] con etapa
     * [EtapaImportacion.SOBRES_HMAC] por lote de [LOTE_ETAPA]: porta los
     * elementos procesados, el tiempo de etapa, las operaciones
     * criptográficas acumuladas y los canales válidos identificados.
     *
     * [alAvance] recibe el total de canales procesados hasta el momento y se
     * invoca por canal; puede lanzar `CancellationException` para abortar de
     * forma cooperativa. El bucle de HMAC con clave de Keystore es la etapa
     * larga de una importación grande y es donde el usuario cancela.
     *
     * Los diagnósticos del parser no pasan por aquí: el llamador que leyó con
     * [leerDeclaraciones] los conserva en [LecturaM3U.diagnosticos].
     */
    fun importarDeclaraciones(
        declaraciones: List<DeclaracionCanal>,
        claveHmac: SecretKey,
        generacionHmac: Int = GENERACION_HMAC,
        alAvanceAgrupacion: ((declaracionesAgrupadas: Int) -> Unit)? = null,
        alEtapa: ((AvanceEtapaImportacion) -> Unit)? = null,
        alAvance: ((canalesProcesados: Int) -> Unit)? = null,
    ): ResultadoImportacionM3U {
        val inicioAgrupacion = System.nanoTime()
        val agrupado =
            AgrupadorImportacion.agrupar(
                declaraciones,
                CampoUrl.CANAL_URL_STREAM,
                alAvanceAgrupacion,
                alEtapa,
            )
        val tiempoAgrupacionMs = (System.nanoTime() - inicioAgrupacion) / NANOS_POR_MS
        val inicioHmac = System.nanoTime()
        val emisorSobres = EmisorSobresHmac(alEtapa, inicioHmac)
        val canales = ArrayList<CanalImportadoM3U>(agrupado.canales.size)
        var operacionesCriptograficas = 0
        var canalesValidos = 0
        var ultimaEmision = 0
        for ((indice, canal) in agrupado.canales.withIndex()) {
            canales += convertirCanal(canal, claveHmac, generacionHmac)
            operacionesCriptograficas += if (canal.logoCanonica == null) 1 else 2
            if (canal.claveDerivable) canalesValidos += 1
            if (alAvance != null) alAvance(indice + 1)
            if ((indice + 1) % LOTE_ETAPA == 0) {
                emisorSobres.emitir(
                    elementos = indice + 1,
                    opsCriptograficas = operacionesCriptograficas,
                    canalesValidos = canalesValidos,
                )
                ultimaEmision = indice + 1
            }
        }
        if (ultimaEmision < canales.size) {
            emisorSobres.emitir(
                elementos = canales.size,
                opsCriptograficas = operacionesCriptograficas,
                canalesValidos = canalesValidos,
            )
        }
        val tiempoHmacMs = (System.nanoTime() - inicioHmac) / NANOS_POR_MS
        return ResultadoImportacionM3U(
            canales = canales,
            rechazadas = agrupado.rechazadas,
            colisiones = agrupado.colisiones,
            diagnosticos = emptyList(),
            repeticionesInformativas = agrupado.repeticionesInformativas,
            declaracionesRecibidas = declaraciones.size,
            tiempoAgrupacionMs = tiempoAgrupacionMs,
            tiempoHmacMs = tiempoHmacMs,
            operacionesCriptograficas = operacionesCriptograficas,
        )
    }

    private fun convertir(
        parseo: ResultadoParserM3U,
        claveHmac: SecretKey,
        generacionHmac: Int,
    ): ResultadoImportacionM3U =
        importarDeclaraciones(parseo.entradas.map(::declaracion), claveHmac, generacionHmac)
            .copy(diagnosticos = parseo.diagnosticos)

    private fun declaracion(entrada: EntradaM3U): DeclaracionCanal =
        DeclaracionCanal(
            nombre = entrada.nombre,
            urlOriginal = entrada.url,
            tvgId = entrada.tvgId,
            groupTitle = entrada.groupTitle,
            tvgName = entrada.tvgName,
            logoOriginal = entrada.logo,
            atributosExtra = entrada.atributosExtra.map { AtributoExtra(it.clave, it.valor) },
        )

    /**
     * Emisor de la etapa [EtapaImportacion.SOBRES_HMAC]: eventos por lote con
     * el tiempo acumulado de la etapa y el tiempo desde el último avance de
     * esta etapa; las ops criptográficas y los canales válidos llegan del
     * bucle. Sin callback no hace nada.
     */
    private class EmisorSobresHmac(
        private val alEtapa: ((AvanceEtapaImportacion) -> Unit)?,
        private val inicioNs: Long,
    ) {
        private var ultimoEventoNs = inicioNs

        fun emitir(
            elementos: Int,
            opsCriptograficas: Int,
            canalesValidos: Int,
        ) {
            if (alEtapa == null) return
            val ahoraNs = System.nanoTime()
            alEtapa(
                AvanceEtapaImportacion(
                    etapa = EtapaImportacion.SOBRES_HMAC,
                    elementos = elementos,
                    msEtapa = (ahoraNs - inicioNs) / NANOS_POR_MS,
                    msDesdeUltimoAvance = (ahoraNs - ultimoEventoNs) / NANOS_POR_MS,
                    opsCriptograficas = opsCriptograficas,
                    canalesValidos = canalesValidos,
                ),
            )
            ultimoEventoNs = ahoraNs
        }
    }

    private fun convertirCanal(
        canal: CanalDeclarado,
        claveHmac: SecretKey,
        generacionHmac: Int,
    ): CanalImportadoM3U {
        val sobreStream =
            HmacUrl.calcularSobre(
                clave = claveHmac,
                generacion = generacionHmac,
                campo = CampoUrl.CANAL_URL_STREAM,
                clase = canal.clase,
                urlCanonica = canal.urlCanonica,
                versionCanonica = VERSION_CANONICA,
            )
        val sobreLogo =
            canal.logoCanonica?.let { logo ->
                HmacUrl.calcularSobre(
                    clave = claveHmac,
                    generacion = generacionHmac,
                    campo = CampoUrl.CANAL_LOGO_URL,
                    clase = ClaseUrl.HTTP_HTTPS,
                    urlCanonica = logo,
                    versionCanonica = VERSION_CANONICA,
                )
            }
        return CanalImportadoM3U(
            nombre = canal.nombre,
            tvgId = canal.tvgId,
            urlOriginal = canal.urlOriginal,
            urlStreamHmac = sobreStream,
            logoOriginal = canal.logoOriginal,
            logoHmac = sobreLogo,
            atributosExtra = canal.atributosExtra,
            claveCanonica = canal.claveCanonica,
            claveUnicidad = canal.claveUnicidad,
            claveDerivable = canal.claveDerivable,
            apariencias = canal.apariencias,
        )
    }
}
