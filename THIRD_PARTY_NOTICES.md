# Avisos de terceros — CoperNicoTV

Este documento registra los componentes de terceros distribuidos dentro del
APK y su base legal de redistribución. El código propio del proyecto se rige
por la licencia del repositorio; los binarios compilados desde fuentes de
terceros requieren los avisos que siguen.

## FFmpeg (libavcodec + libavutil) — subconjunto LGPL

- **Componente:** FFmpeg 7.1.1, concretamente las bibliotecas estáticas
  `libavcodec.a` y `libavutil.a`, enlazadas dentro de `libdecoder_software.so`
  en el APK de depuración y de release.
- **Subconjunto funcional:** únicamente los decoders de vídeo
  `mpeg2video`, `mpeg4` y `h263`. Sin demuxers, sin protocolos de red, sin
  filtros, sin codificadores, sin `avformat`/`avfilter`/`swscale`.
- **Fuentes exactas:** tarball oficial
  `https://ffmpeg.org/releases/ffmpeg-7.1.1.tar.xz`,
  SHA-256
  `733984395e0dbbe5c046abda2dc49a5544e7e0e1e2366bba849222ae9e3a03b1`
  (verificado por `scripts/ffmpeg/build-ffmpeg.sh` antes de compilar).
- **Licencia del binario resultante:** LGPL-2.1-or-later. El script de build
  configura sin `--enable-gpl` ni `--enable-nonfree` y **falla el build** si la
  `config.h` generada no declara `CONFIG_GPL 0` y `CONFIG_NONFREE 0`
  (control automático en CI y en builds locales).
- **Modificaciones al código de FFmpeg:** ninguna. Se compila el tarball
  oficial sin parches; toda la adaptación vive en el script de build, en el
  JNI propio (`app/src/main/cpp/`) y en el renderer Kotlin de `:app`.
- **Script de build reproducible:** `scripts/ffmpeg/build-ffmpeg.sh`
  (versión fijada, SHA-256 fijado, flags documentados). El script y su
  configuración de flags son suficientes para relinkar el binario desde las
  fuentes LGPL originales sin acceso a este repositorio.
- **Aviso legal exigido por la LGPL-2.1:** FFmpeg es copyright de los
  autores de FFmpeg (lista completa en el archivo `CREDITS` y en el sitio
  https://ffmpeg.org/legal.html). Esta biblioteca es software libre; puede
  redistribuirla y/o modificarla bajo los términos de la GNU Lesser General
  Public License publicada por la Free Software Foundation, versión 2.1 o
  posterior. Se distribuye SIN GARANTÍA alguna. Una copia del texto de la
  LGPL-2.1 está disponible en https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html
  y la fuente original del componente en la URL pinned de arriba.
- **Cumplimiento de reversibilidad (§6 de la LGPL):** el enlazado es estático
  dentro de `libdecoder_software.so`; quien lo requiera puede producir un
  binario equivalente relinkeando las fuentes LGPL con el script publicado,
  sin dependencia de código del proyecto.

## Dependencias Media3 (Apache-2.0)

- `androidx.media3:media3-exoplayer`, `media3-ui`, `media3-session`,
  `media3-decoder` y afines en la versión 1.5.0 — Apache License 2.0,
  con avisos estándar de AndroidX. Sin modificaciones.

## Runtime de la app (Apache-2.0 / BSD)

- Kotlin stdlib y corrutinas (Apache-2.0), Jetpack Compose / Material 3
  (Apache-2.0), Room (Apache-2.0), Hilt/Dagger (Apache-2.0), DataStore
  (Apache-2.0), Media3 (Apache-2.0). Sin modificaciones relevantes.

## No incluido

- No se incluye el AAR `org.jellyfin.media3:media3-ffmpeg-decoder`
  (GPL-3.0): prohibido por la decisión de ADR-0008.
- No se compila FFmpeg con `--enable-gpl` ni `--enable-nonfree`; los
  decoders/parsers GPL y componentes nonfree quedan fuera del binario.
