package com.copernicotv.app.data.db

/**
 * Esquema normativo de Room v1, transcrito de §6.3.1 y §6.3.2 de U0
 * (`odd/tasks/mvp-copernicotv.md`).
 *
 * Los nombres se escriben en snake_case, como en §6.3. [nombreFisico] aplica la
 * única transformación que usa Room: el nombre de la propiedad Kotlin en
 * camelCase. La prueba compara siempre contra el nombre físico, así que una
 * columna ausente, sobrante o renombrada falla.
 *
 * `nula = null` significa que §6.3 no fija la nulabilidad de esa columna: la
 * prueba comprueba existencia y tipo, pero no inventa un requisito que el
 * contrato no declara. Lo mismo vale para `borrado = null` en las claves
 * foráneas.
 *
 * Las entidades que §6.3.1 marca como «sin cambios» conservan la definición de
 * E1-U3, que no tiene texto normativo propio: para ellas esta tabla actúa como
 * registro de regresión, no como verificación independiente.
 */
internal data class ColumnaNormativa(
    val nombre: String,
    val tipo: String,
    val nula: Boolean? = null,
    val pk: Int = 0,
)

internal data class FkNormativa(
    val columna: String,
    val tablaPadre: String,
    val columnaPadre: String = "id",
    val borrado: String? = null,
)

internal data class IndiceNormativo(
    val columnas: List<String>,
    val unico: Boolean = false,
)

internal data class TablaNormativa(
    val columnas: List<ColumnaNormativa>,
    val fks: List<FkNormativa> = emptyList(),
    val indices: List<IndiceNormativo> = emptyList(),
)

/** Convierte el nombre snake_case de §6.3 al nombre físico que genera Room. */
internal fun nombreFisico(snake: String): String {
    val partes = snake.split('_')
    return partes.first() +
        partes.drop(1).joinToString("") { parte ->
            parte.replaceFirstChar { caracter -> caracter.uppercase() }
        }
}

private fun texto(
    nombre: String,
    nula: Boolean? = null,
    pk: Int = 0,
) = ColumnaNormativa(nombre, "TEXT", nula, pk)

private fun entero(
    nombre: String,
    nula: Boolean? = null,
    pk: Int = 0,
) = ColumnaNormativa(nombre, "INTEGER", nula, pk)

private fun blob(
    nombre: String,
    nula: Boolean? = null,
) = ColumnaNormativa(nombre, "BLOB", nula)

private fun indice(
    vararg columnas: String,
    unico: Boolean = false,
) = IndiceNormativo(columnas.toList(), unico)

