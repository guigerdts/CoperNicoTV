# ADR-0008 — Decodificación de video ampliada: inventario en fase 1 y ruta LGPL para MPEG-2

Status: accepted (fase 2 ejecutada en E7-T12)

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

**Fase 2 (ejecutada, E7-T12):** decodificación software real bajo LGPL por la ruta C: subconjunto
FFmpeg 7.1.1 (decoders `mpeg2video`, `mpeg4`, `h263`) compilado estáticamente desde el tarball
oficial con SHA-256 verificado (`scripts/ffmpeg/build-ffmpeg.sh`), enlazado en JNI propio
(`app/src/main/cpp/`) y expuesto con `DecoderFfmpeg` + `RendererVideoSoftware`, un
`DecoderVideoRenderer` sobre la API pública de Media3 1.5.0, registrado al final de la política por
`FabricaRenderersSoftware` en `SesionMedia3`. El build declara GPL y nonfree desactivados y el
script **falla** si la `config.h` generada no lo declara (`CONFIG_GPL 0`, `CONFIG_NONFREE 0`); los
avisos de redistribución viven en `THIRD_PARTY_NOTICES.md`. La fase 1 queda intacta: los decoders
externos siguen entrando **al final** de la política y solo cubren MIME sin decoder nativo; el
H.264 del dispositivo no cambia de camino (regresión instrumentada incluida). El soporte MPEG-2
solo se declara con imagen real en Surface (S9); la validación física en dispositivo queda
registrada en el informe de cierre.

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

## Resultado de la ruta C (E7-T12)

La ruta C fue ejecutada: el subconjunto LGPL de FFmpeg se compila para las tres ABIs del APK
(`arm64-v8a`, `armeabi-v7a`, `x86_64`) en el paso de CI dedicado, con caché por hash del script.
El H.264 sigue en hardware; los fixtures instrumentados (`app/src/androidTest/assets/e7t12/`)
validan MPEG-2, MPEG-4 Part 2 y H.263 end-to-end, y la regresión H.264 verifica que el decoder
externo no interviene. El veredicto final de la matriz queda supeditado a la CI verde y a la
validación física pendiente (S9).

## Consecuencias

- El diagnóstico ahora explica el veredicto con datos reales del dispositivo y lo persiste (v4);
  Telecaribe 2719 mostrará el motivo exacto y la ausencia de decoder sin finge soporte.
- Los veredictos son de disponibilidad, no de ejecución: un «fallo de inicialización» real solo
  puede observarse cuando exista un decoder externo que se inicialice (fase 2).
- La fase 2 no rompe la fase 1: los decoders externos entran **al final** de la política y solo
  cubren MIME sin decoder nativo; el H.264 hardware actual no cambia de camino.
- Coste de la fase 2: NDK en CI, ABIs por cada `abiFilters`, y mantenimiento de la cadena nativa.
