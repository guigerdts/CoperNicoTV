# Revisión humana del baseline P1 — aprobada

**Estado: aprobada el 2026-10-29 por el propietario del producto.** Este
documento registra la aprobación explícita de las cinco decisiones que DC-10 §5
exige para versionar el baseline P1. El nombre del archivo es histórico: se
conserva para no romper las referencias existentes.

La aprobación cubre la medición **JVM** registrada en
`docs/evidence/p1/p1-baseline.json`. No incluye una medición P1 en Android ni una
medición de regresión posterior a la aprobación.

## Datos observados

Tres corridas verdes consecutivas del job `p1-performance`, todas sobre el
commit `b186c3a`, con el procedimiento corregido (`System.gc()` antes del
muestreador de heap).

| # | Run | Mediana | p95 | Máximo | Pico heap |
|---|---|---|---|---|---|
| 1 | `40877200854` | 7.403 ms | 10.334 ms | 12.260 ms | 44.836 MiB |
| 2 | `40878469399` | 7.401 ms | 10.421 ms | 11.773 ms | 45.223 MiB |
| 3 | `40879581126` | 10.047 ms | 15.067 ms | 21.284 ms | 45.827 MiB |

Fixture: `F-P1-10k.m3u`, 10 000 entradas, 1 940 008 bytes, SHA-256
`45c489a557efcafa53af5bffd9a001aeb2cdc1616c40a4f823e2f39817ddd04c`.
Límites DC-10: p95 ≤ 1200 ms y pico ≤ 256 MiB.

## Conclusión técnica

- Las tres corridas cumplen los límites absolutos con margen amplio: el peor
  p95 (15.067 ms) es el 1.26 % del límite y el peor pico (45.827 MiB) es el
  17.9 % del límite.
- La dispersión es baja y el pico de heap es estable (44.836–45.827 MiB), lo
  que respalda que la medición ya no captura basura previa.
- Se toma el **peor valor** de las tres corridas como referencia: p95
  15.067 ms y pico 45.827 MiB. Con los factores de DC-10, la regresión
  admisible queda en 18.080 ms y 52.701 MiB.
- La **regresión no se evalúa** en esta primera calibración: no existe un
  baseline aprobado anterior contra el cual comparar (DC-10 §5). Se evaluará
  desde la primera medición posterior a la aprobación.
- Se descartan las corridas anteriores por los motivos registrados en
  `docs/evidence/p1/p1-baseline.md`: una cancelada globalmente por concurrencia,
  dos con el procedimiento anterior y una con el pico contaminado de
  228.520 MiB.

## Decisiones aprobadas por el propietario (2026-10-29)

1. **Aprobado** el baseline y su referencia: peor p95 15.067 ms y peor pico
   45.827 MiB.
2. **Confirmado** el criterio de referencia: peor valor de las tres corridas,
   no promedio ni mediana.
3. **Confirmado** que tres corridas sobre el mismo commit `b186c3a` cuentan
   como «tres corridas verdes consecutivas» para DC-10.
4. **Confirmado** el descarte de `40711018242`, `40857174822`, `40861342045` y
   `40870356075` por los motivos registrados.
5. **Confirmado** que la regresión queda no evaluada hasta la primera medición
   posterior a la aprobación.

## Qué cambia con la aprobación

- El baseline pasa a ser la referencia de regresión para las mediciones JVM de
  E3, E4, E5 y E6.
- Una medición futura por encima de 18.080 ms de p95 o 52.701 MiB de pico
  dispara revisión; no se declara regresión automáticamente.
- La revisión humana del baseline deja de ser un pendiente de E2-T5.

## Qué no hace este documento

- No declara E2 cerrada. La aprobación satisface la revisión humana de DC-10 §5
  para la medición JVM; E2-T5 enumera además límites P1 en Android que no se
  midieron, y esa cláusula sigue pendiente.
- No sustituye una medición de regresión posterior ni una medición P1 en
  Android.
- No reescribe la evidencia histórica de las corridas ni la aceptación SAF con
  limitación registrada en §10.9.1 del contrato U0.
