# E7-T7 Ronda 8 — Envolvente AES 0x02 (clave de datos protegida por Keystore)

- Objetivo: eliminar el cuello de botella AES demostrado en el dispositivo (293.521 ops AES, 1.723.371 ms; Keystore ~5,87 ms/op) con una clave de datos de sesión protegida por la clave raíz no exportable; compatibilidad 0x01 intacta.
- Rama: `feat/ronda8-envolvente-0x02` → PR a `main`; CI verde; APK debug.
- Runner de pruebas: `./gradlew :core:test` (JVM local), `:app` tests en CI (ADR-0005); canarios instrumentados en CI.

## Specs

- S1 (inspección). Antes de modificar código: «Inspecciona primero: CifradoAes.kt. ClavesOperativas.kt. Bytes.kt. Repositorios y DAO que cifran o descifran datos. Recuperación de claves. Formatos 0x01 existentes. Documentación de seguridad y ADR relacionados. Identifica todos los caminos de cifrado y descifrado antes de modificar código.»
- S2 (arquitectura objetivo). «Mantener la clave raíz no exportable en Android Keystore. Generar una clave de datos aleatoria mediante CSPRNG. Proteger la clave de datos mediante una operación criptográfica de la clave raíz. Mantener la clave de datos disponible en memoria durante la sesión. Utilizar AES-GCM con IV único para cada registro. Evitar operaciones de Android Keystore por cada canal. Mantener separación entre cifrado, gestión de claves y persistencia. No introducir claves hardcodeadas ni secretos persistentes en texto plano.»
- S3 (compatibilidad y recuperación). «Los registros 0x01 existentes deben seguir siendo legibles. Los registros 0x02 deben utilizar la nueva clave de datos. No romper instalaciones existentes. La migración debe ser idempotente y recuperable tras interrupciones. Nunca eliminar los datos originales antes de verificar que los nuevos registros pueden descifrarse. Si la clave raíz desaparece o se invalida, conservar el comportamiento de recuperación pendiente ya establecido. No realizar una migración destructiva.»
- S4 (seguridad). «Mantener AES-GCM. IV único por operación. Validar autenticación antes de entregar datos descifrados. Zeroizar buffers temporales cuando corresponda. No exponer claves, credenciales ni material criptográfico en logs o diagnósticos. Documentar claramente el modelo de amenazas y el ciclo de vida de las claves.»
- S5 (instrumentación). «Mantener el informe de importación y ampliar las métricas para distinguir: Tiempo de generación de clave de datos. Tiempo de protección/desprotección de clave. Operaciones Keystore. Operaciones AES. Tiempo de cifrado. Tiempo de descifrado. Tiempo de inserción Room. Registros 0x01 y 0x02. Errores de autenticación y recuperación. No mezclar el tiempo de Room con el tiempo criptográfico.»
- S6 (pruebas obligatorias). «Agregar pruebas para: Lectura de registros 0x01. Escritura y lectura 0x02. IV diferente por operación. Detección de alteración de ciphertext. Clave de datos incorrecta. Clave raíz inválida. Migración interrumpida y reanudada. Migración repetida. Recuperación después de reiniciar el proceso. Ausencia de claves y credenciales en logs. Importación masiva con 146.762 registros simulados.»
- S7 (restricciones). «No modificar funcionalidades del reproductor. No modificar la política de reintentos HTTP. No modificar el formato M3U. No eliminar el watchdog. No modificar Room por optimización especulativa. No eliminar compatibilidad con 0x01. No declarar rendimiento resuelto con benchmarks JVM.»
- S8 (entrega). «Implementa la solución completa, corrige los fallos detectados y genera un PR integrado en la cadena de trabajo correspondiente. CI debe quedar verde. Genera APK debug descargable y entrega: 1. Causa raíz y cambios implementados. 2. Archivos y líneas modificados. 3. ADR y documentación actualizados. 4. Pruebas ejecutadas y resultados. 5. SHA del commit. 6. URL del artefacto APK en GitHub Actions. 7. SHA256 del APK. 8. Riesgos pendientes y protocolo de validación física.»

## Mapa de la inspección (S1, evidencia path:line)

