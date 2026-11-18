# CAM-09-DEF-01 — Separación entre trabajo periódico programado y actualización activa

**Objetivo:** que un trabajo periódico `ENQUEUED` deje de deshabilitar las operaciones manuales de Fuentes y Catálogo, sin perder la semántica de “actualización activa” ni la identidad del trabajo periódico.
**Rama:** `fix/cam-09-def-01-encurso-periodico` (base `origin/main` `199b17b`).
**Entrega:** work unit de corrección (código + pruebas + informe) con commit y push autorizados para ejecutar CI; sin merge ni instalación en el dispositivo.
**Runner canónico:** CI `jvm-tests` → `./gradlew :core:test :app:testDebugUnitTest`. Local aarch64/Termux no ejecuta `:app` (AAPT2 x86-64, ADR-0005).

## Specs

- **S1** (requisito 1): “El trabajo periódico ENQUEUED no debe marcar `enCurso=true`.”
- **S2** (requisito 2): “El trabajo periódico RUNNING debe reflejar una ejecución real.”
- **S3** (requisito 3): “Las actualizaciones manuales deben conservar su seguimiento de estado.”
- **S4** (requisito 4): “Las operaciones manuales no deben permitir solicitudes incompatibles o duplicadas.”
- **S5** (requisito 5): “La finalización, el fallo y la cancelación deben actualizar correctamente los estados observables.”
- **S6** (requisito 6): “Fuentes y Catálogo deben consumir una semántica coherente.”
- **S7** (requisito 7): “La interfaz debe recuperar sus controles cuando no exista una actualización activa.”
- **S8** (requisito 8): “No debe alterarse la programación periódica ni su política KEEP.”
- **S9** (requisito 9): “No debe cambiarse el intervalo de 720 minutos.”
- **S10** (requisito 10): “No debe perderse la identidad del trabajo periódico existente.”
- **S11** (regla arquitectónica): separar (A) trabajo periódico programado, (B) actualización pendiente, (C) actualización ejecutándose, (D) finalizada/fallida; no eliminar `ENQUEUED` indiscriminadamente de una lista de estados.
- **S12** (restricciones): sin desinstalar, sin borrar datos/caché, sin modificar catálogo ni fuente `zonacero`, sin ejecutar actualizaciones físicas, sin cambiar UUID/intervalo/Reintentos, sin tocar contratos ajenos, sin worktrees, sin reset/clean/rebase/stash, sin commits/push/merge.
- **S13** (aceptación): periódico `ENQUEUED` no bloquea; ejecución activa sí bloquea; sin manuales duplicados; identidad/periodicidad intactas; regresiones cubiertas; CI satisfactoria; sin regresiones en Fuentes ni Catálogo.

## Tasks

| ID | Tarea | S# | Ruta | Estado |
|---|---|---|---|---|
| T1 | Añadir marca de tipo (`TAG_MANUAL`) a la solicitud manual, sin tocar el periódico | S3, S4, S8, S10 | inline | hecho — sin commit |
| T2 | Clasificador de “Actualización activa” + `estadoDe` con la semántica nueva | S1, S2, S5, S11 | inline | hecho — sin commit |
| T3 | Actualizar KDoc de `EstadoSolicitud.enCurso` y del reclamo (`ESTADOS_ABIERTOS`) | S11 | inline | hecho — sin commit |
| T4 | Pruebas de regresión (matriz completa + integración WorkManager) | S1–S7, S13 | inline | añadidas; ejecución pendiente en CI |
| T5 | Validación local disponible (ktlint/detekt/diff) y registro del límite de entorno | S13 | inline | ktlint+detekt+diff OK; tests `:app` no ejecutables localmente |
| T6 | Informe de resolución | S13 | inline | hecho |

Ruta `inline` justificada: un solo comportamiento cohesivo (semántica de estado) con superficie de edición contigua; no hay unidades paralelas independientes ni presión de contexto. Sin delegación de escritura.

## Log

### L1 — Orden original (verbatim)

