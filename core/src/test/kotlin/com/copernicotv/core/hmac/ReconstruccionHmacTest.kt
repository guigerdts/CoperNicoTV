package com.copernicotv.core.hmac

import com.copernicotv.core.crypto.MotivoCifrado
import com.copernicotv.core.crypto.ResultadoDescifrado
import com.copernicotv.core.identity.SobreHmac
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReconstruccionHmacTest {
    private val mac = ByteArray(32) { it.toByte() }

    private fun versionado(
        generacion: Int,
        versionCanonica: Int,
    ): String = SobreHmac.construir(generacion, versionCanonica, mac)

    private fun legacy(generacion: Int): String = "hmac:v$generacion:${Base64.getEncoder().encodeToString(mac)}"

    @Test
    fun `sobre en la generacion objetivo ya esta actualizado`() {
        assertEquals(
            DecisionReconstruccion.YaActualizada,
            PlanReconstruccionHmac.decidir(versionado(2, 1), 2),
        )
    }

    @Test
    fun `sobre de otra generacion se reconstruye conservando su cvM`() {
        assertEquals(
            DecisionReconstruccion.Reconstruir(1),
            PlanReconstruccionHmac.decidir(versionado(1, 1), 2),
        )
        assertEquals(
            DecisionReconstruccion.Reconstruir(1),
            PlanReconstruccionHmac.decidir(versionado(5, 1), 2),
        )
    }

    @Test
    fun `cvM no soportado queda pendiente con su version de origen`() {
        val decision = PlanReconstruccionHmac.decidir(versionado(1, 2), 2)
        assertEquals(
            DecisionReconstruccion.Pendiente(VersionOrigenHmac.Cv(2), MotivoHmacPendiente.VERSION_CANONICA_NO_SOPORTADA),
            decision,
        )
    }

    @Test
    fun `sobre legacy queda pendiente con version desconocida`() {
        assertEquals(
            DecisionReconstruccion.Pendiente(
                VersionOrigenHmac.Desconocida,
                MotivoHmacPendiente.VERSION_CANONICA_DESCONOCIDA,
            ),
            PlanReconstruccionHmac.decidir(legacy(1), 2),
        )
    }

    @Test
    fun `sobre malformado queda pendiente`() {
        val esperado =
            DecisionReconstruccion.Pendiente(
                VersionOrigenHmac.Desconocida,
                MotivoHmacPendiente.HMAC_FORMATO_INVALIDO,
            )
        assertEquals(esperado, PlanReconstruccionHmac.decidir("", 2))
        assertEquals(esperado, PlanReconstruccionHmac.decidir("no-es-un-sobre", 2))
        assertEquals(esperado, PlanReconstruccionHmac.decidir("hmac:v1:cv1:no-base64!", 2))
        assertEquals(esperado, PlanReconstruccionHmac.decidir("hmac:v1:cv0:AAAA", 2))
    }

    @Test
    fun `columna sin valor no se reconstruye`() {
        assertEquals(DecisionReconstruccion.SinValor, PlanReconstruccionHmac.decidir(null, 2))
    }

    @Test
    fun `motivo por descifrado es determinista`() {
        assertNull(PlanReconstruccionHmac.motivoPorDescifrado(ResultadoDescifrado.Ok(ByteArray(4))))
        assertEquals(
            MotivoHmacPendiente.CIPHERTEXT_CORRUPTO,
            PlanReconstruccionHmac.motivoPorDescifrado(ResultadoDescifrado.Fallo(MotivoCifrado.FORMATO_INVALIDO)),
        )
        assertEquals(
            MotivoHmacPendiente.CLAVE_AES_NO_DISPONIBLE,
            PlanReconstruccionHmac.motivoPorDescifrado(ResultadoDescifrado.Fallo(MotivoCifrado.CLAVE_INVALIDA)),
        )
        assertEquals(
            MotivoHmacPendiente.CIPHERTEXT_CORRUPTO,
            PlanReconstruccionHmac.motivoPorDescifrado(ResultadoDescifrado.Fallo(MotivoCifrado.FALLO_AUTENTICACION)),
        )
    }

    @Test
    fun `dos sobres de la misma generacion y cvM son comparables`() {
        assertTrue(PlanReconstruccionHmac.puedenCompararse(versionado(1, 1), versionado(1, 1), 1))
    }

    @Test
    fun `generaciones distintas no son comparables`() {
        assertFalse(PlanReconstruccionHmac.puedenCompararse(versionado(1, 1), versionado(2, 1), 1))
        assertFalse(PlanReconstruccionHmac.puedenCompararse(versionado(1, 1), versionado(1, 1), 2))
    }

    @Test
    fun `cvM distintos no son comparables`() {
        assertFalse(PlanReconstruccionHmac.puedenCompararse(versionado(1, 1), versionado(1, 2), 1))
    }

    @Test
    fun `un sobre legacy o invalido no es comparable`() {
        assertFalse(PlanReconstruccionHmac.puedenCompararse(legacy(1), versionado(1, 1), 1))
        assertFalse(PlanReconstruccionHmac.puedenCompararse("roto", versionado(1, 1), 1))
    }

    @Test
    fun `version de origen se parsea y formatea`() {
        assertEquals("cv1", VersionOrigenHmac.Cv(1).texto)
        assertEquals("cv255", VersionOrigenHmac.Cv(255).texto)
        assertEquals("desconocida", VersionOrigenHmac.Desconocida.texto)
        assertEquals(VersionOrigenHmac.Cv(1), VersionOrigenHmac.parsear("cv1"))
        assertEquals(VersionOrigenHmac.Cv(255), VersionOrigenHmac.parsear("cv255"))
        assertEquals(VersionOrigenHmac.Desconocida, VersionOrigenHmac.parsear("desconocida"))
        assertNull(VersionOrigenHmac.parsear("cv0"))
        assertNull(VersionOrigenHmac.parsear("cv256"))
        assertNull(VersionOrigenHmac.parsear("v1"))
        assertNull(VersionOrigenHmac.parsear("cv01"))
    }

    @Test
    fun `la fila pendiente se identifica por entidad entidad id y columna`() {
        val pendiente =
            HmacPendiente(
                entidad = "Canal",
                entidadId = 7,
                columna = "url_stream_hmac",
                versionOrigen = VersionOrigenHmac.Desconocida,
                motivo = MotivoHmacPendiente.VERSION_CANONICA_DESCONOCIDA,
            )
        assertEquals(Triple("Canal", 7L, "url_stream_hmac"), pendiente.claveUnica)
        assertEquals(
            "version_canonica_desconocida",
            MotivoHmacPendiente.VERSION_CANONICA_DESCONOCIDA.codigo,
        )
        assertEquals(
            MotivoHmacPendiente.CANONICALIZACION_FALLIDA,
            MotivoHmacPendiente.desde("canonicalizacion_fallida"),
        )
        assertNull(MotivoHmacPendiente.desde("otro"))
    }

    @Test
    fun `las versiones canonicas soportadas incluyen cv1`() {
        assertTrue(1 in PlanReconstruccionHmac.VERSIONES_SOPORTADAS)
    }
}
