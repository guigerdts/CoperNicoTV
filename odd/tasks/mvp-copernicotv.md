# MVP CoperNicoTV — documento de seguimiento (ODD)

Objetivo: implementar el MVP completo descrito en `README.md`, sin recortes de alcance. Las etapas E1–E7 organizan el orden de implementación; no reducen el alcance final.

Estado: **U0 no aprobada**; se registran **D1(a), D2(a), D3, D4 y D5 (base) aprobadas** (ver L13–L16), **P1 y P2 aprobadas como políticas** (ver L17–L18), **E1-C aprobada (opción B)** (ver L19) y **S14 (alcance y reglas) aprobada** (ver L20–L21), con los detalles de estabilidad/reconfirmación de D1(a), los detalles de D3 (preferencias y representación), los detalles de D4 (intervalos, restricciones, reinicio y backoff), los detalles de D5 (inventario, gestión de claves, backup/restauración y recuperación), los valores numéricos de P1 (límites absolutos, criterio de regresión y procedimiento p95), los umbrales numéricos y el procedimiento de aceptación visual de P2, la ejecución pendiente de E1-C, la implementación/validación pendiente de S14 (con las reglas de comparación, Canales sin nombre y Canales sin grupo ya aprobadas) y la implementación de D2(a), D3, D4 y D5 no verificada. El prerrequisito documental se cerró en L3–L12; **E1 no iniciado**. Esta decimoctava corrección documental no aprueba U0 ni inicia E1 ni U1. U1 y posteriores no autorizadas. El repositorio sigue siendo solo documentación; no hay código, build, dependencias, CI ni commits.

Rama prevista: `feat/e1-bootstrap` (a crear en U1, previa autorización). Estrategia de entrega provisional: `ask-on-risk`. Runner previsto: `./gradlew test` (JVM); no verificado todavía.

Regla de evidencia: nada se declara verificado sin comando ejecutado, salida registrada, fecha y referencia de CI cuando aplique. Compilar no valida comportamiento. Una prueba pendiente no es una prueba verde ni una función validada.

## 1. Alcance, convenciones y estados de evidencia

### 1.1 Alcance de las 57 especificaciones

Las 57 especificaciones S1–S57 cubren las **57 funcionalidades previstas** de `README.md`, «Funcionalidades previstas», puntos 1–8. No cubren las 6 viñetas de «Objetivos principales» ni las 12 de «Stack tecnológico»: esas restricciones se trazan en la matriz complementaria K1–K15. El README tiene 75 viñetas en total; 57 son funcionales y 18 son objetivos/stack. Ninguna se retira ni se aplaza.

### 1.2 Niveles de prueba

Cada fila de la matriz funcional `S#` declara uno o más niveles. Las filas operativas `K14` y `K15` usan `—` porque registran evidencia operativa, no una prueba de producto. Los niveles son:

- `JVM`: prueba unitaria Kotlin pura, sin Android.
- `Robolectric`: prueba JVM con Android framework y Room/SQLite ejecutándose bajo Robolectric.
- `I-M`: prueba instrumentada en emulador o dispositivo móvil.
- `I-TV`: prueba instrumentada en emulador o dispositivo Android TV.
- `Física`: verificación en dispositivo físico, con procedimiento, resultado esperado y estado.

Un mismo requisito puede exigir más de un nivel. Una prueba `Física` pendiente se registra como pendiente por bloqueo externo, nunca como verde.

`JVM` no cubre `StrictMode`, UI Android ni consultas Room contra una base real. `StrictMode` exige ejecución Android (`I-M` o `I-TV`); las consultas Room se ejecutan en `Robolectric` (DAO/migración) o en `I-M` cuando el criterio exige flujo end-to-end. Un run de CI es evidencia de ejecución, no un nivel de prueba: no se usa para sustituir el nivel que realmente ejecuta el comportamiento.

### 1.3 Estados de una verificación

- `Aprobada`: comando o procedimiento ejecutado, con salida y fecha.
- `Pendiente por bloqueo externo`: no ejecutada por ausencia de dispositivo, KVM, imagen de emulador o decisión pendiente. Debe indicar el bloqueo exacto y qué falta.
- `No ejecutada`: planificada, sin intento todavía.

Una función no se declara validada si alguna de sus verificaciones requeridas está `Pendiente` o `No ejecutada`.

### 1.4 Decisiones y mediciones pendientes

- `D1–D5`: decisiones de producto o arquitectura que solo puede resolver el propietario. **D1(a), D2(a), D3, D4 y D5 (base) están aprobadas; siguen pendientes los detalles de D1(a), D3, D4 y D5, y la implementación/verificación de D2(a), D3, D4 y D5.** No se eligen por inferencia.
- `E1-C`: **aprobada la opción B**: E1 exige pruebas instrumentadas en Android TV exitosas con un canario significativo (importar una lista M3U de prueba, abrir el catálogo y navegar usando foco/D-pad); el arranque solo no basta. La decisión está aprobada; la ejecución y los resultados siguen pendientes. Si el emulador TV o el entorno requerido no funciona, se documenta el bloqueo y E1 permanece abierto; no se simula éxito ni se sustituye la prueba sin aprobación explícita.
- `S14 (alcance)`: **aprobada la cobertura de `README.md:38`/`:84`** como combinación de modos A/B/C y sus direcciones, orden manual de Apariencias, orden independiente de Colecciones y preferencias de ordenación por Lista persistidas entre reinicios. No se agregan funciones implícitas de presentación u organización sin requisito concreto aprobado. Es una decisión de alcance; implementación y validación pendientes. S14 y E3 no se cierran hasta implementar y validar las reglas aprobadas de comparación, Canales sin nombre y Canales sin grupo.
- `S14 (reglas)`: **aprobadas** las tres reglas: (1) comparación sin distinguir mayúsculas/minúsculas, con reglas de español; los acentos desempatan y la clave final es determinista; (2) Canales sin nombre se conservan, se muestran como «Sin nombre» y van al final con desempate determinista, sin rechazar la entrada ni usar el nombre mostrado como identidad; (3) en Modo B, sección visual «Sin grupo» al final, ordenada por nombre, sin crear un Grupo ficticio en Room. La comparación técnica reproducible se detalla en §2.1. Implementación y validación pendientes.
- `P1–P2`: **P1 está aprobada como política híbrida** (límite absoluto, comparación de regresión contra un baseline versionado y mediciones p95), con los valores numéricos pendientes. **P2 está aprobada como política híbrida para Android TV** (pruebas automatizadas de límites de texto y foco, capturas reproducibles y revisión visual humana a 10 pies, aprox. 3 m), con los umbrales numéricos y el procedimiento de aceptación visual pendientes. No son umbrales finales y no se usa ningún número no aprobado como criterio de cierre.
- `K1–K15`: restricciones de stack del README y de los ADR.

## 2. Specs S1–S57

Cada `S#` cita literalmente una función del README. La especificación detallada de S14 en §2.1 es una decisión funcional aprobada por el usuario; su alcance genérico se registra como cobertura aprobada de `README.md:38`/`:84` y no se atribuye al README. La lista no admite recortes ni aplazamientos.

### Administración de listas (README.md, «Funcionalidades previstas», punto 1)

- **S1** — Importación de listas M3U/M3U8 desde archivos locales y direcciones URL.
- **S2** — Gestión de múltiples listas y perfiles de reproducción.
- **S3** — Actualización manual y automática de listas, con intervalos configurables.
- **S4** — Validación de fuentes, manejo de errores y detección de listas inválidas.
- **S5** — Procesamiento eficiente de listas grandes.
- **S6** — Conservación de favoritos y preferencias durante las actualizaciones.
- **S7** — Indicadores del estado de carga, actualización y disponibilidad de las fuentes.

### Organización de canales (README.md, «Funcionalidades previstas», punto 2)

- **S8** — Clasificación por grupos y categorías definidos en la lista.
- **S9** — Búsqueda instantánea por nombre y filtros.
- **S10** — Canales favoritos y listas personalizadas.
- **S11** — Historial de reproducción y acceso rápido a los últimos canales.
- **S12** — Logotipos, nombres y metadatos disponibles en la fuente.
- **S13** — Ocultación de grupos y canales no deseados.
- **S14** — Ordenación y personalización de la presentación del catálogo (cobertura aprobada: A/B/C y direcciones, orden manual de Apariencias, orden independiente de Colecciones y preferencias por Lista persistidas entre reinicios; reglas de comparación, Canales sin nombre y Canales sin grupo aprobadas; especificación en §2.1).

### Reproductor multimedia (README.md, «Funcionalidades previstas», punto 3)

- **S15** — Reproducción de canales en directo y contenido bajo demanda cuando la fuente lo permita.
- **S16** — Soporte para los protocolos multimedia compatibles con el motor de reproducción.
- **S17** — Controles de reproducción accesibles y personalizables.
- **S18** — Gestión de pantalla completa, orientación y relación de aspecto.
- **S19** — Selección de pistas de audio y subtítulos cuando estén disponibles.
- **S20** — Gestión de errores, reconexión y recuperación ante interrupciones.
- **S21** — Ajustes de reproducción y preferencias de calidad cuando la fuente lo admita.
- **S22** — Información técnica del flujo y diagnóstico básico de reproducción.
- **S23** — Control del volumen, navegación entre canales y cambio rápido de contenido.

### Guía electrónica de programación (README.md, «Funcionalidades previstas», punto 4)

- **S24** — Compatibilidad prevista con fuentes XMLTV.
- **S25** — Programación actual y siguiente para canales con información disponible.
- **S26** — Guía de programación con vista temporal.
- **S27** — Asociación configurable entre canales y datos de programación.
- **S28** — Información sobre títulos, horarios y descripciones de los programas.
- **S29** — Actualización de la guía y gestión de datos incompletos.

### Experiencia Android TV (README.md, «Funcionalidades previstas», punto 5)

- **S30** — Interfaz diseñada para pantallas grandes y navegación con mando a distancia.
- **S31** — Navegación mediante cruceta, botones de selección y retroceso.
- **S32** — Indicadores de foco claros y navegación predecible.
- **S33** — Listas de canales y categorías optimizadas para una visualización a distancia.
- **S34** — Acceso rápido a favoritos, historial y guía de programación.
- **S35** — Controles adaptados al uso desde el sofá.
- **S36** — Compatibilidad con los patrones de navegación y ciclo de vida de Android TV.

### Experiencia móvil (README.md, «Funcionalidades previstas», punto 6)

- **S37** — Interfaz adaptable a teléfonos y tabletas.
- **S38** — Navegación táctil y controles accesibles.
- **S39** — Gestión sencilla de listas y canales.
- **S40** — Reproductor a pantalla completa y orientación adaptable.
- **S41** — Diseño coherente con las convenciones modernas de Android.
- **S42** — Gestión apropiada de las interrupciones, permisos y ciclo de vida de la aplicación.

### Personalización y preferencias (README.md, «Funcionalidades previstas», punto 7)

- **S43** — Temas claro y oscuro.
- **S44** — Configuración de reproducción y comportamiento del reproductor.
- **S45** — Personalización de la organización del catálogo.
- **S46** — Preferencias de actualización y almacenamiento local.
- **S47** — Gestión de favoritos, historial y opciones de privacidad.
- **S48** — Posibilidad de ampliar la configuración mediante módulos independientes.

### Rendimiento, seguridad y fiabilidad (README.md, «Funcionalidades previstas», punto 8)

- **S49** — Arquitectura modular y separación de responsabilidades.
- **S50** — Procesamiento asíncrono de listas y metadatos.
- **S51** — Caché local cuando sea apropiado.
- **S52** — Persistencia eficiente mediante base de datos local.
- **S53** — Validación de URL y manejo seguro de archivos y datos externos.
- **S54** — Tratamiento de errores de red y fuentes no disponibles.
- **S55** — Pruebas automatizadas y análisis estático.
- **S56** — Gestión responsable de memoria, recursos multimedia y tareas en segundo plano.
- **S57** — Protección de credenciales y datos sensibles cuando existan.

### 2.1 S14 — Especificación funcional, alcance y reglas aprobadas de ordenación del catálogo

Esta especificación es una decisión funcional del usuario, no una enumeración del README. `README.md:38` y `:84` exigen genéricamente ordenación y personalización del catálogo. La **cobertura aprobada** de ese requisito genérico es la combinación de:

1. Modos de ordenación A/B/C y sus direcciones aprobadas (S14e–S14i).
2. Orden manual de Apariencias (S14a, S14c, S14l).
3. Orden independiente de Colecciones (S14b, S14l).
4. Preferencias de ordenación por Lista, persistidas entre reinicios (S14j, S14k).

Los criterios S14a–S14c y la trazabilidad S14d se detallan en la fila S14 de la matriz; S14e–S14m, en la tabla siguiente. No se atribuye esta cobertura al README ni se agregan funciones implícitas de presentación u organización sin un requisito concreto aprobado. Es una decisión de alcance, no de implementación ni validación. Las reglas de mayúsculas/acentos, Canales sin nombre y Canales sin grupo están aprobadas y se detallan a continuación; su implementación y validación siguen pendientes y condicionan el cierre de S14 según §9.1.10. Todas las pruebas de esta sección están **planificadas, no ejecutadas**.

Distinciones obligatorias:

- El orden visual del catálogo es una vista o consulta: no sobrescribe `Apariencia.orden` (ADR-0002:23) ni `ColeccionCanal.posicion` (ADR-0002:27).
- La posición no es identidad del Canal (ADR-0004:21, :55). Ordenar no cambia PK, clave canónica, `clave_unicidad`, FKs, Historial ni relaciones.
- La preferencia de modo y dirección es por `Lista` M3U y se persiste en DataStore con la PK interna de `Lista` como clave; no se añade columna ni restricción en Room. E3-T7 es la tarea dueña de esa persistencia; E7-T5 consume la preferencia. Si el propietario decide persistirla en Room, será una decisión de esquema separada, no implícita en S14.
- Desempate técnico: después de la clave del modo, se aplica la clave secundaria de acentos de §2.1 y, si persiste el empate, la PK interna de la fila de catálogo (`Apariencia`) ascendente. No usa la posición en la Lista ni define identidad.

**Reglas aprobadas de comparación y presentación:**

1. **Mayúsculas y acentos:** la comparación no distingue mayúsculas/minúsculas y usa reglas de español. La clave primaria se calcula en `:core` con una función pura y versionada: normaliza el nombre a NFD, lo pasa a minúsculas con `Locale.ROOT` y lo transforma en una secuencia de rangos según una tabla de orden español fija y versionada (a–z, con `ñ` entre `n` y `o`; las vocales acentuadas y `ü` usan el rango de su vocal base; las demás letras con diacríticos usan el rango de su letra base); los caracteres no alfabéticos se comparan por puntos de código después de las letras. La comparación es lexicográfica por esos rangos. La clave secundaria conserva los diacríticos y compara la forma NFD en minúsculas por puntos de código, para que los acentos desempaten sin distinguir mayúsculas/minúsculas. La clave final es la PK interna de `Apariencia` ascendente. No se usa `Locale.getDefault()` ni un `Collator` dependiente del dispositivo; la misma función y el mismo corpus dorado se ejecutan en JVM y Android.
2. **Canales sin nombre:** se conserva la entrada; la UI muestra «Sin nombre». En los modos que ordenan por nombre (A y B), el Canal sin nombre va al final de la secuencia correspondiente (catálogo, Grupo o sección «Sin grupo») con el desempate de S14i. No se rechaza la entrada, no se sustituye el nombre almacenado por «Sin nombre» y el texto mostrado no se usa como identidad. En Modo C la clave es la última reproducción y la ausencia de nombre no la altera.
3. **Canales sin grupo:** en Modo B se muestra una sección visual «Sin grupo» al final de la lista, en ambas direcciones, con sus Canales ordenados por nombre con la dirección activa; no se crea un Grupo ficticio en Room. Los Canales sin nombre de esa sección van al final de la sección con el desempate de S14i.

| Criterio | Nivel | Aserción | Procedimiento |
|---|---|---|---|
| S14e — Modo A: nombre | JVM, Robolectric, I-M | Con nombres que difieren en mayúsculas y acentos, la clave primaria no distingue mayúsculas/minúsculas ni acentos; los acentos desempatan; los nombres iguales se resuelven por S14i. Dos consultas y una importación con el mismo conjunto en orden permutado producen la misma secuencia; los Canales sin nombre van al final en ambas direcciones; la secuencia es idéntica en JVM y Android. | Fixture F14e con `alfa`, `Álvaro`, `Beta`, `Nube`, `Ñandú`, `zeta` y un Canal sin nombre; consultar ascendente y descendente en DAO y UI; comparar con la secuencia calculada por la clave aprobada; repetir con orden de importación permutado; no comparar PK entre importaciones; ejecutar el mismo corpus en JVM y Android; no usar `Locale.getDefault()`. |
| S14f — Modo B: grupo | JVM, Robolectric, I-M | Ascendente ordena los Grupos A–Z y los Canales dentro de cada Grupo A–Z; descendente invierte ambos niveles. El orden de Grupos y el orden interno se comprueban por separado. La sección «Sin grupo» se muestra al final en ambas direcciones y no crea un Grupo en la base; sus Canales se ordenan por nombre con la dirección activa. Los Canales sin nombre van al final de su Grupo o sección en ambas direcciones con el desempate de S14i. | Fixture F14f con `Grupo Alfa` y `Grupo Bravo`, cada uno con canales en orden no alfabético, más un Canal sin Grupo y un Canal sin nombre; consultar ascendente y descendente; capturar la secuencia de Grupos y la secuencia interna; comprobar que «Sin grupo» queda al final en ambas direcciones, que sus Canales se ordenan por nombre y que no se crea una fila de Grupo; repetir tras reinicio. |
| S14g — Modo C: recientes | Robolectric, I-M | Usa la fecha de la última reproducción registrada por Canal en `Historial`; ascendente va del más antiguo al más reciente y descendente del más reciente al más antiguo; no usa `Apariencia.orden` ni `ColeccionCanal.posicion`. | Fixture F14g con C1 reproducido en t1 y de nuevo en t4, C2 en t2 y C3 en t3; `Apariencia.orden` fijado como C3,C2,C1 y `ColeccionCanal.posicion` como C2,C1,C3, ambos distintos del orden por última reproducción; consultar ascendente (C2,C3,C1) y descendente (C1,C3,C2); comprobar que C1 usa t4 y que `Historial` conserva una sola fila por Canal con la fecha de última reproducción, conforme a E1-U3/E3-T3; snapshot de ambos órdenes antes y después; repetir tras reinicio. |
| S14h — Nunca reproducidos | Robolectric, I-M | Un Canal sin fila en `Historial` queda al final en ascendente y en descendente; no se le asigna una fecha artificial. La ausencia de nombre no altera la clave de recencia en Modo C. | Fixture F14h con C1 y C2 reproducidos, C4 nunca reproducido y C5 sin nombre y nunca reproducido; consultar ambas direcciones; comprobar que C4 y C5 quedan al final por recencia, que su orden relativo es determinista por S14i y que no se crea una fila de Historial. |
| S14i — Desempates deterministas | JVM, Robolectric, I-M | Nombres iguales, nombres que solo difieren en acentos, Canales del mismo Grupo con nombre igual o timestamps iguales producen la misma secuencia en dos consultas y tras reinicio. Orden: clave primaria, clave secundaria de acentos, PK de `Apariencia` ascendente. | Fixture F14i con dos Canales distintos del mismo Grupo y nombre igual en Modo A, con `tvg-id`/URL distintos; sembrar sus Apariencias con PK explícitas 20 y 10, insertando primero la 20 y después la 10 para que el orden de inserción sea contrario al desempate; afirmar la secuencia esperada por PK ascendente [10,20] en ambas direcciones, no solo comparar dos consultas; repetir tras reinicio; asignar posiciones manuales inversas a las PK, permutarlas y comprobar que la secuencia [10,20] no cambia; añadir dos Canales con nombres que solo difieren en acentos y la misma clave primaria; afirmar la secuencia de la clave secundaria y luego la PK; ejecutar el mismo corpus en JVM y Android; no usar `Locale.getDefault()`. |
| S14j — Preferencia por Lista | Robolectric, I-M | L1 y L2 con Canales compartidos conservan modo y dirección independientes; cambiar la preferencia de L1 no altera L2; la UI permite elegir modo y dirección por Lista. | Fixture F14j con L1 y L2 compartiendo C1 y C2; fijar L1=nombre ascendente y L2=recientes descendente; consultar ambas; cambiar solo L1 a grupo descendente; releer L2 y comparar. Verificar que no hay columnas nuevas en Room. E3-T7 es dueña de la preferencia; E7-T5 la consume. |
| S14k — Persistencia tras reinicio | Robolectric, I-M | Modo y dirección de cada Lista se conservan al cerrar y reabrir la app; la preferencia leída y la secuencia visible coinciden con las de antes del reinicio. | Escribir preferencias distintas en L1 y L2; cerrar la app y recrear el proceso (no basta recrear DataStore) para que no sobreviva una caché en memoria; reabrir; leer preferencias y secuencia; comparar con el estado previo. E3-T7 es dueña de la persistencia; E7-T5 consume la preferencia. No declarar verde por compilar. |
| S14l — No regresión de Apariencia y Colección | Robolectric, I-M | Aplicar cualquier modo no modifica `Apariencia.orden` ni `ColeccionCanal.posicion`; el orden manual de Apariencias y el orden propio de Colección siguen intactos tras ordenar y reiniciar. | Snapshot de `Apariencia.orden` y `ColeccionCanal.posicion`; aplicar A, B y C en ambas direcciones; consultar de nuevo; reiniciar; comparar fila a fila. Repetir con una Colección y dos Listas. |
| S14m — Identidad y esquema | JVM, Robolectric | Ordenar no cambia PK, `clave_canonica`, `clave_unicidad`, FKs ni relaciones del Canal; no se añade índice único, columna ni restricción en Room; `exportSchema` y `PRAGMA index_list` no cambian por S14. Un Canal sin nombre se conserva; «Sin nombre» es solo texto de presentación, no nombre almacenado ni identidad. Un Canal sin Grupo no crea un Grupo ficticio; «Sin grupo» es solo una sección visual. | Snapshot de IDs, claves, relaciones, JSON exportado y `PRAGMA index_list`; aplicar todos los modos; comparar; comprobar que la preferencia vive en DataStore y no en `Apariencia` o `ColeccionCanal`; importar un Canal sin nombre y sin Grupo; comprobar que la fila existe, que no se creó un Grupo, que «Sin nombre» no se persistió como nombre y que las claves/identidad no cambiaron. |

