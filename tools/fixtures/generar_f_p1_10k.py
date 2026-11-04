#!/usr/bin/env python3
"""Genera el fixture determinista F-P1-10k.m3u de DC-10.

El fixture tiene 10 000 entradas sintéticas con nombres, grupos, tvg-id y URLs
`example.invalid` (RFC 2606). No usa datos de terceros y no depende de
aleatoriedad: dos ejecuciones producen bytes idénticos.

Uso:
    python3 tools/fixtures/generar_f_p1_10k.py --salida core/src/test/resources/fixtures/F-P1-10k.m3u
"""

from __future__ import annotations

import argparse
import hashlib
from pathlib import Path

ENTRADAS = 10_000
GRUPOS = 100
BASE = "https://example.invalid"


def generar() -> bytes:
    lineas = ["#EXTM3U"]
    for indice in range(1, ENTRADAS + 1):
        grupo = (indice - 1) // (ENTRADAS // GRUPOS) + 1
        nombre = f"Canal P1 {indice:05d}"
        identificador = f"p1-{indice:05d}"
        lineas.append(
            f'#EXTINF:-1 tvg-id="{identificador}" tvg-name="{nombre}" '
            f'group-title="Grupo {grupo:03d}" tvg-logo="{BASE}/logos/{identificador}.png",{nombre}'
        )
        lineas.append(f"{BASE}/streams/{identificador}.m3u8")
    return ("\n".join(lineas) + "\n").encode("utf-8")


def main() -> None:
    parser = argparse.ArgumentParser(description="Genera F-P1-10k.m3u")
    parser.add_argument("--salida", required=True, type=Path)
    args = parser.parse_args()

    contenido = generar()
    args.salida.parent.mkdir(parents=True, exist_ok=True)
    args.salida.write_bytes(contenido)
    print(f"entradas={ENTRADAS} bytes={len(contenido)}")
    print(f"sha256={hashlib.sha256(contenido).hexdigest()}")


if __name__ == "__main__":
    main()
