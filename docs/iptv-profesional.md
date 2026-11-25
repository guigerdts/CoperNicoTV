# IPTV profesional: conexiones, EPG administrada y correcciones H-01..H-06

Este documento describe el alcance implementado en la rama
`feat/iptv-profesional-epg-conexiones`, candidata a `v1.0.0-beta.4`. Cubre los
métodos de conexión Xtream Codes y M3U/M3U8, la administración de guías XMLTV,
la asociación canal↔programa y las seis correcciones de la campaña física del
2026-11-23. El seguimiento por tarea vive en `odd/tasks/iptv-profesional.md`; la
evidencia física, en `docs/evidence/validaciones-fisicas.md`.

El repositorio y el producto no son un proveedor de contenido ni un agregador
de cuentas ajenas: la aplicación administra las conexiones que el usuario ya
tiene y no valida credenciales contra catálogos de terceros.

## 1. Método A — Xtream Codes

Se implementa exclusivamente con las rutas estándar del panel:

| Uso | Ruta |
|---|---|
| Comprobar credenciales y estado de la cuenta | `player_api.php` |
| Lista de canales | `get.php?type=m3u_plus&output=<m3u8\|ts>` |
| Guía expuesta por el panel | `xmltv.php` |

No se inventan endpoints ni se sondean rutas alternativas.

- **Datos**: nombre, URL del servidor, puerto, protocolo (`http`/`https`),
  usuario, contraseña, formato de salida (`m3u8` o `ts`) y URL de guía opcional.
  La URL de guía declarada por el usuario tiene prioridad sobre la generada por
  el panel (`ConexionXtream.urlGuiaEfectiva`).
- **Normalización**: el servidor se reduce a su host; se acepta que el usuario
  pegue `https://host:8080/c/`, pero se rechazan credenciales embebidas
  (`usuario:clave@host`), esquemas distintos de HTTP/HTTPS y puertos fuera de
  `1..65535`. Si el puerto viene embebido en el servidor y además hay campo
  explícito, gana el campo explícito.
- **Validación de respuestas**: la sonda (`SondaConexion`) usa timeouts, no
  sigue redirecciones de forma automática y valida el destino de cada salto
  antes de seguirlo. Clasifica `sin_conexion`, `timeout`, `http_4xx`, `http_5xx`,
  `dns_no_resuelto`, `conexion_rechazada`, `bloqueo_http`,
  `esquema_no_admitido` y `demasiadas_redirecciones`. Del cuerpo lee una muestra
  acotada: reconoce `#EXTM3U` en una lista y `"auth":0` en `player_api.php` sin
  descargar el catálogo completo.
- **Errores**: códigos estables (`conexion_*`) con mensaje ya redactado. HTTP 403
  se informa como `NO_AUTORIZADO`, no como error de cliente genérico.

## 2. Método B — M3U/M3U8

- **Datos**: nombre, URL de la lista (HTTP/HTTPS o SAF), URL de guía opcional,
  configuración de actualización (intervalo en horas, por defecto 24) y estado
  activo/inactivo.
- **Guía declarada**: el parser reconoce `url-tvg`, `x-tvg-url` y `tvg-url` en la
  cabecera `#EXTM3U` y publica la primera en `ResultadoParserM3U.urlGuia`. Si la
  lista no la declara no se asume nada: la guía queda sin configurar hasta que
  el usuario dé de alta una fuente. La lista física de la campaña no traía
  `url-tvg`, así que este camino tampoco se pudo ejercitar con ella.
- **Importación**: reutiliza el pipeline existente (`CatalogoRepositorio`), con
  parser tolerante y streaming (ADR-0006). Crear o editar una conexión no
  duplica el perfil ni cambia la lista activa.

## 3. Administración de conexiones

Desde `Fuentes → Conexiones` (`ConexionesScreen`), con un ViewModel que solo
consume `ConexionRepositorio`, `SondaConexion` y los programadores existentes:

- Crear, editar, eliminar con confirmación, activar/desactivar, probar,
  actualizar lista y actualizar guía.
