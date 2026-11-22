# physical-qa — CoperNicoTV physical QA executor

Lightweight Bash executor for the physical validation campaign, driven over
wireless ADB from an Ubuntu shell under Termux (no PC, no external services).
It automates every step that ADB can verify mechanically and clearly separates
what needs human perception (audio/visual checks) or operator authorization.

## Install/run

```bash
cd tools/physical-qa && chmod +x pqa   # first time only
./pqa help
```

Optional: `pkg install sqlite` (Termux) or `apt install sqlite3` to enable DB
diagnostics. The executor never installs anything by itself.

## Safety model (enforced by the script)

- **Read-only against the device.** Commands used: `getprop`, `dumpsys`,
  `logcat`, `pm list/path/path`, `df`, `pidof`, `run-as cat`, `adb pull`,
  `input`-free. It never uninstalls, clears, force-stops, pushes, or deletes
  anything on the device, and never touches the user's database.
- **No evidence is ever overwritten or deleted.** Every artifact gets a
  date+time+campaign-id name; collisions get an extra suffix.
- **Every command is logged** with timestamp, exit code and duration in
  `pqa-commands-<time>.log` inside the evidence root.
- **Verdicts are separated**: `PASS` / `FAIL` / `BLOCKED` / `INCONCLUSIVE`
  recorded per check in `results.tsv`. A working ADB command never equals an
  approved audiovisual test: tests requiring human perception are marked
  SUPERVISED and only the operator can mark them PASS.
- **Device DB extraction is byte-exact:** `run-as cat` copies of
  `copernicotv.db` + `-wal` + `-shm`, hashed with SHA-256. All SQL (integrity
  check, diagnostics) runs on a scratch copy, so the evidence copy stays
  byte-exact and the device file is never mutated (no WAL checkpoint on device).
- **Connectivity outages:** autonomous capture runs ON the device
  (`logcat`/`screenrecord` to `/sdcard/campana/<date>/`), survives ADB loss, and
  is recovered after reconnect. The executor never toggles Wi-Fi; connectivity
  changes always require explicit operator authorization.

## Evidence layout

```
campana-fisica-YYYY-MM-DD/          # evidence root (runbook section 9)
  pqa-commands-HHMMSS.log           # full command log of the session
  CAM-XX/
    results.tsv                     # PASS/FAIL/BLOCKED/INCONCLUSIVE per check
    logcat-*.txt                    # captured logcat
    meminfo-*.txt                   # memory snapshots
    system-*.txt                    # system snapshots
    copernicotv.db-<ts>[-wal|-shm]  # byte-exact DB copies
    db-sha256.txt                   # hashes of the extracted copies
    diag-*.txt                      # integrity + session/event queries
    report-*.md                     # per-campaign report
```

Override the root with `PQA_EVIDENCE_DIR=/path`.

## Commands

| Command | What it does | Result |
|---|---|---|
| `pqa device [CAM-ID]` | connection, model (expected SM-A356E), Android/API/ABI, battery, space | PASS/BLOCKED |
| `pqa app [CAM-ID]` | package, version, PID, meminfo, `base.apk` SHA-256 traceability against runbook section 3/3.1, DB listing | PASS/INCONCLUSIVE |
| `pqa system [CAM-ID]` | system snapshot file (props, mem, storage, battery, uptime) | PASS |
| `pqa logcat [CAM-ID] [--seconds N]` | buffer dump (default) or live capture for N s, then automatic crash/ANR scan | PASS |
| `pqa filter <logfile> [CAM-ID]` | scan any logcat for `FATAL EXCEPTION`, ANR, app death, app errors | PASS/INCONCLUSIVE |
| `pqa memory [CAM-ID]` | `dumpsys meminfo` snapshot (requires the app running) | PASS/BLOCKED |
| `pqa db [CAM-ID]` | pull byte-exact DB+WAL+SHM copies, hash them, run diagnostics | PASS/FAIL |
| `pqa query "SQL"` | ad-hoc SQL on the latest extracted copy (scratch copy) | — |
| `pqa diag [CAM-ID]` | integrity_check + recent sessions + event counts + RECUPERACION_RED + catalog size | PASS/BLOCKED |
| `pqa autocapture <CAM-ID> <seconds> [--screen]` | start autonomous on-device logcat (+screenrecord max 180 s per call) that survives Wi-Fi loss | PASS |
| `pqa autocapture-stop` | send stop signal to the device-side capture | PASS |
| `pqa recover <CAM-ID>` | pull the autonomous evidences from `/sdcard` after reconnect (device copies preserved) | PASS/FAIL |
| `pqa evidence <CAM-ID> [files...]` | register external artifacts (screenshots, operator notes) with hash-worthy copies | — |
| `pqa report <CAM-ID> [PASS\|FAIL\|BLOCKED\|INCONCLUSIVE] ["summary"]` | write `report-*.md`; refuses to invent a verdict | — |
| `pqa status` | device/app overview + per-campaign result counts | — |

## Typical flows

Before/after any scenario (read-only, always safe):

```bash
./pqa device CAM-09 && ./pqa app CAM-09 && ./pqa system CAM-09
./pqa memory CAM-09         # while the app is open
./pqa logcat CAM-09         # buffer dump + crash scan
./pqa db CAM-09             # extraction + integrity + diagnostics
```

Scenario with a connectivity cut (CAM-16 style, operator cuts/restores Wi-Fi):

```bash
./pqa autocapture CAM-16 0 --screen    # starts device-side capture
# operator: reproduce the scenario; cut and restore Wi-Fi; ADB may drop
./pqa recover CAM-16                   # after reconnect
./pqa logcat CAM-16                    # host-side post-scenario scan
./pqa report CAM-16 INCONCLUSIVE "Wi-Fi instability, see logcat-*.txt"
```

Campaign verdicts are recorded with:

```bash
./pqa report CAM-09 PASS "restart + force-stop reconciliation verified by operator"
```

## What still needs a human (SUPERVISED, by design)

- Perceived audio ducking (CAM-08), visual quality checks (CAM-14/DC-11).
- Declaring PASS for any audiovisual outcome: ADB proves commands ran, not
  that the picture/sound was correct.
- Any connectivity change, app install/uninstall, or data-affecting action.
- Fixing `INSTALL_FAILED_UPDATE_INCOMPATIBLE` (signature change): never
  auto-resolved here; deciding requires the operator.
