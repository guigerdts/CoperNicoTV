package com.copernicotv.app.crypto

import com.copernicotv.core.crypto.CifradoAes
import com.copernicotv.core.crypto.ResultadoDescifrado
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.LongAdder
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Métricas del ciclo de vida de la clave de datos de sesión (E7-T7 ronda 8,
 * S5). Todo separado del tiempo de Room: estas cifras miden solo criptografía.
 */
class MetricasClaveDatos {
    /** Operaciones reales contra Android Keystore (envolver/desproteger). */
    val operacionesKeystore = AtomicInteger(0)

    /**
     * Nanosegundos crudos de generar la clave con CSPRNG (ronda 9, S1): los
     * acumuladores guardan la unidad nativa de [System.nanoTime] y la
     * conversión a milisegundos ocurre solo en los accesores `ms*`, para que
     * un valor sub-milisegundo no se pierda al redondear.
     */
    val nanosGeneracion = LongAdder()

    /** Nanosegundos crudos de proteger (envolver) la clave con la raíz. */
    val nanosProteccion = LongAdder()

    /** Nanosegundos crudos de desproteger la clave envuelta con la raíz. */
    val nanosDesproteccion = LongAdder()

    /** Tiempo de generación de la clave, en milisegundos. */
    fun msGeneracion(): Long = nanosAMs(nanosGeneracion.sum())

    /** Tiempo de protección de la clave, en milisegundos. */
    fun msProteccion(): Long = nanosAMs(nanosProteccion.sum())

    /** Tiempo de desprotección de la clave, en milisegundos. */
    fun msDesproteccion(): Long = nanosAMs(nanosDesproteccion.sum())
}

/** Nanosegundos por milisegundo; la misma constante del informe (S1). */
internal const val NANOS_POR_MS_CLAVE = 1_000_000L

/**
 * Conversión ns→ms con redondeo hacia abajo, igual que el resto del informe
 * (ronda 9, S1): función interna para poder probar la unidad exacta.
 */
internal fun nanosAMs(nanos: Long): Long = nanos / NANOS_POR_MS_CLAVE

/**
 * Clave de datos de sesión para la envolvente `0x02` (E7-T7 ronda 8, S2).
 *
 * La raíz de Android Keystore se usa UNA vez por proceso: genera una clave
 * aleatoria de 32 bytes con CSPRNG, la protege con la raíz (sobre `0x01` de
 * [CifradoAes]) y persiste únicamente el sobre envuelto. El material en claro
 * vive solo en memoria durante la sesión; al reiniciar el proceso se
 * desprotege del sobre persistido con una única operación de Keystore.
 *
 * El AAD del sobre es `ClavesDatos/envuelta`: el sobre no puede moverse a
 * ninguna columna de datos. Si la desprotección falla (raíz inválida o sobre
 * corrupto), la clave anterior ya es irrecuperable para los datos `0x02`
 * existentes; se regenera una clave nueva y el catálogo anterior entra en el
 * mismo comportamiento de recuperación pendiente que los datos `0x01` con
 * raíz regenerada (S3): se reimporta. Nunca se borra el catálogo.
 *
 * Nota de honestidad sobre zeroización (misma limitación que
 * [ClavesOperativas]): el arreglo de bytes intermedio se limpia, pero
 * `SecretKeySpec` copia el material internamente y esa copia no es
 * zeroizable en la JVM.
 */
@Singleton
class ClavesDatos
    @Inject
    constructor(
        private val claves: ClavesApp,
        private val almacen: AlmacenClaveDatos,
    ) {
        val metricas = MetricasClaveDatos()

        private val cerrojo = Any()
        private val aleatorio = SecureRandom()

        @Volatile
        private var claveEnMemoria: SecretKey? = null

        /**
         * Clave de datos de la sesión. Se resuelve una vez por proceso: la
         * primera llamada desprotege o genera; las siguientes devuelven la
         * clave en memoria sin tocar Keystore.
         */
        fun clave(): SecretKey {
            claveEnMemoria?.let { return it }
            synchronized(cerrojo) {
                claveEnMemoria?.let { return it }
                val resuelta = resolver()
                claveEnMemoria = resuelta
                return resuelta
            }
        }

        /** Resuelve la clave: desprotege el sobre persistido o genera una nueva. */
        private fun resolver(): SecretKey {
            val raiz = claves.claveAes
            val persistido = almacen.leer()
            if (persistido != null) {
                val desprotegida = desproteger(raiz, persistido)
                if (desprotegida != null) return desprotegida
            }
            return generar(raiz)
        }

        /** Desprotege el sobre persistido; `null` si la autenticación falla. */
        private fun desproteger(
            raiz: SecretKey,
            sobre: ByteArray,
        ): SecretKey? {
            val inicio = System.nanoTime()
            metricas.operacionesKeystore.incrementAndGet()
            val resultado = CifradoAes.descifrar(raiz, AAD_ENVUELTA, sobre)
            metricas.nanosDesproteccion.add(System.nanoTime() - inicio)
            val bytes =
                when (resultado) {
                    is ResultadoDescifrado.Ok -> resultado.bytes
                    is ResultadoDescifrado.Fallo -> {
                        // CLAVE_INVALIDA o FALLO_AUTENTICACION: sin la clave
                        // de datos original, los datos 0x02 previos ya no son
                        // legibles. Se regenera (comportamiento de
                        // recuperación pendiente, S3) sin borrar nada.
                        null
                    }
                } ?: return null
            val clave = SecretKeySpec(bytes, "AES")
            bytes.fill(0)
            return clave
        }

        /** Genera con CSPRNG, protege con la raíz (UNA op) y persiste envuelta. */
        private fun generar(raiz: SecretKey): SecretKey {
            val inicioGeneracion = System.nanoTime()
            val material = ByteArray(LONGITUD_CLAVE).also(aleatorio::nextBytes)
            metricas.nanosGeneracion.add(System.nanoTime() - inicioGeneracion)

            val inicioProteccion = System.nanoTime()
            val sobre = CifradoAes.cifrar(raiz, AAD_ENVUELTA, material)
            metricas.nanosProteccion.add(System.nanoTime() - inicioProteccion)
            metricas.operacionesKeystore.incrementAndGet()

            // `SecretKeySpec` copia el material: la copia interna no es
            // zeroizable (misma limitación documentada arriba).
            val clave = SecretKeySpec(material, "AES")
            material.fill(0)
            almacen.guardar(sobre)
            return clave
        }

        private companion object {
            const val LONGITUD_CLAVE = 32
            val AAD_ENVUELTA = CifradoAes.aad("ClavesDatos", "envuelta")
        }
    }
