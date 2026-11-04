#!/usr/bin/env python3
"""Regresión del analizador de la brecha NFC (H-02).

El defecto corregido era comparar las edades de `DerivedAge.txt`, que tienen dos
componentes (`14.0`), contra `(14, 0, 0)`. Esa comparación excluía todo Unicode
14.0 y medía «≥ 15.0»: 5116 asignados y 10 marcas en lugar de 5954 y 50.

Uso:
    python3 -m unittest discover -s tools/unicode -p "test_*.py" -v
"""

from __future__ import annotations

import pathlib
import sys
import unittest

DIRECTORIO = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(DIRECTORIO))

import analizar_brecha_nfc as analizador  # noqa: E402


class AnalizadorBrechaTest(unittest.TestCase):
    def test_derived_age_usa_dos_componentes(self) -> None:
        self.assertEqual((14, 0), analizador.tupla_version("14.0"))
        self.assertEqual((15, 1), analizador.tupla_version("15.1"))

    def test_la_edad_minima_incluye_unicode_14_0(self) -> None:
        self.assertTrue(analizador.cumple_edad((14, 0)))
        self.assertTrue(analizador.cumple_edad((15, 1)))
        self.assertFalse(analizador.cumple_edad((13, 0)))

    def test_la_comparacion_historica_excluia_unicode_14_0(self) -> None:
        # Documenta el defecto: (14, 0) no es >= (14, 0, 0) en Python.
        self.assertFalse((14, 0) >= (14, 0, 0))
        self.assertTrue((14, 0) >= (14, 0))

    def test_expande_rangos_de_edad(self) -> None:
        edades = analizador.leer_edades("0041..0043 ; 14.0\n0044 ; 15.1\n")
        self.assertEqual((14, 0), edades[0x41])
        self.assertEqual((14, 0), edades[0x43])
        self.assertEqual((15, 1), edades[0x44])

    def test_medicion_vigente_sobre_las_fuentes_vendorizadas(self) -> None:
        medicion, huellas = analizador.medir(DIRECTORIO)
        self.assertEqual(analizador.SHA256_ESPERADOS["UnicodeData.txt"], huellas["UnicodeData.txt"])
        self.assertEqual(analizador.SHA256_ESPERADOS["DerivedAge.txt"], huellas["DerivedAge.txt"])
        self.assertEqual(5954, medicion.asignados)
        self.assertEqual(50, len(medicion.marcas))
        self.assertNotEqual(analizador.CIFRA_HISTORICA[0], medicion.asignados)
        self.assertNotEqual(analizador.CIFRA_HISTORICA[1], len(medicion.marcas))
        self.assertEqual(0, len(medicion.descomposiciones))
        self.assertEqual(922, medicion.ccc_totales)
        self.assertEqual(2061, medicion.descomposiciones_totales)

    def test_incluye_las_marcas_de_14_0_y_15_0(self) -> None:
        medicion, _ = analizador.medir(DIRECTORIO)
        clases = dict(medicion.marcas)
        # 0898 es de Unicode 14.0: la comparación defectuosa la omitía.
        self.assertEqual(230, clases[0x0898])
        # 10EFD, 11F41, 1E08F y 1E4EC son de Unicode 15.0.
        self.assertEqual(220, clases[0x10EFD])
        self.assertEqual(9, clases[0x11F41])
        self.assertEqual(230, clases[0x1E08F])
        self.assertEqual(232, clases[0x1E4EC])


if __name__ == "__main__":
    unittest.main()
