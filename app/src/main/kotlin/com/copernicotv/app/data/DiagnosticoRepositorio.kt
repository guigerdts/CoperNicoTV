package com.copernicotv.app.data

import com.copernicotv.app.data.db.dao.DiagnosticoDao
import com.copernicotv.app.data.db.entities.EventoDiagnosticoEntity
import com.copernicotv.app.data.db.entities.SesionDiagnosticoEntity
import com.copernicotv.app.player.EstadoPistas
import com.copernicotv.app.player.EstadoReproductor
import com.copernicotv.app.player.EstadoSesion
import com.copernicotv.app.player.PistaDisponible
import com.copernicotv.app.player.sanearUrl
import com.copernicotv.core.player.EstadoImagen
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Escritor del diagnóstico persistente de reproducción (E7-T10, S6a).
 *
 * Recibe el ciclo de vida de la sesión desde el ViewModel y lo vuelca a Room
 * de forma asíncrona: una orden en cola por escritura, procesada en orden por
 * un único consumidor en `Dispatchers.IO`. El hilo principal nunca toca la
 * base de datos y no se persiste cada frame ni cada cambio de fase: solo la
 * apertura, los eventos significativos (error, recuperación de red, recuperación
 * de imagen) y el cierre. Eventos consecutivos idénticos se agrupan.
 *
 * Una fila abierta se cierra una sola vez: toda escritura posterior sobre una
 * sesión ya cerrada se ignora, así que un callback tardío no puede corromper
 * el diagnóstico de otra sesión (E6-T4). La retención (S6a §8) elimina sesiones
 * cerradas anteriores al corte configurable y nunca toca sesiones activas ni
 * recientes.
 *
 * Nunca persiste la URL cruda ni credenciales: la columna guarda la versión
 * saneada de [sanearUrl] (S57a).
 */
