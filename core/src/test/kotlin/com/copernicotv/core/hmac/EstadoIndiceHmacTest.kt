package com.copernicotv.core.hmac

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EstadoIndiceHmacTest {
    private val ahora = Instant.parse("2026-10-28T12:00:00Z")

    private fun operativo(
        filasTotales: Long = 10,
        filasReconstruidas: Long = 0,
        filasPendientes: Long = 0,
    ): EstadoIndiceHmac =
        EstadoIndiceHmac(
            fase = FaseIndiceHmac.OPERATIVO,
            versionActiva = "hmac:v1",
            actualizadoEn = ahora,
            filasTotales = filasTotales,
            filasReconstruidas = filasReconstruidas,
            filasPendientes = filasPendientes,
        )

    private fun enCurso(): EstadoIndiceHmac {
        val pendiente =
            (MaquinaIndiceHmac.detectarPerdidaClave(operativo(), ahora) as ResultadoTransicion.Ok).estado
        return (MaquinaIndiceHmac.iniciarReconstruccion(pendiente, 2, ahora) as ResultadoTransicion.Ok).estado
    }

    private fun estadoDe(resultado: ResultadoTransicion): EstadoIndiceHmac {
        val ok = resultado as? ResultadoTransicion.Ok
        assertTrue(ok != null, "se esperaba una transición aceptada, fue $resultado")
        return ok.estado
    }

    private fun motivoDe(resultado: ResultadoTransicion): MotivoTransicion {
        val rechazo = resultado as? ResultadoTransicion.Rechazada
        assertTrue(rechazo != null, "se esperaba un rechazo, fue $resultado")
        return rechazo.motivo
    }

    @Test
    fun `estado operativo no tiene generacion objetivo`() {
        val estado = operativo()
        assertTrue(estado.esOperativo)
        assertEquals(1, estado.generacionActiva)
        assertNull(estado.generacionObjetivo)
        assertNull(estado.versionObjetivo)
    }

    @Test
    fun `en curso exige version objetivo`() {
        assertFailsWith<IllegalArgumentException> {
            EstadoIndiceHmac(
                fase = FaseIndiceHmac.RECUPERACION_EN_CURSO,
                versionActiva = "hmac:v1",
                actualizadoEn = ahora,
            )
        }
    }

    @Test
    fun `operativo no admite version objetivo`() {
        assertFailsWith<IllegalArgumentException> {
            EstadoIndiceHmac(
                fase = FaseIndiceHmac.OPERATIVO,
                versionActiva = "hmac:v1",
                versionObjetivo = "hmac:v2",
                actualizadoEn = ahora,
            )
        }
    }

    @Test
    fun `objetivo igual a la activa es invalido`() {
        assertFailsWith<IllegalArgumentException> {
            EstadoIndiceHmac(
                fase = FaseIndiceHmac.RECUPERACION_EN_CURSO,
                versionActiva = "hmac:v1",
                versionObjetivo = "hmac:v1",
                actualizadoEn = ahora,
            )
        }
    }

    @Test
    fun `version activa invalida es rechazada`() {
        assertFailsWith<IllegalArgumentException> {
            EstadoIndiceHmac(
                fase = FaseIndiceHmac.OPERATIVO,
                versionActiva = "hmac:v0",
                actualizadoEn = ahora,
            )
        }
    }

    @Test
    fun `conteos incoherentes son rechazados por el estado`() {
        assertFailsWith<IllegalArgumentException> { operativo(filasTotales = 2, filasReconstruidas = 2, filasPendientes = 1) }
        assertFailsWith<IllegalArgumentException> { operativo(filasTotales = 2, filasReconstruidas = -1) }
    }

    @Test
    fun `perdida de clave lleva de operativo a pendiente`() {
        val estado = estadoDe(MaquinaIndiceHmac.detectarPerdidaClave(operativo(), ahora))
        assertEquals(FaseIndiceHmac.RECUPERACION_PENDIENTE, estado.fase)
        assertEquals(ahora, estado.iniciadoEn)
        assertEquals(ahora, estado.actualizadoEn)
        assertNull(estado.versionObjetivo)
    }

    @Test
    fun `perdida de clave desde un estado no operativo se rechaza`() {
        val pendiente = estadoDe(MaquinaIndiceHmac.detectarPerdidaClave(operativo(), ahora))
        assertEquals(
            MotivoTransicion.TRANSICION_NO_PERMITIDA,
            motivoDe(MaquinaIndiceHmac.detectarPerdidaClave(pendiente, ahora)),
        )
    }

    @Test
    fun `iniciar reconstruccion abre la generacion objetivo y reinicia conteos`() {
        val pendiente = estadoDe(MaquinaIndiceHmac.detectarPerdidaClave(operativo(), ahora))
        val estado = estadoDe(MaquinaIndiceHmac.iniciarReconstruccion(pendiente, 2, ahora))
        assertEquals(FaseIndiceHmac.RECUPERACION_EN_CURSO, estado.fase)
        assertEquals("hmac:v2", estado.versionObjetivo)
        assertEquals(2, estado.generacionObjetivo)
        assertEquals(1, estado.generacionActiva)
        assertEquals(0, estado.filasReconstruidas)
        assertEquals(0, estado.filasPendientes)
    }

    @Test
    fun `iniciar reconstruccion con la generacion activa se rechaza`() {
        val pendiente = estadoDe(MaquinaIndiceHmac.detectarPerdidaClave(operativo(), ahora))
        assertEquals(
            MotivoTransicion.GENERACION_OBJETIVO_INVALIDA,
            motivoDe(MaquinaIndiceHmac.iniciarReconstruccion(pendiente, 1, ahora)),
        )
        assertEquals(
            MotivoTransicion.GENERACION_OBJETIVO_INVALIDA,
            motivoDe(MaquinaIndiceHmac.iniciarReconstruccion(pendiente, 0, ahora)),
        )
    }

    @Test
    fun `iniciar reconstruccion desde operativo se rechaza`() {
        assertEquals(
            MotivoTransicion.TRANSICION_NO_PERMITIDA,
            motivoDe(MaquinaIndiceHmac.iniciarReconstruccion(operativo(), 2, ahora)),
        )
    }

    @Test
    fun `avance parcial mantiene la reconstruccion en curso`() {
        val estado = estadoDe(MaquinaIndiceHmac.registrarAvance(enCurso(), 10, 4, 0, ahora))
        assertEquals(FaseIndiceHmac.RECUPERACION_EN_CURSO, estado.fase)
        assertEquals(4, estado.filasReconstruidas)
        assertEquals(0, estado.filasPendientes)
    }

    @Test
    fun `avance completo cierra la reconstruccion y activa la generacion objetivo`() {
        val estado = estadoDe(MaquinaIndiceHmac.registrarAvance(enCurso(), 10, 10, 0, ahora))
        assertEquals(FaseIndiceHmac.OPERATIVO, estado.fase)
        assertEquals("hmac:v2", estado.versionActiva)
        assertEquals(2, estado.generacionActiva)
        assertNull(estado.versionObjetivo)
        assertNull(estado.iniciadoEn)
        assertTrue(estado.esOperativo)
    }

    @Test
    fun `avance con filas pendientes bloquea parcialmente`() {
        val estado = estadoDe(MaquinaIndiceHmac.registrarAvance(enCurso(), 10, 7, 3, ahora))
        assertEquals(FaseIndiceHmac.BLOQUEADO_PARCIAL, estado.fase)
        assertEquals(3, estado.filasPendientes)
        assertEquals("hmac:v2", estado.versionObjetivo)
        assertEquals("hmac:v1", estado.versionActiva)
    }

    @Test
    fun `las filas reconstruidas no retroceden`() {
        val primero = estadoDe(MaquinaIndiceHmac.registrarAvance(enCurso(), 10, 5, 0, ahora))
        assertEquals(
            MotivoTransicion.CONTEOS_INCOHERENTES,
            motivoDe(MaquinaIndiceHmac.registrarAvance(primero, 10, 4, 0, ahora)),
        )
    }

    @Test
    fun `conteos incoherentes en el avance se rechazan`() {
        assertEquals(
            MotivoTransicion.CONTEOS_INCOHERENTES,
            motivoDe(MaquinaIndiceHmac.registrarAvance(enCurso(), 10, 8, 3, ahora)),
        )
        assertEquals(
            MotivoTransicion.CONTEOS_INCOHERENTES,
            motivoDe(MaquinaIndiceHmac.registrarAvance(enCurso(), -1, 0, 0, ahora)),
        )
    }

    @Test
    fun `avance desde operativo se rechaza`() {
        assertEquals(
            MotivoTransicion.TRANSICION_NO_PERMITIDA,
            motivoDe(MaquinaIndiceHmac.registrarAvance(operativo(), 10, 10, 0, ahora)),
        )
    }

    @Test
    fun `resolver pendientes exige que no queden filas pendientes`() {
        val bloqueado = estadoDe(MaquinaIndiceHmac.registrarAvance(enCurso(), 10, 7, 3, ahora))
        assertEquals(
            MotivoTransicion.FILAS_PENDIENTES_NO_RESUELTAS,
            motivoDe(MaquinaIndiceHmac.resolverPendientes(bloqueado, 7, 7, 3, ahora)),
        )
    }

    @Test
    fun `resolver pendientes completa la reconstruccion`() {
        val bloqueado = estadoDe(MaquinaIndiceHmac.registrarAvance(enCurso(), 10, 7, 3, ahora))
        val estado = estadoDe(MaquinaIndiceHmac.resolverPendientes(bloqueado, 10, 10, 0, ahora))
        assertEquals(FaseIndiceHmac.OPERATIVO, estado.fase)
        assertEquals("hmac:v2", estado.versionActiva)
        assertEquals(0, estado.filasPendientes)
    }

    @Test
    fun `resolver pendientes con filas restantes reanuda la reconstruccion`() {
        val bloqueado = estadoDe(MaquinaIndiceHmac.registrarAvance(enCurso(), 10, 7, 3, ahora))
        val estado = estadoDe(MaquinaIndiceHmac.resolverPendientes(bloqueado, 10, 7, 0, ahora))
        assertEquals(FaseIndiceHmac.RECUPERACION_EN_CURSO, estado.fase)
        assertEquals("hmac:v2", estado.versionObjetivo)
    }

    @Test
    fun `resolver pendientes desde un estado distinto se rechaza`() {
        assertEquals(
            MotivoTransicion.TRANSICION_NO_PERMITIDA,
            motivoDe(MaquinaIndiceHmac.resolverPendientes(enCurso(), 10, 10, 0, ahora)),
        )
    }

    @Test
    fun `clave original recuperada exige que no haya filas fuera de la generacion activa`() {
        val pendiente = estadoDe(MaquinaIndiceHmac.detectarPerdidaClave(operativo(), ahora))
        assertEquals(
            MotivoTransicion.RECONSTRUCCION_INCOMPLETA,
            motivoDe(MaquinaIndiceHmac.claveOriginalRecuperada(pendiente, 1, ahora)),
        )
    }

    @Test
    fun `clave original recuperada vuelve a operativo sin cambiar la generacion`() {
        val pendiente = estadoDe(MaquinaIndiceHmac.detectarPerdidaClave(operativo(), ahora))
        val estado = estadoDe(MaquinaIndiceHmac.claveOriginalRecuperada(pendiente, 0, ahora))
        assertEquals(FaseIndiceHmac.OPERATIVO, estado.fase)
        assertEquals("hmac:v1", estado.versionActiva)
        assertNull(estado.versionObjetivo)
        assertNull(estado.iniciadoEn)
    }

    @Test
    fun `clave original recuperada desde en curso se rechaza`() {
        assertEquals(
            MotivoTransicion.TRANSICION_NO_PERMITIDA,
            motivoDe(MaquinaIndiceHmac.claveOriginalRecuperada(enCurso(), 0, ahora)),
        )
    }

    @Test
    fun `el avance es idempotente con los mismos datos`() {
        val base = enCurso()
        val primero = MaquinaIndiceHmac.registrarAvance(base, 10, 4, 0, ahora)
        val segundo = MaquinaIndiceHmac.registrarAvance(base, 10, 4, 0, ahora)
        assertEquals(primero, segundo)
    }

    @Test
    fun `bloqueo de igualdad solo desaparece en operativo`() {
        assertNull(PlanReconstruccionHmac.bloqueoIgualdad(operativo()))
        val pendiente = estadoDe(MaquinaIndiceHmac.detectarPerdidaClave(operativo(), ahora))
        assertEquals(RechazoIndiceHmac(), PlanReconstruccionHmac.bloqueoIgualdad(pendiente))
        assertEquals(RechazoIndiceHmac(), PlanReconstruccionHmac.bloqueoIgualdad(enCurso()))
        val bloqueado = estadoDe(MaquinaIndiceHmac.registrarAvance(enCurso(), 10, 7, 3, ahora))
        assertEquals(RechazoIndiceHmac(), PlanReconstruccionHmac.bloqueoIgualdad(bloqueado))
        assertEquals("indice_hmac_no_operativo", RechazoIndiceHmac().codigo)
    }

    @Test
    fun `generacion hmac formatea y parsea`() {
        assertEquals("hmac:v1", GeneracionHmac.formatear(1))
        assertEquals("hmac:v42", GeneracionHmac.formatear(42))
        assertEquals(1, GeneracionHmac.parsear("hmac:v1"))
        assertEquals(42, GeneracionHmac.parsear("hmac:v42"))
        assertNull(GeneracionHmac.parsear("hmac:v0"))
        assertNull(GeneracionHmac.parsear("v1"))
        assertNull(GeneracionHmac.parsear("hmac:v1:cv1:x"))
        assertFailsWith<IllegalArgumentException> { GeneracionHmac.formatear(0) }
    }

    @Test
    fun `fase se resuelve desde su codigo persistido`() {
        assertEquals(FaseIndiceHmac.OPERATIVO, FaseIndiceHmac.desde("operativo"))
        assertEquals(FaseIndiceHmac.BLOQUEADO_PARCIAL, FaseIndiceHmac.desde("bloqueado_parcial"))
        assertNull(FaseIndiceHmac.desde("desconocido"))
    }
}
