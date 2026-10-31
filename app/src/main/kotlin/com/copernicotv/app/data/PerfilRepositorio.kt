package com.copernicotv.app.data

import android.database.sqlite.SQLiteConstraintException
import com.copernicotv.app.data.db.dao.ListaDao
import com.copernicotv.app.data.db.dao.PerfilDao
import com.copernicotv.app.data.db.entities.PerfilEntity
import com.copernicotv.app.data.db.entities.PerfilListaEntity
import com.copernicotv.core.identity.CaseFolder
import com.copernicotv.core.identity.PerfilNormalizacion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** Perfil visible en la UI; las Listas asociadas se observan por separado. */
data class PerfilResumen(
    val id: Long,
    val nombre: String,
)

/** Resultado de crear un Perfil sin fusionar identidades. */
sealed interface ResultadoAltaPerfil {
    /** Se insertó la fila; [perfil] conserva el nombre original y su id asignado. */
    data class Creado(val perfil: PerfilEntity) : ResultadoAltaPerfil

    /** Ya existe un Perfil con el mismo `nombreNormalizado`; no se insertó nada. */
    data class Conflicto(
        val normalizado: String,
        val existente: PerfilEntity,
        val codigo: String = PerfilRepositorio.NOMBRE_PERFIL_DUPLICADO,
    ) : ResultadoAltaPerfil

    /** El nombre queda vacío tras NFC y recorte exterior; no se insertó nada. */
    data class Rechazado(
        val codigo: String = PerfilRepositorio.NOMBRE_PERFIL_VACIO,
    ) : ResultadoAltaPerfil
}

/** Resultado de renombrar un Perfil sin fusionar identidades. */
sealed interface ResultadoRenombrado {
    /** Se actualizó la fila; [perfil] refleja el nuevo nombre y su forma normalizada. */
    data class Renombrado(val perfil: PerfilEntity) : ResultadoRenombrado

    /** Otro Perfil ya usa el mismo `nombreNormalizado`; no se modificó nada. */
    data class Conflicto(
        val normalizado: String,
        val existente: PerfilEntity,
        val codigo: String = PerfilRepositorio.NOMBRE_PERFIL_DUPLICADO,
    ) : ResultadoRenombrado

    /** El nombre queda vacío tras NFC y recorte exterior; no se modificó nada. */
    data class Rechazado(
        val codigo: String = PerfilRepositorio.NOMBRE_PERFIL_VACIO,
    ) : ResultadoRenombrado

    /** No existe un Perfil con ese id. */
    data object NoExiste : ResultadoRenombrado
}

/**
 * Persistencia de Perfiles con identidad lógica separada de la representación.
 *
 * [PerfilEntity.nombre] guarda el texto original tal como lo escribió la persona;
 * [PerfilEntity.nombreNormalizado] guarda la identidad lógica calculada con
 * [PerfilNormalizacion] y el [CaseFolder] conforme a Unicode 15.1.0. La columna
 * `nombreNormalizado` tiene un índice único, así que la base es la última red de
 * seguridad contra carreras entre la comprobación y la escritura.
 *
 * Esta capa **no** reescribe nombres existentes: el valor normalizado se
 * recalcula solo cuando se crea o se renombra un Perfil. Un conflicto nunca
 * fusiona ni sobrescribe la identidad existente; se devuelve de forma explícita
 * para que la capa que llama decida qué mostrar.
 */
