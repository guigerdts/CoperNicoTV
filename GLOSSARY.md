# CoperNicoTV

Glosario del proyecto: reproductor y administrador de contenido IPTV para Android y Android TV. El usuario aporta las Fuentes; la aplicación importa, organiza y reproduce. Este documento solo define vocabulario de dominio; Las decisiones técnicas viven en `docs/adr/`.

Vigencia L26 (2026-10-24): las definiciones de Fuente, Lista, Perfil, FuenteGuia, Canal, Clave de Canal, Apariencia, Asociación, Caché de transporte y Retención de EPG se actualizaron conforme a DC-01–DC-11; el resto del glosario se conserva.

## Language

### Fuentes y catálogo

**Fuente**:
Agrupación lógica de una o varias Listas que comparten configuración de actualización. No es la ubicación de importación: cada Lista conserva la suya. No presupone un proveedor comercial ni un número fijo de Listas; una Lista puede ser un archivo local sin proveedor detrás. Puede agrupar Listas con orígenes HTTP/HTTPS y SAF `content://`; no presupone que compartan contenido y cada Lista se actualiza de forma independiente. La retención de EPG se configura por `FuenteGuia`, no por Fuente.
_Avoid_: proveedor, servidor, provider, origen

**Lista**:
Documento M3U/M3U8 importado y administrado por la aplicación. Conserva su propia ubicación de importación cifrada en reposo (`ubicacion_cifrada`/`ubicacion_hmac`): URL HTTP/HTTPS o URI SAF `content://`. Es una entidad de la aplicación, no la Fuente que la contiene. Su PK no se reutiliza (`AUTOINCREMENT`) y su preferencia de orden vive en DataStore con la PK como clave.
_Avoid_: playlist, M3U (como nombre de la entidad de dominio), fuente

**FuenteGuia**:
Documento XMLTV que actúa como fuente de guía. Su `channel id` tiene ámbito del import de guía del que proviene y la retención de EPG se configura por `FuenteGuia`, no por Fuente. Tiene su propia ubicación cifrada y su propio presupuesto de caché (256 MiB totales, 96 MiB por `FuenteGuia` y archivo; DC-03/DC-09).
_Avoid_: fuente XMLTV genérica, guía global

**Perfil**:
Agrupación con nombre de Listas M3U mediante la relación n—n `PerfilLista`. El nombre es único global sin distinguir mayúsculas; un Perfil puede estar vacío y una Lista puede no pertenecer a ningún Perfil. No es la Lista activa renombrada y no posee preferencias de reproducción: esas preferencias son globales. Borrar un Perfil o una Lista elimina solo sus pertenencias; no se duplican Perfiles en el MVP (DC-02).
_Avoid_: perfil de usuario, cuenta

**Canal**:
Recurso reproducible declarado por una Lista. Su nombre es opcional: `NULL` significa que la entrada importada no declaró nombre. «Sin nombre» es solo una etiqueta de presentación; nunca se persiste como nombre ni se usa como identidad. Un Canal sin nombre no tiene `clave_canonica`; si la entrada declara un `tvg-id` válido y único en la Fuente, `clave_derivable = 1` y `clave_unicidad = "tvg:v1:" + tvg_id` en estado A; si no, `clave_derivable = 0` y `clave_unicidad = NULL` (DC-01). Una URL de stream es un dato del Canal, no el Canal; se almacena cifrada en reposo.
_Avoid_: stream, playlist entry

**Clave de Canal**:
Identificador determinista que representa a un Canal dentro del ámbito de una Fuente. Deriva de `tvg-id` cuando la Lista lo trae y resulta único en ese ámbito; si no, de una clave canónica documentada construida a partir del nombre normalizado y la URL del stream. La clave es lógica: no es la clave primaria interna de persistencia. Cuando la entrada no declara nombre, no se deriva clave canónica: `clave_canonica` es NULL. Si hay un `tvg-id` válido y único en la Fuente, `clave_derivable = 1` y `clave_unicidad = "tvg:v1:" + tvg_id` en estado A; si no, `clave_derivable = 0` y `clave_unicidad = NULL`. La URL normalizada no es identidad canónica por sí sola y «Sin nombre» no es insumo (DC-01).
_Avoid_: ID, hash (como término de dominio), UUID

