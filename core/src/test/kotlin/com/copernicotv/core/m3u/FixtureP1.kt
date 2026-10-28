package com.copernicotv.core.m3u

import java.security.MessageDigest

/**
 * Fixture determinista de DC-10 y su hash registrado.
 *
 * Una única definición evita que la prueba funcional y la medición P1
 * diverjan en la ruta, el conteo de entradas o la huella del archivo.
 */
object FixtureP1 {
    const val RUTA: String = "/fixtures/F-P1-10k.m3u"
    const val ENTRADAS: Int = 10_000
    const val SHA256: String = "45c489a557efcafa53af5bffd9a001aeb2cdc1616c40a4f823e2f39817ddd04c"

    fun bytes(): ByteArray =
        requireNotNull(FixtureP1::class.java.getResourceAsStream(RUTA)) {
            "no se encontró el recurso $RUTA"
        }.use { it.readBytes() }

    fun sha256(bytes: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }
}
