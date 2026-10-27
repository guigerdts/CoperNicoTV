package com.copernicotv.app.data

import android.content.Context
import androidx.room.withTransaction
import com.copernicotv.app.crypto.ClavesApp
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.app.data.db.entities.AparienciaEntity
import com.copernicotv.app.data.db.entities.CanalEntity
import com.copernicotv.app.data.db.entities.FuenteEntity
import com.copernicotv.app.data.db.entities.GrupoEntity
import com.copernicotv.app.data.db.entities.ListaEntity
import com.copernicotv.core.crypto.CifradoAes
import com.copernicotv.core.crypto.HmacUrl
import com.copernicotv.core.identity.CampoUrl
import com.copernicotv.core.identity.CanonicalizadorUrl
import com.copernicotv.core.identity.ResultadoCanonizacion
import com.copernicotv.core.m3u.ImportadorCanario
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** Resumen de una importación canaria. */
data class ResumenImportacion(
    val listaId: Long?,
    val canales: Int,
    val rechazadas: Int,
    val error: String? = null,
)

/**
 * Persistencia del catálogo para el canario de E1-C.
 *
 * La importación es transaccional: o queda la Fuente, la Lista, los Canales,
 * los Grupos y las Apariencias completas, o no queda nada. No implementa
 * reconciliación de actualizaciones (E4) ni el parser tolerante (E2).
 */
@Singleton
class CatalogoRepositorio
    @Inject
    constructor(
        private val db: AppDatabase,
        private val claves: ClavesApp,
        @ApplicationContext private val contexto: Context,
    ) {
        private val io = Dispatchers.IO

        fun canalesDeLista(listaId: Long): Flow<List<String?>> = db.aparienciaDao().nombresDeLista(listaId)

        /** Importa el fixture sintético incluido en assets. */
        suspend fun importarFixture(): ResumenImportacion {
            val texto =
                withContext(io) {
                    contexto.assets.open(FIXTURE).bufferedReader(Charsets.UTF_8).use { it.readText() }
                }
            return importar(texto, NOMBRE_FUENTE, UBICACION_FIXTURE)
        }

        /**
         * Importa un texto M3U en una Fuente y una Lista nuevas.
         *
         * [ubicacion] es la ubicación original exacta de la lista; se cifra y
         * se indexa por HMAC sobre su forma canónica, nunca se guarda en claro.
         */
        suspend fun importar(
            texto: String,
            nombreFuente: String,
            ubicacion: String,
        ): ResumenImportacion =
            withContext(io) {
                val canonica =
                    CanonicalizadorUrl.canonizar(ubicacion, CampoUrl.LISTA_UBICACION)
                        as? ResultadoCanonizacion.Ok
                        ?: return@withContext ResumenImportacion(null, 0, 0, "ubicacion_invalida")
                val importado = ImportadorCanario.importar(texto, claves.claveHmac)
                if (importado.error != null) {
                    return@withContext ResumenImportacion(
                        null,
                        0,
                        importado.rechazadas.size,
                        importado.error.codigo,
                    )
                }
                val ahora = Instant.now()
                val listaId =
                    db.withTransaction {
                        val fuenteId =
                            db.fuenteDao().insertar(
                                FuenteEntity(
                                    nombre = nombreFuente,
                                    estado = "activa",
                                    usarIntervaloGlobal = true,
                                    creadoEn = ahora,
                                    actualizadoEn = ahora,
                                ),
                            )
                        val idLista =
                            db.listaDao().insertar(
                                ListaEntity(
                                    fuenteId = fuenteId,
                                    nombre = nombreFuente,
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
                                                generacion = ImportadorCanario.GENERACION_HMAC,
                                                campo = CampoUrl.LISTA_UBICACION,
                                                clase = canonica.clase,
                                                urlCanonica = canonica.urlCanonica,
                                            ).toByteArray(Charsets.UTF_8),
                                    esquema = canonica.urlCanonica.substringBefore(':'),
                                    orden = 0,
                                    estado = "activa",
                                    creadoEn = ahora,
                                    actualizadoEn = ahora,
                                ),
                            )
                        var orden = 0
                        for (canal in importado.canales) {
                            val canalId =
                                db.canalDao().insertar(
                                    CanalEntity(
                                        fuenteId = fuenteId,
                                        nombre = canal.nombre,
                                        urlStreamCifrada =
                                            CifradoAes.cifrar(
                                                claves.claveAes,
                                                CifradoAes.aad("Canal", "url_stream_cifrada"),
                                                canal.urlOriginal.toByteArray(Charsets.UTF_8),
                                            ),
                                        urlStreamHmac = canal.urlStreamHmac.toByteArray(Charsets.UTF_8),
                                        tvgId = canal.tvgId,
                                        claveCanonica = canal.claveCanonica,
                                        claveUnicidad = canal.claveUnicidad,
                                        claveDerivable = canal.claveDerivable,
                                        creadoEn = ahora,
                                        actualizadoEn = ahora,
                                    ),
                                )
                            for (apariencia in canal.apariencias) {
                                val grupoId = apariencia.groupTitle?.let { insertarGrupo(idLista, it) }
                                db.aparienciaDao().insertar(
                                    AparienciaEntity(
                                        listaId = idLista,
                                        canalId = canalId,
                                        grupoId = grupoId,
                                        orden = orden++,
                                        groupTitle = apariencia.groupTitle,
                                        tvgName = apariencia.tvgName,
                                        clavePresentacion = apariencia.clavePresentacion,
                                        repeticiones = apariencia.repeticiones,
                                        creadoEn = ahora,
                                        actualizadoEn = ahora,
                                    ),
                                )
                            }
                        }
                        idLista
                    }
                ResumenImportacion(listaId, importado.canales.size, importado.rechazadas.size)
            }

        private suspend fun insertarGrupo(
            listaId: Long,
            nombre: String,
        ): Long =
            db.grupoDao().porNombre(listaId, nombre)?.id
                ?: db.grupoDao().insertar(GrupoEntity(listaId = listaId, nombre = nombre))

        private companion object {
            const val FIXTURE = "fixture-canario.m3u"
            const val NOMBRE_FUENTE = "Fixture canario E1"
            const val UBICACION_FIXTURE = "content://com.copernicotv.app.fixture/fixture-canario.m3u"
        }
    }
