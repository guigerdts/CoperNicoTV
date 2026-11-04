package com.copernicotv.app

import android.content.Context
import android.os.Process
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import com.copernicotv.app.data.PreferenciasOrden
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.app.data.db.entities.AparienciaEntity
import com.copernicotv.app.data.db.entities.CanalEntity
import com.copernicotv.app.data.db.entities.ColeccionCanalEntity
import com.copernicotv.app.data.db.entities.ColeccionEntity
import com.copernicotv.app.data.db.entities.FuenteEntity
import com.copernicotv.app.data.db.entities.ListaEntity
import com.copernicotv.app.ui.PREFIJO_TAG_CANAL
import com.copernicotv.app.ui.PREFIJO_TAG_LISTA
import com.copernicotv.app.ui.PREFIJO_TAG_MODO_OPCION
import com.copernicotv.app.ui.TAG_LISTA_ACTIVA
import com.copernicotv.app.ui.TAG_RESULTADOS
import com.copernicotv.core.identity.ClavePresentacion
import com.copernicotv.core.orden.DireccionOrden
import com.copernicotv.core.orden.ModoOrden
import com.copernicotv.core.orden.PreferenciaOrden
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import java.io.File
import java.time.Instant
import java.util.Properties

internal const val NOMBRE_LISTA_L1 = "Lista E3-T8 L1"
internal const val NOMBRE_LISTA_L2 = "Lista E3-T8 L2"
internal const val NOMBRE_COLECCION_E3T8 = "Colección E3-T8"
internal const val ARCHIVO_MARCA_PROCESO = "e3t8-proceso.properties"

internal data class SemillaE3T8(
    val lista1Id: Long,
    val lista2Id: Long,
    val canalIds: List<Long>,
    val coleccionId: Long,
)

internal data class MarcaProceso(
    val identidad: String,
    val lista1Id: Long,
    val lista2Id: Long,
    val canalIds: List<Long>,
    val coleccionId: Long,
) {
    fun semilla(): SemillaE3T8 = SemillaE3T8(lista1Id, lista2Id, canalIds, coleccionId)
}

internal data class EstadoE3T8(
    val nombresL1: List<String?>,
    val nombresL2: List<String?>,
    val canalesColeccion: List<Long>,
    val canales: Map<Long, String?>,
    val aparienciasL1: List<Long>,
    val aparienciasL2: List<Long>,
    val preferenciaL1: PreferenciaOrden,
    val preferenciaL2: PreferenciaOrden,
)

/**
 * Siembra L1/L2 con tres Canales compartidos, una Colección y preferencias
 * distintas. L1 queda activa y con orden manual; L2 queda inactiva y con orden
 * por nombre descendente para detectar una escritura accidental de la
 * preferencia A/B/C al reordenar.
 */
