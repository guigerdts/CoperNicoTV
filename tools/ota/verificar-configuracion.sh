#!/usr/bin/env bash
#
# Verifica que la configuración de publicación esté completa y coherente ANTES
# de publicar un release. No imprime claves privadas ni contraseñas.
#
#   bash tools/ota/verificar-configuracion.sh [--respaldo DIRECTORIO]
#
# Comprueba:
#   1. Que existan release.keystore, ota-firma.pem y valores-publicos.txt, con
#      permisos cerrados.
#   2. Que la huella del certificado del keystore y la clave pública de firma
#      coincidan con las variables ya cargadas en GitHub. Si no coinciden, el
#      workflow se niega a publicar (o publicaría un APK que la app rechaza).
#   3. Que los cinco secretos de Actions existan (por nombre: GitHub no deja
#      leer su valor).
#   4. Opcionalmente, que un respaldo en otro directorio tenga exactamente los
#      mismos bytes.
#
# Sale con código 0 solo si todo está en orden.

set -euo pipefail

REPOSITORIO="${OTA_REPOSITORIO:-guigerdts/CoperNicoTV}"
DIRECTORIO="$HOME/copernicotv-claves"
RESPALDO=""
ALIAS="copernicotv"
FALLOS=0

uso() {
  cat <<'AYUDA'
Uso: bash tools/ota/verificar-configuracion.sh [opciones]

  --directorio DIR   Directorio con las claves (por defecto ~/copernicotv-claves)
  --respaldo DIR     Directorio de respaldo a comparar byte a byte
  --repo OWNER/REPO  Repositorio destino (por defecto el del proyecto)
  -h, --help         Esta ayuda

No pide ni muestra claves privadas. Solo pide la contraseña del keystore, por
entrada oculta, para poder exportar su certificado y calcular la huella.
AYUDA
}

ok() { printf 'OK     %s\n' "$*"; }
falla() {
  printf 'FALTA  %s\n' "$*"
  FALLOS=$((FALLOS + 1))
}
aviso() { printf 'AVISO  %s\n' "$*"; }

while [ $# -gt 0 ]; do
  case "$1" in
    --directorio)
      DIRECTORIO="${2:?--directorio necesita un valor}"
      shift 2
      ;;
    --respaldo)
      RESPALDO="${2:?--respaldo necesita un valor}"
      shift 2
      ;;
    --repo)
      REPOSITORIO="${2:?--repo necesita un valor}"
      shift 2
      ;;
    -h | --help)
      uso
      exit 0
      ;;
    *)
      echo "Opción desconocida: $1" >&2
      uso >&2
      exit 2
      ;;
  esac
done

for HERRAMIENTA in keytool openssl base64 xxd sha256sum; do
  if ! command -v "$HERRAMIENTA" >/dev/null 2>&1; then
    echo "Falta '$HERRAMIENTA'. Instalalo antes de continuar." >&2
    exit 1
  fi
done

echo "== 1/4 Archivos locales =="
for ARCHIVO in release.keystore ota-firma.pem valores-publicos.txt; do
  if [ -f "$DIRECTORIO/$ARCHIVO" ]; then
    ok "$DIRECTORIO/$ARCHIVO"
  else
    falla "$DIRECTORIO/$ARCHIVO"
  fi
done
if [ "$FALLOS" -gt 0 ]; then
  echo
  echo "Generá las claves primero: bash tools/ota/generar-claves.sh"
  exit 1
fi

for ARCHIVO in release.keystore ota-firma.pem; do
  MODO="$(stat -c %a "$DIRECTORIO/$ARCHIVO")"
  case "$MODO" in
    400 | 600) ok "permisos de $ARCHIVO ($MODO)" ;;
    *) falla "permisos de $ARCHIVO ($MODO): deben ser 600 o 400" ;;
  esac
done

echo
echo "== 2/4 Coherencia entre las claves locales y los valores esperados =="
read -r -s -p "Contraseña del keystore: " CLAVE_KEYSTORE
echo
if [ -z "$CLAVE_KEYSTORE" ]; then
  echo "Sin contraseña no se puede calcular la huella del certificado." >&2
  exit 1
fi

CERT_TMP="$(mktemp)"
trap 'rm -f "$CERT_TMP"' EXIT
if ! keytool -exportcert \
  -keystore "$DIRECTORIO/release.keystore" \
  -alias "$ALIAS" \
  -storepass "$CLAVE_KEYSTORE" >"$CERT_TMP" 2>/dev/null; then
  echo "No se pudo exportar el certificado con esa contraseña y el alias '$ALIAS'." >&2
  exit 1
fi
TAMANO_CERT="$(wc -c <"$CERT_TMP")"
# keytool escribe un mensaje en stdout cuando falla; hashearlo daría una huella
# válida de basura, indistinguible de una huella real.
if [ "$TAMANO_CERT" -lt 256 ]; then
  echo "El certificado exportado mide $TAMANO_CERT bytes: no es un certificado X.509." >&2
  exit 1
fi
HUELLA_LOCAL="$(openssl dgst -sha256 -binary <"$CERT_TMP" | xxd -p -c 256)"
PROPIETARIO="$(keytool -printcert -file "$CERT_TMP" 2>/dev/null | sed -n 's/^Owner: //p' | head -1)"
if [ -n "$PROPIETARIO" ]; then
  aviso "titular del certificado: $PROPIETARIO"
