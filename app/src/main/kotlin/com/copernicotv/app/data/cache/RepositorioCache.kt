package com.copernicotv.app.data.cache

import android.util.Log
import com.copernicotv.app.crypto.ClavesApp
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.app.data.db.entities.CacheM3UEntity
import com.copernicotv.app.data.db.entities.CacheXmltvEntity
import com.copernicotv.core.cache.ClaveCache
import com.copernicotv.core.cache.DecisionEscritura
import com.copernicotv.core.cache.EntradaCache
import com.copernicotv.core.cache.Frescura
import com.copernicotv.core.cache.FrescuraCache
import com.copernicotv.core.cache.PoliticaCache
import com.copernicotv.core.cache.PresupuestoCache
import com.copernicotv.core.cache.TipoCache
import com.copernicotv.core.crypto.CifradoAes
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** Resultado de leer una copia de transporte. */
sealed interface ResultadoLecturaCache {
    /**
     * Copia disponible. [flujo] está descifrado y debe cerrarse: mientras
     * permanezca abierto, la entrada no es candidata a evicción.
     */
    class Copia(
        val frescura: Frescura,
        val fetchedAt: Instant,
        val expiresAt: Instant,
        val tamanoBytes: Long,
        val flujo: InputStream,
    ) : ResultadoLecturaCache

    /** No hay copia para la clave: el llamador debe descargar. */
    data object SinCopia : ResultadoLecturaCache

    /** Hay fila pero el archivo falta o no descifra: no se sirve como copia. */
    data object Ilegible : ResultadoLecturaCache
}

/** Resultado de actualizar una copia de transporte. */
sealed interface ResultadoActualizacion {
    /** La copia nueva reemplazó a la anterior y recalculó `expires_at`. */
    data class Reemplazada(
        val fetchedAt: Instant,
        val expiresAt: Instant,
        val tamanoBytes: Long,
    ) : ResultadoActualizacion

    /**
     * No se almacenó por presupuesto. La copia anterior, si existía, sigue
     * intacta; nunca se presenta como actualización exitosa.
     */
    data class Omitida(val motivo: String) : ResultadoActualizacion

    /**
     * La descarga o la escritura falló. [copiaConservada] indica si quedó una
     * copia previa utilizable; el llamador la sirve como `stale`, nunca como
     * contenido actualizado.
     */
    data class Fallo(
        val codigo: String,
        val copiaConservada: Boolean,
    ) : ResultadoActualizacion
}

/** Informe del barrido de huérfanos (DC-09, S51d). */
data class InformeBarrido(
    val borrados: Int,
    val errores: List<String>,
    val filasSinArchivo: Int,
)

/**
 * Caché de transporte M3U/XMLTV (E2-T7, DC-09, S51a–S51h).
 *
 * Solo cubre descargas HTTP/HTTPS de M3U/XMLTV: nunca archivos SAF locales,
 * imágenes/logos ni streams. La frescura de una copia es un estado propio y
 * distinto del Estado de Actualización y de la Disponibilidad de Reproducción
 * (S51e): este repositorio no escribe `EstadoActualizacion`.
 *
 * `expires_at` se calcula al momento de la descarga y no se recalcula al
 * cambiar el intervalo; la frescura se evalúa siempre contra el valor
 * almacenado. `expires_at` no inicia descargas: [leer] solo informa.
 *
 * La caché XMLTV es transporte, no fuente de verdad (S51f): servir una copia
 * `stale` habilita una actualización explícita, jamás sustituye la generación
 * publicada de EPG.
 */
