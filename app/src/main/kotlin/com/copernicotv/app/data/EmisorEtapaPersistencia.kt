package com.copernicotv.app.data

import com.copernicotv.core.identity.AvanceEtapaImportacion
import com.copernicotv.core.identity.EtapaImportacion

/** Nanosegundos por milisegundo para los tiempos de etapa (E7-T7, S34). */
private const val NANOS_POR_MS = 1_000_000L

/**
 * Emisor de etapas de la capa de persistencia (E7-T7 ronda 4, S34):
 * `persistencia_canales`, `persistencia_grupos`, `persistencia_apariencias`
 * e `importacion_completada`. Mide el tiempo acumulado de la etapa y el
 * tiempo desde el último avance de la capa; las ops criptográficas del
 * pipeline ya viajan en las etapas de `:core` y aquí van en cero.
 */
internal class EmisorEtapaPersistencia(
    private val alEtapa: (AvanceEtapaImportacion) -> Unit,
    private val etapa: EtapaImportacion,
    private val opsCriptograficasTotales: Int = 0,
) {
    private val inicioNs = System.nanoTime()
    private var ultimoNs = inicioNs

    fun emitir(
        elementos: Int,
        canalesValidos: Int,
    ) {
        if (elementos <= 0) return
        val ahoraNs = System.nanoTime()
        alEtapa(
            AvanceEtapaImportacion(
                etapa = etapa,
                elementos = elementos,
                msEtapa = (ahoraNs - inicioNs) / NANOS_POR_MS,
                msDesdeUltimoAvance = (ahoraNs - ultimoNs) / NANOS_POR_MS,
                opsCriptograficas = opsCriptograficasTotales,
                canalesValidos = canalesValidos,
            ),
        )
        ultimoNs = ahoraNs
    }
}
