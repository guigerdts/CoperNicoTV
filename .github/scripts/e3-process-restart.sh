#!/usr/bin/env bash
set -euo pipefail

# E3-T8: verifica persistencia tras terminar y relanzar el proceso real.
#
# No se puede hacer `am force-stop` dentro de la instrumentación porque el
# runner de Android se ejecuta en el proceso de la app: el `force-stop` mataría
# al propio test. Por eso la evidencia se divide en dos invocaciones separadas.
#
# No se usa `connectedDebugAndroidTest` para las fases: AGP desinstala el APK
# bajo prueba al terminar cada invocación, lo que borraría `filesDir` y Room.
# En su lugar se instalan los APK una sola vez y se lanzan las dos fases con
# `am instrument`, de modo que entre fases solo cambie el proceso.
#
# La fase 2 comprueba, con `Process.getStartElapsedRealtime()`, que corre en un
# proceso distinto al de la fase 1. No se usa `ActivityScenario.recreate()`.

ADB="${ADB:-adb}"
APP_ID="com.copernicotv.app"
TEST_ID="com.copernicotv.app.test"
RUNNER="com.copernicotv.app.HiltTestRunner"
CLASE_FASE1="com.copernicotv.app.E3T8ProcesoFase1Test"
CLASE_FASE2="com.copernicotv.app.E3T8ProcesoFase2Test"
APK_APP="app/build/outputs/apk/debug/app-debug.apk"
APK_TEST="app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
REPORTES="app/build/reports/androidTests"

mkdir -p "$REPORTES/e3-fase1" "$REPORTES/e3-fase2"

if [[ ! -f "$APK_APP" ]]; then
    APK_APP=$(find app/build/outputs/apk -name '*-debug.apk' -not -path '*androidTest*' -print -quit)
fi
if [[ ! -f "$APK_TEST" ]]; then
    APK_TEST=$(find app/build/outputs/apk -name '*androidTest*.apk' -print -quit)
fi
if [[ ! -f "$APK_APP" ]]; then
    echo "ERROR: no se encontró el APK de la app" >&2
    exit 1
fi
if [[ ! -f "$APK_TEST" ]]; then
    echo "ERROR: no se encontró el APK de pruebas" >&2
    exit 1
fi

# Instalación única: ambas fases comparten el mismo paquete y sus datos.
"$ADB" install -r -t "$APK_APP"
"$ADB" install -r -t "$APK_TEST"

ejecutar_fase() {
    local clase="$1"
    local destino="$2"
    local salida

    set +e
    salida=$("$ADB" shell am instrument -w \
        -e class "$clase" \
        -e clearPackageData false \
        "$TEST_ID/$RUNNER")
    local codigo=$?
    set -e

    printf '%s\n' "$salida" | tee "$destino"

    if (( codigo != 0 )); then
        echo "ERROR: am instrument terminó con código $codigo para $clase" >&2
        return 1
    fi
    if printf '%s\n' "$salida" | grep -q -E 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed'; then
        echo "ERROR: la instrumentación de $clase falló" >&2
        return 1
    fi
    if ! printf '%s\n' "$salida" | grep -q -E '^OK \(1 test\)'; then
        echo "ERROR: la instrumentación de $clase no reportó OK (1 test)" >&2
        return 1
    fi
}

ejecutar_fase "$CLASE_FASE1" "$REPORTES/e3-fase1/instrumentation.txt"

"$ADB" shell am force-stop "$APP_ID"
if "$ADB" shell pidof "$APP_ID" >/dev/null 2>&1; then
    echo "ERROR: el proceso de $APP_ID sigue activo después de am force-stop" >&2
    exit 1
fi

ejecutar_fase "$CLASE_FASE2" "$REPORTES/e3-fase2/instrumentation.txt"
