package com.copernicotv.app.data

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import com.copernicotv.app.crypto.ClavesApp
import com.copernicotv.app.crypto.ClavesDatos
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.app.data.db.dao.ConexionResumen
import com.copernicotv.app.data.db.entities.ConexionEntity
import com.copernicotv.app.data.db.entities.FuenteEntity
import com.copernicotv.app.data.db.entities.FuenteGuiaEntity
import com.copernicotv.app.data.db.entities.ListaEntity
import com.copernicotv.core.conexion.ConexionM3U
import com.copernicotv.core.conexion.ConexionXtream
import com.copernicotv.core.conexion.DatosConexionM3U
import com.copernicotv.core.conexion.DatosConexionXtream
import com.copernicotv.core.conexion.EstadoConexion
import com.copernicotv.core.conexion.FormatoSalidaXtream
import com.copernicotv.core.conexion.ProtocoloConexion
import com.copernicotv.core.conexion.TipoConexion
import com.copernicotv.core.crypto.CifradoAes
import com.copernicotv.core.crypto.HmacUrl
import com.copernicotv.core.crypto.ResultadoDescifrado
import com.copernicotv.core.identity.CampoUrl
import com.copernicotv.core.identity.CanonicalizadorUrl
import com.copernicotv.core.identity.ResultadoCanonizacion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.net.URI
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Administración de conexiones IPTV (Método A Xtream y Método B M3U).
 *
 * Una conexión no duplica el catálogo: materializa una `Fuente`, una `Lista` y,
 * cuando corresponde, una `FuenteGuia`, y reutiliza el pipeline de importación
 * y actualización existente. La `Lista` se conserva entre ediciones para que
 * favoritos, historial, ocultos y asociaciones sigan apuntando a los mismos
 * canales (S5).
 *
 * Los secretos nunca se guardan en claro: la contraseña y las URLs viajan
 * cifradas con la clave de datos y su AAD de columna, y las URLs además llevan
 * un índice HMAC para comparar sin descifrar. La fila de conexión se borra con
 * sus BLOB al eliminar el perfil (S3).
 */
