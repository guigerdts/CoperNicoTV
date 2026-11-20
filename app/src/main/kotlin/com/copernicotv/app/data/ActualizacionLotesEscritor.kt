package com.copernicotv.app.data

import com.copernicotv.app.crypto.ClavesDatos
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.app.data.db.entities.AparienciaEntity
import com.copernicotv.app.data.db.entities.CanalEntity
import com.copernicotv.app.data.db.entities.GrupoEntity
import com.copernicotv.core.crypto.CifradoAes
import com.copernicotv.core.identity.ClaveCanal
import com.copernicotv.core.m3u.CanalImportadoM3U
import com.copernicotv.core.m3u.CodecAtributosExtra
import com.copernicotv.core.reconciliacion.FilaPlan
import com.copernicotv.core.reconciliacion.ResultadoReconciliacion
import java.time.Instant

/**
 * Persiste el plan de filas de una Actualización en lotes.
 *
 * La versión anterior consultaba y escribía cada Apariencia con un viaje DAO
 * propio. Con 147 000 canales eso son cientos de miles de llamadas y la prueba
 * JVM tardaba ~55 minutos aunque las consultas usaran índice. Aquí cada lote
 * hace una consulta de Apariencias, una de tuplas pendientes y una escritura
 * agrupada por tabla, preservando las invariantes y el orden A→B que fija
 * [ActualizacionRepositorio] fuera de esta clase.
 */
