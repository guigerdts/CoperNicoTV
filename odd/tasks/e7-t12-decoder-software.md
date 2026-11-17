# E7-T12: Decodificación de vídeo por software (ruta LGPL)

Objetivo: decoder de vídeo software real, compatible LGPL, integrado con Media3 1.5.0 y con las
interfaces de E7-T11; primer objetivo MPEG-2. Rama: `feat/e7-t9-compatibilidad`. Sin merge.
Verificación local: `./gradlew :core:test :core:detekt :core:ktlintCheck :app:ktlintCheck :app:detekt` (compilación de `:app` y APK solo en CI, ADR-0005).

## Specs

Citas verbatim de la solicitud del usuario (2026-11-16). El alcance completo está en L1.

### S1 — Fase A: investigación técnica obligatoria
"Revisar ADR-0008 y la documentación existente." "Evaluar las alternativas LGPL documentadas y determinar: Compatibilidad con Media3 1.5.0. Disponibilidad de renderer de vídeo Android. Compatibilidad con Android API 26+. ABI ARM64 y ARM32. Dependencias nativas. Licencias de bibliotecas y componentes. Tamaño final del APK. Consumo de CPU y memoria. Mantenimiento y actualizaciones. Viabilidad de CI con NDK. Capacidad de renderizar directamente sobre Surface." "No seleccionar una alternativa basándose únicamente en que incluya FFmpeg. Debe existir una ruta técnicamente demostrable para entregar frames al Surface de Media3." **"Presentar la decisión técnica y sus evidencias antes de introducir dependencias."** ← CHECKPOINT explícito del usuario.

### S2 — Fase B: integración
"Implementar el renderer externo utilizando las interfaces existentes de E7-T11." Requisitos: "1. Mantener Media3 1.5.0. 2. Priorizar decodificación hardware. 3. Utilizar software solamente cuando el hardware no soporte el formato o perfil. 4. Mantener el comportamiento actual de H.264. 5. Integrar el renderer con el selector de decodificadores. 6. Conservar audio, sincronización A/V, subtítulos y controles de reproducción. 7. Gestionar correctamente Surface, liberación de recursos y cambios de canal. 8. Evitar decodificadores duplicados y fugas de memoria. 9. No simular soporte ni declarar reproducción exitosa antes del primer frame real."

### S3 — Fase C: matriz inicial
"Implementar y validar en este orden: 1. MPEG-2 Video. 2. MPEG-4 Part 2. 3. H.263. 4. H.264 como regresión. 5. HEVC como evaluación posterior." "VP8, VP9, AV1 y VC-1 quedan sujetos a la compatibilidad real de la alternativa seleccionada. No prometer soporte sin implementación y pruebas."

### S4 — Fase D: diagnóstico
"Ampliar los diagnósticos existentes para distinguir: Hardware seleccionado. Software seleccionado. Motivo de fallback. Decoder inicializado. Primer frame recibido. Error de inicialización. Error de decodificación. Tiempo hasta primer frame. Consumo y estabilidad cuando sea medible." "Mantener la distinción entre disponibilidad, inicialización y reproducción real."

### S5 — Fase E: calidad y rendimiento
"Medir: CPU. Memoria. Temperatura del dispositivo cuando la plataforma lo permita. Tiempo de inicialización. Tiempo hasta primer frame. Frames perdidos. Estabilidad de reproducción prolongada." "El decoder software no debe bloquear el hilo principal."

### S6 — Fase F: CI y pruebas
"Incorporar NDK únicamente si la alternativa seleccionada lo requiere. Configurar CI reproducible para las ABI necesarias. Añadir: Pruebas unitarias. Pruebas de integración del renderer. Pruebas instrumentadas. Pruebas de selección hardware/software. Pruebas de liberación de Surface y recursos. Regresión H.264. Validación de licencias y dependencias. Publicar APK debug."

### S7 — Restricciones obligatorias
"No incorporar dependencias GPL. No incorporar el AAR GPL-3 de Jellyfin. No cambiar la licencia del proyecto. No degradar el camino hardware existente. No modificar T3/T4 sin evidencia de necesidad. No romper Room v4. No eliminar diagnósticos existentes. No realizar merge. No ejecutar operaciones destructivas de Git. Conservar la rama y los cambios actuales. No introducir dependencias sin justificar su licencia y mantenimiento."

