# ADR-0001 — Estructura de módulos: `:core` (Kotlin puro) y `:app` (Android)

Status: accepted

## Contexto

El desarrollo ocurre en Termux (Ubuntu aarch64, JDK 17, sin Android SDK local). El único ciclo de feedback automatizado local posible es la ejecución de tests JVM vía Gradle Wrapper. Trasladarlo todo a contexto Android haría imposible verificar cualquier lógica fuera de CI.

## Decisión

Estructura de **dos módulos** desde el primer commit:

1. **`:core` (Kotlin puro, JVM)**: modelos de dominio, parser M3U (ADR-0006), parser XMLTV (ADR-0003), identidad de canal (ADR-0004), reconciliación (Actualización), reglas de catálogo, historial, favoritos, ocultaciones. **Sin dependencias Android, sin Room, sin Hilt.** No se introducen interfaces de dominio o adaptadores que no tengan consumo real justificado.
2. **`:app` (Android)**: integración de la plataforma: Compose (móvil + TV con `androidx.tv:tv-material`), Media3/ExoPlayer, Hilt, Room, DataStore, Storage Access Framework. Los **adaptadores** traducen entre persistencia (entidades Room) y dominio (`:core`), sin exponer tipos Room fuera de la capa de datos.

Los límites se re-revisan cuando aparece acoplamiento real, no preventivamente (`:core-xml` u otros módulos solo si surge un segundo consumidor concreto).

## Consecuencias

- Los domain tests de `:core` corren localmente en Termux (`./gradlew :core:test`) **si el entorno lo permite**; se verifica en el bootstrap de E1 que el Gradle Wrapper y el JDK disponibles lo soportan, sin asumirlo previo.
- Toda lógica reusable por otra plataforma futura ya está aislada; agregar un consumidor no requiere mover código entre plataformas.

## Opciones consideradas

- **Multi-módulo fine-grained** (`:data`, `:player`, `:ui`...): descartado — burocría de build con un solo consumidor real por módulo y sin beneficio adicional de aislamiento respecto a `:core` + `:app`.
- **Módulo único**: descartado — el dominio quedaría acoplado a Android y se perdería el ciclo local de tests.
