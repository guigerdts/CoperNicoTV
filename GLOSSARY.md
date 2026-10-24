# CoperNicoTV

Glosario del proyecto: reproductor y administrador de contenido IPTV para Android y Android TV. El usuario aporta las Fuentes; la aplicación importa, organiza y reproduce. Este documento solo define vocabulario de dominio; Las decisiones técnicas viven en `docs/adr/`.

## Language

### Fuentes y catálogo

**Fuente**:
Agrupación lógica de una o varias Listas que comparten configuración (por ejemplo, actualización y excepciones de retención). No es la ubicación de importación: cada Lista conserva la suya. No presupone un proveedor comercial ni un número fijo de Listas; una Lista puede ser un archivo local sin proveedor detrás. Queda pendiente decidir si una Fuente puede agrupar Listas de orígenes distintos.
_Avoid_: proveedor, servidor, provider, origen

**Lista**:
Documento M3U/M3U8 importado y administrado por la aplicación. Conserva su propia ubicación de importación: URL HTTP/HTTPS o URI SAF `content://`. Es una entidad de la aplicación, no la Fuente que la contiene.
_Avoid_: playlist, M3U (como nombre de la entidad de dominio), fuente

**Canal**:
Recurso reproducible declarado por una Lista. Su nombre es opcional: `NULL` significa que la entrada importada no declaró nombre. «Sin nombre» es solo una etiqueta de presentación; nunca se persiste como nombre ni se usa como identidad. Una URL de stream es un dato del Canal, no el Canal.
_Avoid_: stream, playlist entry

**Clave de Canal**:
Identificador determinista que representa a un Canal dentro del ámbito de una Fuente. Deriva de `tvg-id` cuando la Lista lo trae y resulta único en ese ámbito; si no, de una clave canónica documentada construida a partir del nombre normalizado y la URL del stream. La clave es lógica: no es la clave primaria interna de persistencia. Cuando la entrada no declara nombre, la derivación de la clave canónica queda explícitamente pendiente, incluida la desambiguación de colisiones entre entradas sin nombre; no se usa «Sin nombre» como insumo.
_Avoid_: ID, hash (como término de dominio), UUID

**Grupo**:
Categoría que la Lista asigna a canales (`group-title` / `EXTGRP`). Los Grupos provienen de la Lista; la aplicación no los inventa salvo que la Lista no provea ninguno. El Grupo tiene ámbito de Lista: el mismo nombre de grupo en dos Listas distintas denota grupos distintos.
_Avoid_: categoría, folder

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
Registro de reproducciones pasadas que habilita el acceso rápido a los últimos canales.

**Actualización**:
Reimportación de una Fuente (manual o programada) cuyo resultado reconcilia canales contra claves ya conocidas, preservando Favoritos, Historial y Ocultaciones cuando es posible.

**Reconciliación**:
Proceso de la Actualización que determina si dos referencias a un canal (pre y post-actualización) denotan el mismo Canal. Nunca fusiona canales ambiguos automáticamente.

**Estado de Actualización**:
Resultado de la última Actualización de una Fuente (satisfactoria, fallida, en curso, nunca ejecutada). Es un hecho sobre el proceso de importación.

**Disponibilidad de Reproducción**:
Capacidad de un Canal de reproducir su stream en un momento dado. Es independiente del Estado de Actualización: una Actualización fallida no implica que los streams dejaron de responder, y al contrario.

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
Vínculo configurable entre un Canal y los datos de EPG correspondientes. Es explícita cuando la Fuente no provee el vínculo de forma automática o inequívoca.

**Retención de EPG**:
Política configurable que limita cuánta Programación se conserva (ventana temporal, volumen máximo por canal, tamaño total del almacenamiento de la guía).

### Verificación

**Verificación automatizada**:
Prueba ejecutada por el entorno o en CI sin intervención humana; resultado binario.

**Verificación física**:
Procedimiento que exige un dispositivo real (teléfono o TV) y un operador humano. Siempre se documenta con procedimiento exacto y resultado esperado.

**Verificación pendiente**:
Verificación física requerida y aún no ejecutada por falta de dispositivo. Estado explícito y registrado; nunca impide declarar una tarea terminada, pero sí bloquea declarar el MVP validado en conjunto.
