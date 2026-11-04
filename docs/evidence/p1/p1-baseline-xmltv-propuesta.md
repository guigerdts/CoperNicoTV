# Propuesta de baseline P1 XMLTV — pendiente de revisión humana

Propuesta de entrada XMLTV para `docs/evidence/p1/p1-baseline.json`. **No está
aprobada**, no modifica el baseline M3U existente y no se usa para evaluar
regresiones hasta que el propietario la apruebe conforme a DC-10 §5.

Registro máquina: `docs/evidence/p1/p1-baseline-xmltv-propuesta.json`.
Evidencia cruda: `docs/evidence/p1/medicion-xmltv-b5605f1-r*.json` y
`docs/evidence/p1/mediciones-xmltv-b5605f1-r*.csv`.

## Entorno

| Dato | Valor |
|---|---|
| Runner solicitado | `ubuntu-latest` |
| Imagen real | `ubuntu26`, `X64` (reportada por `ImageOS`) |
| JDK | 17.0.21 |
| AGP | no aplica: la medición corre en `:core`, módulo JVM |
| Heap máximo | 536 870 912 bytes (512 MiB) |
| Workflow / job | `.github/workflows/ci.yml` → `p1-performance` |
| Disparo | `workflow_dispatch` (el job no corre en `push`) |
| Commit | `b5605f15a59e770827bba942e1bcedd7d56799c9` |
| Fecha de la última corrida | 2026-11-02T20:49:23.651154650Z |

## Fixture

`core/src/test/resources/fixtures/F24a.xml`, XMLTV válido con 100 canales y
10 000 programas, 1 533 410 bytes, SHA-256
`20c0417f83b5f3534e3229b2c423ad6bdd3f54bc1c92e7776efbb655c0f74bf1`.

El hash es idéntico al que producía el generador en memoria anterior: la
materialización no cambió los bytes ni la semántica del fixture de rendimiento.
F24b y F24c documentan los casos malformado e incompleto en
`docs/evidence/p1/fixture-f24.md`.

## Procedimiento

Comando del job:

```text
./gradlew :core:p1Performance :core:p1XmltvPerformance :app:testDebugUnitTest -Pp1.busqueda=true -Pp1.commit="$GITHUB_SHA" --no-parallel --stacktrace
```

La tarea XMLTV es `:core:p1XmltvPerformance`: 10 calentamientos, 30 mediciones,
p95 nearest-rank, y pico de heap con
`Runtime.totalMemory() - freeMemory()` muestreado cada 2 ms. El muestreador
arranca después de `System.gc()`.

## Tres corridas verdes consecutivas

| # | Run | Commit | Mediana | p95 | Máximo | Pico heap |
|---|---|---|---|---|---|---|
| 1 | `41519220751` | `b5605f1` | 34.535 ms | 46.427 ms | 48.017 ms | 22.502 MiB |
| 2 | `41520808801` | `b5605f1` | 44.788 ms | 55.584 ms | 80.099 ms | 22.141 MiB |
| 3 | `41522305743` | `b5605f1` | 51.241 ms | 71.081 ms | 82.927 ms | 21.539 MiB |

Las tres corridas completas terminaron verdes, incluyendo `p1-performance`,
`instrumented-mobile`, `instrumented-tv`, `jvm-tests`, `static-analysis`, `apk`
y `p1-android`.

Enlaces de job:

- Run 1: https://github.com/guigerdts/CoperNicoTV/actions/runs/41519220751/job/125530610438
- Run 2: https://github.com/guigerdts/CoperNicoTV/actions/runs/41520808801/job/125536023632
- Run 3: https://github.com/guigerdts/CoperNicoTV/actions/runs/41522305743/job/125541138512

## Corrida descartada

| Run | Commit | Motivo |
|---|---|---|
| `41503973319` | `b5605f1` | El run completo falló de entrada en `instrumented-mobile` por timeout de UI, ajeno a P1. `p1-performance` fue `success` y su artefacto es válido, pero no es una corrida completa verde de entrada. Se reejecutó solo el job móvil; la medición no se repitió. Se descarta para la terna por el mismo criterio que el baseline M3U. |

## Referencia para regresión

Se toma el peor valor de las tres corridas, no el promedio ni la mediana:

- p95 de referencia: **71.081 ms** (regresión admisible ×1.20 → 85.297 ms)
- pico de heap de referencia: **22.502 MiB** (regresión admisible ×1.15 → 25.877 MiB)

Límites absolutos: p95 ≤ 1200 ms y pico ≤ 256 MiB. Las tres corridas cumplen
con amplio margen.

## Diferencias y limitaciones respecto del contrato

1. **Imagen del runner.** DC-10 §1 pide `ubuntu-latest`; el workflow usa esa
   etiqueta, pero GitHub resolvió a `ubuntu26`. El baseline M3U aprobado se midió
   en `ubuntu24`. Las métricas XMLTV son internas a su propio baseline y no se
   comparan con las de M3U; si `ubuntu-latest` cambia de imagen, la referencia
   debe revisarse.
2. **Reinicio del proceso.** DC-10 §3 pide reiniciar el proceso entre mediciones
   de memoria cuando el entorno lo permita. La medición actual hace tres parseos
   de heap en el mismo proceso JVM, con `System.gc()` antes de muestrear. Es la
   misma limitación metodológica que el baseline M3U aprobado; se documenta y
   queda sujeta a la revisión humana.
3. **Contenido de F24a–F24c.** S24 no fija el contenido literal de los archivos.
   La materialización conserva el orden de S24 y los casos ya existentes en
   `ParserXmltvTest`; F24a mantiene el SHA-256 exacto del generador anterior.
   No se sustituyó XMLTV por M3U ni se agregó semántica nueva.
4. **Alcance.** Cubre parseo XMLTV y su pico de heap en JVM. No cubre búsqueda
   (150 ms), página 2 (80 ms) ni la medición P1 en Android.

## Revisión humana

**Pendiente.** Esta propuesta no declara aprobado el baseline, no cierra S24 y
no habilita la evaluación de regresiones. La aprobación de DC-10 §5 debe
registrarse antes de incorporar la entrada XMLTV al baseline versionado.
