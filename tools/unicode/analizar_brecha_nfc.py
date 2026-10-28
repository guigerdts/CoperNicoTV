#!/usr/bin/env python3
"""Mide la brecha entre el NFC de la plataforma (Unicode 13.0) y Unicode 15.1.

Evidencia de la decisión registrada en E1: el case folding completo se puede
implementar por tabla, pero la NFC que usaba `java.text.Normalizer` en JDK 17
implementaba Unicode 13.0 y no era equivalente a 15.1.

El script lee las fuentes oficiales vendorizadas en `tools/unicode/`, verifica
su SHA-256 y calcula:

- code points asignados por Unicode 14.0 o posterior;
- cuántos de ellos tienen `ccc != 0`;
- cuántos tienen descomposición canónica;
- la intersección, que es el conjunto donde 13.0 y 15.1 pueden discrepar.

`DerivedAge.txt` declara edades de dos componentes (`14.0`, `15.1`), por lo que
la comparación correcta es contra `(14, 0)`. La versión anterior de este script
comparaba contra `(14, 0, 0)`, excluía Unicode 14.0 y medía «≥ 15.0»: 5116
asignados y 10 marcas en lugar de 5954 y 50. La cifra histórica queda
documentada como discrepancia, no como resultado vigente.

Uso:
    python3 tools/unicode/analizar_brecha_nfc.py [directorio]

Sin argumento usa `tools/unicode/`. Si una fuente falta en ese directorio, la
descarga a un directorio temporal.
"""

from __future__ import annotations

import hashlib
import pathlib
import sys
import tempfile
import urllib.request
from dataclasses import dataclass

VERSION = "15.1.0"
BASE = f"https://www.unicode.org/Public/{VERSION}/ucd/"
ARCHIVOS = ("UnicodeData.txt", "DerivedAge.txt")
ORIGEN = pathlib.Path(__file__).resolve().parent
EDAD_MINIMA = (14, 0)
CIFRA_HISTORICA = (5116, 10)

SHA256_ESPERADOS = {
    "UnicodeData.txt": "2fc713e6a31a87c4850a37fe2caffa4218180fadb5de86b43a143ddb4581fb86",
    "DerivedAge.txt": "04e16379344bdb9973cdb6f6bf0a5dd66f7cd41b014cd9f79d848768ae757256",
}


@dataclass(frozen=True)
class Medicion:
    """Resultado de la medición de la brecha."""

    asignados: int
    marcas: tuple[tuple[int, int], ...]
    descomposiciones: tuple[int, ...]
    ccc_totales: int
    descomposiciones_totales: int


def tupla_version(texto: str) -> tuple[int, ...]:
    """Convierte `14.0` o `15.1` en una tupla de enteros."""
    return tuple(int(parte) for parte in texto.split("."))


def cumple_edad(
    edad: tuple[int, ...],
    minima: tuple[int, ...] = EDAD_MINIMA,
) -> bool:
    """Indica si [edad] es igual o posterior a [minima].

    Las tuplas de `DerivedAge.txt` tienen dos componentes; compararlas con una
    tupla de tres hace que `(14, 0) >= (14, 0, 0)` sea falso y excluye por error
    todo Unicode 14.0.
    """
    return edad >= minima


def descargar(destino: pathlib.Path, nombre: str) -> pathlib.Path:
    """Devuelve la fuente vendorizada o la descarga si falta."""
    ruta = destino / nombre
    if not ruta.is_file():
        urllib.request.urlretrieve(BASE + nombre, ruta)  # noqa: S310 - URL fija y versionada
    return ruta


def verificar(nombre: str, ruta: pathlib.Path) -> str:
    """Verifica el SHA-256 de la fuente y devuelve su huella."""
    huella = hashlib.sha256(ruta.read_bytes()).hexdigest()
    esperada = SHA256_ESPERADOS[nombre]
    if huella != esperada:
        raise SystemExit(f"{nombre}: SHA-256 {huella} no coincide con el esperado {esperada}")
    return huella