@Singleton
class DiagnosticoRepositorio
    @Inject
    constructor(
        private val dao: DiagnosticoDao,
    ) {
        /** Días que se conservan las sesiones cerradas; punto de configuración de la retención. */
        internal var diasRetencion: Long = DIAS_RETENCION_DEFECTO

        private val alcance = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val ordenes = Channel<suspend () -> Unit>(Channel.UNLIMITED)

        /** Fila de la sesión en curso; solo la toca el consumidor de [ordenes]. */
        private var fila: SesionDiagnosticoEntity? = null
        private var estadoPrevio: EstadoSesion? = null
        private var ultimoEvento: Pair<String?, String?>? = null

        init {
            alcance.launch {
                for (orden in ordenes) orden()
            }
        }

        /** Registra la apertura de una sesión de reproducción. */
        fun abrir(
            canalId: Long?,
            canalNombre: String?,
            urlCruda: String?,
        ) {
            val filaNueva =
                SesionDiagnosticoEntity(
                    canalId = canalId,
                    canalNombre = canalNombre?.take(LONGITUD_TEXTO),
                    urlSaneada = sanearUrl(urlCruda),
                    inicio = Instant.now(),
                )
            encolar {
                fila = filaNueva
                estadoPrevio = null
                ultimoEvento = null
                fila = filaNueva.copy(id = dao.insertarSesion(filaNueva))
            }
        }

        /**
         * Incorpora a la fila vigente la última observación de la sesión y
         * registra los eventos de recuperación y error que el cambio implica.
         */
        fun observar(
            estado: EstadoSesion,
            pistas: EstadoPistas,
        ) {
            encolar {
                val vigente = fila ?: return@encolar
                if (vigente.fin != null) return@encolar
                aplicarObservacion(vigente, estado, pistas)
            }
        }

        /** Cierra la sesión vigente una sola vez y aplica la retención. */
        fun cerrar() {
            encolar {
                val vigente = fila ?: return@encolar
                if (vigente.fin != null) return@encolar
                val resultado =
                    when (estadoPrevio?.estado) {
                        EstadoReproductor.ERROR -> RESULTADO_ERROR
                        EstadoReproductor.TERMINADO -> RESULTADO_TERMINADA
                        else -> RESULTADO_CERRADA
                    }
                val cerrada = vigente.copy(fin = Instant.now(), resultado = resultado)
                fila = cerrada
                dao.actualizarSesion(cerrada)
                dao.insertarEvento(
                    EventoDiagnosticoEntity(
                        sesionId = cerrada.id,
                        momento = Instant.now(),
                        tipo = TIPO_CIERRE,
                        detalle = resultado,
                        codigo = estadoPrevio?.estado?.name,
                    ),
                )
                val corte = Instant.now().minus(Duration.ofDays(diasRetencion))
                dao.eliminarSesionesCerradasAnterioresA(corte)
            }
        }

        /** Punto de espera para las pruebas: agota toda la cola pendiente. */
        internal suspend fun drenar() {
            val terminado = CompletableDeferred<Unit>()
            encolar { terminado.complete(Unit) }
            terminado.await()
        }

        private fun encolar(orden: suspend () -> Unit) {
            ordenes.trySend(orden)
        }

        private suspend fun aplicarObservacion(
            vigente: SesionDiagnosticoEntity,
            estado: EstadoSesion,
            pistas: EstadoPistas,
        ) {
            var nueva = vigente.actualizadaCon(estado, pistas)
            val previo = estadoPrevio
            if (previo != null) {
                if (previo.estado in ESTADOS_ESPERA_RED && estado.estado == EstadoReproductor.REPRODUCIENDO) {
                    nueva = nueva.copy(recuperacionesRed = nueva.recuperacionesRed + 1)
                    registrarEvento(
                        nueva.id,
                        TIPO_RECUPERACION_RED,
                        detalle = "reintentos=${estado.reintento}/${estado.reintentosMaximos}",
                        codigo = previo.estado.name,
                    )
                }
                val imagenPrevio = previo.estadoImagen
                if (
                    imagenPrevio != null &&
                    imagenPrevio in ESTADOS_RECUPERACION_IMAGEN &&
                    estado.estadoImagen == EstadoImagen.IMAGEN_RECUPERADA
                ) {
                    nueva = nueva.copy(recuperacionesImagen = nueva.recuperacionesImagen + 1)
                    registrarEvento(
                        nueva.id,
                        TIPO_RECUPERACION_IMAGEN,
                        detalle = "escalera=${nueva.recuperacionesImagen}",
                        codigo = imagenPrevio.name,
                    )
                }
            }
            if (estado.estado == EstadoReproductor.ERROR) {
                val clave = estado.codigoError to estado.clasificacionTecnica
                if (clave != ultimoEvento && estado.codigoError != null) {
                    registrarEvento(
                        nueva.id,
                        TIPO_ERROR,
                        detalle =
                            listOfNotNull(
                                estado.clasificacionTecnica,
                                estado.estadoHttp?.let { "http=$it" },
                                estado.clasesCausa.lastOrNull(),
                            ).joinToString("; "),
                        codigo = estado.codigoError,
                    )
                    ultimoEvento = clave
                }
            }
            estadoPrevio = estado
            fila = nueva
            dao.actualizarSesion(nueva)
        }

        private suspend fun registrarEvento(
            sesionId: Long,
            tipo: String,
            detalle: String,
            codigo: String,
        ) {
            dao.insertarEvento(
                EventoDiagnosticoEntity(
                    sesionId = sesionId,
                    momento = Instant.now(),
                    tipo = tipo,
                    detalle = detalle.take(LONGITUD_DETALLE),
                    codigo = codigo,
                ),
            )
        }

        private fun SesionDiagnosticoEntity.actualizadaCon(
            estado: EstadoSesion,
            pistas: EstadoPistas,
        ): SesionDiagnosticoEntity =
            conProgreso(estado)
                .conFallo(estado)
                .conTransporte(estado)
                .conRutaRedirecciones(estado)
                .conFormato(estado)
                .conPistasSeleccionadas(estado)
                .conEvidenciaAudiovisual(estado, pistas)

        /** Fase y progreso de reproducción; la evidencia no se degrada nunca. */
        private fun SesionDiagnosticoEntity.conProgreso(estado: EstadoSesion): SesionDiagnosticoEntity =
            copy(
                faseFinal = estado.estado.name,
                posicionMs = estado.posicionMs.positivo(posicionMs),
                duracionMs = estado.duracionMs.positivo(duracionMs),
                imagenFinal = estado.estadoImagen?.name ?: imagenFinal,
                condicion = estado.condicion?.name ?: condicion,
            )

        /** Identificación del fallo y presupuesto de reintentos. */
        private fun SesionDiagnosticoEntity.conFallo(estado: EstadoSesion): SesionDiagnosticoEntity =
            copy(
                ultimoError = estado.codigoError ?: ultimoError,
                codigoMotor = estado.codigoMotor ?: codigoMotor,
                claseExcepcion = estado.clasesCausa.lastOrNull() ?: claseExcepcion,
                clasificacionTecnica = estado.clasificacionTecnica ?: clasificacionTecnica,
                reintento = estado.reintento.positivo(reintento),
                reintentosMaximos = estado.reintentosMaximos.positivo(reintentosMaximos),
            )

        /** Evidencia HTTP y temporización de la respuesta fallida. */
        private fun SesionDiagnosticoEntity.conTransporte(estado: EstadoSesion): SesionDiagnosticoEntity =
            copy(
                estadoHttp = estado.estadoHttp ?: estadoHttp,
                codigoHttpInicial = estado.codigoHttpInicial ?: codigoHttpInicial,
                tipoMime = estado.tipoMime ?: tipoMime,
                bytesRespuesta = estado.bytesRespuesta.positivo(bytesRespuesta),
                msHastaFallo = estado.msHastaFallo.positivo(msHastaFallo),
                intentosFallo = estado.intentosFallo.positivo(intentosFallo),
                redirecciones = estado.redirecciones ?: redirecciones,
            )

        /** Ruta observada del stream (esquemas y ciclo de redirecciones). */
        private fun SesionDiagnosticoEntity.conRutaRedirecciones(estado: EstadoSesion): SesionDiagnosticoEntity =
            copy(
                esquemaInicial = estado.esquemaInicial ?: esquemaInicial,
                esquemaFinal = estado.esquemaFinal ?: esquemaFinal,
                cicloRedirecciones = estado.cicloRedirecciones.siVerdad(cicloRedirecciones),
                limiteRedirecciones = estado.limiteRedirecciones.siVerdad(limiteRedirecciones),
                fuenteMedia = estado.fuenteMedia ?: fuenteMedia,
            )

        /** Diagnóstico de formato del contenedor declarado y observado. */
        private fun SesionDiagnosticoEntity.conFormato(estado: EstadoSesion): SesionDiagnosticoEntity =
            copy(
                formatoEsperado = estado.formatoEsperado ?: formatoEsperado,
                formatoObservado = estado.formatoObservado ?: formatoObservado,
                formatoObservadoMime = estado.formatoObservadoMime ?: formatoObservadoMime,
                reSeleccionMedia = estado.reSeleccionMedia ?: reSeleccionMedia,
            )

        /** Pistas seleccionadas de video y audio con sus decodificadores. */
        private fun SesionDiagnosticoEntity.conPistasSeleccionadas(estado: EstadoSesion): SesionDiagnosticoEntity =
            copy(
                pistaVideoMime = estado.pistaVideoMime ?: pistaVideoMime,
                pistaVideoCodec = estado.pistaVideoCodec ?: pistaVideoCodec,
                pistaVideoResolucion = estado.pistaVideoResolucion ?: pistaVideoResolucion,
                pistaVideoFrameRate = estado.pistaVideoFrameRate ?: pistaVideoFrameRate,
                pistaVideoBitrate = estado.pistaVideoBitrate ?: pistaVideoBitrate,
                pistaAudioMime = estado.pistaAudioMime ?: pistaAudioMime,
                pistaAudioCodec = estado.pistaAudioCodec ?: pistaAudioCodec,
                pistaAudioBitrate = estado.pistaAudioBitrate ?: pistaAudioBitrate,
                decoderVideo = estado.decoderVideo ?: decoderVideo,
                decoderAudio = estado.decoderAudio ?: decoderAudio,
            )

        /** Evidencia audiovisual: renderers, frames y pistas detectadas. */
        private fun SesionDiagnosticoEntity.conEvidenciaAudiovisual(
            estado: EstadoSesion,
            pistas: EstadoPistas,
        ): SesionDiagnosticoEntity =
            copy(
                msHastaPrimerFrameVideo = estado.msHastaPrimerFrameVideo ?: msHastaPrimerFrameVideo,
                framesPerdidosVideo = estado.framesPerdidosVideo ?: framesPerdidosVideo,
                framesRenderidosVideo = estado.framesRenderidosVideo ?: framesRenderidosVideo,
                framesOmitidosVideo = estado.framesOmitidosVideo ?: framesOmitidosVideo,
                videoRendererActivo = estado.videoRendererActivo ?: videoRendererActivo,
                audioRendererActivo = estado.audioRendererActivo ?: audioRendererActivo,
                pistaVideoDetectada = estado.pistaVideoDetectada ?: pistaVideoDetectada,
                pistaAudioDetectada = estado.pistaAudioDetectada ?: pistaAudioDetectada,
                pistaVideoDetectadaMime =
                    estado.pistaVideoDetectadaMime ?: pistaVideoDetectadaMime,
                pistaVideoDetectadaCodec =
                    estado.pistaVideoDetectadaCodec ?: pistaVideoDetectadaCodec,
                pistaVideoSoportada = estado.pistaVideoSoportada ?: pistaVideoSoportada,
                pistasAudioTexto = resumenPistas(pistas.audio, desactivados = false) ?: pistasAudioTexto,
                pistasSubtituloTexto =
                    resumenPistas(pistas.subtitulos, pistas.subtitulosDesactivados) ?: pistasSubtituloTexto,
            ).conVeredictoDecoders(estado)

        /** E7-T11: el veredicto del inventario viaja como texto del enum; nulo sin pista. */
        private fun SesionDiagnosticoEntity.conVeredictoDecoders(estado: EstadoSesion): SesionDiagnosticoEntity =
            copy(
                decoderVideoSeleccionado =
                    estado.decoderVideoSeleccionado ?: decoderVideoSeleccionado,
                tipoDecoderVideo = estado.tipoDecoderVideo?.name ?: tipoDecoderVideo,
                motivoSoporteVideo = estado.motivoSoporteVideo?.name ?: motivoSoporteVideo,
                perfilNivelVideo = estado.perfilNivelVideo ?: perfilNivelVideo,
            )

        private fun Long?.positivo(anterior: Long?): Long? = this?.takeIf { it > 0L } ?: anterior

        private fun Int?.positivo(anterior: Int?): Int? = this?.takeIf { it > 0 } ?: anterior

        private fun Boolean?.siVerdad(anterior: Boolean?): Boolean? = this?.takeIf { it } ?: anterior

        /** Resumen acotado de pistas detectadas; la activa se marca. */
        private fun resumenPistas(
            pistas: List<PistaDisponible>,
            desactivados: Boolean,
        ): String? =
            when {
                desactivados -> "desactivados"
                pistas.isEmpty() -> null
                else ->
                    pistas
                        .mapIndexed { indice, pista ->
                            val nombre = pista.idioma ?: pista.etiqueta ?: "Pista ${indice + 1}"
                            if (pista.activa) "$nombre (activa)" else nombre
                        }.joinToString(", ")
                        .take(LONGITUD_TEXTO)
            }

        private companion object {
            const val DIAS_RETENCION_DEFECTO = 30L
            const val LONGITUD_TEXTO = 200
            const val LONGITUD_DETALLE = 300

            const val RESULTADO_TERMINADA = "TERMINADA"
            const val RESULTADO_ERROR = "ERROR"
            const val RESULTADO_CERRADA = "CERRADA"

            const val TIPO_ERROR = "ERROR"
            const val TIPO_RECUPERACION_RED = "RECUPERACION_RED"
            const val TIPO_RECUPERACION_IMAGEN = "RECUPERACION_IMAGEN"
            const val TIPO_CIERRE = "CIERRE"

            val ESTADOS_ESPERA_RED =
                setOf(EstadoReproductor.REINTENTANDO, EstadoReproductor.ESPERANDO_CONEXION)
            val ESTADOS_RECUPERACION_IMAGEN =
                setOf(
                    EstadoImagen.RECUPERANDO_IMAGEN,
                    EstadoImagen.RESTAURANDO_SUPERFICIE,
                    EstadoImagen.REINICIALIZANDO_RENDERER,
                )
        }
    }
