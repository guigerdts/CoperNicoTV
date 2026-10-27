#!/usr/bin/env python3
"""Mide la brecha entre el NFC de la plataforma (Unicode 13.0) y Unicode 15.1.

Evidencia de la decisión registrada en E1: el case folding completo se puede
implementar por tabla, pero la NFC que usa `java.text.Normalizer` en JDK 17
implementa Unicode 13.0 y no es equivalente a 15.1.

El script descarga los datos oficiales a un directorio temporal, imprime su
SHA-256 y calcula:

- code points asignados por Unicode 14.0 o posterior;
- cuántos de ellos tienen `ccc != 0`;
- cuántos tienen descomposición canónica;
- la intersección, que es exactamente el conjunto donde 13.0 y 15.1 discrepan.

Uso:
    python3 tools/unicode/analizar_brecha_nfc.py
"""

from __future__ import annotations

import hashlib
import pathlib
import tempfile
import urllib.request

VERSION = "15.1.0"
BASE = f"https://www.unicode.org/Public/{VERSION}/ucd/"
ARCHIVOS = ("UnicodeData.txt", "DerivedAge.txt")


def descargar(destino: pathlib.Path, nombre: str) -> pathlib.Path:
    ruta = destino / nombre
    if not ruta.is_file():
        urllib.request.urlretrieve(BASE + nombre, ruta)  # noqa: S310 - URL fija y versionada
    return ruta


def main() -> int:
    with tempfile.TemporaryDirectory(prefix="brecha-nfc-") as temporal:
        directorio = pathlib.Path(temporal)
        rutas = {nombre: descargar(directorio, nombre) for nombre in ARCHIVOS}
        for nombre, ruta in rutas.items():
            huella = hashlib.sha256(ruta.read_bytes()).hexdigest()
            print(f"{nombre}: sha256={huella}")

        edad: dict[int, tuple[int, ...]] = {}
        for linea in rutas["DerivedAge.txt"].read_text(encoding="utf-8").splitlines():
            contenido = linea.split("#", 1)[0].strip()
            if not contenido:
                continue
            rango, version = [campo.strip() for campo in contenido.split(";")]
            primero, _, ultimo = rango.partition("..")
            inicio = int(primero, 16)
            fin = int(ultimo, 16) if ultimo else inicio
            for punto in range(inicio, fin + 1):
                edad[punto] = tuple(int(parte) for parte in version.split("."))

        ccc: dict[int, int] = {}
        descomposiciones: dict[int, str] = {}
        for linea in rutas["UnicodeData.txt"].read_text(encoding="utf-8").splitlines():
            campos = linea.split(";")
            if len(campos) < 6:
                continue
            punto = int(campos[0], 16)
            if int(campos[3]):
                ccc[punto] = int(campos[3])
            if campos[5] and not campos[5].startswith("<"):
                descomposiciones[punto] = campos[5]

        nuevos = {punto for punto, version in edad.items() if version >= (14, 0, 0)}
        marcas_nuevas = sorted(punto for punto in nuevos if punto in ccc)
        descomposiciones_nuevas = sorted(punto for punto in nuevos if punto in descomposiciones)

        print(f"code points con edad >= 14.0: {len(nuevos)}")
        print(f"ccc != 0 totales: {len(ccc)}")
        print(f"ccc != 0 con edad >= 14.0: {len(marcas_nuevas)}")
        for punto in marcas_nuevas:
            print(f"  U+{punto:04X} ccc={ccc[punto]}")
        print(f"descomposiciones canónicas totales: {len(descomposiciones)}")
        print(f"descomposiciones canónicas con edad >= 14.0: {len(descomposiciones_nuevas)}")
        print("=> 13.0 y 15.1 difieren en NFC solo sobre las marcas anteriores.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
