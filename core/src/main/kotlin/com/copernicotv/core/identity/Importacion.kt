package com.copernicotv.core.identity

/** Declaración de Canal tal como la entrega un parser, todavía sin identidad. */
data class DeclaracionCanal(
    val nombre: String?,
    val urlOriginal: String?,
    val tvgId: String? = null,
    val groupTitle: String? = null,
    val tvgName: String? = null,
    val logoOriginal: String? = null,
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
    val logoCanonica: String?,
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
    private data class Canonica(
        val ordinal: Int,
        val nombre: String?,
        val urlOriginal: String,
        val urlCanonica: String,
        val clase: ClaseUrl,
        val tvgPersistido: String?,
        val groupTitle: String?,
        val tvgName: String?,
        val logoCanonica: String?,
    )

    /** Agrupa [declaraciones] en Canales y Apariencias. */
    fun agrupar(
        declaraciones: List<DeclaracionCanal>,
        campoUrl: CampoUrl = CampoUrl.CANAL_URL_STREAM,
    ): ResultadoImportacion {
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
                            logoCanonica = logo,
                        )
                }
            }
        }

        val deduplicadas = deduplicarExactas(canonicas)
        val conteoTvg = deduplicadas.mapNotNull { it.first.tvgPersistido }.groupingBy { it }.eachCount()

        val grupos = linkedMapOf<String, MutableList<Pair<Canonica, Int>>>()
        var sinIdentidad = 0
        for (declaracionPar in deduplicadas) {
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
        }

        val canales = grupos.values.map { grupo -> construirCanal(grupo, conteoTvg) }
        val colisiones = detectarColisiones(canales)
        val repeticiones = deduplicadas.sumOf { it.second } - deduplicadas.size
        return ResultadoImportacion(
            canales = canales,
            rechazadas = rechazadas,
            colisiones = colisiones,
            repeticionesInformativas = repeticiones,
        )
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
            logoCanonica = grupo.mapNotNull { it.first.logoCanonica }.firstOrNull(),
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
    )
}
