#!/bin/sh
# Medición P1 de Android de DC-10 (E2-T5 y E3-T1).
#
# La acción del emulador ejecuta cada línea del input `script` en un `sh -c`
# separado y se detiene en la primera que falla. Por eso este archivo concentra
# la secuencia en un solo proceso: fija el entorno de referencia, ejecuta la
# medición, recupera la evidencia que UTP copia al host antes de desinstalar y
# propaga el código real.
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
    -Pandroid.testInstrumentationRunnerArguments.class=com.copernicotv.app.p1.MedicionP1AndroidTest,com.copernicotv.app.p1.MedicionP1BusquedaAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.p1.commit="${GITHUB_SHA:-desconocido}"
codigo=$?

mkdir -p app/build/p1-android
salida_adicional=app/build/outputs/connected_android_test_additional_output
if [ -d "$salida_adicional" ]; then
    find "$salida_adicional" -type f -exec cp {} app/build/p1-android/ \;
fi
for archivo in medicion.json busqueda-medicion.json; do
    if [ ! -f "app/build/p1-android/$archivo" ]; then
        echo "No se encontro $archivo en la salida adicional del test" >&2
        if [ "$codigo" -eq 0 ]; then
            codigo=1
        fi
    fi
done

exit "$codigo"
