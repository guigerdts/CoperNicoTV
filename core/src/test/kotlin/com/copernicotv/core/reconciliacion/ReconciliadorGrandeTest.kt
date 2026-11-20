package com.copernicotv.core.reconciliacion

import com.copernicotv.core.identity.EstadoCanal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Reconciliación de un catálogo de 147 000 canales con heap acotado.
 *
 * No forma parte de `:core:test`: se ejecuta con la tarea
 * `:core:reconciliacionGrande`, que fija `-Xmx256m` para reproducir el límite
 * real del dispositivo. La prueba estable demuestra el caso de una
 * actualización sin cambios; la prueba con mutaciones demuestra altas, bajas y
 * modificaciones sobre el mismo tamaño.
 */
class ReconciliadorGrandeTest {
    private val total: Int = 147_000

    @Test
    fun `un catalogo estable de 147000 canales se reconcilia completo`() {
        val historicas = ArrayList<FilaHistorica>(total)
        val declaraciones = ArrayList<DeclaracionNueva>(total)
        for (posicion in 0 until total) {
            val historica =
                IdentidadReconciliacion(
                    nombre = "Canal $posicion",
                    tvgId = "tvg-$posicion",
                    claveCanonica = "canon:$posicion",
                    claveUnicidad = "tvg:$posicion",
                    claveDerivable = true,
                    urlStreamHmac = "hmac:$posicion",
                )
            val declarada =
                IdentidadReconciliacion(
                    nombre = "Canal $posicion",
                    tvgId = "tvg-$posicion",
                    claveCanonica = "canon:$posicion",
                    claveUnicidad = "tvg:$posicion",
                    claveDerivable = true,
                    urlStreamHmac = "hmac:$posicion",
                )
            historicas += FilaHistorica(id = posicion.toLong() + 1, identidad = historica, estado = EstadoCanal.A)
            declaraciones += DeclaracionNueva(indice = posicion, identidad = declarada)
        }

        val inicio = System.nanoTime()
        val resultado = Reconciliador.reconciliar(historicas, declaraciones)
        val transcurrido = (System.nanoTime() - inicio) / 1_000_000
        println("reconciliacion estable: ${resultado.filas.size} filas en $transcurrido ms")

        assertEquals(total, resultado.filas.size)
        assertTrue(resultado.filas.all { it.estado == EstadoCanal.A })
        assertTrue(resultado.filas.all { it.declaracionIndice != null })
        assertTrue(resultado.ausencias.isEmpty(), "un catálogo idéntico no debe producir ausencias")
        assertTrue(resultado.declaraciones.isEmpty(), "un catálogo idéntico no debe producir canales nuevos")
        assertTrue(resultado.colisiones.isEmpty())
        assertTrue(resultado.cierresC.isEmpty())
        assertTrue(transcurrido < 60_000, "la reconciliación tardó $transcurrido ms")
    }

    @Test
    fun `un catalogo de 147000 canales conserva altas bajas y modificaciones`() {
        val historicas = ArrayList<FilaHistorica>(total)
        val declaraciones = ArrayList<DeclaracionNueva>(total)
        val bajas = 5
        val altas = 10
        val modificadas = 2

        for (posicion in 0 until total) {
            val puedeModificarse = posicion >= bajas && posicion < bajas + modificadas
            val historica =
                IdentidadReconciliacion(
                    nombre = "Canal $posicion",
                    tvgId = "tvg-$posicion",
                    claveCanonica = "canon:$posicion",
                    claveUnicidad = "tvg:$posicion",
                    claveDerivable = true,
                    urlStreamHmac = "hmac:$posicion",
                )
            val nombreDeclarado = if (puedeModificarse) "Canal renovado $posicion" else "Canal $posicion"
            val canonicaDeclarada = if (puedeModificarse) "canon:renovado:$posicion" else "canon:$posicion"
            val declarada =
                IdentidadReconciliacion(
                    nombre = nombreDeclarado,
                    tvgId = "tvg-$posicion",
                    claveCanonica = canonicaDeclarada,
                    claveUnicidad = "tvg:$posicion",
                    claveDerivable = true,
                    urlStreamHmac = "hmac:$posicion",
                )
            historicas += FilaHistorica(id = posicion.toLong() + 1, identidad = historica, estado = EstadoCanal.A)
            // Las primeras `bajas` filas no se declaran: deben quedar como ausentes.
            if (posicion >= bajas) declaraciones += DeclaracionNueva(indice = declaraciones.size, identidad = declarada)
        }
        // Altas: canales nuevos sin evidencia compartida con el catálogo anterior.
        repeat(altas) { indiceAlta ->
            val posicion = total + indiceAlta
            declaraciones +=
                DeclaracionNueva(
                    indice = declaraciones.size,
                    identidad =
                        IdentidadReconciliacion(
                            nombre = "Canal nuevo $posicion",
                            tvgId = "tvg-nuevo-$posicion",
                            claveCanonica = "canon:nuevo:$posicion",
                            claveUnicidad = "tvg:nuevo:$posicion",
                            claveDerivable = true,
                            urlStreamHmac = "hmac:nuevo:$posicion",
                        ),
                )
        }

        val inicio = System.nanoTime()
        val resultado = Reconciliador.reconciliar(historicas, declaraciones)
        val transcurrido = (System.nanoTime() - inicio) / 1_000_000
        println("reconciliacion con mutaciones: ${resultado.filas.size} filas en $transcurrido ms")

        assertEquals(total, resultado.filas.size)
        assertEquals(bajas, resultado.ausencias.size)
        assertEquals((0 until bajas).map { it.toLong() + 1 }, resultado.ausencias)
        assertEquals(altas, resultado.declaraciones.size)
        assertTrue(resultado.declaraciones.all { it.destino == DestinoDeclaracion.CANAL_NUEVO })
        assertTrue(resultado.filas.all { it.estado == EstadoCanal.A })
        assertEquals(
            total - bajas,
            resultado.filas.count { it.declaracionIndice != null },
        )
        assertEquals(
            "Canal renovado $bajas",
            resultado.filas.first { it.historicoId == bajas.toLong() + 1 }.identidad.nombre,
        )
        assertEquals(
            "canon:renovado:${bajas + 1}",
            resultado.filas.first { it.historicoId == (bajas + 2).toLong() }.identidad.claveCanonica,
        )
        assertTrue(resultado.colisiones.isEmpty())
        assertTrue(transcurrido < 60_000, "la reconciliación tardó $transcurrido ms")
    }
}