- Estado por conexión, último resultado y último error.
- Múltiples conexiones simultáneas.
- **Materialización sobre el catálogo existente**: `ConexionEntity` guarda
  `fuenteId` y `listaId`; crear una conexión crea su `Fuente` y su `Lista`, y
  editar reutiliza la misma `Lista` (`ListaDao.actualizarUbicacion`). Así se
  conservan favoritos, historial, ocultos y asociaciones al actualizar o editar
  (S5).
- **Sin sorpresas de activación**: una conexión nueva no cambia la Lista activa
  si ya había otra. Solo si es la única Lista existente se activa, para que el
  catálogo tenga siempre una Lista activa.
- **Colisiones de nombre**: `Fuente.nombre` es único en toda la tabla; el
  repositorio detecta la colisión antes de insertar y también captura
  `SQLiteConstraintException`.

## 4. Credenciales

- Se cifran con AES-GCM autenticado y AAD por campo (`CifradoAes.aad(...)`),
  dentro de la fila de `Conexion`, separadas de las preferencias de DataStore.
- La clave raíz vive en Android Keystore; nunca se persiste material en claro.
- `toString()` de los modelos de conexión redacta usuario y contraseña; los
  errores y logs se construyen con `ConexionXtream.redactar(...)`, que elimina
  `password`, `username`, `usuario` y `contrasena`, además de credenciales
  embebidas en URLs.
- La UI nunca muestra la contraseña completa ni la rellena al editar: dejar el
  campo vacío conserva el secreto anterior.
- Eliminar una conexión borra sus secretos junto con la fila.
- No hay credenciales de prueba en el repositorio. El volcado
  `14-10_zonacero_lat.txt` es solo referencia de formato y no se versiona.

## 5. EPG/XMLTV administrada

### Alta y ciclo de vida

`Guía → Administrar guías` (`GuiasAdministracionScreen`) permite crear, editar,
eliminar, activar/desactivar y actualizar una `FuenteGuia`, con URL propia o
asociada a una conexión, estado, última actualización, último error e intentos.

- `origen = xtream|m3u`: la guía la creó una conexión y se elimina con ella.
- `origen = manual`: al eliminar la conexión solo se desvincula
  (`FuenteGuiaDao.desvincularConexion`).
- La URL puede ser independiente de la conexión y tiene su propio cifrado y
  sello HMAC.

### Descarga e importación

- Descarga a un fichero temporal; **nunca se materializa el documento**.
- XMLTV comprimido se detecta por **magic bytes** `1f 8b` tras la descarga, no
  por extensión ni cabecera, y se descomprime en streaming.
- `EpgRepositorio.importar` es `suspend`, recibe `InputStream` y hace
  persistencia por lotes con reemplazo atómico y limpieza de staging: no hay
  una escritura individual por canal ni se bloquea el pool.
- El parser incremental conserva `sub-title`, `category`, `episode-num` y
  `language`, respeta fechas y zonas horarias y tolera XML malformado, vacío,
  duplicados, solapamientos, descargas interrumpidas y HTML.
- **Un fallo no destruye la última guía válida**: `generacionPublicadaId` solo
  cambia cuando la importación termina bien.

### Actualización automática y reintentos

- Una sola programación periódica (`guia-automatica`, cada 6 h) revisa todas las
  fuentes activas con actualización automática y filtra por su
  `intervaloHoras` propio (24 h por defecto). «Actualizar ahora» encola un
  trabajo único por fuente.
- `PoliticaReintentoGuia` (en `:core`, con pruebas propias) espera 15 min, 1 h,
  6 h y 24 h, con un tope de 4 intentos automáticos; después el reintento es
  manual.
- `ProgramadorGuias.reconciliar()` se invoca al arranque
  (`InicializadorActualizaciones`) y desde la UI tras cada CRUD.

## 6. Asociación canal↔programa

- Coincidencia por `tvg-id` exacto y, cuando no hay id, por nombre normalizado.
- `PropuestaAsociacion` (en `:core`) puntúa candidatos y marca como ambiguas las
  que no superan el umbral; nunca asocia por semejanza superficial.
- Las decisiones se persisten y se pueden desasociar o reasociar; las
  asociaciones obsoletas se detectan y se muestran como pendientes.
- El selector de canales para asociar ya no carga la tabla completa: consulta
  solo los campos necesarios, pagina de 50 en 50 y busca con debounce de 250 ms,
  cancelando la consulta anterior (H-05).

