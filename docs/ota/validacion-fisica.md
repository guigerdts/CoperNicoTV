# Validación física del canal OTA

Runbook de T6: los cuatro escenarios F-13..F-16 de
`docs/evidence/validaciones-fisicas.md`. El OTA **no se declara terminado porque
compile o porque CI esté en verde**: F-15 es el criterio de cierre.

Orden del pipeline que hay que tener presente al leer los resultados: el
manifiesto se verifica por firma antes de decidir nada; el APK se verifica en
dos tramos, primero tamaño y SHA-256 durante y después de la descarga, y después
paquete, `versionCode`, `minSdk` y huella del certificado. Un caso que falla en
el primer tramo nunca llega al segundo.

## 1. Puerta previa: no publicar antes de esto

```bash
bash tools/ota/verificar-configuracion.sh --respaldo /ruta/al/respaldo
```

Tiene que salir con código 0. Comprueba que existan las claves con permisos
cerrados, que `release.keystore` y `ota-firma.pem` coincidan con
`valores-publicos.txt`, que las variables `OTA_CERT_SHA256` y
`OTA_MANIFIESTO_PUBKEY` de GitHub coincidan con esas claves, que los cinco
secretos de Actions existan por nombre, que el respaldo tenga bytes idénticos y
muestra el titular del certificado (debe decir `C=CO`).

Si falta o no coincide algo, el workflow se niega a publicar o publica un APK
que la app rechaza. No se publica nada hasta que esa comprobación pase.

## 2. Preparación de la campaña

- ADB inalámbrico habilitado y `adb devices` mostrando el dispositivo. El ADB
  volvió a estar operativo el 2026-11-19; la campaña no arranca si el dispositivo
  no responde, y no se repiten CAM-09-D/E sobre una instalación sin claves.
- Un **release publicado** con la identidad de producción. Un APK de CI (debug,
  keystore efímero) no sirve: `release.yml` y `release-publish.yml` son los que
  producen el primer APK instalable.
- Raíz de evidencia `campana-fisica-<fecha>/`, con `tools/physical-qa/pqa` para
  lo que ADB puede verificar mecánicamente y capturas para lo que no.
- Canal del dispositivo en **`beta`** durante toda la campaña: acepta tanto
  releases `beta` como `stable`, así los manifiestos de prueba son elegibles sin
  tocar el canal real.
- La versión instalada tiene que ser la release firmada con la clave de
  producción. Si no lo es, F-14..F-16 miden otra cosa y no sirven.

## 3. F-13 — instalación limpia release e importación

```bash
adb uninstall com.copernicotv.app          # destruye las claves Keystore: requiere autorización expresa
adb install copernicotv-<version>.apk
adb shell dumpsys package com.copernicotv.app | grep -E "versionCode|versionName"
```