## 3. Matriz de trazabilidad funcional (provisional)

La etapa es **provisional** hasta que se resuelvan los detalles pendientes de D1(a), D3, D4 y D5, se implementen y verifiquen D2(a), D3, D4 y D5, y se completen las definiciones funcionales marcadas como pendientes. Cada fila declara nivel, aserción observable y procedimiento reproducible. Las mediciones remiten a P1–P2. P1 y P2 están aprobadas como políticas híbridas, pero sus valores numéricos siguen pendientes; en P2 también queda pendiente el procedimiento de aceptación visual. No se usan números no aprobados como criterio de cierre y ninguna fila dependiente de P1/P2 se declara completa mientras falten los valores numéricos de P1, los umbrales o el procedimiento de aceptación visual de P2. Las rutas con `...`, `*` o `**` son **previstas**, no existentes; se verificarán al crear los archivos.

| S# | Etapa | Nivel(es) | Aserción | Procedimiento |
|---|---|---|---|---|
| S1 | E2 | JVM, I-M | S1a: un fixture M3U y un fixture M3U8, cada uno con 20 Canales esperados, producen 20 Canales y 0 duplicados por archivo SAF y por URL HTTP. S1b: ambos formatos conservan nombre, URL y orden esperados. | Fixtures F1a (M3U) y F1b (M3U8), cada una con 20 entradas y tabla esperada; importar cada fixture una vez desde archivo vía SAF (`content://`) y otra desde URL HTTP local; abrir catálogo; comparar conteo, nombres, URLs y orden contra la tabla. |
| S2 | E2/E3 | JVM, I-M | S2a: dos Listas de Fuentes distintas con el mismo `tvg-id` producen 2 Canales con PK distintas y 0 duplicados; cada Lista se puede crear, renombrar y seleccionar, y la selección persiste tras reinicio. S2b: ocultar un Canal en una Lista no afecta al homónimo de la otra. S2c: un Perfil tiene nombre y agrupa Listas M3U; no se reduce a renombrar la Lista activa. Conserva preferencias de reproducción elegidas por el usuario cuando el contenido las admite, incluidas las preferencias de pista de audio y subtítulos; no inventa pistas ni capacidades; distingue preferencias guardadas de opciones disponibles para el contenido actual. Pendientes de D3: alcance de preferencias (global/por perfil/por lista) y comportamiento cuando la pista preferida no está disponible; S2c no se declara cerrada hasta resolverlos. | Fixture F2 con el mismo `tvg-id` en 2 Fuentes; importar ambas; crear/renombrar/seleccionar cada Lista; reiniciar y releer la selección; consultar por Fuente y por `tvg-id`; ocultar uno; comparar. S2c: crear dos Listas y un Perfil con nombre que las agrupe; comprobar que el Perfil no es solo un renombre de la Lista activa; guardar preferencias de audio o subtítulos en contenido que las admite; abrir contenido sin esas pistas y comprobar que no se inventan y que se distingue la preferencia guardada de las opciones disponibles. La parte de alcance/fallback no se ejecuta hasta que D3 los defina; no declarar S2c cerrada. |
| S3 | E4 | JVM, Robolectric, I-M | S3a manual: pulsar Actualizar con F3 deja el Estado de Actualización en éxito y el catálogo actualizado; no depende de D4. S3b automática: WorkManager registra el disparo a la hora configurada y aplica reintentos con backoff; D4 aprobada. Pendientes: intervalos por defecto/mínimo/máximo, restricciones de red, comportamiento tras reinicio y detalles de backoff; S3b no se declara cerrada hasta definirlos. | S3a: fixture F3; pulsar Actualizar; leer Estado y comparar catálogo antes/después. S3b: `WorkManagerTestDriver` o reloj de prueba con intervalo explícito y registrado; avanzar reloj; leer ejecuciones; la parte de valores/restricciones/reinicio no se ejecuta hasta definir los detalles de D4; no se declara S3b cerrada. |
| S4 | E2 | JVM | Cada fixture produce un código de error tipado distinto; el snapshot del catálogo antes/después es idéntico. | Fixtures F4a–F4f: vacío, sin `#EXTM3U`, URL `ftp://`, BOM, bytes inválidos y binario; ejecutar importación; capturar tipo de error y `SELECT` de conteo/nombres. |
| S5 | E2 | JVM | 10 000 entradas parseadas, conteo exacto, sin `OutOfMemoryError`; memoria de lote acotada a la ventana declarada. P1 aprobada como política híbrida (límite absoluto, regresión contra baseline versionado y p95); los valores numéricos siguen pendientes, por lo que el rendimiento no se declara validado. | Fixture F5 generado por script con 10 000 `EXTINF`; ejecutar `:core:test` con un límite de heap explícito y registrado; medir P1 conforme a §7 (p95 y baseline versionado); comparar conteo; no declarar S5 cerrada hasta calibrar y aprobar los valores numéricos. |
| S6 | E4 | JVM, Robolectric | S6a manual: Favoritos, Historial, Ocultaciones, Colecciones y preferencias conservan IDs, valores y conteos tras Actualizar manual; no depende de D2 ni de D4. S6b automática: la misma conservación tras Actualizar automática queda pendiente de los detalles de D4 (intervalos, restricciones, reinicio y backoff); D4 aprobada; no se declara validada por S6a. | S6a: snapshot de tablas y DataStore antes; ejecutar Actualizar manual con F6 que cambia nombres/URLs; snapshot después; `diff` de consultas ordenadas. S6b: mismo procedimiento con el planificador de E4-T7, no ejecutable hasta definir los detalles de D4. |
| S7 | E4/E6 | JVM, I-M | S7a: `cargando` pasa de true a false al terminar tanto en éxito como en fallo. S7b: el resultado de Actualización registra éxito/fallo con causa. S7c: la disponibilidad de la Fuente se mantiene separada y no cambia por el solo fallo de Actualización. S7d: la UI muestra los tres indicadores sin reutilizar uno por otro. | JVM: inyectar fallo de red y luego éxito; leer los tres campos en modelo antes, durante y después. I-M: abrir la pantalla de Fuentes; observar los tres indicadores; repetir con fallo y con éxito; comparar. |
| S8 | E2/E3 | JVM, Robolectric, I-M | S8a: `group-title` y `EXTGRP` asignan el Grupo declarado a la Apariencia. S8b: dos Grupos con el mismo nombre en Listas distintas son filas distintas. S8c: ocultar uno deja el otro visible. S8d: un Canal sin `group-title` ni `EXTGRP` no se asigna a un Grupo inventado y no provoca crash. | Fixture F8 con `group-title`, `EXTGRP` y un Canal sin Grupo; importar en L1 y L2; `SELECT` por Lista/Grupo; ocultar Grupo en L1; consultar visibilidad en L1 y L2; abrir el catálogo en I-M y comparar las etiquetas. |
| S9 | E3 | Robolectric, I-M | Primera página con los IDs esperados, sin duplicados, y la misma página en dos consultas consecutivas. P1 aprobada como política híbrida (límite absoluto, regresión contra baseline versionado y p95); los valores numéricos siguen pendientes, por lo que el rendimiento no se declara validado. | Cargar 10 000 Canales; buscar `Canal 42`; capturar IDs de la página 0; repetir; aplicar dos filtros (Grupo y Favorito) y capturar de nuevo; comparar; medir P1 conforme a §7; no declarar S9 cerrada hasta calibrar y aprobar los valores numéricos. |
| S10 | E3 | Robolectric, I-M | S10a: marcar y desmarcar Favorito crea/elimina una sola fila y persiste tras reinicio. S10b: un segundo alta de Favorito no crea fila. S10c: crear, renombrar y borrar Colección, y agregar/quitar miembros, se refleja en UI y persiste. S10d: la Colección conserva un orden propio distinto del orden de Lista. | I-M: crear y borrar una Colección, agregar C,B,A y quitar B; reiniciar; comprobar UI. Robolectric: alta de Favorito dos veces y `SELECT count(*)`; comparar orden de Colección C,A con orden de Lista A,B,C. |
| S11 | E3 | Robolectric, I-M | S11a: Historial con una fila por Canal y orden fecha DESC; reproducir actualiza la fecha. S11b: la consulta de últimos N usa índice; N=5 se usa solo como fixture, el valor de producto queda pendiente de definición funcional. S11c: la UI de acceso rápido muestra los últimos N en ese orden y abre el Canal seleccionado. S11d: todo persiste tras reinicio. | Reproducir C1, C2 y C1 con fechas controladas; `SELECT` ordenado; `EXPLAIN QUERY PLAN` de últimos N; I-M: abrir acceso rápido, comprobar el orden, tocar el segundo y verificar el Canal; reiniciar y repetir. |
| S12 | E2 | JVM | Logo, nombre y atributos extendidos persistidos; `EXTGRP` produce Grupo; la ausencia de un atributo no lanza. | Fixtures F12a (completo) y F12b (mínimo); importar; comparar campos y Grupo. |
| S13 | E3 | Robolectric, I-M | S13a: ocultar un Canal lo excluye del Catálogo visible pero conserva fila y relaciones; desocultarlo lo restaura. S13b: ocultar un Grupo afecta solo a las Apariencias de ese Grupo/Lista; el Grupo homónimo de otra Lista sigue visible. S13c: ambas ocultaciones y sus reversiones persisten tras reinicio. | Contar filas y FKs antes/después; ocultar/desocultar Canal; consultar; ocultar/desocultar Grupo en L1; comparar L1/L2; I-M: abrir Catálogo en ambos estados y comprobar visibilidad; reiniciar y releer. |
| S14 | E3 | JVM, Robolectric, I-M | S14a: L1 y L2 comparten tres Canales, C1, C2 y C3, con órdenes independientes y distinguibles; estado inicial L1: C1, C2, C3 y L2: C3, C2, C1. Reordenar únicamente L1 a C2, C1, C3 y verificar inmediatamente L1: C2, C1, C3 y L2: C3, C2, C1. Cerrar la app y recrear el proceso (no basta recrear Activity, DataStore o BD); tras reabrir, verificar de nuevo L1: C2, C1, C3 y L2: C3, C2, C1. La operación no cambia la identidad de los Canales ni el orden propio de las Colecciones; coherencia con S14c y S45a–S45c. No basta comprobar solo L2: deben afirmarse ambas secuencias exactas antes y después del reinicio. S14b: el orden de una Colección es independiente del orden de Apariencia. S14c: la UI permite ejecutar el reordenamiento manual y refleja el nuevo orden tras reiniciar. S14d: `README.md:38` y `:84` exigen genéricamente ordenación y personalización del catálogo; la cobertura aprobada es la combinación de modos A/B/C y sus direcciones, orden manual de Apariencias, orden independiente de Colecciones y preferencias de ordenación por Lista persistidas entre reinicios, sin atribuir esa cobertura al README ni agregar funciones implícitas de presentación u organización sin requisito concreto aprobado. La decisión es de alcance, no de implementación ni validación. Las reglas de mayúsculas/acentos, Canales sin nombre y Canales sin grupo están aprobadas y se detallan en §2.1; su implementación y validación siguen pendientes y condicionan el cierre de S14 en §9.1.10. S14e–S14m: cubren los modos A/B/C, canales nunca reproducidos, desempates deterministas con acentos, comparación reproducible JVM/Android con tabla de orden español fija en `:core`, canales sin nombre al final, sección «Sin grupo» sin Grupo ficticio, preferencia independiente por Lista, persistencia tras reinicio, no regresión de Apariencia/Colección e identidad/esquema; aserciones, niveles y procedimientos en §2.1. La preferencia por Lista es propiedad de E3-T7; E7-T5 la consume. No bloqueada por D1(a), D2(a) ni los detalles pendientes de D3 ni por los detalles pendientes de D4/D5. | S14a: preparar tres Canales compartidos C1, C2 y C3; fijar el estado inicial L1: C1, C2, C3 y L2: C3, C2, C1; leer ambas; reordenar únicamente L1 a C2, C1, C3; verificar inmediatamente L1: C2, C1, C3 y L2: C3, C2, C1; cerrar la app y recrear el proceso (no basta recrear Activity, DataStore o BD); reabrir; verificar de nuevo L1: C2, C1, C3 y L2: C3, C2, C1. Confirmar que no cambia la identidad de los Canales ni el orden propio de las Colecciones; coherencia con S14c y S45a–S45c. No basta comprobar solo L2. S14b: definir orden A,B,C en Apariencia y C,B,A en Colección; cambiar uno; releer. S14c: I-M arrastrar/ordenar manualmente y comparar la secuencia visible; reiniciar. S14a y S14c se implementan en E3-T8; S14b en E3-T5. S14d: contrastar README y ADR-0002:23/:27 con §2.1; comprobar que la cobertura aprobada incluye A/B/C y direcciones, orden manual de Apariencias, orden independiente de Colecciones y preferencias por Lista persistidas; comprobar que las reglas de comparación, Canales sin nombre y Canales sin grupo están registradas en §2.1; no declarar S14 completo hasta implementarlas y validarlas. S14e–S14m: ejecutar los fixtures F14e–F14m y los procedimientos de §2.1, incluidos los casos de mayúsculas/acentos, sin nombre y sin grupo. |
| S15 | E6 | I-M, Física | `READY` y progreso > 0 en HLS directo y MP4 VOD; error tipado si la fuente no está disponible. | Fixture F15 (HLS y MP4); abrir cada uno en I-M; leer `Player.STATE` y `currentPosition`; repetir con flujo real en Física. |
| S16 | E6 | I-M, Física | Cada protocolo **aprobado** en la sección 5 tiene reproducción verificada con fixture y fuente real; ninguno se marca validado solo por documentación. Los protocolos con alcance pendiente (RTSP, RTMP, SmoothStreaming) no cuentan para el cierre hasta que se apruebe su alcance. | Ejecutar la matriz de la sección 5: por protocolo aprobado, abrir fixture y flujo real; registrar `READY` o error, fecha y fuente; para los pendientes, registrar `No ejecutada` y la decisión que falta. |
| S17 | E6/E7 | I-M, I-TV | S17a: play/pause, seek, siguiente/anterior, fullscreen y selección de pista ejecutan su acción, exponen `contentDescription` y son alcanzables por touch y DPad. S17b: la preferencia de personalización de controles queda pendiente de definición funcional: no se inventa qué controles se ocultan o reordenan. | I-M: tap en cada control y leer el estado del reproductor. I-TV: foco + OK en cada control. Inspeccionar `SemanticsNode` y registrar `contentDescription`. S17b: no ejecutar hasta que se defina la personalización. |
| S18 | E6 | I-M, I-TV | La sesión sigue viva tras fullscreen, rotación y resize; `resizeMode` aplicado coincide con el configurado. | Reproducir; alternar fullscreen; rotar; redimensionar ventana; leer `player` y `resizeMode` tras cada paso. |
| S19 | E6 | I-M | S19a: pistas listadas; seleccionar audio o subtítulo cambia la pista activa; ausencia de pistas no lanza. S19b: una preferencia guardada de audio o subtítulos no crea pistas ni capacidades y se distingue de las opciones disponibles para el contenido actual; el comportamiento cuando la pista preferida no está disponible queda pendiente de D3. | Fixture F19 con 2 audio y 2 subtítulos; abrir menú; seleccionar; leer `Tracks`; repetir con fixture sin pistas. S19b: guardar una preferencia de audio o subtítulo; abrir contenido que no ofrece esa pista; comprobar que no se inventa y que la preferencia guardada no se presenta como opción actual. No ejecutar el fallback hasta que D3 lo defina. |
| S20 | E6 | JVM, I-M | S20a: con un fallo de red transitorio simulado (timeout o corte controlado) el reproductor registra al menos un reintento y, al restablecer la red, llega a `READY` sin recrear la pantalla. S20b: con un fallo permanente (HTTP 404) termina en error tipado y no continúa reintentando. S20c: el número exacto de reintentos y el backoff quedan pendientes de definición funcional. | Inyectar timeout o corte controlado para S20a, restablecer la red y leer el estado; inyectar 404 para S20b y comprobar que los intentos cesan; registrar intentos y estados. Interrupciones de app en S42. |
| S21 | E6/E7 | Robolectric, I-M | Preferencia auto/techo/pista se traduce en `TrackSelectionParameters` y persiste tras reinicio; si la fuente no lo admite, se mantiene auto sin crash. D3: la preferencia guardada de audio o subtítulos se distingue de las opciones disponibles del contenido actual y no inventa pistas; el comportamiento cuando la pista preferida no está disponible sigue pendiente de D3; el fallback de auto de S21 no decide ese caso. | Configurar cada opción; leer parámetros; reiniciar app; releer preferencia y parámetros; repetir con fuente que no admita la opción. D3: guardar una preferencia de pista; abrir contenido que no la ofrece; comprobar que no se inventa y que la preferencia guardada no se presenta como opción actual; no ejecutar el fallback de pista preferida hasta que D3 lo defina. |
| S22 | E6 | I-M | S22a: el diagnóstico visible contiene, como mínimo, protocolo, pistas, buffer y último error del fixture. S22b: el texto copiable conserva esos campos y no contiene usuario, contraseña ni token. S22c: los campos técnicos adicionales no están enumerados en el README y quedan pendientes de definición funcional, sin declarar que S22 los cubre. | Reproducir F22 (HLS con 2 pistas) y provocar un 404; abrir diagnóstico; leer los cuatro campos y comparar con el estado del reproductor; copiar el texto; buscar las credenciales del fixture; repetir con un flujo sin pistas. S22c: no ejecutar hasta que exista una definición funcional. |
| S23 | E6/E7 | I-M, I-TV | Volumen sube/baja/silencia; siguiente/anterior cambia de Canal; no se vuelve al Catálogo. | Ejecutar cada acción; leer `volume` y `currentMediaItem`; verificar la pantalla activa. |
| S24 | E5 | JVM | XMLTV válido produce N programas y M canales; malformado produce error tolerado; incompleto conserva lo parseado; no carga el documento completo. | Fixtures F24a–F24c; ejecutar parser; comparar conteos; medir memoria conforme a P1 (§7; p95 y baseline versionado). Sin dependencia de D1(a). |
| S25 | E5 | Robolectric, I-M | Actual devuelve el programa que contiene `now`; siguiente devuelve el inmediatamente posterior; un Canal sin programa no muestra fila y no falla. | Fixture F25 con intervalos conocidos y un Canal sin EPG; fijar reloj; consultar por Canal asociado. Cierre end-to-end pendiente de la regla de estabilidad/reconfirmación de D1(a). |
| S26 | E5 | Robolectric, I-M | La vista temporal muestra los programas del intervalo y carga páginas sucesivas sin duplicados. | Fixture F26 de 7 días; abrir ventana de 24 h; scroll; capturar IDs por página. Cierre end-to-end pendiente de la regla de estabilidad/reconfirmación de D1(a). |
| S27 | E5 | Robolectric, I-M | S27a: la heurística propone una Asociación cuando `tvg-id` coincide; no propone cuando no hay coincidencia. S27b: confirmar, editar y quitar la Asociación persiste tras reinicio. S27c: una nueva generación no borra una Asociación confirmada si el `channel id` lógico sigue existiendo. S27d: si el destino desaparece temporalmente, la Asociación se conserva y queda marcada para resolución, sin reasignación automática. S27e: ante indicios de reutilización del ID, no se reasignan programas automáticamente; si existe ambigüedad, se exige reconfirmación. La huella de identidad, las señales relevantes y los criterios exactos de reconfirmación quedan pendientes de D1(a); no se fijan umbrales. | Fixture F27 con canales XMLTV `A` y `B`; ejecutar heurística; confirmar `A`, editar a `B` y quitar una tercera; reiniciar; importar una generación nueva con `A`/`B` y otra sin `B`; releer y comprobar estado. No ejecutar el cierre estable hasta que se definan huella, señales y reconfirmación. |
| S28 | E5 | Robolectric, I-M | S28a: título, inicio, fin y descripción se mapean correctamente; campos ausentes se muestran vacíos sin crash. S28b: fechas XMLTV con zona horaria y offsets distintos (`+0000`, `+0200`, `-0500`) producen el instante correcto. S28c: la UI móvil muestra la hora local y el texto esperados. | Robolectric: fixture F28 con y sin descripción y con los tres offsets; parsear, consultar y comparar instantes. I-M: abrir el detalle de F28 y comparar título, hora local y descripción; repetir con campos nulos. Cierre end-to-end pendiente de la regla de estabilidad/reconfirmación de D1(a). |
| S29 | E5 | JVM, Robolectric | S29a: fallo a mitad descarta la generación en staging, conserva la guía anterior publicada y registra la causa. S29b: datos incompletos siguen la regla documentada de recuperación. S29c: un error global reporta cuántos elementos se recuperaron y cuántos se descartaron. S29d: la cancelación cooperativa deja el puntero publicado sin cambios. | JVM: parser con fixture F29 y cancelación en el evento K; comprobar staging y errores. Robolectric: consultar la generación publicada y el Estado de Actualización en base real; repetir con campos faltantes y con error global. End-to-end pendiente de la regla de estabilidad/reconfirmación de D1(a). |
| S30 | E7 | I-TV, Física | S30a: la app usa el layout de TV a 1080p, los bounds de los nodos quedan dentro de la pantalla y se navega solo con DPad. S30b: tras importar y abrir catálogo, el nodo enfocado es el primer Canal esperado y hay exactamente un foco. S30c: no se requiere touch para llegar al catálogo. | I-TV: importar F30 con 3 Canales; abrir catálogo; volcar `SemanticsNode` y comprobar texto del primer Canal, bounds dentro de pantalla y `count(focused)==1`; recorrer la pantalla solo con DPad; captura 1080p. Física: repetir con mando. |
| S31 | E7 | I-TV, Física | S31a: para un fixture de 2 filas × 3 columnas, derecha avanza una columna, izquierda retrocede, abajo mantiene columna y avanza fila, arriba retrocede fila; OK activa el elemento enfocado. S31b: back desde el catálogo vuelve a la pantalla anterior sin finalizar la app; back en la raíz sigue la política estándar de Android. | I-TV: con F31, registrar el foco tras cada tecla y comparar con la matriz 2×3; pulsar OK y comprobar la acción; pulsar back y verificar pantalla y `Activity` viva. Física: repetir con mando. |
| S32 | E7 | I-TV | S32a: exactamente un nodo enfocado por pantalla y el recorrido completo no pierde ni duplica foco. S32b: el indicador visual de foco se revisa con la política P2 aprobada (pruebas automatizadas de foco, captura reproducible y revisión visual humana a 10 pies, aprox. 3 m); el umbral numérico y el procedimiento de aceptación visual siguen pendientes; no se declara completo hasta definirlos y ejecutar la revisión. | Recorrer todos los nodos; comprobar `count(focused)==1` en cada paso; captura reproducible del indicador; revisión visual humana a 10 pies; registrar valores; umbral y procedimiento pendientes; CI sola no demuestra legibilidad real a distancia. |
| S33 | E7 | I-TV, Física | S33a: a 10 pies no hay texto cortado ni elementos fuera de pantalla. S33b: tamaño efectivo de texto y área de foco se miden y registran; P2 aprobada como política híbrida (pruebas automatizadas de límites, capturas reproducibles y revisión visual humana a 10 pies, aprox. 3 m); los umbrales numéricos y el procedimiento de aceptación visual siguen pendientes; la fila no se declara completa hasta definirlos y ejecutar la revisión. CI sola no demuestra legibilidad real a distancia. | TV 1080p: captura reproducible y medición en dp; revisión visual humana a 10 pies (aprox. 3 m); registrar valores y condiciones; umbrales y procedimiento pendientes; no declarar validación visual por CI. |
| S34 | E7 | I-TV | Favoritos, Historial y Guía se abren desde TV en un solo nivel; foco inicial en el primer elemento. | Desde el shell, abrir cada destino; verificar pantalla y foco. |
| S35 | E7 | Física | Todas las acciones son alcanzables solo con DPad; ninguna requiere touch. | Checklist E7-T7 en dispositivo: recorrer cada pantalla y acción; registrar OK/fallo. Pendiente si no hay dispositivo. |
| S36 | E7 | I-TV, Física | S36a: desde reproducción, back vuelve al catálogo sin cerrar la app. S36b: pausa/reanudación conserva la sesión y la posición. S36c: `onStop`/`onResume` conserva o restaura la sesión según el ciclo de vida de Android TV. S36d: una recreación de Activity no duplica la sesión ni el foco. | I-TV: reproducir F36; pausar; enviar a background; volver; leer estado y posición; pulsar back y comprobar pantalla; recrear Activity. Física: repetir con mando y control remoto. |
| S37 | E7 | I-M | En teléfono y tableta (`sw600dp`) no hay recortes ni scroll horizontal; el árbol semántico contiene todos los controles. | Ejecutar en 2 tamaños; captura y `SemanticsNode`; comparar la lista de controles. |
| S38 | E7 | I-M | S38a: tap, long-press y swipe ejecutan la acción declarada en los controles del fixture. S38b: cada control tiene `contentDescription`; el área táctil se mide y registra; P2 aprobada como política híbrida (pruebas automatizadas, capturas reproducibles y revisión visual humana a 10 pies cuando aplique); el umbral numérico de área táctil móvil y el procedimiento de aceptación visual siguen pendientes; no se declara completo hasta definirlos y verificar. CI sola no demuestra legibilidad real a distancia. | Ejecutar cada gesto; leer semántica y medir área; registrar valores; umbral y procedimiento pendientes; revisión visual humana cuando aplique; no declarar completo hasta aprobarlos. |
| S39 | E2/E3 | I-M | Alta, edición, baja y ocultación de Lista/Canal se reflejan en UI y persisten tras reinicio. | Ejecutar CRUD; reiniciar; consultar UI y BD. |
| S40 | E6 | I-M | Igual que S18 en móvil: sesión viva y modo aplicado tras fullscreen/rotación. | Reproducir; fullscreen; rotar; leer estado y `resizeMode`. |
| S41 | E7 | I-M | S41a: el tema usa Material 3 y se aplica a las pantallas móviles. S41b: en edge-to-edge, ningún contenido queda tapado por barras del sistema en teléfono ni tableta. S41c: back navega al nivel anterior sin cerrar la app, salvo en la raíz. | I-M: inspeccionar `MaterialTheme`; captura con insets en teléfono y `sw600dp`; recorrer cada nivel y pulsar back; comprobar pantalla y `Activity`. |
| S42 | E7 | I-M, Física | S42a: audio focus loss/gain/duck produce pausa, reanudación o atenuación según el estado declarado. S42b: una llamada o interrupción pausa y, al terminar, la app queda en el estado esperado. S42c: permiso denegado y luego concedido no rompe la reproducción y muestra el estado correcto. S42d: background/foreground conserva o restaura la sesión. | I-M: simular loss/gain/duck y llamada; denegar y conceder permiso; enviar a background y volver; leer estado y posición en cada paso. Física: repetir con llamada real. |
| S43 | E7 | I-M | Cambio claro/oscuro aplica el tema y conserva pantalla y estado. | Cambiar tema; comparar tema y estado antes/después. |
| S44 | E7 | Robolectric, I-M | S44a: la preferencia de selección de pista (auto/techo/pista) persiste y se traduce en `TrackSelectionParameters` tras reinicio. S44b: la preferencia de `resizeMode` persiste y se aplica al reproductor. S44c: si la fuente no admite la preferencia, se mantiene el modo automático sin crash; esto no decide el caso de una pista preferida no disponible. S44d: la preferencia guardada de audio o subtítulos se distingue de las opciones disponibles para el contenido actual; no se inventan pistas; el alcance global/por perfil/por lista y el fallback de pista preferida quedan pendientes de D3. | Robolectric: escribir cada preferencia en DataStore, recrear y leer. I-M: cambiar pista y `resizeMode`, reiniciar, verificar parámetros y efecto; repetir con fuente que no admita la opción. S44d: guardar preferencia de audio o subtítulo; abrir contenido que no la ofrece; comprobar que no se inventa y que se distingue de las opciones actuales; no ejecutar alcance/fallback hasta que D3 los defina. |
| S45 | E3/E7 | Robolectric, I-M | S45a: el orden de Apariencia y el de Colección definidos por el usuario se conservan tras reiniciar la app. S45b: la UI muestra el mismo orden que la base. S45c: cambiar el orden de una Lista no altera el de una Colección ni el de otra Lista. S45d: la preferencia de ordenación por Lista (modo y dirección) persiste tras reiniciar el proceso y no altera el orden de Apariencia ni el de Colección. | Robolectric: definir orden/colección, cerrar y reabrir BD, comparar secuencias. I-M: reordenar en UI, reiniciar, comparar secuencia visible; cambiar una Lista y comprobar las demás. S45d: fijar preferencias distintas en L1/L2; cerrar la app y recrear el proceso (no solo DataStore/BD); reabrir; releer y comparar; comprobar que Apariencia/Colección no cambiaron. S45a–S45c se implementan en E3-T8 para Apariencias y E3-T5 para Colecciones. E3-T7 es dueña de la preferencia; E7-T5 la consume. |
| S46 | E4/E7 | Robolectric | S46a: «usar global» devuelve el valor global y el override por Fuente devuelve el propio, con bandera de existencia que distingue ausencia de valor. S46b: intervalo y reintentos se aplican según D4 aprobada (WorkManager, intervalos configurables, backoff); pendientes valores por defecto/mínimo/máximo, restricciones de red, comportamiento tras reinicio y detalles de backoff. S46c: los valores por defecto de retención global y las excepciones por Fuente se resuelven según ADR-0002:39–40. S46d: cambiar el global no corrompe overrides existentes. | Escribir global y override; leer ambos; borrar el override y releer el global. S46b: reloj de prueba y TestDriver; no ejecutar valores/reinicio hasta definir los detalles de D4; no declarar cerrado. S46c: cambiar global y override de retención; releer ambos. |
| S47 | E7 | Robolectric, I-M | S47a: borrar Historial deja Historial vacío y conserva Favoritos. S47b: borrar Favoritos deja Favoritos vacío y conserva Historial. S47c: borrar ambos deja ambas tablas vacías y persiste tras reinicio. S47d: durante esos flujos no se envía telemetría ni tráfico no solicitado. S47e: las opciones de privacidad adicionales quedan pendientes de definición funcional. | Robolectric: ejecutar cada borrado y `SELECT count(*)` de ambas tablas; reiniciar y repetir. I-M: ejecutar desde la UI; capturar tráfico de red durante el flujo; comprobar que no hay solicitudes salientes. S47e: no ejecutar hasta definir las opciones. |
| S48 | E7 | JVM, I-M | S48a: añadir un ajuste de UI se implementa en la capa de preferencias/UI de `:app` sin modificar `:core` ni introducir dependencia de `:app`/Android en `:core`. S48b: el ajuste nuevo se registra en la capa de preferencias y es visible sin cambiar las pantallas existentes. S48c: `:core` no contiene imports Android ni depende de `:app`; `:app` depende de `:core`. S48d: la inspección de paquetes/imports confirma que parser, identidad, dominio y modelos de EPG viven en `:core`, y que UI, Media3, Room, Hilt y DataStore viven en `:app`; `:app` no reimplementa parser, identidad ni dominio de `:core`. | JVM: test de dependencias y `./gradlew :core:dependencies`; comprobar el grafo y que `:core` no cambió. Inspección manual de paquetes/imports para S48d. I-M: abrir preferencias y comprobar el ajuste nuevo sin cambios en las pantallas existentes. |
| S49 | E1 | JVM | `:core` sin imports Android y sin dependencia de `:app`; `:app` depende de `:core`. | Test de pureza que escanea imports; `./gradlew :core:dependencies`. |
| S50 | E1/E2 | JVM, I-M | Importación y metadatos corren en coroutines/Flow; no hay `Thread`/`Executor` manual; cancelación cooperativa observada. | `runTest` con dispatcher; cancelar y verificar `CancellationException`; buscar `Thread(` y `Executor`. |
| S51 | E2/E4 | Robolectric | S51a: una segunda lectura del mismo recurso dentro de la ventana de caché no vuelve a descargarlo. S51b: tras Actualizar, la lectura devuelve el contenido nuevo y no el valor viejo. S51c: la clave y el TTL exactos de la caché quedan pendientes de definición funcional; no se inventan. | Inyectar un cliente HTTP contador; leer dos veces y comparar descargas; avanzar el reloj de prueba; Actualizar; releer y comparar. S51c: no declarar cerrada la política hasta que se defina. |
| S52 | E1 | Robolectric | Room v1 con índices y Paging devuelve páginas correctas sobre 10 000 filas; el plan usa índice. P1 aprobada como política híbrida (límite absoluto, regresión contra baseline versionado y p95); los valores numéricos siguen pendientes, por lo que el rendimiento no se declara validado. | Cargar 10 000; `PagingSource` páginas 0 y 1; `EXPLAIN QUERY PLAN`; medir P1 conforme a §7; no declarar S52 cerrada hasta calibrar y aprobar los valores numéricos. |
| S53 | E2 | JVM, I-M | S53a: la URL de Fuente/Lista acepta `http`, `https` y URI local SAF `content://`; rechaza `file://` y esquemas desconocidos con error tipado. S53b: la URL de stream se acepta o rechaza según el protocolo aprobado en la sección 5; RTSP, RTMP y SmoothStreaming no se deciden en esta fila. S53c: ni la URL de Fuente/Lista ni la de stream aparecen en logs con usuario, contraseña o token. | JVM: tabla de entradas de Fuente/Lista (`http`, `https`, `content://`, `file://`, desconocido) y de stream (HLS, DASH, progresivo y los pendientes); capturar el resultado y los logs; buscar credenciales. I-M: seleccionar un archivo SAF real y comprobar permiso persistido. |
| S54 | E2/E4 | JVM, I-M | Timeout, 4xx/5xx y offline producen error tipado; el snapshot del catálogo queda intacto. | JVM: servidor simulado. I-M: modo avión. Comparar snapshot antes/después. |
| S55 | E1 | — (evidencia de CI, no nivel de prueba) | Un run de CI identificado por SHA tiene `jvm-tests`, `static-analysis`, `apk`, `instrumented-mobile` e `instrumented-tv`; `instrumented-tv` debe estar verde y ejecutar el canario significativo de E1-C (importar una lista M3U de prueba, abrir el catálogo y navegar con foco/D-pad). El arranque solo no satisface E1-C. El workflow falla si un job falla y el artifact de APK está presente. La evidencia de ejecución es el run de CI, no un nivel de prueba; si el emulador o el entorno falla, se documenta el bloqueo y E1 permanece abierto; `instrumented-tv` no se declara verde si queda pendiente. | Push a la rama; consultar el run por SHA; descargar artifact; inspeccionar logs de cada job; verificar en `instrumented-tv` la importación M3U, la apertura del catálogo y la navegación con foco/D-pad; registrar verde o bloqueo documentado; no sustituir la prueba sin aprobación explícita. |
| S56 | E4/E6/E7 | JVM, I-M, I-TV | S56a: no hay `Thread`/`Executor` manuales ni collectors sin cancelar en el código de importación/reproducción (JVM, inspección estática). S56b: bajo `StrictMode` en Android, importación y reproducción no producen violaciones de disco/red en el hilo principal. S56c: al cerrar la pantalla, el player se libera, los collectors se cancelan y no quedan workers huérfanos. | JVM: buscar `Thread(`/`Executor` y comprobar cancelación con `runTest`. I-M/I-TV: activar `StrictMode` con `penaltyLog`; ejecutar importación y reproducción; cerrar pantalla; inspeccionar logs de StrictMode, `player.release` y jobs activos. |
| S57 | E2/E7 | JVM, Robolectric, I-M | S57a: credenciales de URL no aparecen en logs, diagnóstico, errores ni textos copiables; D5 base aprobada: prohibición de credenciales en logs/diagnósticos; S57a no espera el inventario. S57b: la base no contiene la contraseña ni el token en texto plano; cifrado de campos sensibles en reposo con claves protegidas mediante Android Keystore; inventario exacto, gestión de claves, backup/restauración y pruebas de recuperación pendientes; S57b no se declara validada hasta definirlos. S57c: la UI no muestra la credencial completa según la regla explícita de qué es credencial; no se asume que toda URL sea credencial; pendiente con el inventario/regla. | URL con usuario, contraseña y token; capturar logs, diagnóstico y errores; consultar la BD y buscar las cadenas; cuando D5 defina inventario y gestión de claves, abrir la BD con el mecanismo aprobado y verificar que el valor no es legible en claro y que la app lo recupera por su ruta de acceso; no ejecutar pruebas de backup/recuperación hasta definirlas. I-M: abrir la pantalla de Fuente y comprobar que no se muestra la credencial completa; no asumir que toda URL es credencial. No declarar S57b/S57c cerradas. |

