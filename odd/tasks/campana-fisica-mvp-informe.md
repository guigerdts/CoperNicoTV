# Campaña física consolidada del MVP — Informe de preparación

**Qué es:** registro de lo que realmente se ejecutó en la preparación de la campaña (2026-11-17), los resultados, los pendientes y las decisiones justificadas. **Runbook operativo:** `odd/tasks/campana-fisica-mvp-plan.md`. **Estado:** PREPARACIÓN COMPLETADA — ejecución física pendiente del operador con dispositivo.

## A. Ejecutado en esta preparación

| Acción | Comando / método | Resultado | Duración |
|---|---|---|---|
| Auditoría del estado del repo | `git status/branch/rev-parse`, `merge-base --is-ancestor`, `gh run list` | `main @ c63d241` (HEAD), working tree limpio, `c63d241` = HEAD (descendiente trivial), CI `44083728034` success | < 1 min |
| Identificación del APK | `gh api .../runs/44083728034/artifacts` | 8 artefactos vigentes; `copernicotv-debug-apk` (16.843.188 B comprimidos) | — |
| Descarga y análisis del APK | `gh run download` + `sha256sum` + `unzip -l` | `app-debug.apk`, 18.782.024 B, SHA-256 `64b97880611eef43c77a3d92dfd86604f84bfae9a61c7e9dd57fc814e3c260ac`, variant debug, ABIs arm64-v8a/armeabi-v7a/x86_64 | ~1 min |
| Verificación ADB | `adb version && adb devices -l` | ADB 1.0.41 disponible; **sin dispositivos conectados** | < 1 min |
| Auditoría de evidencias F-01..F-12 | lectura de `docs/evidence/validaciones-fisicas.md` | 8 con evidencia (F-01..F-08), 4 pendientes (F-09..F-12) | — |
| Recuperación de criterios originales | lecturas del doc maestro y feature docs | S33/S35/S36/S42 (`mvp:210-219`), S47d/e (`mvp:224`), S16 (`mvp:193`), S19 (`mvp:196`), S24 (`mvp:201`), DC-11 (`mvp:782`), `e7-t10:118`, S6 de E7-T8 | — |
| Carga de skill | `/root/.agents/skills/cognitive-doc-design/SKILL.md` | aplicada a ambos documentos (quick path, tablas, checklists) | — |
| Creación del runbook | `odd/tasks/campana-fisica-mvp-plan.md` | matriz CAM-00..CAM-14 con precondiciones/procedimiento/criterios/evidencia/prioridad | — |

## B. Automatización — evidencia usada (sin repetir CI)

| Validación | Resultado | Fuente / equivalencia |
|---|---|---|
| CI en `main @ c63d241` | 5/5 jobs success: `jvm-tests`, `static-analysis`, `instrumented-mobile`, `instrumented-tv`, `apk` (p1-* skipped, no bloqueantes) | run `44083728034` — **ejecución válida existente para el mismo commit; no se repite** (regla del mandato) |
| Tests JVM `:core` frescos | **476 pruebas, 0 fallos, 0 errores** (BUILD SUCCESSFUL, 14m 6s) | `./gradlew :core:test --rerun-tasks` del 2026-11-17 (~11:50-12:04) sobre el árbol pre-merge de `f810de0`; el merge PR #40 añadió **solo `.md`**, así que el código bajo test es byte-idéntico al de `c63d241` |
| Tests `:app` JVM/instrumentados | Ejecutados por CI (verde); no ejecutables localmente: AAPT2 x86_64 no arranca en host aarch64 sin emulación (limitación ADR-0005, evidencia: `AAPT2 Daemon startup failed`) | runs `44083728034` / `44081165180` |
| Cancelados vs fallos | Los runs `44030776388`/`44030330851` cancelados de esta mañana fueron **supersession** (re-push minutos después), no fallos: su sucesor `44031110857` y todos los posteriores son success | `gh run list` 2026-11-17 |

## C. Decisiones justificadas

1. **No se tocó `validaciones-fisicas.md`, doc maestro ni `e7-t13`:** ninguna evidencia física nueva existe todavía; sus filas F-09..F-12 siguen Pendiente. Se actualizarán cuando el operador devuelva resultados (nuevas filas con fecha, sin sobrescribir históricas).
2. **AGENTS.md sin cambios:** ninguna información operativa estable cambió (el runbook registra el dispositivo/APK; si la campaña confirma el dispositivo, ahí sí procedería).
3. **Engram:** preparación registrada (ver abajo); registros históricos preservados sin duplicación.
4. **Discrepancia S16/S19/S24 documentada, no resuelta por interpretación:** `e7-t13` §15 las exige "en físico" para la declaratoria, pero su tabla P0 no las lista y S24 es JVM según `mvp:201`. Fuente de autoridad: el doc maestro (requisitos de spec) sobre `e7-t13` (consolidación de roadmap). Quedan CAM-11..CAM-13 como P1 con nota explícita en el runbook §7.
5. **CAM-09 sin reloj físico de 12 h:** el disparo por intervalo ya está probado con `WorkManagerTestDriver` en CI (S3b); en físico se valida persistencia/reconciliación/force-stop/estado/conservación, exactamente el alcance de F-12 y del contrato DC-07.
6. **CAM-10 (S47d/e):** captura de tráfico con herramientas del operador (`dumpsys netstats` u otra); sin umbrales inventados — el criterio es "sin telemetría ni tráfico no solicitado" (texto de la spec).

## D. Pendientes (lo que falta para declarar el MVP)

- Ejecución física de CAM-00..CAM-05, CAM-08, CAM-09, CAM-10 (P0) con evidencia.
- Consolidación de resultados en `validaciones-fisicas.md` (F-09..F-12), doc maestro y `e7-t13`.
- Resolución declaratoria con las dependencias externas documentadas: CAM-07 (fuente `video/mpeg2`), CAM-11 (fuentes por protocolo), CAM-14 (TV física a 10 pies).

## Key Learnings

1. El APK debug de `main @ c63d241` es el artefacto `copernicotv-debug-apk` del run CI 44083728034, con SHA-256 64b97880611eef43c77a3d92dfd86604f84bfae9a61c7e9dd57fc814e3c260ac.
2. El merge de PR #40 añadió solo archivos Markdown, por lo que la ejecución fresca de 476 pruebas en :core realizada antes del merge sigue siendo evidencia válida del código de c63d241.
3. Los runs cancelados 44030776388 y 44030330851 fueron supersession de re-push, no fallos de CI, y sus sucesores son verdes.
4. La preparación de la campaña física no actualiza el registro de evidencias físicas hasta que exista evidencia real del dispositivo, según la regla del propio registro.
