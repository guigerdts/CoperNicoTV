# Commits con review GGA ante reinicios y falta de RAM

Guía de resolución de problemas para el hook `pre-commit` de GGA
(Gentleman Guardian Angel) en este entorno: OpenCode corriendo en Termux
(Android aarch64). Audiencia: sesiones de agente que crean commits y el
propietario que lanza los scripts desde terminal.

## Problema

El hook `pre-commit` ejecuta `gga run`, que lanza una revisión OpenCode
anidada (`provider = opencode`, `timeout = 500s`, `STRICT_MODE = true` en
`.gga`). Dos efectos en este entorno:

1. La revisión larga destabiliza el servidor de OpenCode: el runtime del
   agente se reinicia, lo que mata el `git commit` en curso y deja la
   sesión sin commits. En un bloque de varios commits, cada intento
   avanza un paso y se interrumpe el siguiente.
2. El teléfono se queda sin RAM y se cuelga: cada `gga run` revisiona
   toda la superficie staged y los procesos anidados (OpenCode + MCP)
   compiten por memoria con la sesión del agente.

Precedente E6-T3 (logbook `odd/tasks/mvp-copernicotv.md`, L72): los
commits `49259ef/7143727/a2deddd` se crearon con `--no-verify` por este
mismo problema, con verificación sustituta en CI + `ktlintCheck`/`detekt`
locales. Queda registrado que `--no-verify` es una salida excepcional
documentada a posteriori, no una práctica por defecto.

## Técnica recomendada: script warm lanzado por el propietario

La sesión de E7-T5 desarrolló (`/tmp/opencode/e7t5-gga-warm.sh` →
`warm-parts.sh` → `warm-single.sh` → `warm13.sh`) y la sesión de DC-04
generalizó esta variante que conserva el hook intacto:

1. El agente escribe un script en `/tmp/opencode/<feature>-commits.sh`
   (plantilla abajo) y lo deja listo con sintaxis verificada.
2. **El propietario lo lanza desde su propia terminal** (fuera del
   runtime del agente): ahí el hook corre completo sin que el reinicio
   del servidor de OpenCode lo interrumpa.
3. El script procesa un grupo de commit por invocación (argumento), para
   limitar la RAM usada por revisión.

### Paso a paso por grupo de commit

1. `git restore --staged -- .` — limpiar todo staging previo.
2. **Precalentar el caché en subsets de ≤ 2 archivos `.kt`**: por cada
   subset, `git add`, `gga run`, hasta 6 intentos con espera de 15 s.
   La revisión de 2 archivos cabe en RAM y queda en caché
   (`~/.cache/gga`, clave por hash de contenido).
   - Resultado ambiguo (`STRICT MODE: Failing due to ambiguous`) o
     `UNKNOWN`: tolerarlo, solo cuesta un reintento.
   - `STATUS: FAILED` real con hallazgos en los archivos del subset:
     **cortar y avisar al agente** (exit 2). No martillar una revisión
     que encontró defectos verdaderos.
3. **Commit**: `git add` del grupo completo, `git commit` con mensaje
   convencional. El hook pasa rápido: los archivos ya están en caché
   (reintentar hasta 12 veces con espera de 8 s ante ambigüedad
   residual, como `warm13.sh`).
4. Registrar en el log (`/tmp/opencode/<feature>-commits.log`) marcas
   de tiempo y un mensaje final claro para que el propietario vuelva con
   el agente (`=== <tag> end rc=0 ===`).

El push de la rama va al final del último grupo o como grupo propio.

### Caché GGA

- ubicación: `~/.cache/gga/<hash>/`; el archivo `metadata` guarda la
  revisión; `gga cache status` reporta archivos cacheados.
- Si una revisión interrumpida dejó una respuesta ambigua cacheada, el
  reintento la reutiliza y falla igual: borrar la entrada del run
  interrumpido (`rm -rf ~/.cache/gga/<hash>`) **no** debilita la
  revisión, fuerza una revisión fresca. Identificar la entrada por su
  fecha de modificación (`ls -lat`).
- `*.kt` / `*.kts` son revisados; `.md` y otros archivos no entran en
  el patrón `FILE_PATTERNS` y el hooked commit los aprobará directo.

### Liberar RAM antes de revisar

