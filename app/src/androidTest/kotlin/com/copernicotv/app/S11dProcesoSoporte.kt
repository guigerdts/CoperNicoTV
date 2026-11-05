package com.copernicotv.app

import android.content.Context
import android.os.Process
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.copernicotv.app.data.HistorialRepositorio
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.app.data.db.entities.AparienciaEntity
import com.copernicotv.app.data.db.entities.CanalEntity
import com.copernicotv.app.data.db.entities.FuenteEntity
import com.copernicotv.app.data.db.entities.ListaEntity
import com.copernicotv.app.ui.PREFIJO_TAG_RECIENTE
import com.copernicotv.app.ui.TAG_ACCESO_RAPIDO
import com.copernicotv.app.ui.TAG_ACCIONES_CANAL
import com.copernicotv.core.identity.ClavePresentacion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import java.io.File
import java.time.Instant
import java.util.Properties

internal const val NOMBRE_LISTA_S11D = "Lista S11d"
internal const val CANALES_S11D = 12
internal const val VISIBLES_S11D = 10
internal const val ARCHIVO_MARCA_PROCESO_S11D = "s11d-proceso.properties"

private val BASE_FECHAS_S11D: Instant = Instant.parse("2026-02-01T00:00:00Z")

internal data class SemillaS11d(
    val listaId: Long,
    val canalIds: List<Long>,
    val nombres: List<String>,
) {
    val fechas: List<Instant>
        get() = canalIds.indices.map { indice -> BASE_FECHAS_S11D.plusSeconds(indice + 1L) }

    /** Los diez Canales que deben sobrevivir al recorte, del más reciente al más antiguo. */
    val ordenIds: List<Long>
        get() = canalIds.takeLast(VISIBLES_S11D).reversed()

    val ordenNombres: List<String>
        get() = nombres.takeLast(VISIBLES_S11D).reversed()
}

internal data class MarcaS11d(
    val identidad: String,
    val listaId: Long,
    val canalIds: List<Long>,
    val nombres: List<String>,
) {
    fun semilla(): SemillaS11d = SemillaS11d(listaId, canalIds, nombres)
}

internal data class EstadoS11d(
    val conteo: Int,
    val canales: List<Long>,
    val nombres: List<String?>,
    val fechas: List<Instant>,
    val primerCanalPresente: Boolean,
    val segundoCanalPresente: Boolean,
)

/**
 * Siembra una Lista activa con doce Canales y registra una reproducción por
 * Canal con fechas crecientes. `HistorialRepositorio.registrar` aplica el
 * recorte real de producción, por lo que quedan los diez más recientes.
 */
internal suspend fun sembrarHistorialS11d(
    db: AppDatabase,
    historial: HistorialRepositorio,
): SemillaS11d {
    val ahora = Instant.parse("2026-01-01T00:00:00Z")
    val fuenteId =
        db.fuenteDao().insertar(
            FuenteEntity(
                nombre = "Fuente S11d",
                estado = "activa",
                usarIntervaloGlobal = true,
                creadoEn = ahora,
                actualizadoEn = ahora,
            ),
        )
    val listaId =
        db.listaDao().insertar(
            ListaEntity(
                fuenteId = fuenteId,
                nombre = NOMBRE_LISTA_S11D,
                ubicacionCifrada = byteArrayOf(0x01),
                ubicacionHmac = byteArrayOf(0x02),
                esquema = "content",
                orden = 0,
                estado = "activa",
                creadoEn = ahora,
                actualizadoEn = ahora,
            ),
        )
    val nombres = (1..CANALES_S11D).map { "Canal $it" }
    val canalIds =
        db.canalDao().insertarTodos(
            nombres.map { nombre ->
                CanalEntity(
                    fuenteId = fuenteId,
                    nombre = nombre,
                    urlStreamCifrada = byteArrayOf(0x01),
                    urlStreamHmac = byteArrayOf(0x02),
                    claveCanonica = "s11d:canon:$nombre",
                    claveUnicidad = "s11d:canal:$nombre",
                    claveDerivable = true,
                    creadoEn = ahora,
                    actualizadoEn = ahora,
                )
            },
        )
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

    val semilla = SemillaS11d(listaId, canalIds, nombres)
    semilla.canalIds.forEachIndexed { indice, canalId ->
        historial.registrar(canalId, semilla.fechas[indice])
    }
    return semilla
}

