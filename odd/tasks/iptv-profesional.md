# IPTV profesional: conexiones, EPG completa y correcciones H-01..H-06

Objetivo: entregar en una única rama y un único PR el reproductor IPTV profesional
(conexiones Xtream Codes y M3U/M3U8, EPG/XMLTV completa, asociación resuelta,
historial real, actualización estable y UX accesible) sin recortar alcance.

Rama: `feat/iptv-profesional-epg-conexiones` (desde `main` en `b307d6f`).
Entrega: PR único; estrategia `single-pr` con `size:exception` explícita del
propietario para esta entrega (H-01..H-06 no se separan).
Runner de pruebas: `./gradlew ktlintCheck detekt :core:test` (ADR-0005; `:app`
se compila y prueba en CI, nunca en Termux).

## Specs

### Conexiones

- **S1**. Debe existir el Método A Xtream Codes con: «nombre, URL servidor,
  puerto, protocolo HTTP/HTTPS, usuario, contraseña, formato de salida
  configurable, URL EPG/XMLTV opcional (prioridad sobre la generada)».
- **S2**. El adaptador Xtream es desacoplado y «valida respuestas, redirecciones,
  timeouts y errores»; no se inventan endpoints: solo se usan las rutas
  estándar del proveedor (`player_api.php`, `get.php`, `xmltv.php`).
- **S3**. Debe existir el Método B M3U/M3U8 con «nombre, URL M3U/M3U8, URL
  EPG/XMLTV opcional, configuración de actualización, activación»; la EPG es
  independiente y se detecta `url-tvg` o equivalente «sin asumir presencia».
- **S4**. Administración de conexiones: «crear, editar, eliminar con confirmación,
  activar/desactivar, probar conexión, consultar estado, actualizar lista,
  actualizar guía, último resultado, múltiples conexiones».
- **S5**. Al actualizar se conservan «favoritos, historial, ocultos y
  asociaciones»; no se cambia la fuente activa ni se duplican perfiles.
- **S6**. Credenciales: secretos con Android Keystore y cifrado autenticado;
  «nunca en texto plano»; separadas de preferencias; no se muestran contraseñas
  completas guardadas; se redactan logs, excepciones y reportes; se validan
  esquemas, hosts y redirecciones; se eliminan los secretos al borrar la
  conexión; el respaldo no exporta secretos; nunca hay credenciales de prueba
  en el repositorio.

### EPG / XMLTV

- **S7**. EPG obligatoria y completa: «alta, edición, eliminación, activación,
  URL independiente, asociación con conexión, actualización manual y automática
  configurable, última actualización, estado, diagnóstico del último error,
  reintento controlado»; XMLTV comprimido si es compatible; «nunca materializar
  el documento completo».
- **S8**. Parser XMLTV incremental de `<tv>`, `<channel>`, `<display-name>`,
  `<icon>`, `<programme>`, `start`, `stop`, `channel`, `title`, `sub-title`,
  `desc`, `category`, `episode-num`, `language`; respeta fechas y zonas
  horarias; tolera XML malformado, vacío, sin id, sin hora válida, duplicados,
  solapados, descargas interrumpidas, HTML en lugar de XML y fuentes no
  disponibles; «no destruir la última guía válida».
- **S9**. Persistencia EPG por lotes y transacciones controladas, sin
  actualización individual costosa por canal, sin bloquear el pool, sin cargar
  todo en memoria, con índices adecuados y «reemplazo atómico (no borrar la
  guía anterior hasta que la nueva esté procesada)».
- **S10**. Asociación por id exacto, nombre normalizado, propuestas con
  puntuación, resolución manual de ambiguas, desasociación/reasociación,
  persistencia de decisiones, conservación al actualizar la M3U y detección de
  obsoletas; «no asociar automáticamente por semejanza superficial»; las dudosas
  quedan pendientes de confirmación (H-05).
- **S11**. UI EPG con programación actual y siguiente, horarios, progreso,
  descripción, categoría, navegación por canales y temporal, estado de fuente y
  asociación, acceso a configuración/actualización; móvil táctil y TV D-pad con
  foco visible; «no usar una lista Compose gigante».

### Correcciones de campaña física

- **S12** (H-01, riesgo alto). El historial se registra «conectar
  `HistorialRepositorio.registrar()` al evento real de reproducción Media3»,
  solo cuando la reproducción realmente empieza, sin duplicados por
  recomposición, con reconexiones definidas, último acceso persistido y pruebas
  por el flujo real de producción.
- **S13** (H-04). Un worker que no hizo trabajo no debe reportar `SUCCESS`;
  se distinguen éxito, sin trabajo, cancelación, reintento, fallo recuperable y
  fallo permanente; actualización por lotes con progreso y cancelación real;
  navegación disponible durante la actualización.
- **S14** (H-05). Se elimina `SELECT * FROM Canal ORDER BY nombre, id` del
  selector de asociación; consultas específicas con solo los campos necesarios,
  búsqueda incremental, resultados acotados, paginación y cancelación de
  consultas obsoletas.
- **S15** (H-02/H-03). Se corrige el recorte con `font_scale = 2.0` del selector
  de listas, la acción `Mostrar ocultos`, `Ajustes` fuera del área visible y la
  falta de indicador de scroll; se prueba 1.0/1.3/1.5/2.0 en móvil y D-pad
  donde el entorno lo permita; no se resuelve recortando texto.

### Transversales

- **S16**. Arquitectura: Kotlin, Compose, Material 3, Compose for TV, Media3,
  Room, DataStore, Coroutines/Flow, Hilt, MVVM, modularidad y Version Catalog;
  sin duplicar servicios ni añadir bibliotecas sin justificar.
