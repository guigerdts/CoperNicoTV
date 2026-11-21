# Registro central de validaciones físicas

Fuente primaria del estado de validación física del MVP. Cada entrada cita su entrega y su feature doc; este registro no duplica detalle técnico, solo consolida evidencia con identificador estable. Referencia cruzada: `odd/tasks/mvp-copernicotv.md` §8 y §10.4.

Clasificación de niveles: **CI** (automatizada en GitHub Actions) · **Instrumentada** (prueba automatizada en dispositivo/emulador con aserciones) · **Emulador** (CI con emulador) · **Físico** (dispositivo real del propietario) · **Documental** (comunicada por el propietario, registrada en doc, sin log artifact) · **Pendiente**.

## Índice

| ID | Entrega | Escenario | Clasificación | Resultado | Fuente |
|---|---|---|---|---|---|
| F-01 | E7-T9 | 10 canales reales READY | Físico (documental) | READY × 10 | `odd/tasks/e7-t10-recuperacion-estabilidad.md` (S1, registro retrospectivo) |
| F-02 | E7-T9 | Caracol ID 2571 | Físico (documental) | HTTP 302→403 | mismo doc |
| F-03 | E7-T10 | Recuperación Wi-Fi / zapeo / VOD | Físico (documental) | Comunicada por propietario | mandato E7-T13 §4.2 + doc feature |
| F-04 | E7-T11 | Telecaribe 2719 (pre-E7-T12) | Físico (documental) | `video/mpeg2` MIME_NO_SOPORTADO | mandato E7-T13 §4.2 |
| F-05 | E7-T11 | Caracol 2704 | Físico (documental) | `video/avc` Exynos HARDWARE | mandato E7-T13 §4.2 |
| F-06 | E7-T12 | MPEG-2 software, matriz | Emulador (CI) | `ffmpeg:mpeg2video`, primer frame 365 ms | run `44008034924` |
| F-07 | E7-T12 | Telecaribe 2719 (post-E7-T12) | Físico (documental) | `video/avc` avc1.640029 1920x1080 HARDWARE | mandato E7-T13 §4.2 |
| F-08 | E7-T12 | Caracol 2704 (post-E7-T12) | Físico (documental) | H.264 High hardware OK | mandato E7-T13 §4.2 |
| F-09 | E7-T7 | Importación física (S33/S35/S36/S42, I-M) | Pendiente | sin evidencia recuperada | checklist E7-T7 |
| F-10 | E7-T7 ronda 9 | Importación ~147k canales | Pendiente | medición aproximada sin confirmación | `e7-t7-ronda9-metricas-room.md` |
| F-11 | E7-T6 | Duck de audio | Pendiente | atenuación no medible por API pública | matriz §L91 |
| F-12 | E4-T7 (DC-07 punto 8) | Reinicio del dispositivo / force-stop: el trabajo periódico de WorkManager sobrevive y se reanuda en el próximo arranque, sin hora exacta | Pendiente | sin evidencia física recuperada; en CI queda cubierto por persistencia de plataforma y reconciliación idempotente (`ProgramadorWorkManagerTest`) | `odd/tasks/e4-t7-dc-07-implementacion-cierre.md` §D |
| F-13 | OTA T6 | Instalación limpia del primer APK release firmado + importación M3U | Pendiente | requiere APK release y desinstalar la versión de pruebas | `odd/tasks/ota-actualizaciones.md` |
| F-14 | OTA T6 | Publicación de una segunda versión: detección, notificación y descarga verificada | Pendiente | requiere un release posterior al instalado | mismo doc |
| F-15 | OTA T6 | Instalación sobre la versión anterior, retorno a la app y conservación de datos | Pendiente | requiere F-13 y F-14 | mismo doc |
| F-16 | OTA T6 | Caminos de error en dispositivo: manifiesto inválido, firma incompatible, hash incorrecto, APK corrupto, descarga interrumpida y duplicada | Pendiente | requiere un canal de pruebas controlable | mismo doc |

## Detalle

