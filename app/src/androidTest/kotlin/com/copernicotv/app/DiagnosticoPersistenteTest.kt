package com.copernicotv.app

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.copernicotv.app.data.DiagnosticoRepositorio
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.app.data.db.Migraciones
import com.copernicotv.app.data.db.dao.DiagnosticoDao
import com.copernicotv.app.data.db.entities.EventoDiagnosticoEntity
import com.copernicotv.app.data.db.entities.SesionDiagnosticoEntity
import com.copernicotv.app.player.EstadoPistas
import com.copernicotv.app.player.EstadoReproductor
import com.copernicotv.app.player.EstadoSesion
import com.copernicotv.core.player.EstadoImagen
import com.copernicotv.core.player.MotivoSoporteVideo
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Duration
import java.time.Instant

/**
 * Diagnóstico persistente de reproducción (E7-T10, S6a).
 *
 * Las pruebas ejercitan el [DiagnosticoRepositorio] real contra una Room en
 * memoria: apertura, eventos, cierre, retención, saneamiento y guardas de
 * escrituras tardías. La migración 1→2 tiene su propia prueba con los
 * esquemas exportados.
 */
class DiagnosticoPersistenteTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: DiagnosticoDao
    private lateinit var repositorio: DiagnosticoRepositorio

    @Before
    fun preparar() {
        val contexto = InstrumentationRegistry.getInstrumentation().targetContext
        db =
            Room
                .inMemoryDatabaseBuilder(contexto, AppDatabase::class.java)
                .addMigrations(Migraciones.MIGRACION_1_2)
                .build()
        dao = db.diagnosticoDao()
        repositorio = DiagnosticoRepositorio(dao)
    }

    @After
    fun cerrar() {
        db.close()
    }

    @Test
    fun laSesionAbiertaSeRegistra() {
        runBlocking {
            repositorio.abrir(7L, "Canal Siete", "http://servidor.tv:8080/stream.m3u8")
            repositorio.drenar()
            val fila = dao.recientes(1).single()
            assertEquals(7L, fila.canalId)
            assertEquals("Canal Siete", fila.canalNombre)
            assertNotNull(fila.inicio)
            assertNull(fila.fin)
            assertNull(fila.resultado)
        }
    }

    @Test
    fun losEventosDeErrorSeRegistran() {
        runBlocking {
            repositorio.abrir(1L, "Canal Uno", "http://servidor.tv/stream")
            repositorio.observar(
                EstadoSesion(
                    estado = EstadoReproductor.ERROR,
                    codigoError = "error_http",
                    clasificacionTecnica = "fallo_servidor",
                    estadoHttp = 500,
                    clasesCausa = listOf("InvalidResponseCodeException"),
                ),
                EstadoPistas(),
            )
            repositorio.drenar()
            val fila = dao.recientes(1).single()
            val eventos = dao.eventosDeSesion(fila.id)
            assertEquals(1, eventos.size)
            assertEquals("ERROR", eventos.single().tipo)
            assertEquals("error_http", eventos.single().codigo)
            val detalle = eventos.single().detalle
            assertNotNull(detalle)
            assertTrue(detalle.orEmpty().contains("fallo_servidor"))
            assertEquals(500, fila.estadoHttp)
        }
    }

    @Test
    fun elCierreCorrectoTerminaLaSesion() {
        runBlocking {
            repositorio.abrir(1L, null, "http://servidor.tv/stream")
            repositorio.observar(
                EstadoSesion(estado = EstadoReproductor.REPRODUCIENDO, posicionMs = 4_000L, duracionMs = 12_000L),
                EstadoPistas(),
            )
            repositorio.cerrar()
            repositorio.drenar()
            val fila = dao.recientes(1).single()
            assertNotNull(fila.fin)
            assertEquals("TERMINADA", fila.resultado)
            assertEquals("REPRODUCIENDO", fila.faseFinal)
            assertEquals(4_000L, fila.posicionMs)
            assertEquals("CIERRE", dao.eventosDeSesion(fila.id).last().tipo)
        }
    }

    @Test
    fun elVeredictoDelInventarioDeDecodersSePersiste() {
        runBlocking {
            repositorio.abrir(1L, null, "http://servidor.tv/stream")
            repositorio.observar(
                EstadoSesion(
                    estado = EstadoReproductor.REPRODUCIENDO,
                    pistaVideoDetectadaMime = "video/mpeg2",
                    decoderVideoSeleccionado = null,
                    tipoDecoderVideo = null,
                    motivoSoporteVideo = MotivoSoporteVideo.MIME_NO_SOPORTADO,
                    perfilNivelVideo = null,
                ),
                EstadoPistas(),
            )
            repositorio.drenar()
            val fila = dao.recientes(1).single()
            assertNull(fila.decoderVideoSeleccionado)
            assertNull(fila.tipoDecoderVideo)
            assertEquals("MIME_NO_SOPORTADO", fila.motivoSoporteVideo)
            assertNull(fila.perfilNivelVideo)
        }
    }

    @Test
    fun laSesionFallidaSeConservaConSuError() {
        runBlocking {
            repositorio.abrir(2L, "Canal Dos", "http://servidor.tv/stream")
            repositorio.observar(
                EstadoSesion(
                    estado = EstadoReproductor.ERROR,
                    codigoError = "error_http",
                    clasificacionTecnica = "credenciales_rechazadas",
                    estadoHttp = 401,
                    msHastaFallo = 350L,
                ),
                EstadoPistas(),
            )
            repositorio.cerrar()
            repositorio.drenar()
            val fila = dao.recientes(1).single()
            assertEquals("ERROR", fila.resultado)
            assertEquals("error_http", fila.ultimoError)
            assertEquals(401, fila.estadoHttp)
            assertEquals(350L, fila.msHastaFallo)
        }
    }

    @Test
    fun lasSesionesRecientesSeConsultan() {
        runBlocking {
            repeat(3) { indice ->
                repositorio.abrir(indice.toLong(), "Canal $indice", "http://servidor.tv/$indice")
                repositorio.cerrar()
                repositorio.drenar()
            }
            val recientes = dao.recientes(2)
            assertEquals(2, recientes.size)
            assertTrue(recientes[0].inicio >= recientes[1].inicio)
        }
    }

    @Test
    fun elHistorialDeUnCanalSeConsulta() {
        runBlocking {
            repeat(2) { repositorio.abrir(1L, "Canal Uno", "http://servidor.tv/uno") }
            repositorio.abrir(2L, "Canal Dos", "http://servidor.tv/dos")
            repositorio.drenar()
            assertEquals(2, dao.deCanal(1L, 10).size)
            assertEquals(1, dao.deCanal(2L, 10).size)
        }
    }

    @Test
    fun elDiagnosticoSobreviveAlReinicioDeLaApp() {
        val contexto = InstrumentationRegistry.getInstrumentation().targetContext
        contexto.deleteDatabase(NOMBRE_REINICIO)
        val primera =
            Room
                .databaseBuilder(contexto, AppDatabase::class.java, NOMBRE_REINICIO)
                .build()
        try {
            runBlocking {
                val daoSesion = primera.diagnosticoDao()
                val escritor = DiagnosticoRepositorio(daoSesion)
                escritor.abrir(9L, "Canal Nueve", "http://servidor.tv/nueve")
                escritor.observar(
                    EstadoSesion(
                        estado = EstadoReproductor.REPRODUCIENDO,
                        pistaVideoCodec = "avc1.64001f",
                        pistaAudioCodec = "mp4a.40.2",
                    ),
                    EstadoPistas(),
                )
                escritor.cerrar()
                escritor.drenar()
            }
        } finally {
            primera.close()
        }
        val segunda =
            Room
                .databaseBuilder(contexto, AppDatabase::class.java, NOMBRE_REINICIO)
                .build()
        try {
            runBlocking {
                val fila = segunda.diagnosticoDao().recientes(1).single()
                assertEquals(9L, fila.canalId)
                assertEquals("avc1.64001f", fila.pistaVideoCodec)
                assertNotNull(fila.fin)
            }
        } finally {
            segunda.close()
            contexto.deleteDatabase(NOMBRE_REINICIO)
        }
    }

    @Test
    fun laRecuperacionDeRedSeRegistra() {
        runBlocking {
            repositorio.abrir(3L, "Canal Tres", "http://servidor.tv/tres")
            repositorio.observar(
                EstadoSesion(estado = EstadoReproductor.REINTENTANDO, reintento = 1, reintentosMaximos = 3),
                EstadoPistas(),
            )
            repositorio.observar(
                EstadoSesion(estado = EstadoReproductor.REPRODUCIENDO, reintento = 1),
                EstadoPistas(),
            )
            repositorio.drenar()
            val fila = dao.recientes(1).single()
            assertEquals(1, fila.recuperacionesRed)
            val evento = dao.eventosDeSesion(fila.id).first { it.tipo == "RECUPERACION_RED" }
            assertEquals("REINTENTANDO", evento.codigo)
        }
    }

    @Test
    fun laRecuperacionDeRedSeRegistraATravesDeCargandoYBuffering() {
        runBlocking {
            repositorio.abrir(3L, "Canal Tres", "http://servidor.tv/tres")
            repositorio.observar(
                EstadoSesion(estado = EstadoReproductor.REINTENTANDO, reintento = 2, reintentosMaximos = 6),
                EstadoPistas(),
            )
            // Secuencia real de SesionMedia3.programarRecuperacion: entre la
            // espera y REPRODUCIENDO siempre se publica CARGANDO (antes de
            // prepare) y luego BUFFERING.
            repositorio.observar(
                EstadoSesion(estado = EstadoReproductor.CARGANDO, reintento = 2),
                EstadoPistas(),
            )
            repositorio.observar(
                EstadoSesion(estado = EstadoReproductor.BUFFERING),
                EstadoPistas(),
            )
            repositorio.observar(
                EstadoSesion(estado = EstadoReproductor.REPRODUCIENDO),
                EstadoPistas(),
            )
            repositorio.drenar()
            val fila = dao.recientes(1).single()
            assertEquals(1, fila.recuperacionesRed)
            val eventos = dao.eventosDeSesion(fila.id).filter { it.tipo == "RECUPERACION_RED" }
            assertEquals(1, eventos.size)
            assertEquals("REINTENTANDO", eventos.single().codigo)
        }
    }

    @Test
    fun laRecuperacionFallidaNoSeCuenta() {
        runBlocking {
            repositorio.abrir(5L, "Canal Cinco", "http://servidor.tv/cinco")
            repositorio.observar(
                EstadoSesion(estado = EstadoReproductor.REINTENTANDO, reintento = 6, reintentosMaximos = 6),
                EstadoPistas(),
            )
            repositorio.observar(
                EstadoSesion(estado = EstadoReproductor.CARGANDO, reintento = 6),
                EstadoPistas(),
            )
            repositorio.observar(
                EstadoSesion(
                    estado = EstadoReproductor.ERROR,
                    codigoError = "sin_conexion",
                    codigoMotor = "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED",
                ),
                EstadoPistas(),
            )
            repositorio.drenar()
            val fila = dao.recientes(1).single()
            assertEquals(0, fila.recuperacionesRed)
            assertTrue(dao.eventosDeSesion(fila.id).none { it.tipo == "RECUPERACION_RED" })
        }
    }

    @Test
    fun laRecuperacionDeRedNoSeCuentaDosVeces() {
        runBlocking {
            repositorio.abrir(6L, "Canal Seis", "http://servidor.tv/seis")
            repositorio.observar(
                EstadoSesion(estado = EstadoReproductor.REINTENTANDO, reintento = 1, reintentosMaximos = 3),
                EstadoPistas(),
            )
            repositorio.observar(
                EstadoSesion(estado = EstadoReproductor.REPRODUCIENDO),
                EstadoPistas(),
            )
            // PAUSADO y un nuevo REPRODUCIENDO sin reintento previo no cuentan.
            repositorio.observar(
                EstadoSesion(estado = EstadoReproductor.PAUSADO),
                EstadoPistas(),
            )
            repositorio.observar(
                EstadoSesion(estado = EstadoReproductor.REPRODUCIENDO),
                EstadoPistas(),
            )
            repositorio.drenar()
            val fila = dao.recientes(1).single()
            assertEquals(1, fila.recuperacionesRed)
            val eventos = dao.eventosDeSesion(fila.id).filter { it.tipo == "RECUPERACION_RED" }
            assertEquals(1, eventos.size)
        }
    }

    @Test
    fun unaSesionSinReintentoNoCuentaRecuperacion() {
        runBlocking {
            repositorio.abrir(7L, "Canal Siete", "http://servidor.tv/siete")
            repositorio.observar(
                EstadoSesion(estado = EstadoReproductor.CARGANDO),
                EstadoPistas(),
            )
            repositorio.observar(
                EstadoSesion(estado = EstadoReproductor.BUFFERING),
                EstadoPistas(),
            )
            repositorio.observar(
                EstadoSesion(estado = EstadoReproductor.REPRODUCIENDO),
                EstadoPistas(),
            )
            repositorio.drenar()
            val fila = dao.recientes(1).single()
            assertEquals(0, fila.recuperacionesRed)
            assertTrue(dao.eventosDeSesion(fila.id).none { it.tipo == "RECUPERACION_RED" })
        }
    }

    @Test
    fun laRecuperacionDeImagenSeRegistra() {
        runBlocking {
            repositorio.abrir(4L, "Canal Cuatro", "http://servidor.tv/cuatro")
            repositorio.observar(
                EstadoSesion(estado = EstadoReproductor.REPRODUCIENDO, estadoImagen = EstadoImagen.RECUPERANDO_IMAGEN),
                EstadoPistas(),
            )
            repositorio.observar(
                EstadoSesion(estado = EstadoReproductor.REPRODUCIENDO, estadoImagen = EstadoImagen.IMAGEN_RECUPERADA),
                EstadoPistas(),
            )
            repositorio.drenar()
            val fila = dao.recientes(1).single()
            assertEquals(1, fila.recuperacionesImagen)
            assertEquals("IMAGEN_RECUPERADA", fila.imagenFinal)
            val evento = dao.eventosDeSesion(fila.id).first { it.tipo == "RECUPERACION_IMAGEN" }
            assertEquals("RECUPERANDO_IMAGEN", evento.codigo)
        }
    }

    @Test
    fun noSeDuplicanSesionesNiCierres() {
        runBlocking {
            repositorio.abrir(5L, "Canal Cinco", "http://servidor.tv/cinco")
            repositorio.cerrar()
            repositorio.cerrar()
            repositorio.drenar()
            assertEquals(1, dao.recientes(10).size)
            val fila = dao.recientes(1).single()
            val cierres = dao.eventosDeSesion(fila.id).count { it.tipo == "CIERRE" }
            assertEquals(1, cierres)
        }
    }

    @Test
    fun lasEscriturasTardiasSeIgnoran() {
        runBlocking {
            repositorio.abrir(6L, "Canal Seis", "http://servidor.tv/seis")
            repositorio.observar(
                EstadoSesion(estado = EstadoReproductor.REPRODUCIENDO, posicionMs = 1_000L),
                EstadoPistas(),
            )
            repositorio.cerrar()
            repositorio.drenar()
            val cerrada = dao.recientes(1).single()
            repositorio.observar(
                EstadoSesion(estado = EstadoReproductor.REPRODUCIENDO, posicionMs = 9_000L),
                EstadoPistas(),
            )
            repositorio.cerrar()
            repositorio.drenar()
            val trasTardia = dao.porId(cerrada.id)
            assertNotNull(trasTardia)
            assertEquals(cerrada.fin, trasTardia?.fin)
            assertEquals(cerrada.faseFinal, trasTardia?.faseFinal)
            assertEquals(1_000L, trasTardia?.posicionMs)
        }
    }

    @Test
    fun laRetencionEliminaSoloSesionesCerradasAntiguas() {
        runBlocking {
            val ahora = Instant.now()
            val haceCuarentaDias = ahora.minus(Duration.ofDays(40))
            val haceDiezDias = ahora.minus(Duration.ofDays(10))
            // Antigua y cerrada: fuera.
            val viejaCerrada =
                dao.insertarSesion(
                    SesionDiagnosticoEntity(inicio = haceCuarentaDias, fin = haceCuarentaDias.plusSeconds(60)),
                )
            // Antigua y activa: se conserva.
            val viejaActiva = dao.insertarSesion(SesionDiagnosticoEntity(inicio = haceCuarentaDias))
            // Reciente y cerrada: se conserva.
            val recienteCerrada =
                dao.insertarSesion(
                    SesionDiagnosticoEntity(inicio = haceDiezDias, fin = haceDiezDias.plusSeconds(60)),
                )
            dao.insertarEvento(EventoDiagnosticoEntity(sesionId = viejaCerrada, momento = haceCuarentaDias, tipo = "ERROR"))
            repositorio.diasRetencion = 30L
            repositorio.abrir(8L, "Canal Ocho", "http://servidor.tv/ocho")
            repositorio.cerrar()
            repositorio.drenar()
            assertNull(dao.porId(viejaCerrada))
            assertNotNull(dao.porId(viejaActiva))
            assertNotNull(dao.porId(recienteCerrada))
        }
    }

    @Test
    fun lasUrlsSeSaneanAntesDePersistir() {
        runBlocking {
            repositorio.abrir(
                10L,
                "Canal Diez",
                "https://usuario:clave@servidor.tv:8443/stream.m3u8?token=secreto#frag",
            )
            repositorio.drenar()
            val fila = dao.recientes(1).single()
            assertEquals("https://servidor.tv:8443", fila.urlSaneada)
        }
    }

    @Test
    fun losEventosConsecutivosIdenticosSeAgrupan() {
        runBlocking {
            repositorio.abrir(11L, "Canal Once", "http://servidor.tv/once")
            val error =
                EstadoSesion(
                    estado = EstadoReproductor.ERROR,
                    codigoError = "error_http",
                    clasificacionTecnica = "fallo_servidor",
                    estadoHttp = 503,
                )
            repositorio.observar(error, EstadoPistas())
            repositorio.observar(error, EstadoPistas())
            repositorio.drenar()
            val fila = dao.recientes(1).single()
            val errores = dao.eventosDeSesion(fila.id).count { it.tipo == "ERROR" }
            assertEquals(1, errores)
        }
    }

    @Test
    fun laIntegridadReferencialSeMantiene() {
        runBlocking {
            val violacion =
                runCatching {
                    dao.insertarEvento(
                        EventoDiagnosticoEntity(
                            sesionId = 99_999L,
                            momento = Instant.now(),
                            tipo = "ERROR",
                        ),
                    )
                }
            assertTrue(violacion.isFailure)
            // El borrado en cascada elimina los eventos de la sesión eliminada.
            repositorio.abrir(12L, "Canal Doce", "http://servidor.tv/doce")
            repositorio.observar(
                EstadoSesion(estado = EstadoReproductor.ERROR, codigoError = "error_http"),
                EstadoPistas(),
            )
            repositorio.cerrar()
            repositorio.drenar()
            val fila = dao.recientes(1).single()
            val corte = Instant.now().plusSeconds(3_600)
            dao.eliminarSesionesCerradasAnterioresA(corte)
            assertTrue(dao.eventosDeSesion(fila.id).isEmpty())
        }
    }

    private companion object {
        const val NOMBRE_REINICIO = "prueba-diag-reinicio.db"
    }
}
