package com.copernicotv.app.data

import com.copernicotv.core.m3u.LecturaM3U

/*
 * Tipos de resultado de la escritura del catálogo, extraídos de
 * `CatalogoRepositorio` para mantenerlo bajo el límite de detekt (ronda 9).
 *
 * `PreparacionEntidadesCanales` siguió el mismo precedente (E7-T7).
 */

/** Lista persistida, cantidad de Canales y métricas de la escritura (S50). */
internal data class ListaPersistida(
    val id: Long,
    val canales: Int,
    val canalesValidos: Int = 0,
    val metricas: MetricasPersistencia? = null,
)

/**
 * Tiempos independientes de la escritura del catálogo (E7-T7 ronda 7, S50):
 * cifrado AES y inserción Room medidos por separado, sin atribuirse
 * mutuamente.
 */
data class MetricasPersistencia(
    val tiempoCifradoMs: Long,
    val operacionesAes: Int,
    val tiempoInsercionMs: Long,
    val lotes: Int,
    val transacciones: Int,
    /** Sobres escritos por versión (S5): `0x01` raíz / `0x02` clave de datos. */
    val sobre0x01: Int,
    val sobre0x02: Int,
    /** Tiempo de descifrado AES, separado del cifrado (S5). */
    val tiempoDescifradoMs: Long,
    /** Ciclo de vida de la clave de datos, separado de Room (S5). */
    val tiempoGeneracionClaveMs: Long,
    val tiempoProteccionClaveMs: Long,
    val tiempoDesproteccionClaveMs: Long,
    val operacionesKeystore: Int,
    /**
     * Desglose de la ronda 9 (S2): el tiempo de guardado que no es cifrado ni
     * inserción se reparte en preparación de entidades (incluye el cifrado),
     * preparación de grupos y apariencias, más el total de filas de
     * `Apariencia` insertadas.
     */
    val tiempoPreparacionMs: Long,
    val tiempoGruposMs: Long,
    val tiempoAparienciasMs: Long,
    val filasApariencias: Int,
)

/** Resultado interno de una descarga HTTP. */
internal sealed interface Descarga {
    data class Ok(
        val lectura: LecturaM3U,
        val diagnostico: DiagnosticoImportacion,
    ) : Descarga

    data class Fallo(
        val codigo: String,
        val diagnostico: DiagnosticoImportacion?,
    ) : Descarga
}
