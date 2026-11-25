package com.copernicotv.app.data

import android.content.Context
import androidx.room.withTransaction
import com.copernicotv.app.crypto.ClavesApp
import com.copernicotv.app.crypto.ClavesDatos
import com.copernicotv.app.data.cache.Descargador
import com.copernicotv.app.data.cache.ResultadoDescarga
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.app.data.db.dao.FuenteGuiaResumen
import com.copernicotv.app.data.db.entities.FuenteGuiaEntity
import com.copernicotv.core.crypto.CifradoAes
import com.copernicotv.core.crypto.HmacUrl
import com.copernicotv.core.crypto.ResultadoDescifrado
import com.copernicotv.core.epg.PoliticaReintentoGuia
import com.copernicotv.core.identity.CampoUrl
import com.copernicotv.core.identity.CanonicalizadorUrl
import com.copernicotv.core.identity.ResultadoCanonizacion
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.time.Duration
import java.time.Instant
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import javax.inject.Singleton

/** Datos editables de una guía EPG (S7). */
data class FormularioGuia(
    val nombre: String,
    val url: String? = null,
    val activa: Boolean = true,
    val actualizacionAutomatica: Boolean = false,
    val intervaloHoras: Long? = null,
    val conexionId: Long? = null,
)

/** Guía visible para administración, sin URL ni secretos. */
data class GuiaAdministrada(
    val id: Long,
    val nombre: String,
    val origen: String,
    val estado: String,
    val activa: Boolean,
    val actualizacionAutomatica: Boolean,
    val intervaloHoras: Long?,
    val conexionId: Long?,
    val ultimaActualizacionEn: Instant?,
    val ultimoErrorCodigo: String?,
    val ultimoErrorMensaje: String?,
    val intentosFallidos: Long,
    val publicada: Boolean,
    val urlPresente: Boolean,
    /** El reintento automático todavía está permitido por la política. */
    val puedeReintentar: Boolean,
)

/** Resultado de alta, edición o baja de una guía. */
sealed interface ResultadoGuia {
    data class Ok(val id: Long) : ResultadoGuia

    data class Error(val codigo: String) : ResultadoGuia
}

/** Desenlace de una actualización de guía, ya clasificado para el worker. */
sealed interface ResultadoActualizacionGuia {
    data class Exitosa(
        val fuenteGuiaId: Long,
        val programas: Int,
        val canales: Int,
    ) : ResultadoActualizacionGuia

    /** No había nada que hacer: la guía no existe o no estaba vencida. */
    data object SinTrabajo : ResultadoActualizacionGuia

    /** Fallo recuperable: red, servidor o XML transitoriamente inválido. */
    data class Reintentar(val codigo: String) : ResultadoActualizacionGuia

    /** Fallo permanente: URL inválida, rechazo del servidor o guía vacía. */
    data class Fallo(val codigo: String) : ResultadoActualizacionGuia
}

/** Cifras de una pasada automática sobre todas las guías vencidas. */
data class ResumenGuias(
    val ejecutadas: Int = 0,
    val exitosas: Int = 0,
    val reintentables: Int = 0,
    val fallidas: Int = 0,
)

/**
 * Administración de fuentes de guía EPG (S7, H-06).
 *
 * Antes de este repositorio, `FuenteGuia` solo se sembraba desde pruebas: en
 * producción no existía ninguna ruta de alta, así que la guía nunca podía
 * publicarse. Aquí viven el alta, la edición, la baja, la activación, la
 * vinculación con una conexión y la actualización manual y automática.
 *
 * La URL se guarda cifrada con la clave de datos y con un HMAC para comparar
 * sin descifrar, igual que las URLs de conexión. El documento XMLTV nunca se
 * materializa: se descarga a un archivo temporal, se parsea en streaming y se
 * borra al terminar. La última guía válida no se toca: `EpgRepositorio`
 * publica en una transacción y solo borra la generación anterior después.
 */