````text
ORDEN DE IMPLEMENTACIÓN — CAM-09-DEF-01Separación entre trabajo periódico programado y actualización realmente activaActúa como arquitecto sénior Android especializado en Kotlin, WorkManager, Coroutines, StateFlow, Jetpack Compose y arquitectura limpia.Proyecto: CoperNicoTVRepositorio: "/root/CoperNicoTV"Defecto: "CAM-09-DEF-01"Evidencia física: "campana-fisica-2026-11-18/CAM-09/forensic-fase4-actualizacion-manual-fallo-url-2026-11-18.md"1. OBJETIVOResolver definitivamente el defecto que mantiene deshabilitadas las operaciones manuales de Fuentes y Catálogo cuando existe un trabajo periódico de WorkManager en estado ENQUEUED.La causa documentada:- "ProgramadorActualizaciones.observar()" consulta "getWorkInfosByTagFlow(TAG_ACTUALIZACION)".- La consulta incluye trabajos periódicos y manuales.- "enCurso" considera ENQUEUED como estado abierto.- El trabajo periódico permanece ENQUEUED entre ejecuciones.- La interfaz interpreta incorrectamente que hay una actualización activa.- "FuentesViewModel" y "CatalogoViewModel" deshabilitan sus operaciones manuales.Archivos identificados:- "ProgramadorActualizaciones.kt": líneas 82, 190-193 y 237-238.- "FuentesViewModel.kt": línea 55.- "FuentesScreen.kt": líneas 130 y 151.- "CatalogoViewModel.kt": línea 692.- "CatalogoScreen.kt": líneas 791 y 840.Verifica las líneas actuales antes de modificar nada.2. REGLA ARQUITECTÓNICASeparar explícitamente estos conceptos:A. Trabajo periódico programado.B. Actualización pendiente de ejecución.C. Actualización realmente ejecutándose.D. Actualización finalizada o fallida.La existencia de un trabajo periódico ENQUEUED no puede significar que exista una actualización activa.La UI solamente debe bloquear operaciones incompatibles cuando exista una condición real de ejecución o una operación manual pendiente que, según el contrato del dominio, deba impedir otra solicitud.Un trabajo periódico ENQUEUED esperando su siguiente intervalo de 12 horas no debe bloquear la interfaz.Si el trabajo periódico pasa a RUNNING, la aplicación debe representar correctamente esa ejecución y aplicar las restricciones de concurrencia que correspondan.No soluciones el problema eliminando indiscriminadamente ENQUEUED de una lista de estados. Primero establece la semántica correcta y distingue los tipos de trabajo.3. AUDITORÍA PREVIAInspecciona:- "ProgramadorActualizaciones".- "FuentesViewModel".- "CatalogoViewModel".- "FuentesScreen".- "CatalogoScreen".- Workers de actualización.- Tags y nombres únicos de WorkManager.- Flujos de estado y contratos de dominio.- Pruebas existentes.- Documentación de arquitectura relacionada.Determina cómo se identifican actualmente los trabajos periódicos y manuales.Revisa si existen consultas por nombre único, tags específicos, UUID o metadatos que permitan diferenciarlos correctamente.No cambies el contrato público de los ViewModels sin justificarlo.4. IMPLEMENTACIÓNImplementa una solución robusta, coherente con la arquitectura existente.Requisitos:1. El trabajo periódico ENQUEUED no debe marcar "enCurso=true".2. El trabajo periódico RUNNING debe reflejar una ejecución real.3. Las actualizaciones manuales deben conservar su seguimiento de estado.4. Las operaciones manuales no deben permitir solicitudes incompatibles o duplicadas.5. La finalización, el fallo y la cancelación deben actualizar correctamente los estados observables.6. Fuentes y Catálogo deben consumir una semántica coherente.7. La interfaz debe recuperar sus controles cuando no exista una actualización activa.8. No debe alterarse la programación periódica ni su política KEEP.9. No debe cambiarse el intervalo de 720 minutos.10. No debe perderse la identidad del trabajo periódico existente.Evita introducir consultas redundantes a WorkManager o estados derivados inconsistentes.Mantén la separación entre dominio, datos y presentación.5. PRUEBAS DE REGRESIÓN OBLIGATORIASAñade pruebas que reproduzcan el defecto original.Escenario| Resultado esperadoPeriódico ENQUEUED| "enCurso=false"Periódico RUNNING| "enCurso=true"Manual ENQUEUED| Según política de exclusión manual definidaManual RUNNING| "enCurso=true"Periódico ENQUEUED + manual inexistente| Controles habilitadosPeriódico ENQUEUED + manual RUNNING| Controles incompatibles deshabilitadosTrabajo manual SUCCEEDED| Controles habilitadosTrabajo manual FAILED| Controles habilitadosTrabajo manual CANCELLED| Controles habilitadosDos solicitudes manuales simultáneas| No se crean duplicadosReinicio/reconciliación| Se conserva la programación periódicaAdapta los nombres y tipos de las pruebas a las convenciones existentes.Añade cobertura tanto para la lógica de observación como para los ViewModels cuando corresponda.No simules resultados sin probar el comportamiento real del código.6. VALIDACIÓNEjecuta:- Tests unitarios afectados.- Tests de integración pertinentes.- Detekt.- Ktlint.- Compilación.- Suites de CI correspondientes.Respeta las limitaciones del entorno ARM64/Termux. Utiliza CI como referencia canónica cuando las herramientas locales no puedan ejecutar AAPT2.No repitas pruebas ajenas al defecto que ya tienen evidencia válida.7. RESTRICCIONESNO:- Desinstalar CoperNicoTV.- Borrar datos ni caché.- Modificar el catálogo.- Alterar la fuente original "zonacero".- Ejecutar actualizaciones físicas durante la implementación.- Cambiar UUID, intervalo o configuración del trabajo periódico existente.- Modificar políticas de reintento sin justificación.- Cambiar contratos ajenos al defecto.- Eliminar worktrees.- Ejecutar reset, clean, rebase o stash destructivos.- Crear commits, hacer push o merge.Preserva los cambios existentes y la evidencia de la campaña.8. DOCUMENTACIÓNActualizar la documentación técnica del defecto y registrar:- Causa raíz.- Decisión arquitectónica.- Archivos modificados.- Semántica definitiva de "enCurso".- Pruebas añadidas.- Resultados de validación.- Limitaciones conocidas.- Procedimiento para repetir CAM-09-D y CAM-09-E.Crear el informe:"odd/tasks/cam-09-def-01-resolution.md"No modificar ni reescribir los informes forenses originales.9. CRITERIOS DE ACEPTACIÓNLa corrección estará lista cuando:- El periódico ENQUEUED deje de bloquear las operaciones manuales.- Una ejecución realmente activa siga bloqueando operaciones incompatibles.- No se permitan actualizaciones manuales duplicadas.- No se altere la identidad ni la periodicidad del trabajo existente.- Las pruebas de regresión cubran los escenarios anteriores.- CI termine satisfactoriamente.- No existan regresiones en Fuentes ni Catálogo.10. ENTREGATrabaja de principio a fin, sin detenerte después del diagnóstico.Al terminar presenta:1. Causa raíz confirmada.2. Solución implementada.3. Archivos modificados.4. Pruebas añadidas y resultados.5. Resultado de CI.6. Confirmación de preservación del trabajo periódico.7. Estado de los controles de Fuentes y Catálogo.8. Informe de resolución.No hagas commit, push ni merge.IMPORTANTE: esta orden autoriza la corrección del defecto en el código y las pruebas automatizadas. No autoriza modificar los datos del dispositivo ni ejecutar todavía la campaña física. La siguiente validación física será repetir exclusivamente CAM-09-D y CAM-09-E después de integrar la corrección.
````

