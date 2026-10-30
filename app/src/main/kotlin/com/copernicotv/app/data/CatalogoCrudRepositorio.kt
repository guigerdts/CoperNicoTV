package com.copernicotv.app.data

import android.database.sqlite.SQLiteConstraintException
import android.util.Log
import androidx.room.withTransaction
import com.copernicotv.app.crypto.ClavesApp
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.app.data.db.entities.AparienciaEntity
import com.copernicotv.app.data.db.entities.CanalEntity
import com.copernicotv.app.data.db.entities.FavoritoEntity
import com.copernicotv.app.data.db.entities.FuenteEntity
import com.copernicotv.app.data.db.entities.ListaEntity
import com.copernicotv.app.data.db.entities.OcultacionCanalEntity
import com.copernicotv.core.crypto.CifradoAes
import com.copernicotv.core.crypto.HmacUrl
import com.copernicotv.core.crypto.ResultadoDescifrado
import com.copernicotv.core.identity.CampoUrl
import com.copernicotv.core.identity.CanonicalizadorUrl
import com.copernicotv.core.identity.ClaveCanal
import com.copernicotv.core.identity.ClavePresentacion
import com.copernicotv.core.identity.EstadoCanal
import com.copernicotv.core.identity.ResultadoCanonizacion
import com.copernicotv.core.m3u.ImportadorM3U
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Resultado de una acción de catálogo sin excepciones hacia la UI. */
sealed interface ResultadoAccion {
    data object Ok : ResultadoAccion

    data class Error(val codigo: String) : ResultadoAccion
}

/** Lista visible en el selector del catálogo. */
data class ListaResumen(
    val id: Long,
    val nombre: String,
    val activa: Boolean,
)

/**
 * Acciones de catálogo que no forman parte de la búsqueda paginada: CRUD de
 * Lista y Canal, Favoritos, Ocultaciones y reordenamiento manual de Apariencias.
 *
 * La UI no accede a DAOs (K5). El reordenamiento reutiliza `Apariencia.orden` y
 * nunca toca `ColeccionCanal.posicion` ni la preferencia A/B/C. La preferencia
 * de ordenación se limpia al eliminar la Lista y se barre al iniciar (DC-08).
 */