## 4. Matriz complementaria de stack y restricciones (K1–K15)

| K# | Tecnología o restricción | Fuente | Etapa | Verificación | Nivel |
|---|---|---|---|---|---|
| K1 | Kotlin | README.md, «Stack tecnológico»; ADR-0001 | E1 | Compilación JVM de `:core` (`:core:test`/`compileKotlin`) y compilación Android de `:app` en el job `apk` de CI; sin Java salvo interoperabilidad justificada | JVM; la compilación de `:app` se evidencia en CI, no en JVM local |
| K2 | Jetpack Compose + Material 3 | README.md, «Stack tecnológico»; ADR-0001 | E7-T3 | Pantallas móviles con Material 3; insets y back predictivo | I-M |
| K3 | Compose for TV (`androidx.tv:tv-material`) | README.md, «Stack tecnológico»; ADR-0001 | E7-T1 | Shell TV y navegación DPad | I-TV |
| K4 | AndroidX Media3 / ExoPlayer | README.md, «Stack tecnológico»; ADR-0001 | E6-T1, E6-T2, E6-T3 | Sesión, HLS/VOD, pantalla y pistas | I-M, Física |
| K5 | MVVM + repositorios | README.md, «Stack tecnológico»; ADR-0001 | E1-U1, E2-T3, E3-T6 | UI no accede a DAO; test de dependencias por capa; módulos por responsabilidad: `:core` contiene modelo, parser, identidad, reconciliación y reglas de catálogo; `:app` contiene UI, persistencia, Hilt y plataforma; `:core` no depende de `:app`/Android y no se crean `:data`/`:ui` sin segundo consumidor | JVM |
| K6 | Room | README.md, «Stack tecnológico»; ADR-0002 | E1-U3/U4 | Esquema, DAO, índices y Paging | Robolectric |
| K7 | DataStore | README.md, «Stack tecnológico»; ADR-0002 | E3-T7 (dueña), E7-T5 (consumidora) | Preferencias globales y por Lista (ordenación) persistentes; E3-T7 implementa la preferencia por Lista, E7-T5 la consume | Robolectric, I-M |
| K8 | Coroutines y Flow | README.md, «Stack tecnológico»; ADR-0001 | E1-U1, E2-T1, E2-T3, E5-T1 | Procesamiento asíncrono y cancelación cooperativa | JVM |
| K9 | Hilt | README.md, «Stack tecnológico»; ADR-0001:14; ADR-0002:56 | E1-U1 | `@HiltAndroidApp`, inyección en una pantalla y test de componente | JVM, I-M |
| K10 | Gradle Kotlin DSL + Version Catalog | README.md, «Stack tecnológico» | E1-U1 | `libs.versions.toml`; sin versiones hardcodeadas en módulos | JVM |
| K11 | JUnit + pruebas de componentes + pruebas de interfaz + Robolectric | README.md, «Stack tecnológico»; ADR-0002:48 | E1-U4 | Tests JVM, componentes e interfaz; DAO/migración con Robolectric | JVM, Robolectric, I-M |
| K12 | ktlint + detekt | README.md, «Stack tecnológico»; ADR-0005:23 | E1-U5 | Job `static-analysis` con ambas herramientas | JVM |
| K13 | Paging 3 | README.md, «Stack tecnológico»; ADR-0002 | E3-T1, E5-T5 | Búsqueda y guía paginadas | Robolectric |
| K14 | Git + GitHub | README.md, «Stack tecnológico»; ADR-0005 | E1-U5 | Run de CI identificado por SHA y artifacts descargables; no es una prueba de producto | — (evidencia de CI, no nivel de prueba) |
| K15 | OpenCode como entorno asistido | README.md, «Stack tecnológico» | E1-U7 | Registro de sesión/comandos con fecha, comando y resultado por unidad; criterio observable: existe una entrada por unidad en el registro de evidencias; no es artefacto de producto | — (registro operativo, no prueba de producto) |

