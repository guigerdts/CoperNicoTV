# E4-T7 — Implementación y cierre de DC-07 (Actualización automática con WorkManager)

**Objetivo:** cierre integral de E4-T7/DC-07 — auditoría del código existente contra el contrato, validación con pruebas reales, reconciliación documental y evidencia.
**Fecha:** 2026-11-17 · **Rama de entrega:** `feat/e4-t7-dc-07` · **Base:** `main @ f810de0` · **Test runner local:** `./gradlew :core:test` (JVM); `:app` e instrumentadas en CI (ADR-0005).

## A. Mandato y método

El mandato pidió implementar de forma integral la actualización automática de DC-07. El paso 0 (verificación del estado real del repo) reveló que la premisa "implementación pendiente" era falsa: el doc maestro estaba desactualizado. El método aplicado fue el que el propio mandato prevé para contradicciones: auditar el código existente contra el contrato punto por punto, completar brechas, validar, documentar — no escribir desde cero. No se creó ningún archivo de código nuevo; el único cambio fue documental (secciones K–L).

## B. Contrato DC-07 recuperado (10 preguntas del §3)

Fuente primaria: `odd/tasks/mvp-copernicotv.md:686-702` (DC-07 completo, sin truncamientos), más S3b (`:180`), S6b (`:183`), S46b (`:223`), §6.2/D4 (`:321-331`), fila E4-T7 (`:1052`), E7-T5 (`:1094`).

| # | Pregunta | Respuesta contractual | Fuente |
|---|---|---|---|
| 1 | ¿Qué se actualiza? | Catálogo y guía de **todas las Listas de una Fuente**, cada Lista de forma independiente | DC-07 punto 2, 7 |
| 2 | ¿Desencadenante? | WorkManager `PeriodicWorkRequest` (punto 1) + actualización manual `OneTimeWorkRequest` (punto 6) | puntos 1, 6 |
| 3 | ¿Periodicidad? | Default **720 min**, mínimo **15**, máximo **10080**; por Fuente con bandera «usar global»; global en DataStore; fuera de rango se **rechaza**, no se recorta | punto 2 |
| 4 | ¿Sin conexión? | Restricción `NetworkType.CONNECTED`: no se ejecuta sin red; no se exige carga ni Wi-Fi; batería no baja | punto 3 |
| 5 | ¿Exclusión mutua? | Un único work por Fuente (`actualizar-fuente-<id>`); manual `actualizar-fuente-manual-<id>` coalesce; reclamo `en_curso` con vigencia; limpieza de huérfanos al arrancar | puntos 5, 6 |
| 6 | ¿Conservación del catálogo? | Un fallo **nunca** borra ni modifica el catálogo ni la guía; no cambia la Disponibilidad de Reproducción | punto 7, 9 |
| 7 | ¿Informe al usuario? | Estado de Actualización por Lista (inicio, fin, causa, versión/import, resultado), agregado de Fuente derivado; causa `automatica` | punto 9 |
| 8 | ¿Reintentos? | Backoff exponencial, inicial 30 s, retardo efectivo 30/60/120 s; máx **3 reintentos** (`runAttemptCount < 3`); agotados, fallo hasta el siguiente ciclo | punto 4 |
| 9 | ¿Errores? | Transitorio → `retry` hasta 3; permanente → `failure` sin reintento; credenciales no disponibles → fallo tipado sin reintento en el ciclo; fallo de una Lista no cancela las demás | punto 7 |
| 10 | ¿Criterio de cierre? | §11 del mandato: contrato, arquitectura, implementación, concurrencia, persistencia, errores, reintentos, catálogo protegido, pruebas, build/lint/APK, docs, Engram, roadmap, informe | §11 |

**Vacíos contractuales:** ninguno bloqueante. Observaciones no contractuales en §L.

## C. Contradicción doc ↔ código, resuelta con evidencia

- El doc maestro (original en `:702` y fila `:1052`) declaraba "implementación y validación pendientes".
- El paquete `app/src/main/kotlin/com/copernicotv/app/data/actualizacion/` (7 archivos, 852 líneas) entró a `main` en **`f1f4ce2`** vía **PR #4** (`integration/e4-actualizacion`), con tests incluidos.
- **`05caecd`** ("test(app): prove the periodic trigger and the automatic conservation", ancestro de `main`) añadió exactamente las pruebas S3b (disparo periódico con `WorkManagerTestDriver`, `ProgramadorWorkManagerTest.kt:241-254`) y S6b (conservación automática, `ActualizacionRepositorioTest`).
- CI verde en ese estado: run **`41333845199`** (5 jobs: `:core` 275 pruebas, `:app` 230, instrumented-mobile 29, instrumented-tv 1).
- 36 commits posteriores tocaron el doc maestro sin actualizar sus líneas de DC-07; el roadmap de E7-T13 heredó el estado desactualizado y listó E4-T7 como gap P0.
- **Corrección aplicada (2026-11-17)** en `mvp:702`, `mvp:1052`, S3b/S6b/S46b, D4 (`:321`) y roadmap de E7-T13 (§15, §17, §Roadmap P0).