### F-01 — E7-T9: diez canales reales en READY
- **Fecha**: registrada en la apertura de E7-T10 (2026-11-14 aprox.). **Entorno**: dispositivo físico del propietario. **APK**: debug artifact del run `43731405595` (`b0564c3`) o posterior cercano.
- **Canales**: HBO, Cinecanal, FX (1374), Caracol (2794/2795), Dragon Ball, SupermusicaTV, MTV, Cinemax, Telecaribe, ESPN — todos READY.
- **Diagnóstico**: falló Caracol ID 2571: HTTP 302 inicial → HTTP 403 final, `text/html`, 72 bytes.
- **Limitaciones**: registrada retrospectivamente en el doc de E7-T10 (el doc de E7-T9 la clasificaba como FIS pendiente); sin captura de panel en el repo.
- **Nivel de confianza**: alta para compatibilidad general (10/10 canales representativos), media para diagnóstico de un canal específico.

### F-02 — E7-T9: Caracol 2571, 302→403
- Mismo escenario de F-01. **Resultado**: 302 redirige a un recurso que responde 403 con `text/html` de 72 bytes (página de error del servidor, no stream). **Causa raíz**: externa al reproductor (prohibición del servidor); motivó el diagnóstico de E7-T10 (código `VIDEO_NO_ELEGIBLE`).

### F-03 — E7-T10: recuperación y estabilidad (comunicada por el propietario)
- **Escenarios**: pérdida y recuperación de Wi-Fi; cambio de canal durante recuperación; conservación de posición en VOD; mensajes de recuperación superpuestos al vídeo.
- **Resultado observado**: recuperación tras pérdida de red operativa; la evidencia exacta de cada escenario fue comunicada en conversación y no quedó registrada en el repo con log/panel; se registra como evidencia documental.
- **Prueba sin confirmar**: HTTP 403 con el canal anterior ya no disponible en la lista — no repetible.
- **Limitaciones**: sin registro de panel por escenario; no reproducible tal cual (lista cambió).
- **Pendiente asociado**: protocolo de `e7-t10:118` (Telecaribe 2719 >10 s + panel) sigue abierto para la campaña física consolidada.

### F-04 — E7-T11: Telecaribe 2719 antes del decoder software
- **Entorno**: dispositivo físico (Exynos). **Resultado**: pista de vídeo `video/mpeg2` con motivo `MIME_NO_SOPORTADO` (pista no soportada); audio funcional; sin decoder de vídeo disponible. **Fuente**: mandato E7-T13 §4.2; consistente con la limitación previa de Media3 sin MPEG-2.
- **Nota**: el mismo canal entrega hoy H.264 (F-07): el contenido del stream cambió entre sesiones.

### F-05 — E7-T11: Caracol 2704 con decodificación hardware
- **Resultado**: `video/avc`, perfil `avc1.640028`, 1280x720, decoder `c2.exynos.h264.decoder`, imagen y audio correctos, primer frame registrado. **Inventario**: decoder H.264 hardware Exynos presente.

### F-06 — E7-T12: MPEG-2 por software en CI (emulador)
- **Clasificación**: CI (instrumentada en emulador). **Run**: `44008034924` (commit `2639577`), 5/5 jobs.
- **Resultado**: matriz completa PASS — `decoderVideo=ffmpeg:mpeg2video`, `msHastaPrimerFrameVideo=365`, `msInicializacionDecoderVideo=8`, `tipoDecoderVideo=SOFTWARE`, `motivoSoporteVideo=SOPORTADO`, `videoRendererActivo=true`; MPEG-4 Part 2, H.263 y regresión H.264 (nunca sale por `ffmpeg:*`) verdes.
- **Limitación**: audio del fixture con AAC (el emulador x86_64 no tiene decoder MediaCodec para MP2).

### F-07 — E7-T12: Telecaribe 2719 en dispositivo (post-E7-T12)
- **Resultado**: imagen y audio correctos; **`video/avc`**, `avc1.640029`, **1920x1080**, decoder `c2.exynos.h264.decoder`, `HARDWARE`, primer frame 00:04, inicialización 356 ms.
- **Lectura obligatoria**: la fuente entregó H.264, no MPEG-2. **La reproducción física H.264 está demostrada; el fallback FFmpeg MPEG-2 NO está demostrado físicamente** — queda pendiente de una fuente que entregue realmente `video/mpeg2`. No es un fallo de E7-T12 ni bloquea el MVP.

