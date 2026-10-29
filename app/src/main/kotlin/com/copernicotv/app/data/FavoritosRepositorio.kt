package com.copernicotv.app.data

import com.copernicotv.app.data.db.dao.FavoritoDao
import com.copernicotv.app.data.db.entities.FavoritoEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Favoritos por Canal.
 *
 * Las altas y bajas son idempotentes: repetir una alta no duplica la fila y
 * repetir una baja no falla. Ninguna operación borra ni modifica el Canal.
 */
@Singleton
class FavoritosRepositorio
    @Inject
    constructor(
        private val dao: FavoritoDao,
    ) {
        /** Devuelve `true` solo cuando la fila no existía y se insertó. */
        suspend fun agregar(
            canalId: Long,
            creadoEn: Instant = Instant.now(),
        ): Boolean =
            withContext(Dispatchers.IO) {
                dao.insertar(FavoritoEntity(canalId = canalId, creadoEn = creadoEn)) != -1L
            }

        /** Devuelve `true` solo cuando la fila existía y se eliminó. */
        suspend fun quitar(canalId: Long): Boolean =
            withContext(Dispatchers.IO) {
                dao.eliminarPorCanal(canalId) > 0
            }

        suspend fun esFavorito(canalId: Long): Boolean =
            withContext(Dispatchers.IO) {
                dao.existe(canalId) != 0
            }

        suspend fun canalesFavoritos(): List<Long> =
            withContext(Dispatchers.IO) {
                dao.canalesFavoritos()
            }
    }