## D. Matriz de trazabilidad DC07-01..09

| Elemento | Punto DC-07 | Implementación (evidencia) | Prueba (evidencia) | Estado |
|---|---|---|---|---|
| DC07-01 WorkManager `PeriodicWorkRequest`, sin AlarmManager/JobScheduler | 1 | `ProgramadorWorkManager` (`PeriodicWorkRequestBuilder`; sin `setExact`) | `ProgramadorWorkManagerTest` (forma del trabajo, tags) | ✅ |
| DC07-02 Intervalo 720/15/10080, validación sin recorte, por Fuente + «usar global», DataStore | 2 | `IntervaloActualizacion` (`:core`, `:16-23`), `intervaloEfectivo` (`:59-63`), `PreferenciasActualizacion` (DataStore propio `actualizacion`, `guardarIntervaloGlobal` devuelve `ResultadoIntervalo`) | `PreferenciasActualizacionTest`; `ProgramadorWorkManagerTest` (720 y 60 min efectivos; intervalo 5 **rechazado**, no programado) | ✅ |
| DC07-03 Restricciones `CONNECTED` + `requiresBatteryNotLow` | 3 | `Constraints` en `ProgramadorWorkManager` | `ProgramadorWorkManagerTest` (`requiredNetworkType`, `requiresBatteryNotLow`) | ✅ |
| DC07-04 Backoff exponencial 30 s, máx 3 reintentos, tope 1 h | 4 | `setBackoffCriteria(EXPONENTIAL, 30 s)` en `ProgramadorWorkManager`; `MAXIMO_REINTENTOS = 3` (`CoordinadorActualizacion.kt:167`); `runAttemptCount` pasado tal cual al coordinador | `CoordinadorActualizacionTest` (reintenta mientras queden intentos; cierra al agotar los 3; permanente y credenciales sin reintento) | ✅ (política de WorkManager verificada por lectura: `WorkInfo` no expone backoff) |
| DC07-05 Único work por Fuente, `UPDATE`/`KEEP`, reconciliación al arrancar | 5 | `nombrePeriodico(fuente)` = `actualizar-fuente-<id>`; `reconciliar()` crea/actualiza/cancela; `InicializadorActualizaciones` idempotente desde `MainActivity` | `ProgramadorWorkManagerTest` (UPDATE no duplica; KEEP conserva el id; Fuente eliminada cancela; intervalo inválido no se programa) | ✅ |
| DC07-06 Manual one-time `actualizar-fuente-manual-<id>`, coalescencia sin reemplazo del periódico, exclusión y huérfanos | 6 | `solicitarFuente`/`solicitarLista` (resuelve la Fuente) con `ExistingWorkPolicy.KEEP`; `ReclamoFuenteDataStore` (`en_curso:<id>` + vigencia 30 min); `liberarHuerfano` | `ProgramadorWorkManagerTest` (manual única, KEEP no duplica, no toca la periódica); `CoordinadorActualizacionTest` (fuente ya reclamada se coalesce); S3b disparo real vía TestDriver | ✅ |
| DC07-07 Clasificación de errores transitorio/permanente/credenciales, conservación de copia válida | 7 | `ClasificacionErroresActualizacion` (`:core`: timeout/5xx/sin_conexion/archivo → TRANSITORIO; credenciales → tipado; desconocido → PERMANENTE); `CoordinadorActualizacion.decidir` | `CoordinadorActualizacionTest` (8 casos: éxito, transitorio, agotamiento, permanente, credenciales, independencia por Lista, coalescencia, Fuente sin listas) | ✅ |
| DC07-08 Reinicio: persistencia, force-stop reanuda, sin hora exacta | 8 | Persistencia de plataforma (WorkManager) + reconciliación idempotente al arrancar; `InicializadorActualizaciones` no corre en `Application` ni repite por recreación | `ProgramadorWorkManagerTest` (reconciliación idempotente: KEEP conserva el id tras segunda llamada); reinicio **físico** pendiente → **F-12** | ✅ código · ⏳ F-12 física |
| DC07-09 Estado por Lista, causa, agregado de Fuente, fallo no borra catálogo | 9 | `CausasActualizacion` (`manual`/`automatica`, `:10-13`); causa viaja en `DatosActualizacion` (v1, solo códigos e ids numéricos — sin URLs ni texto libre); resultado por Lista codificado en el `Data` del trabajo; `ActualizacionRepositorio` registra el Estado | `CoordinadorActualizacionTest` (causa en Fuente sin listas); conservación S6b en `ActualizacionRepositorioTest` | ✅ |