La desinstalación no es opcional cuando la instalación previa está firmada con
otra identidad (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`), y no es inocua: las claves
AES/HMAC viven en Android Keystore, no en el datadir, así que cualquier catálogo
previo queda con URL ilegibles. Es una pérdida única y consciente, no un efecto
colateral a ignorar.

Abrir la app, importar una Lista M3U real y confirmar catálogo y reproducción.
En Ajustes > Actualizaciones, la versión instalada debe coincidir con el
`versionName` del APK y el canal debe ser el elegido.

**Esperado**: la app arranca, la Lista importa con su informe y el canal informa
la versión correcta sin ofrecer actualización.

## 4. F-14 — segunda versión detectada, notificada y descargada

Publicar la versión siguiente. El `versionCode` sale del número de run de
validación, así que siempre es mayor. La publicación son **dos** workflows: uno
valida y produce el artifact, otro publica esos mismos bytes.

```bash
git tag -a v1.0.1 -m "Corrección de reproducción"
git push origin v1.0.1
gh run watch -R guigerdts/CoperNicoTV        # workflow "Release OTA": valida y produce el artifact
gh run list --workflow=release.yml --limit 1 --json databaseId --jq '.[0].databaseId'   # <run_id>
gh workflow run release-publish.yml -f tag=v1.0.1 -f run_id=<run_id>
gh release view v1.0.1 --json assets --jq '.assets[].name'
```

Deben estar el APK, `update.json` y `update.json.sig`. El workflow `Release OTA`
**no publica nada por sí solo**: si `gh release view` no encuentra el tag después
de ejecutar el bloque, falta el paso `release-publish.yml`. En el dispositivo,
pulsar **Buscar** en Ajustes > Actualizaciones y esperar la comprobación
periódica como segundo camino.

**Esperado**: notificación de versión disponible, descarga con progreso,
verificación completa y botón de instalar. Ningún aviso de hash ni de firma.

### Descarga interrumpida y descarga duplicada (se ejecutan acá)

Antes de instalar, con la descarga a medias:

1. Activar modo avión. La descarga debe detenerse sin borrar el parcial.
2. Comprobar el parcial:
   `adb shell run-as com.copernicotv.app ls -l files/ota`
   Debe existir `ota-<versionCode>.apk.part` con tamaño no nulo.
3. Desactivar modo avión y volver a comprobar. **Esperado**: la descarga
   continúa desde el parcial (`Range`), no reinicia desde cero.
4. Con el APK ya verificado, pulsar **Buscar** dos veces. **Esperado**: no se
   descarga de nuevo; se reutiliza el APK verificado y se ofrece instalar.

## 5. F-15 — instalación sobre la anterior y conservación de datos

Es el criterio que decide el cierre del OTA.

Antes de instalar, capturar el estado con el extractor del proyecto, que copia
`copernicotv.db` junto con `-wal` y `-shm` sin tocar la base del dispositivo:

```bash
cd tools/physical-qa && ./pqa --help     # captura de base y diagnóstico
```

Con esa copia en un directorio temporal, contar la evidencia que importa:

```bash
sqlite3 antes.db "select 'Lista', count(*) from Lista
  union all select 'Canal', count(*) from Canal
  union all select 'Favorito', count(*) from Favorito
  union all select 'OcultacionCanal', count(*) from OcultacionCanal
  union all select 'Historial', count(*) from Historial;"
```

Instalar aceptando el diálogo del sistema, volver a la app y revisar: Listas y
sus URL (deben descifrarse), favoritos, ocultaciones, historial, ajustes de
reproducción y canal OTA. Volver a capturar y comparar los conteos con el
antes.

**Esperado**: la app queda en la versión nueva sin diálogo de desinstalación,
todos los datos anteriores siguen presentes y una comprobación posterior
informa "al día".

## 6. F-16 — caminos de error

Precondición: el dispositivo está **al día** (no hay actualización real
pendiente), así que cualquier oferta proviene del manifiesto de prueba.

Mecánica común: un release desechable por caso, marcado como preliminar para que
no se confunda con uno real, con `channel: beta` y un `versionCode` mayor que el
instalado. Se borra en cuanto se registra el resultado.

```bash
gh release create v0.0.1-prueba --prerelease --title "Prueba F-16" --notes "No usar"
gh release upload v0.0.1-prueba update.json update.json.sig --clobber
# ... registrar el resultado ...
gh release delete v0.0.1-prueba --yes --cleanup-tag
```

Nunca dejar uno de estos releases publicado: si su `versionCode` es el mayor, es
lo que la app va a ver.

### Casos que usan un manifiesto alterado

Partir de un `update.json` real y firmarlo con la clave que corresponda:

```bash
gh release download v1.0.1 --pattern update.json --dir /tmp/f16 --clobber
cp /tmp/f16/update.json /tmp/f16/base.json
jq '.versionCode = 9999 | .versionName = "9.9.9"
    | .downloadUrl = "https://github.com/guigerdts/CoperNicoTV/releases/download/v0.0.1-prueba/copernicotv-9.9.9.apk"
    | .releaseUrl = "https://github.com/guigerdts/CoperNicoTV/releases/tag/v0.0.1-prueba"' \
  /tmp/f16/base.json > /tmp/f16/update.json
openssl dgst -sha256 -sign ~/copernicotv-claves/ota-firma.pem \
  -out /tmp/f16/update.json.sig /tmp/f16/update.json
```

- **A — manifiesto inválido**: quitar un campo requerido
  (`jq 'del(.sizeBytes)'`) y firmar con la clave real. **Esperado**: no se
  ofrece actualización y el manifiesto se rechaza por campo faltante.
- **B — firma incompatible**: dejar el manifiesto válido y firmarlo con otra
  clave:
  `openssl ecparam -name prime256v1 -genkey -noout -out /tmp/f16/ajena.pem`,
  firmar con `ajena.pem`. **Esperado**: rechazo por firma; no se ofrece nada.
- **C — hash incorrecto**: manifiesto válido firmado con la clave real, pero con
  `sha256` cambiado y el APK real subido como activo (`sizeBytes` correcto, para
  que falle por hash y no por tamaño). **Esperado**: la descarga termina y el
  APK se descarta por hash; no se ofrece instalar y el parcial no se reutiliza.
- **D — APK corrupto**: subir un APK truncado y un manifiesto con el `sha256` y
  `sizeBytes` de esa versión truncada, firmado con la clave real. **Esperado**:
  rechazo tipado del APK (ilegible o inválido según dónde se corte), sin
  ofrecer instalar y sin tocar el estado guardado.

Registrar en cada caso el mensaje exacto que muestra la app y la línea de
`logcat` correspondiente; si no hay mensaje visible, anotarlo también: la app no
debe prometer un estado que no puede sostener.

## 7. Cierre

- Volcar los resultados en `docs/evidence/validaciones-fisicas.md`: PASS, FAIL,
  BLOCKED o INCONCLUSIVE por F-13..F-16, con archivo de evidencia.
- Borrar los releases de prueba y confirmar que la app vuelve a "al día".
- T6 se cierra solo cuando F-15 pasa. Cualquier otra combinación deja la entrega
  en pendiente, aunque el resto esté en verde.
