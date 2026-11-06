#!/usr/bin/env bash
set -euo pipefail

# E3-T8, S11d y S45a/S45b: verifican persistencia tras terminar y relanzar el
# proceso real.
#
# No se puede hacer `am force-stop` dentro de la instrumentación porque el
# runner de Android se ejecuta en el proceso de la app: el `force-stop` mataría
# al propio test. Por eso la evidencia se divide en dos invocaciones separadas
# por cada escenario.
#
# No se usa `connectedDebugAndroidTest` para las fases: AGP desinstala el APK
# bajo prueba al terminar cada invocación, lo que borraría `filesDir` y Room.
# En su lugar se instalan los APK una sola vez y se lanzan las fases con
# `am instrument`, de modo que entre fases solo cambie el proceso.
#
# Cada fase 2 comprueba, con `Process.getStartElapsedRealtime()`, que corre en
# un proceso distinto al de su fase 1. No se usa `ActivityScenario.recreate()`.

ADB="${ADB:-adb}"
APP_ID="com.copernicotv.app"
TEST_ID="com.copernicotv.app.test"
RUNNER="com.copernicotv.app.HiltTestRunner"
CLASE_E3T8_FASE1="com.copernicotv.app.E3T8ProcesoFase1Test"
CLASE_E3T8_FASE2="com.copernicotv.app.E3T8ProcesoFase2Test"
CLASE_S11D_FASE1="com.copernicotv.app.S11dProcesoFase1Test"
CLASE_S11D_FASE2="com.copernicotv.app.S11dProcesoFase2Test"
CLASE_S45_FASE1="com.copernicotv.app.S45ProcesoFase1Test"
CLASE_S45_FASE2="com.copernicotv.app.S45ProcesoFase2Test"
CLASE_E7T5_FASE1="com.copernicotv.app.E7T5ProcesoFase1Test"
CLASE_E7T5_FASE2="com.copernicotv.app.E7T5ProcesoFase2Test"
APK_APP="app/build/outputs/apk/debug/app-debug.apk"
APK_TEST="app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
REPORTES="app/build/reports/androidTests"

mkdir -p "$REPORTES/e3-fase1" "$REPORTES/e3-fase2" "$REPORTES/s11d-fase1" "$REPORTES/s11d-fase2" \
    "$REPORTES/s45-fase1" "$REPORTES/s45-fase2" \
    "$REPORTES/e7t5-fase1" "$REPORTES/e7t5-fase2"

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

detener_proceso() {
    "$ADB" shell am force-stop "$APP_ID"
    if "$ADB" shell pidof "$APP_ID" >/dev/null 2>&1; then
        echo "ERROR: el proceso de $APP_ID sigue activo después de am force-stop" >&2
        return 1
    fi
}

capturar_logcat_s11d() {
    local destino="$1"
    "$ADB" logcat -d -s S11dProceso:I > "$destino"
}

# E7-T5: bitácora de las dos fases del proceso (restauración de preferencias).
capturar_logcat_e7t5() {
    local destino="$1"
    "$ADB" logcat -d -s E7T5Proceso:I > "$destino"
}

# E3-T8: reordenamiento persistido tras un proceso nuevo.
ejecutar_fase "$CLASE_E3T8_FASE1" "$REPORTES/e3-fase1/instrumentation.txt"
detener_proceso
ejecutar_fase "$CLASE_E3T8_FASE2" "$REPORTES/e3-fase2/instrumentation.txt"
detener_proceso

# S11d: Historial persistido tras un proceso nuevo.
"$ADB" logcat -c
ejecutar_fase "$CLASE_S11D_FASE1" "$REPORTES/s11d-fase1/instrumentation.txt"
capturar_logcat_s11d "$REPORTES/s11d-fase1/logcat.txt"
detener_proceso
"$ADB" logcat -c
ejecutar_fase "$CLASE_S11D_FASE2" "$REPORTES/s11d-fase2/instrumentation.txt"
capturar_logcat_s11d "$REPORTES/s11d-fase2/logcat.txt"

# S45a/S45b: la Colección reordenada desde la UI conserva su orden tras un
# proceso nuevo y la secuencia visible coincide con ColeccionCanalDao.
ejecutar_fase "$CLASE_S45_FASE1" "$REPORTES/s45-fase1/instrumentation.txt"
detener_proceso
ejecutar_fase "$CLASE_S45_FASE2" "$REPORTES/s45-fase2/instrumentation.txt"

# E7-T5 (S44b, S17b): el modo de aspecto y la visibilidad de los controles
# secundarios cambiados desde la UI del reproductor sobreviven a un proceso
# nuevo. La siembra de la fase 2 re-importa el mismo tvg-id y la
# reconciliación de ADR-0004 apunta el Canal al puerto efímero de esa fase.
ejecutar_fase "$CLASE_E7T5_FASE1" "$REPORTES/e7t5-fase1/instrumentation.txt"
capturar_logcat_e7t5 "$REPORTES/e7t5-fase1/logcat.txt"
detener_proceso
ejecutar_fase "$CLASE_E7T5_FASE2" "$REPORTES/e7t5-fase2/instrumentation.txt"
capturar_logcat_e7t5 "$REPORTES/e7t5-fase2/logcat.txt"