@Singleton
class CatalogoCrudRepositorio
    @Inject
    constructor(
        private val db: AppDatabase,
        private val claves: ClavesApp,
        private val preferencias: PreferenciasOrden,
    ) {
        fun listas(): Flow<List<ListaResumen>> =
            db.listaDao().observarTodas().map { listas ->
                listas.map { lista ->
                    ListaResumen(
                        id = lista.id,
                        nombre = lista.nombre,
                        activa = lista.estado == ESTADO_ACTIVA,
                    )
                }
            }

        suspend fun crearLista(nombre: String): ResultadoAccion =
            withContext(Dispatchers.IO) {
                val limpio = nombre.trim()
                if (limpio.isEmpty()) return@withContext ResultadoAccion.Error(ERROR_NOMBRE_VACIO)
                val uuid = UUID.randomUUID().toString()
                val ubicacion = "content://com.copernicotv.app.lista/$uuid"
                val canonica =
                    CanonicalizadorUrl.canonizar(ubicacion, CampoUrl.LISTA_UBICACION)
                        as? ResultadoCanonizacion.Ok
                        ?: return@withContext ResultadoAccion.Error(ERROR_UBICACION_INVALIDA)
                try {
                    db.withTransaction {
                        val ahora = Instant.now()
                        val fuenteId =
                            db.fuenteDao().insertar(
                                FuenteEntity(
                                    nombre = "Lista local $uuid",
                                    estado = ESTADO_ACTIVA,
                                    usarIntervaloGlobal = true,
                                    creadoEn = ahora,
                                    actualizadoEn = ahora,
                                ),
                            )
                        db.listaDao().desactivarTodas()
                        db.listaDao().insertar(
                            ListaEntity(
                                fuenteId = fuenteId,
                                nombre = limpio,
                                ubicacionCifrada =
                                    CifradoAes.cifrar(
                                        claves.claveAes,
                                        CifradoAes.aad("Lista", "ubicacion_cifrada"),
                                        ubicacion.toByteArray(Charsets.UTF_8),
                                    ),
                                ubicacionHmac =
                                    HmacUrl
                                        .calcularSobre(
                                            clave = claves.claveHmac,
                                            generacion = ImportadorM3U.GENERACION_HMAC,
                                            campo = CampoUrl.LISTA_UBICACION,
                                            clase = canonica.clase,
                                            urlCanonica = canonica.urlCanonica,
                                        ).toByteArray(Charsets.UTF_8),
                                esquema = canonica.urlCanonica.substringBefore(':'),
                                orden = db.listaDao().maxOrden() + 1,
                                estado = ESTADO_ACTIVA,
                                creadoEn = ahora,
                                actualizadoEn = ahora,
                            ),
                        )
                    }
                    ResultadoAccion.Ok
                } catch (error: Exception) {
                    ResultadoAccion.Error(ERROR_CREAR_LISTA)
                }
            }

        suspend fun renombrarLista(
            listaId: Long,
            nombre: String,
        ): ResultadoAccion =
            withContext(Dispatchers.IO) {
                val limpio = nombre.trim()
                if (limpio.isEmpty()) return@withContext ResultadoAccion.Error(ERROR_NOMBRE_VACIO)
                val actualizadas = db.listaDao().renombrar(listaId, limpio, Instant.now())
                if (actualizadas == 0) {
                    ResultadoAccion.Error(ERROR_LISTA_NO_EXISTE)
                } else {
                    ResultadoAccion.Ok
                }
            }

        suspend fun eliminarLista(listaId: Long): ResultadoAccion =
            withContext(Dispatchers.IO) {
                val lista =
                    db.listaDao().porId(listaId)
                        ?: return@withContext ResultadoAccion.Error(ERROR_LISTA_NO_EXISTE)
                try {
                    db.withTransaction {
                        val ahora = Instant.now()
                        db.listaDao().eliminar(listaId)
                        if (db.listaDao().activa() == null) {
                            db.listaDao().listar().firstOrNull()?.let { siguiente ->
                                db.listaDao().activar(siguiente.id, ahora)
                            }
                        }
                        if (db.listaDao().contarPorFuente(lista.fuenteId) == 0) {
                            db.fuenteDao().eliminar(lista.fuenteId)
                        }
                    }
                } catch (error: Exception) {
                    return@withContext ResultadoAccion.Error(ERROR_ELIMINAR_LISTA)
                }
                runCatching { preferencias.limpiar(listaId) }
                    .onFailure { Log.w(TAG, "No se pudo limpiar la preferencia de orden") }
                ResultadoAccion.Ok
            }

        /**
         * Elimina las preferencias de ordenación de Listas que ya no existen.
         *
         * Se ejecuta al construir el ViewModel. Si DataStore falla, la Lista
         * sigue operativa y el barrido se reintenta en el próximo inicio.
         */
        suspend fun sincronizarPreferenciasDeOrden() {
            withContext(Dispatchers.IO) {
                runCatching {
                    val idsValidos = db.listaDao().listar().map { it.id }.toSet()
                    preferencias.barrerHuerfanas(idsValidos)
                }.onFailure { Log.w(TAG, "No se pudieron barrer las preferencias de orden") }
            }
        }

        suspend fun seleccionarLista(listaId: Long): ResultadoAccion =
            withContext(Dispatchers.IO) {
                db.listaDao().porId(listaId)
                    ?: return@withContext ResultadoAccion.Error(ERROR_LISTA_NO_EXISTE)
                db.withTransaction {
                    val ahora = Instant.now()
                    db.listaDao().desactivarTodas()
                    db.listaDao().activar(listaId, ahora)
                }
                ResultadoAccion.Ok
            }

        suspend fun crearCanal(
            nombre: String?,
            url: String,
        ): ResultadoAccion =
            withContext(Dispatchers.IO) {
                val lista =
                    db.listaDao().activa()
                        ?: return@withContext ResultadoAccion.Error(ERROR_LISTA_NO_EXISTE)
                val canonica =
                    CanonicalizadorUrl.canonizar(url.trim(), CampoUrl.CANAL_URL_STREAM)
                        as? ResultadoCanonizacion.Ok
                        ?: return@withContext ResultadoAccion.Error(ERROR_URL_INVALIDA)
                val nombrePersistido = nombre?.takeIf { it.isNotEmpty() }
                val identidad =
                    ClaveCanal.derivar(
                        nombre = nombrePersistido,
                        urlCanonica = canonica.urlCanonica,
                        tvgId = null,
                        tvgIdUnicoEnFuente = false,
                        estado = EstadoCanal.A,
                    )
                try {
                    db.withTransaction {
                        val ahora = Instant.now()
                        val canalId =
                            CanalEscritor(db).insertar(
                                CanalEntity(
                                    fuenteId = lista.fuenteId,
                                    nombre = nombrePersistido,
                                    urlStreamCifrada =
                                        CifradoAes.cifrar(
                                            claves.claveAes,
                                            CifradoAes.aad("Canal", "url_stream_cifrada"),
                                            url.trim().toByteArray(Charsets.UTF_8),
                                        ),
                                    urlStreamHmac =
                                        HmacUrl
                                            .calcularSobre(
                                                clave = claves.claveHmac,
                                                generacion = ImportadorM3U.GENERACION_HMAC,
                                                campo = CampoUrl.CANAL_URL_STREAM,
                                                clase = canonica.clase,
                                                urlCanonica = canonica.urlCanonica,
                                            ).toByteArray(Charsets.UTF_8),
                                    tvgId = null,
                                    claveCanonica = identidad.claveCanonica,
                                    claveUnicidad = identidad.claveUnicidad,
                                    claveDerivable = identidad.claveDerivable,
                                    estado = EstadoCanal.A,
                                    creadoEn = ahora,
                                    actualizadoEn = ahora,
                                ),
                                tvgIdUnicoEnFuente = false,
                            )
                        db.aparienciaDao().insertar(
                            AparienciaEntity(
                                listaId = lista.id,
                                canalId = canalId,
                                grupoId = null,
                                orden = db.aparienciaDao().maxOrden(lista.id) + 1,
                                groupTitle = null,
                                tvgName = null,
                                clavePresentacion = ClavePresentacion.clave(null, null),
                                repeticiones = 1,
                                creadoEn = ahora,
                                actualizadoEn = ahora,
                            ),
                        )
                    }
                    ResultadoAccion.Ok
                } catch (error: ImportacionRechazada) {
                    ResultadoAccion.Error(error.codigo)
                } catch (error: SQLiteConstraintException) {
                    ResultadoAccion.Error(ERROR_CANAL_DUPLICADO)
                } catch (error: Exception) {
                    ResultadoAccion.Error(ERROR_CREAR_CANAL)
                }
            }

        /**
         * Edita el nombre de un Canal. La URL se conserva: desencriptarla para
         * mostrarla en la UI expondría un dato sensible sin necesidad.
         */
        suspend fun editarNombreCanal(
            canalId: Long,
            nombre: String?,
        ): ResultadoAccion =
            withContext(Dispatchers.IO) {
                val canal =
                    db.canalDao().porId(canalId)
                        ?: return@withContext ResultadoAccion.Error(ERROR_CANAL_NO_EXISTE)
                val urlOriginal =
                    when (
                        val descifrado =
                            CifradoAes.descifrar(
                                claves.claveAes,
                                CifradoAes.aad("Canal", "url_stream_cifrada"),
                                canal.urlStreamCifrada,
                            )
                    ) {
                        is ResultadoDescifrado.Ok -> String(descifrado.bytes, Charsets.UTF_8)
                        is ResultadoDescifrado.Fallo -> return@withContext ResultadoAccion.Error(ERROR_URL_ILEGIBLE)
                    }
                val canonica =
                    CanonicalizadorUrl.canonizar(urlOriginal, CampoUrl.CANAL_URL_STREAM)
                        as? ResultadoCanonizacion.Ok
                        ?: return@withContext ResultadoAccion.Error(ERROR_URL_INVALIDA)
                // Conserva la forma de unicidad ya persistida: si el Canal se
                // importó con `tvg-id` único, renombrarlo no debe cambiarlo a
                // la clave canónica por nombre.
                val tvgIdUnicoEnFuente =
                    canal.claveUnicidad?.startsWith(ClaveCanal.PREFIJO_TVG) == true
                val identidad =
                    ClaveCanal.derivar(
                        nombre = nombre,
                        urlCanonica = canonica.urlCanonica,
                        tvgId = canal.tvgId,
                        tvgIdUnicoEnFuente = tvgIdUnicoEnFuente,
                        estado = canal.estado,
                    )
                try {
                    val actualizadas =
                        CanalEscritor(db).actualizar(
                            canal.copy(
                                nombre = nombre,
                                claveCanonica = identidad.claveCanonica,
                                claveUnicidad = identidad.claveUnicidad,
                                claveDerivable = identidad.claveDerivable,
                                actualizadoEn = Instant.now(),
                            ),
                            tvgIdUnicoEnFuente = tvgIdUnicoEnFuente,
                        )
                    if (actualizadas == 0) {
                        ResultadoAccion.Error(ERROR_CANAL_NO_EXISTE)
                    } else {
                        ResultadoAccion.Ok
                    }
                } catch (error: ImportacionRechazada) {
                    ResultadoAccion.Error(error.codigo)
                } catch (error: SQLiteConstraintException) {
                    ResultadoAccion.Error(ERROR_CANAL_DUPLICADO)
                } catch (error: Exception) {
                    ResultadoAccion.Error(ERROR_EDITAR_CANAL)
                }
            }

        suspend fun eliminarCanal(canalId: Long): ResultadoAccion =
            withContext(Dispatchers.IO) {
                val eliminados = db.canalDao().eliminar(canalId)
                if (eliminados == 0) {
                    ResultadoAccion.Error(ERROR_CANAL_NO_EXISTE)
                } else {
                    ResultadoAccion.Ok
                }
            }

        suspend fun alternarFavorito(canalId: Long): ResultadoAccion =
            withContext(Dispatchers.IO) {
                if (db.favoritoDao().existe(canalId) > 0) {
                    db.favoritoDao().eliminarPorCanal(canalId)
                } else {
                    db.favoritoDao().insertar(
                        FavoritoEntity(canalId = canalId, creadoEn = Instant.now()),
                    )
                }
                ResultadoAccion.Ok
            }

        suspend fun alternarOculto(canalId: Long): ResultadoAccion =
            withContext(Dispatchers.IO) {
                if (db.ocultacionCanalDao().existe(canalId) > 0) {
                    db.ocultacionCanalDao().eliminarPorCanal(canalId)
                } else {
                    db.ocultacionCanalDao().insertar(
                        OcultacionCanalEntity(canalId = canalId, creadoEn = Instant.now()),
                    )
                }
                ResultadoAccion.Ok
            }

        /**
         * Mueve un Canal una posición arriba o abajo dentro de la Lista activa.
         *
         * Las Apariencias del mismo Canal se mueven como bloque y se renumera
         * `Apariencia.orden` de forma secuencial. La operación no toca
         * `ColeccionCanal.posicion` ni ninguna preferencia de ordenación.
         */
        suspend fun moverCanal(
            canalId: Long,
            delta: Int,
        ): ResultadoAccion =
            withContext(Dispatchers.IO) {
                if (delta != -1 && delta != 1) {
                    return@withContext ResultadoAccion.Error(ERROR_MOVIMIENTO_INVALIDO)
                }
                val lista =
                    db.listaDao().activa()
                        ?: return@withContext ResultadoAccion.Error(ERROR_LISTA_NO_EXISTE)
                val filas = db.aparienciaDao().porLista(lista.id)
                val grupos = filas.groupBy { it.canalId }.values.toList()
                val origen = grupos.indexOfFirst { grupo -> grupo.first().canalId == canalId }
                if (origen < 0) return@withContext ResultadoAccion.Error(ERROR_CANAL_NO_EXISTE)
                val destino = origen + delta
                if (destino !in grupos.indices) return@withContext ResultadoAccion.Ok
                val reordenados = grupos.toMutableList().apply { swap(origen, destino) }
                db.withTransaction {
                    val ahora = Instant.now()
                    var orden = 0
                    for (grupo in reordenados) {
                        for (apariencia in grupo) {
                            db.aparienciaDao().actualizarOrden(apariencia.id, orden, ahora)
                            orden++
                        }
                    }
                }
                ResultadoAccion.Ok
            }

        private fun <T> MutableList<T>.swap(
            primero: Int,
            segundo: Int,
        ) {
            val temporal = this[primero]
            this[primero] = this[segundo]
            this[segundo] = temporal
        }

        private companion object {
            const val ESTADO_ACTIVA = "activa"
            const val TAG = "CatalogoCrud"

            const val ERROR_NOMBRE_VACIO = "nombre_vacio"
            const val ERROR_UBICACION_INVALIDA = "ubicacion_invalida"
            const val ERROR_CREAR_LISTA = "crear_lista_fallida"
            const val ERROR_LISTA_NO_EXISTE = "lista_no_existe"
            const val ERROR_ELIMINAR_LISTA = "eliminar_lista_fallida"
            const val ERROR_URL_INVALIDA = "url_invalida"
            const val ERROR_URL_ILEGIBLE = "url_ilegible"
            const val ERROR_CANAL_DUPLICADO = "canal_duplicado"
            const val ERROR_CANAL_NO_EXISTE = "canal_no_existe"
            const val ERROR_CREAR_CANAL = "crear_canal_fallido"
            const val ERROR_EDITAR_CANAL = "editar_canal_fallido"
            const val ERROR_MOVIMIENTO_INVALIDO = "movimiento_invalido"
        }
    }
