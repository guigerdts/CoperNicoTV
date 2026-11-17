# Campaña física consolidada del MVP — Runbook operativo

**Qué es:** procedimiento paso a paso para ejecutar la campaña de validación física que separa al MVP declarable. **Estado de partida:** todo lo implementable y CI-validable ya está hecho e integrado (`main @ c63d241`, CI 5/5); esta campaña cierra F-09..F-12 y las verificaciones con fuente real. **Documento de resultados:** `odd/tasks/campana-fisica-mvp-informe.md`. Registro central de evidencias: `docs/evidence/validaciones-fisicas.md`.

## Quick path (orden del operador)

1. Conectar el dispositivo y ejecutar la hoja ADB del §4 (verificar modelo/Android/ABI/espacio).
2. Instalar el APK verificado del §3 (`app-debug.apk`, SHA-256 del registro).
3. Importar una lista IPTV real (precondición común) y ejecutar la matriz §7 en orden de prioridad (CAM-01 → CAM-09).
4. Registrar cada resultado en la plantilla del §8 y anexar la evidencia del §9.
5. Enviar resultados al repo (el agente consolida en `validaciones-fisicas.md` y el doc maestro).

**Regla de oro:** ningún resultado se declara APROBADO sin la evidencia exigida en su fila. Un escenario no ejecutable se marca BLOQUEADA y se cita la dependencia que lo impide.

## 1. Objetivo

Cerrar las validaciones físicas pendientes del MVP (F-09, F-10, F-11, F-12, física E7-T8, protocolo `e7-t10:118`, revisión I-M/DC-11) con evidencia verificable, sin reabrir decisiones arquitectónicas cerradas ni repetir trabajo CI-validado.

## 2. Estado de partida (2026-11-17)

| Hecho | Valor | Fuente |
|---|---|---|
| Implementación E1–E7 | Completa e integrada | matriz §3 de `e7-t13`, informe E4-T7/DC-07 |
| CI en `main @ c63d241` | 5/5 jobs (`jvm-tests`, `static-analysis`, `instrumented-mobile`, `instrumented-tv`, `apk`) | run `44083728034` |
| Tests JVM `:core` | 476 pruebas, 0 fallos (fresco, mismo código que `c63d241`: el merge solo añadió `.md`) | `./gradlew :core:test --rerun-tasks` 2026-11-17 |
| Pendientes físicos | F-09, F-10, F-11, F-12 + física E7-T8 + `e7-t10:118` + revisión DC-11 | `docs/evidence/validaciones-fisicas.md` |
| Evidencia física previa (no repetir) | F-01..F-08 (10 canales READY, 302→403, H.264 Exynos, recuperación Wi-Fi) | mismo registro |

## 3. Commit y APK (no usar otros)