def leer_edades(texto: str) -> dict[int, tuple[int, ...]]:
    """Lee `DerivedAge.txt` y expande sus rangos."""
    edad: dict[int, tuple[int, ...]] = {}
    for linea in texto.splitlines():
        contenido = linea.split("#", 1)[0].strip()
        if not contenido:
            continue
        rango, version = [campo.strip() for campo in contenido.split(";")]
        primero, _, ultimo = rango.partition("..")
        inicio = int(primero, 16)
        fin = int(ultimo, 16) if ultimo else inicio
        for punto in range(inicio, fin + 1):
            edad[punto] = tupla_version(version)
    return edad


def leer_propiedades(texto: str) -> tuple[dict[int, int], dict[int, str]]:
    """Extrae CCC distinto de 0 y descomposiciones canónicas de UnicodeData."""
    ccc: dict[int, int] = {}
    descomposiciones: dict[int, str] = {}
    for linea in texto.splitlines():
        campos = linea.split(";")
        if len(campos) < 6:
            continue
        punto = int(campos[0], 16)
        if int(campos[3]):
            ccc[punto] = int(campos[3])
        if campos[5] and not campos[5].startswith("<"):
            descomposiciones[punto] = campos[5]
    return ccc, descomposiciones


def medir(directorio: pathlib.Path) -> tuple[Medicion, dict[str, str]]:
    """Mide la brecha sobre las fuentes de [directorio]."""
    rutas = {nombre: descargar(directorio, nombre) for nombre in ARCHIVOS}
    huellas = {nombre: verificar(nombre, ruta) for nombre, ruta in rutas.items()}
    edad = leer_edades(rutas["DerivedAge.txt"].read_text(encoding="utf-8"))
    ccc, descomposiciones = leer_propiedades(rutas["UnicodeData.txt"].read_text(encoding="utf-8"))
    nuevos = {punto for punto, version in edad.items() if cumple_edad(version)}
    marcas = tuple((punto, ccc[punto]) for punto in sorted(nuevos) if punto in ccc)
    descomposiciones_nuevas = tuple(sorted(punto for punto in nuevos if punto in descomposiciones))
    medicion = Medicion(
        asignados=len(nuevos),
        marcas=marcas,
        descomposiciones=descomposiciones_nuevas,
        ccc_totales=len(ccc),
        descomposiciones_totales=len(descomposiciones),
    )
    return medicion, huellas


def main(argv: list[str]) -> int:
    directorio = pathlib.Path(argv[1]) if len(argv) > 1 else ORIGEN
    if len(argv) > 2:
        print("uso: analizar_brecha_nfc.py [directorio]", file=sys.stderr)
        return 2
    if directorio is ORIGEN and not all((directorio / nombre).is_file() for nombre in ARCHIVOS):
        with tempfile.TemporaryDirectory(prefix="brecha-nfc-") as temporal:
            return main([argv[0], temporal])

    medicion, huellas = medir(directorio)
    for nombre, huella in huellas.items():
        print(f"{nombre}: sha256={huella}")
    print(f"code points con edad >= 14.0: {medicion.asignados}")
    print(f"ccc != 0 totales: {medicion.ccc_totales}")
    print(f"ccc != 0 con edad >= 14.0: {len(medicion.marcas)}")
    for punto, clase in medicion.marcas:
        print(f"  U+{punto:04X} ccc={clase}")
    print(f"descomposiciones canónicas totales: {medicion.descomposiciones_totales}")
    print(f"descomposiciones canónicas con edad >= 14.0: {len(medicion.descomposiciones)}")
    print(
        "=> las marcas nuevas alteran el orden canónico (NFD) y pueden cambiar "
        "el resultado de NFC donde intercalan con marcas componibles."
    )
    print(
        f"   La cifra histórica {CIFRA_HISTORICA[0]}/{CIFRA_HISTORICA[1]} se midió "
        "con (14, 0, 0) y excluía Unicode 14.0; no es el resultado vigente."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