### L2 — Auditoría previa (2026-11-18)

- `ProgramadorWorkManager.observar()` (:79-84) usa `getWorkInfosByTagFlow(TAG_ACTUALIZACION)`; `manual()` (:207-214) y `periodico()` (:216-226) añaden **el mismo par de tags** (`actualizacion`, `fuente:<id>`), así que `agrupar`/`estadoDe` no pueden distinguirlos.
- `estadoDe` (:190-199): `enCurso = trabajos.any { it.state in ESTADOS_ABIERTOS }`; `ESTADOS_ABIERTOS` (:237-238) incluye `ENQUEUED`.
- `abierto()` (:172-182) usa el mismo conjunto a propósito (reclamo DC-07 punto 6) y **no** debe cambiar.
- Consumidores: `FuentesViewModel.kt:55` y `CatalogoViewModel.kt:692` (vía `observarLista`), con `FuentesScreen.kt:130,151` y `CatalogoScreen.kt:791,840`. Sin cambios de contrato público.
- `WorkInfo` no expone `inputData`: la causa (`manual`/`automatica`) no sirve para clasificar desde `observar()`; el discriminador viable es un tag de tipo.
- Pruebas: `ProgramadorWorkManagerTest` (Robolectric + WorkManager test) solo cubre “manual ENQUEUED ⇒ enCurso=true”; no hay caso “solo periódico ENQUEUED”. `FuentesViewModelTest` inyecta `EstadoSolicitud` a mano.
- Documentación: `odd/tasks/mvp-copernicotv.md:3423,3446` confirma que el bloqueo de importación debe ocurrir “mientras hay una Actualización en curso”.
- Entorno: SDK en `/root/Android/Sdk`, pero `aapt2` es ELF x86-64 y `uname -m` = `aarch64`, sin qemu/box64 → `:app` no ejecutable localmente (ADR-0005).

