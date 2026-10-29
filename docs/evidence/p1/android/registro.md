# Medición P1 en Android (E2-T5)

- **Fecha:** 2026-10-29
- **Commit medido:** `a28971a2a9b5cf6b896d73e1a7f3933351785f57`
- **Workflow / run:** `.github/workflows/ci.yml`, run `40929318245`
  (`workflow_dispatch`), job `p1-android`; 7/7 jobs verdes.
- **Artifact:** `p1-android-medicion` (1 236 bytes) con `medicion.json`,
  `mediciones.csv` y `mediciones-heap.csv`. Los tres archivos de este directorio
  son el contenido exacto de ese artifact.
- **Entorno:** emulador Android API 34 / Android 14, modelo
  `sdk_gphone64_x86_64`, device `emu64xa`, hardware `ranchu`, 1080x1920,
  320 dpi, 4 GB de RAM, imagen `google_apis`, arquitectura `x86_64`.
- **Fixture:** `app/src/androidTest/assets/F-P1-10k.m3u`, 10 000 entradas,
  SHA-256 `45c489a557efcafa53af5bffd9a001aeb2cdc1616c40a4f823e2f39817ddd04c`.
- **Procedimiento:** 5 calentamientos; 20 mediciones de tiempo de parseo; 20
  mediciones de pico de heap Java (`Runtime.totalMemory() - freeMemory()`); GC y
  pausa de 50 ms antes de cada muestreo de heap; muestreo cada 2 ms; p95 por
  nearest-rank; mediana y máximo.
- **Límites:** parseo p95 ≤ 2500 ms; heap Java ≤ 384 MiB.
- **Resultado:** mediana 134.333 ms; p95 231.794 ms; máximo 250.297 ms; pico de
  heap 35.809 MiB; `cumple: true`.
- **Verificación:** 20 filas de tiempo y 20 de heap; p95, mediana (redondeo
  HALF_UP), máximo y pico de heap recalculados desde los CSV coinciden con
  `medicion.json`.
- **Nota de extracción:** el test escribe en `additionalTestOutputDir`; UTP copia
  esa salida al host antes de desinstalar la app y
  `.github/scripts/p1-android.sh` la recupera. La primera calibración Android
  aplica límites absolutos y no versiona baseline Android (DC-10 §5).