### S8 — Entrega
"1. ADR de decisión tecnológica actualizada. 2. Implementación real del renderer software. 3. MPEG-2 funcional como primer objetivo. 4. Integración con E7-T11. 5. Diagnósticos ampliados. 6. Pruebas y regresiones. 7. CI completo. 8. APK debug. 9. Informe con limitaciones conocidas y protocolo de validación física."

### S9 — Regla dura de cierre
"Trabaja por entregas verificables. No cierres la tarea declarando soporte MPEG-2 hasta demostrar reproducción de imagen real en un dispositivo Android."

## Tasks

- T1 (S1) — Fase A: verificación técnica con evidencias y presentación de la decisión; STOP hasta aprobación. Route: inline. **HECHO** (L2; aprobación L3).
- T2 (S2) — Fase B: módulo nativo FFmpeg LGPL (subconjunto mpeg2video/mpeg4/h263) con CMake + NDK en CI; JNI propio. Route: inline/escritura + build CI. **HECHO** (commit `c672ffe`; configure con sysroot corregido en `4149dcd`, CI pendiente).
- T3 (S2) — Fase B: decoder propio implementando `androidx.media3.decoder.Decoder`. Route: inline. **HECHO** (`c672ffe`; firmas Media3 corregidas en `6328933`, compilación verificada en CI jvm-tests).
- T4 (S2) — Fase B: `RendererVideoSoftware extends DecoderVideoRenderer` + RenderersFactory hardware primero + integración con selector E7-T11. Route: inline. **HECHO** (`c672ffe` + `6328933`).
- T5 (S2) — Fase B: Surface, liberación de recursos, cambios de canal; sin duplicados ni fugas. Route: inline + CI. **HECHO** (`c672ffe`; doble liberar instrumentado en `97b6290`; CI pendiente).
- T6 (S3) — Fase C: matriz MPEG-2 → MPEG-4 Part 2 → H.263 → H.264 (regresión) → HEVC (evaluación). Route: inline + CI + física. **CI en curso** (`97b6290`); HEVC = evaluación documentada; física pendiente (S9).
- T7 (S4) — Fase D: diagnóstico ampliado (init ms, error de decodificación). Route: inline. **HECHO** (`6328933`; runtime-only, Room v4 intacta).
- T8 (S5) — Fase E: medición CPU/memoria/temperatura/init/frames perdidos/estabilidad; sin bloqueo del hilo principal. Route: inline. **PARCIAL**: init ms y frames medibles en EstadoSesion/diagnóstico; CPU/temperatura requieren tooling externo no disponible — documentado en el informe (T10).
- T9 (S6) — Fase F: CI con NDK, pruebas, licencias, APK debug. Route: CI. **EN CURSO**: paso FFmpeg + instrumentadas en CI; `THIRD_PARTY_NOTICES.md` y guard GPL/nonfree listos (`4149dcd`); CI pendiente.
- T10 (S8/S9) — ADR actualizado + informe con limitaciones y protocolo de validación física. Route: inline. **PARCIAL**: ADR-0008 y docs/compatibilidad-video.md actualizados (`4149dcd`); informe final tras CI verde.

## Log

- L1 (2026-11-16): Solicitud original del usuario, verbatim:

"CoperNicoTV — E7-T12: Decodificación de vídeo por software (ruta LGPL)
Decisión: E7-T11 Fase 1 está cerrada y validada físicamente. Evidencia: CI E7-T11: 5/5. Caracol 2704: H.264 High, hardware Exynos, imagen y audio correctos. Telecaribe 2719: MPEG-2, audio correcto, vídeo no soportado por el dispositivo. Diagnóstico: 'MIME_NO_SOPORTADO'. Inventario, política de selección y persistencia Room v4 implementados. Avanzar a la implementación de decodificación de vídeo por software.
Objetivo principal: Incorporar un decoder de vídeo software real, compatible con LGPL, integrado con Media3 1.5.0, que permita reproducir formatos que el hardware del dispositivo no soporte. El primer objetivo funcional es MPEG-2 Video.
[Fase A] Investigación técnica obligatoria: Revisar ADR-0008 y la documentación existente. Evaluar las alternativas LGPL documentadas y determinar: Compatibilidad con Media3 1.5.0. Disponibilidad de renderer de vídeo Android. Compatibilidad con Android API 26+. ABI ARM64 y ARM32. Dependencias nativas. Licencias de bibliotecas y componentes. Tamaño final del APK. Consumo de CPU y memoria. Mantenimiento y actualizaciones. Viabilidad de CI con NDK. Capacidad de renderizar directamente sobre Surface. No seleccionar una alternativa basándose únicamente en que incluya FFmpeg. Debe existir una ruta técnicamente demostrable para entregar frames al Surface de Media3. Presentar la decisión técnica y sus evidencias antes de introducir dependencias.
[Fase B] Integración: Implementar el renderer externo utilizando las interfaces existentes de E7-T11. Requisitos: 1. Mantener Media3 1.5.0. 2. Priorizar decodificación hardware. 3. Utilizar software solamente cuando el hardware no soporte el formato o perfil. 4. Mantener el comportamiento actual de H.264. 5. Integrar el renderer con el selector de decodificadores. 6. Conservar audio, sincronización A/V, subtítulos y controles de reproducción. 7. Gestionar correctamente Surface, liberación de recursos y cambios de canal. 8. Evitar decodificadores duplicados y fugas de memoria. 9. No simular soporte ni declarar reproducción exitosa antes del primer frame real.
[Fase C] Matriz inicial: Implementar y validar en este orden: 1. MPEG-2 Video. 2. MPEG-4 Part 2. 3. H.263. 4. H.264 como regresión. 5. HEVC como evaluación posterior. VP8, VP9, AV1 y VC-1 quedan sujetos a la compatibilidad real de la alternativa seleccionada. No prometer soporte sin implementación y pruebas.
[Fase D] Diagnóstico: Ampliar los diagnósticos existentes para distinguir: Hardware seleccionado. Software seleccionado. Motivo de fallback. Decoder inicializado. Primer frame recibido. Error de inicialización. Error de decodificación. Tiempo hasta primer frame. Consumo y estabilidad cuando sea medible. Mantener la distinción entre disponibilidad, inicialización y reproducción real.
[Fase E] Calidad y rendimiento: Medir: CPU. Memoria. Temperatura del dispositivo cuando la plataforma lo permita. Tiempo de inicialización. Tiempo hasta primer frame. Frames perdidos. Estabilidad de reproducción prolongada. El decoder software no debe bloquear el hilo principal.
[Fase F] CI y pruebas: Incorporar NDK únicamente si la alternativa seleccionada lo requiere. Configurar CI reproducible para las ABI necesarias. Añadir: Pruebas unitarias. Pruebas de integración del renderer. Pruebas instrumentadas. Pruebas de selección hardware/software. Pruebas de liberación de Surface y recursos. Regresión H.264. Validación de licencias y dependencias. Publicar APK debug.
Restricciones obligatorias: No incorporar dependencias GPL. No incorporar el AAR GPL-3 de Jellyfin. No cambiar la licencia del proyecto. No degradar el camino hardware existente. No modificar T3/T4 sin evidencia de necesidad. No romper Room v4. No eliminar diagnósticos existentes. No realizar merge. No ejecutar operaciones destructivas de Git. Conservar la rama y los cambios actuales. No introducir dependencias sin justificar su licencia y mantenimiento.
Entrega: 1. ADR de decisión tecnológica actualizada. 2. Implementación real del renderer software. 3. MPEG-2 funcional como primer objetivo. 4. Integración con E7-T11. 5. Diagnósticos ampliados. 6. Pruebas y regresiones. 7. CI completo. 8. APK debug. 9. Informe con limitaciones conocidas y protocolo de validación física.
Trabaja por entregas verificables. No cierres la tarea declarando soporte MPEG-2 hasta demostrar reproducción de imagen real en un dispositivo Android. La prioridad es avanzar hacia un reproductor IPTV completo, estable y compatible, sin convertir un canal problemático en un bloqueo del desarrollo."