internal suspend fun sembrarE3T8(
    db: AppDatabase,
    preferencias: PreferenciasOrden,
): SemillaE3T8 {
    val ahora = Instant.parse("2026-01-01T00:00:00Z")
    val fuenteId =
        db.fuenteDao().insertar(
            FuenteEntity(
                nombre = "Fuente E3-T8",
                estado = "activa",
                usarIntervaloGlobal = true,
                creadoEn = ahora,
                actualizadoEn = ahora,
            ),
        )
    val lista1Id =
        db.listaDao().insertar(
            ListaEntity(
                fuenteId = fuenteId,
                nombre = NOMBRE_LISTA_L1,
                ubicacionCifrada = byteArrayOf(0x01),
                ubicacionHmac = byteArrayOf(0x02),
                esquema = "content",
                orden = 0,
                estado = "activa",
                creadoEn = ahora,
                actualizadoEn = ahora,
            ),
        )
    val lista2Id =
        db.listaDao().insertar(
            ListaEntity(
                fuenteId = fuenteId,
                nombre = NOMBRE_LISTA_L2,
                ubicacionCifrada = byteArrayOf(0x03),
                ubicacionHmac = byteArrayOf(0x04),
                esquema = "content",
                orden = 1,
                estado = "inactiva",
                creadoEn = ahora,
                actualizadoEn = ahora,
            ),
        )
    val canalIds =
        db.canalDao().insertarTodos(
            listOf("C1", "C2", "C3").map { nombre ->
                CanalEntity(
                    fuenteId = fuenteId,
                    nombre = nombre,
                    urlStreamCifrada = byteArrayOf(0x01),
                    urlStreamHmac = byteArrayOf(0x02),
                    claveCanonica = "canon:$nombre",
                    claveUnicidad = "canal:$nombre",
                    claveDerivable = true,
                    creadoEn = ahora,
                    actualizadoEn = ahora,
                )
            },
        )
    insertarApariencias(db, lista1Id, canalIds, ahora)
    insertarApariencias(db, lista2Id, listOf(canalIds[2], canalIds[1], canalIds[0]), ahora)

    val coleccionId =
        db.coleccionDao().insertar(
            ColeccionEntity(
                nombre = NOMBRE_COLECCION_E3T8,
                creadoEn = ahora,
                actualizadoEn = ahora,
            ),
        )
    listOf(canalIds[1], canalIds[0], canalIds[2]).forEachIndexed { posicion, canalId ->
        db.coleccionCanalDao().insertar(
            ColeccionCanalEntity(
                coleccionId = coleccionId,
                canalId = canalId,
                posicion = posicion,
            ),
        )
    }

    preferencias.guardar(
        lista1Id,
        PreferenciaOrden(ModoOrden.MANUAL, DireccionOrden.ASCENDENTE),
    )
    preferencias.guardar(
        lista2Id,
        PreferenciaOrden(ModoOrden.NOMBRE, DireccionOrden.DESCENDENTE),
    )
    return SemillaE3T8(lista1Id, lista2Id, canalIds, coleccionId)
}

private suspend fun insertarApariencias(
    db: AppDatabase,
    listaId: Long,
    canalIds: List<Long>,
    ahora: Instant,
) {
    db.aparienciaDao().insertarTodas(
        canalIds.mapIndexed { orden, canalId ->
            AparienciaEntity(
                listaId = listaId,
                canalId = canalId,
                orden = orden,
                clavePresentacion = ClavePresentacion.clave(null, null),
                repeticiones = 1,
                creadoEn = ahora,
                actualizadoEn = ahora,
            )
        },
    )
}

internal suspend fun leerEstadoE3T8(
    db: AppDatabase,
    preferencias: PreferenciasOrden,
    semilla: SemillaE3T8,
): EstadoE3T8 {
    val canales = db.canalDao().todos().associate { it.id to it.nombre }
    val aparienciasL1 = db.aparienciaDao().porLista(semilla.lista1Id)
    val aparienciasL2 = db.aparienciaDao().porLista(semilla.lista2Id)
    return EstadoE3T8(
        nombresL1 = aparienciasL1.map { canales[it.canalId] },
        nombresL2 = aparienciasL2.map { canales[it.canalId] },
        canalesColeccion =
            db
                .coleccionCanalDao()
                .porColeccion(semilla.coleccionId)
                .sortedBy { it.posicion }
                .map { it.canalId },
        canales = canales,
        aparienciasL1 = aparienciasL1.map { it.canalId },
        aparienciasL2 = aparienciasL2.map { it.canalId },
        preferenciaL1 = preferencias.preferencia(semilla.lista1Id).first(),
        preferenciaL2 = preferencias.preferencia(semilla.lista2Id).first(),
    )
}

/**
 * Contrato compartido de E3-T8: el reordenamiento de L1 no toca L2, la
 * Colección, la identidad de los Canales ni la preferencia A/B/C.
 */