## 5. Protocolos Media3 a evaluar (S16)

Soporte del motor = lo que Media3 documenta. Validación = reproducción con una fuente real o fixture representativo. Una fila no se declara validada por el solo soporte documentado. **El alcance de RTSP, RTMP y SmoothStreaming no se decide en este documento**: sus filas quedan pendientes de aprobación y no cuentan para el cierre de S16/E6.

| Protocolo | Soporte del motor | Validación prevista | Nivel | Estado |
|---|---|---|---|---|
| HLS (`.m3u8`) | Sí, módulo HLS | Directo con fixture HLS y flujo real | I-M, Física | En alcance; validación pendiente |
| DASH (`.mpd`) | Sí, módulo DASH | VOD con fixture DASH y flujo real | I-M, Física | En alcance; validación pendiente |
| HTTP progresivo (MP4/WebM) | Sí, extractores progresivos | VOD con MP4 de fixture | I-M | En alcance; validación pendiente |
| RTSP | No evaluado hasta decidir el módulo | Flujo RTSP de prueba solo si se aprueba el alcance | I-M, Física | Decisión de alcance pendiente |
| RTMP | No evaluado hasta decidir el módulo | Flujo RTMP de prueba solo si se aprueba el alcance | I-M, Física | Decisión de alcance pendiente |
| SmoothStreaming | No evaluado hasta decidir el módulo | Validación solo si se aprueba el alcance | I-M, Física | Decisión de alcance pendiente |

## 6. Decisiones de producto/arquitectura (D1–D5)

D1, D2, D3, D4 y D5 tienen decisiones aprobadas: **D1(a)**, **D2(a)**, **D3**, **D4** y **D5 (base)**. Siguen pendientes los detalles de estabilidad/reconfirmación de D1(a), la implementación no verificada de D2(a), los detalles de D3 (preferencias y representación), los detalles de D4 (intervalos, restricciones, reinicio y backoff), los detalles de D5 (inventario, gestión de claves, backup/restauración y recuperación) y la implementación/verificación de D2(a), D3, D4 y D5. Se registran con contexto, opciones aprobadas o no adoptadas, impacto y bloqueos. Las opciones marcadas **inadmisibles** contradicen el README o un ADR aprobado y no deben presentarse como alternativas neutras.

### D1 — Asociación EPG independiente de las generaciones de importación (D1(a) aprobada)

- **Estado:** **D1(a) aprobada; detalles de estabilidad/reconfirmación pendientes.** D2(a), D3, D4 y D5 (base) están aprobadas; sus detalles e implementación/verificación siguen pendientes.
- **Contexto:** ADR-0002:32 fija que el `channel id` de XMLTV tiene ámbito del import del que proviene; ADR-0002:34 define la Asociación como estado de la app, propuesto por heurística y confirmado por el usuario, editable y removible. Una Asociación que apunte a una fila de generación se rompe en cada actualización de guía.
- **Decisión aprobada:** asociación EPG por `(fuente_guia_id, channel_id_texto)`, resuelta contra la generación publicada. **No se crea la entidad `CanalXmltv`.**
- **Reglas aprobadas:** (1) conservar las Asociaciones confirmadas ante ausencias temporales; (2) no reasignar programas automáticamente ante indicios de reutilización del `channel id`; (3) exigir reconfirmación cuando exista ambigüedad.
- **Pendiente:** definición exacta de la huella de identidad, las señales relevantes y los criterios de reconfirmación. No se fijan umbrales ni reglas adicionales.
- **Opciones no adoptadas:** (b) entidad estable de canal XMLTV por fuente de guía, con los programas por generación; (c) re-vincular asociaciones en cada publicación sin confirmación del usuario, que contradice ADR-0002:34 y solo sería admisible con reconfirmación explícita y sin pérdida.
- **Impacto:** esquema v1 (`Asociacion`; **sin `CanalXmltv`**), tareas E5 y cierre de S25–S29.
- **Bloquea:** E1-U3 en la parte de Asociación mientras no se definan la huella, las señales y los criterios de reconfirmación; S27 para cierre end-to-end; S25, S26, S28 y S29 para cierre end-to-end. **No bloquea la representación de la tabla `Asociacion`**, ni S24 (parser), ni las generaciones/publicación de E5-T2, ni la retención de E5-T3.
- **No bloquea:** el parser XMLTV, la importación por lotes ni la retención.

### D2 — Agrupación y persistencia de una resolución que afecta a varias filas (D2(a) aprobada)

- **Estado:** **D2(a) aprobada; implementación no verificada.** D3, D4 y D5 (base) están aprobadas; sus detalles e implementación/verificación siguen pendientes.
- **Contexto:** ADR-0004 ya contempla N filas: A→B libera las N (ADR-0004:41), B→C afecta a todas las implicadas (ADR-0004:43) y m9 prueba N filas (ADR-0004:59). Lo que estaba pendiente no era el modelo de estados ni la regla de cierre, sino **cómo agrupar y persistir una resolución explícita del usuario que afecta a varias filas**, y cómo representar el conjunto B/C.
- **Decisión aprobada:** persistir la resolución **por fila** y permitir que una acción de UI agrupe N filas en una **única transacción atómica**. **No se crea una entidad nueva de resolución ni un registro de auditoría grupal.** Si falla una parte de la operación, se revierte la transacción completa. Las reglas de identidad A/B/C de ADR-0004 se mantienen intactas.
- **Pendiente:** implementación y verificación. No queda una decisión de representación pendiente; la acción de UI que agrupa N filas y la prueba de rollback completo no se declaran ejecutadas.
- **Opciones no adoptadas:** (b) entidad de resolución con pertenencia de N filas; (c) decisión por fila más registro de auditoría de la operación agrupada.
- **Opción inadmisible:** resolución por pares con cierre parcial: contradice ADR-0004:32 (cierre solo por biyección única por cada fila histórica) y ADR-0004:43 (B→C afecta a todas las filas implicadas).
- **Impacto:** representación de la resolución por fila en el esquema; tareas E1-U3, E4-T2/T4 y pruebas de atomicidad.
- **Bloquea:** ya no bloquea la representación de E1-U3; E4-T2/T4 quedan pendientes de implementación y verificación, no de decisión.
- **No bloquea:** S6 ni la conservación ordinaria de Favoritos, Historial y Ocultaciones; tampoco m3, m4 ni m9, que ya están especificadas por ADR-0004 y son validación de E4.

### D3 — Definición funcional de «perfiles de reproducción» (D3 aprobada)

- **Estado:** **D3 aprobada; detalles de preferencias y representación pendientes; implementación no verificada.**
- **Contexto:** `README.md`, «Funcionalidades previstas», punto 1, viñeta 2 («Gestión de múltiples listas y perfiles de reproducción») lo exige; GLOSSARY y los ADR no lo definen.
- **Semántica aprobada:**
  - Un **Perfil** tiene nombre y agrupa Listas M3U.
  - **No se reduce a renombrar la Lista activa**: el Perfil es un concepto funcional propio, no la Lista activa con otro nombre.
  - Conserva las preferencias de reproducción elegidas por el usuario cuando el contenido las admite, incluidas las preferencias de pista de audio y subtítulos.
  - No inventa pistas ni capacidades que el contenido no proporcione.
  - Distingue las preferencias guardadas de las opciones disponibles para el contenido actual.
- **Pendiente:** (1) si las preferencias son globales, por perfil o por lista; (2) el comportamiento cuando una pista preferida no está disponible; (3) la cardinalidad y la representación exacta de la agrupación de Listas. No se deciden por inferencia. La implementación y las pruebas no están verificadas.
- **Opciones no adoptadas:** (b) conjunto de Colecciones/filtros activos con nombre; (c) ambas cosas; (d) «perfil» es la Lista activa y la UI usa ese término. La opción (d) no satisface la semántica aprobada. La opción (a) no se adopta literalmente: la semántica aprobada exige un Perfil con nombre que agrupa Listas M3U; no se afirma que sean solo Listas activas.
- **Impacto:** S2c; S19/S21/S44 en la distinción entre preferencias guardadas y opciones disponibles; UI de E3; `Perfil` en E1-U3/U4; E3-T9; E6-T3; E7-T5.
- **Bloquea:** S2c en sus detalles pendientes; E1-U3 en la representación/cardinalidad de `Perfil`; E3-T9 y E7-T5 en el alcance de preferencias y el fallback. No bloquea S2a.
- **No bloquea:** S2a, la importación ni el catálogo. La selección de pistas cuando el contenido las ofrece puede implementarse sin decidir el alcance de las preferencias, pero S2c no se declara cerrada.

### D4 — Política de actualización automática, intervalos y reintentos (D4 aprobada)

- **Estado:** **D4 aprobada; valores y detalles pendientes; implementación no verificada.**
- **Contexto:** `README.md`, «Funcionalidades previstas», punto 1, viñeta 3 («Actualización manual y automática de listas, con intervalos configurables») y punto 7, viñeta 4 («Preferencias de actualización y almacenamiento local») exigen intervalos configurables; no hay ADR de programación en segundo plano ni de reintentos.
- **Semántica aprobada:**
  - Planificador: **WorkManager**.
  - Actualización automática con **intervalos configurables**.
  - **Reintentos con backoff**.
- **Pendiente:** intervalos por defecto, mínimo y máximo; restricciones de red; comportamiento tras reinicio; detalles de reintentos/backoff que aún no estén definidos. No se fijan valores ni políticas por inferencia.
- **Opciones no adoptadas:** `AlarmManager`; `JobScheduler`; solo actualización manual en primer plano (elimina la actualización automática que exige S3 y recorta alcance). No se adopta backoff exponencial ni restricciones de red concretas por inferencia.
- **Dependencia mutua con D5:** la actualización automática necesita acceso a credenciales en segundo plano; D5 define el cifrado en reposo y la protección de claves. La disponibilidad de credenciales tras reinicio, la gestión/restauración de claves y los reintentos deben resolverse de forma coordinada. D4 no se declara cerrada hasta que D5 defina el acceso a credenciales; D5 no se declara cerrada en sus detalles hasta que D4 defina el comportamiento tras reinicio y los reintentos.
- **Impacto:** dependencias de WorkManager, permisos, ciclo de vida, E4-T7, S3b, S46b y E7-T5; D5 para credenciales en segundo plano.
- **Bloquea:** S3b y S46b en los valores por defecto/mínimo/máximo, restricciones de red, comportamiento tras reinicio y detalles de backoff. No bloquea S3a (actualización manual) ni S46a (almacenamiento y resolución global/por Fuente, ya decidido en ADR-0002:39–40); tampoco impide implementar el planificador con un intervalo explícito de prueba.
- **No bloquea:** actualización manual, catálogo ni importación.

### D5 — Protección y almacenamiento de credenciales de URL (D5 base aprobada)

- **Estado:** **D5 base aprobada; inventario, gestión de claves, backup/restauración y pruebas de recuperación pendientes; implementación no verificada.**
- **Contexto:** `README.md`, «Funcionalidades previstas», punto 8, viñeta 9 («Protección de credenciales y datos sensibles cuando existan») exige protección; ADR-0004:20 prohíbe que una URL con credenciales aparezca en logs o diagnóstico, pero no define almacenamiento en reposo.
- **Semántica aprobada:**
  - Cifrado de **campos sensibles en reposo**, con claves protegidas mediante **Android Keystore**.
  - **Prohibición de credenciales en logs y diagnósticos.**
  - **No se implementa cifrado de base completa.**
  - **No se asume que todas las URL son credenciales** sin una regla explícita.
- **Pendiente:** inventario exacto de campos que se cifrarán; gestión de claves; backup/restauración; pruebas de recuperación. No se fijan algoritmos, formatos ni políticas por inferencia; el inventario exacto sigue pendiente.
- **Opciones no adoptadas:** cifrado de base completa; texto plano con prohibición de logs (no satisface «protección de credenciales» de S57); no persistir credenciales y pedirlas en cada uso (choca con S3/D4).
- **Dependencia mutua con D4:** D5 debe permitir que la actualización automática acceda a credenciales sin interacción; D4 debe definir el comportamiento tras reinicio y los reintentos que afectan la disponibilidad de credenciales. La gestión/restauración de claves y el comportamiento tras reinicio no se deciden por separado.
- **Impacto:** campos sensibles de `Fuente`, `Canal` y `ObservacionPendienteImportacion` (inventario exacto pendiente), logs/diagnóstico, E1-U3/U4, E2-T6, E7-T6 y S53/S57.
- **Bloquea:** E1-U3 en el inventario de campos sensibles y la representación cifrada; S57b en la verificación positiva en reposo; S57c en la regla explícita de qué es credencial. No bloquea S53a/S53b ni S57a (prohibición de credenciales en logs/diagnóstico), que son implementables con la base aprobada.
- **No bloquea:** validación de URL, prohibición de credenciales en logs/diagnóstico ni el resto del esquema no sensible.

### 6.1 Definiciones funcionales pendientes (no son D1–D5)

Estas definiciones no se inventan y su parte independiente no se declara completa. S14 no figura aquí: sus reglas de mayúsculas/acentos, Canales sin nombre y Canales sin grupo se aprobaron en L21; queda su implementación y validación.

- **S11:** cuántos Canales forman el acceso rápido; N=5 es solo el valor del fixture.
- **S17:** qué controles se pueden ocultar o reordenar y cómo se persiste esa personalización.
- **S20:** número exacto de reintentos y política de backoff; solo se prueba el comportamiento mínimo de reintento y error tipado.
- **S44:** qué preferencias de comportamiento, además de selección de pista y `resizeMode`, forman parte del alcance; D3 no decide el alcance global/por perfil/por lista ni el comportamiento cuando la pista preferida no está disponible.
- **S47:** qué opciones de privacidad adicionales existen además del borrado de Historial/Favoritos.
- **S51:** clave, ventana de caché y política de invalidación; solo se prueba el comportamiento de caché con un doble de prueba.

## 7. Políticas de medición (P1–P2)

P1 y P2 están aprobadas como políticas; sus valores numéricos siguen pendientes y, en P2, también el procedimiento de aceptación visual. Ninguna medición se declara validada por esta sección.

### P1 — Rendimiento de listas y catálogo (P1 aprobada; valores numéricos pendientes)

- **Estado:** **P1 aprobada como política híbrida**; los valores numéricos de cada métrica siguen pendientes. La calibración y la validación requieren una implementación y un entorno de medición estable; no se declara rendimiento validado.
- **Métricas:** (1) tiempo de parseo de 10 000 entradas; (2) tiempo de la primera página de búsqueda; (3) tiempo de la consulta paginada; (4) memoria máxima del proceso o heap según el entorno.
- **Política aprobada:**
  - **Límite absoluto por métrica:** cada métrica tiene un valor máximo permitido. El valor numérico de cada límite queda pendiente.
  - **Comparación de regresión contra un baseline versionado:** cada medición se compara contra una referencia registrada y versionada. El criterio numérico de regresión aceptable queda pendiente.
  - **Mediciones p95:** cada métrica se reporta como percentil 95 de las repeticiones, no como un único valor. El número de repeticiones, el calentamiento y el procedimiento exacto quedan pendientes de calibración.
- **Pendiente:** los cuatro valores numéricos (parseo de 10 000 entradas, primera página de búsqueda, consulta paginada y memoria máxima), el criterio numérico de regresión y el procedimiento de medición p95. No se fijan por inferencia.
- **Método:** fixture sintético reproducible de 10 000 entradas; medir las cuatro métricas; en JVM, con el mismo fixture y el mismo comando en local y CI; en Android, con Macrobenchmark o perfil de Studio cuando exista dispositivo. El baseline versionado se registra junto con la versión de JDK/AGP, el fixture y el comando.
- **Evidencia:** comando, versión de JDK/AGP, fixture, baseline comparado, salida cruda con p95 y fecha.
- **Límite:** ningún valor numérico de P1 se usa como criterio de cierre hasta que se calibre y apruebe. S5, S9, S52, E2-T5 y E3-T1 no se declaran cerradas por la sola aprobación de la política.

### P2 — Legibilidad y navegación en TV (P2 aprobada; S32, S33, S38; umbrales y procedimiento pendientes)

- **Estado:** **P2 aprobada como política híbrida para Android TV**; los umbrales numéricos y el procedimiento de aceptación visual siguen pendientes. La implementación y la verificación no están realizadas; no se declara validación visual.
- **Política aprobada:**
  - **Pruebas automatizadas de límites de texto y foco:** verifican límites observables de texto y foco en la UI.
  - **Capturas reproducibles:** cada revisión conserva capturas con resolución, densidad y condiciones registradas.
  - **Revisión visual humana a una distancia equivalente a 10 pies (aprox. 3 m):** es parte obligatoria de la aceptación.
  - **CI por sí sola no demuestra legibilidad real a distancia:** un job verde o una captura automatizada son evidencia parcial; no sustituyen la revisión visual humana.
