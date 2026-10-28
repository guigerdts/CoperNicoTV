package com.copernicotv.app.data

import android.database.sqlite.SQLiteConstraintException
import com.copernicotv.app.data.db.dao.PerfilDao
import com.copernicotv.app.data.db.entities.PerfilEntity
import com.copernicotv.core.identity.CaseFolder
import com.copernicotv.core.identity.PerfilNormalizacion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** Resultado de crear un Perfil sin fusionar identidades. */
sealed interface ResultadoAltaPerfil {
    /** Se insertó la fila; [perfil] conserva el nombre original y su id asignado. */
    data class Creado(val perfil: PerfilEntity) : ResultadoAltaPerfil

    /** Ya existe un Perfil con el mismo `nombreNormalizado`; no se insertó nada. */
    data class Conflicto(
        val normalizado: String,
        val existente: PerfilEntity,
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
        private val caseFolder: CaseFolder,
    ) {
        /** Crea un Perfil o informa el conflicto de identidad. */
        suspend fun crear(
            nombre: String,
            ahora: Instant = Instant.now(),
        ): ResultadoAltaPerfil =
            withContext(Dispatchers.IO) {
                val normalizado = PerfilNormalizacion.normalizar(nombre, caseFolder)
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
    }
