# CoperNicoTV

Reproductor IPTV profesional para Android y Android TV.

CoperNicoTV es una aplicación nativa de Android diseñada para cargar, organizar, administrar y reproducir contenido multimedia a partir de listas M3U y M3U8 proporcionadas por el usuario. Su objetivo es ofrecer una experiencia de reproducción moderna, estable, intuitiva y altamente personalizable, tanto en dispositivos móviles como en televisores Android TV y Google TV compatibles.

El proyecto se desarrollará con tecnologías nativas modernas de Android, una arquitectura modular y buenas prácticas de ingeniería de software, priorizando el rendimiento, la facilidad de mantenimiento, la seguridad y la escalabilidad.

Objetivos principales

- Ofrecer un reproductor multimedia completo para listas IPTV.
- Proporcionar una experiencia de uso optimizada para teléfonos, tabletas y televisores.
- Facilitar la administración de múltiples listas y grandes catálogos de canales.
- Incorporar funciones profesionales de reproducción, organización, búsqueda y personalización.
- Mantener una arquitectura preparada para futuras funcionalidades sin comprometer la estabilidad.
- Desarrollar y mantener todo el proyecto mediante OpenCode, utilizando agentes y herramientas de asistencia al desarrollo cuando aporten valor.

Funcionalidades previstas

1. Administración de listas

- Importación de listas M3U/M3U8 desde archivos locales y direcciones URL.
- Gestión de múltiples listas y perfiles de reproducción.
- Actualización manual y automática de listas, con intervalos configurables.
- Validación de fuentes, manejo de errores y detección de listas inválidas.
- Procesamiento eficiente de listas grandes.
- Conservación de favoritos y preferencias durante las actualizaciones.
- Indicadores del estado de carga, actualización y disponibilidad de las fuentes.

2. Organización de canales

- Clasificación por grupos y categorías definidos en la lista.
- Búsqueda instantánea por nombre y filtros.
- Canales favoritos y listas personalizadas.
- Historial de reproducción y acceso rápido a los últimos canales.
- Logotipos, nombres y metadatos disponibles en la fuente.
- Ocultación de grupos y canales no deseados.
- Ordenación y personalización de la presentación del catálogo.

3. Reproductor multimedia

- Reproducción de canales en directo y contenido bajo demanda cuando la fuente lo permita.
- Soporte para los protocolos multimedia compatibles con el motor de reproducción.
- Controles de reproducción accesibles y personalizables.
- Gestión de pantalla completa, orientación y relación de aspecto.
- Selección de pistas de audio y subtítulos cuando estén disponibles.
- Gestión de errores, reconexión y recuperación ante interrupciones.
- Ajustes de reproducción y preferencias de calidad cuando la fuente lo admita.
- Información técnica del flujo y diagnóstico básico de reproducción.
- Control del volumen, navegación entre canales y cambio rápido de contenido.

4. Guía electrónica de programación (EPG)

- Compatibilidad prevista con fuentes XMLTV.
- Programación actual y siguiente para canales con información disponible.
- Guía de programación con vista temporal.
- Asociación configurable entre canales y datos de programación.
- Información sobre títulos, horarios y descripciones de los programas.
- Actualización de la guía y gestión de datos incompletos.

5. Experiencia Android TV

- Interfaz diseñada para pantallas grandes y navegación con mando a distancia.
- Navegación mediante cruceta, botones de selección y retroceso.
- Indicadores de foco claros y navegación predecible.
- Listas de canales y categorías optimizadas para una visualización a distancia.
- Acceso rápido a favoritos, historial y guía de programación.
- Controles adaptados al uso desde el sofá.
- Compatibilidad con los patrones de navegación y ciclo de vida de Android TV.

6. Experiencia móvil

- Interfaz adaptable a teléfonos y tabletas.
- Navegación táctil y controles accesibles.
- Gestión sencilla de listas y canales.
- Reproductor a pantalla completa y orientación adaptable.
- Diseño coherente con las convenciones modernas de Android.
- Gestión apropiada de las interrupciones, permisos y ciclo de vida de la aplicación.

7. Personalización y preferencias

- Temas claro y oscuro.
- Configuración de reproducción y comportamiento del reproductor.
- Personalización de la organización del catálogo.
- Preferencias de actualización y almacenamiento local.
- Gestión de favoritos, historial y opciones de privacidad.
- Posibilidad de ampliar la configuración mediante módulos independientes.

8. Rendimiento, seguridad y fiabilidad

- Arquitectura modular y separación de responsabilidades.
- Procesamiento asíncrono de listas y metadatos.
- Caché local cuando sea apropiado.
- Persistencia eficiente mediante base de datos local.
- Validación de URL y manejo seguro de archivos y datos externos.
- Tratamiento de errores de red y fuentes no disponibles.
- Pruebas automatizadas y análisis estático.
- Gestión responsable de memoria, recursos multimedia y tareas en segundo plano.
- Protección de credenciales y datos sensibles cuando existan.

Stack tecnológico

- Lenguaje: Kotlin.
- Interfaz móvil: Jetpack Compose y Material 3.
- Interfaz de televisión: Compose for TV.
- Motor multimedia: AndroidX Media3 / ExoPlayer.
- Arquitectura: MVVM, repositorios y módulos por responsabilidad.
- Persistencia: Room y DataStore.
- Programación asíncrona: Kotlin Coroutines y Flow.
- Inyección de dependencias: Hilt.
- Construcción: Gradle con Kotlin DSL y Version Catalog.
- Pruebas: JUnit, pruebas de componentes y pruebas de interfaz.
- Control de versiones: Git y GitHub.
- Entorno de desarrollo asistido: OpenCode, con agentes y herramientas de ingeniería de software.

Arquitectura y evolución

CoperNicoTV se desarrollará de forma incremental, con una base funcional verificable antes de incorporar características avanzadas. Los módulos de listas, catálogo, reproducción, programación, preferencias y almacenamiento mantendrán responsabilidades claramente definidas.

La lógica de negocio y el procesamiento de datos se compartirán entre los distintos dispositivos siempre que sea apropiado, mientras que la presentación y navegación se adaptarán a las particularidades de móviles y televisores.

Las funcionalidades opcionales se integrarán mediante interfaces y componentes desacoplados, evitando dependencias innecesarias y facilitando las pruebas, el mantenimiento y la evolución del proyecto.

Alcance y principios

CoperNicoTV será un reproductor y administrador de contenido, no un proveedor de canales. El usuario deberá proporcionar fuentes y contenido que tenga derecho a utilizar. La disponibilidad de canales, programación, subtítulos, logotipos y otras funciones dependerá de los datos y protocolos ofrecidos por cada fuente.

El proyecto priorizará la estabilidad, la claridad de la interfaz, la rapidez de navegación, la compatibilidad entre dispositivos y la calidad del código.

Visión: construir un reproductor IPTV moderno, profesional y extensible, con una experiencia excelente tanto en Android móvil como en Android TV.