- **Pendiente:** umbrales numéricos de tamaño de texto, área de foco e indicador de foco, y área táctil móvil; procedimiento de aceptación visual (condiciones, iluminación, tamaño de pantalla, criterios y responsable). No se fijan valores ni criterios por inferencia.
- **Método:** emulador TV 1080p o dispositivo; medir tamaño efectivo de texto y área de foco en dp; capturas reproducibles; revisión visual humana a 10 pies (aprox. 3 m); para táctil, medir el área de los controles. En CI se ejecutan las pruebas automatizadas de límites y se conservan las capturas; CI no reemplaza la revisión humana.
- **Evidencia:** comando o prueba, versión, resolución y densidad, captura, condiciones de la revisión, fecha y resultado. La validación visual solo se registra cuando se ejecute la revisión humana.
- **Límite:** ningún umbral numérico ni validación visual se declara aprobado hasta definir los umbrales y el procedimiento y ejecutar la verificación. S32, S33, S38 y las tareas E7 correspondientes no se declaran cerradas.

## 8. Tareas

Columnas: ID, objetivo, archivos previstos, criterio de aceptación y estado. `Estado` indica si la unidad es ejecutable ya o si está bloqueada por una decisión. Cada criterio de tarea se traza a las filas `S#` de la matriz; el nivel y el procedimiento se toman de la fila trazada. Las rutas con `...`, `*` o `**` son **previstas**, no archivos existentes.

### 8.1 E1 — Fundación verificable

Alcance: build, módulos, derivación de identidad, esquema Room v1, integridad/persistencia, CI y disponibilidad de emuladores. **Sin** transiciones A/B/C, correspondencia, plan/commit de fusión ni ciclo de vida de Observaciones: eso es E4.

| ID | Objetivo | Archivos previstos | Criterio de aceptación | Estado |
|---|---|---|---|---|
| E1-U0 | Documento de seguimiento | `odd/tasks/mvp-copernicotv.md` | Documento releído; evidencia de U0 actualizada; sin Engram por instrucción. **Cerrada solo como prerrequisito documental; no inicia E1** | Cerrada (solo documento) |
| E1-U1 | Esqueleto Gradle, módulos y Hilt | `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`, `gradlew`, `gradlew.bat`, `gradle/wrapper/*`, `core/build.gradle.kts`, `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/.../CoperNicoApplication.kt`, `.gitignore` | `./gradlew --version`, `projects` y `help` responden; `:core` no depende de Android ni de `:app`; Hilt compila y una prueba de componente inyecta una dependencia; todas las versiones tienen fuente registrada | Planificada |
| E1-U2 | Derivación de identidad en `:core` | `core/.../identity/ClaveCanal.kt`, `NormalizacionUrl.kt`, `DerivacionClave.kt` + tests | Casos reproducibles ADR-0004:56: `tvg-id` único, ausente y duplicado dentro de la misma Fuente (el duplicado se invalida como clave y no fusiona los implicados); nombre con espacios y caja variable produce la misma clave normalizada; URL con esquema/host en mayúsculas y percent-encoding inequívoco se normaliza, mientras los parámetros de consulta se conservan; la misma lista en dos órdenes produce la misma clave; declaraciones idénticas en todos sus atributos se persisten una vez y la repetición se registra como informativa; mismo insumo de identidad con `group-title`/`tvg-name` distintos produce un Canal y dos Apariencias, cada una con su Grupo y orden; colisión de clave canónica entre declaraciones no idénticas no se resuelve por posición y queda para la regla de Apariencias/ambigüedad, no para una fusión automática. Prueba además que una URL con credenciales no se registra en logs. **Límite E1/E4:** E1-U2 prueba la derivación y la deduplicación intra-importación; la detección de colisión canónica no es reconciliación entre versiones. Las transiciones, la correspondencia, la fusión y la persistencia de la ambigüedad son E4. Sin imports Android | Planificada |
| E1-U3 | Esquema Room v1 | `app/.../data/db/AppDatabase.kt`, `entities/*.kt`, `dao/*.kt`, `converters/*.kt`, `app/schemas/**` | Esquema v1 verificable y no alternativo. Entidades/tablas obligatorias y campos mínimos: `Fuente` (PK, identidad y URL de Fuente/Lista, estado; columnas de actualización y excepciones de retención con bandera «usar global»; URL y campos sensibles sujetos a D5: cifrado en reposo con claves de Android Keystore; inventario exacto pendiente; no se asume que toda URL sea credencial; no cifrado de base completa); `Lista` (PK, `fuente_id` FK, nombre, orden/estado); `Perfil` (PK, `nombre`; agrupación de Listas M3U; representación/cardinalidad exacta pendiente; no es un renombre de `Lista`); `Canal` (PK; `fuente_id` FK NOT NULL; `nombre` NOT NULL; `url_stream` NOT NULL, con su tratamiento en reposo sujeto a D5 (inventario exacto pendiente; no toda URL es credencial); `logo_url` NULL; `tvg_id` NULL; `clave_canonica` NOT NULL; `clave_unicidad` NULL en ambigüedad; `estado` NOT NULL; decisión/fecha/evidencia; `atributos_extra` (metadatos) NULL; índices no únicos por `(fuente_id, clave_canonica)`, `nombre` y `tvg_id`; índice único incondicional `(fuente_id, clave_unicidad)` (SQLite trata NULL como distinto, así que las filas ambiguas conviven; ADR-0004:28 no usa índice parcial); no se impone unicidad de `clave_canonica` porque las filas ambiguas pueden repetirla); `Apariencia` (PK; `lista_id` FK NOT NULL; `canal_id` FK NOT NULL; `grupo_id` FK NULL; `orden` NOT NULL; `group_title` NULL; `tvg_name` NULL; **sin unicidad por Lista/Canal**: ADR-0004:18 exige varias Apariencias del mismo Canal en la misma Lista cuando cambian `group-title` o `tvg-name`; índices no únicos `(lista_id, canal_id)`, `(grupo_id)` y `(lista_id, orden)`; la unicidad exacta de la tupla de presentación no se fija como restricción DB porque el ADR no define su semántica con NULL; si se define una regla de igualdad, las alternativas no elegidas son: (i) índice único sobre una tupla canónica de presentación con NULL normalizado; (ii) índice único por `(lista_id, canal_id, group_title, tvg_name)` con una regla explícita de NULL; (iii) deduplicación en importación sin índice único. Ninguna se adopta; la tabla no impone índice único y la decisión queda pendiente, sin impedir la representación múltiple); `Grupo` (PK, `lista_id`, nombre; unicidad por Lista/nombre); `Favorito` (PK, `canal_id` único FK); `OcultacionCanal` (PK, `canal_id` único FK); `OcultacionGrupo` (PK, `grupo_id` único FK); `Historial` (PK, `canal_id`, fecha; una fila por Canal); `Coleccion` (PK, nombre) y `ColeccionCanal` (`id` PK; `coleccion_id`, `canal_id`, `posicion`; índice no único `(coleccion_id, canal_id)`; no se impone una unicidad global de `posicion` ni una restricción de unicidad sobre `(coleccion_id, posicion)`; la tabla debe permitir dos pertenencias de la misma Colección en posiciones distintas y una pertenencia duplicada idéntica antes de la consolidación informada de ADR-0004:59(m5); cualquier unicidad de posición solo podría añadirse con una razón concreta compatible con m5, no como dependencia de D2); `FuenteGuia` (PK, identidad/URL, puntero `generacion_publicada_id`); `GeneracionGuia` (PK, `fuente_guia_id`, versión/import, estado staging/publicada/abandonada, timestamps); `Programa` (PK, `generacion_id`/`fuente_guia_id`, `channel_id_texto`, título, inicio, fin, descripción; referencia el import/generación de origen); `Asociacion` (PK, `canal_id`, `fuente_guia_id`, `channel_id_texto`, estado de confirmación/edición, timestamps; **D1(a) aprobada: asociación por `(fuente_guia_id, channel_id_texto)`; no se crea `CanalXmltv`; la huella, las señales y los criterios de reconfirmación siguen pendientes**); `EstadoActualizacion` (PK, Fuente/Lista, causa, versión/import, timestamps) con tablas hijas `DetalleAusencia` (estado, entrada/Canal, texto «no declarada en esta importación») y `EvidenciaColision` (estado, `clave_canonica`, insumos, fecha, versión); `ObservacionPendienteImportacion` (PK; `fuente_id` FK; `import_origen`; `nombre`, `url`, `tvg_id` y `grupo` de origen, NULL cuando la fuente no los declara, con `url` sujeta a D5 (inventario exacto pendiente); `clave_canonica`; timestamps de primera/última observación; referencia al conjunto B/C; D2(a) aprobada: resolución por fila, sin entidad nueva de resolución ni auditoría grupal; sin FK a Canal). Retención: claves globales en DataStore para ventana, máximo por canal y presupuesto total; excepciones por Fuente como columnas de `Fuente` con bandera de existencia, no valores mágicos (ADR-0002:38–40). Además, `exportSchema`; no se añaden índices únicos no derivados de ADR-0004. La ausencia no crea `EntradaNoDeclarada`. D2(a) aprobada: la resolución se persiste por fila y la acción de UI agrupa N filas en una transacción atómica; no se crea entidad nueva de resolución ni auditoría grupal; si falla una parte, se revierte todo. La implementación no está verificada. Asociación con D1(a) aprobada y sin `CanalXmltv`; sus detalles de estabilidad/reconfirmación siguen pendientes; campos sensibles sujetos a D5 (inventario y gestión de claves pendientes); `Perfil` D3 aprobada: nombre y agrupación de Listas M3U; representación/cardinalidad exacta y preferencias pendientes; no se reduce a renombrar la Lista activa. Las preferencias de D3 se resuelven en E7-T5. No se inventa un esquema alternativo | **Bloqueada por D5 (inventario de campos sensibles, gestión de claves, backup/restauración y pruebas de recuperación); D1(a) aprobada con detalles pendientes; D2(a) aprobada, implementación no verificada; D3 aprobada con representación/cardinalidad y preferencias pendientes; unicidad exacta de presentación pendiente** |
| E1-U4 | Integridad y persistencia | `app/src/test/.../db/*Test.kt` | Duplicado no ambiguo rechazado; N filas con `clave_unicidad` NULL aceptadas; el escritor rechaza A con NULL y B/C con no-NULL (invariante en ambos sentidos); FK íntegras; `Perfil` existe con nombre; la agrupación de Listas M3U se prueba solo con la representación que se defina; no se reduce a renombrar la Lista activa; la cardinalidad/representación pendiente no se prueba como definida; `Apariencia` acepta dos filas del mismo Canal/Lista con `group-title`/`tvg-name` distintos; `ColeccionCanal` acepta dos filas del mismo Canal en posiciones distintas y no rechaza una pertenencia duplicada idéntica antes de la consolidación informada de E4-T4/m5; rollback de una transacción genérica; JSON exportado y `PRAGMA index_list/index_info` verificados. `EstadoActualizacion` persiste causa, versión/import, ausencias y evidencia de colisión; `ObservacionPendienteImportacion` persiste origen, insumos, clave y timestamps, y su tabla no declara FK a `Canal`. `FuenteGuia`, `GeneracionGuia`, `Programa` y `Asociacion` existen con sus FK y el puntero de publicación; `Asociacion` usa la representación D1(a) `(fuente_guia_id, channel_id_texto)` y no existe `CanalXmltv`; las reglas de estabilidad/reconfirmación no se prueban aquí hasta que se definan; las columnas de retención por Fuente y la bandera «usar global» se leen/escriben. Sin pruebas de A→B ni de fusión. La protección en reposo no se prueba aquí hasta que D5 defina inventario de campos sensibles, gestión de claves, backup/restauración y pruebas de recuperación; no se asume cifrado de base completa ni que toda URL sea credencial | **Bloqueada por E1-U3** |
| E1-U5 | CI nativa | `.github/workflows/ci.yml`, `detekt.yml`, `.editorconfig` | `jvm-tests` ejecuta JUnit y Robolectric; `static-analysis` ejecuta ktlint y detekt; `apk` compila `:app` y publica artifact; `instrumented-mobile` arranca el emulador y ejecuta el smoke; `instrumented-tv` ejecuta el canario significativo de E1-C (importar M3U, abrir catálogo, navegar con foco/D-pad) y debe estar verde para cerrar E1; si un job falla, el workflow falla; run identificado por SHA | Planificada; E1-C aprobada, ejecución pendiente |
| E1-U6 | Emuladores y trial TV | `.github/workflows/tv-emulator-trial.yml`; jobs `instrumented-mobile`, `instrumented-tv` | Arranque de emulador móvil y TV; registro de KVM, imagen **candidata** y arranque. `instrumented-mobile` es obligatorio en el cierre de E1. `instrumented-tv` ejecuta el canario significativo de E1-C (importar una lista M3U de prueba, abrir el catálogo y navegar con foco/D-pad), no solo el arranque, y debe estar verde para cerrar E1. Si el emulador TV o el entorno requerido no funciona, se documenta el bloqueo y E1 permanece abierto; no se simula éxito ni se sustituye la prueba sin aprobación explícita | Planificada; E1-C aprobada, ejecución pendiente |
| E1-U7 | Cierre y evidencia | `AGENTS.md`, este documento | Evidencia por unidad (archivos, diff, comandos, CI); corrección de la afirmación sobre `odd/tasks/` y el plan E1–E7; sin lógica de E4 en el diff | Planificada; `AGENTS.md` requiere autorización específica |

#### E1-C — Criterio de cierre aprobado (opción B)

- **Decisión aprobada:** E1 requiere pruebas instrumentadas en Android TV exitosas con un canario significativo:
  1. Importar una lista M3U de prueba.
  2. Abrir el catálogo.
  3. Navegar usando foco/D-pad.
- **No basta** con demostrar que la app arranca.
- **Bloqueo:** si el emulador TV o el entorno requerido no funciona, se documenta el bloqueo y E1 permanece abierto. No se simula éxito ni se sustituye la prueba sin aprobación explícita.
- **Estado de ejecución:** decisión aprobada; pruebas no ejecutadas; resultados no disponibles. `instrumented-tv` no se declara verde.
- **Referencia:** ADR-0005:28 permite marcar las verificaciones de `instrumented-tv` como pendientes-documentadas mientras no se confirme la imagen; E1-C exige verde para cerrar E1, así que E1 permanece abierto si el entorno falla. ADR-0005:29 exige interacciones significativas en las pruebas instrumentadas. El ADR no se modifica.

**Notas E1.** `E1-U0` está cerrada **solo como prerrequisito documental**; E1 sigue sin iniciar y U1 no está autorizada. La autorización para editar `AGENTS.md` es una dependencia separada de E1-U7 y no se satisface con U0. Las rutas con `...`, `*` o `**` son **previstas**, no existentes; se verificarán al crear los archivos. **E1-C (aprobada, opción B; ejecución pendiente):** E1 exige `instrumented-tv` verde con el canario significativo descrito arriba; no se acepta solo el arranque. Si el emulador o el entorno falla, se documenta el bloqueo y E1 permanece abierto; no se simula ni se sustituye sin aprobación explícita. La contradicción anterior entre E1-U6 y ADR-0005:29 queda resuelta por esta decisión; ADR-0005 no se modifica.

### 8.2 E2 — Importación M3U/M3U8 y catálogo base

| ID | Objetivo | Criterio de aceptación |
|---|---|---|
| E2-T1 | Parser M3U/M3U8 streaming | Tablas: mínimo, atributos, `EXTGRP`, comillas, comas en atributos, BOM, CRLF/LF, basura intercalada, `EXTINF` sin URL; fixtures M3U y M3U8 separados. Límites ADR-0006:21–24: línea que excede el máximo configurable se descarta con error tipado y la lista continúa; por encima del máximo configurable de atributos, los extra no se incorporan, se registran como informativo no-fatal y se conservan los ya parseados; ambos límites son configurables; los valores por defecto documentados por el ADR (4 KiB y 64) se prueban como defaults, sin inventar otros; los errores se agregan por código con conteo total y muestra acotada de ubicaciones; ninguna lista válida se rechaza por número de canales |
| E2-T2 | Alta de Fuente y Lista (archivo/URL) | URL http/https validada; archivo local vía SAF `content://` con permiso persistido; errores tipados; Estado de Actualización actualizado |
| E2-T3 | Persistencia de catálogo, Grupo y Apariencia | Sin duplicar Canales entre Listas; Grupo con ámbito de Lista; orden por Apariencia; Hilt usado para repositorios |
| E2-T4 | Metadatos y logos | Logo, nombre y atributos disponibles persistidos; ausencia no rompe |
| E2-T5 | Rendimiento y errores | 10 000 entradas con medición P1 conforme a §7 (límite absoluto, regresión contra baseline versionado y p95); valores numéricos pendientes; timeout y 4xx/5xx sin destruir catálogo previo. No se declara cerrada hasta calibrar y aprobar los valores numéricos. |
| E2-T6 | Protección de credenciales en reposo | Cifrado de campos sensibles con claves protegidas mediante Android Keystore; prohibición de credenciales en logs, diagnóstico, errores y textos copiables; S57a/S57b; D5 base aprobada. Pendientes: inventario exacto de campos, gestión de claves, backup/restauración y pruebas de recuperación. No se implementa cifrado de base completa ni se asume que toda URL sea credencial. No se declara implementado ni probado |

### 8.3 E3 — Organización y estado de usuario

| ID | Objetivo | Criterio de aceptación |
|---|---|---|
| E3-T1 | Búsqueda y filtros | Búsqueda paginada y filtros combinables con 10 000 Canales; medición P1 conforme a §7 (límite absoluto, regresión contra baseline versionado y p95); valores numéricos pendientes; no se declara cerrada hasta calibrarlos y aprobarlos. |
| E3-T2 | Favoritos | Único por Canal; sobrevive a Actualización |
| E3-T3 | Historial | Una fila por Canal con fecha de última reproducción; orden por fecha; acceso rápido; base de S14g |
| E3-T4 | Ocultaciones | Canal y Grupo con FK propia; ocultar Grupo no afecta a otras Listas |
| E3-T5 | Colecciones | Orden propio independiente de la Lista; pertenencia única por Colección tras la consolidación, permitiendo antes de resolver las dos posiciones de ADR-0004:59(m5). Forma parte de la cobertura aprobada de `README.md:38`/`:84` (orden independiente de Colecciones); su cierre requiere implementación y validación, no solo la decisión de alcance. |
| E3-T6 | UI de catálogo móvil | Navegación táctil; CRUD de Lista y Canal; muestra «Sin nombre» y la sección «Sin grupo» según S14; instrumentado |
| E3-T7 | Ordenación del catálogo y preferencias por Lista | Modos A/B/C y dirección; canales nunca reproducidos al final; desempates deterministas con acentos; comparación reproducible JVM/Android con tabla de orden español fija en `:core` (parte de esta tarea; no crea tarea ni dependencia nueva), sin `Locale.getDefault()` ni `Collator` dependiente del dispositivo; canales sin nombre al final y «Sin nombre» solo de presentación; en Modo B, sección visual «Sin grupo» al final sin Grupo ficticio; preferencia independiente por Lista en DataStore (dueña de la persistencia; E7-T5 la consume); no modifica Apariencia/Colección ni identidad; S14e–S14m. La combinación de A/B/C y direcciones, orden manual de Apariencias (E3-T8), orden independiente de Colecciones (E3-T5) y preferencia por Lista persistida (esta tarea) es la cobertura aprobada de `README.md:38`/`:84`; no se agregan funciones implícitas. Las reglas de comparación, Canales sin nombre y Canales sin grupo están aprobadas; su implementación y validación siguen pendientes. Depende de E3-T3 y E3-T6; no bloquea E3-T5; no se declara cerrada hasta implementar y validar |
| E3-T8 | Reordenamiento manual de Apariencias | Reordenar Apariencias dentro de una Lista desde la UI; reutilizar la persistencia de `Apariencia.orden` definida en E2-T3, sin crear una segunda ruta; aislar por Lista con L1/L2 y tres Canales compartidos (C1, C2, C3); no modifica `ColeccionCanal.posicion` ni la preferencia de ordenación A/B/C; S14a, S14c, S45a–S45c. Forma parte de la cobertura aprobada de `README.md:38`/`:84` (orden manual de Apariencias); su cierre requiere implementación y validación, no solo la decisión de alcance. Depende de E3-T6; referencia contractual con E2-T3; no duplica E3-T5 (Colecciones) ni E3-T7 (ordenación del catálogo) |
| E3-T9 | Perfiles de reproducción | Perfil con nombre que agrupa Listas M3U; no se reduce a renombrar la Lista activa. Conserva preferencias de reproducción elegidas por el usuario cuando el contenido las admite, incluidas las preferencias de audio y subtítulos; no inventa pistas ni capacidades; distingue preferencias guardadas de opciones disponibles para el contenido actual. Depende de E1-U3 para `Perfil`; la integración de preferencias de pista depende de E6-T3 y E7-T5. El alcance de preferencias (global/por perfil/por lista) y el comportamiento cuando la pista preferida no está disponible quedan pendientes de D3 y bloquean S2c. No se declara implementado ni probado. |