## E. Arquitectura e implementación

- **`ProgramadorWorkManager`** (`data/actualizacion/ProgramadorActualizaciones.kt`): programación por Fuente con intervalo efectivo (`:core`), restricciones y backoff de DC-07; reconciliación completa (crear faltantes / UPDATE cambiadas / cancelar eliminadas); limpieza de reclamos huérfanos; estado observable por tag de Fuente. Inyecta `WorkManager` como `Provider` para prueba.
- **`ActualizacionWorker`** (`@HiltWorker`, `CoroutineWorker`): no decide nada — pide la ejecución al coordinador y traduce `ResultadoEjecucion → Result` (`success`/`retry`/`failure`); causa por defecto `automatica` (`:32`).
- **`CoordinadorActualizacion`**: reclamo por Fuente (coalescencia), Listas independientes (fallo de una no cancela las demás, `:109-128`), clasificación vía `:core`, tope 3 reintentos, `CancellationException` se propaga (el reclamo se libera siempre en `finally`, `:101-103`).
- **`PreferenciasActualizacion`**: DataStore propio `actualizacion` — intervalo global con validación tipada, mapa `programadas:<id>`, reclamo con vigencia 30 min; módulo Hilt provee `ActualizadorLista` sobre `ActualizacionRepositorio` (no duplica E4-T6).
- **`:core`** (`com.copernicotv.core.actualizacion`): `IntervaloActualizacion` (720/15/10080, rechazo tipado), `intervaloEfectivo` (la bandera «usar global» manda; sin override y bandera apagada usa el global), `ClasificacionErroresActualizacion`.
- **Cableado**: `CoperNicoApplication` implementa `Configuration.Provider` con `HiltWorkerFactory`; `AndroidManifest.xml` remueve `WorkManagerInitializer` (inicialización bajo demanda); `MainActivity` inyecta `InicializadorActualizaciones` (reconciliación al arrancar, idempotente por proceso); `CatalogoViewModel`/`FuentesViewModel` invocan `solicitarLista`/`solicitarFuente` (la actualización manual pasa por el planificador, no por el repositorio directo); `di/ModuloProgramador.kt` provee `WorkManager` y el binding `ProgramadorActualizaciones`; `androidTest/ModuloProgramadorFalso.kt` sustituye el planificador en instrumentadas (HiltTestApplication no implementa `Configuration.Provider`), ejecutando el coordinador real.

## F. Concurrencia y exclusión

Reclamo por Fuente con marca de tiempo y vigencia de 30 min (un crash no bloquea para siempre); coalescencia manual-periódica: la segunda solicitud no crea trabajo paralelo (`ExistingWorkPolicy.KEEP` + reclamo); un trabajo cancelado libera el reclamo en `finally`; huérfanos: `liberarHuerfano` al reconciliar cuando WorkManager ya no reporta `RUNNING`/`ENQUEUED`. Ambos comportamientos probados en JVM sin WorkManager.

## G. Persistencia

DataStore `actualizacion` para preferencias y reclamos (no Room, sin nueva tabla); Room conserva el catálogo/Estado de `ActualizacionRepositorio` (E4-T6) sin cambios de formato; `DatosActualizacion` v1 codificada — solo identificadores numéricos y códigos, sin URLs ni texto libre (no filtra credenciales al registro de WorkManager, DC-04).

## H. Errores y reintentos

Transitorio (timeout, 5xx, sin_conexion, archivo_inaccesible) → reintento hasta 3 con backoff exponencial 30/60/120 s; permanente (4xx, formato, desconocido) → `failure` sin reintento; credenciales no disponibles (DC-04: `GeneralSecurityException`/`ProviderException`) → fallo tipado sin reintento en el ciclo, sin crash ni borrado; el siguiente ciclo periódico reintenta. Un código desconocido se trata como permanente (no reintentar a ciegas una operación que nunca va a funcionar).

## I. Catálogo protegido

El coordinador nunca opera sobre datos persistidos: delega en `ActualizacionRepositorio`, que garantiza la conservación (S6a/S6b probadas: snapshot antes/después de Favoritos, Historial, Ocultaciones, Colecciones y preferencias). Un fallo no cambia la Disponibilidad de Reproducción ni borra el catálogo (DC-07 punto 9).

