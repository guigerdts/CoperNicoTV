# ADR-0006 — Parser M3U streaming y tolerante a fallos

Status: accepted — ver «Enmienda L26» al final, que prevalece en los puntos que enumera.

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

- **Longitud de línea**: una línea física que excede el límite configurable (valor inicial de especificación: 64 KiB; configurable en preferencias globales) invalida y descarta la **entrada en curso**, registra un error tipado con su ubicación y el parser se resincroniza en el siguiente `#EXTINF` válido; la lista continúa. Una URL huérfana no se asocia a una entrada incompleta. No existe un límite separado por atributo: todos los atributos del `EXTINF` comparten la línea y el límite de línea acota su tamaño total.
- **Atributos por entrada**: máximo configurable de 64 pares clave-valor por entrada (valor inicial de especificación). Se considera reconocido todo par clave-valor sintácticamente válido del `EXTINF`, incluidas las claves sin columna dedicada (se conservan en `atributos_extra`). El conteo es por aparición de par clave-valor reconocido, incluidas las claves repetidas; la primera aparición de una clave fija el valor y las posteriores cuentan para el límite pero no reemplazan el valor ya fijado. Se conservan los primeros 64 pares aceptados; los posteriores no se incorporan y generan un diagnóstico informativo no-fatal agregado por código. La entrada no se descarta por este desbordamiento.
- **Sin límite de entrada ni de documento en el parser**: el procesamiento es streaming y el límite de línea es la cota de memoria por entrada. Un límite de descarga o de red, si se define, pertenece a la capa de importación y no se fija en este ADR.
- **Acumulación de errores**: los errores tipados se **agregan por código de error** (cada tipo lleva su conteo total y una muestra acotada de ubicaciones, no una lista ilimitada). Un resultado nunca contiene errores que crezcan al ritmo del tamaño de la lista: si 10.000 entradas traen 10.000 problemas, el resultado reporta el conteo por tipo y las primeras N ubicaciones de cada tipo.
- Todos los límites son configurables como preferencias globales (ADR-0002) y sus valores iniciales de especificación quedan documentados; **no están implementados ni validados experimentalmente** y quedan pendientes de validación técnica. Ninguno actúa como techo de canales.

**Nota de costo**: la complejidad asintótica **O(canales) es propiedad del catálogo resultante** (lo que se persiste en Room, ADR-0002), no una garantía de memoria del parsing, que opera por entrada y con un buffer de salida acotado por el lote.

## Consecuencias

- Las pruebas basadas en tablas cubren: M3U mínimo sin atributos, casos con atributos extendidos, listas con líneas basura intercaladas, entradas sin EXTINF, BOM, CRLF/LF mixtos, archivos grandes sintéticos (mínimo 10.000 entradas) y un caso de entrada corrupta en posición arbitraria que no debe impedir recuperar el resto.
- Las pruebas de límites cubren: línea que excede el máximo (entrada descartada, error tipado y resincronización en el siguiente `#EXTINF`), desborde de atributos (se conservan los primeros 64 y el resto se reporta como informativo agregado) y conteo determinista de claves repetidas.
- El caso de importación vía URL comparte parser: no hay un segundo parser para "M3U de red" y otro para "M3U de archivo".

## Opciones consideradas

- **Parser estricto (fail-fast)**: descartado — invalida listas reales del ecosistema por una sola línea; los datos de mercado no satisfacen la especificación nominal completa.
- **Parser con regex global sobre todo el archivo**: descartado — no escala a archivos grandes y dificulta los errores de ubicación tipados por entrada.

## Enmienda L26 (2026-10-24)

Esta enmienda aclara la frontera entre el parser y la deduplicación; no cambia el parser.

- **El parser no deduplica (DC-05):** el parser emite cada declaración `#EXTINF` con sus atributos, su orden y su URL. No colapsa declaraciones idénticas, no crea Apariencias y no aplica la clave de presentación. La deduplicación exacta y la separación de Apariencias con `(group_title, tvg_name)` distinto pertenecen a la capa de importación/dominio (DC-05).
- **Ausencia y vacío:** el parser distingue un atributo ausente (`NULL`) de una cadena vacía; no normaliza `""` a `NULL` ni al contrario. La capa de deduplicación aplica trim exterior y mantiene `NULL ≠ ""`.
- **`tvg-id` duplicado:** el parser conserva los valores; la invalidación de `tvg-id` duplicado como clave y la no fusión de los canales implicados se rigen por ADR-0004, no por el parser.
- **URL:** el parser conserva la URL tal como aparece, incluidos los parámetros de consulta. La normalización de esquema/host y la decisión de identidad pertenecen a ADR-0004; el parser no elimina parámetros ni credenciales.
- **Límites y rendimiento:** los límites de línea/atributos y su valor inicial no cambian. La medición P1 de 10 000 entradas y su procedimiento están en DC-10; no se declaran ejecutados.
