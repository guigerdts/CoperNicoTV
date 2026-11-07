package com.copernicotv.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.copernicotv.app.ClavesEnMemoria
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.app.data.db.entities.CacheM3UEntity
import com.copernicotv.app.data.db.entities.CacheXmltvEntity
import com.copernicotv.app.data.db.entities.CanalEntity
import com.copernicotv.app.data.db.entities.FuenteEntity
import com.copernicotv.app.data.db.entities.FuenteGuiaEntity
import com.copernicotv.app.data.db.entities.ListaEntity
import com.copernicotv.app.data.db.entities.ObservacionPendienteImportacionEntity
import com.copernicotv.core.crypto.CifradoAes
import com.copernicotv.core.crypto.HmacUrl
import com.copernicotv.core.crypto.ResultadoDescifrado
import com.copernicotv.core.hmac.FaseIndiceHmac
import com.copernicotv.core.hmac.MotivoHmacPendiente
import com.copernicotv.core.hmac.MotivoTransicion
import com.copernicotv.core.hmac.ResultadoTransicion
import com.copernicotv.core.hmac.VersionOrigenHmac
import com.copernicotv.core.identity.CampoUrl
import com.copernicotv.core.identity.CanonicalizadorUrl
import com.copernicotv.core.identity.ResultadoCanonizacion
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.Base64

