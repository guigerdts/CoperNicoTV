package com.copernicotv.core.identity

/** Par clave-valor adicional de una entrada, sin interpretación ni normalización. */
data class AtributoExtra(
    val clave: String,
    val valor: String,
)

/** Declaración de Canal tal como la entrega un parser, todavía sin identidad. */
data class DeclaracionCanal(
    val nombre: String?,
    val urlOriginal: String?,
    val tvgId: String? = null,
    val groupTitle: String? = null,
    val tvgName: String? = null,
    val logoOriginal: String? = null,
    val atributosExtra: List<AtributoExtra> = emptyList(),
)

/** Apariencia declarada dentro de una importación (DC-05). */
data class AparienciaDeclarada(
    val groupTitle: String?,
    val tvgName: String?,
    val clavePresentacion: String,
    val orden: Int,
    val repeticiones: Int,
)

/** Canal resultante de agrupar declaraciones por identidad lógica. */
data class CanalDeclarado(
    val nombre: String?,
    val tvgId: String?,
    val urlOriginal: String,
    val urlCanonica: String,
    val clase: ClaseUrl,
    val logoOriginal: String? = null,
    val logoCanonica: String?,
    val atributosExtra: List<AtributoExtra> = emptyList(),
    val claveCanonica: String?,
    val claveUnicidad: String?,
    val claveDerivable: Boolean,
    val apariencias: List<AparienciaDeclarada>,
    val declaraciones: Int,
)

/** Declaración rechazada con código tipado y ordinal, sin datos sensibles. */
data class DeclaracionRechazada(
    val ordinal: Int,
    val campo: CampoUrl,
    val error: ErrorUrl,
)

/** Colisión de `clave_canonica` entre Canales distintos: no se fusiona. */
data class ColisionCanonica(
    val claveCanonica: String,
    val canalesDistintos: Int,
)

/** Resultado de agrupar una importación completa. */
data class ResultadoImportacion(
    val canales: List<CanalDeclarado>,
    val rechazadas: List<DeclaracionRechazada>,
    val colisiones: List<ColisionCanonica>,
    val repeticionesInformativas: Int,
)

/**
 * Agrupación intra-importación de declaraciones (E1-U2).
 *
 * Aplica la deduplicación de declaraciones idénticas de ADR-0004:18 y la
 * derivación de DC-01. No realiza reconciliación entre versiones, transiciones
 * A/B/C ni fusión de Canales: eso es E4. Una colisión de `clave_canonica` se
 * reporta y nunca se resuelve por posición.
 */
object AgrupadorImportacion {
    /** Frecuencia del avance opcional durante la canonización. */
    private const val LOTE_AVANCE = 1000

    /** Nanosegundos por milisegundo para los tiempos de etapa (S34). */
    private const val NANOS_POR_MS = 1_000_000L

    private data class Canonica(
        val ordinal: Int,
        val nombre: String?,
        val urlOriginal: String,
        val urlCanonica: String,
        val clase: ClaseUrl,
        val tvgPersistido: String?,
        val groupTitle: String?,
        val tvgName: String?,
        val logoOriginal: String?,
        val logoCanonica: String?,
        val atributosExtra: List<AtributoExtra>,
    )

