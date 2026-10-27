package com.copernicotv.core.modelo

import com.copernicotv.core.identity.ClaveCanal
import com.copernicotv.core.identity.EstadoCanal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InvariantesCanalTest {
    private val canonica = ClaveCanal.PREFIJO + "a".repeat(64)

    private fun fila(
        nombre: String? = "Canal",
        estado: EstadoCanal = EstadoCanal.A,
        derivable: Boolean = true,
        claveCanonica: String? = canonica,
        claveUnicidad: String? = canonica,
        tvgId: String? = null,
        urlCifrada: Boolean = true,
        urlHmac: Boolean = true,
    ) = FilaCanal(
        nombre = nombre,
        estado = estado,
        claveDerivable = derivable,
        claveCanonica = claveCanonica,
        claveUnicidad = claveUnicidad,
        tvgId = tvgId,
        urlStreamCifradaPresente = urlCifrada,
        urlStreamHmacPresente = urlHmac,
    )

    @Test
    fun `fila A derivable con nombre y url es valida`() {
        assertEquals(emptyList(), InvariantesCanal.validar(fila(), tvgIdUnicoEnFuente = false))
    }

    @Test
    fun `estado A derivable exige clave de unicidad`() {
        val violaciones = InvariantesCanal.validar(fila(claveUnicidad = null), tvgIdUnicoEnFuente = false)
        assertTrue(ViolacionCanal.ESTADO_A_DERIVABLE_SIN_CLAVE_UNICIDAD in violaciones)
    }

    @Test
    fun `estado B no admite clave de unicidad`() {
        val violaciones =
            InvariantesCanal.validar(fila(estado = EstadoCanal.B), tvgIdUnicoEnFuente = false)
        assertTrue(ViolacionCanal.CLAVE_UNICIDAD_EN_ESTADO_BC in violaciones)
    }

    @Test
    fun `clave derivable falsa no admite clave de unicidad`() {
        val violaciones =
            InvariantesCanal.validar(
                fila(derivable = false, claveUnicidad = canonica),
                tvgIdUnicoEnFuente = false,
            )
        assertTrue(ViolacionCanal.CLAVE_UNICIDAD_CON_DERIVABLE_FALSO in violaciones)
    }

    @Test
    fun `canal sin nombre no admite clave canonica`() {
        assertTrue(
            ViolacionCanal.CLAVE_CANONICA_SIN_NOMBRE in
                InvariantesCanal.validar(
                    fila(nombre = null, derivable = false, claveCanonica = canonica, claveUnicidad = null),
                    tvgIdUnicoEnFuente = false,
                ),
        )
    }

    @Test
    fun `nombre solo con espacios cuenta como ausente`() {
        assertTrue(
            ViolacionCanal.CLAVE_CANONICA_SIN_NOMBRE in
                InvariantesCanal.validar(
                    fila(nombre = "   ", derivable = false, claveCanonica = canonica, claveUnicidad = null),
                    tvgIdUnicoEnFuente = false,
                ),
        )
    }

    @Test
    fun `nombre presente exige clave canonica`() {
        assertTrue(
            ViolacionCanal.NOMBRE_SIN_CLAVE_CANONICA in
                InvariantesCanal.validar(
                    fila(claveCanonica = null, claveUnicidad = "tvg:v1:abc", tvgId = "abc"),
                    tvgIdUnicoEnFuente = true,
                ),
        )
    }

    @Test
    fun `clave canonica exige prefijo y digest en minusculas`() {
        assertTrue(
            ViolacionCanal.CLAVE_CANONICA_PREFIJO_INVALIDO in
                InvariantesCanal.validar(
                    fila(claveCanonica = "canon:v1:abc", claveUnicidad = "canon:v1:abc"),
                    tvgIdUnicoEnFuente = false,
                ),
        )
        assertTrue(
            ViolacionCanal.CLAVE_CANONICA_DIGEST_INVALIDO in
                InvariantesCanal.validar(
                    fila(claveCanonica = ClaveCanal.PREFIJO + "A".repeat(64)),
                    tvgIdUnicoEnFuente = false,
                ),
        )
    }

    @Test
    fun `tvg id con caracteres de control es invalido`() {
        assertTrue(
            ViolacionCanal.TVG_ID_INVALIDO in
                InvariantesCanal.validar(
                    fila(claveUnicidad = "tvg:v1:abc", tvgId = "a\u0001b"),
                    tvgIdUnicoEnFuente = true,
                ),
        )
    }

    @Test
    fun `tvg id unico usa la clave tvg`() {
        assertEquals(
            emptyList(),
            InvariantesCanal.validar(
                fila(claveUnicidad = "tvg:v1:canal-1", tvgId = "canal-1"),
                tvgIdUnicoEnFuente = true,
            ),
        )
    }

    @Test
    fun `tvg id unico con clave canonica es inconsistente`() {
        assertTrue(
            ViolacionCanal.CLAVE_UNICIDAD_TVG_INCONSISTENTE in
                InvariantesCanal.validar(
                    fila(claveUnicidad = canonica, tvgId = "canal-1"),
                    tvgIdUnicoEnFuente = true,
                ),
        )
    }

    @Test
    fun `tvg id no unico exige clave canonica`() {
        assertEquals(
            emptyList(),
            InvariantesCanal.validar(
                fila(claveUnicidad = canonica, tvgId = "canal-1"),
                tvgIdUnicoEnFuente = false,
            ),
        )
        assertTrue(
            ViolacionCanal.CLAVE_UNICIDAD_CANONICA_INCONSISTENTE in
                InvariantesCanal.validar(
                    fila(claveUnicidad = "tvg:v1:canal-1", tvgId = "canal-1"),
                    tvgIdUnicoEnFuente = false,
                ),
        )
    }

    @Test
    fun `url cifrada y hmac son obligatorios`() {
        val violaciones =
            InvariantesCanal.validar(
                fila(urlCifrada = false, urlHmac = false),
                tvgIdUnicoEnFuente = false,
            )
        assertTrue(ViolacionCanal.URL_STREAM_AUSENTE in violaciones)
        assertTrue(ViolacionCanal.URL_STREAM_HMAC_AUSENTE in violaciones)
    }
}