/**
 * Pasada de reconstrucción del índice HMAC (E2-T6).
 *
 * Cada caso usa un valor cifrado y un sobre reales del mismo código de
 * producción: la reconstrucción se compara contra el sobre que `HmacUrl`
 * calcula con la generación objetivo y la misma `cvM` de origen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReconstructorIndiceHmacTest {
    private lateinit var db: AppDatabase
    private lateinit var indice: IndiceHmacRepositorio
    private lateinit var reconstructor: ReconstructorIndiceHmac
    private val claves = ClavesEnMemoria()

    private val ahora: Instant = Instant.parse("2026-01-01T00:00:00Z")

    @Before
    fun abrir() {
        val contexto = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room
                .inMemoryDatabaseBuilder(contexto, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        indice = IndiceHmacRepositorio(db, claves)
        reconstructor =
            ReconstructorIndiceHmac(db, indice, claves, ReconstruccionValorHmac(indice, claves))
    }

    @After
    fun cerrar() {
        db.close()
    }

    @Test
    fun `reconstruye Canal y Lista con la generacion objetivo conservando cvM`() =
        runBlocking {
            val fuenteId = insertarFuente()
            val listaId = insertarLista(fuenteId, URL_LISTA)
            val canalId = insertarCanal(fuenteId, URL_CANAL)
            enReconstruccion(2)

            val resultado = reconstructor.reconstruir(ahora)

            assertTrue(resultado is ResultadoReconstruccion.Ok)
            val estado = (resultado as ResultadoReconstruccion.Ok).estado
            assertEquals(FaseIndiceHmac.OPERATIVO, estado.fase)
            assertEquals("hmac:v2", estado.versionActiva)
            assertEquals(2L, estado.filasTotales)
            assertEquals(2L, estado.filasReconstruidas)
            assertEquals(0L, estado.filasPendientes)
            assertArrayEquals(sobreDe(2, CampoUrl.CANAL_URL_STREAM, URL_CANAL), hmacCanal(canalId))
            assertArrayEquals(sobreDe(2, CampoUrl.LISTA_UBICACION, URL_LISTA), hmacLista(listaId))
            assertEquals(0, indice.pendientes().size)
        }

    @Test
    fun `un sobre legacy queda pendiente con version canonica desconocida`() =
        runBlocking {
            val fuenteId = insertarFuente()
            val canalId = insertarCanal(fuenteId, URL_CANAL, sobre = sobreLegacy())
            enReconstruccion(2)

            val resultado = reconstructor.reconstruir(ahora)

            val estado = (resultado as ResultadoReconstruccion.Ok).estado
            assertEquals(FaseIndiceHmac.BLOQUEADO_PARCIAL, estado.fase)
            val pendiente = indice.pendientes().single()
            assertEquals(MotivoHmacPendiente.VERSION_CANONICA_DESCONOCIDA, pendiente.motivo)
            assertEquals(VersionOrigenHmac.Desconocida, pendiente.versionOrigen)
            assertArrayEquals(sobreLegacy(), hmacCanal(canalId))
        }

    @Test
    fun `un cvM no soportado queda pendiente`() =
        runBlocking {
            val fuenteId = insertarFuente()
            val sobre = sobreDe(1, CampoUrl.CANAL_URL_STREAM, URL_CANAL, cv = 9)
            insertarCanal(fuenteId, URL_CANAL, sobre = sobre)
            enReconstruccion(2)

            val resultado = reconstructor.reconstruir(ahora)

            val estado = (resultado as ResultadoReconstruccion.Ok).estado
            assertEquals(FaseIndiceHmac.BLOQUEADO_PARCIAL, estado.fase)
            val pendiente = indice.pendientes().single()
            assertEquals(MotivoHmacPendiente.VERSION_CANONICA_NO_SOPORTADA, pendiente.motivo)
            assertEquals(VersionOrigenHmac.Cv(9), pendiente.versionOrigen)
        }

    @Test
    fun `un sobre malformado queda pendiente con hmac formato invalido`() =
        runBlocking {
            val fuenteId = insertarFuente()
            val canalId = insertarCanal(fuenteId, URL_CANAL, sobre = "no-es-un-sobre".toByteArray())
            enReconstruccion(2)

            val resultado = reconstructor.reconstruir(ahora)

            assertEquals(
                FaseIndiceHmac.BLOQUEADO_PARCIAL,
                (resultado as ResultadoReconstruccion.Ok).estado.fase,
            )
            assertEquals(
                MotivoHmacPendiente.HMAC_FORMATO_INVALIDO,
                indice.pendientes().single().motivo,
            )
            assertEquals("no-es-un-sobre", hmacCanal(canalId).toString(Charsets.UTF_8))
        }

    @Test
    fun `un ciphertext corrupto queda pendiente sin borrar el Canal`() =
        runBlocking {
            val fuenteId = insertarFuente()
            val canalId = insertarCanal(fuenteId, URL_CANAL, cifrado = ByteArray(40) { 0x01 })
            enReconstruccion(2)

            val resultado = reconstructor.reconstruir(ahora)

            assertEquals(
                FaseIndiceHmac.BLOQUEADO_PARCIAL,
                (resultado as ResultadoReconstruccion.Ok).estado.fase,
            )
            assertEquals(
                MotivoHmacPendiente.CIPHERTEXT_CORRUPTO,
                indice.pendientes().single().motivo,
            )
            assertTrue(db.canalDao().porId(canalId) != null)
        }

    @Test
    fun `un logo sin valor original queda pendiente`() =
        runBlocking {
            val fuenteId = insertarFuente()
            val canalId = insertarCanal(fuenteId, URL_CANAL)
            db.reconstruccionHmacDao().actualizarLogoHmac(
                canalId,
                sobreDe(1, CampoUrl.CANAL_LOGO_URL, URL_LOGO),
            )
            enReconstruccion(2)

            val resultado = reconstructor.reconstruir(ahora)

            assertEquals(
                FaseIndiceHmac.BLOQUEADO_PARCIAL,
                (resultado as ResultadoReconstruccion.Ok).estado.fase,
            )
            val pendiente = indice.pendientes().single()
            assertEquals(MotivoHmacPendiente.VALOR_ORIGINAL_AUSENTE, pendiente.motivo)
            assertEquals("logoUrlHmac", pendiente.columna)
        }

    @Test
    fun `la pasada se rechaza fuera de recuperacion en curso`() =
        runBlocking {
            val fuenteId = insertarFuente()
            insertarCanal(fuenteId, URL_CANAL)

            val resultado = reconstructor.reconstruir(ahora)

            assertEquals(
                ResultadoReconstruccion.Rechazada(MotivoTransicion.TRANSICION_NO_PERMITIDA),
                resultado,
            )
            assertEquals(FaseIndiceHmac.OPERATIVO, indice.estado(ahora).fase)
        }

    @Test
    fun `una base sin filas cierra la reconstruccion en operativo`() =
        runBlocking {
            enReconstruccion(2)

            val resultado = reconstructor.reconstruir(ahora)

            val estado = (resultado as ResultadoReconstruccion.Ok).estado
            assertEquals(FaseIndiceHmac.OPERATIVO, estado.fase)
            assertEquals(0L, estado.filasTotales)
            assertEquals(0L, estado.filasPendientes)
        }

    @Test
    fun `los lotes recorren todas las filas`() =
        runBlocking {
            val fuenteId = insertarFuente()
            repeat(5) { posicion ->
                insertarCanal(fuenteId, "$URL_CANAL?canal=$posicion")
            }
            enReconstruccion(2)

            val resultado = reconstructor.reconstruir(ahora, tamanoLote = 2)

            val estado = (resultado as ResultadoReconstruccion.Ok).estado
            assertEquals(FaseIndiceHmac.OPERATIVO, estado.fase)
            assertEquals(5L, estado.filasTotales)
            assertEquals(5L, estado.filasReconstruidas)
            assertEquals(0, indice.pendientes().size)
        }

    @Test
    fun `reanudar tras resolver pendientes no reescribe lo ya reconstruido`() =
        runBlocking {
            val fuenteId = insertarFuente()
            val bueno = insertarCanal(fuenteId, URL_CANAL)
            val roto = insertarCanal(fuenteId, "$URL_CANAL?roto=1", cifrado = ByteArray(40) { 0x01 })
            val legacy = insertarCanal(fuenteId, "$URL_CANAL?legacy=1", sobre = sobreLegacy())
            enReconstruccion(2)

            val primera = reconstructor.reconstruir(ahora)

            val estadoPrimera = (primera as ResultadoReconstruccion.Ok).estado
            assertEquals(FaseIndiceHmac.BLOQUEADO_PARCIAL, estadoPrimera.fase)
            assertEquals(3L, estadoPrimera.filasTotales)
            assertEquals(1L, estadoPrimera.filasReconstruidas)
            assertEquals(2L, estadoPrimera.filasPendientes)
            val hmacBueno = hmacCanal(bueno)

            repararCifrado(roto, cifrar("Canal", "url_stream_cifrada", "$URL_CANAL?roto=1"))
            borrarCanal(legacy)
            indice.eliminarPendientes()
            assertTrue(indice.resolverPendientes(2, 1, 0, ahora) is ResultadoTransicion.Ok)

            val segunda = reconstructor.reconstruir(ahora)

            val estadoSegunda = (segunda as ResultadoReconstruccion.Ok).estado
            assertEquals(FaseIndiceHmac.OPERATIVO, estadoSegunda.fase)
            assertEquals("hmac:v2", estadoSegunda.versionActiva)
            assertEquals(2L, estadoSegunda.filasReconstruidas)
            assertArrayEquals(hmacBueno, hmacCanal(bueno))
            assertArrayEquals(
                sobreDe(2, CampoUrl.CANAL_URL_STREAM, "$URL_CANAL?roto=1"),
                hmacCanal(roto),
            )
        }

    private suspend fun enReconstruccion(generacion: Int) {
        indice.detectarPerdidaClave(ahora)
        indice.iniciarReconstruccion(generacion, ahora)
    }

    private suspend fun insertarFuente(): Long =
        db.fuenteDao().insertar(
            FuenteEntity(
                nombre = "Fuente de prueba",
                estado = "activa",
                usarIntervaloGlobal = true,
                creadoEn = ahora,
                actualizadoEn = ahora,
            ),
        )

    private suspend fun insertarLista(
        fuenteId: Long,
        url: String,
    ): Long =
        db.listaDao().insertar(
            ListaEntity(
                fuenteId = fuenteId,
                nombre = "Lista de prueba",
                ubicacionCifrada = cifrar("Lista", "ubicacion_cifrada", url),
                ubicacionHmac = sobreDe(1, CampoUrl.LISTA_UBICACION, url),
                esquema = "m3u",
                orden = 0,
                estado = "activa",
                creadoEn = ahora,
                actualizadoEn = ahora,
            ),
        )

    private suspend fun insertarCanal(
        fuenteId: Long,
        url: String,
        sobre: ByteArray = sobreDe(1, CampoUrl.CANAL_URL_STREAM, url),
        cifrado: ByteArray = cifrar("Canal", "url_stream_cifrada", url),
        logo: Boolean = false,
    ): Long =
        db.canalDao().insertar(
            CanalEntity(
                fuenteId = fuenteId,
                nombre = "Canal de prueba",
                urlStreamCifrada = cifrado,
                urlStreamHmac = sobre,
                logoUrlCifrada = if (logo) cifrar("Canal", "logo_url_cifrada", URL_LOGO) else null,
                logoUrlHmac = if (logo) sobreDe(1, CampoUrl.CANAL_LOGO_URL, URL_LOGO) else null,
                claveDerivable = true,
                creadoEn = ahora,
                actualizadoEn = ahora,
            ),
        )

    /**
     * Simula la reparación externa de un valor cifrado: la pasada nunca escribe
     * esta columna, así que la prueba la corrige con SQL directo.
     */
    private fun repararCifrado(
        canalId: Long,
        cifrado: ByteArray,
    ) {
        db.openHelper.writableDatabase
            .compileStatement("UPDATE Canal SET urlStreamCifrada = ? WHERE id = ?")
            .use { sentencia ->
                sentencia.bindBlob(1, cifrado)
                sentencia.bindLong(2, canalId)
                sentencia.executeUpdateDelete()
            }
    }

    private fun borrarCanal(canalId: Long) {
        db.openHelper.writableDatabase
            .compileStatement("DELETE FROM Canal WHERE id = ?")
            .use { sentencia ->
                sentencia.bindLong(1, canalId)
                sentencia.executeUpdateDelete()
            }
    }

    private fun sobreDe(
        generacion: Int,
        campo: CampoUrl,
        url: String,
        cv: Int = 1,
    ): ByteArray {
        val canonica = CanonicalizadorUrl.canonizar(url, campo) as ResultadoCanonizacion.Ok
        return HmacUrl
            .calcularSobre(
                clave = claves.crearClaveHmac(1),
                generacion = generacion,
                campo = campo,
                clase = canonica.clase,
                urlCanonica = canonica.urlCanonica,
                versionCanonica = cv,
            ).toByteArray(Charsets.UTF_8)
    }

    private fun sobreLegacy(): ByteArray =
        "hmac:v1:${Base64.getEncoder().encodeToString(ByteArray(32) { 0x0A })}".toByteArray(Charsets.UTF_8)

    private fun cifrar(
        tabla: String,
        columna: String,
        url: String,
    ): ByteArray =
        CifradoAes.cifrar(
            claves.claveAes,
            CifradoAes.aad(tabla, columna),
            url.toByteArray(Charsets.UTF_8),
        )

    private suspend fun hmacCanal(id: Long): ByteArray = requireNotNull(db.canalDao().porId(id)?.urlStreamHmac)

    private suspend fun hmacLista(id: Long): ByteArray =
        db.reconstruccionHmacDao().loteListas(0, Long.MAX_VALUE, 10).first { it.id == id }.ubicacionHmac

    @Test
    fun `los ocho valores contractuales se reconstruyen a la generacion objetivo`() =
        runBlocking {
            val fuenteId = insertarFuente()
            val listaId = insertarLista(fuenteId, URL_LISTA)
            insertarCanal(fuenteId, URL_CANAL, logo = true)
            insertarObservacion(fuenteId, "$URL_CANAL?obs=1", logo = "$URL_LOGO")
            insertarCacheM3u(fuenteId, listaId, "$URL_LISTA?m3u=1")
            val fuenteGuiaId = insertarFuenteGuia("$URL_LISTA?guia=1")
            insertarCacheXmltv(fuenteGuiaId, "$URL_LISTA?xmltv=1")
            enReconstruccion(2)

            val resultado = reconstructor.reconstruir(ahora)

            val estado = (resultado as ResultadoReconstruccion.Ok).estado
            // Canal url+logo, Lista, Observacion url+logo, CacheM3U, CacheXmltv, FuenteGuia.
            assertEquals(8L, estado.filasTotales)
            assertEquals(FaseIndiceHmac.OPERATIVO, estado.fase)
            assertEquals(0, indice.pendientes().size)
            val dao = db.reconstruccionHmacDao()
            assertArrayEquals(sobreDe(2, CampoUrl.LISTA_UBICACION, URL_LISTA), hmacLista(listaId))
            val canalSobres = dao.loteCanales(0, Long.MAX_VALUE, 10).single()
            assertArrayEquals(sobreDe(2, CampoUrl.CANAL_URL_STREAM, URL_CANAL), canalSobres.urlStreamHmac)
            assertArrayEquals(sobreDe(2, CampoUrl.CANAL_LOGO_URL, URL_LOGO), canalSobres.logoUrlHmac)
            val observaciones = dao.loteObservaciones(0, Long.MAX_VALUE, 10)
            assertArrayEquals(
                sobreDe(2, CampoUrl.CANAL_URL_STREAM, "$URL_CANAL?obs=1"),
                observaciones.single().urlHmac,
            )
            assertArrayEquals(
                sobreDe(2, CampoUrl.CANAL_LOGO_URL, "$URL_LOGO"),
                observaciones.single().logoUrlHmac,
            )
            assertArrayEquals(
                sobreDe(2, CampoUrl.CACHE_M3U_URL, "$URL_LISTA?m3u=1"),
                dao.loteCacheM3u(0, Long.MAX_VALUE, 10).single().urlHmac,
            )
            assertArrayEquals(
                sobreDe(2, CampoUrl.CACHE_XMLTV_URL, "$URL_LISTA?xmltv=1"),
                dao.loteCacheXmltv(0, Long.MAX_VALUE, 10).single().urlHmac,
            )
            assertArrayEquals(
                sobreDe(2, CampoUrl.FUENTE_GUIA_URL, "$URL_LISTA?guia=1"),
                dao.loteFuentesGuia(0, Long.MAX_VALUE, 10).single().urlHmac,
            )
        }

    @Test
    fun `el reingreso restaura cifrado y sobre y cierra la reconstruccion`() =
        runBlocking {
            val fuenteId = insertarFuente()
            val otro = insertarCanal(fuenteId, "$URL_CANAL?otro=1")
            val roto = insertarCanal(fuenteId, URL_CANAL, cifrado = ByteArray(40) { 0x01 })
            enReconstruccion(2)
            assertEquals(FaseIndiceHmac.BLOQUEADO_PARCIAL, (reconstructor.reconstruir(ahora) as ResultadoReconstruccion.Ok).estado.fase)
            val sobrePrevio = hmacCanal(otro)

            val resultado = reconstructor.reingresar("Canal", roto, "urlStreamHmac", "$URL_CANAL?reingreso=1", ahora)

            val estado = (resultado as ResultadoReingreso.Ok).estado
            assertEquals(FaseIndiceHmac.OPERATIVO, estado.fase)
            assertEquals(0, indice.pendientes().size)
            assertArrayEquals(sobreDe(2, CampoUrl.CANAL_URL_STREAM, "$URL_CANAL?reingreso=1"), hmacCanal(roto))
            val restaurado = requireNotNull(db.canalDao().porId(roto)?.urlStreamCifrada)
            assertEquals(
                "$URL_CANAL?reingreso=1",
                (CifradoAes.descifrar(claves.claveAes, CifradoAes.aad("Canal", "url_stream_cifrada"), restaurado) as ResultadoDescifrado.Ok)
                    .bytes.toString(Charsets.UTF_8),
            )
            // La fila ya reconstruida no se toca.
            assertArrayEquals(sobrePrevio, hmacCanal(otro))
        }

    @Test
    fun `el reingreso alcanza el logo de una observacion`() =
        runBlocking {
            val fuenteId = insertarFuente()
            val roto = insertarObservacion(fuenteId, URL_CANAL, logo = URL_LOGO)
            enReconstruccion(2)

            val resultado =
                reconstructor.reingresar("ObservacionPendienteImportacion", roto, "logoUrlHmac", "$URL_LOGO?reingreso=1", ahora)

            val estado = (resultado as ResultadoReingreso.Ok).estado
            assertEquals(FaseIndiceHmac.OPERATIVO, estado.fase)
            val obs = db.reconstruccionHmacDao().loteObservaciones(0, Long.MAX_VALUE, 10).single()
            assertArrayEquals(sobreDe(2, CampoUrl.CANAL_LOGO_URL, "$URL_LOGO?reingreso=1"), obs.logoUrlHmac)
            assertEquals(
                "$URL_LOGO?reingreso=1",
                (
                    CifradoAes.descifrar(
                        claves.claveAes,
                        CifradoAes.aad("ObservacionPendienteImportacion", "logo_url_cifrada"),
                        requireNotNull(obs.logoUrlCifrada),
                    ) as ResultadoDescifrado.Ok
                )
                    .bytes.toString(Charsets.UTF_8),
            )
        }

    @Test
    fun `el reingreso rechaza un valor no canonicalizable y la fila sigue pendiente`() =
        runBlocking {
            val fuenteId = insertarFuente()
            val roto = insertarCanal(fuenteId, URL_CANAL, cifrado = ByteArray(40) { 0x01 })
            enReconstruccion(2)
            reconstructor.reconstruir(ahora)

            val resultado = reconstructor.reingresar("Canal", roto, "urlStreamHmac", "no es una url", ahora)

            assertEquals("canonica_invalida", (resultado as ResultadoReingreso.Rechazada).motivo)
            assertEquals(1, indice.pendientes().size)
        }

    @Test
    fun `el reingreso rechaza columnas y entidades desconocidas`() =
        runBlocking {
            enReconstruccion(2)
            assertEquals(
                "columna_desconocida",
                (reconstructor.reingresar("Canal", 1L, "columna_inexistente", URL_CANAL, ahora) as ResultadoReingreso.Rechazada).motivo,
            )
            assertEquals(
                "canonica_invalida",
                // Columna valida pero entidad distinta: urlHmac la comparten
                // cuatro entidades y la fila no existe para esa pareja.
                (reconstructor.reingresar("EntidadInventada", 1L, "urlHmac", URL_CANAL, ahora) as ResultadoReingreso.Rechazada).motivo,
            )
        }

    @Test
    fun `el reingreso rechaza fase invalida fuera de la reconstruccion`() =
        runBlocking {
            // Idx operativo v1, sin reconstrucción abierta.
            indice.estado(ahora)

            val resultado = reconstructor.reingresar("Canal", 1L, "urlStreamHmac", URL_CANAL, ahora)

            assertEquals("fase_invalida", (resultado as ResultadoReingreso.Rechazada).motivo)
        }

    @Test
    fun `la reconstruccion es idempotente en una segunda pasada`() =
        runBlocking {
            val fuenteId = insertarFuente()
            insertarCanal(fuenteId, URL_CANAL, logo = true)
            enReconstruccion(2)
            val primera = reconstructor.reconstruir(ahora)
            val hmacPrimera = hmacCanal(db.reconstruccionHmacDao().loteCanales(0, Long.MAX_VALUE, 10).single().id)

            val segunda = reconstructor.reconstruir(ahora)

            val estado = (segunda as ResultadoReconstruccion.Ok).estado
            assertEquals(FaseIndiceHmac.OPERATIVO, estado.fase)
            assertEquals((primera as ResultadoReconstruccion.Ok).reconstruidas, estado.reconstruidas)
            assertEquals(0L, estado.pendientes)
            assertArrayEquals(hmacPrimera, hmacCanal(db.reconstruccionHmacDao().loteCanales(0, Long.MAX_VALUE, 10).single().id))
        }

    private suspend fun insertarObservacion(
        fuenteId: Long,
        url: String,
        logo: String? = null,
    ): Long =
        db.observacionPendienteDao().insertar(
            ObservacionPendienteImportacionEntity(
                fuenteId = fuenteId,
                importOrigen = "lista:1",
                nombre = "Observacion de prueba",
                urlCifrada = cifrar("ObservacionPendienteImportacion", "url_cifrada", url),
                urlHmac = sobreDe(1, CampoUrl.CANAL_URL_STREAM, url),
                logoUrlCifrada = logo?.let { cifrar("ObservacionPendienteImportacion", "logo_url_cifrada", it) },
                logoUrlHmac = logo?.let { sobreDe(1, CampoUrl.CANAL_LOGO_URL, it) },
                claveCanonica = null,
                huellaDeclaracion = "huella",
                claveDeduplicacion = "dedup-$url",
                primeraObservacion = ahora,
                ultimaObservacion = ahora,
            ),
        )

    private suspend fun insertarCacheM3u(
        fuenteId: Long,
        listaId: Long,
        url: String,
    ): Long =
        db.cacheM3UDao().insertar(
            CacheM3UEntity(
                fuenteId = fuenteId,
                listaId = listaId,
                urlCifrada = cifrar("CacheM3U", "url_cifrada", url),
                urlHmac = sobreDe(1, CampoUrl.CACHE_M3U_URL, url),
                tipo = "m3u",
                fetchedAt = ahora,
                expiresAt = ahora.plusSeconds(3600),
                tamanoBytes = 128,
                estadoFrescura = "fresca",
                archivoRef = "archivo.cache",
            ),
        )

    private suspend fun insertarFuenteGuia(url: String): Long =
        db.fuenteGuiaDao().insertar(
            FuenteGuiaEntity(
                nombre = "Guia de prueba",
                urlCifrada = cifrar("FuenteGuia", "url_cifrada", url),
                urlHmac = sobreDe(1, CampoUrl.FUENTE_GUIA_URL, url),
                usarRetencionGlobal = true,
                creadoEn = ahora,
                actualizadoEn = ahora,
            ),
        )

    private suspend fun insertarCacheXmltv(
        fuenteGuiaId: Long,
        url: String,
    ): Long =
        db.cacheXmltvDao().insertar(
            CacheXmltvEntity(
                fuenteGuiaId = fuenteGuiaId,
                urlCifrada = cifrar("CacheXmltv", "url_cifrada", url),
                urlHmac = sobreDe(1, CampoUrl.CACHE_XMLTV_URL, url),
                tipo = "xmltv",
                fetchedAt = ahora,
                expiresAt = ahora.plusSeconds(3600),
                tamanoBytes = 256,
                estadoFrescura = "fresca",
                archivoRef = "guia.cache",
            ),
        )

    private companion object {
        const val URL_CANAL = "https://example.com/canal.m3u8"
        const val URL_LISTA = "https://example.com/lista.m3u"
        const val URL_LOGO = "https://example.com/logo.png"
    }
}
