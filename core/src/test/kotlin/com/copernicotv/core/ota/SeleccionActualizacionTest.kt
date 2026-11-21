package com.copernicotv.core.ota

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Reglas de elección de actualización (S1).
 *
 * La comparación es por `versionCode`, nunca por `versionName`: el primero es
 * el contrato de Android y el único que el instalador valida.
 */
class SeleccionActualizacionTest {
    @Test
    fun `sin manifiestos no hay actualizacion`() {
        assertEquals(
            DecisionActualizacion.AlDia,
            elegirActualizacion(INSTALADA, CanalActualizacion.ESTABLE, SDK, emptyList()),
        )
    }

    @Test
    fun `un versionCode mayor se ofrece`() {
        val candidato = manifiesto(versionCode = 3)

        val decision = elegirActualizacion(INSTALADA, CanalActualizacion.ESTABLE, SDK, listOf(candidato))

        assertEquals(DecisionActualizacion.Disponible(candidato), decision)
    }

    @Test
    fun `el mismo versionCode no se ofrece, aunque cambie el versionName`() {
        val mismo = manifiesto(versionCode = 2, versionName = "0.2.0-hotfix")

        assertEquals(
            DecisionActualizacion.AlDia,
            elegirActualizacion(INSTALADA, CanalActualizacion.ESTABLE, SDK, listOf(mismo)),
        )
    }

    @Test
    fun `una version menor no se ofrece`() {
        assertEquals(
            DecisionActualizacion.AlDia,
            elegirActualizacion(INSTALADA, CanalActualizacion.ESTABLE, SDK, listOf(manifiesto(versionCode = 1))),
        )
    }

    @Test
    fun `el canal estable ignora las publicaciones beta`() {
        val beta = manifiesto(versionCode = 9, canal = CanalActualizacion.BETA)

        assertEquals(
            DecisionActualizacion.AlDia,
            elegirActualizacion(INSTALADA, CanalActualizacion.ESTABLE, SDK, listOf(beta)),
        )
    }

    @Test
    fun `el canal beta acepta estable y beta y elige el mayor versionCode`() {
        val estable = manifiesto(versionCode = 3, canal = CanalActualizacion.ESTABLE)
        val beta = manifiesto(versionCode = 4, canal = CanalActualizacion.BETA)

        val decision = elegirActualizacion(INSTALADA, CanalActualizacion.BETA, SDK, listOf(estable, beta))

        assertEquals(DecisionActualizacion.Disponible(beta), decision)
    }

    @Test
    fun `el canal beta prefiere la estable si empatan en versionCode`() {
        val estable = manifiesto(versionCode = 4, canal = CanalActualizacion.ESTABLE)
        val beta = manifiesto(versionCode = 4, canal = CanalActualizacion.BETA)

        assertEquals(
            DecisionActualizacion.Disponible(estable),
            elegirActualizacion(INSTALADA, CanalActualizacion.BETA, SDK, listOf(beta, estable)),
        )
    }

    @Test
    fun `una version mas nueva incompatible se reporta, no se descarta`() {
        val futuro = manifiesto(versionCode = 5, minSdk = SDK + 1)

        val decision = elegirActualizacion(INSTALADA, CanalActualizacion.ESTABLE, SDK, listOf(futuro))

        assertEquals(DecisionActualizacion.Incompatible(MotivoIncompatibilidad.ANDROID_DEMASIADO_ANTIGUO, futuro), decision)
    }

    @Test
    fun `la obligatoriedad se hereda del manifiesto`() {
        val decision =
            elegirActualizacion(
                INSTALADA,
                CanalActualizacion.ESTABLE,
                SDK,
                listOf(manifiesto(versionCode = 3, obligatoria = true)),
            )

        val disponible = assertIs<DecisionActualizacion.Disponible>(decision)
        assertEquals(true, disponible.obligatoria)
    }

    private fun manifiesto(
        versionCode: Long,
        versionName: String = "0.3.0",
        canal: CanalActualizacion = CanalActualizacion.ESTABLE,
        minSdk: Int = 26,
        obligatoria: Boolean = false,
    ) = ManifiestoActualizacion(
        esquema = 1,
        paquete = "com.copernicotv.app",
        versionCode = versionCode,
        versionName = versionName,
        canal = canal,
        minSdk = minSdk,
        publicadoEn = Instant.parse("2026-11-20T18:00:00Z"),
        tamanoBytes = 1024,
        sha256 = "0".repeat(64),
        urlDescarga = "https://github.com/a/b/releases/download/v0.3.0/app.apk",
        urlRelease = "https://github.com/a/b/releases/tag/v0.3.0",
        notas = "Notas",
        obligatoria = obligatoria,
        huellaFirmaApk = "1".repeat(64),
    )

    private companion object {
        val INSTALADA = VersionInstalada(versionCode = 2, versionName = "0.1.0")
        const val SDK = 34
    }
}
