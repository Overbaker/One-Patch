#!/usr/bin/env bash
# Generate a release signing keystore for DeadZone.
# Run ONCE, then BACK UP release.jks + the passwords. Losing them = unable to update existing installs.
set -euo pipefail

KEYSTORE="${KEYSTORE:-release.jks}"
ALIAS="${ALIAS:-deadzone}"
VALIDITY_DAYS="${VALIDITY_DAYS:-36500}"  # 100 years

if [[ -f "$KEYSTORE" ]]; then
  echo "❌ $KEYSTORE already exists. Refusing to overwrite. Move or rename it first." >&2
  exit 1
fi

if ! command -v keytool >/dev/null 2>&1; then
  echo "❌ keytool not found. Install JDK 17+ and re-run." >&2
  exit 1
fi

echo "🔑 Generating new release keystore: $KEYSTORE (alias: $ALIAS, validity: $VALIDITY_DAYS days)"
echo
read -srp "Enter STORE password: " STORE_PASS; echo
read -srp "Confirm STORE password: " STORE_PASS_CONFIRM; echo
[[ "$STORE_PASS" == "$STORE_PASS_CONFIRM" ]] || { echo "❌ Passwords do not match"; exit 1; }

read -srp "Enter KEY password (press enter to use STORE password): " KEY_PASS; echo
KEY_PASS="${KEY_PASS:-$STORE_PASS}"

read -p "Common Name (CN, e.g. DeadZone): " CN
read -p "Organization (O, e.g. overbaker): " ORG
read -p "Country (C, 2-letter, e.g. CN): " CC

keytool -genkey -v \
  -keystore "$KEYSTORE" \
  -alias "$ALIAS" \
  -keyalg RSA -keysize 2048 \
  -validity "$VALIDITY_DAYS" \
  -storepass "$STORE_PASS" \
  -keypass "$KEY_PASS" \
  -dname "CN=${CN:-DeadZone}, O=${ORG:-overbaker}, C=${CC:-CN}"

echo
echo "✅ Keystore generated: $(realpath "$KEYSTORE")"
echo
echo "📋 Next: create keystore.properties with these values:"
echo "    storeFile=../$KEYSTORE"
echo "    storePassword=<your-store-password>"
echo "    keyAlias=$ALIAS"
echo "    keyPassword=<your-key-password>"
echo
echo "🔐 BACK UP release.jks AND THE PASSWORDS. Losing them means:"
echo "    - You can NEVER ship updates to existing installs"
echo "    - Users would have to uninstall + reinstall a new app"

# Quick verification
echo
echo "📜 Certificate fingerprint:"
keytool -list -v -keystore "$KEYSTORE" -alias "$ALIAS" -storepass "$STORE_PASS" \
  | grep -E "SHA1|SHA-256|Valid" || true
