# ADR-0008 — Decodificación de video ampliada: inventario en fase 1 y ruta LGPL para MPEG-2

Status: accepted

## Contexto

El caso físico Telecaribe 2719 (TS directo) declara `video/mpeg2` y el diagnóstico de E7-T10
clasificó la pista como no elegible: el dispositivo no tiene decoder MPEG-2 y la imagen no se
renderiza, mientras el audio funciona. La misma fuente en un equipo con decoder MPEG-2 funciona.
El diagnóstico era honesto pero no decía **por qué** ni distinguía decoder inexistente de formato
no soportado, perfil incompatible o resolución fuera de límites.

Restricciones autorizadas para esta fase (E7-T11):

- Sin dependencias GPL y sin incorporar `org.jellyfin.media3:media3-ffmpeg-decoder`.
- Sin NDK todavía; no modificar la licencia del proyecto; no simular decodificación software.
- No alterar la clasificación `VIDEO_NO_ELEGIBLE` (E7-T10 T3/T4) ni perjudicar la reproducción
  H.264 actual; no modificar innecesariamente la arquitectura Media3.

Investigación de la ruta GPL (con evidencia descargada e inspeccionada):

- La **extensión FFmpeg oficial** (`androidx.media3:media3-decoder-ffmpeg`) solo expone
  `FfmpegAudioRenderer`: no decodifica video.
- El **AAR de Jellyfin** (`org.jellyfin.media3:media3-ffmpeg-decoder:1.5.0+1`, GPL-3.0, ~2,9 MB,
  4 ABIs) contiene `ExperimentalFfmpegVideoRenderer extends DecoderVideoRenderer` con libavcodec
  compilando decoders de `mpeg2video`, `mpeg4`, `h263`, `h264`, `hevc`, `dvvideo`, `mjpegb` y
  `theora` (sin VP8/VP9/AV1/VC-1). Es la vía GPL-3.0 y por restricción queda **descartada**.

## Decisión

**Fase 1 (implementada):** servicio de inventario `InventarioDecodificadores` (`MediaCodecList`
REGULAR_CODECS → datos puros `DecoderVideo` de `:core`), política de selección pura
`seleccionarDecoderVideo` (hardware → software nativo → decoders externos registrados, con motivo
honesto: `PISTA_AUSENTE`, `MIME_NO_SOPORTADO`, `PERFIL_NO_SOPORTADO`, `RESOLUCION_NO_SOPORTADA`,
`SOPORTADO`), integración con el diagnóstico de sesión (+4 líneas y +4 columnas persistidas, Room
v4) y punto de integración `DecoderVideoExterno` (`:core`) para la fase 2. La selección se
consulta por formato detectado (memoizada) y **no** altera la selección real de Media3 ni la
clasificación `VIDEO_NO_ELEGIBLE`.

**Fase 2 (propuesta, sin implementar):** decodificación software real bajo LGPL, por la vía
principal descrita abajo, integrada por `RenderersFactory` propio en `ExoPlayer.Builder` y
registrada como `DecoderVideoExterno`. La fase 1 no declara soporte que no exista: el veredicto
del inventario es diagnóstico, no una promesa de reproducción.

## Alternativas para MPEG-2 (y codecs no soportados por el dispositivo)

| Alternativa | Licencia | Viabilidad | Mantenimiento | ABI | Tamaño | Rendimiento | Coste CI |
| --- | --- | --- | --- | --- | --- | --- | --- |
| A. Extensión FFmpeg oficial | BSD + LGPL de FFmpeg | **No**: solo audio (`FfmpegAudioRenderer`) | nulo | n/a | n/a | n/a | n/a |
| B. AAR Jellyfin | **GPL-3.0** | Alta (renderer probado, mpeg2/mpeg4/h263/h264/hevc) | del upstream | 4 | ~2,9 MB | Software CPU | cero (binario) |
| C. FFmpeg LGPL auto-build + port del renderer | LGPL-2.1+ (sin `--enable-gpl`) | Alta: `DecoderVideoRenderer` es API pública de Media3 1.5.0; los decoders `mpeg2video`/`mpeg4`/`h263` son LGPL | alto: cadenas de build y versiones de FFmpeg propias | 3–4 | ~2–3 MB por ABI | Software CPU | +20–30 min por build (NDK en CI) |
| D. `libmpeg2` de AOSP (Apache-2.0) + decoder propio | Apache-2.0 | Media: solo MPEG-2; port manual del patrón `C2SoftMpeg2Dec` a un `DecoderVideoRenderer` propio | medio: código propio sobre una base estable | 3–4 | <1 MB | Software CPU, optimizable por SIMD del original | +10–15 min (NDK en CI) |

Consideraciones comunes a C y D: exigen NDK en CI (ADR-0005 ya separa `jvm-tests`/`apk`/
instrumentados; el job de APK se encargaría del nativo), suben el tamaño del APK y la
decodificación es en CPU (el H.264 del dispositivo sigue por hardware; los decoders externos solo
cubren los MIME que el dispositivo no declara).

Decisión de ruta LGPL (fase 2): **C como principal** cuando el objetivo sea cubrir la familia
completa (MPEG-2, MPEG-4 Part 2, H.263) con una sola cadena de build; **D como opción mínima**
si el objetivo real es solo MPEG-2, evitando toda la cadena FFmpeg. B queda descartada por la
restricción no-GPL, no por capacidad técnica.

## Consecuencias

- El diagnóstico ahora explica el veredicto con datos reales del dispositivo y lo persiste (v4);
  Telecaribe 2719 mostrará el motivo exacto y la ausencia de decoder sin finge soporte.
- Los veredictos son de disponibilidad, no de ejecución: un «fallo de inicialización» real solo
  puede observarse cuando exista un decoder externo que se inicialice (fase 2).
- La fase 2 no rompe la fase 1: los decoders externos entran **al final** de la política y solo
  cubren MIME sin decoder nativo; el H.264 hardware actual no cambia de camino.
- Coste de la fase 2: NDK en CI, ABIs por cada `abiFilters`, y mantenimiento de la cadena nativa.