internal class ActualizacionLotesEscritor(
    private val db: AppDatabase,
    private val clavesDatos: ClavesDatos,
) {
    private val escritorCanales = CanalEscritor(db)

    suspend fun aplicar(
        listaId: Long,
        plan: ResultadoReconciliacion,
        canalesImportados: List<CanalImportadoM3U>,
        ahora: Instant,
        grupos: MutableMap<String, Long>,
    ): Int {
        val estado = EstadoLoteEscritura(db.aparienciaDao().maxOrden(listaId) + 1)
        for (lote in plan.filas.chunked(LOTE_ESCRITURA)) {
            aplicarLote(listaId, lote, canalesImportados, ahora, grupos, estado)
            db.canalDao().actualizarTodos(estado.canales)
            db.aparienciaDao().actualizarTodas(estado.aparienciasActualizadas)
            db.aparienciaDao().insertarTodas(estado.aparienciasNuevas)
            db.aparienciaDao().eliminarPorIds(estado.aparienciasEliminadas)
            estado.limpiar()
        }
        return estado.actualizadas
    }

    private suspend fun aplicarLote(
        listaId: Long,
        lote: List<FilaPlan>,
        canalesImportados: List<CanalImportadoM3U>,
        ahora: Instant,
        grupos: MutableMap<String, Long>,
        estado: EstadoLoteEscritura,
    ) {
        val canalIds = lote.map { it.historicoId }
        val entidades = db.canalDao().porIds(canalIds).associateBy { it.id }
        val aparienciasPorCanal =
            db.aparienciaDao().porCanalesEnLista(listaId, canalIds).groupBy { it.canalId }
        val pendientes =
            db.aparienciaDao()
                .pendientesSinResolverDeCanales(listaId, canalIds)
                .map { it.canalId to it.clavePresentacion }
                .toHashSet()
        for (fila in lote) {
            aplicarFila(
                listaId, fila, entidades, aparienciasPorCanal, pendientes,
                canalesImportados, ahora, grupos, estado,
            )
        }
    }

    private suspend fun aplicarFila(
        listaId: Long,
        fila: FilaPlan,
        entidades: Map<Long, CanalEntity>,
        aparienciasPorCanal: Map<Long, List<AparienciaEntity>>,
        pendientes: Set<Pair<Long, String>>,
        canalesImportados: List<CanalImportadoM3U>,
        ahora: Instant,
        grupos: MutableMap<String, Long>,
        estado: EstadoLoteEscritura,
    ) {
        val historica = entidades[fila.historicoId] ?: return
        val declaracion = fila.declaracionIndice?.let { canalesImportados[it] }
        val actualizada = calcularCanalActualizado(fila, historica, declaracion, ahora) ?: return
        escritorCanales.validar(actualizada, tvgIdUnico(declaracion))
        estado.canales += actualizada
        if (declaracion != null) {
            sincronizarAparienciasEnLote(
                listaId = listaId,
                canalId = historica.id,
                declaracion = declaracion,
                ahora = ahora,
                grupos = grupos,
                existentes = aparienciasPorCanal[historica.id].orEmpty(),
                pendientes = pendientes,
                estado = estado,
            )
        }
        estado.actualizadas += 1
    }

    /**
     * Calcula la entidad resultante de aplicar el plan a una fila; `null` si la
     * fila no cambia (una ausencia o un C que sigue C no se toca, ADR-0004:58(c)).
     */
    @Suppress("CyclomaticComplexMethod")
    private fun calcularCanalActualizado(
        fila: FilaPlan,
        historica: CanalEntity,
        declaracion: CanalImportadoM3U?,
        ahora: Instant,
    ): CanalEntity? {
        val sinCambios =
            declaracion == null &&
                fila.estado == historica.estado &&
                fila.identidad.claveUnicidad == historica.claveUnicidad &&
                fila.evidencia == historica.evidencia &&
                !fila.decisionSuperada
        if (sinCambios) return null

        val decision =
            if (fila.decisionSuperada) marcarSuperada(historica.decision) else historica.decision
        val fechaDecision =
            if (fila.decisionSuperada) historica.fechaDecision ?: ahora else historica.fechaDecision
        return historica.copy(
            nombre = fila.identidad.nombre,
            tvgId = fila.identidad.tvgId,
            claveCanonica = fila.identidad.claveCanonica,
            claveUnicidad = fila.identidad.claveUnicidad,
            claveDerivable = fila.identidad.claveDerivable,
            estado = fila.estado,
            evidencia = fila.evidencia,
            decision = decision,
            fechaDecision = fechaDecision,
            urlStreamCifrada =
                declaracion?.let { cifrar(it.urlOriginal, "Canal", "url_stream_cifrada") }
                    ?: historica.urlStreamCifrada,
            urlStreamHmac = declaracion?.urlStreamHmac?.toByteArray(Charsets.UTF_8) ?: historica.urlStreamHmac,
            logoUrlCifrada =
                if (declaracion != null) cifrarLogo(declaracion.logoOriginal) else historica.logoUrlCifrada,
            logoUrlHmac = declaracion?.logoHmac?.toByteArray(Charsets.UTF_8) ?: historica.logoUrlHmac,
            atributosExtra =
                declaracion?.let { CodecAtributosExtra.codificar(it.atributosExtra) }
                    ?: historica.atributosExtra,
            actualizadoEn = ahora,
        )
    }

    /**
     * Sincroniza las Apariencias con los datos ya cargados para todo el lote.
     * Conserva el `orden` de las filas existentes; una Apariencia que la Fuente
     * deja de declarar se elimina sin tocar Canal ni relaciones (ADR-0004:58(c)).
     * Una tupla con migración pendiente se rechaza como en `reconciliar` (DC-05:635).
     */
    private suspend fun sincronizarAparienciasEnLote(
        listaId: Long,
        canalId: Long,
        declaracion: CanalImportadoM3U,
        ahora: Instant,
        grupos: MutableMap<String, Long>,
        existentes: List<AparienciaEntity>,
        pendientes: Set<Pair<Long, String>>,
        estado: EstadoLoteEscritura,
    ) {
        val porClave = existentes.associateBy { it.clavePresentacion }
        val declaradas = declaracion.apariencias.map { it.clavePresentacion }.toSet()
        for (apariencia in declaracion.apariencias) {
            if (Pair(canalId, apariencia.clavePresentacion) in pendientes) {
                throw ImportacionRechazada(AparienciaEscritor.CODIGO_PENDIENTE)
            }
            val grupoId = apariencia.groupTitle?.let { insertarGrupo(listaId, it, grupos) }
            val existente = porClave[apariencia.clavePresentacion]
            if (existente == null) {
                estado.aparienciasNuevas +=
                    AparienciaEntity(
                        listaId = listaId,
                        canalId = canalId,
                        grupoId = grupoId,
                        orden = estado.ordenSiguiente++,
                        groupTitle = apariencia.groupTitle,
                        tvgName = apariencia.tvgName,
                        clavePresentacion = apariencia.clavePresentacion,
                        repeticiones = apariencia.repeticiones,
                        creadoEn = ahora,
                        actualizadoEn = ahora,
                    )
            } else {
                estado.aparienciasActualizadas +=
                    existente.copy(
                        grupoId = grupoId,
                        groupTitle = apariencia.groupTitle,
                        tvgName = apariencia.tvgName,
                        repeticiones = apariencia.repeticiones,
                        actualizadoEn = ahora,
                    )
            }
        }
        for (existente in existentes) {
            if (existente.clavePresentacion !in declaradas) {
                estado.aparienciasEliminadas += existente.id
            }
        }
    }

    private suspend fun insertarGrupo(
        listaId: Long,
        nombre: String,
        grupos: MutableMap<String, Long>,
    ): Long {
        grupos[nombre]?.let { return it }
        val id =
            db.grupoDao().porNombre(listaId, nombre)?.id
                ?: db.grupoDao().insertar(GrupoEntity(listaId = listaId, nombre = nombre))
        grupos[nombre] = id
        return id
    }

    private fun tvgIdUnico(canal: CanalImportadoM3U?): Boolean {
        val tvg = canal?.tvgId ?: return false
        return canal.claveUnicidad == ClaveCanal.PREFIJO_TVG + tvg
    }

    private fun marcarSuperada(decision: String?): String? =
        decision?.let { if (it.endsWith(SUFIJO_SUPERADA)) it else it + SUFIJO_SUPERADA }

    private fun cifrar(
        valor: String,
        tabla: String,
        columna: String,
    ): ByteArray =
        CifradoAes.cifrarDatos(
            clavesDatos.clave(),
            CifradoAes.aad(tabla, columna),
            valor.toByteArray(Charsets.UTF_8),
        )

    private fun cifrarLogo(logo: String?): ByteArray? = logo?.let { cifrar(it, "Canal", "logo_url_cifrada") }

    private companion object {
        const val LOTE_ESCRITURA = 500
        const val SUFIJO_SUPERADA = ":superada"
    }
}

/** Acumulador de escrituras de un lote, para ejecutarlas agrupadas. */
private class EstadoLoteEscritura(
    var ordenSiguiente: Int,
) {
    val canales = ArrayList<CanalEntity>()
    val aparienciasActualizadas = ArrayList<AparienciaEntity>()
    val aparienciasNuevas = ArrayList<AparienciaEntity>()
    val aparienciasEliminadas = ArrayList<Long>()
    var actualizadas = 0

    fun limpiar() {
        canales.clear()
        aparienciasActualizadas.clear()
        aparienciasNuevas.clear()
        aparienciasEliminadas.clear()
    }
}