### L3 — Decisión de diseño (2026-11-18)

- **Discriminador:** tag `TAG_MANUAL = "actualizacion-manual"`, añadido solo a la solicitud manual. El trabajo periódico existente no lo lleva (ausencia = periódico), por lo que **no** se toca su UUID, intervalo, política ni configuración (S8-S10). Una solicitud manual anterior a esta versión tampoco lo lleva; `KEEP` sigue impidiendo duplicados por nombre único (limitación documentada).
- **Semántica de `enCurso`:** `true` si hay un trabajo `RUNNING` (periódico o manual) o una solicitud **manual** en `ENQUEUED`/`BLOCKED`. Un periódico `ENQUEUED`/`BLOCKED` no cuenta.
- **Sin consultas nuevas:** se conserva la única consulta por tag y se clasifica con los tags de cada `WorkInfo` (S11, “evita consultas redundantes”).
- **Reclamo intacto:** `ESTADOS_ABIERTOS` y `abierto()` se mantienen tal cual; solo se documenta que no son la señal de UI.
- **Contratos:** no se cambian firmas públicas de `ProgramadorActualizaciones`, ViewModels ni pantallas.

## Resolución

### 1. Causa raíz confirmada

`ProgramadorWorkManager.observar()` (`ProgramadorActualizaciones.kt:88`) consulta `getWorkInfosByTagFlow(TAG_ACTUALIZACION)` y devuelve **todos** los trabajos de Actualización juntos: el periódico y el manual comparten los tags `actualizacion` y `fuente:<id>` (`manual()` :217-225, `periodico()` :227-237). `estadoDe` (:199-209) derivaba `enCurso` de `ESTADOS_ABIERTOS`, que incluía `ENQUEUED`. Como el periódico vive en `ENQUEUED` esperando su intervalo (720 min por defecto), `enCurso` quedaba en `true` de forma permanente.

Ese valor alimenta `FuentesViewModel.kt:55` (`cargando`) y `CatalogoViewModel.kt:692` (`actualizando`); las pantallas derivan de ahí el `enabled` de sus operaciones manuales (`FuentesScreen.kt:130,151`; `CatalogoScreen.kt:791,840`). Resultado: controles deshabilitados sin ninguna ejecución real. La causa es de **clasificación del estado**, no de programación ni de política de trabajo.

