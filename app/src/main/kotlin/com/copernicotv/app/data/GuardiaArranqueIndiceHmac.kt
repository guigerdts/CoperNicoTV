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
 * - Instalación sin sobres `hmac:vN` —sin datos protegidos—: primera
 *   inicialización legítima. Incorpora la clave de la generación persistida de
 *   forma explícita e idempotente.
 * - Fila de estado operativo cuya clave activa ya no está en la bóveda:
 *   pérdida real de datos. Preserva fila y sobres, transiciona a
 *   `recuperacion_pendiente` y el sellado queda bloqueado de manera efectiva:
 *   [IndiceHmacRepositorio.selloHmac] ya no entrega clave a ningún sitio de
 *   escritura.
 * - Estado ya no operativo (recuperación en curso o bloqueada): no toca nada;
 *   el flujo de reconstrucción es el dueño de esas fases.
 *
 * La evaluación completa se resuelve en la misma sección crítica que las
 * operaciones normales ([IndiceHmacRepositorio.evaluarIntegridad]): no existe
 * una ventana entre leer la fila y comprobar la clave.
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
        suspend fun evaluar(ahora: Instant = Instant.now()): ResultadoArranque =
            when (indice.evaluarIntegridad(ahora)) {
                is IntegridadIndiceHmac.Operativo -> ResultadoArranque.Operativo
                is IntegridadIndiceHmac.Inicializado -> ResultadoArranque.Inicializado
                is IntegridadIndiceHmac.Perdida -> ResultadoArranque.PerdidaDetectada
                is IntegridadIndiceHmac.Recuperacion -> ResultadoArranque.RecuperacionEnMarcha
            }

        /** Resultado de la evaluación de arranque. */
        sealed interface ResultadoArranque {
            /** Índice operativo con su clave activa presente. */
            data object Operativo : ResultadoArranque

            /** Primera inicialización legítima: clave persistida e idempotente. */
            data object Inicializado : ResultadoArranque

            /** Pérdida real detectada: transición a `recuperacion_pendiente`, sellado bloqueado. */
            data object PerdidaDetectada : ResultadoArranque

            /** La recuperación ya estaba abierta; la guardia no interviene. */
            data object RecuperacionEnMarcha : ResultadoArranque
        }
    }
