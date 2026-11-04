package com.copernicotv.core.xmltv

import java.security.MessageDigest

/**
 * Fixtures XMLTV de S24 y sus hashes registrados.
 *
 * F24a es el XMLTV válido de la medición P1: 100 canales y 10 000 programas.
 * F24b y F24c materializan los casos funcionales de XML malformado e incompleto
 * que ya definía el parser. Los tres archivos son deterministas y se versionan
 * en `core/src/test/resources/fixtures/`.
 */
object FixtureP1Xmltv {
    const val RUTA: String = "/fixtures/F24a.xml"
    const val RUTA_F24B: String = "/fixtures/F24b.xml"
    const val RUTA_F24C: String = "/fixtures/F24c.xml"

    const val CANALES: Int = 100
    const val PROGRAMAS: Int = 10_000

    const val SHA256: String = "20c0417f83b5f3534e3229b2c423ad6bdd3f54bc1c92e7776efbb655c0f74bf1"
    const val SHA256_F24B: String = "f2f60ce1dfff28574bbc801c160c3357e59d7915d58321295a281df2e8db89ca"
    const val SHA256_F24C: String = "e19031d8cd8be09f2b86add11fc1aaa15edfd79c81edbd020e1fe9230a7e2053"

    fun bytes(): ByteArray = bytes(RUTA)

    fun bytes(ruta: String): ByteArray =
        requireNotNull(FixtureP1Xmltv::class.java.getResourceAsStream(ruta)) {
            "no se encontró el recurso $ruta"
        }.use { it.readBytes() }

    fun sha256(bytes: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }
}