### 2. Solución implementada

1. **Discriminador de tipo:** la solicitud manual añade `TAG_MANUAL = "actualizacion-manual"` (`.addTag(TAG_MANUAL)`, :224). El periódico **no** lo lleva; la ausencia identifica al periódico. No se modifica el request periódico, así que su UUID, intervalo, restricciones, backoff y nombre único quedan intactos.
2. **Clasificador puro:** `actualizacionActiva(estado, etiquetas)` (:264-269) centraliza la semántica y la reutiliza `estadoDe` (:202):
   - `RUNNING` ⇒ activo, venga del periódico o del manual.
   - `ENQUEUED`/`BLOCKED` ⇒ activo **solo** si el trabajo lleva `TAG_MANUAL`.
   - Resto (`SUCCEEDED`, `FAILED`, `CANCELLED`) ⇒ inactivo.
3. **Sin consultas nuevas:** se conserva la única consulta por tag; la distinción usa los tags que ya expone cada `WorkInfo`.
4. **Reclamo intacto:** `ESTADOS_ABIERTOS` y `abierto()` no cambian; siguen sosteniendo el reclamo DC-07 punto 6 mientras el periódico espera. Solo se documenta que no son la señal de UI.
5. **Sin cambios de contrato:** ViewModels, pantallas y la interfaz `ProgramadorActualizaciones` no se tocan; consumen el valor corregido.

### 3. Archivos modificados

| Archivo | Cambio |
|---|---|
| `app/src/main/kotlin/com/copernicotv/app/data/actualizacion/ProgramadorActualizaciones.kt` | KDoc de `EstadoSolicitud` (:31-45); `estadoDe` (:199-209); tag manual (:224); `TAG_MANUAL` (:250), `ESTADOS_MANUALES_PENDIENTES` (:253-254), `actualizacionActiva` (:264-269); KDoc de `ESTADOS_ABIERTOS` (:277-285) |
| `app/src/test/kotlin/com/copernicotv/app/data/actualizacion/ProgramadorWorkManagerTest.kt` | Marca manual en la prueba existente de no duplicados (:195-198) y tres pruebas nuevas (:244-338) |
| `odd/tasks/cam-09-def-01-resolution.md` | Este informe |

Sin cambios en `FuentesViewModel`, `CatalogoViewModel`, `FuentesScreen`, `CatalogoScreen`, workers, `reconciliar()`, `PreferenciasActualizacion` ni `ReclamoFuente`.

### 4. Semántica definitiva de `enCurso`

| Trabajo | Estado | `enCurso` | Efecto en la UI |
|---|---|---|---|
| Periódico | `ENQUEUED` | `false` | Controles habilitados |
| Periódico | `BLOCKED` | `false` | Controles habilitados |
| Periódico | `RUNNING` | `true` | Controles incompatibles deshabilitados |
| Manual | `ENQUEUED` | `true` | Bloqueado hasta ejecutarse (evita duplicados) |
| Manual | `BLOCKED` | `true` | Bloqueado hasta ejecutarse |
| Manual | `RUNNING` | `true` | Bloqueado mientras ejecuta |
| Manual | `SUCCEEDED`/`FAILED`/`CANCELLED` | `false` | Controles habilitados |
| Periódico `ENQUEUED` + manual `RUNNING` | — | `true` (por el manual) | Bloqueado |

`resumenes` mantiene su comportamiento: agrega el `outputData` de todos los trabajos de la Fuente.

### 5. Pruebas añadidas