@Singleton
class ConexionRepositorio
    @Inject
    constructor(
        private val db: AppDatabase,
        private val claves: ClavesApp,
        private val clavesDatos: ClavesDatos,
        private val indice: IndiceHmacRepositorio,
    ) {
        /** Conexiones visibles, sin secretos ni BLOB. */
        fun observarTodas(): Flow<List<ConexionVista>> =
            db.conexionDao().observarTodas().map { filas ->
                filas.map { it.aVista() }
            }

        /** Detalle editable de una conexión; los secretos solo se indican como presentes. */
        suspend fun detalle(id: Long): DetalleConexion? =
            withContext(Dispatchers.IO) {
                val fila = db.conexionDao().porId(id) ?: return@withContext null
                DetalleConexion(
                    id = fila.id,
                    nombre = fila.nombre,
                    tipo = TipoConexion.desdeCodigo(fila.tipo) ?: TipoConexion.M3U,
                    activa = fila.activa,
                    protocolo = ProtocoloConexion.desdeEsquema(fila.protocolo) ?: ProtocoloConexion.HTTPS,
                    servidor = fila.servidor,
                    puerto = fila.puerto,
                    usuario = fila.usuario,
                    formatoSalida = FormatoSalidaXtream.desdeValor(fila.formatoSalida),
                    actualizacionAutomatica = fila.actualizacionAutomatica,
                    intervaloHoras = fila.intervaloHoras,
                    passwordPresente = fila.passwordCifrada != null,
                    urlListaPresente = fila.urlListaCifrada != null,
                    urlListaRedactada = descifrarTexto(fila.urlListaCifrada, COLUMNA_URL_LISTA)?.let(::redactarUrl),
                    urlGuiaPresente = fila.urlGuiaCifrada != null,
                    urlGuiaRedactada = descifrarTexto(fila.urlGuiaCifrada, COLUMNA_URL_GUIA)?.let(::redactarUrl),
                )
            }

        /** Método A: alta de un perfil Xtream Codes. */
        suspend fun crearXtream(formulario: FormularioXtream): ResultadoConexion =
            withContext(Dispatchers.IO) {
                val contrasena =
                    formulario.contrasena?.takeIf { it.isNotEmpty() }
                        ?: return@withContext ResultadoConexion.Error(CodigosConexion.SIN_PASSWORD)
                val datos = formulario.aDatos(contrasena)
                val errores = ConexionXtream.validar(datos)
                if (errores.isNotEmpty()) return@withContext ResultadoConexion.Invalida(errores)
                val urlLista =
                    ConexionXtream.urlLista(datos)
                        ?: return@withContext ResultadoConexion.Error(CodigosConexion.URL_INVALIDA)
                val urlGuia = ConexionXtream.urlGuiaEfectiva(datos)
                val lista = sellarUrl(urlLista, CampoUrl.LISTA_UBICACION, COLUMNA_URL_LISTA)
                val guia = urlGuia?.let { sellarUrl(it, CampoUrl.FUENTE_GUIA_URL, COLUMNA_URL_GUIA) }
                val perfil =
                    perfilXtream(formulario, datos, contrasena, lista, guia)
                        ?: return@withContext errorSellado(lista, guia)
                guardar(existente = null, perfil = perfil)
            }

        /** Método A: edición sin cambiar la Lista ni sus asociaciones. */
        suspend fun editarXtream(
            id: Long,
            formulario: FormularioXtream,
        ): ResultadoConexion =
            withContext(Dispatchers.IO) {
                val actual =
                    db.conexionDao().porId(id)
                        ?: return@withContext ResultadoConexion.Error(CodigosConexion.NO_EXISTE)
                val contrasena =
                    formulario.contrasena?.takeIf { it.isNotEmpty() }
                        ?: descifrarTexto(actual.passwordCifrada, COLUMNA_PASSWORD)
                        ?: return@withContext ResultadoConexion.Error(CodigosConexion.SIN_PASSWORD)
                val datos = formulario.aDatos(contrasena)
                val errores = ConexionXtream.validar(datos)
                if (errores.isNotEmpty()) return@withContext ResultadoConexion.Invalida(errores)
                val urlLista =
                    ConexionXtream.urlLista(datos)
                        ?: return@withContext ResultadoConexion.Error(CodigosConexion.URL_INVALIDA)
                val urlGuia = ConexionXtream.urlGuiaEfectiva(datos)
                val lista = sellarUrl(urlLista, CampoUrl.LISTA_UBICACION, COLUMNA_URL_LISTA)
                val guia = urlGuia?.let { sellarUrl(it, CampoUrl.FUENTE_GUIA_URL, COLUMNA_URL_GUIA) }
                val perfil =
                    perfilXtream(formulario, datos, formulario.contrasena?.takeIf { it.isNotEmpty() }, lista, guia)
                        ?: return@withContext errorSellado(lista, guia)
                guardar(existente = actual, perfil = perfil)
            }

        /** Método B: alta de una lista M3U/M3U8 servida por URL. */
        suspend fun crearM3U(formulario: FormularioM3U): ResultadoConexion =
            withContext(Dispatchers.IO) {
                val urlLista =
                    formulario.urlLista?.takeIf { it.isNotBlank() }
                        ?: return@withContext ResultadoConexion.Error(CodigosConexion.SIN_URL_LISTA)
                val datos = formulario.aDatos(urlLista)
                val errores = ConexionM3U.validar(datos)
                if (errores.isNotEmpty()) return@withContext ResultadoConexion.Invalida(errores)
                val lista = sellarUrl(urlLista, CampoUrl.LISTA_UBICACION, COLUMNA_URL_LISTA)
                val guia = datos.urlGuia?.let { sellarUrl(it, CampoUrl.FUENTE_GUIA_URL, COLUMNA_URL_GUIA) }
                val perfil = perfilM3U(formulario, datos, lista, guia) ?: return@withContext errorSellado(lista, guia)
                guardar(existente = null, perfil = perfil)
            }

        /** Método B: edición conservando la Lista y sus asociaciones. */
        suspend fun editarM3U(
            id: Long,
            formulario: FormularioM3U,
        ): ResultadoConexion =
            withContext(Dispatchers.IO) {
                val actual =
                    db.conexionDao().porId(id)
                        ?: return@withContext ResultadoConexion.Error(CodigosConexion.NO_EXISTE)
                val urlLista =
                    formulario.urlLista?.takeIf { it.isNotBlank() }
                        ?: descifrarTexto(actual.urlListaCifrada, COLUMNA_URL_LISTA)
                        ?: return@withContext ResultadoConexion.Error(CodigosConexion.SIN_URL_LISTA)
                val datos = formulario.aDatos(urlLista)
                val errores = ConexionM3U.validar(datos)
                if (errores.isNotEmpty()) return@withContext ResultadoConexion.Invalida(errores)
                val lista = sellarUrl(urlLista, CampoUrl.LISTA_UBICACION, COLUMNA_URL_LISTA)
                val guia = datos.urlGuia?.let { sellarUrl(it, CampoUrl.FUENTE_GUIA_URL, COLUMNA_URL_GUIA) }
                val perfil = perfilM3U(formulario, datos, lista, guia) ?: return@withContext errorSellado(lista, guia)
                guardar(existente = actual, perfil = perfil)
            }

        /**
         * Elimina el perfil con sus secretos y su materialización en el catálogo.
         *
         * La guía administrada por la conexión se borra; una guía `manual` solo
         * se desvincula para no destruir una fuente que el usuario dio de alta
         * por su cuenta. Borrar la `Fuente` arrastra `Lista`, `Canal` y todo lo
         * que cuelga de ellos por FK en cascada.
         */
        suspend fun eliminar(id: Long): ResultadoConexion =
            withContext(Dispatchers.IO) {
                val actual =
                    db.conexionDao().porId(id)
                        ?: return@withContext ResultadoConexion.Error(CodigosConexion.NO_EXISTE)
                try {
                    db.withTransaction {
                        val ahora = Instant.now()
                        db.fuenteGuiaDao().porConexionAdministrada(id)?.let { guia ->
                            db.fuenteGuiaDao().eliminar(guia.id)
                        }
                        db.fuenteGuiaDao().desvincularConexion(id, ahora)
                        actual.fuenteId?.let { fuenteId -> db.fuenteDao().eliminar(fuenteId) }
                        db.conexionDao().eliminar(id)
                        if (db.listaDao().activa() == null) {
                            db.listaDao().listar().firstOrNull()?.let { siguiente ->
                                db.listaDao().activar(siguiente.id, ahora)
                            }
                        }
                    }
                    ResultadoConexion.Eliminada
                } catch (_: Exception) {
                    ResultadoConexion.Error(CodigosConexion.ELIMINAR_FALLO)
                }
            }

        /** Activa una conexión y su Lista; desactiva el resto. */
        suspend fun activar(id: Long): ResultadoConexion =
            withContext(Dispatchers.IO) {
                val actual =
                    db.conexionDao().porId(id)
                        ?: return@withContext ResultadoConexion.Error(CodigosConexion.NO_EXISTE)
                try {
                    db.withTransaction {
                        val ahora = Instant.now()
                        db.conexionDao().desactivarTodasExcepto(id, ahora)
                        db.conexionDao().marcarActiva(id, ahora)
                        actual.listaId?.let { listaId ->
                            db.listaDao().desactivarTodas()
                            db.listaDao().activar(listaId, ahora)
                        }
                    }
                    ResultadoConexion.Actualizada
                } catch (_: Exception) {
                    ResultadoConexion.Error(CodigosConexion.GUARDAR_FALLO)
                }
            }

        /** Desactiva una conexión; si su Lista era la activa, activa otra. */
        suspend fun desactivar(id: Long): ResultadoConexion =
            withContext(Dispatchers.IO) {
                val actual =
                    db.conexionDao().porId(id)
                        ?: return@withContext ResultadoConexion.Error(CodigosConexion.NO_EXISTE)
                try {
                    db.withTransaction {
                        val ahora = Instant.now()
                        db.conexionDao().marcarInactiva(id, ahora)
                        val listaId = actual.listaId
                        if (listaId != null && db.listaDao().porId(listaId)?.estado == ESTADO_ACTIVA) {
                            db.listaDao().desactivarTodas()
                            db.listaDao()
                                .listar()
                                .firstOrNull { it.id != listaId }
                                ?.let { siguiente -> db.listaDao().activar(siguiente.id, ahora) }
                        }
                    }
                    ResultadoConexion.Actualizada
                } catch (_: Exception) {
                    ResultadoConexion.Error(CodigosConexion.GUARDAR_FALLO)
                }
            }

        /** Persiste el resultado de una prueba o actualización de la conexión. */
        suspend fun fijarEstado(
            id: Long,
            estado: EstadoConexion,
            codigo: String?,
            mensaje: String? = null,
        ): ResultadoConexion =
            withContext(Dispatchers.IO) {
                val actualizadas =
                    db.conexionDao().fijarEstado(id, estado.codigo, mensaje, codigo, Instant.now())
                if (actualizadas == 0) ResultadoConexion.Error(CodigosConexion.NO_EXISTE) else ResultadoConexion.Actualizada
            }

        suspend fun fijarListaActualizada(
            id: Long,
            cuando: Instant = Instant.now(),
        ): ResultadoConexion =
            withContext(Dispatchers.IO) {
                val actualizadas = db.conexionDao().fijarListaActualizada(id, cuando)
                if (actualizadas == 0) ResultadoConexion.Error(CodigosConexion.NO_EXISTE) else ResultadoConexion.Actualizada
            }

        suspend fun fijarGuiaActualizada(
            id: Long,
            cuando: Instant = Instant.now(),
        ): ResultadoConexion =
            withContext(Dispatchers.IO) {
                val actualizadas = db.conexionDao().fijarGuiaActualizada(id, cuando)
                if (actualizadas == 0) ResultadoConexion.Error(CodigosConexion.NO_EXISTE) else ResultadoConexion.Actualizada
            }

        /** URL de lista descifrada para el pipeline de importación. */
        suspend fun urlListaDescifrada(id: Long): String? =
            withContext(Dispatchers.IO) {
                val fila = db.conexionDao().porId(id) ?: return@withContext null
                descifrarTexto(fila.urlListaCifrada, COLUMNA_URL_LISTA)
            }

        /** URL de guía descifrada para el pipeline EPG. */
        suspend fun urlGuiaDescifrada(id: Long): String? =
            withContext(Dispatchers.IO) {
                val fila = db.conexionDao().porId(id) ?: return@withContext null
                descifrarTexto(fila.urlGuiaCifrada, COLUMNA_URL_GUIA)
            }

        /** Datos completos de un perfil Xtream, con la contraseña descifrada en memoria. */
        suspend fun datosXtream(id: Long): DatosConexionXtream? =
            withContext(Dispatchers.IO) {
                val fila = db.conexionDao().porId(id) ?: return@withContext null
                if (TipoConexion.desdeCodigo(fila.tipo) != TipoConexion.XTREAM) return@withContext null
                val servidor = fila.servidor ?: return@withContext null
                val usuario = fila.usuario ?: return@withContext null
                val contrasena = descifrarTexto(fila.passwordCifrada, COLUMNA_PASSWORD) ?: return@withContext null
                DatosConexionXtream(
                    nombre = fila.nombre,
                    servidor = servidor,
                    puerto = fila.puerto,
                    protocolo = ProtocoloConexion.desdeEsquema(fila.protocolo) ?: ProtocoloConexion.HTTPS,
                    usuario = usuario,
                    contrasena = contrasena,
                    formatoSalida = FormatoSalidaXtream.desdeValor(fila.formatoSalida) ?: FormatoSalidaXtream.M3U8,
                    urlGuia = descifrarTexto(fila.urlGuiaCifrada, COLUMNA_URL_GUIA),
                )
            }

        /** Datos completos de un perfil M3U, con las URLs descifradas en memoria. */
        suspend fun datosM3U(id: Long): DatosConexionM3U? =
            withContext(Dispatchers.IO) {
                val fila = db.conexionDao().porId(id) ?: return@withContext null
                if (TipoConexion.desdeCodigo(fila.tipo) != TipoConexion.M3U) return@withContext null
                val urlLista = descifrarTexto(fila.urlListaCifrada, COLUMNA_URL_LISTA) ?: return@withContext null
                DatosConexionM3U(
                    nombre = fila.nombre,
                    urlLista = urlLista,
                    urlGuia = descifrarTexto(fila.urlGuiaCifrada, COLUMNA_URL_GUIA),
                    actualizacionAutomatica = fila.actualizacionAutomatica,
                    intervaloHoras = fila.intervaloHoras,
                    activa = fila.activa,
                )
            }

        /** Fuente materializada de la conexión, si ya se creó. */
        suspend fun fuenteId(id: Long): Long? = withContext(Dispatchers.IO) { db.conexionDao().porId(id)?.fuenteId }

        /** Lista materializada de la conexión, si ya se creó. */
        suspend fun listaId(id: Long): Long? = withContext(Dispatchers.IO) { db.conexionDao().porId(id)?.listaId }

        /**
         * Alta y edición comparten esta transacción.
         *
         * [existente] nulo crea; con fila edita en el sitio. En ambos casos la
         * `Lista` es la misma cuando ya existe, y la guía se concilia con la URL
         * efectiva: se actualiza, se crea o se elimina según corresponda.
         */
        private suspend fun guardar(
            existente: ConexionEntity?,
            perfil: Perfil,
        ): ResultadoConexion =
            try {
                val id =
                    db.withTransaction {
                        val ahora = Instant.now()
                        if (colisiona(perfil.nombre, existente?.id)) throw NombreDuplicado()
                        val conexionId = existente?.id ?: insertarConexion(perfil, ahora)
                        if (existente != null) actualizarConexion(existente, perfil, ahora)
                        val fuenteId = materializarFuente(existente, perfil.nombre, perfil.intervaloHoras, ahora)
                        val listaId = materializarLista(existente, fuenteId, perfil, ahora)
                        db.conexionDao().fijarCatalogo(conexionId, fuenteId, listaId, ahora)
                        conciliarGuia(conexionId, perfil.tipo, perfil.nombre, perfil.guia, ahora)
                        if (perfil.activa) {
                            db.conexionDao().desactivarTodasExcepto(conexionId, ahora)
                            db.conexionDao().marcarActiva(conexionId, ahora)
                            db.listaDao().desactivarTodas()
                            db.listaDao().activar(listaId, ahora)
                        } else if (db.listaDao().contar() == 1) {
                            // Primera Lista del catálogo: aunque la conexión nazca
                            // inactiva, el catálogo necesita una Lista activa.
                            db.listaDao().activar(listaId, ahora)
                        }
                        conexionId
                    }
                if (existente == null) ResultadoConexion.Creada(id) else ResultadoConexion.Actualizada
            } catch (_: NombreDuplicado) {
                ResultadoConexion.Error(CodigosConexion.NOMBRE_DUPLICADO)
            } catch (_: SQLiteConstraintException) {
                ResultadoConexion.Error(CodigosConexion.NOMBRE_DUPLICADO)
            } catch (_: Exception) {
                ResultadoConexion.Error(CodigosConexion.GUARDAR_FALLO)
            }

        private suspend fun colisiona(
            nombre: String,
            idPropio: Long?,
        ): Boolean {
            val porConexion = db.conexionDao().porNombre(nombre)
            if (porConexion != null && porConexion.id != idPropio) return true
            val porFuente = db.fuenteDao().porNombre(nombre)
            if (porFuente == null) return false
            val conexionDeFuente = db.conexionDao().porFuente(porFuente.id)
            return conexionDeFuente == null || conexionDeFuente.id != idPropio
        }

        private suspend fun insertarConexion(
            perfil: Perfil,
            ahora: Instant,
        ): Long =
            db.conexionDao().insertar(
                ConexionEntity(
                    nombre = perfil.nombre,
                    tipo = perfil.tipo.codigo,
                    activa = perfil.activa,
                    protocolo = perfil.protocolo,
                    servidor = perfil.servidor,
                    puerto = perfil.puerto,
                    usuario = perfil.usuario,
                    passwordCifrada = perfil.contrasena?.let { cifrarTexto(it, COLUMNA_PASSWORD) },
                    formatoSalida = perfil.formatoSalida,
                    urlListaCifrada = perfil.lista.cifrada,
                    urlListaHmac = perfil.lista.hmac,
                    urlGuiaCifrada = perfil.guia?.cifrada,
                    urlGuiaHmac = perfil.guia?.hmac,
                    actualizacionAutomatica = perfil.actualizacionAutomatica,
                    intervaloHoras = perfil.intervaloHoras,
                    estado = EstadoConexion.SIN_PROBAR.codigo,
                    creadoEn = ahora,
                    actualizadoEn = ahora,
                ),
            )

        private suspend fun actualizarConexion(
            existente: ConexionEntity,
            perfil: Perfil,
            ahora: Instant,
        ) {
            db.conexionDao().actualizarPerfil(
                id = existente.id,
                nombre = perfil.nombre,
                activa = perfil.activa,
                protocolo = perfil.protocolo,
                servidor = perfil.servidor,
                puerto = perfil.puerto,
                usuario = perfil.usuario,
                formatoSalida = perfil.formatoSalida,
                actualizacionAutomatica = perfil.actualizacionAutomatica,
                intervaloHoras = perfil.intervaloHoras,
                ahora = ahora,
            )
            perfil.contrasena?.let { contrasena ->
                db.conexionDao().actualizarPassword(existente.id, cifrarTexto(contrasena, COLUMNA_PASSWORD), ahora)
            }
            db.conexionDao().actualizarUrlLista(existente.id, perfil.lista.cifrada, perfil.lista.hmac, ahora)
            db.conexionDao().actualizarUrlGuia(existente.id, perfil.guia?.cifrada, perfil.guia?.hmac, ahora)
        }

        private suspend fun materializarFuente(
            existente: ConexionEntity?,
            nombre: String,
            intervaloHoras: Long,
            ahora: Instant,
        ): Long {
            val fuenteId = existente?.fuenteId
            if (fuenteId != null) {
                db.fuenteDao().renombrar(fuenteId, nombre, ahora)
                db.fuenteDao().actualizarProgramacion(fuenteId, intervaloHoras * 60, false, ESTADO_ACTIVA, ahora)
                return fuenteId
            }
            return db.fuenteDao().insertar(
                FuenteEntity(
                    nombre = nombre,
                    estado = ESTADO_ACTIVA,
                    intervaloMinutos = intervaloHoras * 60,
                    usarIntervaloGlobal = false,
                    creadoEn = ahora,
                    actualizadoEn = ahora,
                ),
            )
        }

        private suspend fun materializarLista(
            existente: ConexionEntity?,
            fuenteId: Long,
            perfil: Perfil,
            ahora: Instant,
        ): Long {
            val listaId = existente?.listaId
            if (listaId != null) {
                db.listaDao().renombrar(listaId, perfil.nombre, ahora)
                db.listaDao()
                    .actualizarUbicacion(listaId, perfil.lista.cifrada, perfil.lista.hmac, perfil.lista.esquema, ahora)
                return listaId
            }
            return db.listaDao().insertar(
                ListaEntity(
                    fuenteId = fuenteId,
                    nombre = perfil.nombre,
                    ubicacionCifrada = perfil.lista.cifrada,
                    ubicacionHmac = perfil.lista.hmac,
                    esquema = perfil.lista.esquema,
                    orden = db.listaDao().maxOrden() + 1,
                    estado = if (perfil.activa) ESTADO_ACTIVA else ESTADO_INACTIVA,
                    creadoEn = ahora,
                    actualizadoEn = ahora,
                ),
            )
        }

        private suspend fun conciliarGuia(
            conexionId: Long,
            tipo: TipoConexion,
            nombre: String,
            guia: Sellado.Ok?,
            ahora: Instant,
        ) {
            val existente = db.fuenteGuiaDao().porConexionAdministrada(conexionId)
            when {
                guia != null && existente != null ->
                    db.fuenteGuiaDao().actualizarUrlDesdeConexion(existente.id, guia.cifrada, guia.hmac, tipo.codigo, ahora)
                guia != null ->
                    db.fuenteGuiaDao().insertar(fuenteGuia(nombre, guia, conexionId, tipo.codigo, ahora))
                existente != null -> db.fuenteGuiaDao().eliminar(existente.id)
            }
        }

        private suspend fun sellarUrl(
            url: String,
            campo: CampoUrl,
            columna: String,
        ): Sellado {
            val canonica =
                CanonicalizadorUrl.canonizar(url, campo) as? ResultadoCanonizacion.Ok
                    ?: return Sellado.UrlInvalida
            val sello =
                indice.selloHmac() as? SelloIndiceHmac.Disponible
                    ?: return Sellado.IndiceNoOperativo
            val hmac =
                HmacUrl.calcularSobre(
                    clave = sello.clave,
                    generacion = sello.generacion,
                    campo = campo,
                    clase = canonica.clase,
                    urlCanonica = canonica.urlCanonica,
                )
            return Sellado.Ok(
                cifrada = cifrarTexto(url, columna),
                hmac = hmac.toByteArray(Charsets.UTF_8),
                esquema = canonica.urlCanonica.substringBefore(':'),
            )
        }

        private fun cifrarTexto(
            texto: String,
            columna: String,
        ): ByteArray =
            CifradoAes.cifrarDatos(
                clavesDatos.clave(),
                CifradoAes.aad(TABLA, columna),
                texto.toByteArray(Charsets.UTF_8),
            )

        private fun descifrarTexto(
            sobre: ByteArray?,
            columna: String,
        ): String? {
            if (sobre == null) return null
            val resultado =
                CifradoAes.descifrarSobre(
                    claves.claveAes,
                    clavesDatos.clave(),
                    CifradoAes.aad(TABLA, columna),
                    sobre,
                )
            return (resultado as? ResultadoDescifrado.Ok)?.bytes?.toString(Charsets.UTF_8)
        }

        private companion object {
            const val TABLA = "Conexion"
            const val COLUMNA_PASSWORD = "password_cifrada"
            const val COLUMNA_URL_LISTA = "url_lista_cifrada"
            const val COLUMNA_URL_GUIA = "url_guia_cifrada"
            const val ESTADO_ACTIVA = "activa"
            const val ESTADO_INACTIVA = "inactiva"
        }
    }