## J. Pruebas ejecutadas (comandos y resultados reales)

| Comando | Dónde | Resultado |
|---|---|---|
| `./gradlew :core:test --rerun-tasks` | local (host aarch64, JDK 17) | **BUILD SUCCESSFUL, 14m 6s — 476 pruebas, 0 fallos, 0 errores** (fresco, sin caché) |
| `./gradlew :core:test :app:testDebugUnitTest` | local | `:app` **no ejecutable**: AAPT2 x86_64 no arranca en host aarch64 (sin emulación). Limitación de entorno, ya documentada (ADR-0005) — no es un fallo del código |
| CI run `44031110857` sobre `f810de0` (main, 2026-11-17 06:25) | GitHub Actions | **5/5 jobs verdes**: `jvm-tests`, `static-analysis` (ktlint+detekt), `instrumented-mobile`, `instrumented-tv`, `apk`; `p1-performance`/`p1-android` skipped (no bloquean) |
| CI run `41333845199` sobre `05caecd` | GitHub Actions | 5/5 verdes en el commit que añadió las pruebas S3b/S6b: `:core` 275, `:app` 230, instrumented 29+1 |

**Suites relevantes en `:app` (ejecutadas por CI, auditadas aquí):** `ProgramadorWorkManagerTest` (10 pruebas, Robolectric + `WorkManagerTestDriver` + `SynchronousExecutor`), `CoordinadorActualizacionTest` (8), `DatosActualizacionTest`, `PreferenciasActualizacionTest`, `ActualizacionRepositorioTest` (conservación S6b).

**Cobertura de la "prueba de reloj" exigida por DC-07:** materializada en `ProgramadorWorkManagerTest` vía `WorkManagerTestInitHelper.getTestDriver` → `setAllConstraintsMet` + `setPeriodDelayMet` (el TestDriver simula el reloj/retraso periódico).

## K. Build, lint y APK

- `apk`: job verde en run `44031110857` — el APK debug se compila y publica en CI.
- `static-analysis` (ktlint + detekt): verde en el mismo run.
- Local: compilar `:app` requiere AAPT2 x86_64 (no disponible en aarch64) — exactamente la restricción de ADR-0005/AGENTS.md.

## L. Documentación, Engram, agentes, roadmap y estado final

- **Doc maestro** (`odd/tasks/mvp-copernicotv.md`): D4 `:321`, DC-07 `:702`, S3b/S6b/S46b (`:180`, `:183`, `:223`), fila E4-T7 `:1052` — actualizados con implementación y evidencia de CI.
- **Roadmap** (`odd/tasks/e7-t13-integracion-reconciliacion.md`): §14/15/17 y P0 — E4-T7 deja de ser gap; queda solo su verificación física de reinicio.
- **Evidencias físicas** (`docs/evidence/validaciones-fisicas.md`): nueva fila **F-12** (reinicio/force-stop de DC-07 punto 8, pendiente, dentro de la campaña física consolidada).
- **Engram**: hallazgo de auditoría (`e4-t7-dc-07/auditoria`) y cierre de esta tarea; conflicto con el registro histórico de cierre (obs 2645, "Cerrada E4-T7… CI verde") reconciliado: 2645 era correcto, el doc maestro era el desactualizado.
- **AGENTS.md**: sin cambios — sus reglas (ADR-0005, CI, verificación física) ya describen este flujo; añadir contenido no aporta.

**Observaciones (no bloqueos, no brechas contractuales):**
1. No existe pantalla para editar intervalos; ninguna spec S46/S3b/S6b la exige (S46a-d son a nivel dominio/persistencia, cumplidas). El dominio ya acepta y valida valores; una UI de edición sería ítem nuevo de roadmap (P1), decisión del propietario.
2. La política de backoff no es asertable vía `WorkInfo`; quedó verificada por lectura del código y por el comportamiento de reintento en el coordinador.
3. Reconciliación a mitad de sesión: el contrato solo la exige al arrancar (punto 5); sin UI que cambie intervalos en caliente, no hay escenario real que la pida hoy.

**Estado final: COMPLETADA (con verificación física F-12 pendiente, propia de la campaña consolidada).** La funcionalidad estaba implementada y validada en CI; este cierre resolvió la contradicción documental, dejó el doc maestro y el roadmap coherentes con el código, y consolidó la evidencia. Fallo pendiente conocido: ninguna prueba fallida; limitación de entorno local documentada (AAPT2 aarch64).

`Risk: none` — el cambio es exclusivamente documental; no modifica código, contratos ni ADRs aprobados.
