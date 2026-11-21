# Distribución y firma de CoperNicoTV

Este documento describe cómo se publica una versión, qué claves se usan y qué
hay que hacer si algo se pierde. Es el procedimiento operativo de S10 y S11 de
`odd/tasks/ota-actualizaciones.md`; el contrato del manifiesto está en
`ManifiestoActualizacion.kt` y su verificación en `FirmaManifiesto.kt`.

## Piezas

| Pieza | Dónde vive | Qué prueba |
| --- | --- | --- |
| `release.keystore` | Fuera del repositorio; secreto de GitHub Actions | Identidad del APK ante Android. Cambiarla impide actualizar. |
| `ota-firma.pem` | Fuera del repositorio; secreto de GitHub Actions | Autenticidad del manifiesto (`update.json.sig`). |
| `OTA_MANIFIESTO_PUBKEY` | Variable de repositorio, inyectada en la app | Clave pública que verifica la firma del manifiesto. |
| `OTA_CERT_SHA256` | Variable de repositorio, inyectada en la app | Huella del certificado del APK. Se compara con la del APK descargado y con la del propio APK en el release. |

Las dos variables no son secretas: son datos públicos que la app lleva
embebidos. Los dos archivos de clave sí lo son y nunca se versionan.

## Preparación, una sola vez

En un equipo de confianza, con JDK y OpenSSL instalados:

```bash
bash tools/ota/generar-claves.sh
```

El script crea el directorio (por defecto `~/copernicotv-claves`), genera el
keystore RSA 4096 y la clave ECDSA P-256, y **no sobrescribe nada** si ya
existen claves. Al terminar imprime los comandos exactos para cargar secretos y
variables con `gh`, y los dos valores públicos.

Cargar los secretos y variables es un paso manual del propietario: el script
solo imprime los comandos para que las contraseñas no pasen por un log.

### Respaldo obligatorio

Antes de publicar el primer release, guardar en **dos** lugares offline
distintos:

- `release.keystore`
- `ota-firma.pem`
- las dos contraseñas, en un gestor de contraseñas

Qué pasa si se pierde cada cosa:

- **Se pierde el keystore**: ya no se puede publicar una actualización que
  instale sobre la app existente. Android rechaza un APK con otra firma. La
  única salida es pedir a cada usuario que desinstale y reinstale: se pierden
  Listas, URL, favoritos e historial. No hay recuperación técnica.
- **Se pierde `ota-firma.pem`**: se genera un par nuevo, se actualiza
  `OTA_MANIFIESTO_PUBKEY` y se publica un release nuevo. Las versiones viejas
  instaladas dejan de aceptar manifiestos firmados con la clave vieja, así que
  ese release debe distribuirse también por fuera del OTA.

## Validar una versión

1. Elegir el tag. `v1.2.0` produce el canal `stable`; cualquier sufijo
   (`v1.2.0-beta.1`, `v1.2.0-rc.1`) produce el canal `beta`, porque un sufijo
   marca una versión previa a la estable. Un tag que no produzca un
   `versionName` numérico válido se rechaza antes de compilar.
2. Crear el tag anotado: el cuerpo del tag se usa como notas del manifiesto si
   no se pasan otras.

   ```bash
   git tag -a v1.2.0 -m "Corrección de reproducción y ajustes de OTA"
   git push origin v1.2.0
   ```

3. El workflow `Release OTA` **valida** y no publica:
   - exige que el commit compilado sea el commit del tag (si no, falla antes de
     compilar);
   - materializa las claves desde los secretos;
   - ensambla `:app:assembleRelease` con `versionName` del tag,
     `versionCode` del número de run, la clave pública y la huella fijadas;
   - lee el `minSdk` real del APK y verifica que la firma coincida con
     `OTA_CERT_SHA256`;
   - calcula SHA-256 y tamaño, escribe `update.json`, lo firma y verifica la
     firma;
   - comprueba que la clave que firmó el manifiesto sea exactamente
     `OTA_MANIFIESTO_PUBKEY`, la que la app lleva embebida, y que el `sha256`
     del manifiesto corresponda al APK real;
   - publica los tres archivos como artifact
     `ota-<tag>-<commit>-r<run_id>-a<intento>`, con retención de **30 días**;
   - **no crea ni modifica ningún release**: sus permisos son `contents: read`.

Para revalidar un tag existente (por ejemplo tras un fallo de infraestructura) el
workflow debe ejecutarse sobre el tag, no sobre una rama:

```bash
gh workflow run release.yml --ref v1.2.0 -f tag=v1.2.0
```

## Publicar el artifact validado

La publicación es un `workflow_dispatch` explícito del workflow `Publicar OTA`.
Necesita el tag y el `run_id` del run de validación; el resumen de ese run imprime
el comando exacto:

```bash
gh workflow run release-publish.yml -f tag=v1.2.0 -f run_id=<run_id>
```

El publicador se niega a publicar si cualquiera de estas comprobaciones falla:

- el artifact pertenece a este repositorio;
- proviene del workflow de validación (`.github/workflows/release.yml`);
- el run terminó en éxito y con un evento esperado;
- el commit del run es el commit del tag pedido;
- el artifact se llama exactamente
  `ota-<tag>-<commit>-r<run_id>-a<intento>`;