/** Perfil ya validado y sellado, listo para la transacción. */
private data class Perfil(
    val nombre: String,
    val tipo: TipoConexion,
    val activa: Boolean,
    val protocolo: String,
    val servidor: String?,
    val puerto: Int?,
    val usuario: String?,
    val contrasena: String?,
    val formatoSalida: String?,
    val lista: Sellado.Ok,
    val guia: Sellado.Ok?,
    val actualizacionAutomatica: Boolean,
    val intervaloHoras: Long,
)

/** Resultado de cifrar y sellar una URL; nunca transporta el texto en claro. */
private sealed interface Sellado {
    data class Ok(
        val cifrada: ByteArray,
        val hmac: ByteArray,
        val esquema: String,
    ) : Sellado

    data object UrlInvalida : Sellado

    data object IndiceNoOperativo : Sellado
}

/** Nombre de conexión o de Fuente ya ocupado. */
private class NombreDuplicado : Exception()

private fun perfilXtream(
    formulario: FormularioXtream,
    datos: DatosConexionXtream,
    contrasena: String?,
    lista: Sellado,
    guia: Sellado?,
): Perfil? {
    val sellada = lista as? Sellado.Ok ?: return null
    val guiaOk = guia as? Sellado.Ok
    if (guia != null && guiaOk == null) return null
    return Perfil(
        nombre = datos.nombre,
        tipo = TipoConexion.XTREAM,
        activa = formulario.activa,
        protocolo = datos.protocolo.esquema,
        servidor = ConexionXtream.normalizarServidor(datos.servidor),
        puerto = formulario.puerto ?: ConexionXtream.puertoDeclarado(datos.servidor),
        usuario = datos.usuario,
        contrasena = contrasena,
        formatoSalida = datos.formatoSalida.valor,
        lista = sellada,
        guia = guiaOk,
        actualizacionAutomatica = formulario.actualizacionAutomatica,
        intervaloHoras = formulario.intervaloHoras,
    )
}

