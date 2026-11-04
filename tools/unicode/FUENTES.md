# Fuentes Unicode vendorizadas

Datos oficiales del Unicode Character Database (UCD) **15.1.0**, incorporados al
repositorio para que la generación de tablas y las pruebas de conformidad sean
herméticas: no dependen de la red ni de la versión Unicode del JDK o del
dispositivo.

- Versión normativa del proyecto: `15.1.0` (`VERSION_UNICODE_NORMATIVA`).
- Origen: <https://www.unicode.org/Public/15.1.0/ucd/>.
- Licencia: Unicode License v3. El texto completo está en
  [`LICENSE-UNICODE.txt`](LICENSE-UNICODE.txt); cada archivo conserva su cabecera
  de copyright.
- Integridad: los SHA-256 de abajo son los que validan
  [`generar_nfc.py`](generar_nfc.py), [`generar_casefold.py`](generar_casefold.py)
  y [`analizar_brecha_nfc.py`](analizar_brecha_nfc.py). Si un archivo no coincide,
  el script falla y no genera nada.

| Archivo | Bytes | SHA-256 | Propósito |
| --- | ---: | --- | --- |
| `UnicodeData.txt` | 1 914 200 | `2fc713e6a31a87c4850a37fe2caffa4218180fadb5de86b43a143ddb4581fb86` | CCC (campo 3) y descomposición canónica (campo 5) para `generar_nfc.py`; también lo usa el analizador de la brecha. |
| `DerivedNormalizationProps.txt` | 1 355 530 | `8875dccee2bc1a7c1fe568a3b502a9e78c9e0495afd96b6568b4294d0ed1f7e1` | `Full_Composition_Exclusion`, que decide qué pares no recomponen. |
| `CompositionExclusions.txt` | 8 888 | `59d2d9e3dfdf0a999cf9dae11d594f053631222679a2f5710315ea07f7fe82af` | Verificación cruzada de las exclusiones de composición. |
| `DerivedAge.txt` | 131 154 | `04e16379344bdb9973cdb6f6bf0a5dd66f7cd41b014cd9f79d848768ae757256` | Edad de cada code point; mide la brecha 13.0/15.1. |
| `NormalizationTest.txt` | 2 625 136 | `871238e37e3be0696ec2bd0891119a041b052da1a84485eda05a5438724b223e` | Suite oficial de conformidad; la consumen las pruebas de `:core`. |
| `CaseFolding.txt` | 84 870 | `4e55acfdc32825a22e87670e9056a3bf94ad7c5400065778e9e10f8314372bcf` | Case folding completo (estados C y F) para `generar_casefold.py`. |
| `LICENSE-UNICODE.txt` | 1 995 | `e7a93b009565cfce55919a381437ac4db883e9da2126fa28b91d12732bc53d96` | Licencia Unicode v3 aplicable a todos los archivos anteriores. |

## Cómo se regeneran las tablas

```sh
python3 tools/unicode/generar_nfc.py
python3 tools/unicode/generar_casefold.py
```

Ambos scripts son deterministas: los mismos datos de entrada producen los mismos
bytes. Escriben en `core/src/main/kotlin/com/copernicotv/core/identity/nfc/` y
`.../casefold/` respectivamente; esos archivos llevan la cabecera
«GENERADO … NO EDITAR A MANO».

## Cómo se verifica la conformidad

`Nfc151ConformidadTest` lee `NormalizationTest.txt`, verifica su SHA-256 y
comprueba las invariantes normativas de UAX #15 sobre las 19 074 filas de la
suite. `CaseFolderCompletoTest` hace lo mismo con `CaseFolding.txt`.

## Cómo se mide la brecha

```sh
python3 tools/unicode/analizar_brecha_nfc.py
python3 -m unittest discover -s tools/unicode -p "test_*.py" -v
```

El analizador compara `DerivedAge.txt` contra `(14, 0)`; comparar contra
`(14, 0, 0)` excluía Unicode 14.0 y producía la cifra histórica errónea
5116 asignados / 10 marcas, en lugar de 5954 / 50. La discrepancia queda
documentada en `odd/tasks/mvp-copernicotv.md` §10.7.
