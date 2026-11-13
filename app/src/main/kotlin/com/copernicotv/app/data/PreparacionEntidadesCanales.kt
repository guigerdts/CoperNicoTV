package com.copernicotv.app.data

import com.copernicotv.app.crypto.ClavesApp
import com.copernicotv.app.data.db.entities.CanalEntity
import com.copernicotv.core.crypto.CifradoAes
import com.copernicotv.core.identity.ClaveCanal
import com.copernicotv.core.m3u.CanalImportadoM3U
import com.copernicotv.core.m3u.CodecAtributosExtra
import com.copernicotv.core.modelo.FilaCanal
import com.copernicotv.core.modelo.InvariantesCanal
import java.time.Instant

/**
 * Filas de `Canal` listas para inserción, preparadas fuera del
 * `CatalogoRepositorio` para mantener la clase bajo el límite de detekt.
 *
 * La forma persistida de la clave de unicidad es la única fuente de verdad
 * sobre si el tvg-id habilitó la clave (§6.3.2): la primera violación de
 * invariantes aborta con `CanalRechazado` y la transacción entera se
 * descarta, sin catálogo parcial.
 */
internal fun prepararEntidadesCanales(
    claves: ClavesApp,
    canales: List<CanalImportadoM3U>,
    fuenteId: Long,
    ahora: Instant,
    avance: AvanceImportacion?,
): List<CanalEntity> {
    val entidades = ArrayList<CanalEntity>(canales.size)
    canales.forEachIndexed { indice, canal ->
        val tvg = canal.tvgId
        val tvgUnico = tvg != null && canal.claveUnicidad == ClaveCanal.PREFIJO_TVG + tvg
        val entidad =
            CanalEntity(
                fuenteId = fuenteId,
                nombre = canal.nombre,
                urlStreamCifrada =
                    CifradoAes.cifrar(
                        claves.claveAes,
                        CifradoAes.aad("Canal", "url_stream_cifrada"),
                        canal.urlOriginal.toByteArray(Charsets.UTF_8),
                    ),
                urlStreamHmac = canal.urlStreamHmac.toByteArray(Charsets.UTF_8),
                logoUrlCifrada = cifrarLogo(claves, canal.logoOriginal),
                logoUrlHmac = canal.logoHmac?.toByteArray(Charsets.UTF_8),
                tvgId = canal.tvgId,
                claveCanonica = canal.claveCanonica,
                claveUnicidad = canal.claveUnicidad,
                claveDerivable = canal.claveDerivable,
                atributosExtra = CodecAtributosExtra.codificar(canal.atributosExtra),
                creadoEn = ahora,
                actualizadoEn = ahora,
            )
        val violaciones =
            InvariantesCanal.validar(
                FilaCanal(
                    nombre = entidad.nombre,
                    estado = entidad.estado,
                    claveDerivable = entidad.claveDerivable,
                    claveCanonica = entidad.claveCanonica,
                    claveUnicidad = entidad.claveUnicidad,
                    tvgId = entidad.tvgId,
                    urlStreamCifradaPresente = entidad.urlStreamCifrada.isNotEmpty(),
                    urlStreamHmacPresente = entidad.urlStreamHmac.isNotEmpty(),
                ),
                tvgUnico,
            )
        if (violaciones.isNotEmpty()) throw CanalRechazado(violaciones)
        entidades += entidad
        avance?.ticPreparacion(indice + 1)
    }
    return entidades
}

private fun cifrarLogo(
    claves: ClavesApp,
    logo: String?,
): ByteArray? =
    logo?.let {
        CifradoAes.cifrar(
            claves.claveAes,
            CifradoAes.aad("Canal", "logo_url_cifrada"),
            it.toByteArray(Charsets.UTF_8),
        )
    }
