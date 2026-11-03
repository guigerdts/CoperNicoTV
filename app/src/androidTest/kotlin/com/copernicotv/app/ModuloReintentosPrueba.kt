package com.copernicotv.app

import com.copernicotv.app.player.ModuloReintentos
import com.copernicotv.app.player.PoliticaReintentos
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Singleton

/**
 * Política de reintentos con espera controlada por la prueba (E6-T4).
 *
 * Reemplaza solo el planificador: el presupuesto y las esperas de S20 siguen
 * siendo los de producción. Cada reintento queda suspendido en
 * [PuertaReintentos] hasta que la prueba lo libera, así que la secuencia se
 * observa sin dormir y sin depender del reloj.
 */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [ModuloReintentos::class])
object ModuloReintentosPrueba {
    @Provides
    @Singleton
    fun politicaReintentos(): PoliticaReintentos = PoliticaReintentos(esperar = { PuertaReintentos.esperar() })
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