- L2 (2026-11-16, T1): Fase A ejecutada. Evidencia técnica verificada sobre los AAR reales (no documentación):
  1. `media3-exoplayer-1.5.0.aar` (classes.jar, cache Gradle): `androidx.media3.exoplayer.video.DecoderVideoRenderer` es **pública y abstracta**, extiende `BaseRenderer`, y espera un `androidx.media3.decoder.Decoder<DecoderInputBuffer, ? extends VideoDecoderOutputBuffer, ? extends DecoderException>` (verificado con `javap`). `VideoDecoderOutputBufferRenderer` (interfaz pública, método `setOutputBuffer`) existe en el mismo paquete. Punto de render a Surface: GL interno del renderer o la interfaz de output-buffer.
  2. `media3-decoder-1.5.0.aar`: `Decoder` (interfaz genérica pública: getName/setOutputStartTimeUs/dequeueInputBuffer/queueInputBuffer/dequeueOutputBuffer/flush/release), `DecoderInputBuffer`, `VideoDecoderOutputBuffer` (pública, con `initForYuvFrame(w, h, strides..., colorspace)`, `yuvPlanes`, `yuvStrides`, `colorspace`, constantes COLORSPACE_BT601/BT709/BT2020), `SimpleDecoder`, `DecoderException`. **Todo API pública Apache-2.0.**
  3. La interfaz `VideoDecoder` de ExoPlayer 2.x NO existe en Media3 1.5.0 → el patrón Jellyfin (que la necesita) no es el patrón de Media3 1.5.0. Implementación propia: decoder que implementa directamente `androidx.media3.decoder.Decoder`. Sin código GPL de Jellyfin, sin duplicación.
  4. AAR Jellyfin (`/tmp/opencode/jfaar/unpacked`): solo referencia arquitectónica GPL-3 (jni/ 4 ABIs; `ExperimentalFfmpegVideoRenderer` extendía DecoderVideoRenderer) — NO copiable.
  5. Termux: ffmpeg/cmake/ninja locales sin NDK (esperado); NDK disponible en GitHub Actions ubuntu-latest; x86_64 obligatorio para ejercitar el decoder nativo en las instrumentadas del emulador, además de arm64-v8a y armeabi-v7a.
  6. Licencias: libavcodec con decoders `mpeg2video`, `mpeg4`, `h263` (y `h264` solo si se necesitara; no se necesita: H.264 va por hardware) compila sin `--enable-gpl` ni `--enable-nonfree` → binario LGPL-2.1+; se enlaza como librería compartida separada (`libavcodec.so` etc.) con scripts de build publicados en el repo (cumple LGPL). Código Java/Kotlin propio: Apache-2.0 (licencia del proyecto, sin cambios).
  7. Decisión documentada en ADR-0008 ruta C como principal; ruta D (libmpeg2 AOSP Apache-2.0, solo MPEG-2) como fallback mínimo.
- L3 (2026-11-16): AUTORIZACIÓN RUTA C del usuario (verbatim): FFmpeg LGPL subconjunto, JNI propio, renderer propio sobre API pública Media3 1.5.0, media3-decoder:1.5.0, ABIs arm64/arm32/x86_64, MPEG-2/MPEG-4 Part 2/H.263 iniciales, hardware primero. Ejecución continua sin parar entre fases; commits de trabajo en la rama actual; límites: sin GPL/nonfree, sin merge, sin git destructivo, sin cambiar licencia, sin declarar soporte no verificado. MPEG-2 solo se declara soportado con: init real, JNI packets, frames recibidos, YUV correcto, imagen real en Surface, audio sincronizado, sin bloqueo del hilo principal, liberación correcta, reproducción estable. Control de licencias obligatorio antes de distribuir APK (verificar config LGPL efectiva, sin GPL/nonfree, avisos y materiales LGPL, scripts documentados).
- L4 (2026-11-17): Fases C y F avanzadas. Fixtures generados con FFmpeg de Termux (mpeg2.ts 262 KB mpeg2video+mp2; mpeg4.mp4 79 KB mpeg4+aac; h263.3gp 78 KB h263+aac; H.264 de regresión reutiliza f19_pistas.mp4). `ReproductorDecoderSoftwareTest` (4 tests end-to-end) y veredicto MPEG-2 con externo en `InventarioDecodificadoresTest` (commit `97b6290`). Regresión H.264: en emulador x86_64 el MediaCodec puede ser software (c2.*), así que la aserción correcta es «nunca ffmpeg:*», no «HARDWARE».
- L5 (2026-11-17): Diagnóstico CI: el configure de FFmpeg fallaba en el runner porque el script pasaba `--sysroot=<ndk>/bin/sysroot` (inexistente; el sysroot real está fuera de `bin/`). Corregido + guard temprano + diagnóstico de `ffbuild/config.log` (commit `4149dcd`). Licencias: `THIRD_PARTY_NOTICES.md` creado, ADR-0008 actualizado a fase 2 ejecutada, docs/compatibilidad-video.md con estado software y validación física pendiente.