## 7. UI de guía

- Actual/siguiente, progreso del programa actual (reloj de 30 s), categoría,
  episodio e idioma.
- Navegación por canales y vista temporal; la lista no se construye como un
  único `Compose` gigante: se pagina y se compone por filas.
- Estado de la fuente y de la asociación visibles, con acceso a administración.
- Móvil y D-pad: alturas mínimas de 48 dp y foco predecible.

## 8. Correcciones de la campaña física 2026-11-23

### H-01 — El Historial nunca se registraba en producción (alta)

- **Causa raíz**: `HistorialRepositorio.registrar(...)` no tenía ningún llamador
  en `app/src/main`; solo lo invocaban pruebas instrumentadas. `CatalogoViewModel`
  lo usaba únicamente para leer recientes. Reproducir un canal no escribía nada.
- **Corrección**: `RegistroHistorial` es una clase pura que consume solo el
  primer `EstadoReproductor.REPRODUCIENDO` de cada sesión y se reinicia al abrir
  una sesión nueva. `ReproductorViewModel` la reinicia en `abrirSesion(...,
  canalId)` y registra en el primer estado reproduciendo, de modo que no hay
  duplicados por recomposición.
- **Pruebas**: `RegistroHistorialTest` (7 casos: primer estado, estados
  repetidos, pausa y reanudación, nueva sesión, canal distinto).
- **Estado**: corrección lógica verificada con pruebas JVM. La comprobación
  física «reproducir → volver → aparece en Acceso rápido» queda pendiente en
  F-19.

### H-02 — `font_scale` 2.0 recortaba selectores (media)

- **Causa raíz**: las filas de controles usaban scroll horizontal sin wrap; a
  escala 2.0 el nombre de la lista, `Renombrar lista`, `Mostrar ocultos` y
  `Ajustes` quedaban cortados en los bordes.
- **Corrección**: `FlowRow` en `FilaControles`, `SelectorLista`, `FiltrosYOrden`
  y en las filas de acciones de `DialogoColecciones` y `DialogoPerfiles`, de
  forma que el contenido salta de línea en vez de recortarse. No se resuelve
  recortando texto.
- **Pruebas**: verificación estática y revisión de composición; la matriz física
  1.0/1.3/1.5/2.0 queda pendiente en F-19.

### H-03 — `Ajustes` fuera del área visible y sin indicador de scroll (media)

- **Causa raíz**: el último acceso de la fila de navegación quedaba con 56 px
  visibles y se alcanzaba con un swipe lento, sin ninguna señal visual.
- **Corrección**: además del wrap de H-02, `IndicadorDesplazamiento` muestra un
  aviso (`TAG_MAS_CONTROLES`) cuando `canScrollForward`, para que el contenido
  desplazable sea descubrible. El composable se movió a su propio fichero para
  no superar los umbrales de detekt.
- **Pruebas**: revisión de composición; matriz física pendiente en F-19.

### H-04 — Un worker sin trabajo reportaba `SUCCESS` (alta)

- **Causa raíz**: una solicitud manual coalescida con una ejecución en curso
  terminaba sin trabajo observable pero publicaba éxito, indistinguible de una
  actualización real. Además, el bucle de escritura no comprobaba cancelación
  entre Listas y lotes.
- **Corrección**: `DecisionEjecucion.SIN_TRABAJO` publica
  `resultado=sin_trabajo`; `DatosActualizacion` distingue `completado`,
  `sin_trabajo` y `fallido`; hay progreso por Lista y `ensureActive()` antes de
  cada Lista y cada lote, por lo que cancelar detiene el trabajo de verdad.
- **Pruebas**: casos de avance, cancelación y códigos de desenlace en los tests
  de actualización.
- **Nota de alcance**: la duplicación exacta de filas (`totalRows=238469` para
  119 532 canales importados) puede ser la suma de dos fuentes importadas
  (`zonacero` + `prueba-c2`) y no un duplicado por fuente; requiere verificación
  directa en F-20. Esta entrega corrige el reporte falso de éxito y la
  cancelación, no afirma haber medido el conteo final en dispositivo.

### H-05 — El selector de asociación cargaba el catálogo completo (alta)

