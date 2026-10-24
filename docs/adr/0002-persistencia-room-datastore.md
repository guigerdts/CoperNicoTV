# ADR-0002 — Persistencia: Room + DataStore

Status: accepted

## Contexto

El MVP completo (7 entregas) incluye múltiples Listas, catálogos de decenas de miles de canales, Historial de reproducción, Favoritos, Ocultaciones, Colecciones y una EPG que potencialmente maneja millones de Programación. Archivos JSON + DataStore no sostienen ese volumen con integridad ni proveen indexado ni consultas estructuradas.

## Decisión

- **Room** (desde el día 1, en `:app`) sostiene todas las **entidades de persistencia**: Fuente, Lista, Canal, Apariencia (pertenencia Lista↔Canal), Grupo, Favoritos, Ocultaciones, Historial, Colecciones, Programación de EPG y Asociaciones, con índices sobre las claves de búsqueda (identidad, nombre, grupo) y **integridad referencial** (foreign keys) entre relaciones persistidas.
- **DataStore** (preferencias) sostiene la configuración **global** del usuario (tema, ajustes de reproducción, valores por defecto de retención de EPG). La configuración **por Fuente** (intervalo de actualización individual, excepciones de retención) se persiste en Room junto a la Fuente, ver "Preferencias: global vs. por Fuente".
- **Paging** conecta Room con las UIs de catálogo y guía para recorrer catálogos largos sin cargarlos completos en memoria.

### Separaciones obligatorias

1. **Entidades de dominio ≠ entidades Room**: los modelos de dominio viven en `:core` (ADR-0001); las entidades Room en `:app`; el mapeo se hace con adaptadores explícitos. Un tipo Room jamás cruza la frontera del dominio.
2. **Canales sin duplicación entre Listas**: si el mismo Canal (misma Clave en el mismo ámbito de Fuente) aparece en varias Listas, la entidad Canal se persiste una sola vez, y la pertenencia se modela con una **tabla de unión** (ver "Relaciones del esquema v1").
3. **Identidad lógica ≠ claves internas de Room**: la Clave de Canal es la identidad lógica de dominio; las PK autogeneradas de Room son claves internas de persistencia. **Las entidades Room se relacionan entre sí exclusivamente mediante PK/FK internas** (integridad referencial garantizada por el motor). La **semántica de usuario** de Favoritos, Historial, Ocultaciones y Colecciones se conserva **mediante la identidad lógica del Canal** (ADR-0004) y una **reconciliación controlada** (E4): al Actualizar, la reconciliación **localiza filas por la identidad lógica** (invariante de unicidad a nivel de BD sobre la Clave de Canal por Fuente, garantizado por el índice único sobre la columna de unicidad, que es NULL en las filas de ambigüedad gestionada; esas filas se anclan a su PK interna y a su decisión persistida — ADR-0004, puntos 6 y 7) y **redirige las relaciones de forma controlada** cuando cambia la fila subyacente. Las Actualizaciones **no pierden estas relaciones** ni fusionan canales ambiguos automáticamente; nunca eliminan relaciones en bloque por un cambio de identidad sin decisión explícita del usuario.

### Relaciones del esquema v1

- `Fuente` 1—n `Lista` 1—n **`Apariencia`**. **La pertenencia de un Canal a una Lista es la tabla de unión `Apariencia` (Lista↔Canal)**, y conserva los metadatos propios de cada aparición: **el orden dentro de la Lista**, y **el Grupo asignado por esa Lista** a ese canal.
- **El Grupo es una entidad con ámbito de Lista, no una relación directa Canal↔Grupo**: el mismo nombre de grupo en dos Listas distintas **no** implica que sea el mismo Grupo, y ocultar un grupo en una Lista no afecta a la otra Lista. La relación `Apariencia` referencia el Grupo (con ámbito de su Lista); no existe FK directa Canal↔Grupo fuera de ese contexto.
- **La Ocultación de Grupo es una entidad propia que referencia el Grupo concreto mediante su FK** (hacia la fila de Grupo con ámbito de su Lista). Como el Grupo tiene ámbito de Lista, **la ocultación no afecta a grupos homónimos de otras Listas**. `Apariencia → Grupo` representa únicamente **la asignación de grupo a una aparición del Canal** (los metadatos con que la Lista contiene al canal); **no sustituye ni implementa la relación de ocultación**: son dos relaciones independientes, cada una con su FK.
- `Favorito`, `Ocultación de Canal` e `Historial` referencian `Canal` por su FK interna; su semántica de usuario se ancla a la identidad lógica (ADR-0004), que la reconciliación redirige de forma controlada. La `Ocultación de Grupo` es la entidad propia descrita arriba, con FK hacia el Grupo concreto de su Lista.
- `Colección` referencia canales por FK interna y guarda el orden definido por el usuario (no es el orden de ninguna Lista).
- El esquema v1 **declara las tablas y columnas de EPG/Asociaciones** desde E1, sin implementar la lógica de E3–E5.