| Escenario de la orden | Prueba | Resultado local |
|---|---|---|
| Periódico `ENQUEUED` ⇒ `enCurso=false`; controles habilitados | `el trabajo periodico programado no marca la fuente en curso` (:245) | No ejecutada (ver §6); pendiente de CI |
| Periódico `RUNNING` ⇒ `true` | Matriz de `actualizacionActiva` (:273) | No ejecutada; pendiente de CI |
| Manual `ENQUEUED` ⇒ política manual | `el estado observado marca la fuente en curso` (:232, existente) | No ejecutada; pendiente de CI |
| Manual `RUNNING` ⇒ `true` | Matriz (:273) | No ejecutada; pendiente de CI |
| Manual `SUCCEEDED` ⇒ controles habilitados | `una actualizacion manual terminada no deja la fuente en curso` (:313) | No ejecutada; pendiente de CI |
| Manual `FAILED` / `CANCELLED` ⇒ habilitados | Matriz (:273) | No ejecutada; pendiente de CI |
| Periódico `ENQUEUED` + manual `RUNNING` ⇒ deshabilitado | Matriz (:273) | No ejecutada; pendiente de CI |
| Dos solicitudes manuales ⇒ sin duplicados + tag de tipo | `la actualizacion manual usa un nombre propio y no se duplica` (:184, ampliada) | No ejecutada; pendiente de CI |
| Reconciliación conserva la programación | `sin cambio de intervalo se conserva el trabajo` (:138, existente) + aserción de id en la prueba nueva (:245) | No ejecutada; pendiente de CI |
| periódico `ENQUEUED` + manual inexistente ⇒ habilitados | `el trabajo periodico programado no marca la fuente en curso` (:245) | No ejecutada; pendiente de CI |

La prueba de la matriz cubre los estados `RUNNING` que no se pueden observar de forma determinista con el ejecutor síncrono de WorkManager; usa el clasificador real de producción, no una copia.

Cobertura de ViewModels: no se añadieron pruebas nuevas porque su contrato no cambia y `FuentesViewModelTest` ya fija `enCurso → cargando` en ambos sentidos (S7a); `CatalogoViewModel` deriva `actualizando = solicitud.enCurso` (:692) con la misma semántica. La corrección restaura el valor que ambos consumen.

### 6. Resultados de validación

- `./gradlew ktlintCheck detekt --offline` → **BUILD SUCCESSFUL** (exit 0), incluyendo `:app:runKtlintCheckOverMainSourceSet` y `:app:runKtlintCheckOverTestSourceSet` sobre los archivos modificados.
- `./gradlew :app:ktlintMainSourceSetCheck :app:ktlintTestSourceSetCheck :app:detekt --offline` → **BUILD SUCCESSFUL** tras el último ajuste. ktlint parsea ambos archivos, por lo que la sintaxis Kotlin está validada.
- `git diff --check` → sin errores de espacios ni líneas en blanco.
- `./gradlew :app:testDebugUnitTest --tests "com.copernicotv.app.data.actualizacion.ProgramadorWorkManagerTest" --offline` → **BUILD FAILED antes de compilar las pruebas**, en `:app:processDebugResources`:
  `AAPT2 aapt2-8.7.3-12006047-linux Daemon #1: Daemon startup failed` / `Daemon #0: Daemon startup failed`.
  Causa de entorno: `aapt2` es ELF x86-64 y la máquina es `aarch64`; `./aapt2 version` responde `cannot execute: required file not found` y no hay qemu/box64 (ADR-0005). **Ninguna prueba se ejecutó.**
- `:core:test` no se repitió: el cambio no toca `:core` y su evidencia previa sigue válida.
- **CI: no ejecutada.** Requiere commit y push, prohibidos por la orden. No se declara PASS funcional ni de CI.

Comandos canónicos para ejecutar la validación cuando se autorice el push (`.github/workflows/ci.yml`):

```bash
./gradlew :core:test :app:testDebugUnitTest --stacktrace   # job jvm-tests
./gradlew ktlintCheck detekt --stacktrace                  # job static-analysis
```

### 7. Limitaciones conocidas

