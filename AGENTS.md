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
