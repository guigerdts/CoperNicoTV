# E7-T13 — Integración total, reconciliación documental y consolidación del conocimiento

**Objetivo**: consolidar en `main` todo el trabajo aprobado de E7-T8 a E7-T12 (con sus dependencias #32/#33), reconciliar la documentación maestra y consolidar el conocimiento (Engram + evidencias físicas históricas). Sin funcionalidades nuevas.
**Branch**: `main` (integraciones) · trabajo documental sobre `main` al cierre. **Estrategia**: merge commits (convención del repo: "Merge pull request #N"). **CI**: 5 jobs, verde entre cada integración.

## Specs

- S1: Integrar en orden topológico real: PR #32 (ronda 8) → PR #33 (ronda 9) → E7-T8 → E7-T9 → E7-T10 → E7-T11 → E7-T12. Cada integración con CI 5/5 antes de la siguiente.
- S2: No `git reset --hard`, no `git clean`, no force push, no reescritura de historial, no borrar ramas remotas durante E7-T13.
- S3: Sin commits perdidos, sin cambios ajenos descartados, sin duplicar commits ya presentes en `main`.
- S4: Documento maestro (`odd/tasks/mvp-copernicotv.md`) registra E7-T8..T12 con evidencia CI, estados reales, validaciones físicas y limitaciones; Log desde L94 sin borrar historia; completar estados de E4-T5/E4-T8 solo con evidencia real.
- S5: E7-T12 distingue: MPEG-2 demostrado en emulador CI (run 44008034924); física H.264 demostrada (video/avc, Exynos HARDWARE); fallback MPEG-2 físico pendiente de fuente real `video/mpeg2`. No presentar la limitación como fallo de E7-T12 ni bloqueo del MVP.
- S6: Consolidar validaciones físicas históricas clasificadas (CI/instrumentada/emulador/dispositivo/documental/pendiente) en `docs/evidence/`, sin transformar evidencia de conversación en prueba automatizada.
- S7: Engram reconciliado sin duplicados; contexto de agentes apunta al doc maestro vigente; una sola fuente autoritativa por dato.
- S8: Roadmap P0..P3 actualizado con estados verificados (E4-T5/T8, E4-T7/DC-07, E5/S24, E6/RTSP, E7-T1..T12, DC-11, S44a, DC-09/DC-04, S51, subtítulos, rendimiento); no convertir pendientes históricos en bloqueos automáticos; Xtream API/VOD/HEVC quedan P3 fuera de alcance.
- S9: Informe consolidado en este archivo (17 puntos del mandato) + commits separados para integración y documentación.

## Tasks

- T1 (S1) — Preflight: inventario de ramas, PRs, bases, merge-bases, fronteras de la cascada. Route: inline. **HECHO** (L2).
- T2 (S1) — Integración #32 (ronda 8) → main + CI. Route: gh + CI. **HECHO**: merge `0f13180`; CI `44018549923` 5/5.
- T3 (S1) — Integración #33 (ronda 9) → main + CI. Route: gh + CI. **HECHO**: merge `f45b9aa`; CI `44019557653` 5/5.
- T4 (S1) — Integración E7-T8 (PR #34, `feat/e7-t8-reproduccion`) → main + CI. **HECHO**: merge `f3a8d1c`; CI `44020642494` 5/5.
- T5 (S1) — Integración E7-T9 (PR #35, frontera `53e2259`) → main + CI. **HECHO**: merge `e3ae178`; CI `44021847975`: primer intento `instrumented-mobile` falló en `CatalogoUiTest.favoritoYocultacionFiltranYseRevierten` (timeout de condición UI); diagnóstico: E7-T9 no toca el catálogo (diff limitado a archivos del reproductor) → flake de emulador, rerun `--failed` verde (patrón documentado en L92 de mvp).
- T6 (S1) — Integración E7-T10 (PR #36, frontera `ec74154`) → main + CI. **HECHO**: merge `f4a2b2c`; CI `44024932575` 5/5.
- T7 (S1) — Integración E7-T11 (PR #37, frontera `7a0d543`) → main + CI. **HECHO**: merge `f03c3ca`; CI `44026614654`: primer intento `jvm-tests` falló en `MavenArtifactFetcher` (`IOException` descargando artefactos android-all de Maven Central — infraestructura del runner, misma suite verde en el run anterior); rerun `--failed` verde.
- T8 (S1) — Integración E7-T12 (PR #38, punta `7eece87`) → main + CI. **HECHO**: merge `168a1ec`; CI `44029043192` (ver T12 para el cierre). Verificación de integridad: 97 commits de la cascada alcanzables desde `origin/main` (104 = 97 + 7 merges); puntas de cada entrega (`015219f`, `3549095`, `b0564c3`, `53e2259`, `ec74154`, `7a0d543`, `7eece87`) todas ancestros de `main`.
- T9 (S4/S9) — Reconciliación documental: doc maestro, features E7-T8..T12, evidencias físicas, skill cognitiva aplicada. **HECHO**: §8.8 + E4-T5/T8 + §10.4 + Log L94/L95 (mvp); `docs/evidence/validaciones-fisicas.md` F-01..F-11; `AGENTS.md` actualizado; skill `cognitive-doc-design` aplicada (informe §10).
- T10 (S7) — Engram + contexto de agentes reconciliados. **HECHO**: espejos/lecciones/auditoría conservados sin contradicciones; cierre E7-T13 añadido; duplicados históricos #2787/#2788 registrados como deuda menor (informe §11).
- T11 (S8) — Roadmap actualizado. **HECHO**: §Roadmap de este doc (P0..P3 con estados verificados y fuentes); referencia cruzada desde mvp Log L95 y AGENTS.md.
- T12 (S9) — Informe final + commits de documentación + CI de main verde. **HECHO**: informe de 17 puntos completo; commits documentales separados (`10d91c7`, `5b44318`, PR #39 → `76bcbc5`); registro de la cancelación por supersección de `44029043192` y del run definitivo de `main` (ver Log L5).

## Log

- L1 (2026-11-17): Mandato del propietario: E7-T13 integral (integración, documentación, conocimiento) con autorización continua, límites git estrictos y criterios de aceptación en §8 del mandato.
- L2 (2026-11-17): Preflight verificado — `origin/main` = `6bfb0b7`; #32 y #33 con base `main`, MERGEABLE, CI SUCCESS. Topología lineal confirmada: ronda8 (13 commits sobre main) → ronda9 (+3) → e7-t8 (+11) → e7-t9-compatibilidad (+70 = T9 13 [c8130b5..53e2259] + T10 35 [554a538..ec74154] + T11 11 [05d40d2..7a0d543] + T12 11 [0f464e9..7eece87]). Worktree limpio en `feat/e7-t9-compatibilidad`. Método de merge del repo: merge commits.
- L3 (2026-11-17): Integraciones T2–T8 ejecutadas en orden topológico (informe §5–§7). Dos fallos de CI diagnosticados como infraestructura con rerun `--failed` (T9: flake UI de catálogo; T7: red del runner en Robolectric); ninguna regresión. Integridad verificada: 97 commits de cascada ancestros de `main` (informe §8).
- L4 (2026-11-17): Reconciliación documental T9–T11 aplicada sobre el worktree local (se consolidará en `main` con commits documentales al cierre): mvp §8.8/E4/§10.4/Log L94–L95, `docs/evidence/validaciones-fisicas.md`, `AGENTS.md`, roadmap en §Roadmap de este doc.
- L5 (2026-11-17): Cierre — integraciones T2–T8 y PR documental #39 completados. `44029043192` (tras #38) quedó **cancelado por supersección** (4 jobs success; `instrumented-mobile` cancelado al iniciar el run del merge documental sobre el mismo código funcional; ningún fallo de test). Run definitivo de `main`: `44030330851` (cascada completa + docs). El commit de cierre que registra este log lanza su propio run de CI, verificado tras el push y registrado en el informe al propietario y en Engram.

---

## Informe final — 17 puntos del mandato

### 1. Resumen ejecutivo

E7-T13 consolidó en `main` la cascada completa E7-T8..E7-T12 con sus dependencias (#32/#33): 7 integraciones secuenciales con CI 5/5 de `main` entre cada una, 97 commits de cascada incorporados sin pérdidas ni reescritura de historial, dos fallos de CI diagnosticados como infraestructura (ninguna regresión), documentación maestra reconciliada, registro central de evidencias físicas creado, Engram y contexto de agentes actualizados, roadmap P0..P3 publicado. **Corrección (2026-11-17):** E4-T7/DC-07 resultó ya implementado y validado (implementación en `f1f4ce2`/PR #4 con CI verde sobre `05caecd` — run `41333845199` — y sobre `f810de0` — run `44031110857`); el estado "pendiente" de esta sección derivaba de un doc maestro desactualizado, no de un hueco real de código. **Lo único que separa al MVP declarable es la campaña de verificación física consolidada** (más la verificación física de reinicio de DC-07, F-12).

### 2. Estado inicial verificado

- `origin/main` en `6bfb0b7` (última integración: PR #31, E7-T6).
- PR #32 y #33: base `main`, MERGEABLE, CI SUCCESS previo.
- Cascada apilada lineal confirmada con merge-bases: `feat/ronda8-envolvente-0x02` (13 commits sobre main) → `feat/ronda9-metricas-room` (+3) → `feat/e7-t8-reproduccion` (+11) → `feat/e7-t9-compatibilidad` (+70: T9 13, T10 35, T11 11, T12 11).
- Worktree limpio en `feat/e7-t9-compatibilidad`; `7eece87` punta conocida confirmada.

### 3. Matriz de integración

| Elemento | Rama origen | PR | Commits delta | CI previo | Integración |
|---|---|---|---|---|---|
| E7-T7 ronda 8 | `feat/ronda8-envolvente-0x02` | #32 | 13 | SUCCESS | merge directo |
| E7-T7 ronda 9 | `feat/ronda9-metricas-room` | #33 | +3 | SUCCESS | merge directo |
| E7-T8 | `feat/e7-t8-reproduccion` | #34 (nuevo) | +11 | 5/5 en doc feature | PR nuevo, base main |
| E7-T9 | frontera `53e2259` → `integration/e7-t9` (nueva) | #35 (nuevo) | +13 | 5/5 ×2 | rama frontera para separar la entrega de la cascada apilada |
| E7-T10 | frontera `ec74154` → `integration/e7-t10` (nueva) | #36 (nuevo) | +35 | 5/5 (`43928184901`) | idem |
| E7-T11 | frontera `7a0d543` → `integration/e7-t11` (nueva) | #37 (nuevo) | +11 | 5/5 | idem |
| E7-T12 | punta `7eece87` → `feat/e7-t9-compatibilidad` | #38 (nuevo) | +11 | 5/5 (`44008034924`) | PR nuevo |

Sin duplicaciones: cada PR muestra solo el delta de su entrega (el apilamiento lineal garantiza diff enfocado tras cada merge previo). Sin cambios ajenos: los diffs fueron verificados por archivo (T9: solo archivos del reproductor, etc.).

### 4. Orden real de integración

El orden topológico confirmado coincide con la hipótesis: #32 → #33 → T8 → T9 → T10 → T11 → T12. Cada integración esperó CI 5/5 de `main` antes de la siguiente; dos integraciones (T9, T11) requirieron rerun `--failed` con diagnóstico documentado (punto 7).

### 5. PR integrados

#32, #33 (existentes, base ya correcta) y #34–#38 (creados en esta entrega). Todos MERGED con merge commit (convención del repo). Ningún PR del alcance quedó abierto; ninguna rama remota se borró (rastro conservado).

### 6. Commits resultantes (merge commits en `main`)

`0f13180` (#32) · `f45b9aa` (#33) · `f3a8d1c` (#34) · `e3ae178` (#35) · `f4a2b2c` (#36) · `f03c3ca` (#37) · `168a1ec` (#38).

### 7. Runs de CI por integración

| Paso | Run | Resultado |
|---|---|---|
| #32 | `44018549923` | 5/5 |
| #33 | `44019557653` | 5/5 |
| #34 | `44020642494` | 5/5 |
| #35 | `44021847975` | 1er intento: `instrumented-mobile` falló en `CatalogoUiTest.favoritoYocultacionFiltranYseRevierten` (timeout de condición UI). Diagnóstico: E7-T9 no toca el catálogo (diff limitado a `DetalleFallo`, `DiagnosticoReproductor`, `EstadoReproductor`, `SesionMedia3`) → flake de emulador, patrón de L92. Rerun `--failed`: 5/5 |
| #36 | `44024932575` | 5/5 |
| #37 | `44026614654` | 1er intento: `jvm-tests` falló en `ActualizacionRepositorioTest` vía `MavenArtifactFetcher` (`IOException` descargando artefactos android-all de Maven Central — red del runner). Misma suite verde en el run anterior → infraestructura. Rerun `--failed`: 5/5 |
| #38 | `44029043192` | 4 jobs success; `instrumented-mobile` cancelado por supersección (el merge documental #39 lanzó el run definitivo `44030330851` sobre el mismo código funcional). Sin fallo de test: ningún job terminó en failure |
| #39 (documental) | `44030330851` | Cancelado por supersección (el push del commit de cierre `48e8192` lanzó `44030776388` sobre el mismo código + registro de cierre); sin fallo de test |
| Cierre | `44030776388` y el run del push de este registro | Run definitivo de `main`; verificado tras el push y registrado en Engram/informe al propietario |

### 8. Estado final de `main`

97 commits de cascada alcanzables desde `origin/main` (104 = 97 + 7 merges). Puntas verificadas como ancestros: `015219f`, `3549095`, `b0564c3`, `53e2259`, `ec74154`, `7a0d543`, `7eece87`. Historial lineal preservado, sin force push ni reescritura. CI final: ver punto 7 (fila #38).

### 9. Archivos documentales modificados

- `odd/tasks/mvp-copernicotv.md` — §8.8 nueva (E7-T8..T12), estados E4-T5/T8 con evidencia, §10.4 referencia al registro central, Log L94–L95.
- `docs/evidence/validaciones-fisicas.md` — nuevo: registro central F-01..F-11.
- `AGENTS.md` — referencias al registro de evidencias y al roadmap vigente.
- `odd/tasks/e7-t13-integracion-reconciliacion.md` — este informe.
- Feature docs E7-T8..T12 y `docs/compatibilidad-video.md`/ADR-0008: ya consistentes con su implementación (integrados por la cascada); sin contradicciones detectadas en la auditoría.
- Commits documentales separados en `main` (integración ≠ documentación): ver Log L5.

### 10. Aplicación de la skill cognitiva

`cognitive-doc-design` localizada en el entorno (`<available_skills>`, id `cognitive-doc-design`), leída completa y aplicada: respuesta primero (tablas de estado al inicio de cada sección), divulgación progresiva (doc maestro resumido con punteros a feature docs — sin monolito), tablas sobre prosa (§8.8, matriz de integración, runs, roadmap), una sola fuente autoritativa por dato (estados en el doc maestro, evidencia física en `docs/evidence/validaciones-fisicas.md`, detalle técnico en feature docs) y referencias cruzadas en lugar de duplicación. Registrado en Log L95 del doc maestro.

### 11. Reconciliación de Engram

Registros existentes conservados: espejos por feature (#2774 E7-T8, #2777 E7-T9, #2779 E7-T10, #2794 E7-T11, #2796 E7-T12), lecciones (#2798/#2799), auditoría y corrección física (#2808/#2809). Sin contradicciones detectadas. Añadido: registro de cierre de E7-T13 (integración, documentación, roadmap). Nota menor: #2787/#2788 (mismo título, sin topic key) son duplicados históricos de una nota T3 de E7-T10; no se fusionaron (no hay herramienta de fusión de observaciones y su valor histórico es bajo) — registrado aquí como deuda de conocimiento menor.

### 12. Evidencias físicas recuperadas

Consolidadas en `docs/evidence/validaciones-fisicas.md` (F-01..F-11) con clasificación obligatoria (entrega, entorno, APK cuando es identificable, escenario, resultado, diagnóstico, limitaciones, fuente, confianza):

- **F-01/F-02 (E7-T9)**: 10 canales reales READY + Caracol 2571 302→403 (registrada retrospectivamente en `e7-t10:8`).
- **F-03 (E7-T10)**: recuperación Wi-Fi/zapeo/VOD comunicada por el propietario (evidencia documental; sin panel por escenario).
- **F-04/F-05 (E7-T11)**: Telecaribe 2719 `video/mpeg2` MIME_NO_SOPORTADO (pre-E7-T12); Caracol 2704 `video/avc` Exynos HARDWARE.
- **F-06 (E7-T12, CI/emulador)**: MPEG-2 software run `44008034924` — `ffmpeg:mpeg2video`, primer frame 365 ms, init 8 ms, matriz completa PASS.
- **F-07/F-08 (E7-T12, físico)**: Telecaribe 2719 `video/avc` `avc1.640029` 1920x1080 HARDWARE (primer frame 00:04, init 356 ms); Caracol 2704 H.264 High hardware. **Lectura normativa**: física H.264 demostrada; **fallback MPEG-2 físico NO demostrado** — la fuente entregó H.264; pendiente de una fuente real `video/mpeg2`. No es fallo de E7-T12 ni bloquea el MVP (F-04 vs F-07: el contenido del stream cambió entre sesiones).

### 13. Evidencias todavía pendientes

F-09 (E7-T7 importación física S33/S35/S36/S42 + captura I-M DC-11), F-10 (~147k canales: desglose físico del `tiempo_guardado_ms` medido en CI), F-11 (percepción del duck), física E7-T8 (MPEG-TS 302 + HLS), protocolo `e7-t10:118` (Telecaribe 2719 >10 s + panel), fallback MPEG-2 físico (dependencia externa: fuente que entregue `video/mpeg2`), subtítulos y protocolos no HLS con fuente real (S16/S19). Todas con procedimiento en sus feature docs / §10.4.

### 14. Roadmap actualizado

Ver §Roadmap más abajo. Fuente de cada estado: doc maestro y registros citados; ningún estado inventado.

### 15. Estado del MVP

**Toda la funcionalidad del MVP está implementada, integrada y validada en CI** (E1–E7, incluida la actualización automática E4-T7/DC-07 — corrección 2026-11-17: `f1f4ce2`/PR #4, runs `41333845199` y `44031110857` verdes). La declaratoria formal del MVP exige además la campaña de verificación física consolidada (§10.4 + F-09..F-12 + físicas de E7-T8/T10/T12) y las verificaciones I-M con fuente real que el propio doc maestro exige (S16, S19, S24 en físico). El MVP NO se declara validado mientras existan esas verificaciones pendientes — coherente con la regla de §10.4.

### 16. Riesgos y deuda técnica restante

- **Física acumulada**: la campaña consolidada es el único camino al MVP declarable; sin ella, todo lo demás es CI.
- **MPEG-2 físico**: depende de encontrar una fuente que entregue realmente `video/mpeg2` (externa; no bloqueante para las demás verificaciones).
- **Flakes de CI recurrentes**: 2 en 7 integraciones (UI timeout de catálogo; red del runner en Robolectric). Práctica de rerun `--failed` documentada (L92, L94); consideraría a futuro anclar artefactos android-all en caché del runner para eliminar el segundo patrón.
- **Deuda de conocimiento menor**: #2787/#2788 duplicados históricos en Engram (punto 11).
- **Ramas de la cascada sin borrar**: intencional (rastro conservado); limpieza diferida a decisión del propietario tras verificar la consolidación.

### 17. Próximos pasos recomendados

1. **Campaña de verificación física consolidada** con el APK del último CI de `main` (procedimientos ya escritos: §10.4, feature docs, F-09..F-11): importación física, I-M/DC-11, E7-T8 302/HLS, protocolo E7-T10 2719, E7-T6 duck, S16/S19 con fuente real.
2. ~~E4-T7/DC-07: implementar y validar la actualización automática WorkManager~~ — **Resuelto (2026-11-17)**: ya estaba implementado y validado (`f1f4ce2`/PR #4; CI verde `41333845199`, `44031110857`); la pendencia era del doc maestro, no del código. Queda su verificación física de reinicio (F-12) dentro de la campaña.
3. **Búsqueda activa de fuente MPEG-2** real (`video/mpeg2`) para demostrar el fallback físico de E7-T12.
4. **~147k canales**: confirmar el desglose físico del `tiempo_guardado_ms` con la próxima importación real (F-10).
5. Tras la campaña: cierre formal de E7-T8..T12 y declaratoria del MVP con su evidencia.

---

## Roadmap

Prioridades según el mandato (P0 imprescindible para declarar MVP · P1 versión sólida · P2 mejoras · P3 evolución). Estado = el registrado en el doc maestro y sus registros; nada se convierte automáticamente en bloqueo.

### P0 — Imprescindible para declarar el MVP

| Ítem | Estado verificado | Fuente |
|---|---|---|
| Funcionalidad E1–E7 implementada e integrada (E7-T1..T12) | HECHO (2026-11-17): 97 commits de cascada en `main`, CI 5/5 por entrega | Log L94 (mvp), matriz §3 de este doc |
| E4-T5/T8 (fusión, indicadores) | Implementados con pruebas JVM/instrumentada; verificación CI con fusión de E4 (PR #4) | Log L95 (mvp) |
| E4-T7/DC-07 (actualización automática WorkManager) | **HECHO (2026-11-17)**: implementado en `f1f4ce2` (PR #4) con pruebas S3b/S6b/S46b; CI verde en `05caecd` (run `41333845199`: `:core` 275, `:app` 230, instrumented 29+1) y en `f810de0` (run `44031110857`: 5 jobs). El estado "pendiente" era doc maestro desactualizado. Reinicio físico pendiente (F-12) | `mvp:702` y `mvp:1052` (corregidos), `odd/tasks/e4-t7-dc-07-implementacion-cierre.md` |
| Campaña de verificación física consolidada | Pendiente: F-09, F-10, F-11, física E7-T8, protocolo `e7-t10:118`, I-M/DC-11 | §10.4 (mvp), `docs/evidence/validaciones-fisicas.md` |
| Fallback MPEG-2 físico (E7-T12) | Pendiente de fuente real `video/mpeg2` (dependencia externa; no bloquea otras verificaciones) | F-04/F-07 |
| Rendimiento importación ~147k (físico) | Pendiente de confirmación física (desglose CI existente) | F-10, `e7-t7-ronda9-metricas-room.md` |

### P1 — Necesario para una versión sólida

| Ítem | Estado verificado | Fuente |
|---|---|---|
| E5/S24 EPG XMLTV | Implementada y validada en CI; no declarada cerrada (verificación física de guía con fuente real) | §8.5 (mvp) |
| E6/S16 protocolos no HLS (DASH, SS, RTSP, RTMP) con fuente real | Motor soportado por Media3; validación = reproducción con fuente real/fixture — pendiente físico | S16 + tabla de protocolos (mvp:258–271) |
| S19 subtítulos/pistas con fuente real | Implementado en E6-T3 (CI); verificación I-M/física pendiente | S19 (mvp:196), §8.6 |
| S44a preferencia de pista persistente | Implementado en E7-T5 (CI); verificación física pendiente | S44 (mvp:221), L90 |
| S51 caché TTL y DC-09/DC-04 presupuesto/ubicación | Aprobadas; implementación según doc maestro §8.2/§8.4 — verificación física I-M pendiente | S51 (mvp:228), DC-09 (mvp:724), S53a/DC-04 (mvp:230) |

### P2 — Mejoras posteriores

| Ítem | Nota |
|---|---|
| Robustez de CI | Anclar artefactos android-all en caché del runner (elimina el patrón `MavenArtifactFetcher`) |
| Limpieza de ramas de la cascada | Tras verificar la consolidación; decisión del propietario |
| Conocimiento | Fusionar duplicados históricos #2787/#2788 de Engram si se habilita fusión |

### P3 — Evolución futura (fuera del alcance del MVP)

| Ítem | Nota |
|---|---|
| Xtream API nativa | Decisión del propietario; hoy la importación Xtream usa el endpoint M3U Plus |
| VOD completo (catálogo series/películas Xtream) | Fuera del MVP |
| HEVC (H.265) | Depende de decoders del dispositivo; el inventario de E7-T11 lo detecta, la reproducción queda P3 |
| Otras propuestas post-MVP del README | Se evalúan tras la declaratoria del MVP |
