# Matriz de compatibilidad de decodificación de video

Fuente de verdad: el **inventario del dispositivo** (`InventarioDecodificadores`, E7-T11). Esta
tabla documenta el panorama típico de Android para interpretar el diagnóstico; nunca declara
soporte por sí misma. El veredicto de una sesión (`MotivoSoporteVideo`) sale de la política de
`:core` consultada sobre `MediaCodecList` en el momento de la reproducción.

| Codec | MIME | Codec strings típicos | Disponibilidad nativa típica | Notas |
| --- | --- | --- | --- | --- |
| H.264/AVC | `video/avc` | `avc1.PPCCLL`, `avc3.PPCCLL` | Universal: hardware en casi todo el parque; software `c2.android.avc.decoder` siempre presente | Caso Caracol 2704 (`avc1.640028`). Perfil/nivel se parsean del string (RFC 6381: perfil, flags, nivel). |
| H.265/HEVC | `video/hevc` | `hev1.*`, `hvc1.*` | Hardware común en dispositivos modernos; el perfil (Main/Main10) y el nivel importan | Sin parseo de perfil en fase 1: el string HEVC no se traduce honestamente a `MediaCodecInfo.CodecProfileLevel` sin tabla de constantes; queda desconocido. |
| MPEG-2 | `video/mpeg2` | `mpg1`, `mpg2` (frecuentemente ausente en TS) | Raro: hardware en algunos SoC (OMX/qti); algunos equipos traen `c2.android.mpeg2.decoder`; muchos dispositivos no lo declaran | Caso Telecaribe 2719: decoder inexistente en el dispositivo de referencia; reproducción real requiere decoder externo (ADR-0008, fase 2). |
| MPEG-4 Part 2 | `video/mp4v-es` | `mp4v.*` | Ocasional: hardware en SoC antiguos; software no estándar | Perfil ASP no parseado en fase 1. |
| H.263 | `video/3gpp` | `h263.*` | Legado: hardware en dispositivos antiguos | Casi extinto en TV en vivo actual. |
| VP8 | `video/x-vnd.on2.vp8` | `vp8` | Software `c2.android.vp8.decoder` siempre; hardware raro | Exótico en IPTV; disponible por software. |
| VP9 | `video/x-vnd.on2.vp9` | `vp9`, `vp09.*` | Software `c2.android.vp9.decoder` (API ≥ 24); hardware en gama alta | Similar a VP8. |
| AV1 | `video/av01` | `av01.*` | Android 10+: software `c2.android.av1.decoder` (gmedia); hardware muy reciente | Coste de CPU alto en resoluciones grandes. |
| VC-1 | `video/x-ms-wmv` (variantes) | `WVC1`, `wmv3` | Muy raro: algunos SoC con hardware dedicado; sin decoder de AOSP | Usado por alguna IPTV heredada; normalmente `MIME_NO_SOPORTADO`. |

## Cómo leer el veredicto del diagnóstico

- `SOPORTADO`: la política encontró decoder (se nombra) compatible con MIME, perfil conocido y
  límites de resolución declarados.
- `MIME_NO_SOPORTADO`: ningún decoder (nativo ni externo) declara el MIME — p. ej. MPEG-2 en el
  dispositivo de referencia. La imagen no puede renderizarse en este dispositivo.
- `PERFIL_NO_SOPORTADO`: hay decoder para el MIME pero el perfil parseado del codec string no está
  en los `CodecProfileLevel` declarados.
- `RESOLUCION_NO_SOPORTADA`: hay decoder para el MIME pero la resolución excede los límites
  declarados (`VideoCapabilities`).
- Perfil/nivel «no disponible»: el formato no declara codec string parseable (p. ej. MPEG-2 en
  TS); la política no finge rechazo de perfil con datos inexistentes.

## Estado de decodificación software (E7-T12)

Con el decoder externo FFmpeg (LGPL, ADR-0008 fase 2) registrado en la sesión:

- **MPEG-2** (`video/mpeg2`): soportado por software (`ffmpeg:mpeg2video`) en dispositivos sin
  decoder nativo. Validado en el emulador de CI (primer frame real en Surface, audio, liberación
  estable) con `ReproductorDecoderSoftwareTest`; la validación en dispositivo físico queda
  **pendiente** (protocolo: reproducir un canal MPEG-2 real, p. ej. Telecaribe 2719, y verificar
  imagen, audio y diagnóstico en el panel).
- **MPEG-4 Part 2** y **H.263**: soportados por software cuando el dispositivo no declara decoder;
  en el emulador de CI MediaCodec software nativo suele cubrirlos y la política lo prefiere.
- **H.264**: nunca sale por el decoder software; sigue en MediaCodec del dispositivo (regresión
  instrumentada). El caso Caracol 2704 (`avc1.640028`) permanece como validación física pendiente.

Licencias y avisos: ver `THIRD_PARTY_NOTICES.md` y `scripts/ffmpeg/build-ffmpeg.sh`.

Limitaciones declaradas de la fase 1: la evaluación es de disponibilidad (datos de
`MediaCodecList`); un `SOPORTADO` no garantiza decodificación ante streams corruptos o perfiles no
declarados, y el «fallo de inicialización» real queda para la fase 2 (ADR-0008).