| Dato | Resultado |
|---|---|
| Commit funcional (código) | `c63d241` (PR #40) · CI run `44083728034` (5/5 success) |
| Baseline documental de campaña | `fa7d0a9` (merge PR #41, 2026-11-17: solo añade estos 2 documentos; **el código funcional no cambió**) |
| APK válido | El del commit `c63d241` (abajo): la campaña usa este APK, que no se regeneró por el merge documental |
| Artefacto | `copernicotv-debug-apk` → `app-debug.apk` |
| Variante | debug (build de `:app`) |
| ABIs | arm64-v8a, armeabi-v7a, x86_64 (APK universal) |
| SHA-256 | `64b97880611eef43c77a3d92dfd86604f84bfae9a61c7e9dd57fc814e3c260ac` |
| Tamaño | 18.782.024 bytes |
| Descarga reproducible | `gh run download 44083728034 -R guigerdts/CoperNicoTV -n copernicotv-debug-apk` |

**Verificación del operador:** recomparar el SHA-256 tras descargar. Si difiere, detenerse y reportar. Instalación física: CAM-00 (§7).

## 4. Preparación del dispositivo

Dispositivo reportado: **Samsung SM-A356E, Android 16**. No se asume conexión: verificar primero.

```bash
adb devices -l                                  # estado de conexión (debe listar el dispositivo)
adb shell getprop ro.product.model              # esperado: SM-A356E
adb shell getprop ro.build.version.release      # Android
adb shell getprop ro.build.version.sdk          # API level
adb shell getprop ro.product.cpu.abi            # ABI (arm64-v8a esperado)
adb shell cat /proc/meminfo | head -1           # memoria
adb shell df -h /data | tail -1                 # espacio libre
adb shell pm list packages | grep copernicotv   # instalación previa (no desinstalar: conserva datos/catálogos)
```

**Hoja de comandos de campaña:**

```bash
adb install -r app-debug.apk                    # CAM-00 (conserve datos; -r no borra datos de usuario)
adb logcat -c && adb logcat | tee campana-logcat.txt        # logs con evidencia
adb shell dumpsys package com.copernicotv.app   # versión/permisos instalados
adb exec-out screencap -p > <nombre>.png        # captura de pantalla (evidencia)
adb pull /sdcard/<ruta-evidencia> .             # exportar evidencia generada en el dispositivo
adb shell screenrecord --time-limit 60 <nombre>.mp4         # video (interacciones complejas)
```

Si el dispositivo no está conectado: dejar estos comandos listos y continuar con todo lo demás (ya ejecutado en esta preparación).

## 5. Requisitos previos

- [ ] APK del §3 descargado y SHA-256 verificado.
- [ ] Dispositivo con espacio ≥ 1 GiB libre y batería > 50%.
- [ ] Una **lista IPTV real** para importar (la misma del uso cotidiano del propietario); para CAM-02, la lista grande (~147k entradas) que generó la medición de CI.
- [ ] TV no requerida para CAM-01..CAM-09 en móvil; la revisión DC-11 en Android TV (S33/S35 a 10 pies) requiere TV física — ver §12.
- [ ] Entorno de red: Wi-Fi doméstico estable; para CAM-03 permitir redirección HTTP/HTTPS.
- [ ] No desinstalar la app si ya hay datos del usuario (no borrar catálogos).

## 6. Procedimientos paso a paso

**CAM-00 Instalación:** `adb install -r app-debug.apk` → abrir la app → registrar en logcat la primera apertura sin crash.

**CAM-01..CAM-09:** seguir la fila correspondiente de la matriz §7. Cada escenario termina con: captura(s) + extracto de logcat relevante + registro en plantilla §8.

**Reglas de ejecución:** un escenario a la vez; si un paso falla, registrar el resultado tal cual (FALLIDA + evidencia) y continuar con los demás (un fallo no detiene la campaña); no repetir un escenario APROBADO para "mejorarlo".

## 7. Matriz de pruebas

| ID | Prueba | Precondiciones | Procedimiento | Resultado esperado | Evidencia | Criterio de aceptación | Fuente del criterio | Prioridad | Estado |
|---|---|---|---|---|---|---|---|---|---|
| CAM-00 | Instalación del APK | APK §3 verificado | `adb install -r` + abrir app | Instala sin error; app abre | salida `adb install`, logcat | Instalación OK y primera apertura sin crash | §4 | P0 | PREPARADA |
| CAM-01 | Importación física (F-09): S33/S35/S36/S42 + checklist E7-T7 | Lista real; app instalada | Importar lista; recorrer cada pantalla y acción **solo con controles del teléfono**; verificar importación y activación; probar progreso, persistencia y errores (URL inválida, sin conexión, lista duplicada) | Catálogo activo tras importación; errores tolerados sin crash ni pérdida de datos previos | capturas + logcat + checklist marcado | Cada ítem del checklist OK; el catálogo previo intacto; ningún crash | S33a/b, S35, S36a–d, S42a–c (mvp:210-219); DC-11 umbrales (mvp:782) | P0 | PREPARADA |
| CAM-02 | Importación masiva ~147k (F-10) | Lista grande ~147k | Importarla con `adb logcat` activo; observar progreso visible; esperar fin | Importación completa sin crash ni ANR; progreso visible durante todo el proceso | logcat completo + capturas del progreso + resultado final | Registrar: tiempo total, memoria (adb dumpsys meminfo durante y al final), tiempo de procesamiento, tiempo de persistencia, progreso visible, resultado, errores, estabilidad. **Referencia CI (no es medición física): ~146.859 filas, 148 lotes, 48.860 s de inserción** — comparar cualitativamente | F-10 + `e7-t7-ronda9-metricas-room.md` | P0 | PREPARADA |
| CAM-03 | E7-T8 MPEG-TS con 302 | Lista con canal MPEG-TS que responde 302 (ej. tipo zonacero/Caracol 2571) | Reproducir canal; abrir panel de diagnóstico; registrar URL saneada, fase, error, clasificación | READY_AUDIO_VIDEO; la redirección 302 se sigue de forma controlada (HTTP→HTTPS / mismo esquema); **sin** reenvío de credenciales a destinos no autorizados | panel + logcat Media3 | Reproducción ≥ 10 s o diagnóstico correcto si el servidor lo prohíbe (403 = causa externa documentada, como F-02) | S6 de `e7-t8-reproduccion-iptv.md` (302 mismo protocolo, HTTPS→HTTP, HTTP→HTTPS, 401/403/404) | P0 | PREPARADA |
| CAM-04 | E7-T8 HLS | Lista con canal HLS | Reproducir canal HLS; abrir diagnóstico | READY_AUDIO_VIDEO con MIME HLS reconocido | panel + logcat | Reproducción ≥ 10 s; **no mezclar este resultado con CAM-03** | S6 (200 con HLS) | P0 | PREPARADA |
| CAM-05 | `e7-t10:118` — Telecaribe 2719 | Lista con Telecaribe 2719 | Reproducir > 10 s; abrir diagnóstico persistente | «Video MIME detectado» y «Video códec detectado» = formato real del contenedor; «Pista video soportada»; **sin** `ERROR_DECODIFICACION` (antes `ERROR_RENDERIZADO`) sin reconstrucciones | panel completo + logcat | Panel con MIME/códec reales; comparar con Caracol 2704 (debe seguir READY_AUDIO_VIDEO sin cambios). Si el MIME/códec revela códec soportado que igualmente no se selecciona → reabrir caso con ese dato | `e7-t10-recuperacion-estabilidad.md:118` | P0 | PREPARADA |
| CAM-06 | H.264 hardware Exynos (confirmatoria E7-T11/T12) | Canal H.264 (Caracol 2704) | Reproducir; abrir diagnóstico | `video/avc`, decoder `c2.exynos.*`, `HARDWARE`, imagen+audio | panel | Coincide con F-05/F-07/F-08 (evidencia física previa); solo refresca, no reabre | F-05/F-07/F-08 | P1 (confirmatoria) | PREPARADA |
| CAM-07 | Fallback MPEG-2 real (E7-T12) | **Fuente que entregue realmente `video/mpeg2`** | Reproducir; diagnóstico | `decoderVideo=ffmpeg:mpeg2video`, `SOPORTADO`, imagen visible | panel | Igual que F-06 pero en físico | F-06/F-07 | **BLOQUEADA por dependencia externa** | PREPARADA |
| CAM-08 | Duck de audio auditivo (F-11, S42a) | Contenido con audio + segunda app que pida audio focus (ej. otro reproductor/Asistente) | Reproducir canal con audio; invocar la otra app; volver | Atenuación **percibida auditivamente** al duck; pausa/reanudación según el estado declarado; la app queda en el estado esperado | percepción del operador + capturas de estado (no hay API pública del multiplicador) | El operador registra PERCIBIDO/NO PERCIBIDO; el criterio es humano, no numérico | S42a (mvp:219); F-11 (`mvp:4862`) | P0 | PREPARADA |
| CAM-09 | DC-07 en físico (F-12) | App con Fuente programada (default 720 min); dispositivo reiniciable | (a) Reiniciar el dispositivo; (b) abrir la app; (c) repetir tras force-stop (Ajustes → Forzar detención) y reabrir; (d) forzar una actualización manual desde Fuentes; (e) ante un fallo (p.ej. URL muerta) verificar catálogo y guía intactos | Trabajo periódico sobrevive al reinicio; al reabrir se reconcilia (sin duplicados ni ejecución inmediata injustificada); tras force-stop **no se promete ejecución**: se reanuda en la próxima apertura; Estado de Actualización registra causa y resultado por Lista; fallo no borra catálogo | capturas del Estado de Actualización + logcat WorkManager + `adb shell dumpsys` | (a)-(e) OK según contrato; **el disparo por intervalo ya está probado con TestDriver en CI (S3b)** — en físico se valida persistencia/reconciliación/estado, no un reloj de 12 h | DC-07 puntos 5-9 (mvp:686-702); F-12 | P0 | PREPARADA |
| CAM-10 | I-M S47d/S47e — tráfico de red (DC-11) | Dispositivo + la app en reposo y en uso | Observar tráfico de red durante flujos S47 (borrar Historial/Favoritos) y durante uso normal | Solo tráfico propio solicitado; **sin telemetría ni tráfico no solicitado** | captura de tráfico (`adb shell dumpsys netstats` o herramienta de red del operador) | Ningún endpoint ajeno a las URLs de las Listas | S47d/e (mvp:224); DC-11 | P0 | PREPARADA |
| CAM-11 | S16 no-HLS con fuente real (DASH/SS/RTSP/RTMP) | **Fuente real por cada protocolo** | Reproducir cada uno y registrar | Reproducción con fixture ya validada en CI; con fuente real: READY + diagnóstico | panel | Cada protocolo del alcance §5 requiere su propia fuente real | S16 (mvp:193) | P1 (roadmap) — nota de discrepancia abajo | PREPARADA |
| CAM-12 | S19 subtítulos/pistas con fuente real | Fuente con pistas de audio/subtítulos | Listar pistas; cambiar audio/subtítulo; con fuente que no las ofrece, verificar `auto/default` y aviso en UI | Selección cambia la pista activa; preferencia guardada ≠ pistas disponibles; no se inventan pistas | capturas de pantalla de menú de pistas | S19a/S19b | S19 (mvp:196) | P1 (roadmap) | PREPARADA |
| CAM-13 | Guía EPG XMLTV con fuente real | Fuente con XMLTV real | Cargar guía; verificar retención (1 día pasado + 7 futuro, Q4) y render | Guía visible con datos reales; sin carga del documento completo | capturas de Guía | E5/S24 (parser JVM ya verde en CI) | §8.5, Q4 | P1 (roadmap) | PREPARADA |
| CAM-14 | DC-11 revisión visual en Android TV | **TV física a 10 pies** | Recorrer pantallas; medir cuerpo/título/encabezado/metadatos, foco ≥56×56 dp, indicador ≥2 dp, contraste 3:1, separación ≥8 dp | Ningún texto cortado; umbrales DC-11 cumplidos | capturas + mediciones | DC-11 (mvp:782); S32b/S33b/S38b | P2 (política) — requiere TV | PREPARADA |

**Nota de discrepancia (registrada, no resuelta por interpretación):** `e7-t13` §15 exige S16/S19/S24 "en físico" para la declaratoria, pero su tabla P0 no las incluye y S24 (`mvp:201`) es JVM (parser ya validado en CI; la guía con fuente real es el ítem P1 de §8.5). La fila S16 (`mvp:193`) exige fuente real por protocolo. **Fuentes de autoridad:** el doc maestro define los requisitos de cada spec; `e7-t13` consolida el roadmap. La declaración del MVP no debe apoyarse en un ítem cuya fuente (CAM-11/12/13) no pueda ejecutarse: si el operador no dispone de esas fuentes, quedan como P1 documentadas y se declara la discrepancia en el informe de campaña, sin inventar su cierre.

## 8. Datos que registra el operador (plantilla por escenario)

```
ID escenario: CAM-XX   Fecha/hora: ___   Ejecutor: ___
Resultado: PREPARADA / EJECUTADA-OK / APROBADA / FALLIDA / BLOQUEADA
Pasos ejecutados (verbatim de la fila): ___
Desviaciones observadas: ___
Métricas (si aplica): tiempo total __ · memoria inicial/final __ · progreso visible SÍ/NO · errores: ___
Logs: extracto relevante pegado o adjunto (campana-logcat.txt con marca de tiempo)
Capturas: nombres de archivo + a qué paso corresponden
Conclusión del operador (1 línea)
```

## 9. Evidencia requerida

Por escenario: **(1)** captura(s) de pantalla o grabación, **(2)** extracto de `campana-logcat.txt` con marca de tiempo, **(3)** plantilla §8 completada. CAM-02 añade `adb shell dumpsys meminfo com.copernicotv.app` al inicio, durante y al final. CAM-09 añade `adb logcat` filtrado por WorkManager (`adb logcat -s WM-*`). CAM-10 añade la salida de tráfico. Los archivos se entregan en un directorio `campana-fisica-YYYY-MM-DD/` que el agente consolida en `docs/evidence/validaciones-fisicas.md` (nuevas filas, sin sobrescribir históricas).

## 10. Criterios de aceptación (por escenario)

Los definidos **solo** por la columna "Criterio de aceptación" de §7, citada de la fuente original de cada spec (doc maestro + feature docs). Nada se marca APROBADA sin la evidencia del §9. Un resultado FALLIDA genera: reproducción del fallo, reporte en el informe de campaña y evaluación de corrección como defecto reproducible (regla 5 del mandato), sin modificar código en caliente durante la campaña.

## 11. Clasificación P0/P1/P2

| Nivel | Escenarios | Justificación (fuente) |
|---|---|---|
| **P0** (necesarios para declarar MVP) | CAM-00..CAM-05, CAM-08, CAM-09, CAM-10 | Tabla P0 de `e7-t13` (F-09, F-10, F-11, física E7-T8, `e7-t10:118`, I-M/DC-11) + F-12 del cierre DC-07 |
| **P1** (versión sólida; no bloquean la declaratoria) | CAM-06 (confirmatoria), CAM-11, CAM-12, CAM-13 | Roadmap P1 de `e7-t13` (S16 no-HLS, S19, EPG real); CAM-06 ya tiene evidencia física previa F-05/F-07/F-08 |
| **P2** (mejoras/posteriores) | CAM-14 | DC-11 es la política P2 aprobada; requiere TV física |
| **Dependencia externa** | CAM-07 (fuente `video/mpeg2`), CAM-11 (fuentes por protocolo), CAM-14 (TV) | No bloquean los escenarios independientes |

## 12. Dependencias externas

1. **CAM-07:** ninguna fuente conocida entrega hoy `video/mpeg2` (F-04/F-07: Telecaribe cambió de MPEG-2 a H.264 entre sesiones). Buscar fuente es tarea externa del propietario; no bloquea CAM-00..CAM-06, CAM-08..CAM-10.
2. **CAM-11:** URLs reales de DASH/SmoothStreaming/RTSP/RTMP del operador. Con fixture ya validado en CI, la validación con fuente real es P1.
3. **CAM-14:** TV física a 10 pies; los checks de TV automatizados (I-TV) ya corren en CI (`instrumented-tv` verde).

## 13. Criterios para declarar el MVP

El MVP se declara cuando:

- [ ] CAM-00..CAM-05, CAM-08, CAM-09, CAM-10 **APROBADAS con evidencia** (o FALLIDA resuelta como defecto documentado y corregido con su regresión, revalidando el escenario).
- [ ] `docs/evidence/validaciones-fisicas.md` actualizado: F-09..F-12 con resultado y evidencia (nuevas filas, históricas intactas).
- [ ] Doc maestro y roadmap actualizados con la declaratoria y sus referencias.
- [ ] Los P1 (CAM-11..CAM-13) quedan explícitamente fuera de la declaratoria o se incluyen si el operador ejecuta y aprueba con fuente real.
- [ ] Las dependencias externas (CAM-07/11/14) documentadas como pendientes de P1/P2, sin bloquear.
- [ ] Ningún criterio se cierra por inferencia o medición de CI presentada como física.

## Checklist del agente (preparación)

- [x] Estado del repo verificado (main @ c63d241, limpio, CI 5/5).
- [x] APK del commit correcto identificado, descargado y hasheado (§3).
- [x] Criterios tomados de los documentos originales (sin umbrales inventados).
- [x] Hoja ADB preparada (dispositivo no conectado al momento de la preparación).
- [x] Discrepancia S16/S19/S24 registrada (§7), no resuelta por interpretación.
- [x] Evidencia histórica F-01..F-08 preservada; nada marcado APROBADO sin evidencia.

## Next step

El operador ejecuta el Quick path (§Quick path) con el dispositivo físico; los resultados vuelven al agente para consolidar en `validaciones-fisicas.md` y el doc maestro.
