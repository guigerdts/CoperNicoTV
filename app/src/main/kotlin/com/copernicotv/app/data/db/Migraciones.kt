package com.copernicotv.app.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migraciones del esquema de Room.
 *
 * La v2 añade las tablas del diagnóstico persistente de reproducción
 * (E7-T10, S6a). Las tablas existentes no se tocan: ninguna fila de la v1
 * se altera ni se pierde.
 */
object Migraciones {
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