### 8.4 E4 — Actualización, reconciliación y fusión

| ID | Objetivo | Criterio de aceptación |
|---|---|---|
| E4-T1 | Transiciones A/B/C con N filas | A→B, B→A, B→C, C→A y C→B en transacción única; A→B libera las N antes de escribir evidencia; C→B no colisiona porque `clave_unicidad` ya es NULL; fallo revierte todo. Cubre m2 y m9 y el caso ADR-0004:57(b): dos Canales previamente distintos que reducen a la misma clave canónica quedan ambos en `resolución requerida`, sin fusión automática. Prueba reproducible: fixture F57b con dos filas históricas distintas y una nueva clave común; ejecutar la Actualización; comprobar que ambas filas conservan PK y relaciones y que el Estado registra la ambigüedad. **No** cubre (a), (c) ni (d), que no son transiciones y se asignan a E4-T2 |
| E4-T2 | Evidencia y correspondencia entre filas históricas y declaraciones nuevas | Evidencia por fila; biyección única cierra; conteo, orden y coincidencia parcial no cierran; m3 y m4. Casos ADR-0004:57 asignados aquí: (a) reordenar una Lista con declaraciones idénticas conserva la Clave y Favoritos/Historial/Ocultaciones/Colecciones; (c) una Apariencia que la Fuente deja de declarar no elimina el Canal ni sus relaciones; (d) la resolución del usuario persiste en Actualizaciones posteriores. m8: una entrada ausente que reaparece conserva la PK interna y las relaciones, no duplica la fila y sigue las reglas de correspondencia única o resolución explícita. Prueba reproducible: F57a (mismo conjunto en orden permutado), F57c (importación sin una Apariencia), F57d (resolución confirmada y nueva Actualización) y F57m8 (importar F1, Actualizar sin C, Actualizar con C); comparar PK, relaciones y conteo de filas. D2(a) aprobada: la resolución del conjunto B/C se persiste por fila; la acción de UI agrupa N filas en una única transacción atómica; sin entidad nueva de resolución ni auditoría grupal; si falla una parte, rollback completo; las reglas A/B/C de ADR-0004 no cambian. Implementación y prueba pendientes. Prueba pendiente: confirmar la resolución de N filas, forzar un fallo parcial y comprobar rollback completo, persistencia por fila y ausencia de entidad/auditoría grupal |
| E4-T3 | Observaciones pendientes de importación | Crear, deduplicar por (Fuente, clave canónica), conservar y resolver: asignar como evidencia, promover a Canal nuevo o descartar; m6 y m7; nunca asignación implícita |
| E4-T4 | Plan de fusión y taxonomía de conflictos | Plan calculado antes de confirmar; duplicados sin pérdida consolidados; conflictos con pérdida (Ocultación, EPG, posición de Colección) presentados; Historial completo; m5. D2(a) aprobada: la resolución se persiste por fila; la acción de UI agrupa N filas en una única transacción atómica; sin entidad nueva de resolución ni auditoría grupal; si falla una parte, rollback completo; las reglas A/B/C de ADR-0004 no cambian. Implementación y prueba pendientes. Prueba pendiente: confirmar N filas y forzar un fallo parcial; verificar rollback completo, persistencia por fila y ausencia de entidad/auditoría grupal |
| E4-T5 | Commit de fusión | Una transacción traslada relaciones, retira la fila perdedora, fija `clave_unicidad` y registra la decisión; fallo revierte todo; m2. ADR-0004:58(v): prueba reproducible con F58v: tras retirar la PK X, crear un Canal nuevo, cerrar y reabrir la base y comprobar que la PK nueva no es X. ADR-0004:42: si la clave final choca con una fila no ambigua ajena, la transacción se aborta y el conflicto vuelve a B, sin cambiar relaciones, estado ni `clave_unicidad`; verificar con una fila ajena preparada |
| E4-T6 | Actualización manual y conservación | Favoritos, Historial, Ocultaciones, Colecciones y preferencias intactos; S6a. No bloqueado por D2 ni D4 |
| E4-T7 | Actualización automática | WorkManager; intervalos configurables y reintentos con backoff según D4; S3b, S6b y S46b. Pendientes: intervalos por defecto/mínimo/máximo, restricciones de red, comportamiento tras reinicio y detalles de backoff. Depende de D5 para el acceso a credenciales en segundo plano. No se declara validado por la actualización manual de E4-T6 ni por esta corrección |
| E4-T8 | Indicadores de estado | Carga, actualización y disponibilidad separadas; S7 |

### 8.5 E5 — EPG XMLTV

E5 **no se declara validada** mientras sigan pendientes la huella de identidad, las señales relevantes y los criterios de reconfirmación de D1(a). La representación aprobada es la asociación por `(fuente_guia_id, channel_id_texto)`, sin `CanalXmltv`. S24, E5-T1, E5-T2 y E5-T3 son implementables y verificables sin D1(a); S25–S29 pueden tener pruebas de parser/generación con fixtures, pero su cierre end-to-end queda pendiente de esos detalles. S27 no está completamente bloqueada en su representación, pero su cierre end-to-end sigue pendiente.

| ID | Objetivo | Criterio de aceptación |
|---|---|---|
| E5-T1 | Parser XMLTV streaming | XML válido, malformado e incompleto. ADR-0003:16: un elemento recuperable genera error tipado con su ubicación y el parseo continúa; un error global (encoding, EOF abrupto, raíz inválida) reporta error de importación con cantidad de elementos recuperados y descartados. Cancelación cooperativa: staging descartado y guía publicada intacta. S24; sin dependencia de D1(a) |
| E5-T2 | Generaciones y publicación | Éxito publica atómicamente; fallo conserva la guía anterior; S29 en su parte de publicación; sin dependencia de D1(a) |
| E5-T3 | Retención | ADR-0002:39–40: ventana temporal, máximo por canal y presupuesto total configurables; los valores globales de DataStore son el default; una excepción por Fuente solo aplica si existe, y «usar global» devuelve el global; cambiar el global no corrompe excepciones; los tres defaults están documentados y probados. Sin dependencia de D1(a) |
| E5-T4 | Asociación configurable | Heurística, confirmación, edición y remoción persistentes; S27. D1(a) aprobada: `(fuente_guia_id, channel_id_texto)`, sin `CanalXmltv`; las reglas de estabilidad/reconfirmación siguen pendientes. **Cierre end-to-end bloqueado por esos detalles** |
| E5-T5 | UI de guía | Actual/siguiente y vista temporal con Paging; S25, S26 y S28. **Cierre end-to-end bloqueado por los detalles pendientes de D1(a)** |

### 8.6 E6 — Reproductor Media3

| ID | Objetivo | Criterio de aceptación |
|---|---|---|
| E6-T1 | Sesión Media3 y fuentes | Directo y VOD; HLS, DASH y progresivo según sección 5 |
| E6-T2 | Controles, pantalla y aspecto | Play/pause, seek, siguiente/anterior; fullscreen, rotación y `resizeMode` sin perder sesión |
| E6-T3 | Pistas | Audio y subtítulos seleccionables; ausencia no rompe. D3 aprobada: la preferencia guardada no crea pistas ni capacidades y se distingue de las opciones disponibles para el contenido actual; el fallback cuando la pista preferida no está disponible sigue pendiente de D3 |
| E6-T4 | Errores y reconexión | Reintento y recuperación con errores inyectados; interrupciones en S42 |
| E6-T5 | Diagnóstico | Protocolo, pistas, buffer y último error; URL saneada |
| E6-T6 | Zapeo y volumen | Volumen, siguiente/anterior y cambio sin volver al Catálogo |

### 8.7 E7 — Android TV, móvil y preferencias

| ID | Objetivo | Criterio de aceptación |
|---|---|---|
| E7-T1 | Shell Compose for TV | Navegación con DPad; un solo foco visible. P2 aprobada: pruebas automatizadas de foco, captura reproducible y revisión visual humana a 10 pies (aprox. 3 m); umbrales de área e indicador de foco y procedimiento de aceptación visual pendientes; no se declara cerrada. |
| E7-T2 | Canarios de foco y DPad | Importar → abrir catálogo → foco en primer Canal; recorrido completo de DPad; I-TV. P2 aprobada: pruebas automatizadas de límites de texto y foco, capturas reproducibles y revisión visual humana a 10 pies (aprox. 3 m); CI sola no demuestra legibilidad real a distancia; umbrales y procedimiento de aceptación visual pendientes; no se declara cerrada. |
| E7-T3 | Adaptación móvil | Layouts por tamaño; Material 3, insets y back predictivo. P2: área táctil móvil medida y registrada; umbral numérico y procedimiento de aceptación visual pendientes; no se declara cerrada. |
| E7-T4 | Temas | Claro y oscuro sin perder estado |
| E7-T5 | Preferencias | DataStore para preferencias; consume la preferencia por Lista (ordenación) de E3-T7; por Fuente en Room con «usar global». D3 aprobada: conserva preferencias de reproducción elegidas, incluidas las preferencias de audio y subtítulos, cuando el contenido las admite; distingue guardadas de disponibles; no inventa pistas. El alcance de las preferencias de reproducción (global/por perfil/por lista) y el fallback cuando la pista preferida no está disponible quedan pendientes; no se fija «global» para D3 por inferencia. D4 aprobada: los intervalos y reintentos configurables se persisten para E4-T7; valores por defecto/mínimo/máximo, restricciones de red, comportamiento tras reinicio y detalles de backoff quedan pendientes; no se fijan valores. No implementa de nuevo la preferencia por Lista de ordenación |
| E7-T6 | Privacidad, interrupciones y ciclo de vida | Borrado de Historial/Favoritos; audio focus, permisos y ciclo de vida; S47 y S42. D5: la UI no muestra la credencial completa según la regla explícita que defina D5; consume E2-T6; inventario, gestión de claves, backup/restauración y recuperación pendientes; no se asume que toda URL sea credencial |
| E7-T7 | Verificación física | Checklist con procedimiento, resultado esperado y estado; S33, S35, S36 y S42. P2 aprobada: incluye capturas reproducibles y revisión visual humana a 10 pies (aprox. 3 m); el procedimiento de aceptación visual sigue pendiente; no se declara cerrada ni validada visualmente por CI. |

## 9. Criterios de aceptación

### 9.1 Criterios globales

1. Ninguna función del README se retira ni se aplaza: E1–E7 ordenan, no recortan.
2. Ningún comportamiento se declara validado solo por compilar.
3. Toda verificación física se registra con procedimiento, resultado esperado y estado; una pendiente no es verde.
4. Las pruebas se ejecutan contra estado aislado cuando mutan datos; el estado de prueba vive fuera del worktree.
5. Cada cambio de esquema posterior a v1 lleva migración explícita, JSON exportado y prueba de migración con el esquema anterior real. En v1 no hay migración previa: se verifican el JSON y `PRAGMA index_list/index_info`; la prueba de migración se ejecuta en la primera unidad que introduzca un cambio post-v1.
6. Toda decisión pendiente se resuelve por el propietario del producto, nunca por inferencia del agente.
7. Cada unidad registra archivos modificados, diff, comandos ejecutados y referencia de CI cuando aplique.
8. E5 no se declara validada mientras sigan pendientes la huella de identidad, las señales relevantes y los criterios de reconfirmación de D1(a); sus pruebas con fixtures no sustituyen el cierre end-to-end.
9. Cada fila de la matriz identifica nivel, aserción y procedimiento reproducible; `S55` y `K14` describen evidencia de CI, no sustituyen el nivel que ejecuta el comportamiento. Las rutas con `...`, `*` o `**` son previstas hasta que los archivos existan.
10. Las filas dependientes de P1/P2, de los detalles pendientes de D1(a), de la implementación no verificada de D2(a), de los detalles pendientes de D3, D4 y D5 o de una definición funcional pendiente no se declaran completas por aprobar solo su parte independiente; en particular, las filas dependientes de P1 no se declaran completas mientras falten sus valores numéricos (límites absolutos, criterio de regresión y procedimiento p95), y las dependientes de P2 no se declaran completas mientras falten sus umbrales numéricos y el procedimiento de aceptación visual, y un job de CI no se acepta como validación visual; S57 no valida la protección en reposo mientras D5 no defina inventario de campos sensibles, gestión de claves, backup/restauración y pruebas de recuperación; S3b y S46b no se declaran completas mientras D4 no defina intervalos, restricciones, comportamiento tras reinicio y detalles de backoff; y S14 no se declara completa hasta implementar y validar las reglas aprobadas de mayúsculas/acentos, Canales sin nombre y Canales sin grupo; no queda una definición funcional pendiente para S14; el alcance genérico de `README.md:38`/`:84` ya está aprobado y no se usa como bloqueo adicional.

### 9.2 Criterio de finalización de E1

E1 no se cierra mientras U3 o U4 estén bloqueadas, mientras `instrumented-mobile` no esté verde o mientras `instrumented-tv` no esté verde con el canario significativo de E1-C. El cierre exige:

1. `./gradlew --version`, `projects` y `help` responden, con versiones y fuentes registradas.
2. `:core:test` se ejecutó en local; si el entorno lo impide, el mismo comando se ejecutó en `jvm-tests` y se registró el run. Sin una de las dos evidencias, la unidad no se cierra.
3. Suites de derivación de identidad verdes con los casos de ADR-0004:56 asignados a E1-U2: `tvg-id` único/ausente/duplicado, espacios/caja, normalización de esquema/host y percent-encoding, query preservada, orden permutado, declaraciones idénticas, mismo insumo con Grupos distintos y colisión canónica frente a declaración idéntica.
4. Room v1 con las entidades y campos de ADR-0002/0003/0004 que no dependen del inventario de campos sensibles de D5 ni de los detalles pendientes de D1(a), incluidos `Perfil`, `Programa`, `FuenteGuia`, generaciones/publicación, `Asociacion` con la representación D1(a), la resolución por fila de D2(a) y campos de retención, `exportSchema`, índice único e invariante de unicidad. **D1(a) está aprobada: `Asociacion` se representa por `(fuente_guia_id, channel_id_texto)` y no se crea `CanalXmltv`; la huella, las señales y los criterios de reconfirmación siguen pendientes, por lo que la parte de Asociación no se declara cerrada.** **D2(a) está aprobada: la resolución se persiste por fila, sin entidad nueva de resolución ni auditoría grupal; la acción de UI que agrupa N filas debe ser una transacción atómica y revertir todo si falla; su implementación no está verificada.** E1 **no se cierra con un esquema parcial**: U3 debe estar desbloqueada y ejecutada con las decisiones que afecten al esquema resueltas. D3 está aprobada: el esquema v1 incluye `Perfil` con nombre y agrupación de Listas M3U; la representación/cardinalidad exacta de la agrupación y el alcance de las preferencias (global/por perfil/por lista) siguen pendientes, al igual que el comportamiento cuando una pista preferida no está disponible. S2c no se declara cerrada. D5 base aprobada: cifrado de campos sensibles en reposo con claves protegidas mediante Android Keystore; prohibición de credenciales en logs/diagnósticos; inventario exacto, gestión de claves, backup/restauración y pruebas de recuperación pendientes; no se implementa cifrado de base completa ni se asume que toda URL sea credencial. E1-U3 sigue bloqueada por ese inventario y la gestión de claves. Las partes dependientes de los detalles de D1(a), de la implementación no verificada de D2(a), de los detalles pendientes de D3, D4 y D5 no se simulan ni se declaran cerradas.
5. Pruebas de integridad observadas: duplicado rechazado, N NULL aceptados, invariante en ambos sentidos, FK íntegras, `ColeccionCanal` con dos posiciones y duplicado idéntico admitido antes de la consolidación de E4, y rollback genérico. Sin A→B ni fusión.
6. `jvm-tests`, `static-analysis`, `apk`, `instrumented-mobile` e `instrumented-tv` verdes en un run identificado de Actions, con artifacts. `instrumented-tv` debe ejecutar el canario de E1-C: importar una lista M3U de prueba, abrir el catálogo y navegar con foco/D-pad. Un smoke de arranque no satisface E1-C. Si el emulador o el entorno falla, se documenta el bloqueo y E1 permanece abierto; no se simula éxito ni se sustituye la prueba sin aprobación explícita.
7. `tv-emulator-trial` con resultado registrado: KVM disponible o bloqueo exacto, imagen candidata, arranque y smoke. **E1-C aprobada (opción B):** E1 exige `instrumented-tv` verde con el canario significativo; el trial no sustituye esa prueba. Si el emulador o el entorno no funciona, se registra el bloqueo y E1 permanece abierto.
8. Este documento releído, con evidencia actualizada por unidad.
9. `AGENTS.md` sin afirmaciones falsas sobre `odd/tasks/` y sobre el plan E1–E7, cuando se autorice su edición. Esta autorización es una dependencia separada de E1-U7; no se obtiene de U0.
10. El diff de E1 no contiene transiciones A/B/C, correspondencia, plan/commit de fusión ni ciclo de vida de Observaciones.

## 10. Evidencias, CI y verificaciones físicas

### 10.1 Registro por unidad

Toda unidad que se ejecute añade una fila. La fila `E1-U0` de esta tabla es evidencia; no cuenta como una tarea adicional de §8:

