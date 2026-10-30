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

El peor valor de las tres corridas es la referencia candidata:

| Entorno | Primera página p95 | Página 2 p95 | Pico heap |
|---|---|---|---|
| JVM | 34.429 ms | 22.528 ms | 72.225 MiB |
| Android | 38.892 ms | 16.281 ms | 19.102 MiB |

Todos los valores están muy por debajo de los límites absolutos de DC-10
(JVM: 150/80 ms y 256 MiB; Android: 300/150 ms y 384 MiB).

## Estado

El baseline queda **candidato, pendiente de revisión humana**. No se declara
aprobado y E3-T1 no se cierra. La aprobación humana explícita es la que
satisface DC-10 §5. El detalle de la referencia, los umbrales de regresión y la
evidencia está en `p1-baseline-busqueda.md` y
`p1-baseline-busqueda.json`.

## Artefactos

- `docs/evidence/p1/busqueda/p1-baseline-busqueda.json`
- `docs/evidence/p1/busqueda/p1-baseline-busqueda.md`
- `docs/evidence/p1/busqueda/corridas/418c33f-r1/`
- `docs/evidence/p1/busqueda/corridas/418c33f-r2/`
- `docs/evidence/p1/busqueda/corridas/418c33f-r3/`
- GitHub Actions: artifacts `p1-medicion` y `p1-android-medicion` de cada run.
