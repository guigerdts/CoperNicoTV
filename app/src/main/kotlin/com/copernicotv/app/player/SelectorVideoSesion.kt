package com.copernicotv.app.player

import com.copernicotv.core.player.DeclaracionFormatoVideo
import com.copernicotv.core.player.SeleccionVideo
import com.copernicotv.core.player.perfilNivelDeCodec

/**
 * Selección de decoder de video de una sesión (E7-T11, T3).
 *
 * Consulta el inventario del dispositivo una sola vez por formato
 * declarado: los códecs del dispositivo no cambian en runtime, así que la
 * misma declaración produce siempre el mismo veredicto. La conversión de
 * la pista observada a declaración y el texto legible de perfil/nivel
 * viven aquí para no inflar la sesión.
 */
class SelectorVideoSesion(
    private val inventario: InventarioDecodificadores = InventarioDecodificadores(),
) {
    private val memo = mutableMapOf<String, SeleccionVideo>()

    /**
     * Veredicto del inventario para la pista de video detectada; `null`
     * sin pista: la sesión publica los cuatro campos vacíos.
     */
    fun seleccion(pista: InfoPista?): SeleccionVideo? {
        val declaracion = declaracionDe(pista) ?: return null
        val clave = "${declaracion.mime}|${declaracion.codec}|${declaracion.ancho}|${declaracion.alto}"
        return memo.getOrPut(clave) { inventario.seleccionar(declaracion) }
    }

    /** Perfil/nivel legible del codec string; nulo si el formato no lo declara. */
    fun perfilNivelTexto(codec: String?): String? =
        perfilNivelDeCodec(codec)?.let { "perfil 0x%02X nivel 0x%02X".format(it.perfil, it.nivel) }

    /** Declaración de formato con la resolución parseada del texto ancho x alto. */
    private fun declaracionDe(pista: InfoPista?): DeclaracionFormatoVideo? {
        pista ?: return null
        val medidas = pista.resolucion?.split('x')
        return DeclaracionFormatoVideo(
            mime = pista.mime,
            codec = pista.codec,
            ancho = medidas?.getOrNull(0)?.toIntOrNull(),
            alto = medidas?.getOrNull(1)?.toIntOrNull(),
        )
    }
}
