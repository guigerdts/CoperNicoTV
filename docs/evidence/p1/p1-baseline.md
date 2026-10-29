# Baseline P1 — parseo de 10 000 entradas

Tres corridas verdes consecutivas del job `p1-performance` sobre el runner de
referencia, todas sobre el mismo commit para que sean comparables. Registro
máquina: `docs/evidence/p1/p1-baseline.json`.

## Entorno

| Dato | Valor |
|---|---|
| Runner | `ubuntu24`, `X64` |
| JDK | 17.0.21 |
| Heap máximo | 536 870 912 bytes (512 MiB) |
| Workflow / job | `.github/workflows/ci.yml` → `p1-performance` |
| Disparo | `workflow_dispatch` (el job no corre en `push`) |
| Commit | `b186c3a` |

## Procedimiento

`./gradlew :core:p1Performance --offline`, 10 calentamientos y 30 mediciones,
p95 nearest-rank, sobre `core/src/test/resources/fixtures/F-P1-10k.m3u`
(10 000 entradas, 1 940 008 bytes, SHA-256
`45c489a557efcafa53af5bffd9a001aeb2cdc1616c40a4f823e2f39817ddd04c`).

El muestreador de heap arranca **después** de `System.gc()` desde el commit
`b186c3a`. Antes de esa corrección el muestreador arrancaba primero y podía
capturar basura de los calentamientos: una corrida registró 228.520 MiB con el
mismo código que en local medía ~30 MiB. Las corridas anteriores a `b186c3a` no
son comparables en pico de heap y no se usan en la referencia.

## Corridas

| # | Run | Commit | Mediana | p95 | Máximo | Pico heap |
|---|---|---|---|---|---|---|
| 1 | `40877200854` | `b186c3a` | 7.403 ms | 10.334 ms | 12.260 ms | 44.836 MiB |
| 2 | `40878469399` | `b186c3a` | 7.401 ms | 10.421 ms | 11.773 ms | 45.223 MiB |
| 3 | `40879581126` | `b186c3a` | 10.047 ms | 15.067 ms | 21.284 ms | 45.827 MiB |

Límites absolutos: p95 ≤ 1200 ms y pico ≤ 256 MiB. Las tres corridas cumplen
con dos órdenes de magnitud de margen en tiempo y más de cinco veces en memoria.
Los picos de heap son estables (44.8–45.8 MiB), señal de que la medición ya no
captura basura previa.

## Corridas descartadas

| Run | Commit | Motivo |
|---|---|---|
| `40711018242` | `6364e4f` | El run figura `cancelled` por concurrencia cruzada de CI; su job `p1-performance` fue `success`, pero no es una corrida completa verde. |
| `40857174822` | `e4ce7a8` | Procedimiento anterior a la corrección del GC; no comparable. |
| `40861342045` | `5e13de3` | Procedimiento anterior a la corrección del GC; no comparable. |
| `40870356075` | `2b70543` | Pico 228.520 MiB contaminado por basura previa al `System.gc()`; descartada y superada por `b186c3a`. |

## Referencia para regresión

Se toma el peor valor de las tres corridas, no el promedio:

- p95 de referencia: **15.067 ms** (regresión admisible ×1.20 → 18.080 ms)
- pico de heap de referencia: **45.827 MiB** (regresión admisible ×1.15 → 52.701 MiB)

## Revisión humana

**Pendiente.** DC-10 exige revisión humana del baseline antes de usarlo como
referencia de regresión. Este archivo registra las tres corridas verdes y los
valores observados; no afirma que la revisión esté hecha. Los datos y los puntos
de decisión del propietario están en
`docs/evidence/p1/revision-humana-pendiente.md`.

## Alcance

Cubre el parseo M3U y su pico de heap. **No** cubre las métricas de búsqueda
(150 ms) ni de página 2 (80 ms): dependen de la consulta de E3, que todavía no
existe, y quedan asignadas a esa etapa.