| Unidad | Fecha | Archivos modificados | Diff | Comandos ejecutados | Resultado | CI run | Estado |
|---|---|---|---|---|---|---|---|
| E1-U0 | 2026-10-23 | `odd/tasks/mvp-copernicotv.md` | 364 líneas, 28 119 bytes; creación (sin ruta/hash permanente) | `sha256sum`, `git status`, `grep` de conteos | 57/57/45/5; sin baseline de hashes verificable | — | Cerrada solo como documento |
| Corrección U0 (L5–L9) | 2026-10-23 | `odd/tasks/mvp-copernicotv.md` | Ver L5–L9; sin baseline, no se verificó diff | `sha256sum`, `git status`, conteos | Sin informe de cierre; estado observado en 10.6 | — | Cerrada solo como documento |
| Corrección U0 (L10) | 2026-10-23 | `odd/tasks/mvp-copernicotv.md` | Ver L10; sin baseline, no se verificó diff | `sha256sum`, `git status`, conteos | Pendiente de revisión; sin pruebas ejecutadas | — | Abierta solo como documento |
| Corrección U0 (L11) | 2026-10-23 | `odd/tasks/mvp-copernicotv.md` | Ver L11; sin baseline, no se verificó diff | `sha256sum`, `wc`, `sed`, `grep`, `find`, `git status`, validaciones de conteo | H3 y E3-T8 corregidos; sin pruebas ejecutadas | — | Abierta solo como documento |
| Corrección U0 (L12) | 2026-10-23 | `odd/tasks/mvp-copernicotv.md` | Ver L12; sin baseline, no se verificó diff | `sha256sum`, `wc`, `sed`, `grep`, `git status`, validaciones de conteo | S14a con tres Canales corregido; sin pruebas ejecutadas | — | Abierta solo como documento |
| Corrección U0 (L13) | 2026-10-23 | `odd/tasks/mvp-copernicotv.md` | Ver L13; sin baseline, no se verificó diff | `sha256sum`, `wc`, `sed`, `grep`, `git status`, validaciones de conteo | D1(a) registrada; sin pruebas ejecutadas | — | Abierta solo como documento |
| Corrección U0 (L14) | 2026-10-23 | `odd/tasks/mvp-copernicotv.md` | Ver L14; sin baseline, no se verificó diff | `sha256sum`, `wc`, `sed`, `grep`, `git status`, validaciones de conteo | D2(a) registrada; sin pruebas ejecutadas | — | Abierta solo como documento |
| Corrección U0 (L15) | 2026-10-23 | `odd/tasks/mvp-copernicotv.md` | Ver L15; sin baseline, no se verificó diff | `sha256sum`, `wc`, `sed`, `grep`, `git status`, validaciones de conteo | D3 registrada; sin pruebas ejecutadas | — | Abierta solo como documento |
| Corrección U0 (L16) | 2026-10-23 | `odd/tasks/mvp-copernicotv.md` | Ver L16; sin baseline, no se verificó diff | `sha256sum`, `wc`, `sed`, `grep`, `git status`, validaciones de conteo | D4 y D5 (base) registradas; sin pruebas ejecutadas | — | Abierta solo como documento |
| Corrección U0 (L17) | 2026-10-23 | `odd/tasks/mvp-copernicotv.md` | Ver L17; sin baseline, no se verificó diff | `sha256sum`, `wc`, `sed`, `grep`, `git status`, validaciones de conteo | P1 registrada como política; valores numéricos pendientes; sin pruebas ejecutadas | — | Abierta solo como documento |
| Corrección U0 (L18) | 2026-10-23 | `odd/tasks/mvp-copernicotv.md` | Ver L18; sin baseline, no se verificó diff | `sha256sum`, `wc`, `sed`, `grep`, `git status`, validaciones de conteo | P2 registrada como política; umbrales y procedimiento visual pendientes; sin pruebas ejecutadas | — | Abierta solo como documento |
| Corrección U0 (L19) | 2026-10-23 | `odd/tasks/mvp-copernicotv.md` | Ver L19; sin baseline, no se verificó diff | `sha256sum`, `wc`, `sed`, `grep`, `git status`, validaciones de conteo | E1-C aprobada (opción B); ejecución pendiente; sin pruebas ejecutadas | — | Abierta solo como documento |
| Corrección U0 (L20) | 2026-10-23 | `odd/tasks/mvp-copernicotv.md` | Ver L20; sin baseline, no se verificó diff | `sha256sum`, `wc`, `sed`, `grep`, `git status`, validaciones de conteo | S14 (alcance) aprobada; implementación/validación pendientes; sin pruebas ejecutadas | — | Abierta solo como documento |
| Corrección U0 (L21) | 2026-10-23 | `odd/tasks/mvp-copernicotv.md` | Ver L21; sin baseline, no se verificó diff | `sha256sum`, `wc`, `sed`, `grep`, `git status`, validaciones de conteo | Reglas de S14 aprobadas; implementación/validación pendientes; sin pruebas ejecutadas | — | Abierta solo como documento |

### 10.2 Evidencia de CI

Cada unidad que use CI registra: workflow, job, URL o ID del run, SHA, artifact y resultado. Un job ausente o no ejecutado se registra como `No ejecutada`, no como verde. Un workflow que falla no se compensa con una ejecución local.

### 10.3 Límites de Termux

- Local: `./gradlew :core:test` (JVM) y el wrapper; nada de Android real.
- No local: `assembleDebug`, pruebas instrumentadas, Compose runtime, Media3 y Room bajo `:app`.
- Sin SDK Android ni emulador local. La compilación de APK y las pruebas instrumentadas ocurren en GitHub Actions.
- Si el wrapper o el JDK no funcionan, se registra el error exacto y se usa CI; no se declara E1 cerrado sin evidencia.

### 10.4 Verificaciones físicas

Una verificación física pendiente se registra con: procedimiento exacto, resultado esperado, estado `Pendiente por bloqueo externo`, bloqueo (dispositivo, KVM, imagen) y qué falta. No se marca verde ni se usa para declarar la función validada. El MVP no se declara completamente validado mientras existan verificaciones físicas pendientes.

### 10.5 Evidencia E0 (auditoría previa a E1, ejecutada)

| Verificación | Comando o fuente | Resultado |
|---|---|---|
| Repositorio | `git rev-parse`, `git status --porcelain`, `git log` | `main` @ `c538046`; `M .gga`, `M AGENTS.md`, `?? GLOSSARY.md`, `?? docs/adr/` |
| Build existente | `find` por `*.gradle*`, `libs.versions.toml` | Ninguno |
| `odd/` | `ls -d odd` | No existía |
| JDK | `java -version`, `javac -version` | OpenJDK 17.0.19 |
| Gradle | `which gradle`, `~/.gradle` | No instalado, sin caché |
| Android SDK | variables y `command -v` | Ausente: sin `sdkmanager`, `adb`, `emulator` |
| SO y recursos | `uname`, `nproc`, `free`, `df` | Linux aarch64 (PRoot), 3 vCPU, 1,7 GB disponibles, 8,5 GB libres |
| Red | `curl` a `dl.google.com` | HTTP 200 |
| Imágenes TV | XML oficial `sys-img/android-tv` | API 21–34 y 36; sin 35; x86_64 solo en 36; **candidatas** hasta completar el trial |
| Imágenes móviles | XML oficial `sys-img/google_apis` | API 34/35/36 en x86_64; **candidatas** hasta completar el trial |
| Runner de CI | Readme de `actions/runner-images` | JDK 17, SDK en `/usr/local/lib/android/sdk`, build-tools 34–37 |
| KVM | Readme de `android-emulator-runner` | Requiere habilitación por udev; no verificado en runtime |

### 10.6 Evidencia U0 (documento creado; U0 no aprobada)

U0 creó este archivo con 364 líneas y 28 119 bytes. Verificación ejecutada: 57 specs, 57 filas de matriz, 45 tareas, 5 decisiones, sin S# duplicados ni faltantes, UTF-8 válido, sin espacios finales ni CRLF; la relectura detectó y corrigió un paréntesis sin sentido en la fila S7. La auditoría posterior y las correcciones se registran en L4–L21. **No se conservó un archivo de evidencia permanente ni un diff con ruta/hash**: los datos de esta sección son observaciones de sesión, no evidencia permanente. El estado de Git observado al inicio de esta cuarta ronda es `M .gga`, `M AGENTS.md`, `?? GLOSSARY.md`, `?? docs/adr/`, `?? odd/`. No existe un baseline verificable con hashes de esos archivos; por eso no se atribuye ese estado a U0 ni a esta ronda. La decisión sobre conservar evidencia permanente de U0 sigue pendiente.

### 10.7 Pruebas de E1: propuestas, no ejecutadas

Todas las pruebas de E1-U1 a E1-U6 están **propuestas** y no ejecutadas. E1-C está **aprobada como criterio de cierre**, pero su canario no se ejecutó y no hay resultados. No se ejecutó ninguna tarea Gradle, compilación Kotlin, prueba de Room, build de APK, arranque de emulador ni workflow.

## 11. Log

### L1 — Pedido original (transcripción reformateada; no es verbatim)

> La revisión documental de identidad y reconciliación queda cerrada. Pasamos a preparar el bootstrap de E1 de CoperNicoTV, pero todavía NO autorizo su ejecución.ObjetivoPreparar una propuesta verificable para iniciar E1, respetando "AGENTS.md", los ADR aprobados y el alcance completo del README.El MVP debe incluir todas las funciones del README, sin excepciones. Las etapas E1–E7 organizan el orden de implementación; no reducen el alcance final.Tareas permitidas en esta fase1. Audita el estado actual del repositorio, la rama, los archivos de documentación y las herramientas disponibles. No supongas que existen JDK, Gradle, Android SDK ni emuladores: verifica el entorno real.2. Revisa la coherencia entre "README.md", "AGENTS.md", "GLOSSARY.md" y los ADR 0001–0006, sin reabrir decisiones ya aprobadas salvo que encuentres una contradicción concreta.3. Prepara el plan de bootstrap de E1 con unidades pequeñas, objetivos, archivos que se crearían o modificarían y criterios de aceptación comprobables.4. Incluye el artefacto "odd/tasks/mvp-copernicotv.md" como entregable planificado de E1, dado que todavía no existe.5. Define cómo se verificarán desde GitHub Actions los tests JVM, análisis estático, generación del APK y pruebas instrumentadas móvil/Android TV. Comprueba qué imágenes de emulador están disponibles antes de comprometer una concreta.6. Asegura que el esquema Room inicial contemple las entidades documentadas, incluidas las Observaciones pendientes de importación y el Estado de Actualización. El diseño debe reflejar los ADR; no inventes entidades ni cambies decisiones sin justificarlo.7. Incluye las pruebas de parser, identidad, colisiones, migración, persistencia, atomicidad y resolución documentadas. Diferencia claramente entre pruebas propuestas y pruebas realmente ejecutadas.8. Identifica los riesgos, dependencias y decisiones que todavía requieran mi aprobación.Restricciones- No escribas ni modifiques archivos en esta fase.- No instales dependencias ni herramientas.- No cambies configuraciones globales o externas.- No crees commits, ramas, repositorios remotos ni workflows.- No empieces E1 ni implementes código.- No declares una prueba verificada si no se ha ejecutado.EntregaDevuélveme:A. Estado real del entorno y del repositorio, con evidencias.B. Plan de E1 dividido en unidades pequeñas y ordenadas, con archivos previstos y criterios de aceptación.C. Matriz de trazabilidad entre las funciones del README, las etapas E1–E7 y sus pruebas de aceptación, para demostrar que ninguna función del MVP se ha excluido.D. Plan de CI y verificación en GitHub Actions, incluidos los límites del entorno Termux.E. Riesgos, contradicciones o decisiones pendientes.F. Orden exacto recomendado para ejecutar E1 y criterio de finalización.Detente después de entregar la propuesta. Espera mi aprobación explícita antes de modificar archivos, instalar herramientas o iniciar E1.

### L2 — Autorización de U0 (transcripción reformateada; no es verbatim)

> La propuesta de E1 está aprobada como plan de trabajo provisional, pero NO autorizo la ejecución completa de E1.Autorizo exclusivamente U0: crear el documento local de seguimiento "odd/tasks/mvp-copernicotv.md", sujeto a estas condiciones:1. Incluye las funciones del README sin recortes, la matriz de trazabilidad propuesta, las tareas E1–E7, los criterios de aceptación y el registro de evidencias.2. Registra explícitamente como pendientes, sin resolverlas unilateralmente, estas decisiones:   - Asociación EPG independiente de las generaciones de importación.   - Modelo de conflictos de identidad con tres o más Canales.   - Definición funcional de «perfiles de reproducción».   - Política de actualización automática, intervalos y reintentos.   - Protección y almacenamiento de credenciales de URL.3. La asignación de funciones a E1–E7 es provisional hasta que yo apruebe las decisiones pendientes. No presentes una propuesta como decisión definitiva.4. No modifiques "AGENTS.md", los ADR, "GLOSSARY.md", ".gga" ni ningún otro archivo.5. No escribas código, no instales herramientas, no ejecutes Gradle ni E1, no crees commits y no hagas cambios externos. No sincronices todavía el documento con Engram.6. Antes de escribir, revisa el estado actual de Git y conserva todos los cambios existentes. No reviertas, sobrescribas ni incluyas cambios ajenos a U0.7. Al terminar, relee el documento y comprueba que las referencias, las tareas y los criterios de aceptación sean coherentes. Informa de la ruta creada, el diff exacto y el resultado de las verificaciones.Detente después de U0. Espera mi revisión explícita antes de continuar con U1 o cualquier otra unidad.

### L3 — Ejecución de U0

- Creado únicamente `odd/tasks/mvp-copernicotv.md`.
- Estado de Git previo conservado: `M .gga`, `M AGENTS.md`, `?? GLOSSARY.md`, `?? docs/adr/`.
- D1–D5 registradas como pendientes, sin resolver.
- Asignación de etapas marcada como provisional.
- Espejo Engram no sincronizado.

### L4 — Auditoría aceptada y autorización de corrección (síntesis fiel; no verbatim)

- La auditoría de coherencia fue aceptada.
- Autorizada exclusivamente la corrección de `odd/tasks/mvp-copernicotv.md`; no U1, código, instalaciones ni otros archivos.
- Instrucciones: separar E1/E4; corregir D2; corregir dependencias del esquema v1; corregir EPG y bloqueos; completar trazabilidad; actualizar U0 y el tracking.
- Restricciones: conservar D1–D5 pendientes, no editar ADR/AGENTS.md/.gga, no ejecutar Gradle, no Engram, no commits.

### L5 — Corrección de la auditoría

- Reescrito únicamente `odd/tasks/mvp-copernicotv.md`.
- E1 reducido a build, identidad, esquema, integridad, CI y trial; E4 concentra transiciones, correspondencia, fusión y pruebas transaccionales.
- D2 reescrita; eliminada la opción de cierre por pares; retirados los bloqueos indebidos de S6 y S46.
- D1/D2/D3/D5 declarados como bloqueos reales de E1-U3; D3 condicional.
- Añadidas matrices K1–K15, niveles de prueba, protocolos Media3, P1–P2 y registro por unidad.
- Matriz funcional reescrita con columnas separadas de aserción y procedimiento reproducible para las 57 filas.
- E1-U3/U4 completados con causa de fallo, ausencias, evidencia de colisión, datos de origen de la Observación y ausencia de FK a Canal.
- L1/L2 identificadas como transcripciones reformateadas, no verbatim.
- Comandos, conteos y hashes se calcularon en la sesión; no se verificó un diff contra baseline; no se conservó un informe de cierre ni un archivo de diff permanente (ver 10.6).

### L6 — Tercera corrección documental

- Modificado únicamente `odd/tasks/mvp-copernicotv.md`; sin código, Gradle, pruebas, instalaciones, CI, Engram, commits ni operaciones externas.
- A: asignados `m8` y los casos ADR-0004:57(a)–(d) a E4-T2/E4-T1; añadidos criterios de prueba de ADR-0004:58(v) y :42 a E4-T5; ampliados los casos de E1-U2 y el esquema v1 descrito en E1-U3, ADR-0006:21–24 en E2-T1, ADR-0003:16 en E5-T1 y ADR-0002:39–40 en E5-T3.
- B: separados S6 manual/automático; corregida S53 para distinguir URL de Fuente/Lista y de stream; alineados niveles de S16, S28, S29, S55, S56 y K14; añadido `instrumented-mobile` al cierre; E1-C documentada como decisión pendiente; K1, K4, K5, K8, K14 y K15 asignados o precisados.
- C: corregidas referencias al README por sección/viñeta; concretados S17, S20, S27, S30, S31, S41, S44, S47, S51 y S56; ampliadas las filas auditadas; S57 incluye verificación positiva en reposo pendiente de D5; S28 cubre offsets; S1 distingue M3U/M3U8; P1/P2 siguen pendientes; rutas con `...` marcadas como previstas; U0 aclarada como prerrequisito documental y evidencia temporal no permanente.
- D: D1–D5, P1/P2, alcance de protocolos, E1-C, definiciones funcionales y conservación permanente de evidencia de U0 siguen pendientes. D1(a) queda condicional a una regla de estabilidad/reconfirmación.
- No se ejecutaron pruebas ni se aprobó U0; no hay baseline de diff: en la sesión solo se pudo calcular el hash del archivo resultante, sin archivo permanente ni informe de cierre (ver 10.6).

### L7 — Cuarta corrección documental

- Modificado únicamente `odd/tasks/mvp-copernicotv.md`; sin código, Gradle, pruebas, instalaciones, CI, Engram, commits ni operaciones externas. U0 sigue sin aprobar; E1 no inició; U1 no está autorizada.
- E1-U3: eliminada la unicidad `Apariencia`/Lista/Canal que contradecía ADR-0004:18; precisados `Canal` (nombre, URL de stream, logo, `tvg_id`, metadatos, clave y estado), `Apariencia` (`group-title`, `tvg-name`, orden, Grupo), `ObservacionPendienteImportacion` (nombre, URL, `tvg-id` y grupo de origen), índices y unicidad; `ColeccionCanal` ya no impide dos posiciones del mismo Canal. La unicidad exacta de la tupla de presentación queda pendiente y no se inventa; el índice de `Canal` es único incondicional sobre `(fuente_id, clave_unicidad)`, sin índice parcial, conforme ADR-0004:28.
- E1-U4: añadida la comprobación de que `Apariencia` admite dos filas del mismo Canal/Lista y `ColeccionCanal` dos posiciones distintas.
- S14: en esta corrección se declaró completa para los modos de Apariencia y Colección y se dejaron los modos adicionales pendientes; L8 retira la afirmación de completitud y la atribución de esos dos modos al README. S55 separada de los niveles de prueba y con el run de CI como evidencia; S20 distingue fallo transitorio de 404 permanente; S22 cubre los campos técnicos mínimos y deja los adicionales pendientes; S30 verifica bounds; S32 queda condicionada a P2; S48 verifica responsabilidades de `:core` y `:app`, no solo pureza de `:core`.
- D1(b): documentado el problema de estabilidad/reconfirmación de la entidad estable de canal XMLTV, sin elegir regla; D1–D5, P1/P2 y E1-C siguen pendientes.
- Evidencia U0: L5/L6, §10.1 y §10.6 ya no remiten a un informe de cierre externo ni afirman que archivos temporales sean permanentes. El estado observado es `M .gga`, `M AGENTS.md`, `?? GLOSSARY.md`, `?? docs/adr/`, `?? odd/`; no hay baseline de hashes verificable y no se atribuye ese estado a U0 ni a esta ronda.
- No hay baseline de diff; en la sesión solo se calcula el hash del archivo resultante y no se verifica un diff contra una versión anterior. No se conserva archivo de diff permanente ni informe de cierre externo.

### L8 — Quinta corrección documental

- Modificado únicamente `odd/tasks/mvp-copernicotv.md`; sin código, Gradle, pruebas, instalaciones, CI, Engram, commits ni operaciones externas. U0 sigue sin aprobar; E1 no inició; U1 no está autorizada.
- S14: eliminada la afirmación de que los dos modos de ADR-0002 son los únicos exigidos por el README; conservados el orden de Apariencia por Lista y el orden propio de Colección; distinguidos de los requisitos genéricos de `README.md:38` y `:84`; el requisito genérico no se declara cubierto y su forma observable pendiente queda como definición funcional, no como decisión de alcance opcional. Superado por L20: el alcance genérico ya está aprobado.
- E1-U3: eliminada la dependencia de la unicidad de `posicion` de `ColeccionCanal` respecto de D2; el esquema permite dos pertenencias de la misma Colección en posiciones distintas y una pertenencia duplicada idéntica antes de la consolidación informada de ADR-0004:59(m5); no se impone unicidad global de posición ni restricción que impida esos estados. D2 sigue limitada a la representación B/C y a la persistencia de la resolución de identidad.
- E1-U4: precisada la comprobación de que `ColeccionCanal` no rechaza la pertenencia duplicada idéntica antes de la consolidación de E4-T4/m5.
- E3-T5 y §9.2: precisada la pertenencia única tras la consolidación y añadida la comprobación de las dos posiciones y del duplicado idéntico antes de resolver; no se impone una restricción que impida el estado de ADR-0004:59(m5).
- D1–D5, P1/P2, E1-C y la conservación permanente de evidencia de U0 siguen pendientes; D1(b) sigue condicional. No hay baseline de diff: en la sesión solo se calcula el hash del archivo resultante; no se verifica un diff contra una versión anterior.

