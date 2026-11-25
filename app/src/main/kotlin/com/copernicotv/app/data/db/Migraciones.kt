package com.copernicotv.app.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migraciones del esquema de Room.
 *
 * La v2 añade las tablas del diagnóstico persistente de reproducción
 * (E7-T10, S6a). La v3 añade a `SesionDiagnostico` las columnas de la pista
 * de video detectada aunque no seleccionada (E7-T10 T7): MIME, códec y
 * soporte observado del dispositivo. Las tablas existentes no se tocan:
 * ninguna fila se altera ni se pierde.
 */
object Migraciones {
    /**
     * La v5 añade la gestión de conexiones IPTV y completa la guía:
     *
     * - Tabla `Conexion` con perfiles Xtream Codes y M3U/M3U8. Los secretos
     *   (`passwordCifrada`, URL de lista y de guía) son BLOB cifrados con
     *   Android Keystore; ninguna columna guarda texto plano.
     * - `FuenteGuia`: origen, estado, conexión administradora, última
     *   actualización y último error. Todas las columnas nuevas son nulables
     *   para que las filas existentes sobrevivan sin valor por defecto.
     * - `Programa`: subtítulo, categoría, `episode-num` e idioma.
     *
     * Es una migración aditiva: no se recrean tablas ni se tocan filas.
     */
    val MIGRACION_4_5: Migration =
        object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `Conexion` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`nombre` TEXT NOT NULL, " +
                        "`tipo` TEXT NOT NULL, " +
                        "`activa` INTEGER NOT NULL, " +
                        "`protocolo` TEXT NOT NULL, " +
                        "`servidor` TEXT, " +
                        "`puerto` INTEGER, " +
                        "`usuario` TEXT, " +
                        "`passwordCifrada` BLOB, " +
                        "`formatoSalida` TEXT, " +
                        "`urlListaCifrada` BLOB, " +
                        "`urlListaHmac` BLOB, " +
                        "`urlGuiaCifrada` BLOB, " +
                        "`urlGuiaHmac` BLOB, " +
                        "`actualizacionAutomatica` INTEGER NOT NULL, " +
                        "`intervaloHoras` INTEGER NOT NULL, " +
                        "`estado` TEXT NOT NULL, " +
                        "`ultimoMensaje` TEXT, " +
                        "`ultimoCodigo` TEXT, " +
                        "`listaActualizadaEn` INTEGER, " +
                        "`guiaActualizadaEn` INTEGER, " +
                        "`creadoEn` INTEGER NOT NULL, " +
                        "`actualizadoEn` INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_Conexion_nombre` ON `Conexion` (`nombre`)",
                )
                db.execSQL("ALTER TABLE `FuenteGuia` ADD COLUMN `origen` TEXT")
                db.execSQL("ALTER TABLE `FuenteGuia` ADD COLUMN `estado` TEXT")
                db.execSQL("ALTER TABLE `FuenteGuia` ADD COLUMN `conexionId` INTEGER")
                db.execSQL("ALTER TABLE `FuenteGuia` ADD COLUMN `ultimaActualizacionEn` INTEGER")
                db.execSQL("ALTER TABLE `FuenteGuia` ADD COLUMN `ultimoErrorCodigo` TEXT")
                db.execSQL("ALTER TABLE `FuenteGuia` ADD COLUMN `ultimoErrorMensaje` TEXT")
                db.execSQL("ALTER TABLE `FuenteGuia` ADD COLUMN `intentosFallidos` INTEGER")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_FuenteGuia_conexionId` " +
                        "ON `FuenteGuia` (`conexionId`)",
                )
                db.execSQL("ALTER TABLE `Programa` ADD COLUMN `subtitulo` TEXT")
                db.execSQL("ALTER TABLE `Programa` ADD COLUMN `categoria` TEXT")
                db.execSQL("ALTER TABLE `Programa` ADD COLUMN `episodeNum` TEXT")
                db.execSQL("ALTER TABLE `Programa` ADD COLUMN `idioma` TEXT")
            }
        }

    /**
     * La v4 añade a `SesionDiagnostico` las columnas del veredicto del
     * inventario de decoders (E7-T11): decoder seleccionado, tipo, motivo
     * de soporte y perfil/nivel. Todas TEXT; las filas v3 sobreviven con
     * columnas nulas.
     */
    val MIGRACION_3_4: Migration =
        object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `SesionDiagnostico` ADD COLUMN `decoderVideoSeleccionado` TEXT")
                db.execSQL("ALTER TABLE `SesionDiagnostico` ADD COLUMN `tipoDecoderVideo` TEXT")
                db.execSQL("ALTER TABLE `SesionDiagnostico` ADD COLUMN `motivoSoporteVideo` TEXT")
                db.execSQL("ALTER TABLE `SesionDiagnostico` ADD COLUMN `perfilNivelVideo` TEXT")
            }
        }

    val MIGRACION_2_3: Migration =
        object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `SesionDiagnostico` ADD COLUMN `pistaVideoDetectadaMime` TEXT")
                db.execSQL("ALTER TABLE `SesionDiagnostico` ADD COLUMN `pistaVideoDetectadaCodec` TEXT")
                db.execSQL("ALTER TABLE `SesionDiagnostico` ADD COLUMN `pistaVideoSoportada` INTEGER")
            }
        }

    val MIGRACION_1_2: Migration =
        object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `SesionDiagnostico` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`canalId` INTEGER, " +
                        "`canalNombre` TEXT, " +
                        "`urlSaneada` TEXT, " +
                        "`inicio` INTEGER NOT NULL, " +
                        "`fin` INTEGER, " +
                        "`faseFinal` TEXT, " +
                        "`resultado` TEXT, " +
                        "`posicionMs` INTEGER, " +
                        "`duracionMs` INTEGER, " +
                        "`ultimoError` TEXT, " +
                        "`codigoMotor` TEXT, " +
                        "`claseExcepcion` TEXT, " +
                        "`clasificacionTecnica` TEXT, " +
                        "`reintento` INTEGER, " +
                        "`reintentosMaximos` INTEGER, " +
                        "`recuperacionesRed` INTEGER NOT NULL, " +
                        "`recuperacionesImagen` INTEGER NOT NULL, " +
                        "`imagenFinal` TEXT, " +
                        "`condicion` TEXT, " +
                        "`estadoHttp` INTEGER, " +
                        "`codigoHttpInicial` INTEGER, " +
                        "`tipoMime` TEXT, " +
                        "`bytesRespuesta` INTEGER, " +
                        "`msHastaFallo` INTEGER, " +
                        "`intentosFallo` INTEGER, " +
                        "`redirecciones` INTEGER, " +
                        "`esquemaInicial` TEXT, " +
                        "`esquemaFinal` TEXT, " +
                        "`cicloRedirecciones` INTEGER, " +
                        "`limiteRedirecciones` INTEGER, " +
                        "`fuenteMedia` TEXT, " +
                        "`formatoEsperado` TEXT, " +
                        "`formatoObservado` TEXT, " +
                        "`formatoObservadoMime` TEXT, " +
                        "`reSeleccionMedia` TEXT, " +
                        "`pistaVideoMime` TEXT, " +
                        "`pistaVideoCodec` TEXT, " +
                        "`pistaVideoResolucion` TEXT, " +
                        "`pistaVideoFrameRate` REAL, " +
                        "`pistaVideoBitrate` INTEGER, " +
                        "`pistaAudioMime` TEXT, " +
                        "`pistaAudioCodec` TEXT, " +
                        "`pistaAudioBitrate` INTEGER, " +
                        "`decoderVideo` TEXT, " +
                        "`decoderAudio` TEXT, " +
                        "`msHastaPrimerFrameVideo` INTEGER, " +
                        "`framesPerdidosVideo` INTEGER, " +
                        "`framesRenderidosVideo` INTEGER, " +
                        "`framesOmitidosVideo` INTEGER, " +
                        "`videoRendererActivo` INTEGER, " +
                        "`audioRendererActivo` INTEGER, " +
                        "`pistaVideoDetectada` INTEGER, " +
                        "`pistaAudioDetectada` INTEGER, " +
                        "`pistasAudioTexto` TEXT, " +
                        "`pistasSubtituloTexto` TEXT)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_SesionDiagnostico_canalId` " +
                        "ON `SesionDiagnostico` (`canalId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_SesionDiagnostico_inicio` " +
                        "ON `SesionDiagnostico` (`inicio`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_SesionDiagnostico_fin` " +
                        "ON `SesionDiagnostico` (`fin`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_SesionDiagnostico_resultado` " +
                        "ON `SesionDiagnostico` (`resultado`)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `EventoDiagnostico` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`sesionId` INTEGER NOT NULL, " +
                        "`momento` INTEGER NOT NULL, " +
                        "`tipo` TEXT NOT NULL, " +
                        "`detalle` TEXT, " +
                        "`codigo` TEXT, " +
                        "FOREIGN KEY(`sesionId`) REFERENCES `SesionDiagnostico`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_EventoDiagnostico_sesionId_momento` " +
                        "ON `EventoDiagnostico` (`sesionId`, `momento`)",
                )
            }
        }
}
