# Baseline P1 de búsqueda — E3-T1

**Estado: candidato pendiente de revisión humana.** Este documento no declara
aprobado el baseline ni cierra E3-T1. La aprobación humana explícita es la que
satisface DC-10 §5.

## Alcance

Cubre la primera página de búsqueda y la página 2 sobre 10 000 Canales en JVM y
Android, con el pico de heap Java de cada escenario. El protocolo completo de
cada corrida también ejecutó la medición de parseo JVM y Android; sus resultados
se registran abajo para demostrar que las tres corridas fueron completas.

No cubre UI/Compose, filtros combinados medidos, ordenación, E3-U3/E4 ni la
regresión posterior: la regresión se evalúa desde la primera medición que se
haga después de aprobar este baseline.

## Commit y corridas

Las tres corridas verdes consecutivas se ejecutaron con `workflow_dispatch`
sobre el mismo commit:

- Commit: `418c33f6745ad4667c7e77a77dfc609ecf4f0d5b` (`418c33f`)
- Workflow: `.github/workflows/ci.yml`
- Jobs de medición: `p1-performance` (JVM) y `p1-android` (Android)
- Fecha: 2026-10-30

| # | Run | Inicio | Fin | Conclusión |
|---|---|---|---|---|
| 1 | `40987152414` | 2026-10-30T01:39:43Z | 2026-10-30T01:46:07Z | `success` (7/7 jobs) |
| 2 | `40987957054` | 2026-10-30T01:47:57Z | 2026-10-30T01:54:23Z | `success` (7/7 jobs) |
| 3 | `40988640006` | 2026-10-30T01:55:08Z | 2026-10-30T02:01:51Z | `success` (7/7 jobs) |

No hay corridas descartadas. Las tres corridas son consecutivas y comparables:
mismo commit, mismo workflow, mismos jobs y mismo fixture.

## Entorno

| Dato | JVM | Android |
|---|---|---|
| Plataforma | `ubuntu24`, `X64` | Emulador API 34 x86_64 |
| Runtime | JDK 17.0.21 Temurin | Android 14 (`sdk_gphone64_x86_64`, `emu64xa`, `ranchu`) |
| Memoria | `-Xmx512m` (536 870 912 bytes) | Heap Java máximo 201 326 592 bytes; 4 GB RAM del emulador |
| Pantalla | — | 1080x1920, 320 dpi |
| Job | `p1-performance` | `p1-android` |
| Disparo | `workflow_dispatch` | `workflow_dispatch` |

## Fixture

- JVM: `core/src/test/resources/fixtures/F-P1-10k.m3u`
- Android: `app/src/androidTest/assets/F-P1-10k.m3u`
- Entradas: 10 000
- Bytes: 1 940 008
- SHA-256: `45c489a557efcafa53af5bffd9a001aeb2cdc1616c40a4f823e2f39817ddd04c`

## Procedimiento

- Texto de búsqueda: `Canal P1`. El fixture usa nombres `Canal P1 NNNNN`, por lo
  que la consulta devuelve al menos 50 resultados. La prueba funcional de S9
  conserva `Canal 42`; este medidor no la sustituye.
- Tamaño de página: 50.
- Ruta medida: `CanalDao.buscarEnListaActiva -> PagingSource -> Room`, sobre una
  base Room real ya poblada con las 10 000 entradas. No se mide una lista
  precargada ni un mock.
- Primera página: `load(Refresh)` con `loadSize = 50`; se mide desde el inicio
  de `load` hasta que la página completa de 50 resultados está disponible.
- Página 2: se precarga la página 1 fuera del cronómetro y se mide solo
  `load(Append)` con la `nextKey`.
- JVM: 10 calentamientos y 30 mediciones por escenario.
- Android: 5 calentamientos y 20 mediciones por escenario.
- Estadístico: p95 nearest-rank (orden ascendente, índice
  `ceil(0.95 * n) - 1`). Se reportan también mediana y máximo.
- Heap: `Runtime.totalMemory() - freeMemory()` muestreado durante la operación;
  se registra el máximo. En JVM se hace `System.gc()` antes de arrancar el
  muestreador. En Android se hace `System.gc()` y una pausa de 50 ms antes de
  arrancar el muestreador. La memoria nativa Android se reporta aparte y no se
  usa para el límite.

## Resultados de búsqueda

### JVM

| # | Run | Primera página p95 | Primera página mediana | Primera página máx. | Página 2 p95 | Página 2 mediana | Página 2 máx. | Pico heap |
|---|---|---|---|---|---|---|---|---|
| 1 | `40987152414` | 24.460 ms | 21.735 ms | 24.953 ms | 18.696 ms | 10.691 ms | 21.574 ms | 70.535 MiB |
| 2 | `40987957054` | 34.429 ms | 25.781 ms | 35.470 ms | 19.296 ms | 12.806 ms | 22.369 ms | 72.225 MiB |
| 3 | `40988640006` | 34.428 ms | 25.854 ms | 35.725 ms | 22.528 ms | 12.433 ms | 25.457 ms | 71.397 MiB |