private fun perfilM3U(
    formulario: FormularioM3U,
    datos: DatosConexionM3U,
    lista: Sellado,
    guia: Sellado?,
): Perfil? {
    val sellada = lista as? Sellado.Ok ?: return null
    val guiaOk = guia as? Sellado.Ok
    if (guia != null && guiaOk == null) return null
    return Perfil(
        nombre = datos.nombre,
        tipo = TipoConexion.M3U,
        activa = formulario.activa,
        protocolo = protocoloDe(datos.urlLista),
        servidor = null,
        puerto = null,
        usuario = null,
        contrasena = null,
        formatoSalida = null,
        lista = sellada,
        guia = guiaOk,
        actualizacionAutomatica = formulario.actualizacionAutomatica,
        intervaloHoras = formulario.intervaloHoras,
    )
}

private fun errorSellado(
    lista: Sellado,
    guia: Sellado?,
): ResultadoConexion {
    val sellados = listOfNotNull(lista, guia)
    return if (sellados.any { it is Sellado.IndiceNoOperativo }) {
        ResultadoConexion.Error(CodigosConexion.INDICE_HMAC_NO_OPERATIVO)
    } else {
        ResultadoConexion.Error(CodigosConexion.URL_INVALIDA)
    }
}

private fun fuenteGuia(
    nombre: String,
    guia: Sellado.Ok,
    conexionId: Long,
    origen: String,
    ahora: Instant,
): FuenteGuiaEntity =
    FuenteGuiaEntity(
        nombre = "Guía $nombre",
        urlCifrada = guia.cifrada,
        urlHmac = guia.hmac,
        usarRetencionGlobal = true,
        creadoEn = ahora,
        actualizadoEn = ahora,
        origen = origen,
        estado = FuenteGuiaEntity.ESTADO_SIN_ACTUALIZAR,
        conexionId = conexionId,
    )

