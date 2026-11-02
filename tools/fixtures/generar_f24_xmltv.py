#!/usr/bin/env python3
"""Genera los fixtures XMLTV F24a-F24c de S24.

F24a conserva el fixture determinista de la medición P1 XMLTV: 100 canales y
10 000 programas, con fechas UTC y offsets explícitos. F24b y F24c materializan
los casos funcionales ya definidos por las pruebas del parser: XML malformado e
incompleto. No usan datos de terceros y no dependen de aleatoriedad.

Uso:
    python3 tools/fixtures/generar_f24_xmltv.py \
        --salida-dir core/src/test/resources/fixtures
"""

from __future__ import annotations

import argparse
import hashlib
from datetime import datetime, timedelta, timezone
from pathlib import Path

CANALES = 100
PROGRAMAS = 10_000
INICIO = datetime(2026, 1, 1, tzinfo=timezone.utc)
FORMATO = "%Y%m%d%H%M%S +0000"


def generar_f24a() -> bytes:
    lineas = ['<?xml version="1.0" encoding="UTF-8"?>', "<tv>"]
    for canal in range(CANALES):
        lineas.append(
            f'<channel id="c{canal}"><display-name>Canal {canal}</display-name></channel>'
        )
    for indice in range(PROGRAMAS):
        canal = indice % CANALES
        inicio = INICIO + timedelta(seconds=indice * 900)
        fin = inicio + timedelta(seconds=900)
        lineas.append(
            f'<programme channel="c{canal}" start="{inicio.strftime(FORMATO)}" '
            f'stop="{fin.strftime(FORMATO)}">'
            f"<title>Programa {indice}</title><desc>Descripción {indice}</desc></programme>"
        )
    lineas.append("</tv>")
    return ("\n".join(lineas) + "\n").encode("utf-8")


def generar_f24b() -> bytes:
    return """<tv>
  <channel id="a"><display-name>A</display-name></channel>
  <programme start="20260101120000 +0000" stop="20260101130000 +0000" channel="a"><title>Uno</title></programme>
  <programme start="20260101130000 +0000" stop="20260101140000 +0000" channel="a">""".encode("utf-8")


def generar_f24c() -> bytes:
    return """<tv>
  <channel id="a"><display-name>A</display-name></channel>
  <programme start="20260101120000 +0000" channel="a"><title>Sin fin</title></programme>
  <programme start="20260101130000 +0000" stop="20260101140000 +0000"><title>Sin canal</title></programme>
  <programme start="20260101140000 +0000" stop="20260101150000 +0000" channel="a"><title>Completo</title></programme>
</tv>""".encode("utf-8")


def main() -> None:
    parser = argparse.ArgumentParser(description="Genera los fixtures XMLTV F24a-F24c")
    parser.add_argument("--salida-dir", required=True, type=Path)
    args = parser.parse_args()

    fixtures = {
        "F24a.xml": generar_f24a(),
        "F24b.xml": generar_f24b(),
        "F24c.xml": generar_f24c(),
    }
    args.salida_dir.mkdir(parents=True, exist_ok=True)
    for nombre, contenido in fixtures.items():
        destino = args.salida_dir / nombre
        destino.write_bytes(contenido)
        print(f"{nombre}: bytes={len(contenido)} sha256={hashlib.sha256(contenido).hexdigest()}")


if __name__ == "__main__":
    main()
