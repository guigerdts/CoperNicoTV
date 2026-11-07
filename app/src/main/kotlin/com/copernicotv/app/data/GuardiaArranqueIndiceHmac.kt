package com.copernicotv.app.data

import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Guardia de arranque del índice HMAC (DC-04 §7, E2-T6; Q2).
 *
 * Decide en un segundo plano durante `onCreate` qué hacer con la clave HMAC
 * sin regenerarla nunca en silencio:
 *
 * - Instalación virgen —sin fila de estado ni sobres `hmac:vN`—: primera
 *   inicialización legítima. Incorpora la clave `v1` de forma explícita e
 *   idempotente ([IndiceHmacRepositorio.inicializarLegitimamente]).
 * - Fila de estado operativo cuya clave activa ya no está en la bóveda:
 *   pérdida real de datos. Preserva fila y sobres, transiciona a
 *   `recuperacion_pendiente` y el sellado queda bloqueado de manera efectiva:
 *   [IndiceHmacRepositorio.selloHmac] ya no entrega clave a ningún sitio de
 *   escritura.
 * - Estado ya no operativo (recuperación en curso o bloqueada): no toca nada;
 *   el flujo de reconstrucción es el dueño de esas fases.
 *
 * Ninguna rama lanza: un arranque con el índice bloqueado sigue siendo un
 * arranque válido. El resultado se reporta para la evidencia técnica DC-04.
 */
@Singleton
class GuardiaArranqueIndiceHmac
    @Inject
    constructor(
        private val indice: IndiceHmacRepositorio,
    ) {
        suspend fun evaluar(ahora: Instant = Instant.now()): ResultadoArranque {
            val fila = indice.estadoSinInicializar()
            if (fila == null) {
                if (indice.haySobres()) {
                    // Anomalía: hay sobres sellados pero la fila de estado que
                    // describe su generación se perdió. Se preservan los datos
                    // y se declara la pérdida a partir de la fila v1 que
                    // materializa el singleton.
                    indice.estado(ahora)
                    indice.detectarPerdidaClave(ahora)
                    return ResultadoArranque.PerdidaDetectada
                }
                indice.inicializarLegitimamente(ahora)
                return ResultadoArranque.Inicializado
            }
            val estado = indice.estado(ahora)
            if (!estado.esOperativo) {
                return ResultadoArranque.RecuperacionEnMarcha
            }
            if (indice.clavePresente(estado.generacionActiva)) {
                return ResultadoArranque.Operativo
            }
            indice.detectarPerdidaClave(ahora)
            return ResultadoArranque.PerdidaDetectada
        }

        /** Resultado de la evaluación de arranque. */
        sealed interface ResultadoArranque {
            /** Índice operativo con su clave activa presente. */
            data object Operativo : ResultadoArranque

            /** Primera inicialización legítima: clave `v1` incorporada y fila persistida. */
            data object Inicializado : ResultadoArranque

            /** Pérdida real detectada: transición a `recuperacion_pendiente`, sellado bloqueado. */
            data object PerdidaDetectada : ResultadoArranque

            /** La recuperación ya estaba abierta; la guardia no interviene. */
            data object RecuperacionEnMarcha : ResultadoArranque
        }
    }
