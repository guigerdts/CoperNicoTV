#!/usr/bin/env bash
#
# Genera las claves permanentes de distribución de CoperNicoTV.
#
# Se ejecuta UNA sola vez, en un equipo de confianza, y nunca en CI: la salida
# son claves privadas que no deben pasar por ningún repositorio ni por un log.
#
#   bash tools/ota/generar-claves.sh [directorio]
#
# Directorio por defecto: ~/copernicotv-claves
#
# Produce:
#   release.keystore   Identidad de firma del APK. Perderla rompe las
#                      actualizaciones para siempre: un APK con otra firma no
#                      instala sobre el anterior.
#   ota-firma.pem      Clave privada ECDSA P-256 que firma update.json.
#                      No es la firma del APK; solo autentica el manifiesto.
#   valores-publicos.txt  Clave pública del manifiesto y huella del certificado,
#                      que sí se publican y se inyectan en la app.
#
# Al terminar imprime los comandos para cargar los secretos en GitHub y el
# respaldo que hay que hacer antes de seguir.

set -euo pipefail

DIRECTORIO="${1:-$HOME/copernicotv-claves}"
ALIAS="copernicotv"
REPOSITORIO="${OTA_REPOSITORIO:-guigerdts/CoperNicoTV}"

for HERRAMIENTA in keytool openssl base64 xxd; do
  if ! command -v "$HERRAMIENTA" >/dev/null 2>&1; then
    echo "Falta '$HERRAMIENTA'. Instalalo antes de continuar." >&2
    exit 1
  fi
done

if [ -e "$DIRECTORIO/release.keystore" ] || [ -e "$DIRECTORIO/ota-firma.pem" ]; then
  echo "Ya hay claves en $DIRECTORIO." >&2
  echo "No se sobrescriben: si las pisás, la app instalada deja de poder actualizarse." >&2
  echo "Para regenerar de cero, movelas a mano y volvé a ejecutar." >&2
  exit 2
fi

mkdir -p "$DIRECTORIO"
chmod 700 "$DIRECTORIO"

echo "== Claves de distribución de CoperNicoTV =="
echo "Directorio: $DIRECTORIO"
echo

read -r -s -p "Contraseña del keystore (mínimo 12 caracteres): " CLAVE_KEYSTORE
echo
read -r -s -p "Repetir contraseña: " CLAVE_KEYSTORE2
echo
if [ "$CLAVE_KEYSTORE" != "$CLAVE_KEYSTORE2" ]; then
  echo "Las contraseñas no coinciden." >&2
  exit 3
fi
if [ "${#CLAVE_KEYSTORE}" -lt 12 ]; then
  echo "La contraseña es demasiado corta." >&2
  exit 3
fi

read -r -s -p "Contraseña de la clave (Enter para usar la misma): " CLAVE_CLAVE
echo
CLAVE_CLAVE="${CLAVE_CLAVE:-$CLAVE_KEYSTORE}"

echo
echo "-- 1/4 Keystore del APK (RSA 4096, validez 27 años)"
keytool -genkeypair \
  -keystore "$DIRECTORIO/release.keystore" \
  -alias "$ALIAS" \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000 \
  -storepass "$CLAVE_KEYSTORE" \
  -keypass "$CLAVE_CLAVE" \
  -dname "CN=CoperNicoTV, OU=Distribucion, O=CoperNicoTV, C=AR" \
  -noprompt >/dev/null

echo "-- 2/4 Clave de firma del manifiesto (ECDSA P-256)"
openssl ecparam -name prime256v1 -genkey -noout -out "$DIRECTORIO/ota-firma.pem"
chmod 600 "$DIRECTORIO/ota-firma.pem"

echo "-- 3/4 Valores públicos"
CLAVE_PUBLICA_BASE64="$(
  openssl ec -in "$DIRECTORIO/ota-firma.pem" -pubout -outform DER 2>/dev/null |
    base64 -w 0
)"
HUELLA_CERTIFICADO="$(
  keytool -exportcert \
    -keystore "$DIRECTORIO/release.keystore" \
    -alias "$ALIAS" \
    -storepass "$CLAVE_KEYSTORE" 2>/dev/null |
    openssl dgst -sha256 -binary |
    xxd -p -c 256
)"

{
  echo "OTA_MANIFIESTO_PUBKEY=$CLAVE_PUBLICA_BASE64"
  echo "OTA_CERT_SHA256=$HUELLA_CERTIFICADO"
} >"$DIRECTORIO/valores-publicos.txt"

echo "-- 4/4 Resumen"
echo
echo "Clave pública del manifiesto (va en la app, no es secreta):"
echo "  OTA_MANIFIESTO_PUBKEY=$CLAVE_PUBLICA_BASE64"
echo
echo "Huella SHA-256 del certificado del APK (va en la app, no es secreta):"
echo "  OTA_CERT_SHA256=$HUELLA_CERTIFICADO"
echo
echo "Se guardaron en $DIRECTORIO/valores-publicos.txt"
echo
echo "== Paso siguiente: cargar los secretos en GitHub =="
echo "Ejecutá estas líneas vos mismo, desde este equipo:"
echo
cat <<COMANDOS
  gh secret set ANDROID_KEYSTORE_BASE64 \\
    --repo $REPOSITORIO \\
    --body "\$(base64 -w 0 "$DIRECTORIO/release.keystore")"
  gh secret set ANDROID_KEYSTORE_PASSWORD --repo $REPOSITORIO
  gh secret set ANDROID_KEY_ALIAS --repo $REPOSITORIO --body "$ALIAS"
  gh secret set ANDROID_KEY_PASSWORD --repo $REPOSITORIO
  gh secret set OTA_SIGNING_KEY_BASE64 \\
    --repo $REPOSITORIO \\
    --body "\$(base64 -w 0 "$DIRECTORIO/ota-firma.pem")"
  gh variable set OTA_MANIFIESTO_PUBKEY \\
    --repo $REPOSITORIO --body "$CLAVE_PUBLICA_BASE64"
  gh variable set OTA_CERT_SHA256 --repo $REPOSITORIO --body "$HUELLA_CERTIFICADO"
COMANDOS
echo
echo "Cada 'gh secret set' sin --body pide el valor por entrada oculta:"
echo "  ANDROID_KEYSTORE_PASSWORD -> la contraseña del keystore"
echo "  ANDROID_KEY_PASSWORD      -> la contraseña de la clave"
echo
echo "== Respaldo obligatorio =="
echo "Antes de publicar cualquier release, guardá release.keystore y ota-firma.pem"
echo "en dos lugares offline distintos, junto con las contraseñas en un gestor"
echo "de contraseñas. Sin ese respaldo, perder el equipo significa no poder"
echo "volver a actualizar la app instalada."
echo
echo "Este directorio NO se versiona: está fuera del repositorio a propósito."
