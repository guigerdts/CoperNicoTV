# ADR-0010 — Perfiles de conexión y guía administrada sobre el catálogo existente

Status: accepted

## Contexto

Hasta `v1.0.0-beta.3` la aplicación importaba listas M3U/M3U8 como ubicaciones
sueltas y la EPG carecía de una ruta de alta en producción: `E5-T5` se declaraba
implementado, pero ninguna pantalla creaba una `FuenteGuiaEntity` y
`EpgRepositorio` exigía un `fuenteGuiaId` ya existente. La campaña física del
2026-11-23 lo confirmó como defecto de alcance (H-06): la guía, la asociación y
«Ahora/Siguiente» no se podían ejercitar de punta a punta.

Además, el producto necesita administrar conexiones Xtream Codes y M3U como
perfiles de primera clase: crear, editar, probar, activar, desactivar y eliminar,
con credenciales protegidas y sin perder favoritos, historial, ocultos ni
asociaciones al actualizar. La alternativa —una segunda pila de importación y un
catálogo paralelo— duplicaría servicios y rompería la identidad de canal
(ADR-0002, ADR-0004).

## Decisión

1. **La conexión se materializa sobre el catálogo existente.** `Conexion` es una
   entidad nueva con la configuración del perfil (tipo, servidor, puerto,
   protocolo, formato de salida, URL de guía opcional, intervalo y estado) y con
   los secretos cifrados en la propia fila. Guarda `fuenteId` y `listaId`
   nulables hacia las entidades ya existentes. Crear una conexión crea su
   `Fuente` y su `Lista`; editar reutiliza la misma `Lista`
   (`ListaDao.actualizarUbicacion`). No se crea un catálogo paralelo ni se
   duplica el pipeline de importación.
2. **Los secretos nunca salen en claro del almacenamiento.** Usuario y
   contraseña se cifran con AES-GCM autenticado, con AAD por campo
   (`CifradoAes.aad(...)`) y la clave raíz en Android Keystore, igual que el
   resto del catálogo (ADR-0007). Los modelos de dominio redactan en
   `toString()`; los errores y logs pasan por `ConexionXtream.redactar(...)`.
   La UI no rellena la contraseña al editar y eliminar la conexión borra sus
   secretos.
3. **Xtream se limita a las rutas estándar del panel**: `player_api.php`,
   `get.php?type=m3u_plus&output=…` y `xmltv.php`. No se inventan endpoints. La
   construcción de URLs es pura (`:core`) y el adaptador de `:app` hace la red
   con timeouts, validación de destino y de cada redirección.
4. **La guía es una entidad administrable con ciclo de vida propio.**
   `FuenteGuia` gana origen (`xtream`, `m3u`, `manual`), estado, `conexionId`,
   última actualización, último error, intentos, activa, actualización
   automática e intervalo propio. Una guía creada por una conexión se elimina
   con ella; una manual solo se desvincula. La URL propia tiene prioridad sobre
   la generada por el panel.
5. **Una sola programación periódica para la EPG.** `guia-automatica` corre cada
   6 h, revisa todas las fuentes activas con actualización automática y filtra
   por el intervalo propio de cada una; «actualizar ahora» encola un trabajo
   único por fuente. `PoliticaReintentoGuia` (en `:core`) define las esperas
   15 min / 1 h / 6 h / 24 h y el tope de 4 intentos automáticos. Así se evita
   mantener una programación de WorkManager por fuente.
6. **Migración aditiva 4→5, sin pérdida de datos.** La tabla `Conexion` es
   nueva; las columnas añadidas a `FuenteGuia` y `Programa` son nulables para
   que las filas v4 sobrevivan sin defaults. `FuenteGuia.conexionId` no declara
   FK porque `ALTER TABLE` de SQLite no la admite: la integridad se mantiene en
   el repositorio y se indexa la columna. La migración está cubierta por
   `MigracionDiagnosticoTest` y `EsquemaRoomTest`.

## Consecuencias

- Los perfiles de conexión y las guías se benefician de la reconciliación, la
  identidad lógica y el cifrado ya existentes; no hay dos caminos de catálogo.
- Editar una conexión conserva favoritos, historial, ocultos y asociaciones
  porque no cambia la identidad de la `Lista` ni de los canales.
- El esquema v5 evoluciona más allá de la transcripción normativa §6.3.1 de
  `EsquemaNormativoV1`, que se extiende de forma documentada para `FuenteGuia` y
  `Programa`.
- La actualización automática de EPG no es puntual al minuto: respeta el
  intervalo por fuente en la primera pasada que lo cumpla. Es una concesión
  deliberada para no multiplicar programaciones.
- Un fallo de Keystore que inutilice el índice HMAC bloquea las actualizaciones
  hasta restaurar claves, pero nunca borra la última guía publicada.
- Cualquier panel Xtream que solo exponga rutas no documentadas queda fuera de
  alcance; se informa como error de conexión, no se intenta adivinar.
