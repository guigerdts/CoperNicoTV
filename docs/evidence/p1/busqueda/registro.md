# Registro de ejecución — medición P1 de búsqueda (E3-T1)

Fecha: 2026-10-30
Commit medido: `418c33f6745ad4667c7e77a77dfc609ecf4f0d5b` (`418c33f`)
Rama: `feat/e1-bootstrap`

## Objetivo

Medir la primera página de búsqueda y la página 2 sobre 10 000 Canales en JVM y
Android, con el protocolo de DC-10: 10+30 mediciones JVM, 5+20 Android, p95
nearest-rank, pico de heap Java, fixture versionado y tres corridas verdes
consecutivas sobre el mismo commit.

## Infraestructura ejecutada

- `p1-performance`: `./gradlew :core:p1Performance :app:testDebugUnitTest
  -Pp1.busqueda=true -Pp1.commit="$GITHUB_SHA" --no-parallel`.
  - `:core:p1Performance` mide parseo JVM.
  - `:app:testDebugUnitTest` con `-Pp1.busqueda=true` activa solo
    `MedicionP1BusquedaTest`, con `-Xmx512m` y evidencia en
    `app/build/p1-busqueda/`.
- `p1-android`: `.github/scripts/p1-android.sh` sobre emulador API 34,
  1080x1920, 320 dpi, 4 GB RAM.
  - Ejecuta `MedicionP1AndroidTest` (parseo) y
    `MedicionP1BusquedaAndroidTest` (búsqueda y página 2).
  - Exige `medicion.json` y `busqueda-medicion.json`; falla si falta alguno.

## Corridas

| # | Run | Jobs | Resultado |
|---|---|---|---|
| 1 | `40987152414` | `p1-performance`, `p1-android`, `jvm-tests`, `static-analysis`, `apk`, `instrumented-mobile`, `instrumented-tv` | 7/7 `success` |
| 2 | `40987957054` | mismos jobs | 7/7 `success` |
| 3 | `40988640006` | mismos jobs | 7/7 `success` |

No se descartó ninguna corrida. Las tres se lanzaron secuencialmente con
`workflow_dispatch` y terminaron verdes sobre el mismo commit.

## Verificación de los datos

- Los JSON declaran el commit `418c33f6745ad4667c7e77a77dfc609ecf4f0d5b` en
  JVM y Android.
- El fixture declarado en ambos entornos tiene 10 000 entradas, 1 940 008 bytes
  y SHA-256 `45c489a557efcafa53af5bffd9a001aeb2cdc1616c40a4f823e2f39817ddd04c`.
- Se recalcularon los p95 nearest-rank desde `muestrasMs` y desde los CSV; los
  valores coinciden con los reportados por el medidor.
- Cada escenario tiene 30 muestras en JVM y 20 en Android.
- Cada página se verificó con 50 resultados y sin IDs duplicados.
- La página 2 se midió después de precargar la página 1 fuera del cronómetro.
- Los JSON de las tres corridas reportan `cumple: true` para primera página,
  página 2 y heap.

## Resultado

El peor valor de las tres corridas es la referencia aprobada:

| Entorno | Primera página p95 | Página 2 p95 | Pico heap |
|---|---|---|---|
| JVM | 34.429 ms | 22.528 ms | 72.225 MiB |
| Android | 38.892 ms | 16.281 ms | 19.102 MiB |

Todos los valores están muy por debajo de los límites absolutos de DC-10
(JVM: 150/80 ms y 256 MiB; Android: 300/150 ms y 384 MiB).

## Auditoría y corrección documental

- DC-10 §3 define la métrica como primera página de búsqueda con primera página
  de 50. En Paging 3 la página es la unidad atómica de entrega y el primer
  elemento individual no es observable antes de que la página se materialice.
  La página completa de 50 es, por tanto, el primer resultado completo
  observable; no se requiere una medición separada del primer elemento.
- El KDoc y el mensaje de `assumeTrue` del medidor JVM citaban
  `:app:p1BusquedaPerformance`, tarea inexistente. La activación real es
  `:app:testDebugUnitTest -Pp1.busqueda=true`.
- El defecto era exclusivamente documental: no afectaba la ruta, el fixture, los
  parámetros ni el medidor. No se repitieron las mediciones; las tres corridas
  sobre `418c33f` siguen siendo la evidencia válida.
- Commit de corrección documental y aprobación: `7c3483d`
  (`7c3483d60c70c2ae1ac76d0e10d475b09415833f`).

## Estado

El baseline queda **aprobado el 2026-10-30** por la instrucción del propietario
del producto condicionada a la auditoría contractual; no se registra una
aprobación humana separada. E3-T1 se cierra. La regresión se evalúa desde la
primera medición posterior a la aprobación. El detalle de la referencia, los
umbrales de regresión, las limitaciones y la evidencia está en
`p1-baseline-busqueda.md` y `p1-baseline-busqueda.json`.

## Limitaciones y pendientes

- Room in-memory: ejercita DAO, SQL y PagingSource reales, pero no I/O en disco;
  no representa el rendimiento completo de la UI/Compose.
- No se miden UI/Compose ni filtros combinados; los filtros tienen cobertura
  funcional en E3-U2.
- La regresión no se evalúa en esta calibración.
- S52 conserva pendiente el `EXPLAIN QUERY PLAN`; E3-T2/T3, E3-T5,
  E3-T6/T7/T8/T9 y E4 siguen fuera de esta aprobación.

## Artefactos

- `docs/evidence/p1/busqueda/p1-baseline-busqueda.json`
- `docs/evidence/p1/busqueda/p1-baseline-busqueda.md`
- `docs/evidence/p1/busqueda/corridas/418c33f-r1/`
- `docs/evidence/p1/busqueda/corridas/418c33f-r2/`
- `docs/evidence/p1/busqueda/corridas/418c33f-r3/`
- GitHub Actions: artifacts `p1-medicion` y `p1-android-medicion` de cada run.