- **Causa raíz**: `AsociacionRepositorio.canalesCatalogo()` ejecutaba
  `SELECT * FROM Canal ORDER BY nombre, id` y materializaba la tabla entera, con
  los blobs cifrados de cada canal. Con 238 469 filas el cursor tardaba minutos
  y saturaba el heap; al fallar, la UI mostraba error y estado vacío a la vez.
- **Corrección**: `CanalDao.opcionesDeAsociacion(consulta, limite)` selecciona
  solo los campos necesarios; `canalesCatalogo()` pagina con tope de 50;
  `GuiaViewModel.buscarCanales()` aplica debounce de 250 ms y cancela la
  búsqueda anterior. `MensajesGuia` muestra un único estado consistente con
  causa y botón de reintento (`TAG_REINTENTAR_GUIA`).
- **Pruebas**: pruebas de repositorio y de ViewModel; la medición con catálogo
  sintético grande queda pendiente en F-20.

### H-06 — No existía ruta de alta de una Fuente de guía (alta)

- **Causa raíz**: `E5-T5` se declaró implementado, pero ninguna ruta de
  `app/src/main` creaba una `FuenteGuiaEntity`. `EpgRepositorio` exige un
  `fuenteGuiaId` ya existente, así que la ingesta XMLTV era inalcanzable desde
  la app instalada. `GuiaUiTest:144` sembraba la fila directamente por DAO y por
  eso CI no detectaba la ausencia del alta.
- **Corrección**: `AdministracionGuiaRepositorio` y `GuiasAdministracionScreen`
  dan alta, edición, baja, activación y actualización de `FuenteGuia`; la guía
  puede venir de una conexión o ser manual; `ProgramadorGuias` ejecuta la
  actualización manual y automática con reintento controlado. El parser M3U
  publica `url-tvg` cuando existe.
- **Pruebas**: `AdministracionGuiaRepositorioTest` (11 casos),
  `ReintentoGuiaTest` (7 casos), `ParserM3UGuiaTest`.
- **Estado**: cierre lógico y de alcance. La prueba de punta a punta sin sembrar
  por DAO queda pendiente en F-18.

## 9. Persistencia: esquema v5

Migración **aditiva** `4→5`, sin pérdida de datos:

- Nueva tabla `Conexion` con secretos cifrados en la propia fila, `fuenteId` y
  `listaId` nulables.
- `FuenteGuia` gana origen, estado, `conexionId` (con índice), última
  actualización, último error, intentos, activa, actualización automática e
  intervalo propio. Sin FK para `conexionId` porque `ALTER TABLE` de SQLite no
  la admite; la integridad se mantiene en el repositorio.
- `Programa` gana subtítulo, categoría, `episode-num` e idioma.

Las columnas nuevas son nulables para que las filas v4 sobrevivan sin valores
por defecto. `MigracionDiagnosticoTest` y `EsquemaRoomTest` cubren la migración;
el esquema generado (`app/schemas/.../5.json`) lo produce KSP en CI.

## 10. Estado de validación

- Gate local ADR-0005 (`./gradlew ktlintCheck detekt :core:test`): verde tras
  cada commit de la rama, incluida la UI de T11.
- `:app` (compilación, Robolectric y pruebas instrumentadas) se valida **solo en
  CI**, porque AAPT2 x86-64 no corre en Termux.
- Sin evidencia física nueva en esta entrega. F-17..F-20 están **pendientes** y
  no se declara PASS ningún escenario que dependa de ellas.

## 11. Limitaciones conocidas

- La validación de Xtream se limita a las rutas estándar: un panel que solo
  exponga rutas no documentadas no se soporta.
- Detectar el `auth` del panel es una comprobación de credenciales, no de
  catálogo: una cuenta válida sin canales se informa como válida.
- La guía automática usa una única programación periódica cada 6 h; el intervalo
  por fuente se respeta al filtrar, así que una fuente con intervalo de 24 h no
  se actualiza exactamente a la hora, sino en la primera pasada que cumpla el
  intervalo.
- Un fallo de Keystore que inutilice el índice HMAC impide actualizar hasta
  restaurar claves; la guía publicada anterior se conserva.
- La verificación con `font_scale` 1.3/1.5/2.0, D-pad y catálogo de 100 000+
  canales exige dispositivo o emulador y sigue pendiente.
