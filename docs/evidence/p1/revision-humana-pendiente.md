# Revisión humana del baseline P1 — pendiente

**Estado: pendiente.** Este documento reúne los datos y las decisiones que
corresponden al propietario. No registra una aprobación, no marca la revisión
como hecha y no declara E2 cerrada.

DC-10 exige revisión humana del baseline antes de usarlo como referencia de
regresión. La revisión es una acción del propietario; este archivo solo la
prepara.

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

## Conclusión técnica propuesta

- Las tres corridas cumplen los límites absolutos con margen amplio: el peor
  p95 (15.067 ms) es el 1.26 % del límite y el peor pico (45.827 MiB) es el
  17.9 % del límite.
- La dispersión es baja y el pico de heap es estable (44.836–45.827 MiB), lo
  que respalda que la medición ya no captura basura previa.
- Se propone tomar el **peor valor** de las tres corridas como referencia:
  p95 15.067 ms y pico 45.827 MiB. Con los factores de DC-10, la regresión
  admisible queda en 18.080 ms y 52.701 MiB.
- La **regresión no se evalúa** en esta primera calibración: no existe un
  baseline aprobado anterior contra el cual comparar (DC-10 §5). Se evaluará
  desde la primera medición posterior a la aprobación.
- Se descartan las corridas anteriores por los motivos registrados en
  `docs/evidence/p1/p1-baseline.md`: una cancelada globalmente por concurrencia,
  dos con el procedimiento anterior y una con el pico contaminado de
  228.520 MiB.

## Decisiones que corresponden al propietario

1. **Aprobar o rechazar** el baseline y su referencia (peor p95 15.067 ms,
   peor pico 45.827 MiB).
2. **Confirmar el criterio de referencia**: peor valor de las tres corridas, no
   promedio ni mediana.
3. **Confirmar que tres corridas sobre el mismo commit** cuentan como «tres
   corridas verdes consecutivas» para DC-10. El criterio no exige commits
   distintos y el mismo commit elimina diferencias de código entre corridas.
4. **Confirmar el descarte** de `40711018242`, `40857174822`, `40861342045` y
   `40870356075` por los motivos registrados.
5. **Confirmar que la regresión queda no evaluada** hasta la primera medición
   posterior a la aprobación.

## Qué cambia si se aprueba

- El baseline pasa a ser la referencia de regresión para las mediciones de E3,
  E4, E5 y E6.
- Una medición futura por encima de 18.080 ms de p95 o 52.701 MiB de pico
  dispara revisión; no se declara regresión automáticamente.
- La revisión del baseline deja de ser un pendiente de E2-T5.

## Qué no hace este documento

- No marca la revisión como hecha ni registra una aprobación en nombre del
  propietario.
- No declara E2 cerrada: la verificación SAF fue ejecutada y aceptada por el
  propietario el 2026-10-29 con limitación (grant no inspeccionado, §10.4); la
  revisión humana del baseline P1 sigue pendiente.
- No sustituye la decisión del propietario.