    /**
     * Agrupa [declaraciones] en Canales y Apariencias.
     *
     * [alAvance], si se aporta, recibe el total de declaraciones procesadas
     * (canonizadas) hasta el momento y se invoca cada [LOTE_AVANCE] entradas;
     * puede lanzar `CancellationException` para abortar de forma cooperativa.
     * Los llamadores existentes no lo pasan y conservan el comportamiento
     * previo.
     *
     * [alEtapa], si se aporta, emite [AvanceEtapaImportacion] por etapa y por
     * lote en TODO el tramo, incluido el post-canonización (deduplicación,
     * derivación de identidad y construcción de Canales), que con listas
     * grandes es la mitad más larga de la agrupación y era la segunda ventana
     * muda del vigilante (E7-T7 ronda 4, L17).
     */
    fun agrupar(
        declaraciones: List<DeclaracionCanal>,
        campoUrl: CampoUrl = CampoUrl.CANAL_URL_STREAM,
        alAvance: ((declaracionesProcesadas: Int) -> Unit)? = null,
        alEtapa: ((AvanceEtapaImportacion) -> Unit)? = null,
    ): ResultadoImportacion {
        val emisor = EmisorEtapas(alEtapa)
        emisor.emitir(EtapaImportacion.AGRUPACION_INICIADA, elementos = 0, canalesValidos = 0)
        val rechazadas = mutableListOf<DeclaracionRechazada>()
        val canonicas = mutableListOf<Canonica>()

        declaraciones.forEachIndexed { ordinal, declaracion ->
            when (val resultado = CanonicalizadorUrl.canonizar(declaracion.urlOriginal, campoUrl)) {
                is ResultadoCanonizacion.Rechazada -> {
                    rechazadas += DeclaracionRechazada(ordinal, campoUrl, resultado.error)
                }
                is ResultadoCanonizacion.Ok -> {
                    val tvg = (ValidadorTvgId.validar(declaracion.tvgId) as? ResultadoTvgId.Valido)?.valor
                    val logo =
                        declaracion.logoOriginal?.let { original ->
                            (CanonicalizadorUrl.canonizar(original, CampoUrl.CANAL_LOGO_URL) as? ResultadoCanonizacion.Ok)
                                ?.urlCanonica
                        }
                    canonicas +=
                        Canonica(
                            ordinal = ordinal,
                            nombre = declaracion.nombre,
                            urlOriginal = declaracion.urlOriginal.orEmpty(),
                            urlCanonica = resultado.urlCanonica,
                            clase = resultado.clase,
                            tvgPersistido = tvg,
                            groupTitle = declaracion.groupTitle,
                            tvgName = declaracion.tvgName,
                            logoOriginal = declaracion.logoOriginal,
                            logoCanonica = logo,
                            atributosExtra = declaracion.atributosExtra,
                        )
                }
            }
            if (alAvance != null && (ordinal + 1) % LOTE_AVANCE == 0) {
                alAvance(ordinal + 1)
            }
            emisor.lote(EtapaImportacion.CONVERSION_IDENTIDADES, ordinal + 1)
        }
        if (alAvance != null && declaraciones.size % LOTE_AVANCE != 0) {
            alAvance(declaraciones.size)
        }
        emisor.emitir(EtapaImportacion.CONVERSION_IDENTIDADES, declaraciones.size, canalesValidos = 0)

        val deduplicadas = deduplicarExactas(canonicas)
        val conteoTvg = deduplicadas.mapNotNull { it.first.tvgPersistido }.groupingBy { it }.eachCount()

        val grupos = linkedMapOf<String, MutableList<Pair<Canonica, Int>>>()
        var sinIdentidad = 0
        for ((indice, declaracionPar) in deduplicadas.withIndex()) {
            val declaracion = declaracionPar.first
            val tvgUnico = declaracion.tvgPersistido?.let { conteoTvg[it] == 1 } ?: false
            val identidad =
                ClaveCanal.derivar(
                    nombre = declaracion.nombre,
                    urlCanonica = declaracion.urlCanonica,
                    tvgId = declaracion.tvgPersistido,
                    tvgIdUnicoEnFuente = tvgUnico,
                )
            val clave = identidad.claveUnicidad
            if (clave == null) {
                sinIdentidad += 1
                grupos["#sin-identidad-$sinIdentidad"] = mutableListOf(declaracionPar)
            } else {
                grupos.getOrPut(clave) { mutableListOf() }.add(declaracionPar)
            }
            emisor.lote(EtapaImportacion.DERIVACION_CLAVES, indice + 1)
        }
        emisor.emitir(EtapaImportacion.DERIVACION_CLAVES, deduplicadas.size, canalesValidos = 0)

        var validosConstruidos = 0
        val canales =
            grupos.values.mapIndexed { indice, grupo ->
                val canal = construirCanal(grupo, conteoTvg)
                if (canal.claveDerivable) {
                    validosConstruidos += 1
                }
                emisor.lote(EtapaImportacion.VALIDACION_CATALOGO, indice + 1, canalesValidos = validosConstruidos)
                canal
            }
        emisor.emitir(EtapaImportacion.VALIDACION_CATALOGO, canales.size, canalesValidos = validosConstruidos)
        val colisiones = detectarColisiones(canales)
        emisor.emitir(EtapaImportacion.AGRUPACION_COMPLETADA, canales.size, canalesValidos = validosConstruidos)
        val repeticiones = deduplicadas.sumOf { it.second } - deduplicadas.size
        return ResultadoImportacion(
            canales = canales,
            rechazadas = rechazadas,
            colisiones = colisiones,
            repeticionesInformativas = repeticiones,
        )
    }

