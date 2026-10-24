# ADR-0003 — Parser XMLTV streaming con XmlPullParser (kxml2) en `:core`

Status: accepted

## Contexto

Los archivos XMLTV reales alcanzan cientos de MB y millones de elementos `<programme>`. Un parser DOM mantiene el documento completo en memoria: no escala. Además, Android no incluye StAX (`javax.xml.stream`), así que un parser StAX de JVM sería in-transportable a `:app` sin reescribirlo.

## Decisión

El parsing XMLTV es **incremental (streaming) con XmlPullParser (kxml2)**, disponible tanto en JVM como en Android, implementado en `:core`. El diseño separa tres fases con responsabilidades y límites propios:

### 1. Parseo incremental

- El parser emite eventos unitarios (una `Programación` parseada por vez) que el consumidor procesa a medida que llegan; el archivo nunca se carga entero en memoria.
- El XML malformado o incompleto **no aborta la importación completa si es recuperable**: un elemento inválido genera un error tipado (con su ubicación) y el parseo continúa con el siguiente. Los errores globales (encoding, EOF abrupto, raíz inválida) se reportan como error de importación con la cantidad de elementos recuperados y descartados.
- La **cancelación** es cooperativa: el parser consulta una señal de cancelación entre eventos y se detiene sin dejar estado inconsistente; la generación en staging se descarta y la guía publicada vigente permanece intacta (ver "Publicación controlada de la guía").

### 2. Persistencia por lotes

- Los elementos parseados se acumulan en **lotes de tamaño configurable** y se escriben en Room (ADR-0002) lote a lote, amortizando el costo transaccional.
- **Cada import escribe contra una nueva `generación de la guía`** (identificador de versión de los datos de EPG de esa Fuente de guía): los lotes de la importación en curso se etiquetan con esa generación (staging) y **nunca modifican la guía publicada vigente**.
- **No se promete memoria constante para el proceso completo**: el pico de memoria de trabajo es proporcional al tamaño del lote (configurable), no al tamaño del archivo. El almacenamiento persistido sí escala con el contenido (staging + guía publicada durante una import).

### 2b. Publicación controlada de la guía

- **Éxito**: al terminar la importación (parser completo, retención aplicada, lotes consolidados), la nueva generación se **publica atómicamente** actualizando el marcador de la Fuente de guía (único puntero: `generación publicada`). Las lecturas de la app siempre resuelven contra ese puntero, así que el cambio de guía es instantáneo y consistente sin reindexar UIs.
- **Cancelación o fallo global** (EOF abrupto, encoding inválido, error de disco, cancelación del usuario): la generación en staging **se abandona y se elimina** (los lotes ya escritos se descartan). La **última guía completa válida sigue publicada y es la que la app sigue mostrando**; una importación fallida nunca deja la guía a medio llenar, y el Estado de Actualización registra el fallo con la causa resumida.
- El descarte de la generación abandonada puede diferirse a una recolección de basura diferida si el dispositivo está presionado, pero nunca es visible por la app mientras el puntero de publicación no cambie.

### 2c. Caché de transporte XMLTV

- La caché es una **copia de transporte**, no la fuente de verdad de la guía. La UI lee exclusivamente la generación publicada.
- Una copia XMLTV anterior solo puede alimentar una **actualización explícita** que atraviese parseo, validación, retención y publicación atómica. Una importación fallida o parcial no sustituye la generación publicada.
- Los metadatos de la caché viven en Room (ADR-0002) y el contenido en archivos privados; `expires_at` indica frescura y no inicia descargas por sí mismo.
- El presupuesto de la caché es independiente del presupuesto de la generación publicada y de la ventana de retención; su valor numérico queda pendiente. La evicción elimina primero entradas inválidas o `stale` y luego las de `fetched_at` más antiguo.
- La caché se incorpora al inventario de D5: URLs, contenido, archivos temporales, logs y errores pueden contener credenciales; cifrado, claves, backup y borrado seguro siguen pendientes.

### 3. Retención temporal

