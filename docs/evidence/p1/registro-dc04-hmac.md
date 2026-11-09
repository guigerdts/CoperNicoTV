# DC-04 — Ciclo de vida de la clave HMAC v1 (E2-T6)

Registro de evidencia de la implementación del ciclo de vida del índice HMAC.
Audiencia: revisión técnica de contratos maestros (DC-04) y del alcance E2-T6.

## Alcance de esta entrega

Brechas cerradas (plan E2-T6/DC-04, Q1–Q3 del propietario):

1. **Q1 (IV administrado por `CifradoAes`)** — ratificada sin modificación de
   contratos. La sonda demostró que el sobre AES `0x01+IV12+ct+tag16` es
   administrado por `CifradoAes` con clave no exportable; no se cambió ningún
   contrato.
2. **Q2 (inicialización y recuperación)** — la API de claves separa
   `claveHmac(generacion)` (solo lectura; jamás genera) de
   `crearClaveHmac(generacion)` (explícita e idempotente). El guardia de sello
   ([`IndiceHmacRepositorio.selloHmac`](../../app/src/main/kotlin/com/copernicotv/app/data/IndiceHmacRepositorio.kt)
   distingue la primera inicialización legítima —sin fila de estado ni sobres
   `hmac:vN`— de la pérdida real (fila o sobres existen), y transiciona a
   `recuperacion_pendiente`.
3. **Q3 (wiring funcional sin UI)** — detección al arranque
   (`GuardiaArranqueIndiceHmac` en `CoperNicoApplication.onCreate`, segundo
   plano), guardia en los ocho sitios de sellado, bloqueo efectivo de igualdades
   dependientes (`leer`, caché hit/actualización/invalidación, huella de guía),
   reconstrucción de las ocho columnas contractuales y reingreso funcional de
   filas pendientes. UI reservada a E7-T6.

## Reconstructor: ocho columnas y campos

| Tabla | Columna | Campo de sellado | AAD AES |
| --- | --- | --- | --- |
| `Canal` | `urlStreamHmac` | `CANAL_URL_STREAM` (0x02) | `Canal.url_stream_cifrada` |
| `Canal` | `logoUrlHmac` | `CANAL_LOGO_URL` (0x03) | `Canal.logo_url_cifrada` |
| `Lista` | `ubicacionHmac` | `LISTA_UBICACION` (0x01) | `Lista.ubicacion_cifrada` |
| `ObservacionPendienteImportacion` | `urlHmac` | `CANAL_URL_STREAM` (0x02) | `ObservacionPendienteImportacion.url_cifrada` |
| `ObservacionPendienteImportacion` | `logoUrlHmac` | `CANAL_LOGO_URL` (0x03) | `ObservacionPendienteImportacion.logo_url_cifrada` |
| `CacheM3U` | `urlHmac` | `CACHE_M3U_URL` (0x06) | `CacheM3U.url_cifrada` |
| `CacheXmltv` | `urlHmac` | `CACHE_XMLTV_URL` (0x07) | `CacheXmltv.url_cifrada` |
| `FuenteGuia` | `urlHmac` | `FUENTE_GUIA_URL` (0x08) | `FuenteGuia.url_cifrada` |

Decisión clave: las columnas de observación se reconstruyen con el mismo campo
con el que se sellaron (sobres de canal a la espera de promoción), para no
mover el `campo_id` dentro del sobre y romper la promoción a `Canal`. Los
campos `OBSERVACION_URL` (0x04) / `OBSERVACION_LOGO_URL` (0x05) permanecen
definidos en el contrato pero sin uso en este ciclo (crédito: análisis de
brechas E2-T6). `FuenteGuia` no tiene escritor en `main` hoy: su AAD es
anticipado y su sobre se calcula solo si existe valor.

## Q1 — Justificación del IV administrado y riesgo residual

- **Justificación**: el IV aleatorio de 12 bytes por cifrado bajo AES-GCM y la
  clave AES (no exportable, Android Keystore) son administrados por
  `CifradoAes`; la rotación HMAC solo re-sella la columna HMAC (IV nuevo para
  la restauración del valor original en reingreso). No se introduce un segundo
  esquema de IV.
- **Riesgo residual aceptado**: la pérdida de la clave AES no es recuperable
  (sin regeneración silenciosa; los valores cifrados AES quedan irrecuperables
  y el reingreso de cada columna es la única restauración). Comportamiento
  aceptado por diseño: los sobres HMAC faltantes/ilegibles devuelven rutas
  gráciles (`recuperacion_pendiente`, igualdades bloqueadas como omisión
  segura).
- **Compatibilidad de sobres**: formato `hmac:vN:...` y alias
  `copernicotv.hmac.v1` se conservan; la nueva API añade generación explícita
  sin romper los sobres existentes.

## Q3 — Invariantes operativas

- Detección al arranque: instala virgen inicializa `v1` explícitamente;
  fila de estado operativa sin clave en la bóveda declara pérdida real
  preservando fila y sobres; sobres sin fila de estado declaran la pérdida a
  partir de la fila v1 materializada; recuperación abierta no se toca.
- Bloqueo efectivo (código `indice_hmac_no_operativo`): en
  `CatalogoRepositorio` (texto, URL, SAF, `persistir`/`escribirCatalogo`),
  `CatalogoCrudRepositorio` (crearLista/crearCanal), `ActualizacionRepositorio`
  (`leer()` completo; sin descarga ni sellado), `RepositorioCache`
  (hit → `SinCopia` como omisión segura; actualización → `Fallo`; invalidación
  pospuesta) y `EpgRepositorio` (huella sin clave → ruta grácil de E3 con
  `requiereClaveHmac`).
- Reconstrucción: solo en `recuperacion_en_curso`; incorpora de forma
  explícita e idempotente la clave de la generación objetivo
  (`crearClaveHmac`); conserva `cvM` de origen; idempotente y reanudable.
- Reingreso: solo con reconstrucción abierta (`recuperacion_en_curso` o
  `bloqueado_parcial`); restaura cifrado (IV nuevo) y sobre (generación
  objetivo); al resolver la última pendiente cierra con `resolverPendientes`
  (exige `filasReconstruidas == filasTotales` y `filasPendientes == 0`).

## Pruebas

- `:core:test` (JVM local): suite del parser y del ciclo HMAC de `:core`
  incluye la propagación de generación en `ImportadorM3U`.
- Tests de app módulo (Robolectric, se ejecutan en CI, job `jvm-tests`):
  `ReconstructorIndiceHmacTest` (8 columnas, promoción de observación,
  reingreso funcional y rechazos tipados, idempotencia),
  `GuardiaArranqueIndiceHmacTest` (inicialización legítima, pérdida real,
  recuperación en marcha, sobres sin fila),
  `IndiceHmacRepositorioTest` (guardia de sello), suites preexistentes de los
  repositorios sellados.
- `:app` no compila localmente en Termux (ADR-0005); la autoridad de build y
  de tests instrumentados es GitHub Actions (workflow `ci.yml`, 5 jobs).

## Evidencias asociadas

- CI: run de la rama `e2-t6-dc04-recuperacion` (5 jobs; verificar verde tras
  el push de cierre).
- Sin verificaciones físicas pendientes para este ciclo (no requiere
  dispositivo: cobertura JVM/Robolectric).
