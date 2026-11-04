#!/usr/bin/env python3
"""Genera las tablas de normalización NFC de Unicode 15.1.0.

Fuentes oficiales vendorizadas en `tools/unicode/`:

- `UnicodeData.txt`: CCC y descomposición canónica (campo 5 sin etiqueta).
- `DerivedNormalizationProps.txt`: propiedad `Full_Composition_Exclusion`.
- `CompositionExclusions.txt`: exclusiones de composición (verificación cruzada).

El script valida el SHA-256 de cada fuente antes de leerla y falla si no
coincide. La salida es determinista: los mismos datos producen los mismos bytes.

Uso:
    python3 tools/unicode/generar_nfc.py

Reescribe `core/src/main/kotlin/com/copernicotv/core/identity/nfc/`.
"""

from __future__ import annotations

import hashlib
import pathlib
import sys

RAIZ = pathlib.Path(__file__).resolve().parents[2]
ORIGEN = RAIZ / "tools" / "unicode"
DESTINO = RAIZ / "core" / "src" / "main" / "kotlin" / "com" / "copernicotv" / "core" / "identity" / "nfc"
VERSION = "15.1.0"
BASE_URL = f"https://www.unicode.org/Public/{VERSION}/ucd/"

SHA256_ESPERADOS = {
    "UnicodeData.txt": "2fc713e6a31a87c4850a37fe2caffa4218180fadb5de86b43a143ddb4581fb86",
    "DerivedNormalizationProps.txt": "8875dccee2bc1a7c1fe568a3b502a9e78c9e0495afd96b6568b4294d0ed1f7e1",
    "CompositionExclusions.txt": "59d2d9e3dfdf0a999cf9dae11d594f053631222679a2f5710315ea07f7fe82af",
}

# Hangul se compone y descompone de forma algorítmica (UAX #15, §3.12).
S_BASE = 0xAC00
S_COUNT = 11172
L_BASE = 0x1100
L_COUNT = 19
V_BASE = 0x1161
V_COUNT = 21
T_BASE = 0x11A7
T_COUNT = 28

CAMPO = 6  # dígitos hexadecimales por valor; 24 bits cubren todo code point
ANCHO_CCC = 2 * CAMPO  # code point + CCC
ANCHO_DESCOMPOSICION = 3 * CAMPO  # code point + dos componentes
ANCHO_COMPOSICION = 3 * CAMPO  # dos componentes + compuesto
COLUMNA = 108  # caracteres hexadecimales por línea de la cadena empaquetada


def leer(nombre: str) -> tuple[str, str]:
    """Lee una fuente vendorizada y verifica su SHA-256."""
    ruta = ORIGEN / nombre
    if not ruta.is_file():
        raise SystemExit(f"falta la fuente oficial: {ruta}")
    crudo = ruta.read_bytes()
    huella = hashlib.sha256(crudo).hexdigest()
    if huella != SHA256_ESPERADOS[nombre]:
        raise SystemExit(
            f"{nombre}: SHA-256 {huella} no coincide con el esperado {SHA256_ESPERADOS[nombre]}"
        )
    return crudo.decode("utf-8"), huella


def verificar_version(nombre: str, texto: str) -> None:
    """Comprueba que la fuente declara la versión normativa."""
    if f"{pathlib.Path(nombre).stem}-{VERSION}.txt" not in texto:
        raise SystemExit(f"{nombre} no declara la versión {VERSION}")


def ccc_y_descomposiciones(texto: str) -> tuple[dict[int, int], dict[int, tuple[int, ...]]]:
    """Extrae CCC distinto de 0 y descomposiciones canónicas directas."""
    ccc: dict[int, int] = {}
    descomposiciones: dict[int, tuple[int, ...]] = {}
    for numero, linea in enumerate(texto.splitlines(), start=1):
        campos = linea.split(";")
        if len(campos) < 6:
            continue
        punto = int(campos[0], 16)
        clase = int(campos[3])
        if clase:
            ccc[punto] = clase
        mapeo = campos[5].strip()
        if mapeo and not mapeo.startswith("<"):
            valores = tuple(int(valor, 16) for valor in mapeo.split())
            if len(valores) > 2:
                raise SystemExit(f"UnicodeData.txt:{numero}: descomposición canónica de longitud {len(valores)}")
            descomposiciones[punto] = valores
    if not ccc or not descomposiciones:
        raise SystemExit("no se leyeron CCC o descomposiciones")
    return ccc, descomposiciones


