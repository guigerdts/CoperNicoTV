# Sistema OTA de CoperNicoTV — documento de seguimiento (ODD)

Objetivo: actualizaciones in-app de GitHub Releases con descarga reanudable, verificación de integridad y autenticidad, instalación nativa y continuidad de datos. Rama base: `main`. Estrategia de entrega: `stacked-to-main` (PRs secuenciales a main). Runner: `./gradlew :core:test`; `:app` se valida en CI (ADR-0005).

## Specs

- **S1 — Comprobación de versión.** La app compara la versión instalada (`versionCode`) con el manifiesto publicado y ofrece actualización solo si `candidato.versionCode > instalada.versionCode`. Igual o menor no se ofrece. Canales: `stable` y `beta`; `beta` acepta ambos, `stable` solo estable.
- **S2 — Manifiesto de actualización.** JSON versionado con `schema`, `packageName`, `versionCode`, `versionName`, `channel`, `minSdk`, `publishedAt`, `sizeBytes`, `sha256`, `downloadUrl`, `releaseUrl`, `releaseNotes`, `mandatory`, `signingCertSha256`. Un campo faltante, tipo incorrecto, esquema desconocido, hash no hexadecimal de 64, tamaño no positivo o URL no HTTPS se rechazan con motivo tipado.
- **S3 — Autenticidad del manifiesto.** Firma ECDSA P-256/SHA-256 separada (`update.json.sig`) verificada con la clave pública embebida en la app. SHA-256 y URL publicados en el mismo canal comprometido no bastan: sin firma válida no hay actualización.
- **S4 — Verificación del APK.** Antes de instalar: tamaño exacto, SHA-256 exacto, `packageName` igual, `versionCode` igual al manifiesto y superior al instalado, huella SHA-256 del certificado de firma igual a la fijada en la app, `minSdk` compatible y APK legible por el instalador.
- **S5 — Descarga reanudable.** Descarga HTTPS con `Range` cuando el servidor lo admite, progreso observable, archivo `.part` reutilizable, reintentos acotados y limpieza de APK parciales o de versiones ya no ofrecidas.
- **S6 — Instalación nativa.** `PackageInstaller` con sesión, confirmación del sistema, permiso `REQUEST_INSTALL_PACKAGES` y autorización por app. No hay instalación silenciosa. La actualización preserva paquete, datos privados, Room, DataStore y URL cifradas.
- **S7 — Notificaciones.** Canal propio `actualizaciones_app`, distinto de la actualización de listas IPTV. Notifica versión disponible, progreso y listo para instalar. Permiso `POST_NOTIFICATIONS` en API 33+.
- **S8 — Programación aislada.** Comprobación periódica (12 h, red disponible, batería no baja) y manual desde Ajustes. Tags y nombres únicos `ota-*`; no comparte tags, nombres ni estado con los workers de listas M3U/M3U8.
- **S9 — Ajustes > Actualizaciones.** Muestra versión instalada, canal, buscar, versión disponible, notas, descargar, progreso, instalar, estado automático y última comprobación. Legible y navegable con D-pad en TV y usable en móvil, con el sistema visual azul existente.
- **S10 — Firma de producción.** Keystore permanente fuera del repositorio, en GitHub Actions Secrets. APK release y de producción con la misma identidad. `versionCode` crece en cada publicación. APK debug y release se distinguen. Respaldo y recuperación documentados.
- **S11 — Distribución.** GitHub Releases como canal oficial: APK firmado, `update.json`, `update.json.sig`, SHA-256, notas, canal y compatibilidad. Publicación automatizada en GitHub Actions.
- **S12 — Validación física.** Demostrar en Samsung Galaxy A35: instalación limpia release, importación M3U, publicación de segunda versión, detección, notificación, descarga verificada, instalación sobre la anterior, conservación de listas/URL/favoritos/configuración y apertura correcta. Coordinar con F-10 y CAM-09; no repetir CAM-09-D/E mientras ADB esté deshabilitado.

## Tasks

- T1 — Dominio OTA en `:core` + pruebas JVM. S1, S2, S3, S4 (lógica pura). Estado: **completada** — commit `173bff1`; 42 tests en `:core` (incluye `PublicacionesGitHub`), ktlint y detekt en verde local.
- T2 — Infraestructura app: cliente HTTP, fuente GitHub, comprobador, descargador y verificador APK + pruebas. S1–S5. Estado: implementada, pendiente de validación CI (`:app` no compila en Termux).
- T3 — Instalador PackageInstaller, notificaciones, workers y programación aislada + pruebas. S6–S8. Estado: pendiente.
- T4 — Pantalla Ajustes > Actualizaciones y navegación móvil/TV + pruebas. S9. Estado: pendiente.
- T5 — Firma permanente, workflow de release, manifiesto y documentación de respaldo/recuperación. S10, S11. Estado: pendiente.
- T6 — Validación física F-13..F-16 y cierre documental. S12. Estado: bloqueada por ADB/Wi-Fi y por el gate de firma.

## Log

- L1 — Pedido del propietario (2026-11-20): sistema OTA integral con GitHub Releases, firma permanente, continuidad de datos y validación física; el sistema no se declara terminado por compilar.
- L2 — El propietario elige estrategia de entrega `PRs apilados a main` para mantener revisiones acotadas.
- L3 — T1 implementada en `:core`: `ManifiestoActualizacion` (parse estricto con motivos tipados), `CanalActualizacion`, `SeleccionActualizacion`, `FirmaManifiesto` (ECDSA P-256) e `Integridad` (SHA-256 streaming y comparación constante). Evidencia: `./gradlew :core:test --tests "com.copernicotv.core.ota.*"` con 36 tests, `:core:ktlintCheck` y `:core:detekt` en verde. Riesgo: item 3 (contrato entre workflow y app), cubierto con pruebas de rechazo.
- L4 — T1 commiteada en `feat/ota-1-dominio` (`173bff1`) con la revisión GGA en verde por subsets. Al integrar T2 se sumó `PublicacionesGitHub` (6 tests) al dominio: el descubrimiento del release por `api.github.com` también es lógica pura y se prueba sin Android. Total `:core` ota: 42 tests.
- L5 — T2 implementada en `:app`: `ClienteHttpOtaUrlConnection` (validación de esquema/host antes de abrir socket, redirecciones revalidadas, cuerpo acotado, descarga con `Range`), `DescargadorApk` (reanudación `.part`, progreso, limpieza), `VerificadorApk` (tamaño, SHA-256, paquete, versionCode, huella de certificado, minSdk), `FuenteManifiestosGitHub`, `ComprobadorActualizaciones`, `PreferenciasOta` y grafo Hilt. Tests contra servidor TLS local para el cliente real. Validación de compilación delegada a CI por ADR-0005.
