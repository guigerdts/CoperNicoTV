# ADR-0009 — Persistencia del estado OTA (DataStore + instantánea del manifiesto)

Status: accepted (2026-11-21). Excepción documentada y acotada a ADR-0002.

## Contexto

El sistema de actualizaciones de la app (S1–S12, `odd/tasks/ota-actualizaciones.md`)
necesita conservar entre arranques:

- el canal elegido por el usuario (`stable`/`beta`),
- el instante de la última comprobación correcta,
- el `versionCode` que el usuario decidió omitir,
- y el último manifiesto **ya verificado**, con sus bytes originales y su firma,
  para poder mostrar «versión disponible» y sus notas al reabrir la app sin
  volver a consultar GitHub.

ADR-0002 reserva Room para entidades de persistencia y DataStore para
configuración global del usuario. El manifiesto verificado no es lo primero
(no tiene identidad, relaciones, consultas, índices ni historial: es un
documento único que se lee completo) y tampoco es del todo lo segundo (no es
una preferencia del usuario). Sin una decisión explícita, la implementación de
T2 quedaba fuera del ADR-0002.

## Decisión

- El OTA usa un **DataStore propio** (`ota_actualizaciones`), separado del de
  las Listas M3U/M3U8. Los dos sistemas comparten proceso pero nunca archivo ni
  claves, igual que sus workers (S8).
- En ese DataStore se persisten como preferencias/estado de interfaz:
  `canal`, `ultima_comprobacion_ms` y `version_omitida`. Son valores escalares,
  elegidos o producidos por el usuario, y encajan sin discusión en ADR-0002.
- **Excepción documentada a ADR-0002:** la instantánea del manifiesto verificado
  (`manifiesto_base64`, `firma_base64` y el instante en
  `ultima_comprobacion_ms`) se persiste en el mismo DataStore y no en Room.
  - Es **caché descartable**, no una entidad: perderla degrada a «volver a
    consultar el canal» y nunca pierde datos del usuario.
  - Es **un solo valor**, de alrededor de 1 KiB, que se lee siempre completo y
    se re-verifica al leerlo (firma ECDSA y SHA-256 del binario). No se
    consulta, no se filtra, no se relaciona y no tiene historial.
  - Room aportaría tabla, DAO, migración y consultas para un blob opaco que
    nunca participa de un `join`, a cambio de una migración de esquema para
    datos que pueden simplemente descartarse. DataStore ofrece justo lo que
    este dato necesita: lectura y escritura atómicas de un valor pequeño.
  - La re-verificación al leer es la razón de guardar bytes y firma en lugar de
    campos ya parseados: un archivo alterado no puede pasar por manifiesto
    firmado. El manifiesto y su firma son documentos públicos; no contienen
    credenciales.

## Límites de la excepción

Esta excepción cubre **un** documento verificado y **un** canal activo. Si el
OTA necesita historial de publicaciones, consultas por canal o por versión,
varias instantáneas simultáneas o cualquier dato que participe de relaciones,
eso se modela como entidad Room y exige revisar este ADR. Guardar APKs o notas
extensas en DataStore queda fuera de alcance en todos los casos: el APK vive en
el almacenamiento de la app como archivo (S5) y sus metadatos ya están en el
manifiesto.

## Consecuencias

- Una sola excepción, explícita y con criterio de salida, en lugar de una
  migración de esquema sin beneficio.
- El borrado del estado OTA es completo y seguro: eliminar el archivo DataStore
  solo cuesta una consulta nueva al canal.
- Cualquier crecimiento del dato (historial o consultas) obliga a volver a este
  ADR y a ADR-0002, no a ampliar el uso de DataStore por costumbre.

Referencias: ADR-0002, ADR-0005, `odd/tasks/ota-actualizaciones.md` (S1, S5, S8).