def rango(campo: str) -> range:
    """Expande un campo `inicio` o `inicio..fin` de un archivo de propiedades."""
    primero, _, ultimo = campo.partition("..")
    inicio = int(primero, 16)
    fin = int(ultimo, 16) if ultimo else inicio
    return range(inicio, fin + 1)


def exclusiones(texto: str) -> set[int]:
    """Lee `Full_Composition_Exclusion` de DerivedNormalizationProps.txt."""
    puntos: set[int] = set()
    for linea in texto.splitlines():
        contenido = linea.split("#", 1)[0].strip()
        if not contenido:
            continue
        campos = [campo.strip() for campo in contenido.split(";")]
        if len(campos) < 2 or campos[1] != "Full_Composition_Exclusion":
            continue
        puntos.update(rango(campos[0]))
    if not puntos:
        raise SystemExit("no se leyó Full_Composition_Exclusion")
    return puntos


def exclusiones_declaradas(texto: str) -> set[int]:
    """Lee CompositionExclusions.txt para verificar la coherencia de las fuentes."""
    puntos: set[int] = set()
    for linea in texto.splitlines():
        contenido = linea.split("#", 1)[0].strip()
        if contenido:
            puntos.update(rango(contenido))
    return puntos


def composiciones(
    ccc: dict[int, int],
    descomposiciones: dict[int, tuple[int, ...]],
    excluidos: set[int],
) -> dict[tuple[int, int], int]:
    """Pares (primero, segundo) que componen, sin exclusiones completas."""
    pares: dict[tuple[int, int], int] = {}
    for punto, mapeo in sorted(descomposiciones.items()):
        if len(mapeo) != 2:
            continue
        primero, segundo = mapeo
        if ccc.get(primero, 0) != 0 or punto in excluidos:
            continue
        clave = (primero, segundo)
        if clave in pares:
            raise SystemExit(f"composición ambigua para {clave}")
        pares[clave] = punto
    return dict(sorted(pares.items()))


def empaquetar(filas: list[tuple[int, ...]], anchos: tuple[int, ...]) -> list[str]:
    """Empaqueta filas de enteros en hexadecimal de ancho fijo y las parte en líneas."""
    texto = "".join(
        "".join(f"{valor:0{ancho}X}" for valor, ancho in zip(fila, anchos)) for fila in filas
    )
    return [texto[indice : indice + COLUMNA] for indice in range(0, len(texto), COLUMNA)]


def literal(cadenas: list[str]) -> str:
    """Emite una concatenación de literales Kotlin, una por línea."""
    if not cadenas:
        return '        ""'
    partes = [f'        "{cadenas[0]}"']
    partes.extend(f'            "{cadena}"' for cadena in cadenas[1:])
    return " +\n".join(partes)


def cabecera(huellas: dict[str, str]) -> str:
    return (
        "// GENERADO por tools/unicode/generar_nfc.py — NO EDITAR A MANO.\n"
        f"// Fuentes: UnicodeData-{VERSION}.txt, DerivedNormalizationProps-{VERSION}.txt\n"
        f"// y CompositionExclusions-{VERSION}.txt, {BASE_URL}\n"
        f"// SHA-256 de UnicodeData.txt: {huellas['UnicodeData.txt']}\n"
        f"// SHA-256 de DerivedNormalizationProps.txt: {huellas['DerivedNormalizationProps.txt']}\n"
        f"// SHA-256 de CompositionExclusions.txt: {huellas['CompositionExclusions.txt']}\n"
        "// © 2023 Unicode, Inc. Distribuido bajo la licencia Unicode; ver tools/unicode/LICENSE-UNICODE.txt\n"
        "package com.copernicotv.core.identity.nfc"
    )


def escribir(ruta: pathlib.Path, contenido: str) -> pathlib.Path:
    ruta.write_text(contenido, encoding="utf-8", newline="\n")
    return ruta


