package com.copernicotv.core.ota

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Autenticidad del manifiesto (S3).
 *
 * La prueba firma los bytes exactos del documento con una clave ECDSA P-256 y
 * comprueba que cualquier alteración —del documento, de la firma o de la clave—
 * invalida la verificación.
 */
class FirmaManifiestoTest {
    @Test
    fun `una firma de la clave correcta se acepta`() {
        val par = parDeClaves()
        val documento = DOCUMENTO.toByteArray()

        assertTrue(FirmaManifiesto.verificar(documento, firmar(par, documento), publica(par)))
    }

    @Test
    fun `alterar un byte del manifiesto invalida la firma`() {
        val par = parDeClaves()
        val documento = DOCUMENTO.toByteArray()
        val firma = firmar(par, documento)

        assertFalse(FirmaManifiesto.verificar(DOCUMENTO.toByteArray() + ' '.code.toByte(), firma, publica(par)))
    }

    @Test
    fun `una clave publica distinta no valida la firma`() {
        val firmante = parDeClaves()
        val otro = parDeClaves()
        val documento = DOCUMENTO.toByteArray()

        assertFalse(FirmaManifiesto.verificar(documento, firmar(firmante, documento), publica(otro)))
    }

    @Test
    fun `una firma corrupta o vacia no valida`() {
        val par = parDeClaves()
        val documento = DOCUMENTO.toByteArray()
        val firma = firmar(par, documento)

        assertFalse(FirmaManifiesto.verificar(documento, ByteArray(0), publica(par)))
        assertFalse(FirmaManifiesto.verificar(documento, firma.copyOf().also { it[0] = (it[0].toInt() xor 0xFF).toByte() }, publica(par)))
    }

    @Test
    fun `una clave publica mal codificada no lanza excepciones`() {
        val documento = DOCUMENTO.toByteArray()

        assertFalse(FirmaManifiesto.verificar(documento, byteArrayOf(1, 2, 3), "no-es-base64"))
        assertFalse(FirmaManifiesto.verificar(documento, byteArrayOf(1, 2, 3), Base64.getEncoder().encodeToString(byteArrayOf(9, 9, 9))))
        assertFalse(FirmaManifiesto.verificar(documento, byteArrayOf(1, 2, 3), ""))
    }

    @Test
    fun `un documento vacio no se verifica`() {
        val par = parDeClaves()

        assertFalse(FirmaManifiesto.verificar(ByteArray(0), firmar(par, ByteArray(0)), publica(par)))
    }

    @Test
    fun `una firma truncada no valida`() {
        val par = parDeClaves()
        val documento = DOCUMENTO.toByteArray()
        val firma = firmar(par, documento)

        listOf(10, 30, firma.size - 1).forEach { largo ->
            assertFalse(
                FirmaManifiesto.verificar(documento, firma.copyOfRange(0, largo), publica(par)),
                "una firma de $largo bytes no debe validar",
            )
        }
    }

    @Test
    fun `la firma de otro manifiesto no valida`() {
        val par = parDeClaves()
        val original = DOCUMENTO.toByteArray()
        val alterado = """{"schema":1,"packageName":"com.copernicotv.app","versionCode":3}""".toByteArray()

        assertFalse(FirmaManifiesto.verificar(alterado, firmar(par, original), publica(par)))
    }

    private fun parDeClaves(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()

    private fun firmar(
        par: KeyPair,
        documento: ByteArray,
    ): ByteArray =
        Signature.getInstance("SHA256withECDSA").run {
            initSign(par.private)
            update(documento)
            sign()
        }

    private fun publica(par: KeyPair): String = Base64.getEncoder().encodeToString(par.public.encoded)

    private companion object {
        const val DOCUMENTO = """{"schema":1,"packageName":"com.copernicotv.app","versionCode":2}"""
    }
}
