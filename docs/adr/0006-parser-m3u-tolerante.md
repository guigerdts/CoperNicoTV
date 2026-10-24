# ADR-0006 — Parser M3U streaming y tolerante a fallos

Status: accepted

## Contexto

Las listas M3U reales están regularmente malformadas: entradas incompletas, atributos con comas dentro de comillas o fuera de ellas, líneas desconocidas después del encabezado `#EXTM3U`, fin de línea CRLF/LF mixtos, BOM, encodings variados. Un parser estricto descarta listas completas por una sola línea incorrecta — inaceptable para los datos reales de este dominio.

## Decisión

El parser M3U (en `:core`, Kotlin puro, ADR-0001) es **streaming y tolerante a fallos**:

- Procesa el documento línea a línea sin cargarlo entero en memoria.
- Una entrada malformada genera un **error tipado por entrada** (con ubicación) y la entrada se salta; la lista **no** se descarta en bloque si es recuperable de forma segura.
- Cubre: atributos extendidos (`tvg-id`, `tvg-name`, `tvg-logo`, `group-title`, etc.), `EXTGRP`, comillas simples/dobles, comas dentro de atributos delimitadas por comillas, líneas desconocidas conservadas como informativas no-fatales, BOM y CRLF/LF.
- Las entradas incompletas (EXTINF sin URL de stream) generan un error tipado y no se agregan al Catálogo.
- El resultado de un parse es: (catálogo parcial recuperado, lista de errores tipados) — nunca `null` ni excepción global cuando hubo recuperación posible.

**Límites defensivos** (protegen la memoria; ninguno depende del *número de canales*, así que una lista grande y válida nunca se rechaza por tamaño del catálogo):

- **Longitud de línea**: una línea que excede el límite configurable (por defecto, suficientemente grande para URIs legítimas, p. ej. 4 KiB) se descarta con error tipado por línea; evita que una sola línea corrupta o maliciosa agote la memoria. La lista continúa.
- **Atributos por entrada**: por arriba del máximo configurable (por defecto 64) los atributos no se incorporan y se registran como informativo no-fatal; la entrada conserva los atributos ya parseados.
- **Acumulación de errores**: los errores tipados se **agregan por código de error** (cada tipo lleva su conteo total y una muestra acotada de ubicaciones, no una lista ilimitada). Un resultado nunca contiene errores que crezcan al ritmo del tamaño de la lista: si 10.000 entradas traen 10.000 problemas, el resultado reporta el conteo por tipo y las primeras N ubicaciones de cada tipo.
- Todos los límites son configurables (preferencias globales, ADR-0002) y sus valores por defecto quedan documentados y probados; ninguno actúa como techo de canales.

**Nota de costo**: la complejidad asintótica **O(canales) es propiedad del catálogo resultante** (lo que se persiste en Room, ADR-0002), no una garantía de memoria del parsing, que opera por entrada y con un buffer de salida acotado por el lote.

## Consecuencias

- Las pruebas basadas en tablas cubren: M3U mínimo sin atributos, casos con atributos extendidos, listas con líneas basura intercaladas, entradas sin EXTINF, BOM, CRLF/LF mixtos, archivos grandes sintéticos (mínimo 10.000 entradas) y un caso de entrada corrupta en posición arbitraria que no debe impedir recuperar el resto.
- El caso de importación vía URL comparte parser: no hay un segundo parser para "M3U de red" y otro para "M3U de archivo".

## Opciones consideradas

- **Parser estricto (fail-fast)**: descartado — invalida listas reales del ecosistema por una sola línea; los datos de mercado no satisfacen la especificación nominal completa.
- **Parser con regex global sobre todo el archivo**: descartado — no escala a archivos grandes y dificulta los errores de ubicación tipados por entrada.