@Singleton
class PerfilRepositorio
    @Inject
    constructor(
        private val dao: PerfilDao,
        private val listaDao: ListaDao,
        private val caseFolder: CaseFolder,
    ) {
        /** Perfiles ordenados por identidad normalizada, para el selector de la UI. */
        fun perfiles(): Flow<List<PerfilResumen>> =
            dao.observarTodos().map { filas ->
                filas.map { fila -> PerfilResumen(id = fila.id, nombre = fila.nombre) }
            }

        /** Listas asociadas a un Perfil; un Perfil vacío devuelve una lista vacía. */
        fun listasDePerfil(perfilId: Long): Flow<List<Long>> = dao.observarListasDePerfil(perfilId)

        /** Perfiles con su representación actual, para pruebas y diagnóstico. */
        suspend fun listar(): List<PerfilResumen> =
            withContext(Dispatchers.IO) {
                dao.listar().map { fila -> PerfilResumen(id = fila.id, nombre = fila.nombre) }
            }

        /** Crea un Perfil desde la UI, sin exponer el resultado sellado interno. */
        suspend fun crearPerfil(nombre: String): ResultadoAccion =
            when (val resultado = crear(nombre)) {
                is ResultadoAltaPerfil.Creado -> ResultadoAccion.Ok
                is ResultadoAltaPerfil.Conflicto -> ResultadoAccion.Error(resultado.codigo)
                is ResultadoAltaPerfil.Rechazado -> ResultadoAccion.Error(resultado.codigo)
            }

        /** Renombra un Perfil desde la UI. */
        suspend fun renombrarPerfil(
            id: Long,
            nombre: String,
        ): ResultadoAccion =
            when (val resultado = renombrar(id, nombre)) {
                is ResultadoRenombrado.Renombrado -> ResultadoAccion.Ok
                is ResultadoRenombrado.Conflicto -> ResultadoAccion.Error(resultado.codigo)
                is ResultadoRenombrado.Rechazado -> ResultadoAccion.Error(resultado.codigo)
                ResultadoRenombrado.NoExiste -> ResultadoAccion.Error(PERFIL_NO_EXISTE)
            }

        /** Elimina un Perfil; `PerfilLista` se borra por cascada. */
        suspend fun eliminarPerfil(id: Long): ResultadoAccion =
            withContext(Dispatchers.IO) {
                if (dao.eliminar(id) == 0) {
                    ResultadoAccion.Error(PERFIL_NO_EXISTE)
                } else {
                    ResultadoAccion.Ok
                }
            }

        /** Asocia una Lista a un Perfil. Es idempotente por la PK compuesta. */
        suspend fun asociarLista(
            perfilId: Long,
            listaId: Long,
        ): ResultadoAccion =
            withContext(Dispatchers.IO) {
                if (dao.porId(perfilId) == null) {
                    return@withContext ResultadoAccion.Error(PERFIL_NO_EXISTE)
                }
                if (listaDao.porId(listaId) == null) {
                    return@withContext ResultadoAccion.Error(LISTA_NO_EXISTE)
                }
                dao.asociarLista(PerfilListaEntity(perfilId = perfilId, listaId = listaId))
                ResultadoAccion.Ok
            }

        /** Desasocia una Lista de un Perfil; una asociación ausente no es error. */
        suspend fun desasociarLista(
            perfilId: Long,
            listaId: Long,
        ): ResultadoAccion =
            withContext(Dispatchers.IO) {
                if (dao.porId(perfilId) == null) {
                    return@withContext ResultadoAccion.Error(PERFIL_NO_EXISTE)
                }
                dao.desasociarLista(perfilId, listaId)
                ResultadoAccion.Ok
            }

        /** Crea un Perfil o informa el conflicto de identidad. */
        suspend fun crear(
            nombre: String,
            ahora: Instant = Instant.now(),
        ): ResultadoAltaPerfil =
            withContext(Dispatchers.IO) {
                val normalizado = PerfilNormalizacion.normalizar(nombre, caseFolder)
                if (normalizado.isEmpty()) return@withContext ResultadoAltaPerfil.Rechazado()
                val existente = dao.porNombreNormalizado(normalizado)
                if (existente != null) return@withContext ResultadoAltaPerfil.Conflicto(normalizado, existente)
                val entidad =
                    PerfilEntity(
                        nombre = nombre,
                        nombreNormalizado = normalizado,
                        creadoEn = ahora,
                        actualizadoEn = ahora,
                    )
                try {
                    ResultadoAltaPerfil.Creado(entidad.copy(id = dao.insertar(entidad)))
                } catch (conflicto: SQLiteConstraintException) {
                    val carrera =
                        dao.porNombreNormalizado(normalizado)
                            ?: throw conflicto
                    ResultadoAltaPerfil.Conflicto(normalizado, carrera)
                }
            }

        /** Renombra un Perfil o informa el conflicto de identidad. */
        suspend fun renombrar(
            id: Long,
            nombre: String,
            ahora: Instant = Instant.now(),
        ): ResultadoRenombrado =
            withContext(Dispatchers.IO) {
                val actual = dao.porId(id) ?: return@withContext ResultadoRenombrado.NoExiste
                val normalizado = PerfilNormalizacion.normalizar(nombre, caseFolder)
                if (normalizado.isEmpty()) return@withContext ResultadoRenombrado.Rechazado()
                val existente = dao.porNombreNormalizado(normalizado)
                if (existente != null && existente.id != id) {
                    return@withContext ResultadoRenombrado.Conflicto(normalizado, existente)
                }
                try {
                    val filas = dao.actualizarNombre(id, nombre, normalizado, ahora)
                    if (filas == 0) {
                        ResultadoRenombrado.NoExiste
                    } else {
                        ResultadoRenombrado.Renombrado(
                            actual.copy(
                                nombre = nombre,
                                nombreNormalizado = normalizado,
                                actualizadoEn = ahora,
                            ),
                        )
                    }
                } catch (conflicto: SQLiteConstraintException) {
                    val carrera =
                        dao.porNombreNormalizado(normalizado)
                            ?: throw conflicto
                    ResultadoRenombrado.Conflicto(normalizado, carrera)
                }
            }

        companion object {
            /** Código contractual de rechazo de un nombre vacío o solo `White_Space`. */
            const val NOMBRE_PERFIL_VACIO: String = "nombre_perfil_vacio"

            /** Código contractual de conflicto de identidad de Perfil. */
            const val NOMBRE_PERFIL_DUPLICADO: String = "nombre_perfil_duplicado"

            /** Código de Perfil inexistente para las operaciones de gestión. */
            const val PERFIL_NO_EXISTE: String = "perfil_no_existe"

            /** Código de Lista inexistente al asociar un Perfil. */
            const val LISTA_NO_EXISTE: String = "lista_no_existe"
        }
    }
