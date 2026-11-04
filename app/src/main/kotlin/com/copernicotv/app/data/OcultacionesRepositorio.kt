package com.copernicotv.app.data

import com.copernicotv.app.data.db.dao.OcultacionCanalDao
import com.copernicotv.app.data.db.dao.OcultacionGrupoDao
import com.copernicotv.app.data.db.entities.OcultacionCanalEntity
import com.copernicotv.app.data.db.entities.OcultacionGrupoEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ocultaciones de Canal y de Grupo.
 *
 * Cada ocultación se identifica por la FK concreta: ocultar un Canal no toca
 * sus Listas ni sus Apariencias, y ocultar un Grupo no alcanza a Grupos
 * homónimos de otras Listas. Altas y bajas son idempotentes.
 */
@Singleton
class OcultacionesRepositorio
    @Inject
    constructor(
        private val canales: OcultacionCanalDao,
        private val grupos: OcultacionGrupoDao,
    ) {
        suspend fun ocultarCanal(
            canalId: Long,
            creadoEn: Instant = Instant.now(),
        ): Boolean =
            withContext(Dispatchers.IO) {
                canales.insertar(OcultacionCanalEntity(canalId = canalId, creadoEn = creadoEn)) != -1L
            }

        suspend fun mostrarCanal(canalId: Long): Boolean =
            withContext(Dispatchers.IO) {
                canales.eliminarPorCanal(canalId) > 0
            }

        suspend fun canalOculto(canalId: Long): Boolean =
            withContext(Dispatchers.IO) {
                canales.existe(canalId) != 0
            }

        suspend fun canalesOcultos(): List<Long> =
            withContext(Dispatchers.IO) {
                canales.canalesOcultos()
            }

        suspend fun ocultarGrupo(
            grupoId: Long,
            creadoEn: Instant = Instant.now(),
        ): Boolean =
            withContext(Dispatchers.IO) {
                grupos.insertar(OcultacionGrupoEntity(grupoId = grupoId, creadoEn = creadoEn)) != -1L
            }

        suspend fun mostrarGrupo(grupoId: Long): Boolean =
            withContext(Dispatchers.IO) {
                grupos.eliminarPorGrupo(grupoId) > 0
            }

        suspend fun grupoOculto(grupoId: Long): Boolean =
            withContext(Dispatchers.IO) {
                grupos.existe(grupoId) != 0
            }

        suspend fun gruposOcultos(): List<Long> =
            withContext(Dispatchers.IO) {
                grupos.gruposOcultos()
            }
    }
