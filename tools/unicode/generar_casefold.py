#!/usr/bin/env python3
"""Genera la tabla de case folding completo de Unicode 15.1.0.

Fuente oficial: `CaseFolding.txt` de Unicode 15.1.0, estados C (común) y F
(completo). Los estados S (simple) y T (turco) se descartan a propósito: el
contrato de identidad de U0 exige case folding completo sin locale.

Uso:
    python3 tools/unicode/generar_casefold.py

El script reescribe `core/src/main/kotlin/com/copernicotv/core/identity/casefold/`
y es determinista: mismos datos de entrada producen los mismos bytes.
"""

from __future__ import annotations

import hashlib
import pathlib
import sys

RAIZ = pathlib.Path(__file__).resolve().parents[2]
FUENTE = RAIZ / "tools" / "unicode" / "CaseFolding.txt"
DESTINO = RAIZ / "core" / "src" / "main" / "kotlin" / "com" / "copernicotv" / "core" / "identity" / "casefold"
VERSION = "15.1.0"
PARTES = 4
SUFIJO = "ABCD"
ESTADOS_APLICADOS = ("C", "F")


def escapar(texto: str) -> str:
    """Escapa un valor como literal Kotlin entre comillas dobles."""
    salida: list[str] = []
    for caracter in texto:
        punto = ord(caracter)
        if caracter == '"':
            salida.append('\\"')
        elif caracter == "\\":
            salida.append("\\\\")
        elif caracter == "$":
            salida.append("\\$")
        elif 0x20 <= punto <= 0x7E:
            salida.append(caracter)
        elif punto <= 0xFFFF:
            salida.append(f"\\u{punto:04X}")
        else:
            desplazado = punto - 0x10000
            alto = 0xD800 + (desplazado >> 10)
            bajo = 0xDC00 + (desplazado & 0x3FF)
            salida.append(f"\\u{alto:04X}\\u{bajo:04X}")
    return "".join(salida)


def leer_entradas() -> dict[int, str]:
    """Lee las entradas C y F ordenadas por code point."""
    entradas: dict[int, str] = {}
    for numero, linea in enumerate(FUENTE.read_text(encoding="utf-8").splitlines(), start=1):
        contenido = linea.split("#", 1)[0].strip()
        if not contenido:
            continue
        campos = [campo.strip() for campo in contenido.split(";")]
        if len(campos) < 3:
            raise SystemExit(f"{FUENTE}:{numero}: línea con formato inesperado")
        estado = campos[1]
        if estado not in ESTADOS_APLICADOS:
            continue
        punto = int(campos[0], 16)
        mapeo = "".join(chr(int(valor, 16)) for valor in campos[2].split())
        if punto in entradas and entradas[punto] != mapeo:
            raise SystemExit(f"{FUENTE}:{numero}: mapeo C/F duplicado y contradictorio")
        entradas[punto] = mapeo
    if not entradas:
        raise SystemExit("no se leyó ninguna entrada C/F")
    return dict(sorted(entradas.items()))


def cabecera(sha256: str) -> str:
    return (
        "// GENERADO por tools/unicode/generar_casefold.py — NO EDITAR A MANO.\n"
        f"// Fuente: CaseFolding-{VERSION}.txt (estados C y F), "
        f"https://www.unicode.org/Public/{VERSION}/ucd/CaseFolding.txt\n"
        f"// SHA-256 de la fuente: {sha256}\n"
        "// © 2023 Unicode, Inc. Distribuido bajo la licencia Unicode; ver tools/unicode/LICENSE-UNICODE.txt\n"
        "package com.copernicotv.core.identity.casefold"
    )


def escribir_parte(indice: int, entradas: list[tuple[int, str]], sha256: str) -> pathlib.Path:
    sufijo = SUFIJO[indice]
    lineas = [cabecera(sha256), "", f"internal object TablaCaseFold{sufijo} {{", "    val entradas: Map<Int, String> =", "        mapOf("]
    for punto, mapeo in entradas:
        lineas.append(f'            0x{punto:04X} to "{escapar(mapeo)}",')
    lineas.append("        )")
    lineas.append("}")
    ruta = DESTINO / f"TablaCaseFold{sufijo}.kt"
    ruta.write_text("\n".join(lineas) + "\n", encoding="utf-8", newline="\n")
    return ruta


def main() -> int:
    if not FUENTE.is_file():
        print(f"falta la fuente oficial: {FUENTE}", file=sys.stderr)
        return 1
    crudo = FUENTE.read_bytes()
    sha256 = hashlib.sha256(crudo).hexdigest()
    if f"CaseFolding-{VERSION}.txt" not in crudo.decode("utf-8", errors="replace"):
        print(f"{FUENTE} no declara la versión {VERSION}", file=sys.stderr)
        return 1

    entradas = leer_entradas()
    DESTINO.mkdir(parents=True, exist_ok=True)

    tamano = (len(entradas) + PARTES - 1) // PARTES
    elementos = list(entradas.items())
    rutas = []
    for indice in range(PARTES):
        trozo = elementos[indice * tamano : (indice + 1) * tamano]
        if not trozo:
            continue
        rutas.append(escribir_parte(indice, trozo, sha256))

    indice_kt = (
        cabecera(sha256)
        + "\n\n"
        + "internal object TablaCaseFold {\n"
        + "    val entradas: Map<Int, String> =\n"
        + "        " + " + ".join(f"TablaCaseFold{SUFIJO[i]}.entradas" for i in range(len(rutas))) + "\n"
        + "}\n"
    )
    ruta_indice = DESTINO / "TablaCaseFold.kt"
    ruta_indice.write_text(indice_kt, encoding="utf-8", newline="\n")
    rutas.append(ruta_indice)

    print(f"fuente : {FUENTE.name}  sha256={sha256}")
    print(f"entradas C+F: {len(entradas)}  multi-carácter: {sum(1 for v in entradas.values() if len(v) > 1)}")
    for ruta in rutas:
        print(f"escrito: {ruta.relative_to(RAIZ)} ({len(ruta.read_text(encoding='utf-8').splitlines())} líneas)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
