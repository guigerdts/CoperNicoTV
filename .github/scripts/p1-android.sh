#!/bin/sh
# Medición P1 de Android de DC-10 (E2-T5).
#
# La acción del emulador ejecuta cada línea del input `script` en un `sh -c`
# separado y se detiene en la primera que falla. Por eso este archivo concentra
# la secuencia en un solo proceso: fija el entorno de referencia, ejecuta la
# medición, extrae la evidencia aunque el test falle y propaga el código real.
set +e

adb shell wm size 1080x1920
adb shell wm density 320

if ! adb shell wm size | grep -q "1080x1920"; then
    echo "No se pudo fijar la resolucion 1080x1920" >&2
    exit 1
fi
if ! adb shell wm density | grep -q "320"; then
    echo "No se pudo fijar la densidad 320 dpi" >&2
    exit 1
fi

./gradlew :app:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=com.copernicotv.app.p1.MedicionP1AndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.p1.commit="${GITHUB_SHA:-desconocido}"
codigo=$?

mkdir -p app/build/p1-android
if ! adb pull /sdcard/Android/data/com.copernicotv.app/files/p1/. app/build/p1-android/; then
    echo "No se pudo extraer la evidencia P1 de Android" >&2
    if [ "$codigo" -eq 0 ]; then
        codigo=1
    fi
fi

exit "$codigo"