internal suspend fun leerEstadoS11d(
    db: AppDatabase,
    historial: HistorialRepositorio,
    semilla: SemillaS11d,
): EstadoS11d {
    val ultimos = historial.ultimos()
    val nombresPorId = db.canalDao().todos().associate { it.id to it.nombre }
    return EstadoS11d(
        conteo = db.historialDao().contar(),
        canales = ultimos.map { it.canalId },
        nombres = ultimos.map { nombresPorId[it.canalId] },
        fechas = ultimos.map { it.fecha },
        primerCanalPresente = db.historialDao().porCanal(semilla.canalIds[0]) != null,
        segundoCanalPresente = db.historialDao().porCanal(semilla.canalIds[1]) != null,
    )
}

/** Contrato observable de S11d: límite, orden, fechas y Canales recuperados. */
internal fun afirmarEstadoS11d(
    estado: EstadoS11d,
    semilla: SemillaS11d,
) {
    assertEquals("Canales persistidos", VISIBLES_S11D, estado.conteo)
    assertEquals("Orden persistido", semilla.ordenIds, estado.canales)
    assertEquals("Nombres persistidos", semilla.ordenNombres, estado.nombres)
    assertEquals(
        "Fechas persistidas",
        semilla.fechas.takeLast(VISIBLES_S11D).reversed(),
        estado.fechas,
    )
    assertFalse("El Canal ${semilla.nombres[0]} no debía sobrevivir al recorte", estado.primerCanalPresente)
    assertFalse("El Canal ${semilla.nombres[1]} no debía sobrevivir al recorte", estado.segundoCanalPresente)
}

internal fun ReglaPrincipal.esperarAccesoS11d(semilla: SemillaS11d) {
    esperarTag(TAG_ACCESO_RAPIDO)
    semilla.ordenNombres.forEachIndexed { indice, nombre ->
        esperarTag(PREFIJO_TAG_RECIENTE + indice)
        esperarTexto(PREFIJO_TAG_RECIENTE + indice, nombre)
    }
    esperarTagAusente(PREFIJO_TAG_RECIENTE + VISIBLES_S11D)
}

internal fun ReglaPrincipal.abrirSegundoS11d(semilla: SemillaS11d) {
    onNodeWithTag(PREFIJO_TAG_RECIENTE + 1).performScrollTo().performClick()
    esperarTag(TAG_ACCIONES_CANAL)
    onNodeWithText("Acciones de ${semilla.ordenNombres[1]}").assertExists()
}

/** Identidad del proceso actual; el PID solo no basta porque Android puede reutilizarlo. */
internal fun identidadProcesoS11d(): String = "${Process.myPid()}:${Process.getStartElapsedRealtime()}"

private fun archivoMarcaS11d(contexto: Context): File = File(contexto.filesDir, ARCHIVO_MARCA_PROCESO_S11D)

internal fun borrarMarcaS11d(contexto: Context) {
    archivoMarcaS11d(contexto).delete()
}

internal fun escribirMarcaS11d(
    contexto: Context,
    semilla: SemillaS11d,
) {
    val propiedades = Properties()
    propiedades["identidad"] = identidadProcesoS11d()
    propiedades["lista"] = semilla.listaId.toString()
    propiedades["canales"] = semilla.canalIds.joinToString(",")
    propiedades["nombres"] = semilla.nombres.joinToString(",")
    archivoMarcaS11d(contexto).outputStream().use { propiedades.store(it, null) }
}

internal fun leerMarcaS11d(contexto: Context): MarcaS11d {
    val archivo = archivoMarcaS11d(contexto)
    check(archivo.isFile) {
        "Falta la marca de la fase 1; ejecute S11dProcesoFase1Test antes de la fase 2"
    }
    val propiedades = Properties()
    archivo.inputStream().use { propiedades.load(it) }

    fun requerida(clave: String): String = requireNotNull(propiedades.getProperty(clave)) { "La marca no contiene '$clave'" }
    return MarcaS11d(
        identidad = requerida("identidad"),
        listaId = requerida("lista").toLong(),
        canalIds = requerida("canales").split(',').map { it.toLong() },
        nombres = requerida("nombres").split(','),
    )
}
