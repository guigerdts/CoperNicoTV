# Baseline P1 — parseo de 10 000 entradas

Tres corridas verdes consecutivas del job `p1-performance` sobre el runner de
referencia. Registro máquina: `docs/evidence/p1/p1-baseline.json`.

## Entorno

| Dato | Valor |
|---|---|
| Runner | `ubuntu24`, `X64` |
| JDK | 17.0.21 |
| Heap máximo | 536 870 912 bytes (512 MiB) |
| Workflow / job | `.github/workflows/ci.yml` → `p1-performance` |
| Disparo | `workflow_dispatch` (el job no corre en `push`) |

## Procedimiento

`./gradlew :core:p1Performance --offline`, 10 calentamientos y 30 mediciones,
p95 nearest-rank, sobre `core/src/test/resources/fixtures/F-P1-10k.m3u`
(10 000 entradas, 1 940 008 bytes, SHA-256
`45c489a557efcafa53af5bffd9a001aeb2cdc1616c40a4f823e2f39817ddd04c`).

## Corridas

| # | Run | Commit | Mediana | p95 | Máximo | Pico heap |
|---|---|---|---|---|---|---|
| 1 | `40711018242` | `6364e4f` | 10.129 ms | 15.075 ms | 16.032 ms | 32.888 MiB |
| 2 | `40857174822` | `e4ce7a8` | 7.616 ms | 11.840 ms | 13.095 ms | 33.419 MiB |
| 3 | `40861342045` | `5e13de3` | 5.401 ms | 8.605 ms | 14.982 ms | 42.861 MiB |

Límites absolutos: p95 ≤ 1200 ms y pico ≤ 256 MiB. Las tres corridas cumplen
con dos órdenes de magnitud de margen en tiempo y seis veces en memoria.

## Referencia para regresión

Se toma el peor valor de las tres corridas, no el promedio:

- p95 de referencia: **15.075 ms** (regresión admisible ×1.20 → 18.090 ms)
- pico de heap de referencia: **42.861 MiB** (regresión admisible ×1.15 → 49.290 MiB)

## Revisión humana

**Pendiente.** DC-10 exige revisión humana del baseline antes de usarlo como
referencia de regresión. Este archivo registra las tres corridas verdes y los
valores observados; no afirma que la revisión esté hecha.

## Alcance

Cubre el parseo M3U y su pico de heap. **No** cubre las métricas de búsqueda
(150 ms) ni de página 2 (80 ms): dependen de la consulta de E3, que todavía no
existe, y quedan asignadas a esa etapa.