`/tmp/opencode/free-memory.sh` mata los servidores MCP de la sesión
(`codegraph serve`, etc.) que no necesita la verificación de commits y
muestra `free -m`. Ejecutarlo si el teléfono está justo de memoria.

## Plantilla del script

```bash
#!/bin/bash
# Commits por grupos con warming de la review GGA.
# Uso: bash <este-script> <c2|c3|...|push>   (un grupo por invocación)
set -euo pipefail
cd /ruta/al/worktree || exit 90
LOG=/tmp/opencode/<feature>-commits.log
echo "=== <grupo> start $(date +%H:%M:%S) ===" >> "$LOG"

restore_all() { git restore --staged -- . >/dev/null 2>&1; }

KT=(  # archivos .kt del grupo
  ruta/a/archivo1.kt
  ruta/a/archivo2.kt
)

case "$1" in
c2)
  N=${#KT[@]}; OFF=0
  while [ "$OFF" -lt "$N" ]; do
    SUB=("${KT[@]:OFF:2}")
    for TRY in 1 2 3 4 5 6; do
      echo "--- subset[$OFF] try $TRY: ${SUB[*]} ---" >> "$LOG"
      restore_all; git add "${SUB[@]}" || { echo "ADD FAILED" >> "$LOG"; sleep 8; continue; }
      if gga run >> "$LOG" 2>&1; then
        echo "=== subset[$OFF] PASSED try $TRY ===" >> "$LOG"; break
      fi
      # FAILED real con hallazgos corta; ambiguo reintenta.
      if tail -80 "$LOG" | grep -aq "^[[:space:]]*STATUS: FAILED"; then
        echo "⚠️ PASSED real FAILED — avisale al agente"; exit 2
      fi
      sleep 15
    done
    OFF=$((OFF+2))
  done
  restore_all; git add "${KT[@]}"
  for TRY in 1 2 3 4 5 6 7 8 9 10 11 12; do
    git commit -m "<tipo>: <mensaje> (<contexto>)" >> "$LOG" 2>&1 && break
    sleep 8
  done
  git log --oneline -1 >> "$LOG"
  echo "=== c2 end rc=0 $(date +%H:%M:%S) ===" >> "$LOG"
  echo "⚠️  Cuando veas 'c2 end rc=0' avisale al agente y lanzá el siguiente grupo."
  ;;
push)
  git push -u origin <rama> >> "$LOG" 2>&1
  echo "=== push end rc=0 $(date +%H:%M:%S) ===" >> "$LOG"
  echo "⚠️  Push hecho: revisar el run de CI y avisar al agente."
  ;;
esac
```

## Qué no hacer

- `--no-verify` salvo decisión explícita del propietario documentada en
  el logbook con su verificación sustituta (precedente E6-T3 L72).
- Relajar `STRICT_MODE` en `.gga`: convierte la falla simple de la
  revisión en silencio.
- Insertar varios `git commit` en un solo comando del agente: si el
  runtime se reinicia a mitad, se pierde el estado y los mensajes.
- Reintentar una revisión que devolvió `STATUS: FAILED` con hallazgos
  reales: avisar al agente para corregir y volver a warm.
- Detectar el `FAILED` con `grep` sin anclar a inicio de línea: el
  banner de GGA imprime los literales `'STATUS: PASSED' or
  'STATUS: FAILED'` al explicar el modo estricto y produce un falso
  positivo que corta el warm. Anclar con `grep -aq "^[[:space:]]*STATUS:
  FAILED"` (así lo hace `warm13.sh` con su doble condición).

## Referencias

- Hook: `<git-dir>/hooks/pre-commit` → `gga run`.
- Config del proyecto: `.gga` (`PROVIDER`, `STRICT_MODE`, `TIMEOUT`).
- Config global del propietario: `/root/.config/gga/config`.
- Scripts históricos: `/tmp/opencode/e7t5-warm13.sh`,
  `e7t5-warm-parts.sh`, `e7t5-gga-warm.sh`, `free-memory.sh`
  (no duran entre máquinas; esta guía contiene la plantilla).
- Sesión DC-04 (E2-T6): `/tmp/opencode/dc04-commits.sh` adaptó la
  técnica a cinco grupos de commit + push.