Límites JVM: primera página p95 ≤ 150 ms; página 2 p95 ≤ 80 ms; heap ≤ 256 MiB.
Las tres corridas cumplen. El peor p95 de primera página es 34.429 ms (23.0% del
límite); el peor p95 de página 2 es 22.528 ms (28.2% del límite); el peor pico
de heap es 72.225 MiB (28.2% del límite).

### Android

| # | Run | Primera página p95 | Primera página mediana | Primera página máx. | Página 2 p95 | Página 2 mediana | Página 2 máx. | Pico heap |
|---|---|---|---|---|---|---|---|---|
| 1 | `40987152414` | 17.177 ms | 15.832 ms | 17.183 ms | 8.209 ms | 7.530 ms | 8.917 ms | 13.920 MiB |
| 2 | `40987957054` | 38.892 ms | 30.946 ms | 39.177 ms | 15.347 ms | 14.006 ms | 15.464 ms | 19.102 MiB |
| 3 | `40988640006` | 30.651 ms | 29.078 ms | 35.950 ms | 16.281 ms | 14.260 ms | 16.799 ms | 11.941 MiB |

Límites Android: primera página p95 ≤ 300 ms; página 2 p95 ≤ 150 ms; heap Java
≤ 384 MiB. Las tres corridas cumplen. El peor p95 de primera página es 38.892 ms
(13.0% del límite); el peor p95 de página 2 es 16.281 ms (10.9% del límite); el
peor pico de heap es 19.102 MiB (5.0% del límite).

## Parseo ejecutado en el protocolo completo

El mismo run ejecutó la medición de parseo de 10 000 entradas. No forma parte de
la referencia de búsqueda, pero se registra para demostrar que el protocolo
completo fue verde.

| # | Run | JVM parseo p95 | JVM parseo heap | Android parseo p95 | Android parseo heap |
|---|---|---|---|---|---|
| 1 | `40987152414` | 30.361 ms | 48.444 MiB | 124.213 ms | 36.798 MiB |
| 2 | `40987957054` | 38.273 ms | 46.289 MiB | 199.138 ms | 35.194 MiB |
| 3 | `40988640006` | 46.130 ms | 45.419 MiB | 204.859 ms | 29.578 MiB |

Límites de parseo: JVM p95 ≤ 1200 ms y heap ≤ 256 MiB; Android p95 ≤ 2500 ms y
heap Java ≤ 384 MiB. Las tres corridas cumplen.

## Referencia para regresión

Se toma el peor valor de las tres corridas, no el promedio ni la mediana.

### JVM

- Primera página p95 de referencia: **34.429 ms**; regresión admisible ×1.20 →
  **41.315 ms**.
- Página 2 p95 de referencia: **22.528 ms**; regresión admisible ×1.20 →
  **27.034 ms**.
- Pico de heap de referencia: **72.225 MiB**; regresión admisible ×1.15 →
  **83.059 MiB**.

### Android

- Primera página p95 de referencia: **38.892 ms**; regresión admisible ×1.20 →
  **46.670 ms**.
- Página 2 p95 de referencia: **16.281 ms**; regresión admisible ×1.20 →
  **19.537 ms**.
- Pico de heap de referencia: **19.102 MiB**; regresión admisible ×1.15 →
  **21.967 MiB**.

La regresión no se evalúa en esta primera calibración. Se evaluará desde la
primera medición posterior a la aprobación humana, comparando contra estas
referencias y exigiendo además el límite absoluto.

## Revisión humana

**Pendiente.** No hay aprobación humana registrada. El baseline queda como
candidato y E3-T1 sigue abierta. La aprobación debe decidir, como mínimo:

1. aprobar el baseline de búsqueda JVM y Android y sus referencias;
2. confirmar el criterio de referencia: peor p95 por escenario y peor pico de
   heap de las tres corridas, no promedio ni mediana;
3. confirmar que las tres corridas verdes sobre `418c33f` son consecutivas y
   comparables;
4. confirmar que no se descarta ninguna corrida y que la regresión se evalúa
   desde la próxima medición posterior a la aprobación.

## Evidencia

- Baseline máquina: `docs/evidence/p1/busqueda/p1-baseline-busqueda.json`.
- Corridas crudas: `docs/evidence/p1/busqueda/corridas/418c33f-r1/`,
  `...-r2/` y `...-r3/`.
- Cada corrida contiene JSON y CSV de búsqueda y parseo para JVM y Android,
  además de `run.json` con el run ID, el commit, las fechas y la conclusión de
  los jobs.
- Artefactos de GitHub Actions: `p1-medicion` y `p1-android-medicion` de los
  runs `40987152414`, `40987957054` y `40988640006`.