- contiene exactamente tres archivos en su raíz: `update.json`,
  `update.json.sig` y `copernicotv-<versión>.apk`;
- el SHA-256 y el tamaño recalculados del APK coinciden con el manifiesto;
- el certificado del APK coincide con `OTA_CERT_SHA256` y con
  `signingCertSha256` del manifiesto;
- `packageName`, `versionCode` y `versionName` leídos del APK coinciden con el
  manifiesto y con el tag;
- la firma del manifiesto verifica con `OTA_MANIFIESTO_PUBKEY`.

El publicador **no ejecuta Gradle, no firma y no tiene acceso a ningún secreto**:
no puede producir un APK distinto del validado. Sus permisos son
`contents: write` (crear el release) y `actions: read` (descargar el artifact de
otra ejecución).

### Por qué el APK publicado es el validado

El artifact transporta los bytes tal como quedaron en el run de validación:
`upload-artifact` archiva los tres archivos y `gh run download` los extrae sin
tocarlos; archivar un APK firmado no altera su bloque de firma. El SHA-256 se
recalcula sobre el archivo descargado y se compara con el `sha256` que el run de
validación calculó sobre ese mismo archivo; ese valor viaja dentro de
`update.json`, cuya firma ECDSA se verifica de nuevo antes de publicar. La cadena
es `update.json.sig` → `update.json.sha256` → APK: sustituir cualquiera de las
tres piezas rompe la verificación.

El `versionCode` se fija en la validación (número de run) y no se recalcula al
publicar. Por eso la ventana de publicación es de 30 días, lo que dura el
artifact: pasado ese plazo hay que validar otra vez y el `run_id` cambia.

### Un tag no se republica

Un tag con manifiesto ya publicado **no se republica**. El `versionCode` sale del
número de run, así que republicar un tag viejo le daría un número más alto que
el de una versión posterior, y la app instalaría una regresión encima de una
versión más nueva. El publicador se niega a subir un manifiesto si el release ya
tiene `update.json`; para cambiar notas, canal u obligatoriedad se publica un tag
nuevo con la versión siguiente.

La única excepción es una publicación que falló antes de subir el manifiesto: el
release queda con assets parciales y sin `update.json`, y el reintento de ese
mismo tag se permite porque no hay manifiesto que reemplazar. Para el reintento,
preferir el mismo artifact; si ya venció, una validación nueva del mismo tag
produce otro `run_id` y otro `versionCode`. Cualquiera de las dos opciones solo es
válida mientras no se haya publicado una versión posterior; si ya existe una, el
reintento le asignaría al tag viejo un `versionCode` mayor y hay que publicar un
tag nuevo.

### versionCode

`versionCode` es el número de run del workflow, no el `versionName`. Se asigna en
la validación y se publica tal cual: el publicador no lo recalcula. Crece de
forma monotónica en todo el repositorio, así que dos releases nunca comparten
`versionCode` y el OTA nunca ve un candidato "anterior" mientras cada tag se
publique una sola vez, en orden. No se edita a mano: la app compara
exclusivamente este campo.

### Canales

- `stable`: solo releases estables.
- `beta`: acepta releases `beta` y también estables.

Un release `beta` no se ofrece a quien está en `stable`. Un release estable sí
llega a `beta`, porque es más nuevo y el canal acepta ambos.

## Verificación después de publicar

Los dos workflows ya verifican la firma del APK, la firma del manifiesto, el
SHA-256 y la identidad del paquete. Después de publicar, conviene comprobar:

```bash
gh release view v1.2.0 --json assets --jq '.assets[].name'
```

Deben aparecer `copernicotv-<versión>.apk`, `update.json` y `update.json.sig`.
Descargar `update.json` y confirmar que `sha256` coincide con el APK publicado y
que `signingCertSha256` es la huella fijada en la app.

Para auditar el artifact antes de publicar sin escribir nada:

```bash
gh run download <run_id> -n ota-v1.2.0-<commit>-r<run_id>-a1 -D ~/ota-validado
sha256sum ~/ota-validado/*.apk
apksigner verify --print-certs ~/ota-validado/*.apk
```

La validación en dispositivo (detección, notificación, descarga, instalación y
conservación de datos) es la fase F-13 a F-16 de
`docs/evidence/validaciones-fisicas.md`; publicar no equivale a validar.

## Transición desde la instalación de pruebas

La app instalada hoy usa el keystore de depuración, que es efímero y distinto
del de producción. Android no permite instalar el primer APK release encima:
hay que desinstalar la versión de pruebas, aceptando la pérdida de datos
locales. Esa desinstalación solo se hace cuando exista un APK release firmado y
verificado, nunca antes.

Después de esa instalación limpia, todas las actualizaciones siguientes ya
preservan paquete y datos.

## Rollback

No se puede "volver" a una versión anterior por OTA: la app solo instala
`versionCode` estrictamente mayor. Para revertir un release defectuoso se
publica una versión nueva con `versionCode` mayor que contenga el código
anterior. Borrar el release de GitHub no alcanza: quien ya descargó el
manifiesto lo conserva y lo re-verifica al leer.
