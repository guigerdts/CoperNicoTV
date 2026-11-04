# Fixtures XMLTV F24a–F24c — S24

Registro de los fixtures contractuales XMLTV y de su relación con el contrato
vigente. Los hashes exactos están en `docs/evidence/p1/fixture-f24.sha256`.

## Correspondencia

S24 enumera los casos válido, malformado e incompleto, pero no fija el contenido
literal de cada archivo. La materialización conserva el orden de la cláusula y
los casos que ya ejercitaba `ParserXmltvTest`:

| Fixture | Caso | Contenido | Bytes |
|---|---|---|---|
| `F24a.xml` | XMLTV válido | 100 canales y 10 000 programas, fechas UTC con offsets explícitos | 1 533 410 |
| `F24b.xml` | XMLTV malformado | un programa recuperado y un `programme` truncado; error global tolerado | 259 |
| `F24c.xml` | XMLTV incompleto | un programa completo y dos descartados por datos faltantes | 383 |

F24a es el fixture de la medición P1 XMLTV. Los tres archivos viven en
`core/src/test/resources/fixtures/` y se generan con
`tools/fixtures/generar_f24_xmltv.py`.

## Verificación de identidad

El SHA-256 de F24a es
`20c0417f83b5f3534e3229b2c423ad6bdd3f54bc1c92e7776efbb655c0f74bf1`, el mismo
que producía el generador en memoria anterior. Por lo tanto, la materialización
no cambió los bytes del fixture de rendimiento ni su semántica de 100 canales y
10 000 programas.

F24b y F24c se materializaron desde los textos inline que ya usaba
`ParserXmltvTest` para los casos malformado e incompleto; no agregan requisitos
ni datos nuevos.

## Compatibilidad con DC-10 y S24

- DC-10 §1 exige un fixture de 10 000 entradas para la medición JVM; F24a cumple
  con 10 000 programas y se ejecuta en `:core:p1XmltvPerformance` bajo
  `-Xmx512m`, 10 calentamientos y 30 mediciones.
- DC-10 §6 documenta el fixture versionado de M3U `F-P1-10k.m3u`; no define el
  contenido de un fixture XMLTV. F24a–F24c cubren esa ausencia sin sustituir el
  fixture M3U ni mezclar métricas de ambos formatos.
- S24 exige XMLTV válido, malformado e incompleto; F24a, F24b y F24c cubren
  respectivamente esos casos.
- El fixture se registra con SHA-256 y las pruebas `FixtureF24Test` verifican
  integridad, conteos y comportamiento del parser.

No se encontró una contradicción contractual real. La única precisión necesaria
es que la correspondencia F24a/F24b/F24c se deduce del orden de S24 y de los
casos ya implementados, porque el contrato no asigna nombres literales a cada
caso.