### L9 — Sexta corrección documental

- Modificado únicamente `odd/tasks/mvp-copernicotv.md`; sin código, Gradle, pruebas, instalaciones, CI, Engram, commits ni operaciones externas. U0 sigue sin aprobar; E1 no inició; U1 no está autorizada.
- S14: incorporada la especificación funcional aprobada de ordenación del catálogo (modos A/B/C, direcciones, recientes, no reproducidos, desempates, preferencia por Lista y persistencia), distinguiendo el requisito genérico del README de la decisión funcional del usuario. Añadida §2.1 con S14e–S14m, nivel, aserción y procedimiento; todas las pruebas quedan planificadas, no ejecutadas.
- S14a–S14c se conservan para el orden manual de Apariencias y el orden propio de Colección; S14l/S14m impiden que la ordenación visual los sobrescriba o cambie la identidad o el esquema.
- Tareas: E3-T3 precisa la fecha de última reproducción; nueva E3-T7 para ordenación y preferencias por Lista; E7-T5 queda como consumidora de la preferencia por Lista (L10); S45d verifica persistencia y no regresión.
- Pendientes: política de mayúsculas/acentos, nombres vacíos y canales sin grupo en Modo B (superado por L21); D1–D5, P1/P2, E1-C y conservación permanente de evidencia de U0 siguen pendientes; D1(b) sigue condicional.
- En esta sesión solo se calcula el SHA-256 del archivo resultante; no existe un baseline en archivo y no se verifica un diff contra la versión anterior. La conservación permanente de evidencia sigue pendiente.

### L10 — Séptima corrección documental

- Modificado únicamente `odd/tasks/mvp-copernicotv.md`; sin código, Gradle, pruebas, instalaciones, CI, Engram, commits ni operaciones externas. U0 sigue sin aprobar; E1 no inició; U1 no está autorizada.
- H1: S14g reproduce C1 después de los demás, fija `Apariencia.orden` y `ColeccionCanal.posicion` en orden distinto, y verifica última reproducción, una fila de `Historial`, ambas direcciones y reinicio.
- H2: S14i usa dos Canales distintos con el mismo criterio primario, PK explícitas y orden de inserción contrario; afirma la secuencia PK ascendente esperada, reinicio y permutación de posiciones.
- H3: S14a usa L1/L2 con Canales compartidos y órdenes distintos; reordena L1 y comprueba L2 antes y después de reiniciar.
- H4: nueva E3-T8 para reordenamiento manual de Apariencias, UI y persistencia; enlazada con S14a/S14c/S45a–S45c, sin duplicar E3-T5 ni E3-T7.
- H5: el alcance genérico del README más allá de A/B/C queda pendiente en §6.1 y bloquea S14 en §9.1.10; no se declara cubierto por A/B/C. Superado por L20: el alcance genérico ya está aprobado como la combinación registrada en §2.1.
- H6: E3-T7 es dueña de la preferencia por Lista en DataStore; E7-T5 la consume; K7, S14j/S14k y matriz actualizados, sin ciclo.
- H7: S14k y S45d exigen cerrar la app y recrear el proceso, no solo DataStore/BD.
- H8: L5–L9 se corrigen para no afirmar un diff verificable; solo se calcula el SHA-256 actual y la evidencia permanente sigue pendiente.
- E3-T8 es la única tarea nueva; no se duplica ningún ID y el conteo histórico de U0 (45 tareas) no se reescribe.
- Pendientes sin cambios: D1–D5, D1(b) condicional, P1/P2, E1-C, reglas de nombres/grupos (superado por L21) y evidencia permanente de U0. Todas las pruebas S14 siguen planificadas, no ejecutadas.

### L11 — Octava corrección documental

- Modificado únicamente `odd/tasks/mvp-copernicotv.md`; sin código, Gradle, pruebas, CI, instalaciones, Engram, commits ni operaciones externas. U0 sigue sin aprobar; E1 no inició; U1 no está autorizada.
- H3: `S14a` precisa el estado inicial (L1 contiene C1,C2; L2 contiene C2,C1; ambas comparten los mismos Canales), reordena L1 a C2,C1, verifica inmediatamente L1=C2,C1 y L2=C2,C1, cierra la app y recrea el proceso (no basta recrear Activity, DataStore o BD), y tras reabrir vuelve a verificar L1=C2,C1 y L2=C2,C1. La aserción exige tanto el cambio de L1 como la ausencia de cambio en L2, sin alterar la identidad del Canal ni el orden de Colección (S45c).
- H4/E2-T3: `E3-T8` declara que reutiliza la persistencia de `Apariencia.orden` definida en E2-T3, sin crear una segunda ruta; se mantiene la dependencia de `E3-T6` y la referencia contractual con `E2-T3`, sin ciclo.
- Sin cambios en la propiedad de DataStore de `E3-T7` ni en el papel consumidor de `E7-T5`.
- Pendientes en esa fecha: alcance genérico del README (superado por L20), D1–D5, D1(b) condicional, P1/P2, E1-C, reglas de nombres/grupos (superado por L21) y evidencia permanente de U0. Todas las pruebas S14 siguen planificadas, no ejecutadas.
- No hay baseline de diff; solo se calcula el SHA-256 actual. No se afirma un diff verificable.

### L12 — Novena corrección documental

- Modificado únicamente `odd/tasks/mvp-copernicotv.md`; sin código, Gradle, pruebas, CI, instalaciones, Engram, commits ni operaciones externas. U0 sigue sin aprobar; E1 no inició; U1 no está autorizada.
- S14a: sustituido el fixture de dos Canales por tres Canales compartidos `C1`, `C2` y `C3`. Estado inicial `L1: C1, C2, C3` y `L2: C3, C2, C1`; se reordena únicamente `L1` a `C2, C1, C3`; se verifican inmediatamente `L1: C2, C1, C3` y `L2: C3, C2, C1`; se cierra la app y se recrea el proceso (no basta Activity, DataStore o BD); tras reabrir se verifican de nuevo ambas secuencias exactas. La aserción exige el nuevo orden de `L1` y la conservación de `L2` antes y después del reinicio, no solo que `L2` no cambió.
- Se mantiene la coherencia con `S14c` y `S45a–S45c`: la operación no cambia la identidad de los Canales ni el orden propio de las Colecciones. `E3-T8` referencia el fixture de tres Canales compartidos.
- Sin cambios en la propiedad de DataStore de `E3-T7` ni en el papel consumidor de `E7-T5`.
- Pendientes en esa fecha: alcance genérico del README (superado por L20), D1–D5, D1(b) condicional, P1/P2, E1-C, reglas de nombres/grupos (superado por L21) y evidencia permanente de U0. Todas las pruebas S14 siguen planificadas, no ejecutadas.
- No hay baseline de diff; solo se calcula el SHA-256 actual. No se afirma un diff verificable.

### L13 — Décima corrección documental (D1(a) aprobada)

- Modificado únicamente `odd/tasks/mvp-copernicotv.md`; sin código, Gradle, pruebas, CI, instalaciones, Engram, commits ni operaciones externas. U0 sigue sin aprobar; E1 no inició; U1 no está autorizada.
- D1(a) aprobada: asociación EPG por `(fuente_guia_id, channel_id_texto)`, resuelta contra la generación publicada. No se crea `CanalXmltv`.
- Reglas aprobadas: conservar Asociaciones confirmadas ante ausencias temporales; no reasignar programas automáticamente ante indicios de reutilización del ID; exigir reconfirmación cuando exista ambigüedad.
- Pendiente: definición exacta de la huella de identidad, las señales relevantes y los criterios de reconfirmación. No se fijan umbrales ni reglas adicionales.
- Actualizados D1, §1.4, §3, S25–S29, E1-U3/U4, E5 y §8.5, §9.1.8, §9.1.10, §9.2.4, §10.1 y §10.6. E1-U3 no se declara cerrado: la representación de `Asociacion` está aprobada, pero sus detalles de estabilidad/reconfirmación siguen pendientes y D2/D5 continúan bloqueando el esquema.
- No hay baseline de diff; solo se calcula el SHA-256 actual. La evidencia permanente de U0 sigue pendiente.

### L14 — Undécima corrección documental (D2(a) aprobada)

- Modificado únicamente `odd/tasks/mvp-copernicotv.md`; sin código, Gradle, pruebas, CI, instalaciones, Engram, commits ni operaciones externas. U0 sigue sin aprobar; E1 no inició; U1 no está autorizada.
- D2(a) aprobada: resolución persistida por fila; la acción de UI agrupa N filas en una única transacción atómica; sin entidad nueva de resolución ni registro de auditoría grupal; si falla una parte, se revierte la transacción completa; las reglas de identidad A/B/C de ADR-0004 se mantienen intactas.
- Implementación no verificada: no se ejecutaron pruebas de atomicidad, rollback ni UI. E1-U3, E4-T2 y E4-T4 no se declaran cerradas.
- Actualizados D2, §1.4, §3, E1-U3, E4-T2/T4, §9.1.10, §9.2.4, §10.1 y §10.6. No se modificó código ni otros archivos.
- No hay baseline de diff; solo se calcula el SHA-256 actual. La evidencia permanente de U0 sigue pendiente.

### L15 — Duodécima corrección documental (D3 aprobada)

- Modificado únicamente `odd/tasks/mvp-copernicotv.md`; sin código, Gradle, pruebas, CI, instalaciones, Engram, commits ni operaciones externas. U0 sigue sin aprobar; E1 no inició; U1 no está autorizada.
- D3 aprobada: un Perfil tiene nombre y agrupa Listas M3U; no se reduce a renombrar la Lista activa. Conserva preferencias de reproducción elegidas por el usuario cuando el contenido las admite, incluidas las preferencias de audio y subtítulos; no inventa pistas ni capacidades; distingue preferencias guardadas de opciones disponibles para el contenido actual.
- Pendiente: alcance de preferencias (global/por perfil/por lista), comportamiento cuando la pista preferida no está disponible y representación/cardinalidad exacta de la agrupación de Listas. No se deciden por inferencia.
- Implementación y pruebas no verificadas: S2c, S19/S21/S44, E1-U3/U4, E3-T9, E6-T3 y E7-T5 no se declaran cerradas. E1-U3 sigue bloqueada por D5 y detalles D1(a); D2(a) sigue con implementación no verificada.
- Actualizados D3, §1.4, §3, §6, S2c, S19, S21, S44, E1-U3/U4, E3-T9, E6-T3, E7-T5, §9.1.10, §9.2.4, §10.1 y §10.6. No se modificó código ni otros archivos.
- No hay baseline de diff; solo se calcula el SHA-256 actual. La evidencia permanente de U0 sigue pendiente.

### L16 — Decimotercera corrección documental (D4 aprobada y D5 base aprobada)

- Modificado únicamente `odd/tasks/mvp-copernicotv.md`; sin código, Gradle, pruebas, CI, instalaciones, Engram, commits ni operaciones externas. U0 sigue sin aprobar; E1 no inició; U1 no está autorizada.
- D4 aprobada: WorkManager; actualización automática con intervalos configurables y reintentos con backoff. Pendientes: intervalos por defecto, mínimo y máximo; restricciones de red; comportamiento tras reinicio; detalles de reintentos/backoff. No se fijan valores ni se adopta backoff exponencial por inferencia.
- D5 base aprobada: cifrado de campos sensibles en reposo con claves protegidas mediante Android Keystore; prohibición de credenciales en logs y diagnósticos. Pendientes: inventario exacto de campos, gestión de claves, backup/restauración y pruebas de recuperación. No se implementa cifrado de base completa ni se asume que toda URL sea credencial sin regla explícita.
- Dependencia mutua D4/D5: la actualización automática necesita acceso a credenciales en segundo plano; D5 debe permitirlo y D4 debe definir el comportamiento tras reinicio y los reintentos. La gestión/restauración de claves y el comportamiento tras reinicio se resuelven de forma coordinada.
- Actualizados D4/D5, §1.4, §3, §6, D1, D2, S3, S6, S14, S46, S57, E1-U3/U4, E2-T6, E4-T7, E7-T5/T6, §9.1.10, §9.2.4, §10.1 y §10.6. `E1-U3` sigue abierta; `E4-T7`, `S3b`, `S46b`, `S57b` y `S57c` no se declaran cerrados. No se ejecutaron pruebas.
- No hay baseline de diff; solo se calcula el SHA-256 actual. La evidencia permanente de U0 sigue pendiente.

### L17 — Decimocuarta corrección documental (P1 aprobada)

- Modificado únicamente `odd/tasks/mvp-copernicotv.md`; sin código, Gradle, pruebas, CI, instalaciones, Engram, commits ni operaciones externas. U0 sigue sin aprobar; E1 no inició; U1 no está autorizada.
- P1 aprobada como política híbrida: límite absoluto por métrica, comparación de regresión contra un baseline versionado y mediciones p95.
- Métricas con valores numéricos pendientes: tiempo de parseo de 10 000 entradas, primera página de búsqueda, consulta paginada y memoria máxima. También quedan pendientes el criterio numérico de regresión y el procedimiento de medición p95 (repeticiones, calentamiento y entorno).
- La calibración y la validación requieren una implementación y un entorno de medición estable; no se inventan umbrales ni se declara rendimiento validado.
- Actualizados P1, §1.4, §3, S5, S9, S52, E2-T5, E3-T1, §9.1.10, §10.1, §10.6 y encabezado. P2 no se modificó. S5, S9, S52, E2-T5 y E3-T1 no se declaran cerradas.
- No hay baseline de diff; solo se calcula el SHA-256 actual. La evidencia permanente de U0 sigue pendiente.

### L18 — Decimoquinta corrección documental (P2 aprobada)

- Modificado únicamente `odd/tasks/mvp-copernicotv.md`; sin código, Gradle, pruebas, CI, instalaciones, Engram, commits ni operaciones externas. U0 sigue sin aprobar; E1 no inició; U1 no está autorizada.
- P2 aprobada como política híbrida para Android TV: pruebas automatizadas de límites de texto y foco, capturas reproducibles y revisión visual humana a una distancia equivalente a 10 pies (aprox. 3 m).
- Pendientes: umbrales numéricos de tamaño de texto, área de foco e indicador de foco, y área táctil móvil; procedimiento de aceptación visual (condiciones, iluminación, tamaño de pantalla, criterios y responsable). No se inventan valores.
- CI por sí sola no demuestra legibilidad real a distancia; un job verde o una captura automatizada son evidencia parcial y no sustituyen la revisión humana.
- Actualizados P2, §1.4, §3, S32, S33, S38, E7-T1/T2/T3/T7, §9.1.10, §10.1, §10.6 y encabezado. P1 no se modificó en esta corrección. S32, S33, S38 y las tareas E7 correspondientes no se declaran cerradas ni visualmente validadas.
- No hay baseline de diff; solo se calcula el SHA-256 actual. La evidencia permanente de U0 sigue pendiente.

### L19 — Decimosexta corrección documental (E1-C aprobada, opción B)

- Modificado únicamente `odd/tasks/mvp-copernicotv.md`; sin código, Gradle, pruebas, CI, instalaciones, Engram, commits ni operaciones externas. U0 sigue sin aprobar; E1 no inició; U1 no está autorizada.
- E1-C aprobada (opción B): E1 requiere pruebas instrumentadas en Android TV exitosas con un canario significativo: importar una lista M3U de prueba, abrir el catálogo y navegar usando foco/D-pad. No basta con demostrar que la app arranca.
- Si el emulador TV o el entorno requerido no funciona, se documenta el bloqueo y E1 permanece abierto; no se simula éxito ni se sustituye la prueba sin aprobación explícita.
- Actualizados E1-C, E1-U5/U6, S55, §1.4, §8.1, §9.2, §10.1, §10.6, §10.7 y encabezado. ADR-0005 no se modificó; sus referencias en este documento se alinean con el canario aprobado. Decisión aprobada separada de la ejecución: no hay resultados de pruebas ni `instrumented-tv` verde.
- No hay baseline de diff; solo se calcula el SHA-256 actual. La evidencia permanente de U0 sigue pendiente.

### L20 — Decimoséptima corrección documental (alcance de S14 aprobado)

- Modificado únicamente `odd/tasks/mvp-copernicotv.md`; sin código, README, ADR, pruebas, CI, instalaciones, Engram, commits ni operaciones externas. U0 sigue sin aprobar; E1 no inició; U1 no está autorizada.
- Aprobada la cobertura del requisito genérico de `README.md:38`/`:84` como combinación de: modos A/B/C y direcciones aprobadas; orden manual de Apariencias; orden independiente de Colecciones; preferencias de ordenación por Lista persistidas entre reinicios.
- No se agregan funciones implícitas de presentación u organización sin requisito concreto aprobado. La decisión es de alcance, no de implementación ni validación.
- Actualizados §1.4, §2 (intro y §2.1), S14, §6.1, §9.1.10, E3-T5/T7/T8, L8/L10/L11/L12 (notas de superación), §10.1, §10.6 y encabezado. README.md y los ADR no se modificaron. S14 y E3 no se declaran cerrados mientras sigan pendientes las reglas de mayúsculas/acentos, nombres vacíos y canales sin grupo. L21 aprueba esas reglas; la implementación y validación siguen pendientes. No se ejecutaron pruebas.
- No hay baseline de diff; solo se calcula el SHA-256 actual. La evidencia permanente de U0 sigue pendiente.

### L21 — Decimoctava corrección documental (reglas de S14 aprobadas)

- Modificado únicamente `odd/tasks/mvp-copernicotv.md`; sin código, README, ADR, pruebas, CI, instalaciones, Engram, commits ni operaciones externas. U0 sigue sin aprobar; E1 no inició; U1 no está autorizada.
- Aprobadas las tres reglas de S14: (1) mayúsculas/acentos: comparación sin distinguir mayúsculas/minúsculas, con reglas de español; acentos en el desempate y clave final determinista; (2) Canales sin nombre: se conservan, se muestran como «Sin nombre» y van al final con desempate determinista, sin rechazar la entrada ni usar el nombre mostrado como identidad; (3) Canales sin grupo: en Modo B, sección visual «Sin grupo» al final, ordenada por nombre, sin crear un Grupo ficticio en Room.
- La comparación técnica se define en §2.1 con una clave primaria calculada en `:core` (NFD, minúsculas con `Locale.ROOT`, tabla de orden español fija con `ñ` entre `n` y `o`, vocales acentuadas plegadas a su vocal base), una clave secundaria que conserva los diacríticos para el desempate y la PK de `Apariencia` como clave final. No se usa `Locale.getDefault()` ni un `Collator` dependiente del dispositivo; el mismo corpus se ejecuta en JVM y Android.
- Actualizados §1.4, §2.1, S14, S14e/S14f/S14h/S14i/S14m, §6.1, §9.1.10, E3-T6/E3-T7, matriz S14, L9/L20 (notas de superación), §10.1, §10.6 y encabezado. README.md y los ADR no se modificaron. No se ejecutaron pruebas; S14 y E3 no se declaran implementados ni validados.
- No hay baseline de diff; solo se calcula el SHA-256 actual. La evidencia permanente de U0 sigue pendiente.

### Próximo paso

Esperar revisión explícita de esta decimoctava corrección. No aprobar U0, no iniciar E1 ni U1 ni ninguna otra unidad. Las reglas de S14 quedan aprobadas, pero su implementación y validación siguen pendientes; S14 y E3 no se cierran hasta implementarlas y validarlas. E1-C sigue aprobada con ejecución pendiente; no se declara `instrumented-tv` verde ni E1 cerrado. U1 no depende de D1–D5, pero E1 no puede cerrarse mientras E1-U3/U4, `instrumented-mobile` o `instrumented-tv` con el canario de E1-C estén pendientes.
