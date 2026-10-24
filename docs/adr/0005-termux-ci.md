# ADR-0005 — Desarrollo en Termux; Android real y CI en GitHub Actions

Status: accepted

## Contexto

El entorno local no tiene Android SDK: ensamblar APKs o ejecutar código Android es imposible localmente. El ciclo local existe para el Java/Kotlin de `:core` (ADR-0001). Las decisiones de plataforma Android (Compose, Media3, Room, Compose for TV, imagen de emulador TV) se validan en GitHub Actions.

## Decisión

### Entorno local (Termux)

- **Sí corre local**: `./gradlew :core:test` (tests JVM del dominio), lint parcial del módulo y operaciones de git.
- No corre local: ensamblar APK, tests instrumentados de `:app`, Compose runtime, Media3, Room bajo `:app`: pertenecen a CI exclusivamente.
- **No se afirma que Gradle Wrapper, JDK o Robolectric funcionan hasta verificarse.** El bootstrap de E1 valida Gradle Wrapper + JDK con un run de prueba y registra el resultado; si el entorno no permite correr tests localmente, la ejecución depende de CI y se documenta.
- Las pruebas JVM compatibles —incluyendo DAO (Room) y migraciones vía Robolectric cuando corresponda— se ejecutan localmente **si el entorno lo permite**, y siempre en CI.

### CI (GitHub Actions)

Jobs separados para diagnóstico claro y aprovechamiento de cache:

1. **`jvm-tests`**: tests JVM (`./gradlew test`), incluidos Robolectric cuando aplique.
2. **`static-analysis`**: ktlint + detekt.
3. **`apk`**: `assembleDebug`; el APK debug se publica como **artifact del workflow** (descargable).
4. **`instrumented-mobile`**: emulador (KVM) con pruebas Compose/instrumented móviles.
5. **`instrumented-tv`**: emulador Android TV, con pruebas de focus/DPad.

- **Imagen de emulador TV**: la configuración definitiva **no se fija de nombre** hasta que un workflow de prueba verifica que la imagen de sistema Android TV elegida (por ejemplo, vía `target: android-atv`, o la selección equivalente vigente) está disponible en los runtimes de Actions y arranca. Mientras ese bootstrap no confirme la imagen, el job `instrumented-tv` no se considera automático y sus verificaciones quedan marcadas pendientes-documentadas.
- **Canarios**: las pruebas instrumentadas incluyen interacciones **significativas** (importar, abrir catálogo, navegar), no solo arrancar la app.
- **No se declara un comportamiento "validado" por el solo hecho de compilar.** Compilar no valida nada de semántica.

### Verificaciones físicas

Toda verificación que exija dispositivo físico se registra en el **checklist de verificaciones físicas**, con tres campos obligatorios:

- **Procedimiento**: pasos exactos reproducibles por un humano.
- **Resultado esperado**: el comportamiento que constituye aprobación.
- **Estado**: `pendiente` hasta que alguien con dispositivo la ejecute (con fecha y resultado).

El MVP no se declara completamente validado mientras existan verificaciones físicas pendientes. Eso no justifica quitarlas del alcance: se mantiene la obligación de ejecutarlas cuando haya dispositivos disponibles.

## Consecuencias

- El tiempo de feedback para problemas de Android es el ciclo workflow (push → resultado en Actions), no un loop local rápido: los cambios de `:app` se diseñan con esa latencia en mente, y para eso existe el aislamiento de `:core` (ADR-0001).
- Los bugs corregidos llevan tests de regresión cuando sea aplicable (regla general del repo).

## Opciones consideradas

- **Instalar el Android SDK command-line tools en Termux (aarch64)**: descartado — la compatibilidad práctica (build-tools, emuladores x86) es frágil en aarch64 y el costo de mantenimiento supera el beneficio; Android real se verifica en el runtime de CI.
- **Emulador local en Termux (qemu)**: descartado — compite por RAM/CPU con la compilación y los tests en el mismo dispositivo, y añade riesgo de falsos fallos.
