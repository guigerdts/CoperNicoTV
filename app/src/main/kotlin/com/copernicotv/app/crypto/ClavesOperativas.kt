package com.copernicotv.app.crypto

import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Claves operativas de sesión derivadas de las raíces de Keystore (E7-T7
 * ronda 6, S44).
 *
 * Cada operación HMAC con una clave no exportable de Android Keystore es una
 * transacción IPC de ~4 ms medida en dispositivo (895.712 ms para 217.863
 * operaciones en la importación Zona Cero de la ronda 6). El sellado del
 * catálogo hace decenas de miles de esas operaciones en el bucle de
 * importación; la derivación lo reduce a UNA operación de Keystore por raíz
 * por proceso:
 *
 * `operativa = HMAC-SHA256(raiz, "copernicotv/hmac/operativa/v1")`
 *
 * El sellado (`IndiceHmacRepositorio.selloHmac`, el reconstructor y sus
 * reingresos) entrega la operativa; la raíz queda como ancla de integridad:
 * su presencia en la bóveda es lo que `contieneClaveHmac` evalúa, y su
 * pérdida sigue bloqueando el sellado y declarando `recuperacion_pendiente`.
 * No cambia el formato de sobre (`hmac:vN:cvM:`), no hay bump de generación
 * ni migración: no existe verificación per-fila de sobres en runtime y los
 * sobres sellados con la raíz en builds anteriores permanecen válidos.
 *
 * La derivación es determinista por raíz: misma raíz ⇒ misma operativa en
 * cualquier proceso. La operativa vive en memoria como `SecretKeySpec` —
 * trade-off documentado y acotado: el material raíz no exportable nunca sale
 * de Keystore, y la operativa expuesta es una clave de firma cuyo dominio de
 * uso es exclusivo de los sobres. Los bytes intermedios de derivación se
 * zeroizan tras construir la clave.
 */
@Singleton
class ClavesOperativas
    @Inject
    constructor() {
        /** Operativas por raíz: `equals` de `SecretKeySpec` compara material. */
        private val porRaiz = ConcurrentHashMap<SecretKey, SecretKey>()

        /**
         * Devuelve la clave operativa derivada de [raiz], derivándola una sola
         * vez por proceso: bajo concurrencia `computeIfAbsent` calcula una
         * única derivación y las demás llamadas esperan y reciben la misma
         * instancia.
         */
        fun hmacOperativa(raiz: SecretKey): SecretKey =
            porRaiz.computeIfAbsent(raiz) { claveRaiz ->
                val bytes = derivar(claveRaiz)
                val operativa = SecretKeySpec(bytes, ALGORITMO)
                bytes.fill(0)
                operativa
            }

        /** Una operación HMAC con la raíz: el único coste de Keystore. */
        private fun derivar(raiz: SecretKey): ByteArray {
            val mac = Mac.getInstance(ALGORITMO)
            mac.init(raiz)
            return mac.doFinal(DOMINIO.toByteArray(Charsets.UTF_8))
        }

        companion object {
            const val ALGORITMO = "HmacSHA256"

            /** Dominio exclusivo de la derivación; ninguna otra ruta lo usa. */
            const val DOMINIO = "copernicotv/hmac/operativa/v1"
        }
    }