**Grupo**:
Categoría que la Lista asigna a canales (`group-title` / `EXTGRP`). Los Grupos provienen de la Lista; la aplicación no los inventa salvo que la Lista no provea ninguno. El Grupo tiene ámbito de Lista: el mismo nombre de grupo en dos Listas distintas denota grupos distintos.
_Avoid_: categoría, folder

**Apariencia**:
Aparición de un Canal en una Lista; conserva el orden dentro de la Lista y el Grupo asignado por esa Lista. La misma identidad de Canal con una tupla `(group_title, tvg_name)` distinta produce varias Apariencias. El duplicado exacto se colapsa por `clave_presentacion` (identidad + tupla tras trim exterior; `NULL ≠ ""`), con índice único `(lista_id, canal_id, clave_presentacion)`; el orden no forma parte de la clave y la primera aparición conserva el orden (DC-05).
_Avoid_: ocurrencia duplicada, canal repetido

**Catálogo**:
Conjunto de canales visibles para el usuario en un momento dado, producto de las Listas importadas y de las selecciones del usuario (Favoritos, Ocultaciones, Colecciones, orden).
_Avoid_: biblioteca, contenido

**Favorito**:
Selección persistente que el usuario toma sobre un Canal o un conjunto de canales para darle acceso rápido. Es estado de la aplicación (decisión del usuario), no un dato de la Lista ni de la Fuente, y sobrevive a las Actualizaciones; se guarda anclada a la Clave de Canal, no a la posición de la Lista.

**Colección**:
Vista u ordenamiento de canales definido por el usuario, independiente del orden de la Lista y de la pertenencia a grupos. Se distingue de una Lista importada: una Lista proviene de la Fuente y sus contenidos cambian con la Actualización; una Colección es estado de la aplicación, se construye desde el Catálogo y no proviene de ninguna Fuente. _Avoid_: lista personalizada (confundiría con Lista), playlist propia

**Ocultación**:
Decisión del usuario de excluir del Catálogo un Canal o un Grupo sin borrar el contenido subyacente. La ocultación de un Canal afecta a ese Canal según su identidad lógica; la ocultación de un Grupo se limita al Grupo concreto dentro de su Lista y no afecta a grupos homónimos de otras Listas.
_Avoid_: bloqueo, filtro

**Historial**:
Estado de la aplicación que conserva, por Canal, la fecha de su última reproducción para habilitar el acceso rápido a los últimos canales. No es un registro de cada reproducción: hay una fila por Canal, garantizada por `UNIQUE(canal_id)`. Al fusionar dos Canales que pasan a representar la misma identidad canónica, se conserva `max(fecha)` y no se duplican filas. La consulta de últimos N usa el orden determinista `fecha DESC, canal_id DESC`; ese orden no es el de la organización del Catálogo.

**Actualización**:
Reimportación de una Fuente (manual o programada) cuyo resultado reconcilia canales contra claves ya conocidas, preservando Favoritos, Historial y Ocultaciones cuando es posible.

**Reconciliación**:
Proceso de la Actualización que determina si dos referencias a un canal (pre y post-actualización) denotan el mismo Canal. Nunca fusiona canales ambiguos automáticamente.

**Estado de Actualización**:
Resultado de la última Actualización de una Fuente (satisfactoria, fallida, en curso, nunca ejecutada). Es un hecho sobre el proceso de importación.

**Disponibilidad de Reproducción**:
Capacidad de un Canal de reproducir su stream en un momento dado. Es independiente del Estado de Actualización: una Actualización fallida no implica que los streams dejaron de responder, y al contrario.