- `core/src/main/kotlin/com/copernicotv/core/crypto/CifradoAes.kt`: dueño del formato `0x01 + IV(12) + ciphertext + tag(16)` (:41 `VERSION=0x01`, :72 sobre, :81 gate estricto de versión en `descifrar`). AAD por tabla.columna (:99-101). Cipher reutilizada por hilo, IV por operación (:62-72).
- `app/src/main/kotlin/com/copernicotv/app/crypto/ClavesApp.kt`: interfaz `claveAes` + HMAC por generación; `ClavesKeystore` (:79): alias `copernicotv.aes.v1` (:182), no exportable, `setRandomizedEncryptionRequired(false)` por contrato del IV del llamador (:163), sonda de compatibilidad `admiteSobrePropio` (:135-143) que regenera la clave si no admite IV propio (:123-132). Ese es el «comportamiento de recuperación» de la raíz AES a conservar.
- `app/src/main/kotlin/com/copernicotv/app/crypto/ClavesOperativas.kt`: patrón de ronda 6 para HMAC (operativa de sesión derivada de la raíz, una op de Keystore por proceso, zeroizar de intermedios :55). La envolvente 0x02 replica el patrón para AES con clave de datos CSPRNG.
- Caminos de cifrado (12 sitios en `app/src/main`, grep `CifradoAes.cifrar|descifrar`): escrituras — `PreparacionEntidadesCanales.kt:103/105` (helper `cifrarMedido`: URL y logo de cada canal), `CatalogoRepositorio.kt:640` (ubicación de lista), `CatalogoCrudRepositorio.kt:106/235` (crear/editar canal), `ActualizacionRepositorio.kt:657` (actualización de lista), `ObservacionRepositorio.kt:287` (movimiento de columna, descifra-cifra), `ReconstructorIndiceHmac.kt:202` (reingreso de valores), `cache/RepositorioCache.kt:464` (cache de URLs). Lecturas — `ReproductorRepositorio.kt:71` (resolución de reproducción), `ReconstruccionValorHmac.kt:84`, `ObservacionRepositorio.kt:285`, `ActualizacionRepositorio.kt:540`, `CatalogoCrudRepositorio.kt:299`. Todas pasan `claves.claveAes` (raíz).
- Persistencia disponible para la clave envuelta: DataStore de preferencias con patrón instalado (`PreferenciasOrden.kt:41/54`); ADR-0002 reserva DataStore para preferencias y Room para catálogo. La clave envuelta es un único blob binario, no dato de catálogo.
- Recuperación establecida: `GuardiaArranqueIndiceHmac.kt:53` transiciona a `recuperacion_pendiente` ante pérdida de raíz; la sonda de `ClavesKeystore.cargarAes` (:123-132) regenera la raíz AES incompatible sin destruir datos (todo sobre de datos usa IV del llamador). El modelo 0x02 no cambia ninguna de las dos.
- ADRs: `docs/adr/0001..0006`; este trabajo se documenta en ADR-0007.

## Diseño (resumen ejecutable)

- `:core` sigue siendo dueño del formato: `CifradoAes` agrega `VERSION_DATOS=0x02` con el MISMO layout (`0x02 + IV(12) + ciphertext + tag`), `cifrarDatos()` y descifrado por despacho de versión (`descifrarSobre(clave0x01, clave0x02, aad, sobre)`). El AAD no cambia: un valor sigue sin poder moverse entre columnas.
- `ClavesApp` agrega `claveDatos: SecretKey` (sesión): CSPRNG 32 bytes; se protege con la raíz (`CifradoAes.cifrar` raíz → sobre 0x01) y se persiste envuelta en DataStore; nunca se persiste en claro, nunca sale del proceso en claro. Al arrancar: si hay envuelta, UNA op de Keystore la desprotege y queda en memoria; si no, se genera y se envuelve (UNA op).
- Todas las escrituras de valores de datos pasan a 0x02 (clave de datos); las lecturas despachan por byte de versión. La envuelta y la sonda de raíz siguen en 0x01 con la raíz.
- La importación reescribe el catálogo completo dentro de la transacción existente: la primera importación con esta build es la migración (idempotente, atómica, no destructiva; los registros 0x01 viejos solo se reemplazan dentro de la transacción ya verificada).
- Instrumentación: métricas separadas de generación/protección de clave, ops Keystore, ops AES por versión, tiempo de cifrado/descifrado, tiempo Room, conteo 0x01/0x02.

## Tareas

- T1 (S1) — inspección mapeada (arriba). Completada.
- T2 (S2/S4) — `:core`: 0x02 en `CifradoAes` + tests de formato (IV único, tamper, clave incorrecta). Completada (commit `854e878`, RED por compilación, GREEN `:core:test`).
- T3 (S2/S3/S4) — `app`: clave de datos en `ClavesApp`/`ClavesKeystore` + persistencia envuelta en DataStore + ciclo de vida y recuperación. Completada (commit `bff080d`): `AlmacenClaveDatos` (archivo en `noBackupFilesDir`, síncrono) + `ClavesDatos` (CSPRNG 32B, envuelta 0x01 con la raíz, métricas separadas) + 5 tests JVM con fakes. Estática local verde; compilación `:app` verificada en CI (ADR-0005).
- T4 (S3) — call sites: escrituras → 0x02, lecturas → despacho de versión. Pendiente.
- T5 (S5) — instrumentación del informe (nuevas métricas separadas). Pendiente.
- T6 (S6) — pruebas obligatorias (lista completa). Pendiente.
- T7 (S7/S8) — ADR-0007, GLOSSARY, estática, CI verde, PR, APK + SHA256, informe final. Pendiente.

## Log

- L1 (2026-11-14). Solicitud ronda 8 (verbatim, resumen de alcance): «E7-T7 RONDA 8 — ELIMINAR EL CUELLO DE BOTELLA AES CON ENVOLVENTE 0x02». Evidencia física del usuario: importación Zona Cero completa con la APK de ronda 7 — 146.762 canales, 293.521 ops AES, tiempo_cifrado_ms=1.723.371, tiempo_insercion_ms=43.252, tiempo_hmac_ms=6.439, tiempo_guardado_ms=1.793.243, coste promedio AES ≈ 5,87 ms/op, CI 5/5. «La medición demuestra que Android Keystore está penalizando el cifrado individual de cada registro.» Objetivo verbatim: «Resolver el cuello de botella físico demostrado en la importación de Zona Cero» con envolvente AES 0x02 y clave de datos protegida por la clave raíz de Android Keystore. Restricciones y entrega verbatim en S1-S8. «No te detengas en un plan o una auditoría: ejecuta la implementación. No afirmes que el rendimiento está resuelto hasta medirlo en el dispositivo físico.»