fi

PUBLICA_LOCAL="$(
  openssl ec -in "$DIRECTORIO/ota-firma.pem" -pubout -outform DER 2>/dev/null |
    base64 -w 0
)" || true
if [ -z "$PUBLICA_LOCAL" ]; then
  echo "ota-firma.pem no es una clave EC válida." >&2
  exit 1
fi

PUBLICA_ARCHIVO="$(
  sed -n 's/^OTA_MANIFIESTO_PUBKEY=//p' "$DIRECTORIO/valores-publicos.txt" | head -1
)"
HUELLA_ARCHIVO="$(
  sed -n 's/^OTA_CERT_SHA256=//p' "$DIRECTORIO/valores-publicos.txt" | head -1
)"

if [ "$PUBLICA_ARCHIVO" = "$PUBLICA_LOCAL" ]; then
  ok "valores-publicos.txt coincide con ota-firma.pem"
else
  falla "valores-publicos.txt no coincide con ota-firma.pem (archivo viejo o de otra clave)"
fi
if [ "$HUELLA_ARCHIVO" = "$HUELLA_LOCAL" ]; then
  ok "valores-publicos.txt coincide con el keystore"
else
  falla "valores-publicos.txt no coincide con el keystore (archivo viejo o de otra clave)"
fi

echo
echo "== 3/4 Secretos y variables en GitHub =="
if ! command -v gh >/dev/null 2>&1; then
  falla "gh no está instalado"
elif ! gh auth status >/dev/null 2>&1; then
  falla "gh no está autenticado"
else
  SECRETOS="$(
    gh secret list --repo "$REPOSITORIO" --json name --jq '.[].name' 2>/dev/null || true
  )"
  for NOMBRE in ANDROID_KEYSTORE_BASE64 ANDROID_KEYSTORE_PASSWORD ANDROID_KEY_ALIAS \
    ANDROID_KEY_PASSWORD OTA_SIGNING_KEY_BASE64; do
    if grep -qx "$NOMBRE" <<<"$SECRETOS"; then
      ok "secreto $NOMBRE"
    else
      falla "secreto $NOMBRE"
    fi
  done

  VARIABLES="$(
    gh variable list --repo "$REPOSITORIO" --json name,value \
      --jq '.[] | "\(.name)=\(.value)"' 2>/dev/null || true
  )"
  HUELLA_REMOTA="$(
    sed -n 's/^OTA_CERT_SHA256=//p' <<<"$VARIABLES" | head -1
  )"
  PUBLICA_REMOTA="$(
    sed -n 's/^OTA_MANIFIESTO_PUBKEY=//p' <<<"$VARIABLES" | head -1
  )"

  if [ -z "$HUELLA_REMOTA" ]; then
    falla "variable OTA_CERT_SHA256"
  elif [ "$HUELLA_REMOTA" = "$HUELLA_LOCAL" ]; then
    ok "variable OTA_CERT_SHA256 coincide con el keystore"
  else
    falla "variable OTA_CERT_SHA256 no coincide con el keystore: el workflow rechazaría el APK"
    echo "       local:  $HUELLA_LOCAL"
    echo "       GitHub: $HUELLA_REMOTA"
  fi

  if [ -z "$PUBLICA_REMOTA" ]; then
    falla "variable OTA_MANIFIESTO_PUBKEY"
  elif [ "$PUBLICA_REMOTA" = "$PUBLICA_LOCAL" ]; then
    ok "variable OTA_MANIFIESTO_PUBKEY coincide con ota-firma.pem"
  else
    falla "variable OTA_MANIFIESTO_PUBKEY no coincide con ota-firma.pem: la app rechazaría todos los manifiestos"
  fi
fi

echo
echo "== 4/4 Respaldo =="
printf 'sha256 %s  %s\n' "$(sha256sum "$DIRECTORIO/release.keystore" | cut -d' ' -f1)" "release.keystore"
printf 'sha256 %s  %s\n' "$(sha256sum "$DIRECTORIO/ota-firma.pem" | cut -d' ' -f1)" "ota-firma.pem"
if [ -n "$RESPALDO" ]; then
  for ARCHIVO in release.keystore ota-firma.pem; do
    if [ ! -f "$RESPALDO/$ARCHIVO" ]; then
      falla "respaldo sin $ARCHIVO en $RESPALDO"
    elif cmp -s "$DIRECTORIO/$ARCHIVO" "$RESPALDO/$ARCHIVO"; then
      ok "respaldo idéntico de $ARCHIVO"
    else
      falla "respaldo distinto de $ARCHIVO en $RESPALDO"
    fi
  done
else
  aviso "no se comparó ningún respaldo: repetí con --respaldo DIRECTORIO"
  aviso "las contraseñas no se pueden verificar desde acá: guardalas en tu gestor"
fi

echo
if [ "$FALLOS" -eq 0 ]; then
  echo "Configuración completa: el workflow puede publicar."
  exit 0
fi
echo "$FALLOS comprobación(es) pendiente(s). No publiques todavía."
exit 1