def generar_tabla_nfc(
    huellas: dict[str, str],
    entradas_ccc: int,
    entradas_descomposicion: int,
    entradas_composicion: int,
    total_exclusiones: int,
) -> pathlib.Path:
    contenido = (
        cabecera(huellas)
        + "\n\n"
        + "/** Metadatos de las tablas NFC de Unicode 15.1.0. */\n"
        + "internal object TablaNfc {\n"
        + f'    const val VERSION: String = "{VERSION}"\n'
        + f'    const val SHA256_UNICODE_DATA: String = "{huellas["UnicodeData.txt"]}"\n'
        + "    const val SHA256_DERIVED_NORMALIZATION_PROPS: String =\n"
        + f'        "{huellas["DerivedNormalizationProps.txt"]}"\n'
        + "    const val SHA256_COMPOSITION_EXCLUSIONS: String =\n"
        + f'        "{huellas["CompositionExclusions.txt"]}"\n'
        + f"    const val ENTRADAS_CCC: Int = {entradas_ccc}\n"
        + f"    const val ENTRADAS_DESCOMPOSICION: Int = {entradas_descomposicion}\n"
        + f"    const val ENTRADAS_COMPOSICION: Int = {entradas_composicion}\n"
        + f"    const val EXCLUSIONES_COMPOSICION: Int = {total_exclusiones}\n"
        + "}\n"
    )
    return escribir(DESTINO / "TablaNfc.kt", contenido)


def generar_tabla_ccc(huellas: dict[str, str], ccc: dict[int, int]) -> pathlib.Path:
    filas = [(punto, clase) for punto, clase in sorted(ccc.items())]
    contenido = (
        cabecera(huellas)
        + "\n\n"
        + "/**\n"
        + " * `Canonical_Combining_Class` (CCC) de Unicode 15.1.0.\n"
        + " *\n"
        + " * `DATOS` empaqueta, por code point ascendente, 6 dígitos hexadecimales de\n"
        + " * code point seguidos de 6 de CCC. [pares] es la decodificación que consumen\n"
        + " * las búsquedas binarias; solo incluye code points con CCC distinto de 0.\n"
        + " */\n"
        + "internal object TablaCcc {\n"
        + "    /** Cantidad de code points con CCC distinto de 0. */\n"
        + f"    const val ENTRADAS: Int = {len(ccc)}\n\n"
        + f"    private const val ANCHO: Int = {ANCHO_CCC}\n\n"
        + f"    private const val CAMPO: Int = {CAMPO}\n\n"
        + "    private const val DATOS: String =\n"
        + literal(empaquetar(filas, (CAMPO, CAMPO)))
        + "\n\n"
        + "    /** Pares (code point, CCC) ordenados por code point. */\n"
        + "    val pares: IntArray by lazy { decodificarTabla(DATOS, ANCHO, CAMPO) }\n"
        + "}\n"
    )
    return escribir(DESTINO / "TablaCcc.kt", contenido)


def generar_tabla_descomposicion(
    huellas: dict[str, str],
    descomposiciones: dict[int, tuple[int, ...]],
) -> pathlib.Path:
    filas = []
    for punto, mapeo in sorted(descomposiciones.items()):
        primero = mapeo[0]
        segundo = mapeo[1] if len(mapeo) == 2 else 0
        filas.append((punto, primero, segundo))
    contenido = (
        cabecera(huellas)
        + "\n\n"
        + "/**\n"
        + " * Descomposición canónica directa de Unicode 15.1.0.\n"
        + " *\n"
        + " * `DATOS` empaqueta, por code point ascendente, tres valores de 6 dígitos\n"
        + " * hexadecimales: code point, primer componente y segundo componente. El\n"
        + " * segundo vale 0 cuando la descomposición es singleton. Hangul no aparece:\n"
        + " * se descompone de forma algorítmica. [entradas] es la decodificación que\n"
        + " * consumen las búsquedas binarias.\n"
        + " */\n"
        + "internal object TablaDescomposicion {\n"
        + "    /** Cantidad de code points con descomposición canónica directa. */\n"
        + f"    const val ENTRADAS: Int = {len(descomposiciones)}\n\n"
        + f"    private const val ANCHO: Int = {ANCHO_DESCOMPOSICION}\n\n"
        + f"    private const val CAMPO: Int = {CAMPO}\n\n"
        + "    private const val DATOS: String =\n"
        + literal(empaquetar(filas, (CAMPO, CAMPO, CAMPO)))
        + "\n\n"
        + "    /** Tripletas (code point, primer componente, segundo componente). */\n"
        + "    val entradas: IntArray by lazy { decodificarTabla(DATOS, ANCHO, CAMPO) }\n"
        + "}\n"
    )
    return escribir(DESTINO / "TablaDescomposicion.kt", contenido)


