# JNI wrapper del decoder de video por software (E7-T12).
#
# Enlaza estáticamente el subconjunto LGPL de FFmpeg compilado por
# scripts/ffmpeg/build-ffmpeg.sh (libavcodec + libavutil) dentro de una sola
# librería compartida: evita empaquetar librerías con SONAME versionado
# (libavcodec.so.60) que AGP no incluye en el APK. El cumplimiento LGPL de la
# redistribución se documenta en THIRD_PARTY_NOTICES.md: el script de build
# fija la versión, el SHA-256 y no activa GPL ni nonfree.
#
# Requiere que el paso de CI `scripts/ffmpeg/build-ffmpeg.sh` haya corrido
# antes de externalNativeBuild (los artefactos viven en native/ffmpeg/, que
# no se versiona).

cmake_minimum_required(VERSION 3.22.1)
project(copernicotv_decoder_software LANGUAGES C)

set(FFMPEG_INSTALL "${CMAKE_CURRENT_SOURCE_DIR}/../../../../native/ffmpeg/install/${ANDROID_ABI}")

add_library(decoder_software SHARED decoder_ffmpeg_jni.c)

add_library(ffmpeg_avcodec STATIC IMPORTED)
set_target_properties(ffmpeg_avcodec PROPERTIES IMPORTED_LOCATION "${FFMPEG_INSTALL}/lib/libavcodec.a")

add_library(ffmpeg_avutil STATIC IMPORTED)
set_target_properties(ffmpeg_avutil PROPERTIES IMPORTED_LOCATION "${FFMPEG_INSTALL}/lib/libavutil.a")

target_include_directories(decoder_software PRIVATE "${FFMPEG_INSTALL}/include")

target_link_libraries(decoder_software PRIVATE ffmpeg_avcodec ffmpeg_avutil log android)