class RepositorioCache(
    private val db: AppDatabase,
    private val almacen: AlmacenCache,
    private val descargador: Descargador,
    private val claves: ClavesApp,
    private val presupuesto: PresupuestoCache = PresupuestoCache(),
) {
    /** Referencias abiertas para lectura: nunca se evictan mientras se sirven. */
    private val enUso = ConcurrentHashMap.newKeySet<String>()

    suspend fun leer(
        clave: ClaveCache,
        ahora: Instant,
    ): ResultadoLecturaCache {
        val fila = filaPorClave(clave) ?: return ResultadoLecturaCache.SinCopia
        val flujo = almacen.abrir(fila.archivoRef, clave.tipo) ?: return ResultadoLecturaCache.Ilegible
        enUso.add(fila.archivoRef)
        return ResultadoLecturaCache.Copia(
            frescura = FrescuraCache.evaluar(fila.expiresAt, ahora),
            fetchedAt = fila.fetchedAt,
            expiresAt = fila.expiresAt,
            tamanoBytes = fila.tamanoBytes,
            flujo = vigilarCierre(fila.archivoRef, flujo),
        )
    }

    /**
     * Descarga [url] y la publica como copia de [clave].
     *
     * Una descarga fallida no toca la copia previa; un presupuesto insuficiente
     * tampoco la evicta parcialmente (DC-09).
     */
    suspend fun actualizar(
        clave: ClaveCache,
        url: String,
        intervalo: Duration,
        ahora: Instant,
    ): ResultadoActualizacion {
        val temporal = almacen.temporalNuevo()
        return try {
            when (val descarga = descargador.descargar(url, temporal)) {
                is ResultadoDescarga.Fallo ->
                    ResultadoActualizacion.Fallo(descarga.codigo, existeCopia(clave))
                is ResultadoDescarga.Ok ->
                    almacenar(clave, url, intervalo, ahora, temporal)
            }
        } finally {
            temporal.delete()
        }
    }

    /** Invalida todas las copias M3U de una Lista (cambio de URL o baja). */
    suspend fun invalidarPorLista(listaId: Long) {
        val filas = db.cacheM3UDao().porLista(listaId, TipoCache.M3U.codigo)
        for (fila in filas) {
            borrarFila(TipoCache.M3U, fila.id, fila.archivoRef)
        }
    }

    /** Invalida todas las copias XMLTV de una FuenteGuia (cambio de URL o baja). */
    suspend fun invalidarPorFuenteGuia(fuenteGuiaId: Long) {
        val filas = db.cacheXmltvDao().porFuenteGuia(fuenteGuiaId, TipoCache.XMLTV.codigo)
        for (fila in filas) {
            borrarFila(TipoCache.XMLTV, fila.id, fila.archivoRef)
        }
    }

    /**
     * Invalida las copias del mismo propietario y tipo cuya `url_hmac` no es la
     * vigente: un cambio de URL deja la copia anterior inutilizable.
     */
    suspend fun invalidarOtrasUrls(vigente: ClaveCache) {
        val vigenteBytes = vigente.urlHmac.toByteArray(Charsets.UTF_8)
        when (vigente) {
            is ClaveCache.M3U -> {
                val obsoletas =
                    db
                        .cacheM3UDao()
                        .porLista(vigente.listaId, vigente.tipo.codigo)
                        .filterNot { it.urlHmac.contentEquals(vigenteBytes) }
                for (fila in obsoletas) {
                    borrarFila(TipoCache.M3U, fila.id, fila.archivoRef)
                }
            }
            is ClaveCache.Xmltv -> {
                val obsoletas =
                    db
                        .cacheXmltvDao()
                        .porFuenteGuia(vigente.fuenteGuiaId, vigente.tipo.codigo)
                        .filterNot { it.urlHmac.contentEquals(vigenteBytes) }
                for (fila in obsoletas) {
                    borrarFila(TipoCache.XMLTV, fila.id, fila.archivoRef)
                }
            }
        }
    }

    /**
     * Borra archivos sin fila con más de [gracia] de antigüedad y purga filas
     * cuyo archivo desapareció. Los errores de borrado se registran con el
     * nombre opaco, nunca con la URL ni con contenido.
     */
    suspend fun barrerHuerfanos(
        ahora: Instant,
        gracia: Duration = GRACIA_HUERFANOS,
    ): InformeBarrido {
        val referencias = referencias()
        val limite = ahora.minus(gracia)
        val borrados = mutableListOf<String>()
        val errores = mutableListOf<String>()
        val candidatos = almacen.nombres().filterNot { it in referencias }.filter { vencido(it, limite) }
        for (nombre in candidatos) {
            if (almacen.borrar(nombre)) {
                borrados += nombre
            } else {
                Log.w(TAG, "no se pudo borrar el archivo de caché $nombre")
                errores += nombre
            }
        }
        val purgadas = purgarFilasSinArchivo()
        return InformeBarrido(borrados.size, errores, purgadas)
    }

    private suspend fun almacenar(
        clave: ClaveCache,
        url: String,
        intervalo: Duration,
        ahora: Instant,
        temporal: File,
    ): ResultadoActualizacion {
        val tamanoCifrado = temporal.length() + SOBRE_BYTES
        val almacenadas = entradas(ahora)
        val decision =
            PoliticaCache.planificarEscritura(
                nueva = clave,
                tamanoNuevo = tamanoCifrado,
                existentes = almacenadas.map { it.entrada },
                libreBytes = almacen.libreBytes(),
                presupuesto = presupuesto,
            )
        return when (decision) {
            DecisionEscritura.OmitidaPorTamano -> ResultadoActualizacion.Omitida(MOTIVO_TAMANO)
            DecisionEscritura.SinEspacio -> ResultadoActualizacion.Omitida(MOTIVO_SIN_ESPACIO)
            is DecisionEscritura.Almacenar ->
                publicar(clave, url, intervalo, ahora, temporal, decision.evictar, almacenadas)
        }
    }

    private suspend fun publicar(
        clave: ClaveCache,
        url: String,
        intervalo: Duration,
        ahora: Instant,
        temporal: File,
        evictar: List<Long>,
        almacenadas: List<EntradaAlmacenada>,
    ): ResultadoActualizacion {
        evictar(evictar, almacenadas)
        val anterior = almacenadas.firstOrNull { it.entrada.clave == clave }?.archivoRef
        val nombre = almacen.nombreNuevo()
        return try {
            almacen.escribir(nombre, temporal, clave.tipo)
            val tamano = almacen.tamano(nombre)
            val expiresAt = FrescuraCache.expiracion(ahora, intervalo)
            guardar(
                clave,
                DatosFila(
                    urlCifrada = cifrarUrl(clave.tipo, url),
                    urlHmac = clave.urlHmac.toByteArray(Charsets.UTF_8),
                    fetchedAt = ahora,
                    expiresAt = expiresAt,
                    tamanoBytes = tamano,
                    archivoRef = nombre,
                ),
            )
            retirarAnterior(anterior, nombre)
            ResultadoActualizacion.Reemplazada(ahora, expiresAt, tamano)
        } catch (_: IOException) {
            Log.w(TAG, "no se pudo escribir la copia de caché")
            almacen.borrar(nombre)
            ResultadoActualizacion.Fallo(ERROR_ESCRITURA, existeCopia(clave))
        }
    }

    /** Retira el contenido reemplazado; si falla, queda huérfano para el barrido. */
    private fun retirarAnterior(
        anterior: String?,
        nuevo: String,
    ) {
        if (anterior == null || anterior == nuevo) return
        if (!almacen.borrar(anterior)) {
            Log.w(TAG, "no se pudo borrar el archivo reemplazado $anterior")
        }
    }

    private suspend fun evictar(
        ids: List<Long>,
        almacenadas: List<EntradaAlmacenada>,
    ) {
        val porId = almacenadas.associateBy { it.entrada.id }
        for (unico in ids) {
            val entrada = porId[unico] ?: continue
            borrarFila(entrada.entrada.clave.tipo, idBase(unico), entrada.archivoRef)
        }
    }

    private suspend fun borrarFila(
        tipo: TipoCache,
        id: Long,
        archivoRef: String,
    ) {
        when (tipo) {
            TipoCache.M3U -> db.cacheM3UDao().eliminar(id)
            TipoCache.XMLTV -> db.cacheXmltvDao().eliminar(id)
        }
        if (!almacen.borrar(archivoRef)) {
            Log.w(TAG, "no se pudo borrar el archivo de caché $archivoRef")
        }
    }

    private suspend fun entradas(ahora: Instant): List<EntradaAlmacenada> {
        val m3u = db.cacheM3UDao().todas().map { it.aEntrada(ahora) }
        val xmltv = db.cacheXmltvDao().todas().map { it.aEntrada(ahora) }
        return m3u + xmltv
    }

    private suspend fun referencias(): Set<String> {
        val m3u = db.cacheM3UDao().todas().map { it.archivoRef }
        val xmltv = db.cacheXmltvDao().todas().map { it.archivoRef }
        return (m3u + xmltv).toSet()
    }

    private fun vencido(
        nombre: String,
        limite: Instant,
    ): Boolean = Instant.ofEpochMilli(almacen.archivo(nombre).lastModified()).isBefore(limite)

    private suspend fun purgarFilasSinArchivo(): Int {
        var purgadas = 0
        for (fila in db.cacheM3UDao().todas()) {
            if (almacen.existe(fila.archivoRef)) continue
            purgadas += db.cacheM3UDao().eliminar(fila.id)
        }
        for (fila in db.cacheXmltvDao().todas()) {
            if (almacen.existe(fila.archivoRef)) continue
            purgadas += db.cacheXmltvDao().eliminar(fila.id)
        }
        return purgadas
    }

    private suspend fun existeCopia(clave: ClaveCache): Boolean = filaPorClave(clave) != null

    private suspend fun filaPorClave(clave: ClaveCache): FilaCache? =
        when (clave) {
            is ClaveCache.M3U ->
                db
                    .cacheM3UDao()
                    .porClave(
                        clave.fuenteId,
                        clave.listaId,
                        clave.tipo.codigo,
                        clave.urlHmac.toByteArray(Charsets.UTF_8),
                    )?.let { FilaCache(it.archivoRef, it.fetchedAt, it.expiresAt, it.tamanoBytes) }
            is ClaveCache.Xmltv ->
                db
                    .cacheXmltvDao()
                    .porClave(
                        clave.fuenteGuiaId,
                        clave.tipo.codigo,
                        clave.urlHmac.toByteArray(Charsets.UTF_8),
                    )?.let { FilaCache(it.archivoRef, it.fetchedAt, it.expiresAt, it.tamanoBytes) }
        }

    private suspend fun guardar(
        clave: ClaveCache,
        datos: DatosFila,
    ) {
        when (clave) {
            is ClaveCache.M3U -> {
                val existente =
                    db
                        .cacheM3UDao()
                        .porClave(
                            clave.fuenteId,
                            clave.listaId,
                            clave.tipo.codigo,
                            datos.urlHmac,
                        )
                if (existente == null) {
                    db.cacheM3UDao().insertar(
                        CacheM3UEntity(
                            fuenteId = clave.fuenteId,
                            listaId = clave.listaId,
                            urlCifrada = datos.urlCifrada,
                            urlHmac = datos.urlHmac,
                            tipo = clave.tipo.codigo,
                            fetchedAt = datos.fetchedAt,
                            expiresAt = datos.expiresAt,
                            tamanoBytes = datos.tamanoBytes,
                            estadoFrescura = ESTADO_VALIDA,
                            archivoRef = datos.archivoRef,
                        ),
                    )
                } else {
                    db.cacheM3UDao().actualizar(
                        existente.id,
                        datos.urlCifrada,
                        datos.urlHmac,
                        datos.fetchedAt,
                        datos.expiresAt,
                        datos.tamanoBytes,
                        ESTADO_VALIDA,
                        datos.archivoRef,
                    )
                }
            }
            is ClaveCache.Xmltv -> {
                val existente =
                    db
                        .cacheXmltvDao()
                        .porClave(clave.fuenteGuiaId, clave.tipo.codigo, datos.urlHmac)
                if (existente == null) {
                    db.cacheXmltvDao().insertar(
                        CacheXmltvEntity(
                            fuenteGuiaId = clave.fuenteGuiaId,
                            urlCifrada = datos.urlCifrada,
                            urlHmac = datos.urlHmac,
                            tipo = clave.tipo.codigo,
                            fetchedAt = datos.fetchedAt,
                            expiresAt = datos.expiresAt,
                            tamanoBytes = datos.tamanoBytes,
                            estadoFrescura = ESTADO_VALIDA,
                            archivoRef = datos.archivoRef,
                        ),
                    )
                } else {
                    db.cacheXmltvDao().actualizar(
                        existente.id,
                        datos.urlCifrada,
                        datos.urlHmac,
                        datos.fetchedAt,
                        datos.expiresAt,
                        datos.tamanoBytes,
                        ESTADO_VALIDA,
                        datos.archivoRef,
                    )
                }
            }
        }
    }

    private fun cifrarUrl(
        tipo: TipoCache,
        url: String,
    ): ByteArray =
        CifradoAes.cifrar(
            claves.claveAes,
            aadUrl(tipo),
            url.toByteArray(Charsets.UTF_8),
        )

    private fun aadUrl(tipo: TipoCache): String =
        when (tipo) {
            TipoCache.M3U -> CifradoAes.aad("CacheM3U", "url_cifrada")
            TipoCache.XMLTV -> CifradoAes.aad("CacheXmltv", "url_cifrada")
        }

    private fun vigilarCierre(
        archivoRef: String,
        flujo: InputStream,
    ): InputStream =
        object : FilterInputStream(flujo) {
            override fun close() {
                try {
                    super.close()
                } finally {
                    enUso.remove(archivoRef)
                }
            }
        }

    private fun CacheM3UEntity.aEntrada(ahora: Instant): EntradaAlmacenada =
        EntradaAlmacenada(
            entrada =
                EntradaCache(
                    id = idUnico(TipoCache.M3U, id),
                    clave = ClaveCache.M3U(fuenteId, listaId, urlHmac.toString(Charsets.UTF_8)),
                    tamanoBytes = tamanoBytes,
                    fetchedAt = fetchedAt,
                    frescura = FrescuraCache.evaluar(expiresAt, ahora),
                    enUso = archivoRef in enUso,
                ),
            archivoRef = archivoRef,
        )

    private fun CacheXmltvEntity.aEntrada(ahora: Instant): EntradaAlmacenada =
        EntradaAlmacenada(
            entrada =
                EntradaCache(
                    id = idUnico(TipoCache.XMLTV, id),
                    clave = ClaveCache.Xmltv(fuenteGuiaId, urlHmac.toString(Charsets.UTF_8)),
                    tamanoBytes = tamanoBytes,
                    fetchedAt = fetchedAt,
                    frescura = FrescuraCache.evaluar(expiresAt, ahora),
                    enUso = archivoRef in enUso,
                ),
            archivoRef = archivoRef,
        )

    private data class FilaCache(
        val archivoRef: String,
        val fetchedAt: Instant,
        val expiresAt: Instant,
        val tamanoBytes: Long,
    )

    private data class DatosFila(
        val urlCifrada: ByteArray,
        val urlHmac: ByteArray,
        val fetchedAt: Instant,
        val expiresAt: Instant,
        val tamanoBytes: Long,
        val archivoRef: String,
    )

    private data class EntradaAlmacenada(
        val entrada: EntradaCache,
        val archivoRef: String,
    )

    private companion object {
        const val TAG = "RepositorioCache"
        const val SOBRE_BYTES = 1L + CifradoAes.LONGITUD_IV + 16L
        const val ESTADO_VALIDA = "valida"
        const val MOTIVO_TAMANO = "cache_omitida_por_tamano"
        const val MOTIVO_SIN_ESPACIO = "cache_sin_espacio"
        const val ERROR_ESCRITURA = "cache_no_escribible"

        val GRACIA_HUERFANOS: Duration = Duration.ofHours(24)

        /**
         * `PoliticaCache` identifica entradas por `id` y ordena por él; los
         * `id` de `CacheM3U` y `CacheXmltv` son secuencias independientes y
         * pueden coincidir. El bit bajo del identificador que se entrega a la
         * política distingue la tabla y el resto conserva el `id` real.
         */
        fun idUnico(
            tipo: TipoCache,
            id: Long,
        ): Long = (id shl 1) or if (tipo == TipoCache.M3U) 0L else 1L

        fun idBase(unico: Long): Long = unico ushr 1
    }
}