internal fun afirmarEstadoE3T8(
    estado: EstadoE3T8,
    semilla: SemillaE3T8,
) {
    assertEquals(listOf("C2", "C1", "C3"), estado.nombresL1)
    assertEquals(listOf("C3", "C2", "C1"), estado.nombresL2)
    assertEquals(
        listOf(semilla.canalIds[1], semilla.canalIds[0], semilla.canalIds[2]),
        estado.aparienciasL1,
    )
    assertEquals(
        listOf(semilla.canalIds[2], semilla.canalIds[1], semilla.canalIds[0]),
        estado.aparienciasL2,
    )
    assertEquals(
        listOf(semilla.canalIds[1], semilla.canalIds[0], semilla.canalIds[2]),
        estado.canalesColeccion,
    )
    assertEquals(semilla.canalIds.toSet(), estado.canales.keys)
    assertEquals(
        PreferenciaOrden(ModoOrden.MANUAL, DireccionOrden.ASCENDENTE),
        estado.preferenciaL1,
    )
    assertEquals(
        PreferenciaOrden(ModoOrden.NOMBRE, DireccionOrden.DESCENDENTE),
        estado.preferenciaL2,
    )
}

/** Identidad del proceso actual; el PID solo no basta porque Android puede reutilizarlo. */
internal fun identidadProceso(): String = "${Process.myPid()}:${Process.getStartElapsedRealtime()}"

private fun archivoMarca(contexto: Context): File = File(contexto.filesDir, ARCHIVO_MARCA_PROCESO)

internal fun borrarMarca(contexto: Context) {
    archivoMarca(contexto).delete()
}

internal fun escribirMarca(
    contexto: Context,
    semilla: SemillaE3T8,
) {
    val propiedades = Properties()
    propiedades["identidad"] = identidadProceso()
    propiedades["lista1"] = semilla.lista1Id.toString()
    propiedades["lista2"] = semilla.lista2Id.toString()
    propiedades["canales"] = semilla.canalIds.joinToString(",")
    propiedades["coleccion"] = semilla.coleccionId.toString()
    archivoMarca(contexto).outputStream().use { propiedades.store(it, null) }
}

internal fun leerMarca(contexto: Context): MarcaProceso {
    val archivo = archivoMarca(contexto)
    check(archivo.isFile) {
        "Falta la marca de la fase 1; ejecute E3T8ProcesoFase1Test antes de la fase 2"
    }
    val propiedades = Properties()
    archivo.inputStream().use { propiedades.load(it) }

    fun requerida(clave: String): String = requireNotNull(propiedades.getProperty(clave)) { "La marca no contiene '$clave'" }
    return MarcaProceso(
        identidad = requerida("identidad"),
        lista1Id = requerida("lista1").toLong(),
        lista2Id = requerida("lista2").toLong(),
        canalIds = requerida("canales").split(',').map { it.toLong() },
        coleccionId = requerida("coleccion").toLong(),
    )
}

internal fun ReglaPrincipal.esperarListaE3T8(nombre: String) {
    esperarHastaE3T8 {
        onAllNodesWithText("Lista: $nombre").fetchSemanticsNodes().isNotEmpty()
    }
}

internal fun ReglaPrincipal.esperarTagE3T8(tag: String) {
    esperarTag(tag, TIEMPO_ESPERA_E3T8)
}

internal fun ReglaPrincipal.esperarModoSeleccionadoE3T8(modo: ModoOrden) {
    esperarHastaE3T8 {
        runCatching {
            onNodeWithTag(PREFIJO_TAG_MODO_OPCION + modo.codigo).performScrollTo().assertIsSelected()
        }.isSuccess
    }
}

internal fun ReglaPrincipal.seleccionarListaE3T8(
    listaId: Long,
    nombre: String,
) {
    onNodeWithTag(TAG_LISTA_ACTIVA).performScrollTo().performClick()
    esperarTagE3T8(PREFIJO_TAG_LISTA + listaId)
    onNodeWithTag(PREFIJO_TAG_LISTA + listaId).performClick()
    esperarListaE3T8(nombre)
}

internal fun ReglaPrincipal.esperarTextoE3T8(
    indice: Int,
    texto: String,
) {
    esperarHastaE3T8 {
        runCatching {
            onNodeWithTag(TAG_RESULTADOS).performScrollToIndex(indice)
            onNodeWithTag(PREFIJO_TAG_CANAL + indice).assertTextEquals(texto)
        }.isSuccess
    }
}

internal fun ReglaPrincipal.esperarHastaE3T8(condicion: () -> Boolean) {
    esperarHasta(TIEMPO_ESPERA_E3T8, condicion)
}

private const val TIEMPO_ESPERA_E3T8 = 20_000L
