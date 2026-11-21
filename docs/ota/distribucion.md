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

## Publicar una versión

1. Elegir el tag. `v1.2.0` publica al canal `stable`; cualquier sufijo
   (`v1.2.0-beta.1`, `v1.2.0-rc.1`) publica al canal `beta`, porque un sufijo
   marca una versión previa a la estable. Un tag que no produzca un
   `versionName` numérico válido se rechaza antes de compilar.
2. Crear el tag anotado: el cuerpo del tag se usa como notas del manifiesto si
   no se pasan otras.

   ```bash
   git tag -a v1.2.0 -m "Corrección de reproducción y ajustes de OTA"
   git push origin v1.2.0
   ```

3. El workflow `Release OTA` hace el resto:
   - materializa las claves desde los secretos;
   - ensambla `:app:assembleRelease` con `versionName` del tag,
     `versionCode` del número de run, la clave pública y la huella fijadas;
   - lee el `minSdk` real del APK y verifica que la firma coincida con
     `OTA_CERT_SHA256` (si no coincide, **no publica**);
   - calcula SHA-256 y tamaño, escribe `update.json`, lo firma y verifica la
     firma antes de subir;
   - crea o actualiza el release con el APK, `update.json` y
     `update.json.sig`.

Para republicar un tag ya existente con otro canal, otro estado de
obligatoriedad u otras notas, se usa `workflow_dispatch` con el tag como
entrada. El APK se reemplaza con `--clobber` y el manifiesto se regenera.

### versionCode

`versionCode` es el número de run del workflow, no el `versionName`. Crece de
forma monotónica en todo el repositorio, así que dos releases nunca comparten
`versionCode` y el OTA nunca ve un candidato "anterior". No se edita a mano: la
app compara exclusivamente este campo.

### Canales

- `stable`: solo releases estables.
- `beta`: acepta releases `beta` y también estables.

Un release `beta` no se ofrece a quien está en `stable`. Un release estable sí
llega a `beta`, porque es más nuevo y el canal acepta ambos.

## Verificación después de publicar

El propio workflow ya verifica firma del APK, firma del manifiesto y
coincidencia de identidad. Después de publicar, conviene comprobar:

```bash
gh release view v1.2.0 --json assets --jq '.assets[].name'
```

Deben aparecer `copernicotv-<versión>.apk`, `update.json` y `update.json.sig`.
Descargar `update.json` y confirmar que `sha256` coincide con el APK publicado y
que `signingCertSha256` es la huella fijada en la app.

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