    /**
     * Emisor interno de avances por etapa: agrupa los lotes en [LOTE_AVANCE],
     * mide el tiempo acumulado de la etapa y el tiempo desde el último avance
     * emitido, y solo despacha al callback cuando corresponde.
     */
    private class EmisorEtapas(
        private val alEtapa: ((AvanceEtapaImportacion) -> Unit)?,
    ) {
        private val inicioEtapaNs = mutableMapOf<EtapaImportacion, Long>()
        private var ultimoEventoNs = System.nanoTime()

        /** Marca un lote de [elementos] procesados en [etapa]. */
        fun lote(
            etapa: EtapaImportacion,
            elementos: Int,
            canalesValidos: Int = 0,
        ) {
            if (alEtapa != null && elementos % LOTE_AVANCE == 0) {
                emitir(etapa, elementos, canalesValidos)
            }
        }

        /** Emite un evento de [etapa] sin filtro de lote. */
        fun emitir(
            etapa: EtapaImportacion,
            elementos: Int,
            canalesValidos: Int,
        ) {
            if (alEtapa == null) return
            val ahoraNs = System.nanoTime()
            val inicio = inicioEtapaNs.getOrPut(etapa) { ahoraNs }
            alEtapa(
                AvanceEtapaImportacion(
                    etapa = etapa,
                    elementos = elementos,
                    msEtapa = (ahoraNs - inicio) / NANOS_POR_MS,
                    msDesdeUltimoAvance = (ahoraNs - ultimoEventoNs) / NANOS_POR_MS,
                    opsCriptograficas = 0,
                    canalesValidos = canalesValidos,
                ),
            )
            ultimoEventoNs = ahoraNs
        }
    }

    private fun deduplicarExactas(canonicas: List<Canonica>): List<Pair<Canonica, Int>> {
        val acumulado = linkedMapOf<ClaveExacta, Pair<Canonica, Int>>()
        for (declaracion in canonicas) {
            val clave =
                ClaveExacta(
                    url = declaracion.urlCanonica,
                    tvg = declaracion.tvgPersistido,
                    grupo = declaracion.groupTitle,
                    tvgName = declaracion.tvgName,
                    logo = declaracion.logoCanonica,
                    extras = declaracion.atributosExtra,
                )
            val previo = acumulado[clave]
            acumulado[clave] =
                if (previo == null) {
                    declaracion to 1
                } else {
                    previo.first to previo.second + 1
                }
        }
        return acumulado.values.toList()
    }

    private fun construirCanal(
        grupo: List<Pair<Canonica, Int>>,
        conteoTvg: Map<String, Int>,
    ): CanalDeclarado {
        val primera = grupo.first().first
        val declaraciones = grupo.sumOf { it.second }
        val tvgUnico = primera.tvgPersistido?.let { conteoTvg[it] == 1 } ?: false
        val identidad =
            ClaveCanal.derivar(
                nombre = primera.nombre,
                urlCanonica = primera.urlCanonica,
                tvgId = primera.tvgPersistido,
                tvgIdUnicoEnFuente = tvgUnico,
            )
        val apariencias = linkedMapOf<String, AparienciaDeclarada>()
        for (declaracionPar in grupo) {
            val declaracion = declaracionPar.first
            val clave = ClavePresentacion.clave(declaracion.groupTitle, declaracion.tvgName)
            val previa = apariencias[clave]
            apariencias[clave] =
                if (previa == null) {
                    AparienciaDeclarada(
                        groupTitle = declaracion.groupTitle,
                        tvgName = declaracion.tvgName,
                        clavePresentacion = clave,
                        orden = declaracion.ordinal,
                        repeticiones = 1,
                    )
                } else {
                    previa.copy(repeticiones = previa.repeticiones + 1)
                }
        }
        return CanalDeclarado(
            nombre = grupo.mapNotNull { it.first.nombre }.firstOrNull(),
            tvgId = primera.tvgPersistido,
            urlOriginal = primera.urlOriginal,
            urlCanonica = primera.urlCanonica,
            clase = primera.clase,
            logoOriginal = grupo.mapNotNull { it.first.logoOriginal }.firstOrNull(),
            logoCanonica = grupo.mapNotNull { it.first.logoCanonica }.firstOrNull(),
            atributosExtra = primera.atributosExtra,
            claveCanonica = identidad.claveCanonica,
            claveUnicidad = identidad.claveUnicidad,
            claveDerivable = identidad.claveDerivable,
            apariencias = apariencias.values.toList(),
            declaraciones = declaraciones,
        )
    }

    private fun detectarColisiones(canales: List<CanalDeclarado>): List<ColisionCanonica> =
        canales
            .mapNotNull { canal -> canal.claveCanonica?.let { it to canal } }
            .groupBy({ it.first }, { it.second })
            .filterValues { it.size > 1 }
            .map { (clave, lista) -> ColisionCanonica(clave, lista.size) }

    private data class ClaveExacta(
        val url: String,
        val tvg: String?,
        val grupo: String?,
        val tvgName: String?,
        val logo: String?,
        val extras: List<AtributoExtra>,
    )
}