@Singleton
class AdministracionGuiaRepositorio
    @Inject
    constructor(
        private val db: AppDatabase,
        private val claves: ClavesApp,
        private val clavesDatos: ClavesDatos,
        private val indice: IndiceHmacRepositorio,
        private val epg: EpgRepositorio,
        @ApplicationContext private val contexto: Context,
        private val descargador: Descargador,
        private val politica: PoliticaReintentoGuia,
    ) {
        /** Guías visibles, sin BLOB de URL ni secretos. */
        fun observarTodas(): Flow<List<GuiaAdministrada>> = db.fuenteGuiaDao().observarResumenes().map { filas -> filas.map(::aVista) }

        /** Alta de una guía manual con URL propia (S7). */
        suspend fun crear(formulario: FormularioGuia): ResultadoGuia =
            withContext(Dispatchers.IO) {
                val nombre = formulario.nombre.trim()
                if (nombre.isEmpty()) return@withContext ResultadoGuia.Error(CODIGO_NOMBRE_VACIO)
                val url = formulario.url?.trim().orEmpty()
                if (url.isEmpty()) return@withContext ResultadoGuia.Error(CODIGO_URL_REQUERIDA)
                when (val sello = sellar(url)) {
                    is SelloGuia.UrlInvalida -> return@withContext ResultadoGuia.Error(CODIGO_URL_INVALIDA)
                    is SelloGuia.IndiceNoOperativo ->
                        return@withContext ResultadoGuia.Error(CODIGO_INDICE_NO_OPERATIVO)
                    is SelloGuia.Ok -> {
                        val ahora = Instant.now()
                        val id =
                            db.fuenteGuiaDao().insertar(
                                FuenteGuiaEntity(
                                    nombre = nombre,
                                    urlCifrada = sello.cifrada,
                                    urlHmac = sello.hmac,
                                    usarRetencionGlobal = true,
                                    creadoEn = ahora,
                                    actualizadoEn = ahora,
                                    origen = FuenteGuiaEntity.ORIGEN_MANUAL,
                                    estado = FuenteGuiaEntity.ESTADO_SIN_ACTUALIZAR,
                                    conexionId = formulario.conexionId,
                                    activa = formulario.activa,
                                    actualizacionAutomatica = formulario.actualizacionAutomatica,
                                    intervaloHoras = formulario.intervaloHoras,
                                ),
                            )
                        ResultadoGuia.Ok(id)
                    }
                }
            }

        /**
         * Edición de una guía.
         *
         * Si [FormularioGuia.url] viene vacía se conserva la URL guardada: el
         * usuario puede cambiar el nombre o la programación sin volver a
         * escribir una credencial que la app no muestra completa.
         */
        suspend fun editar(
            id: Long,
            formulario: FormularioGuia,
        ): ResultadoGuia =
            withContext(Dispatchers.IO) {
                val fila = db.fuenteGuiaDao().porId(id) ?: return@withContext ResultadoGuia.Error(CODIGO_NO_ENCONTRADA)
                val nombre = formulario.nombre.trim()
                if (nombre.isEmpty()) return@withContext ResultadoGuia.Error(CODIGO_NOMBRE_VACIO)
                val url = formulario.url?.trim().orEmpty()
                val ahora = Instant.now()
                val sello =
                    if (url.isEmpty()) {
                        null
                    } else {
                        when (val resultado = sellar(url)) {
                            is SelloGuia.UrlInvalida -> return@withContext ResultadoGuia.Error(CODIGO_URL_INVALIDA)
                            is SelloGuia.IndiceNoOperativo ->
                                return@withContext ResultadoGuia.Error(CODIGO_INDICE_NO_OPERATIVO)
                            is SelloGuia.Ok -> resultado
                        }
                    }
                db.withTransaction {
                    if (sello != null) {
                        db.fuenteGuiaDao().actualizarUrl(id, sello.cifrada, sello.hmac, ahora)
                    }
                    db.fuenteGuiaDao().actualizarConfiguracion(
                        id = id,
                        nombre = nombre,
                        automatica = formulario.actualizacionAutomatica,
                        intervaloHoras = formulario.intervaloHoras,
                        ahora = ahora,
                    )
                    if (fila.conexionId != formulario.conexionId) {
                        db.fuenteGuiaDao().cambiarConexion(id, formulario.conexionId, ahora)
                    }
                }
                ResultadoGuia.Ok(id)
            }

        /** Baja definitiva: la cascada borra generaciones, programas y asociaciones. */
        suspend fun eliminar(id: Long): Boolean = withContext(Dispatchers.IO) { db.fuenteGuiaDao().eliminar(id) > 0 }

        /** Activación o desactivación de la guía para actualizaciones. */
        suspend fun cambiarActiva(
            id: Long,
            activa: Boolean,
        ): Boolean =
            withContext(Dispatchers.IO) {
                db.fuenteGuiaDao().cambiarActiva(id, activa, Instant.now()) > 0
            }

        /**
         * Actualización manual o automática de una guía.
         *
         * Descarga a un temporal, detecta gzip por sus dos primeros bytes
         * (una fuente puede servir XMLTV comprimido con o sin extensión `.gz`)
         * y delega en [EpgRepositorio.importar]. El temporal se borra siempre.
         */
        suspend fun actualizarAhora(
            id: Long,
            causa: String = EpgRepositorio.CAUSA_MANUAL,
        ): ResultadoActualizacionGuia =
            withContext(Dispatchers.IO) {
                val fila = db.fuenteGuiaDao().porId(id) ?: return@withContext ResultadoActualizacionGuia.SinTrabajo
                val url = descifrar(fila.urlCifrada) ?: return@withContext registrarFallo(fila, CODIGO_SIN_URL)
                val ahora = Instant.now()
                db.fuenteGuiaDao().fijarEstado(
                    id = id,
                    estado = ESTADO_ACTUALIZANDO,
                    ultimaActualizacionEn = fila.ultimaActualizacionEn,
                    codigo = null,
                    mensaje = null,
                    intentos = fila.intentosFallidos ?: 0L,
                    ahora = ahora,
                )
                val archivo = File.createTempFile(PREFIJO_TEMPORAL, SUFIJO_TEMPORAL, contexto.cacheDir)
                try {
                    when (val descarga = descargador.descargar(url, archivo)) {
                        is ResultadoDescarga.Fallo -> registrarFallo(fila, descarga.codigo)
                        is ResultadoDescarga.Ok ->
                            if (descarga.bytes <= 0L) {
                                registrarFallo(fila, CODIGO_GUIA_VACIA)
                            } else {
                                importar(fila, archivo, causa)
                            }
                    }
                } finally {
                    archivo.delete()
                }
            }

        /**
         * Pasada automática: actualiza solo las guías activas con actualización
         * automática cuyo intervalo venció y cuyo reintento está permitido.
         */
        suspend fun procesarPendientes(): ResumenGuias =
            withContext(Dispatchers.IO) {
                val ahora = Instant.now()
                var ejecutadas = 0
                var exitosas = 0
                var reintentables = 0
                var fallidas = 0
                for (fila in db.fuenteGuiaDao().automaticas()) {
                    if (!estaVencida(fila, ahora)) continue
                    ejecutadas++
                    when (actualizarAhora(fila.id, EpgRepositorio.CAUSA_AUTOMATICA)) {
                        is ResultadoActualizacionGuia.Exitosa -> exitosas++
                        is ResultadoActualizacionGuia.Reintentar -> reintentables++
                        is ResultadoActualizacionGuia.Fallo -> fallidas++
                        ResultadoActualizacionGuia.SinTrabajo -> Unit
                    }
                }
                ResumenGuias(ejecutadas, exitosas, reintentables, fallidas)
            }

        private suspend fun importar(
            fila: FuenteGuiaEntity,
            archivo: File,
            causa: String,
        ): ResultadoActualizacionGuia {
            val resumen =
                abrir(archivo).use { entrada ->
                    epg.importar(fila.id, entrada, causa)
                }
            return if (resumen.resultado == EpgRepositorio.RESULTADO_EXITOSA) {
                val ahora = Instant.now()
                db.fuenteGuiaDao().fijarEstado(
                    id = fila.id,
                    estado = ESTADO_EXITOSA,
                    ultimaActualizacionEn = ahora,
                    codigo = null,
                    mensaje = null,
                    intentos = 0L,
                    ahora = ahora,
                )
                ResultadoActualizacionGuia.Exitosa(fila.id, resumen.programas, resumen.canales)
            } else {
                registrarFallo(fila, resumen.errorGlobal?.codigo ?: CODIGO_IMPORTACION)
            }
        }

        private suspend fun registrarFallo(
            fila: FuenteGuiaEntity,
            codigo: String,
        ): ResultadoActualizacionGuia {
            val ahora = Instant.now()
            val intentos = (fila.intentosFallidos ?: 0L) + 1L
            db.fuenteGuiaDao().fijarEstado(
                id = fila.id,
                estado = ESTADO_FALLIDA,
                ultimaActualizacionEn = fila.ultimaActualizacionEn,
                codigo = codigo,
                mensaje = mensajeDe(codigo),
                intentos = intentos,
                ahora = ahora,
            )
            return if (transitorio(codigo)) {
                ResultadoActualizacionGuia.Reintentar(codigo)
            } else {
                ResultadoActualizacionGuia.Fallo(codigo)
            }
        }

        /**
         * ¿Toca actualizarla ahora?
         *
         * El intervalo se cuenta desde la última publicación correcta; la
         * espera del reintento, desde el último intento ([FuenteGuiaEntity.actualizadoEn]),
         * para que un fallo no espere además el intervalo completo.
         */
        private fun estaVencida(
            fila: FuenteGuiaEntity,
            ahora: Instant,
        ): Boolean {
            val intervalo = Duration.ofHours(fila.intervaloHoras ?: INTERVALO_PREDETERMINADO_HORAS)
            val vencida = fila.ultimaActualizacionEn?.let { !ahora.isBefore(it.plus(intervalo)) } ?: true
            if (!vencida) return false
            return politica.puedeReintentar(fila.intentosFallidos ?: 0L, fila.actualizadoEn, ahora)
        }

        private fun abrir(archivo: File): InputStream {
            val entrada = BufferedInputStream(FileInputStream(archivo))
            entrada.mark(2)
            val primero = entrada.read()
            val segundo = entrada.read()
            entrada.reset()
            return if (primero == GZIP_MAGIC_1 && segundo == GZIP_MAGIC_2) GZIPInputStream(entrada) else entrada
        }

        private suspend fun sellar(url: String): SelloGuia {
            val canonica =
                CanonicalizadorUrl.canonizar(url, CampoUrl.FUENTE_GUIA_URL) as? ResultadoCanonizacion.Ok
                    ?: return SelloGuia.UrlInvalida
            val sello =
                indice.selloHmac() as? SelloIndiceHmac.Disponible
                    ?: return SelloGuia.IndiceNoOperativo
            val hmac =
                HmacUrl.calcularSobre(
                    clave = sello.clave,
                    generacion = sello.generacion,
                    campo = CampoUrl.FUENTE_GUIA_URL,
                    clase = canonica.clase,
                    urlCanonica = canonica.urlCanonica,
                )
            return SelloGuia.Ok(
                cifrada = cifrar(url),
                hmac = hmac.toByteArray(Charsets.UTF_8),
            )
        }

        private fun cifrar(texto: String): ByteArray =
            CifradoAes.cifrarDatos(
                clavesDatos.clave(),
                CifradoAes.aad(TABLA, COLUMNA_URL),
                texto.toByteArray(Charsets.UTF_8),
            )

        private fun descifrar(sobre: ByteArray?): String? {
            if (sobre == null) return null
            val resultado =
                CifradoAes.descifrarSobre(
                    claves.claveAes,
                    clavesDatos.clave(),
                    CifradoAes.aad(TABLA, COLUMNA_URL),
                    sobre,
                )
            return (resultado as? ResultadoDescifrado.Ok)?.bytes?.toString(Charsets.UTF_8)
        }

        private fun aVista(fila: FuenteGuiaResumen): GuiaAdministrada =
            GuiaAdministrada(
                id = fila.id,
                nombre = fila.nombre,
                origen = fila.origen ?: FuenteGuiaEntity.ORIGEN_MANUAL,
                estado = fila.estado ?: FuenteGuiaEntity.ESTADO_SIN_ACTUALIZAR,
                activa = fila.activa ?: true,
                actualizacionAutomatica = fila.actualizacionAutomatica ?: false,
                intervaloHoras = fila.intervaloHoras,
                conexionId = fila.conexionId,
                ultimaActualizacionEn = fila.ultimaActualizacionEn,
                ultimoErrorCodigo = fila.ultimoErrorCodigo,
                ultimoErrorMensaje = fila.ultimoErrorMensaje,
                intentosFallidos = fila.intentosFallidos ?: 0L,
                publicada = fila.generacionPublicadaId != null,
                urlPresente = fila.urlPresente,
                puedeReintentar =
                    politica.puedeReintentar(
                        fila.intentosFallidos ?: 0L,
                        null,
                        Instant.now(),
                    ),
            )

        private companion object {
            const val TABLA = "FuenteGuia"
            const val COLUMNA_URL = "urlCifrada"
            const val ESTADO_ACTUALIZANDO = "actualizando"
            const val ESTADO_EXITOSA = "exitosa"
            const val ESTADO_FALLIDA = "fallida"
            const val CODIGO_NOMBRE_VACIO = "nombre_vacio"
            const val CODIGO_URL_REQUERIDA = "url_requerida"
            const val CODIGO_URL_INVALIDA = "url_invalida"
            const val CODIGO_INDICE_NO_OPERATIVO = "indice_hmac_no_operativo"
            const val CODIGO_NO_ENCONTRADA = "no_encontrada"
            const val CODIGO_SIN_URL = "sin_url"
            const val CODIGO_GUIA_VACIA = "guia_vacia"
            const val CODIGO_IMPORTACION = "importacion_fallida"
            const val INTERVALO_PREDETERMINADO_HORAS = 24L
            const val PREFIJO_TEMPORAL = "guia-"
            const val SUFIJO_TEMPORAL = ".xml"
            const val GZIP_MAGIC_1 = 0x1f
            const val GZIP_MAGIC_2 = 0x8b

            /** Fallos que valen un reintento automático. */
            val TRANSITORIOS: Set<String> =
                setOf(
                    "timeout",
                    "sin_conexion",
                    "dns_no_resuelto",
                    "conexion_rechazada",
                    "http_5xx",
                    "importacion_fallida",
                    "guia_vacia",
                )

            fun transitorio(codigo: String): Boolean = codigo in TRANSITORIOS

            fun mensajeDe(codigo: String): String =
                when (codigo) {
                    "timeout" -> "El servidor no respondió a tiempo"
                    "sin_conexion" -> "No se pudo conectar con el servidor"
                    "dns_no_resuelto" -> "El nombre del servidor no se pudo resolver"
                    "conexion_rechazada" -> "El servidor rechazó la conexión"
                    "http_4xx" -> "El servidor rechazó la solicitud de la guía"
                    "http_5xx" -> "El servidor de la guía devolvió un error"
                    "bloqueo_http" -> "La conexión sin cifrar está bloqueada"
                    "esquema_no_admitido" -> "La URL debe usar http o https"
                    "sin_url" -> "La guía no tiene una URL guardada"
                    "guia_vacia" -> "La guía descargada está vacía"
                    else -> "La guía no se pudo procesar"
                }
        }
    }

/** Resultado del sellado de la URL de una guía. */
private sealed interface SelloGuia {
    data class Ok(
        val cifrada: ByteArray,
        val hmac: ByteArray,
    ) : SelloGuia

    data object UrlInvalida : SelloGuia

    data object IndiceNoOperativo : SelloGuia
}