**Caché de transporte**:
Copia local del contenido descargado (M3U o XMLTV) para evitar descargas repetidas y para servir una copia marcada `stale` cuando la actualización falla o la copia venció. No es la fuente de verdad del catálogo ni de la guía: los metadatos viven en Room y el contenido en archivos privados de la aplicación con nombres opacos; las columnas URL se cifran y se indexan por HMAC (DC-04). Presupuesto: `CacheM3U` 64 MiB totales/16 MiB por Lista y archivo; `CacheXmltv` 256 MiB totales/96 MiB por `FuenteGuia` y archivo; global 320 MiB con 100 MiB de reserva libre; evicción de inválidas/stale y luego `fetched_at` más antiguo; archivos en uso protegidos; temp + rename atómico; última copia válida conservada (DC-09). La frescura de la caché es independiente del Estado de Actualización y de la Disponibilidad de Reproducción.

### Reproducción

**Reproducción en vivo (live)**:
Reproducción de un stream en curso, no pregrabado. _Avoid_: directo

**VOD**:
Contenido bajo demanda que la Fuente expone como reproducible. _Avoid_: película (independiente del tipo de contenido)

**Zapeo**:
Cambio rápido de Canal que ocurre sin volver al Catálogo. _Avoid_: surfing

**Diagnóstico de reproducción**:
Información técnica sobre el stream en curso (protocolo, pistas, buffer, errores) expuesta en la interfaz.

### Guía (EPG)

**EPG**:
Información de programación horaria asociada opcionalmente a canales.

**XMLTV**:
Formato de documento desde el cual se importa la EPG. Es un formato de intercambio, no sinónimo de EPG.
_Avoid_: "la XMLTV" (como nombre de la guía)

**Programación**:
Un programa individual de la guía con título, horarios y descripción.

**Asociación**:
Vínculo configurable entre un Canal y los datos de EPG correspondientes, representado por `(fuente_guia_id, channel_id_texto)` sin `CanalXmltv`. Es explícita cuando la Fuente no provee el vínculo de forma automática o inequívoca. Tiene huella versionada y estados `propuesta`, `confirmada`, `sospechosa`, `ausente` y `revocada`; `sospechosa` oculta la EPG y exige reconfirmación; `ausente` conserva la Asociación confirmada y se reactiva si la huella coincide; el borrado manual es `revocada` y la heurística no la recrea (DC-06).

**Retención de EPG**:
Política configurable que limita cuánta Programación se conserva (ventana temporal, volumen máximo por canal, tamaño total del almacenamiento de la guía). Las excepciones se configuran por `FuenteGuia`, no por Fuente; los valores globales viven en DataStore. Valores iniciales de especificación: 1 día de pasado y 7 de futuro, 500 programas por `(fuente_guia_id, channel_id_texto)` y 200 MB lógicos por `FuenteGuia`; son valores iniciales pendientes de calibración, no mediciones (DC-03).

### Verificación

**Verificación automatizada**:
Prueba ejecutada por el entorno o en CI sin intervención humana; resultado binario.

**Verificación física**:
Procedimiento que exige un dispositivo real (teléfono o TV) y un operador humano. Siempre se documenta con procedimiento exacto y resultado esperado.

**Verificación pendiente**:
Verificación física requerida y aún no ejecutada por falta de dispositivo. Estado explícito y registrado; nunca impide declarar una tarea terminada, pero sí bloquea declarar el MVP validado en conjunto.

## Enmienda L26 (2026-10-24)

Actualizadas las definiciones de Fuente, Lista, Perfil, FuenteGuia, Canal, Clave de Canal, Apariencia, Asociación, Caché de transporte y Retención de EPG conforme a DC-01–DC-11 y U0 §6.2/§6.3. El resto del glosario se conserva. No se modificó `docs/evidence/u0-manifest.md`.
