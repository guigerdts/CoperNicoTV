# E7-T11 Fase 1 — Inventario y selección inteligente de decodificadores

Objetivo: inventario dinámico de capacidades audiovisuales del dispositivo y política de selección de decodificadores, integrados con el diagnóstico existente (E7-T10) y persistidos en Room v4. Preparar la interfaz para decoders externos (fase 2) sin implementar decodificación software simulada. Caso que motiva: Telecaribe 2719 (video/mpeg2 detectado, pista no soportada, renderer inactivo, audio funcional); referencia sana Caracol 2704 (H.264 avc1.640028 1280x720, c2.exynos.h264.decoder).
Branch: cascada sobre feat/e7-t9-compatibilidad (ec74154) · Delivery: ask-on-risk · Runner: ./gradlew :core:test local; :app tests/instrumentados/apk en CI (ADR-0005)

## Specs

- S1 (alcance autorizado, verbatim del usuario):
  1. «Crear un servicio de inventario audiovisual basado en MediaCodecList y MediaCodecInfo.»
  2. «Detectar decodificadores hardware y software.»
  3. «Evaluar MIME, codec string, perfil, nivel y resolución.»
  4. «Implementar una política de selección que priorice hardware compatible y después software disponible.»
  5. «Distinguir decoder inexistente, formato no soportado, perfil incompatible, resolución fuera de límites y fallo de inicialización.»
  6. «Integrar la información con ObservadorAudiovisual y los diagnósticos existentes.»
  7. «Implementar migración Room v3→v4 y sus pruebas.»
  8. «Preparar una interfaz de integración para futuros decodificadores externos.»
  9. «Añadir pruebas unitarias e instrumentadas.»
  10. «Mantener compatibilidad con Media3 1.5.0.»
- S2 (matriz inicial, verbatim): «Evaluar: MPEG-2, MPEG-4 Part 2, H.263, H.264/AVC, H.265/HEVC, VP8, VP9, AV1, VC-1.» «No declarar soporte por MIME solamente. Consultar las capacidades reales del dispositivo.»
- S3 (restricciones, verbatim): «No incorporar org.jellyfin.media3:media3-ffmpeg-decoder. No introducir dependencias GPL. No incorporar NDK todavía. No modificar la licencia del proyecto. No simular decodificación software. No alterar T3/T4 ni la clasificación VIDEO_NO_ELEGIBLE. No perjudicar la reproducción H.264 actual. No modificar innecesariamente la arquitectura Media3. No hacer merge.»
- S4 (ADR, verbatim): «Documentar una ADR que compare las alternativas de decoder MPEG-2 bajo LGPL, incluyendo viabilidad técnica, licencia, mantenimiento, ABI, tamaño, rendimiento y coste de CI. Dejar preparada una propuesta de segunda fase para incorporar decodificación software real, sin implementarla todavía.»
- S5 (entrega, verbatim): «Implementación completa de esta fase. Migración Room v4. Pruebas unitarias e instrumentadas. ADR de decodificación. Documentación de la matriz de compatibilidad. CI completo. APK debug.»
- S6 (diagnóstico, de E7-T11 prompt): diferenciar claramente «Pista inexistente. Pista deshabilitada. Códec no soportado. Decoder disponible pero fallido. Error de inicialización. Error de renderizado. Fallo de Surface.» — en esta fase se instrumenta la clasificación de disponibilidad/selección (fallo de inicialización real queda para la fase con decoder externo; se declara honestamente).
- S7 (criterios de aceptación heredados): Caracol 2704 conserva H.264; hardware preferido; diagnóstico explica exactamente por qué si no es viable; sin bucles de recuperación; sin audio duplicado; CI 5/5; limitaciones de validación física documentadas.

## Tasks

- T1 (S2/S3/S5) — Política de selección y modelo en `:core` (puro, JVM-testeable): parseo de codec string a perfil/nivel, `DecoderVideo` (datos), política hardware→software→externo→incompatible con motivos. **Hecho** (12 pruebas en `DecodersVideoTest`, RED→GREEN, commit `6538b1a`).
- T2 (S1/S2/S6) — Servicio Android `InventarioDecodificadores` (MediaCodecList/REGULAR_CODECS → `DecoderVideo`), detección hardware/software con guard API 29, consulta por formato con perfil/nivel/resolución vía CodecCapabilities/VideoCapabilities; interfaz de registro para decoders externos (`RegistroDecodersVideo`, vacío en fase 1). **Pendiente**.
- T3 (S1/S6) — Integración: `SesionMedia3.publicarEstado` consulta la selección para el formato de video detectado (T7) y publica `decoderVideoSeleccionado`/`tipoDecoderVideo`/`motivoSoporteVideo`/`perfilNivelVideo`; panel de diagnóstico +4 líneas; memoización por formato. **Pendiente**.
- T4 (S5) — Persistencia v4: +4 columnas TEXT en `SesionDiagnosticoEntity`, `MIGRACION_3_4` (ALTER TABLE ADD COLUMN ×4), AppDatabase v4, contratos JVM v4, test de migración 3→4 con fila v3 sobreviviente. **Pendiente**.
- T5 (S9) — Pruebas instrumentadas: `InventarioDecodificadoresTest` (inventario no vacío, h264 con decoder hardware, veredicto MPEG-2 consistente con el dispositivo, end-to-end de diagnóstico sobre fixture H.264 con motivo soportado); registro en ci.yml (móvil). **Pendiente**.
- T6 (S4/S5) — ADR-0008 (comparación LGPL de alternativas MPEG-2) + matriz de compatibilidad documentada + propuesta de fase 2 sin implementarla. **Pendiente**.
- T7 (S5) — CI completo + APK debug + cierre. **Pendiente**.

## Log

- L1 (2026-11-16): solicitud Fase 1 del usuario, verbatim en S1–S5. Decisión previa de sesión: la ruta GPL-3 (AAR Jellyfin con FfmpegVideoRenderer real, MPEG-2/MPEG-4/H.263/H.264/HEVC compilados en nativo, 2.9 MB, 4 ABIs, compatible con media3 1.5.0) fue presentada y el usuario eligió la opción 3 (sin GPL, sin NDK). La investigación quedó documentada y alimenta la ADR.