def generar_tabla_composicion(
    huellas: dict[str, str],
    pares: dict[tuple[int, int], int],
) -> pathlib.Path:
    filas = [(primero, segundo, compuesto) for (primero, segundo), compuesto in sorted(pares.items())]
    contenido = (
        cabecera(huellas)
        + "\n\n"
        + "/**\n"
        + " * Pares de composición canónica de Unicode 15.1.0.\n"
        + " *\n"
        + " * `DATOS` empaqueta, ordenados por (primer componente, segundo componente),\n"
        + " * tres valores de 6 dígitos hexadecimales: primero, segundo y compuesto. Ya\n"
        + " * excluye `Full_Composition_Exclusion` y las descomposiciones no-starter.\n"
        + " * Hangul no aparece: se compone de forma algorítmica. [entradas] es la\n"
        + " * decodificación que consumen las búsquedas binarias.\n"
        + " */\n"
        + "internal object TablaComposicion {\n"
        + "    /** Cantidad de pares que componen. */\n"
        + f"    const val ENTRADAS: Int = {len(pares)}\n\n"
        + f"    private const val ANCHO: Int = {ANCHO_COMPOSICION}\n\n"
        + f"    private const val CAMPO: Int = {CAMPO}\n\n"
        + "    private const val DATOS: String =\n"
        + literal(empaquetar(filas, (CAMPO, CAMPO, CAMPO)))
        + "\n\n"
        + "    /** Tripletas (primer componente, segundo componente, compuesto). */\n"
        + "    val entradas: IntArray by lazy { decodificarTabla(DATOS, ANCHO, CAMPO) }\n"
        + "}\n"
    )
    return escribir(DESTINO / "TablaComposicion.kt", contenido)


def verificar_metricas(
    ccc: dict[int, int],
    descomposiciones: dict[int, tuple[int, ...]],
    excluidos: set[int],
    pares: dict[tuple[int, int], int],
) -> None:
    """Falla si los datos no tienen la forma que el algoritmo asume."""
    if any(0xAC00 <= punto <= 0xD7A3 for punto in descomposiciones):
        raise SystemExit("Hangul no debe tener descomposición explícita")
    if any(len(mapeo) > 2 for mapeo in descomposiciones.values()):
        raise SystemExit("hay descomposiciones de longitud mayor que 2")
    if any(not (0 < punto <= 0x10FFFF) for punto in ccc):
        raise SystemExit("CCC fuera del rango Unicode")
    if len(pares) != len(set(pares)):
        raise SystemExit("pares de composición duplicados")


def main() -> int:
    textos: dict[str, str] = {}
    huellas: dict[str, str] = {}
    for nombre in SHA256_ESPERADOS:
        textos[nombre], huellas[nombre] = leer(nombre)
    verificar_version("DerivedNormalizationProps.txt", textos["DerivedNormalizationProps.txt"])
    verificar_version("CompositionExclusions.txt", textos["CompositionExclusions.txt"])

    ccc, descomposiciones = ccc_y_descomposiciones(textos["UnicodeData.txt"])
    excluidos = exclusiones(textos["DerivedNormalizationProps.txt"])
    declaradas = exclusiones_declaradas(textos["CompositionExclusions.txt"])
    if not declaradas <= excluidos:
        raise SystemExit("CompositionExclusions.txt tiene entradas fuera de Full_Composition_Exclusion")
    pares = composiciones(ccc, descomposiciones, excluidos)
    verificar_metricas(ccc, descomposiciones, excluidos, pares)

    DESTINO.mkdir(parents=True, exist_ok=True)
    rutas = [
        generar_tabla_nfc(huellas, len(ccc), len(descomposiciones), len(pares), len(excluidos)),
        generar_tabla_ccc(huellas, ccc),
        generar_tabla_descomposicion(huellas, descomposiciones),
        generar_tabla_composicion(huellas, pares),
    ]

    print(f"UnicodeData.txt: sha256={huellas['UnicodeData.txt']}")
    print(f"DerivedNormalizationProps.txt: sha256={huellas['DerivedNormalizationProps.txt']}")
    print(f"CompositionExclusions.txt: sha256={huellas['CompositionExclusions.txt']}")
    print(
        f"ccc={len(ccc)} descomposiciones={len(descomposiciones)} "
        f"composiciones={len(pares)} exclusiones={len(excluidos)}"
    )
    for ruta in rutas:
        print(f"escrito: {ruta.relative_to(RAIZ)} ({len(ruta.read_text(encoding='utf-8').splitlines())} líneas)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