- **S17**. Pruebas: unitarias de dominio y flujos completos por la interfaz
  pública; rendimiento con datos sintéticos (119 532 canales, XMLTV grandes,
  concurrencia, cancelación); no se declara validado por solo compilar.
- **S18**. Documentación: se conservan H-01..H-06 con causa raíz, decisión de
  arquitectura, corrección, pruebas, evidencia, estado y riesgos residuales;
  se actualizan `docs/evidence/validaciones-fisicas.md`,
  `odd/tasks/ota-actualizaciones.md`, docs EPG, README y ADR; sin PASS sin
  evidencia y sin credenciales ni URLs sensibles.
- **S19**. Entrega: una rama, un PR, H-01..H-06 juntos; antes del PR se revisa el
  diff completo y la ausencia de credenciales; checks obligatorios verdes ⇒
  merge inmediato con merge commit; prohibido squash, rebase y force push.

## Tasks

| ID | Specs | Ruta | Estado | Evidencia |
| --- | --- | --- | --- | --- |
| T1 | S1, S2, S6 | inline | implementado | `core/.../conexion/*`, `ConexionXtreamTest` |
| T2 | S3 | inline | implementado | `ParserM3U.urlGuia`, `ParserM3UGuiaTest` |
| T3 | S8 | inline | implementado | `ProgramaXmltv` enriquecido, `ParserXmltvEnriquecidoTest` |
| T4 | S10 | inline | implementado | `core/epg/PropuestaAsociacion.kt`, `PropuestaAsociacionTest` |
| T5 | S7, S9 | inline | pendiente | Room 4→5 |
| T6 | S4, S5, S6 | inline | pendiente | repositorio de conexiones |
| T7 | S7 | inline | pendiente | CRUD FuenteGuia + worker EPG |
| T8 | S13 | inline | pendiente | Coordinador/Worker actualización |
| T9 | S14 | inline | pendiente | selector paginado |
| T10 | S12 | inline | pendiente | historial Media3 |
| T11 | S11, S15 | inline | pendiente | UI conexiones + EPG + escalas |
| T12 | S18, S19 | inline | pendiente | docs, gates, PR |

## Log

### L1 — Encargo del propietario (texto recibido)

> Implementar en CoperNicoTV la mejora integral IPTV profesional (H-01 a H-06 +
> Xtream Codes + M3U/M3U8 + EPG/XMLTV completa + estabilidad Room/WorkManager +
> historial real + UX) en una única rama y un único PR, candidato a
> `v1.0.0-beta.4`.
>
> Método A Xtream Codes: nombre, URL servidor, puerto, protocolo HTTP/HTTPS,
> usuario, contraseña, formato de salida configurable, URL EPG/XMLTV opcional
> (prioridad sobre la generada); adaptador desacoplado; validar respuestas,
> redirecciones, timeouts y errores; no inventar endpoints.
>
> Método B M3U/M3U8: nombre, URL M3U/M3U8, URL EPG/XMLTV opcional, configuración
> de actualización, activación; EPG independiente; detectar `url-tvg` o
> equivalentes y proponer su uso sin asumir presencia.
>
> Credenciales: secretos con Android Keystore y cifrado autenticado; nunca en
> texto plano; separadas de preferencias; no mostrar contraseñas completas
> guardadas; redactar logs/excepciones/reportes; validar esquemas, hosts y
> redirecciones; eliminar secretos al borrar la conexión.
>
> EPG/XMLTV obligatoria y completa (H-05/H-06 prioritarios): alta, edición,
> eliminación, activación, URL independiente, asociación con conexión,
> actualización manual y automática configurable, última actualización, estado,
> diagnóstico del último error, reintento controlado.
>
> H-01 historial: conectar `HistorialRepositorio.registrar()` al evento real de
> reproducción Media3; registrar solo cuando la reproducción realmente empieza;
> sin duplicados por recomposición.
>
> H-04: un worker que no hizo trabajo no debe reportar SUCCESS.
>
> H-05: eliminar `SELECT * FROM Canal ORDER BY nombre, id` del selector de
> asociación.
>
> H-02/H-03: corregir selector de listas recortado con `font_scale = 2.0`,
> acción `Mostrar ocultos` recortada, `Ajustes` parcialmente fuera del área
> visible, ausencia de indicador de scroll.
>
> Regla permanente de merge: con checks obligatorios verdes, sin conflictos,
> rama actualizada, diff revisado y alcance/documentación coherentes ⇒ merge
> inmediato con merge commit, sin pedir otra autorización; prohibido squash,
> rebase y force push.
>
> Ejecución: no detenerse en plan o diseño; no preguntar si continuar con el
> siguiente bloque; no inventar resultados.

### L2 — Estado inicial verificado

`main` local = `origin/main` = GitHub HEAD = `b307d6f`
(`Merge pull request #58 …`). Árbol limpio salvo 14 rutas untracked de campañas
físicas y el archivo de referencia de cuentas, que no se tocan.

### L3 — Hallazgos del relevamiento

- `FuenteGuiaDao` no tiene alta/edición/estado y ninguna pantalla crea
  `FuenteGuia`: E5-T5 se declaró implementado sin ruta de producción (H-06).
- `CanalDao.todos()` sigue siendo `SELECT * FROM Canal ORDER BY nombre, id`
  (H-05) y es lo que consume el selector de asociación.
- `CoordinadorActualizacion` traduce `Coalescido` a `DecisionEjecucion.EXITO`
  (H-04).
- `HistorialRepositorio.registrar()` existe pero ninguna reproducción lo invoca
  (H-01).
- `ParserM3U` no expone `url-tvg`; `ProgramaXmltv` no tiene subtítulo,
  categoría, episode-num ni idioma.
- `FechaXmltv` ya respeta offsets y asume UTC sin offset: no requiere cambio.
