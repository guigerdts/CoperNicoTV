package com.copernicotv.app.data

import com.copernicotv.app.crypto.ClavesApp
import com.copernicotv.core.crypto.CifradoAes
import com.copernicotv.core.crypto.HmacUrl
import com.copernicotv.core.crypto.ResultadoDescifrado
import com.copernicotv.core.hmac.DecisionReconstruccion
import com.copernicotv.core.hmac.HmacPendiente
import com.copernicotv.core.hmac.MotivoHmacPendiente
import com.copernicotv.core.hmac.PlanReconstruccionHmac
import com.copernicotv.core.hmac.VersionOrigenHmac
import com.copernicotv.core.identity.CampoUrl
import com.copernicotv.core.identity.CanonicalizadorUrl
import com.copernicotv.core.identity.ResultadoCanonizacion
import java.time.Instant
import javax.crypto.SecretKey
import javax.inject.Inject

/**
 * Decisión y reconstrucción del valor HMAC de una fila evaluada.
 *
 * Resuelve [procesar] por valor: consulta [PlanReconstruccionHmac], descifra
 * el valor original persistido, calcula el sobre nuevo para la generación
 * objetivo y registra en `HmacPendiente` lo que no se puede reconstruir.
 * No itera columnas ni toca la base: eso pertenece a la pasada del
 * reconstructor; este componente solo decide y produce sobres.
 */
internal class ReconstruccionValorHmac
    @Inject
    constructor(
        private val indice: IndiceHmacRepositorio,
        private val claves: ClavesApp,
    ) {
        /**
         * Decide y, si procede, reconstruye un valor HMAC.
         *
         * Devuelve el sobre nuevo cuando hay que escribirlo; no toca la base.
         */
        suspend fun procesar(
            valor: ValorHmac,
            claveObjetivo: SecretKey,
            generacionObjetivo: Int,
            ahora: Instant,
        ): ResultadoValor {
            val texto = valor.sobre.toString(Charsets.UTF_8)
            return when (val decision = PlanReconstruccionHmac.decidir(texto, generacionObjetivo)) {
                DecisionReconstruccion.SinValor -> ResultadoValor.SinValor
                DecisionReconstruccion.YaActualizada -> {
                    indice.eliminarPendiente(
                        valor.identidad.entidad,
                        valor.identidad.entidadId,
                        valor.identidad.columna,
                    )
                    ResultadoValor.YaActualizada
                }

                is DecisionReconstruccion.Pendiente -> {
                    registrarPendiente(valor.identidad, decision.versionOrigen, decision.motivo, ahora)
                    ResultadoValor.Pendiente
                }

                is DecisionReconstruccion.Reconstruir ->
                    reconstruir(valor, decision.versionCanonica, claveObjetivo, generacionObjetivo, ahora)
            }
        }

        private suspend fun reconstruir(
            valor: ValorHmac,
            versionCanonica: Int,
            claveObjetivo: SecretKey,
            generacionObjetivo: Int,
            ahora: Instant,
        ): ResultadoValor {
            val cifrado = valor.cifrado
            if (cifrado == null) {
                registrarPendiente(
                    valor.identidad,
                    VersionOrigenHmac.Cv(versionCanonica),
                    MotivoHmacPendiente.VALOR_ORIGINAL_AUSENTE,
                    ahora,
                )
                return ResultadoValor.Pendiente
            }
            val descifrado = CifradoAes.descifrar(claves.claveAes, valor.identidad.aad, cifrado)
            if (descifrado is ResultadoDescifrado.Fallo) {
                val motivo =
                    PlanReconstruccionHmac.motivoPorDescifrado(descifrado)
                        ?: MotivoHmacPendiente.CIPHERTEXT_CORRUPTO
                registrarPendiente(valor.identidad, VersionOrigenHmac.Cv(versionCanonica), motivo, ahora)
                return ResultadoValor.Pendiente
            }
            val original = String((descifrado as ResultadoDescifrado.Ok).bytes, Charsets.UTF_8)
            val canonica = CanonicalizadorUrl.canonizar(original, valor.identidad.campo)
            if (canonica !is ResultadoCanonizacion.Ok) {
                registrarPendiente(
                    valor.identidad,
                    VersionOrigenHmac.Cv(versionCanonica),
                    MotivoHmacPendiente.CANONICALIZACION_FALLIDA,
                    ahora,
                )
                return ResultadoValor.Pendiente
            }
            val sobre =
                HmacUrl
                    .calcularSobre(
                        clave = claveObjetivo,
                        generacion = generacionObjetivo,
                        campo = valor.identidad.campo,
                        clase = canonica.clase,
                        urlCanonica = canonica.urlCanonica,
                        versionCanonica = versionCanonica,
                    ).toByteArray(Charsets.UTF_8)
            indice.eliminarPendiente(
                valor.identidad.entidad,
                valor.identidad.entidadId,
                valor.identidad.columna,
            )
            return ResultadoValor.Reconstruida(sobre)
        }

        private suspend fun registrarPendiente(
            identidad: IdentidadHmac,
            versionOrigen: VersionOrigenHmac,
            motivo: MotivoHmacPendiente,
            ahora: Instant,
        ) {
            indice.registrarPendiente(
                HmacPendiente(
                    entidad = identidad.entidad,
                    entidadId = identidad.entidadId,
                    columna = identidad.columna,
                    versionOrigen = versionOrigen,
                    motivo = motivo,
                ),
                ahora,
            )
        }
    }

/** Identidad de una columna HMAC persistida. */
internal data class IdentidadHmac(
    val entidad: String,
    val entidadId: Long,
    val columna: String,
    val campo: CampoUrl,
    val aad: String,
)

/** Valor persistido que se está evaluando. */
internal class ValorHmac(
    val sobre: ByteArray,
    val cifrado: ByteArray?,
    val identidad: IdentidadHmac,
)

/** Resultado por valor evaluado. */
internal sealed interface ResultadoValor {
    /** Hay que escribir el sobre nuevo. */
    class Reconstruida(val sobre: ByteArray) : ResultadoValor

    /** Ya estaba en la generación objetivo. */
    data object YaActualizada : ResultadoValor

    /** Quedó registrado en `HmacPendiente`. */
    data object Pendiente : ResultadoValor

    /** No había valor persistido. */
    data object SinValor : ResultadoValor
}