Tres límites configurables (valores globales por defecto en DataStore; excepciones por Fuente en Room, ADR-0002:48–50), con **valores iniciales de especificación** documentados. Son valores normativos iniciales del MVP, pendientes de calibración con datos reales; **no están implementados ni validados experimentalmente**.

- **Ventana temporal**: se conserva la Programación que se solape con un intervalo relativo al momento de importación de cada FuenteGuia: **1 día de pasado y 7 días de futuro**. Un programa se conserva si su fin es posterior al inicio de la ventana y su inicio es anterior al fin de la ventana. La retención se aplica durante la importación, antes de la publicación atómica; esta decisión no introduce una purga periódica automática.
- **Límite por canal**: máximo de **500 programas por `(fuente_guia_id, channel_id_texto)`** en la generación publicada. Al superarlo, se descarta primero la Programación más antigua dentro de esa asociación. Es un límite de diseño pendiente de calibración con datos reales, especialmente para guías con intervalos de 15 minutos.
- **Presupuesto total**: máximo de **200 MB por FuenteGuia** para la generación publicada, medido como **tamaño lógico serializado** de los `Programa` retenidos, no como espacio físico de SQLite. Se calcula sumando, por cada `Programa`, los bytes UTF-8 de `channel_id_texto`, título y descripción, más 8 bytes por `inicio` y 8 por `fin`, más un prefijo de longitud de 4 bytes por cada campo variable (0 bytes de contenido si el campo es `NULL`). Se excluyen PK, FK de generación, índices, páginas SQLite, cabeceras de fila y metadatos de publicación; el valor se calcula incrementalmente antes de publicar. Al superar el límite, se evicta primero la Programación más antigua dentro de esa fuente. No es un límite global del dispositivo ni garantiza que todas las fuentes puedan consumir 200 MB simultáneamente. El **staging** de una importación queda fuera de este presupuesto, pero consume espacio físico adicional mientras la generación publicada sigue vigente; antes de importar se verifica que el espacio libre alcance al menos el archivo de transporte y el presupuesto de la generación publicada; cuando se defina el presupuesto numérico de la caché de transporte XMLTV, la comprobación deberá incluir también ese presupuesto aplicable. Un espacio insuficiente produce un error tipado. La **caché de transporte XMLTV** tiene un presupuesto independiente, aún sin valor numérico decidido, y su política de evicción elimina primero entradas inválidas o `stale` y luego las de `fetched_at` más antiguo; no se computa contra el presupuesto de la generación publicada.

La resolución de cada límite es: valor explícito de la Fuente si existe; si no, el global de DataStore. Cambiar el global no corrompe las excepciones por Fuente (ADR-0002:48–50).

## Consecuencias

- Las pruebas cubren: XML malformado, datos incompletos, cancelación a mitad de parseo, archivos grandes sintéticos y consumo de memoria razonable; nunca dependen de archivos reales de terceros ni de contenido cuya distribución no esté autorizada.
- Las pruebas de retención cubren los tres límites, la resolución global/por Fuente y la evicción oldest-first; los valores iniciales no se consideran validados hasta calibrarlos con datos reales.
- Las pruebas de publicación cubren: importación que falla a mitad de camino conserva la guía publicada anterior íntegra; importación exitosa publica la nueva generación atómicamente y la anterior queda fuera del puntero (y es candidata a recolección).
- Las pruebas de la caché de transporte cubren: frescura por tiempo sin descarga automática, invalidación por actualización/cambio de URL/eliminación del propietario, barrido de huérfanos y errores de borrado, y que una copia antigua o una importación fallida nunca sustituye la generación publicada.
- El consumo desde `:app` es incremental (Flow perezoso), nunca listas completas.
- Agregar un segundo formato de guía (futuro) no obliga a reescribir el pipeline: solo un parser nuevo que emita los mismos eventos.

## Opciones consideradas

- **StAX**: descartado — estándar de JVM pero ausente en Android; exigiría mantener dos parsers o uno no portable.
- **DOM**: descartado — memoria O(tamaño del archivo), inviable con XMLTV reales.
- **Parser hand-rolled propio**: descartado — complejidad innecesaria para un formato estándar ya cubierto por XmlPullParser.