internal val ESQUEMA_NORMATIVO_V1: Map<String, TablaNormativa> =
    mapOf(
        "Fuente" to
            TablaNormativa(
                columnas =
                    listOf(
                        entero("id", pk = 1),
                        texto("nombre"),
                        texto("estado"),
                        entero("intervalo_minutos", nula = true),
                        entero("usar_intervalo_global", nula = false),
                        entero("creado_en"),
                        entero("actualizado_en"),
                    ),
            ),
        "Lista" to
            TablaNormativa(
                columnas =
                    listOf(
                        entero("id", pk = 1),
                        entero("fuente_id", nula = false),
                        texto("nombre"),
                        blob("ubicacion_cifrada", nula = false),
                        blob("ubicacion_hmac", nula = false),
                        texto("esquema"),
                        entero("orden"),
                        texto("estado"),
                        entero("creado_en"),
                        entero("actualizado_en"),
                    ),
                fks = listOf(FkNormativa("fuente_id", "Fuente")),
            ),
        "Grupo" to
            TablaNormativa(
                columnas =
                    listOf(
                        entero("id", pk = 1),
                        entero("lista_id"),
                        texto("nombre"),
                    ),
                fks = listOf(FkNormativa("lista_id", "Lista")),
                indices = listOf(indice("lista_id", "nombre", unico = true)),
            ),
        "Canal" to
            TablaNormativa(
                columnas =
                    listOf(
                        entero("id", pk = 1),
                        entero("fuente_id", nula = false),
                        texto("nombre", nula = true),
                        blob("url_stream_cifrada", nula = false),
                        blob("url_stream_hmac", nula = false),
                        blob("logo_url_cifrada", nula = true),
                        blob("logo_url_hmac", nula = true),
                        texto("tvg_id", nula = true),
                        texto("clave_canonica", nula = true),
                        texto("clave_unicidad", nula = true),
                        entero("clave_derivable", nula = false),
                        texto("estado", nula = false),
                        texto("atributos_extra", nula = true),
                        texto("decision", nula = true),
                        entero("fecha_decision", nula = true),
                        texto("evidencia", nula = true),
                        entero("creado_en"),
                        entero("actualizado_en"),
                    ),
                fks = listOf(FkNormativa("fuente_id", "Fuente")),
                indices =
                    listOf(
                        indice("fuente_id", "clave_unicidad", unico = true),
                        indice("fuente_id", "clave_canonica"),
                        indice("fuente_id", "url_stream_hmac"),
                        indice("fuente_id", "tvg_id"),
                        indice("nombre"),
                    ),
            ),
        "Apariencia" to
            TablaNormativa(
                columnas =
                    listOf(
                        entero("id", pk = 1),
                        entero("lista_id", nula = false),
                        entero("canal_id", nula = false),
                        entero("grupo_id", nula = true),
                        entero("orden", nula = false),
                        texto("group_title", nula = true),
                        texto("tvg_name", nula = true),
                        texto("clave_presentacion", nula = false),
                        entero("repeticiones", nula = false),
                        entero("creado_en"),
                        entero("actualizado_en"),
                    ),
                fks =
                    listOf(
                        FkNormativa("lista_id", "Lista"),
                        FkNormativa("canal_id", "Canal"),
                        FkNormativa("grupo_id", "Grupo"),
                    ),
                indices =
                    listOf(
                        indice("lista_id", "canal_id", "clave_presentacion", unico = true),
                        indice("lista_id", "canal_id"),
                        indice("grupo_id"),
                        indice("lista_id", "orden"),
                    ),
            ),
        "AparienciaClavePendiente" to
            TablaNormativa(
                columnas =
                    listOf(
                        entero("apariencia_id", pk = 1),
                        texto("clave_presentacion_v2", nula = false),
                        texto("motivo", nula = false),
                        entero("creada_en"),
                        entero("resuelta_en", nula = true),
                    ),
                fks = listOf(FkNormativa("apariencia_id", "Apariencia", borrado = "CASCADE")),
                indices = listOf(indice("clave_presentacion_v2")),
            ),
        "Favorito" to
            TablaNormativa(
                columnas =
                    listOf(
                        entero("id", pk = 1),
                        entero("canal_id"),
                        entero("creado_en"),
                    ),
                fks = listOf(FkNormativa("canal_id", "Canal")),
                indices = listOf(indice("canal_id", unico = true)),
            ),
        "OcultacionCanal" to
            TablaNormativa(
                columnas =
                    listOf(
                        entero("id", pk = 1),
                        entero("canal_id"),
                        entero("creado_en"),
                    ),
                fks = listOf(FkNormativa("canal_id", "Canal")),
                indices = listOf(indice("canal_id", unico = true)),
            ),
        "OcultacionGrupo" to
            TablaNormativa(
                columnas =
                    listOf(
                        entero("id", pk = 1),
                        entero("grupo_id"),
                        entero("creado_en"),
                    ),
                fks = listOf(FkNormativa("grupo_id", "Grupo")),
                indices = listOf(indice("grupo_id", unico = true)),
            ),
        "Historial" to
            TablaNormativa(
                columnas =
                    listOf(
                        entero("id", pk = 1),
                        entero("canal_id", nula = false),
                        entero("fecha", nula = false),
                    ),
                fks = listOf(FkNormativa("canal_id", "Canal")),
                indices =
                    listOf(
                        indice("canal_id", unico = true),
                        indice("fecha", "canal_id"),
                    ),
            ),
        "Coleccion" to
            TablaNormativa(
                columnas =
                    listOf(
                        entero("id", pk = 1),
                        texto("nombre"),
                        entero("creado_en"),
                        entero("actualizado_en"),
                    ),
            ),
        "ColeccionCanal" to
            TablaNormativa(
                columnas =
                    listOf(
                        entero("id", pk = 1),
                        entero("coleccion_id"),
                        entero("canal_id"),
                        entero("posicion"),
                    ),
                fks =
                    listOf(
                        FkNormativa("coleccion_id", "Coleccion"),
                        FkNormativa("canal_id", "Canal"),
                    ),
                indices = listOf(indice("coleccion_id", "canal_id")),
            ),
        "Perfil" to
            TablaNormativa(
                columnas =
                    listOf(
                        entero("id", pk = 1),
                        texto("nombre", nula = false),
                        texto("nombre_normalizado", nula = false),
                        entero("creado_en"),
                        entero("actualizado_en"),
                    ),
                indices = listOf(indice("nombre_normalizado", unico = true)),
            ),
        "PerfilLista" to
            TablaNormativa(
                columnas =
                    listOf(
                        entero("perfil_id", pk = 1),
                        entero("lista_id", pk = 2),
                    ),
                fks =
                    listOf(
                        FkNormativa("perfil_id", "Perfil", borrado = "CASCADE"),
                        FkNormativa("lista_id", "Lista", borrado = "CASCADE"),
                    ),
                indices = listOf(indice("lista_id")),
            ),
        "FuenteGuia" to
            TablaNormativa(
                columnas =
                    listOf(
                        entero("id", pk = 1),
                        texto("nombre"),
                        blob("url_cifrada"),
                        blob("url_hmac"),
                        entero("generacion_publicada_id", nula = true),
                        entero("retencion_ventana_pasado_dias", nula = true),
                        entero("retencion_ventana_futuro_dias", nula = true),
                        entero("retencion_max_programas", nula = true),
                        entero("retencion_presupuesto_bytes", nula = true),
                        entero("usar_retencion_global", nula = false),
                        entero("creado_en"),
                        entero("actualizado_en"),
                    ),
            ),
        "GeneracionGuia" to
            TablaNormativa(
                columnas =
                    listOf(
                        entero("id", pk = 1),
                        entero("fuente_guia_id"),
                        texto("version"),
                        texto("estado"),
                        entero("creado_en"),
                        entero("publicada_en"),
                    ),
                fks = listOf(FkNormativa("fuente_guia_id", "FuenteGuia")),
            ),
        "Programa" to
            TablaNormativa(
                columnas =
                    listOf(
                        entero("id", pk = 1),
                        entero("generacion_id"),
                        entero("fuente_guia_id"),
                        texto("channel_id_texto"),
                        texto("titulo"),
                        entero("inicio"),
                        entero("fin"),
                        texto("descripcion"),
                    ),
                fks =
                    listOf(
                        FkNormativa("generacion_id", "GeneracionGuia"),
                        FkNormativa("fuente_guia_id", "FuenteGuia"),
                    ),
                indices =
                    listOf(
                        indice("generacion_id", "channel_id_texto", "inicio"),
                        indice("fuente_guia_id", "inicio"),
                    ),
            ),
        "Asociacion" to
            TablaNormativa(
                columnas =
                    listOf(
                        entero("id", pk = 1),
                        entero("canal_id"),
                        entero("fuente_guia_id"),
                        texto("channel_id_texto"),
                        texto("estado"),
                        texto("origen"),
                        entero("huella_version"),
                        texto("huella_valor", nula = true),
                        texto("senales_fuertes"),
                        entero("ultima_observacion"),
                        entero("confirmada_en", nula = true),
                        entero("actualizada_en"),
                    ),
                fks =
                    listOf(
                        FkNormativa("canal_id", "Canal"),
                        FkNormativa("fuente_guia_id", "FuenteGuia"),
                    ),
                indices =
                    listOf(
                        indice("fuente_guia_id", "channel_id_texto", unico = true),
                        indice("canal_id"),
                    ),
            ),
        "EstadoActualizacion" to
            TablaNormativa(
                columnas =
                    listOf(
                        entero("id", pk = 1),
                        entero("fuente_id"),
                        entero("lista_id"),
                        texto("causa"),
                        texto("version_import"),
                        entero("inicio"),
                        entero("fin"),
                        texto("resultado"),
                    ),
                fks =
                    listOf(
                        FkNormativa("fuente_id", "Fuente"),
                        FkNormativa("lista_id", "Lista"),
                    ),
            ),
        "DetalleAusencia" to
            TablaNormativa(
                columnas =
                    listOf(
                        entero("id", pk = 1),
                        entero("estado_actualizacion_id"),
                        entero("canal_id", nula = true),
                        texto("estado"),
                        texto("texto"),
                        entero("creado_en"),
                    ),
                fks =
                    listOf(
                        FkNormativa("estado_actualizacion_id", "EstadoActualizacion"),
                        FkNormativa("canal_id", "Canal"),
                    ),
            ),
        "EvidenciaColision" to
            TablaNormativa(
                columnas =
                    listOf(
                        entero("id", pk = 1),
                        entero("estado_actualizacion_id"),
                        texto("clave_canonica"),
                        texto("insumos"),
                        entero("fecha"),
                        texto("version"),
                    ),
                fks = listOf(FkNormativa("estado_actualizacion_id", "EstadoActualizacion")),
            ),
        "ObservacionPendienteImportacion" to
            TablaNormativa(
                columnas =
                    listOf(
                        entero("id", pk = 1),
                        entero("fuente_id"),
                        texto("import_origen"),
                        texto("nombre", nula = true),
                        blob("url_cifrada", nula = true),
                        blob("url_hmac", nula = true),
                        blob("logo_url_cifrada", nula = true),
                        blob("logo_url_hmac", nula = true),
                        texto("tvg_id", nula = true),
                        texto("grupo", nula = true),
                        texto("clave_canonica", nula = true),
                        texto("huella_declaracion", nula = false),
                        texto("clave_deduplicacion", nula = false),
                        entero("primera_observacion"),
                        entero("ultima_observacion"),
                        texto("conjunto_bc"),
                    ),
                fks = listOf(FkNormativa("fuente_id", "Fuente")),
                indices = listOf(indice("fuente_id", "clave_deduplicacion", unico = true)),
            ),
        "CacheM3U" to
            TablaNormativa(
                columnas =
                    listOf(
                        entero("id", pk = 1),
                        entero("fuente_id"),
                        entero("lista_id"),
                        blob("url_cifrada"),
                        blob("url_hmac"),
                        texto("tipo"),
                        entero("fetched_at"),
                        entero("expires_at"),
                        entero("tamano_bytes"),
                        texto("estado_frescura"),
                        texto("archivo_ref"),
                    ),
                fks =
                    listOf(
                        FkNormativa("fuente_id", "Fuente"),
                        FkNormativa("lista_id", "Lista"),
                    ),
                indices = listOf(indice("fuente_id", "lista_id", "tipo", "url_hmac", unico = true)),
            ),
        "CacheXmltv" to
            TablaNormativa(
                columnas =
                    listOf(
                        entero("id", pk = 1),
                        entero("fuente_guia_id"),
                        blob("url_cifrada"),
                        blob("url_hmac"),
                        texto("tipo"),
                        entero("fetched_at"),
                        entero("expires_at"),
                        entero("tamano_bytes"),
                        texto("estado_frescura"),
                        texto("archivo_ref"),
                    ),
                fks = listOf(FkNormativa("fuente_guia_id", "FuenteGuia")),
                indices = listOf(indice("fuente_guia_id", "tipo", "url_hmac", unico = true)),
            ),
        "EstadoIndiceHmac" to
            TablaNormativa(
                columnas =
                    listOf(
                        entero("id", pk = 1),
                        texto("estado"),
                        texto("version_activa", nula = false),
                        texto("version_objetivo", nula = true),
                        entero("iniciado_en"),
                        entero("actualizado_en"),
                        entero("filas_totales", nula = false),
                        entero("filas_reconstruidas", nula = false),
                        entero("filas_pendientes", nula = false),
                        texto("ultimo_error_tipo", nula = true),
                    ),
            ),
        "HmacPendiente" to
            TablaNormativa(
                columnas =
                    listOf(
                        entero("id", pk = 1),
                        texto("entidad", nula = false),
                        entero("entidad_id", nula = false),
                        texto("columna", nula = false),
                        texto("version_origen", nula = false),
                        texto("motivo", nula = false),
                        entero("creado_en"),
                        entero("actualizado_en"),
                    ),
                indices = listOf(indice("entidad", "entidad_id", "columna", unico = true)),
            ),
    )
