# Medición P1 de E2-T5 — corrida de referencia

- **Commit medido:** `6364e4f73bc7ed27f4b3cb727d129821c8fb0ccd`
- **Workflow / run:** `CI` → `40711018242` (`workflow_dispatch`), job `p1-performance`
- **Job:** `p1-performance` (`ubuntu-latest`), conclusión `success`
- **Fecha de la corrida:** 2026-10-28T14:59:33Z
- **Artefacto:** `p1-medicion` (`medicion.json`, `mediciones.csv`)

## Entorno de referencia

| Dato | Valor |
|---|---|
| Runner | `ubuntu24` |
| Arquitectura | `X64` |
| JDK | `17.0.21` |
| Heap máximo | 536 870 912 bytes (`-Xmx512m`) |

## Fixture

| Dato | Valor |
|---|---|
| Ruta | `core/src/test/resources/fixtures/F-P1-10k.m3u` |
| Entradas | 10 000 |
| SHA-256 | `45c489a557efcafa53af5bffd9a001aeb2cdc1616c40a4f823e2f39817ddd04c` |

El hash coincide con `docs/evidence/p1/fixture-f-p1-10k.sha256` y con la aserción
`el fixture conserva su hash registrado` de `ParserM3U10kTest`.

## Resultado

| Métrica | Valor | Límite DC-10 | Cumple |
|---|---:|---:|---|
| p95 de parseo | 15.075 ms | 1200 ms | Sí |
| Mediana de parseo | 10.129 ms | — | — |
| Máximo observado | 16.032 ms | — | — |
| Pico de heap | 32.888 MiB | 256 MiB | Sí |

Procedimiento: 10 calentamientos y 30 mediciones del parseo completo del fixture
con `ParserM3U.parsear`; pico de heap muestreado durante 3 parseos adicionales
tras `System.gc()`.

`cumple: true` en `medicion.json`.

## Alcance y límites de esta evidencia

- Cubre **solo la métrica de parseo** de DC-10 (10 000 entradas). Las métricas de
  primera página de búsqueda y consulta paginada dependen de la consulta de E3,
  que aún no existe; no se declaran medidas.
- **No constituye un baseline.** DC-10 exige tres corridas verdes sobre el runner
  de referencia y revisión humana antes de fijar `docs/evidence/p1-baseline.json`.
  Esta es la primera de esas corridas.
- La corrida se ejecutó sobre `feat/e1-bootstrap`, no sobre `main`.
- El job no corre en cada PR: solo con `workflow_dispatch` de `ci.yml`.

## Reproducción

```bash
gh workflow run ci.yml --ref <rama>
gh run download <run-id> -n p1-medicion -D <destino>
```

Localmente, sin validez como evidencia de referencia:

```bash
./gradlew :core:p1Performance -Pp1.commit=$(git rev-parse HEAD)
```