### F-08 — E7-T12: Caracol 2704 en dispositivo (post-E7-T12)
- **Resultado**: imagen y audio correctos con decoder hardware Exynos, H.264 High. Coincide con F-05 (hardware consistente entre sesiones).

### F-09 — E7-T7: verificación física de importación — PENDIENTE
- Checklist S33/S35/S36/S42 y captura de tráfico I-M de S47d/S47e (DC-11) sin ejecutar; sin evidencia física recuperada en repo ni conversación. Pendiente de la campaña física consolidada.

### F-10 — E7-T7 ronda 9: importación ~147k canales — PENDIENTE
- Desglose de `tiempo_guardado_ms` medido en CI (inserción 48,860 s sobre ~146.859 filas, 148 lotes); la confirmación en dispositivo con la próxima importación real de ~147.000 canales sigue abierta (`e7-t7-ronda9-metricas-room.md:27`). No declarar la optimización física resuelta.
- Actualización P0 (2026-11-20): la **actualización** de 147.000 canales ya está validada en CI (`:app:testDebugUnitTest`, run `44741157641`, ~13 min, SHA integrado `526d897`); no sustituye a F-10, que sigue siendo la comprobación **física** en el Samsung. Sin ADB/Wi-Fi y sin autorización para instalar, desinstalar ni borrar datos. Ver `odd/tasks/p0-actualizacion-oom.md` §L7/L8.

### F-11 — E7-T6: atenuación del duck — PENDIENTE
- El multiplicador de volumen del duck es interno del sink de Media3; la atenuación efectiva no es medible por API pública (`mvp:4862`). Verificación física de percepción pendiente en campaña consolidada.

### F-13 — OTA: instalación limpia release + importación — PENDIENTE
- **Procedimiento**: desinstalar la versión de pruebas (pierde datos, aceptado y autorizado); instalar el APK release publicado; abrir la app; importar una Lista M3U real; confirmar catálogo y reproducción.
- **Resultado esperado**: la app arranca, la Lista importa con su informe, el canal OTA informa la versión instalada y el canal elegido en Ajustes > Actualizaciones.
- **Estado**: pendiente. Requiere un APK release firmado con el keystore de producción, que a su vez requiere las claves de T5.
- **Bloqueo**: ADB y Wi-Fi del dispositivo deshabilitados; no se repiten CAM-09-D/E mientras sigan así.

### F-14 — OTA: segunda versión detectada, notificada y descargada — PENDIENTE
- **Procedimiento**: instalar la versión N; publicar la versión N+1 con `versionCode` mayor; esperar la comprobación periódica o usar "Buscar" en Ajustes; observar notificación de disponibilidad; descargar.
- **Resultado esperado**: notificación `actualizaciones_app` con la versión nueva; descarga con progreso; verificación de tamaño, SHA-256, `versionCode` y certificado antes de ofrecer instalar; sin re-descarga si se vuelve a comprobar.
- **Estado**: pendiente. Requiere F-13 y un segundo release.

### F-15 — OTA: instalación sobre la anterior y conservación de datos — PENDIENTE
- **Procedimiento**: desde F-14, aceptar el diálogo del sistema; volver a la app; revisar Listas, URL de cada Lista, favoritos, ocultaciones, historial, ajustes de reproducción y canal OTA.
- **Resultado esperado**: la app queda en la versión N+1 sin diálogo de desinstalación; todos los datos anteriores siguen presentes y las URL se descifran correctamente; la comprobación posterior informa "al día".
- **Estado**: pendiente. Requiere F-14. **Es el criterio que decide si el OTA está terminado**: compilar no lo demuestra.

### F-16 — OTA: caminos de error en dispositivo — PENDIENTE
- **Procedimiento**: con un canal de pruebas controlable, provocar en orden: manifiesto con un campo faltante, firma modificada, hash de APK alterado, APK truncado, y corte de red a mitad de descarga seguido de reanudación.
- **Resultado esperado**: cada caso se rechaza con el motivo tipado, no se ofrece instalar, no se corrompe el estado guardado y la descarga interrumpida se reanuda en vez de repetirse desde cero. Una comprobación duplicada no descarga dos veces.
- **Estado**: pendiente. Requiere un release de pruebas que se pueda alterar sin afectar el canal real.
