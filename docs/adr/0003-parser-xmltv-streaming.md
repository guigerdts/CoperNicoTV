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

### 3. Retención temporal

Tres límites configurables (vía DataStore, ADR-0002), con valores por defecto documentados:

- **Ventana temporal**: descarta la Programación fuera de un intervalo relativo al momento de importación (p. ej., pasado reciente + futuro próximo).
- **Límite por canal**: cantidad máxima de Programación conservada por Canal.
- **Presupuesto total**: volumen máximo que la EPG puede ocupar; al excederlo se evicta la Programación más antigua primero.

## Consecuencias

- Las pruebas cubren: XML malformado, datos incompletos, cancelación a mitad de parseo, archivos grandes sintéticos y consumo de memoria razonable; nunca dependen de archivos reales de terceros ni de contenido cuya distribución no esté autorizada.
- Las pruebas de publicación cubren: importación que falla a mitad de camino conserva la guía publicada anterior íntegra; importación exitosa publica la nueva generación atómicamente y la anterior queda fuera del puntero (y es candidata a recolección).
- El consumo desde `:app` es incremental (Flow perezoso), nunca listas completas.
- Agregar un segundo formato de guía (futuro) no obliga a reescribir el pipeline: solo un parser nuevo que emita los mismos eventos.

## Opciones consideradas

- **StAX**: descartado — estándar de JVM pero ausente en Android; exigiría mantener dos parsers o uno no portable.
- **DOM**: descartado — memoria O(tamaño del archivo), inviable con XMLTV reales.
- **Parser hand-rolled propio**: descartado — complejidad innecesaria para un formato estándar ya cubierto por XmlPullParser.
