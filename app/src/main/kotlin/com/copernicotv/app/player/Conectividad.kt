package com.copernicotv.app.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Observador del estado de conectividad del dispositivo (E7-T10, S4).
 *
 * La recuperación lo consulta antes de preparar el motor y se suspende en
 * [esperarConexion] cuando el dispositivo está desconectado: la espera de
 * red no consume reintentos ni genera peticiones al servidor. Solo informa
 * disponibilidad de red, nunca disponibilidad del servidor IPTV: esa
 * diferencia la fija el error del motor, no este observador.
 */
interface ObservadorConectividad {
    /** `true` si hay una red validada disponible en este instante. */
    fun hayConexion(): Boolean

    /** Se suspende hasta que vuelva la conexión; retorna al instante si ya hay. */
    suspend fun esperarConexion()

    /** Libera los recursos del observador; idempotente. */
    fun cerrar()
}

/**
 * Fábrica de observadores de conectividad.
 *
 * Cada sesión de reproducción crea el suyo y lo libera al cerrarse: así
 * nunca hay más de un observador registrado por sesión y el ciclo de vida
 * queda en manos del dueño de la sesión.
 */
fun interface FabricaConectividad {
    fun crear(): ObservadorConectividad
}

/**
 * Observador real sobre [ConnectivityManager].
 *
 * Registra un único callback predeterminado al construirse. El estado se
 * siembra con la red activa y luego lo mantienen los eventos del callback;
 * `NET_CAPABILITY_VALIDATED` distingue una red con salida real a Internet
 * de una red meramente asociada (portales cautivos).
 */
class ConectividadAndroid(contexto: Context) : ObservadorConectividad {
    private val conectado = AtomicBoolean(false)
    private val canal = Channel<Unit>(Channel.CONFLATED)
    private val administrador =
        contexto.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val devolucion =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                conectado.set(true)
                canal.trySend(Unit)
            }

            override fun onLost(network: Network) {
                conectado.set(false)
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capacidades: NetworkCapabilities,
            ) {
                val valida = capacidades.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                conectado.set(valida)
                if (valida) canal.trySend(Unit)
            }
        }

    init {
        conectado.set(redActivaValidada())
        runCatching { administrador.registerDefaultNetworkCallback(devolucion) }
    }

    override fun hayConexion(): Boolean = conectado.get()

    override suspend fun esperarConexion() {
        while (!conectado.get()) {
            canal.receive()
        }
    }

    override fun cerrar() {
        runCatching { administrador.unregisterNetworkCallback(devolucion) }
    }

    /** Estado inicial observado sin esperar al primer evento del callback. */
    private fun redActivaValidada(): Boolean =
        administrador.activeNetwork
            ?.let { red -> administrador.getNetworkCapabilities(red) }
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
}