1. **Pruebas `:app` no ejecutables en este entorno** (AAPT2 x86-64/aarch64). La ejecución de la regresión queda en CI.
2. **CI no disparada** por la prohibición de commit/push; no se fabricó un resultado.
3. **Solicitud manual anterior a esta versión:** no lleva `TAG_MANUAL`, así que mientras siga `ENQUEUED` se clasifica como periódica y la UI podría habilitar el botón. `ExistingWorkPolicy.KEEP` sobre el mismo nombre único impide el duplicado y la ejecución no se duplica. No hay solicitudes de ese tipo en la instalación actual (no quedan trabajos manuales pendientes de la campaña).
4. **Suite instrumentada** (`ActualizacionManualTest`, `FuentesUiTest`) usa `ModuloProgramadorFalso` y publica `EstadoSolicitud` directamente; no depende del clasificador y no se ejecutó localmente (solo CI/emulador).
5. Las pruebas nuevas no se declaran aprobadas hasta que `jvm-tests` corra sobre este cambio.

### 8. Preservación del trabajo periódico

- `periodico()` no se modifica: mismo builder, nombre único `actualizar-fuente-<id>`, restricciones (red conectada, batería no baja), backoff y tags.
- `reconciliarFuente()` conserva `ExistingPeriodicWorkPolicy.KEEP` cuando el intervalo no cambió y no toca el valor por defecto de 720 minutos.
- La prueba nueva reafirma que tras `reconciliar()` el `id` (UUID) del trabajo periódico es el mismo.
- El reclamo (`abierto()`, `ESTADOS_ABIERTOS`) sigue considerando `ENQUEUED`, por lo que la exclusión DC-07 punto 6 no cambia.
- `solicitarFuente`/`solicitarLista` siguen usando `KEEP`, así que no se crean duplicados manuales.

### 9. Estado de los controles de Fuentes y Catálogo

- Solo periódico `ENQUEUED`: `enCurso=false` ⇒ «Actualizar» habilitado en Fuentes y en Catálogo; sin «Carga: actualizando».
- Cualquier ejecución `RUNNING`: `enCurso=true` ⇒ operaciones incompatibles deshabilitadas y «Carga: actualizando» visible (incluye la ejecución del planificador, como ya documenta `CatalogoViewModel`:664-672).
- Solicitud manual pendiente: deshabilitado hasta que termine.
- Al terminar (éxito, fallo o cancelación): `enCurso=false` ⇒ controles recuperados sin reiniciar la app.

### 10. Procedimiento para repetir CAM-09-D y CAM-09-E

Requisitos previos: APK con esta corrección ya integrado e instalado sobre los datos actuales (sin desinstalar ni borrar datos/caché), adb disponible y la Fuente `zonacero` existente. No modificar el catálogo ni la fuente durante la prueba.

**CAM-09-D (Fuentes):**
1. `adb logcat -c` y abrir Fuentes con la app en reposo (sin actualización en curso).
2. Confirmar que el trabajo periódico de la Fuente está `ENQUEUED`: `adb shell dumpsys jobscheduler | grep -A3 copernicotv` (o el visor de WorkManager de depuración).
3. Verificar que «Actualizar» está **habilitado** y que no se muestra «Carga: actualizando».
4. Pulsar «Actualizar»: el botón debe deshabilitarse mientras el trabajo está `RUNNING` y volver a habilitarse al terminar, con el resumen o el error visible.
5. Repetir una segunda pulsación en cuanto termine: no debe encolarse un segundo trabajo manual (`KEEP`).

**CAM-09-E (Catálogo):**
1. Con la Fuente del catálogo programada en `ENQUEUED`, abrir Catálogo.
2. Verificar que «Actualizar» (Lista activa) está habilitado y que no figura «actualizando».
3. Pulsar «Actualizar»: deshabilitado durante `RUNNING`, habilitado al terminar; el resumen corresponde a la Lista activa.
4. Alternar de Lista mientras el trabajo corre: `actualizando` debe seguir reflejando el trabajo de la Lista activa.

Evidencia a registrar en la próxima campaña (sin reescribir el informe forense existente): hora, estado del trabajo observado (`ENQUEUED`/`RUNNING`), estado del control antes/durante/después, resultado o código de error, y captura de la pantalla. Esa evidencia cierra CAM-09-D/E; este informe solo cubre la corrección de código.