### EPG: modelo

- **Identificador de canal XMLTV**: el `channel id` que el documento XMLTV declara (`<channel id="...">`), con ámbito del **import EPG del que proviene**. **No es la Clave de Canal**: la identidad lógica del Canal de la app (ADR-0004) es otra cosa y nunca se sobreponen.
- **Programa**: cada entrada de guía importada (título, horarios, descripción), referenciado al identificador de canal XMLTV de su import. Evita duplicación: un Programa se persiste una vez por import, no por canal de la app.
- **Asociación**: vínculo **configurable** entre un Canal de la app y un identificador de canal XMLTV de un import. La app resuelve la EPG de un Canal siempre vía Asociación; si no hay Asociación, el canal simplemente no muestra EPG. La Asociación la propone la heurística (tvg-id coincidente u otro criterio documentado) y confirma el usuario; puede editarse y quitarse, y no pertenece a los datos importados: es estado de la app.

### Preferencias: global vs. por Fuente

- **Global** (DataStore): tema, ajustes de reproducción, organización del Catálogo, y **valores por defecto de retención de EPG** (ventana temporal, máximo por canal, presupuesto total).
- **Por Fuente** (Room, en la tabla `Fuente` como columnas distinguibles): **intervalo de actualización individual** (configurable por Fuente, con valor que puede diferir entre fuentes) y **excepciones de retención de EPG** para esa fuente.
- **Representación de la mezcla**: las columnas por Fuente almacenan el valor configurado o un marcador explícito "usar global" (representado como columna separada con bandera de existencia, no como valor mágico); la resolución en ejecución es: valor explícito de la Fuente si existe, si no el global de DataStore. Así el cambio del valor global no corrompe valores por fuente configurados, y se ve siempre cuáles fuentes tienen excepciones.

### Alcance E1

Se define en E1 una **base de datos** que soporta las entregas posteriores (tablas, columnas, índices, PRAGMA foreign_keys activado), pero **no se implementa anticipadamente la lógica funcional de E3–E5** (colecciones editables, reconciliación, guía). El criterio es: todo dato que E3–E5 vaya a necesitar ya tiene lugar en el esquema v1; ninguna de sus reglas de negocio se anticipa.

## Consecuencias

- Los **tests de DAO y de migración se ejecutan con Robolectric en JVM** cuando corresponda, de modo que corren localmente (si el entorno lo permite, ADR-0005) y en el job de CI correspondiente. No se afirma que Robolectric esté funcionando hasta verificarlo.
- Migraciones: **no se declara que "las migraciones desde v1 están cubiertas" mientras el esquema v1 no exista publicado**; a partir de v1, cada versión posterior del esquema lleva su prueba de migración desde la versión anterior, con datos representativos, antes de mezclarse a `main`.
- Los tests de rendimiento usan catálogos sintéticos reproducibles (mínimo 10.000 entradas); el rendimiento se mide sobre búsquedas y paginación con esos índices.
- La tabla `Apariencia` agrega una consulta por catálogo visible, pero elimina la duplicación de canales entre listas y conserva el contexto por lista: es el trade-off elegido.

## Opciones consideradas

- **JSON + DataStore hasta que se necesite más**: descartado — el volumen real rompe consistencia/rendimiento, y mover de JSON a Room después implica migración de datos desde un formato sin esquema.
- **Room desde el día cero con Hilt ya integrado**: adoptado — evita la migración posterior y sostiene las 6 entregas subsiguientes sin reescribir la persistencia.
- **Relación directa Canal↔Grupo** (sin `Apariencia`): descartado — pierde el contexto de Lista, fuerza scopes de grupo globales y hace que ocultar/renombrar un grupo sea ambiguo entre listas.
- **Pertenencia embebida en la entidad Canal** (columna lista_id en Canal): descartado — duplica canales que aparecen en varias Listas, contradiciendo la separación 2.
