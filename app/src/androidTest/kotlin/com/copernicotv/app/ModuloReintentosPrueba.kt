package com.copernicotv.app

import com.copernicotv.app.player.FabricaConectividad
import com.copernicotv.app.player.ModuloReintentos
import com.copernicotv.app.player.ObservadorConectividad
import com.copernicotv.app.player.PoliticaReintentos
import com.copernicotv.core.player.ConfiguracionImagen
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Singleton

/**
 * Política de reintentos con espera controlada por la prueba (E6-T4, ampliada
 * en E7-T10).
 *
 * Reemplaza solo el planificador y la conectividad: el presupuesto, las esperas
 * de S20 y el backoff de S4 siguen siendo los de producción. Cada reintento
 * queda suspendido en [PuertaReintentos] hasta que la prueba lo libera, y cada
 * periodo de estabilidad en [PuertaEstabilidad]; la red la controla
 * [ConectividadPrueba], que arranca siempre conectada para no alterar el
 * comportamiento de las pruebas que no la manipulan.
 */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [ModuloReintentos::class])
object ModuloReintentosPrueba {
    @Provides
    @Singleton
    fun politicaReintentos(): PoliticaReintentos =
        PoliticaReintentos(
            esperar = { PuertaReintentos.esperar() },
            esperarEstabilidad = { PuertaEstabilidad.esperar() },
        )

    @Provides
    @Singleton
    fun configuracionImagen(): ConfiguracionImagen =
        // Ventana corta: la escalera de imagen avanza rápido en el emulador
        // sin tocar la política de producción. Los tests existentes usan
        // fixtures con evidencia estable o sin audio, así que la detección
        // nunca abre casos espurios.
        ConfiguracionImagen(ventanaConfirmacionMs = 1_000L)

    @Provides
    @Singleton
    fun fabricaConectividad(): FabricaConectividad = FabricaConectividad { ConectividadPrueba }
}

/** Puerta compartida por las pruebas instrumentadas de reintentos. */
object PuertaReintentos {
    private val canal = Channel<Unit>(Channel.UNLIMITED)
    private val esperas = AtomicInteger(0)

    /** Reintentos que llegaron a la espera; sirve para probar que no hubo ninguno. */
    val esperasRegistradas: Int
        get() = esperas.get()

    /** Vacía permisos y contador antes de cada prueba. */
    fun reiniciar() {
        while (canal.tryReceive().isSuccess) {
            // Drena permisos que dejó una prueba anterior.
        }
        esperas.set(0)
    }

    /** Libera exactamente un reintento pendiente. */
    fun liberar() {
        canal.trySend(Unit)
    }

    suspend fun esperar() {
        esperas.incrementAndGet()
        canal.receive()
    }
}

/** Puerta del periodo de estabilidad que repone el presupuesto (S4). */
object PuertaEstabilidad {
    private val canal = Channel<Unit>(Channel.UNLIMITED)
    private val esperas = AtomicInteger(0)

    /** Veces que una sesión llegó a la espera de estabilidad. */
    val esperasRegistradas: Int
        get() = esperas.get()

    /** Vacía permisos y contador antes de cada prueba. */
    fun reiniciar() {
        while (canal.tryReceive().isSuccess) {
            // Drena permisos que dejó una prueba anterior.
        }
        esperas.set(0)
    }

    /** Completa el periodo de estabilidad pendiente. */
    fun liberar() {
        canal.trySend(Unit)
    }

    suspend fun esperar() {
        esperas.incrementAndGet()
        canal.receive()
    }
}

/**
 * Observador de conectividad controlable por las pruebas (S4).
 *
 * Es un objeto compartido porque el módulo de prueba reemplaza al de
 * producción para toda la compilación de `androidTest`: no puede haber dos.
 * Arranca conectado, así que las pruebas que no lo manipulan se comportan
 * igual que con la conectividad real. [ultimoCambioMs] deja constancia del
 * último cambio para depurar fluctuaciones.
 */
object ConectividadPrueba : ObservadorConectividad {
    private val conectado = AtomicBoolean(true)
    private val canal = Channel<Unit>(Channel.CONFLATED)
    private val ultimoCambio = AtomicLong(0L)

    /** Milisegundos del reloj de la prueba en el último cambio de red. */
    val ultimoCambioMs: Long
        get() = ultimoCambio.get()

    override fun hayConexion(): Boolean = conectado.get()

    override suspend fun esperarConexion() {
        while (!conectado.get()) {
            canal.receive()
        }
    }

    override fun cerrar() {
        // No-op: el objeto es compartido por todas las sesiones de la prueba.
    }

    /** Simula la pérdida de red; las esperas de red se quedan suspendidas. */
    fun desconectar() {
        ultimoCambio.set(System.currentTimeMillis())
        conectado.set(false)
    }

    /** Simula la vuelta de la red; libera las esperas suspendidas. */
    fun conectar() {
        ultimoCambio.set(System.currentTimeMillis())
        conectado.set(true)
        canal.trySend(Unit)
    }
}
