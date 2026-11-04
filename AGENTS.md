# CoperNicoTV: reglas del proyecto

## Entorno
- Se desarrolla en Termux (Ubuntu aarch64, JDK 17, sin Android SDK local).
- Los módulos Kotlin puros se prueban con `./gradlew test` en JVM.
- El APK y los tests de Android se compilan en GitHub Actions.

## Kotlin
- Kotlin idiomático; `val` por defecto, sin `!!` salvo justificación.
- Coroutines y Flow para asincronía; nada de hilos manuales.
- Sin credenciales ni URLs privadas en el código.

## Arquitectura
- MVVM + repositorios; módulos por responsabilidad.
- La lógica de negocio no depende de Android (parser M3U, EPG, modelos).
- UI con Jetpack Compose y Material 3; TV con Compose for TV.

## Pruebas
- La lógica de dominio y el parser se desarrollan con TDD.
- Todo bug corregido lleva un test de regresión.

## Commits
- Mensajes convencionales (feat:, fix:, chore:, test:).

## Agent skills

### Issue tracker

Los issues viven en GitHub Issues del repo (CLI `gh`). Ver `docs/agents/issue-tracker.md`.

### Triage labels

Se usan los cinco labels canónicos por defecto (`needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`). Ver `docs/agents/triage-labels.md`.

### Domain docs

Repo single-context: `GLOSSARY.md` en la raíz + `docs/adr/`. Ver `docs/agents/domain.md`.

## Enfoque del proyecto

- El **MVP incluye TODAS las funcionalidades del README**, sin excepciones ni aplazamientos al post-MVP. Las entregas E1–E7 organizan la implementación; no recortan alcance. El plan de entregas y su criterio de cierre están registrados en `odd/tasks/mvp-copernicotv.md` (§8 y §9). El documento de funcionalidad del MVP (seguimiento por tarea) se publica en `odd/tasks/mvp-copernicotv.md`; su existencia no aprueba U0 ni inicia E1. La ruta `odd/tasks/` se crea con este documento.

## Entorno (Termux)

- Sí corre local: `./gradlew :core:test` (JVM, tests de dominio) si el entorno lo permite, verificado en el bootstrap de E1 (ADR-0005). No se asume Gradle/JDK/Robolectric funcionando hasta verificarlo.
- No corre local: ensamblar APK, instrumented tests, builds de `:app`. La compilación de APK y las pruebas instrumentadas (móvil + Android TV) ocurren **exclusivamente en GitHub Actions** (ADR-0005).
- La imagen de emulador Android TV se verifica con un workflow de prueba antes de fijar la configuración definitiva en CI.

## Arquitectura

- Dos módulos: `:core` (Kotlin puro, sin dependencias Android) y `:app` (Compose móvil + TV, Media3, Hilt, Room, DataStore). Los límites se revisan al aparecer acoplamiento real, no preventivamente (ADR-0001).
- Clave de Canal con ámbito de Fuente: `tvg-id` único en su ámbito, o clave canónica determinista (nombre normalizado + URL) documentada y versionada. La reconciliación de actualizaciones nunca fusiona canales ambiguos automáticamente (ADR-0004).
- Persistencia: Room para catálogo/Listas/Historial/Favoritos/Ocultaciones/EPG (con Paging e índices); DataStore solo preferencias. Identidad lógica independiente de la PK interna de Room; sin duplicar canales que aparecen en varias Listas (ADR-0002).
- Parsers (M3U y XMLTV) streaming y tolerantes a fallos, en `:core`; XMLTV con persistencia por lotes y retención configurable (ADR-0003, ADR-0006).

## Verificación

- CI separada en jobs: `jvm-tests` (incluido Robolectric cuando aplique), `static-analysis` (ktlint + detekt), `apk` (assembleDebug publicado como artifact), `instrumented-mobile` y `instrumented-tv` (emuladores).
- Los canarios de la suite instrumentada verifican interacciones significativas, no solo que la app arranca.
- Compilar nunca equivale a validar: no se declara comportamiento validado por solo compilarlo.
- Toda verificación que exija dispositivo físico queda documentada con: procedimiento exacto, resultado esperado y estado (`pendiente` hasta ejecutarse). El MVP no se declara completamente validado mientras existan verificaciones físicas pendientes; eso no justifica retirar funciones del alcance.

## Reglas de ejecución

- **Sin autorización explícita**: no instalar herramientas, no modificar configuración global, no publicar repositorios, no realizar cambios externos (remotos, cuentas, servicios).
- Antes de cada unidad de trabajo: presentar el **objetivo** y sus **criterios de aceptación**. Al finalizar: informar cambios, pruebas ejecutadas, resultados y limitaciones.
- Los bugs corregidos llevan test de regresión (regla existente, no se duplica aquí).
- Mensajes de commit convencionales (regla existente: `feat:`, `fix:`, `chore:`, `test:`).