/** Esquema de la URL de lista, sin asumir que siempre es `https`. */
private fun protocoloDe(url: String): String =
    ProtocoloConexion.desdeEsquema(url.trim().substringBefore(':'))?.esquema ?: ProtocoloConexion.HTTPS.esquema

/**
 * Muestra solo esquema, host y puerto.
 *
 * En M3U las credenciales suelen viajar en la ruta, así que el resto se
 * reemplaza por puntos suspensivos para no revelarlas en pantalla.
 */
private fun redactarUrl(url: String): String =
    try {
        val uri = URI(url)
        val host = uri.host
        if (host == null) {
            REDACTADO
        } else {
            val puerto = if (uri.port > 0) ":${uri.port}" else ""
            "${uri.scheme}://$host$puerto/…"
        }
    } catch (_: Exception) {
        REDACTADO
    }

private fun ConexionResumen.aVista(): ConexionVista =
    ConexionVista(
        id = id,
        nombre = nombre,
        tipo = TipoConexion.desdeCodigo(tipo) ?: TipoConexion.M3U,
        activa = activa,
        estado = EstadoConexion.desdeCodigo(estado),
        ultimoCodigo = ultimoCodigo,
        listaActualizadaEn = listaActualizadaEnMs?.let(Instant::ofEpochMilli),
        guiaActualizadaEn = guiaActualizadaEnMs?.let(Instant::ofEpochMilli),
    )

private fun FormularioXtream.aDatos(contrasena: String): DatosConexionXtream =
    DatosConexionXtream(
        nombre = nombre.trim(),
        servidor = servidor.trim(),
        puerto = puerto,
        protocolo = protocolo,
        usuario = usuario.trim(),
        contrasena = contrasena,
        formatoSalida = formatoSalida,
        urlGuia = urlGuia?.trim()?.takeIf { it.isNotEmpty() },
    )

private fun FormularioM3U.aDatos(urlLista: String): DatosConexionM3U =
    DatosConexionM3U(
        nombre = nombre.trim(),
        urlLista = urlLista.trim(),
        urlGuia = urlGuia?.trim()?.takeIf { it.isNotEmpty() },
        actualizacionAutomatica = actualizacionAutomatica,
        intervaloHoras = intervaloHoras,
        activa = activa,
    )

private const val REDACTADO = "…"
