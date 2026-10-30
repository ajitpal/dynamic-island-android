#!/usr/bin/env bash
# Write and sign the live module catalogue for the website (CI only; needs the release key).
#   KEYSTORE_FILE, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD   tools/sign_catalog.sh _site
# Output: <out>/catalog.json and <out>/catalog.json.sig (openssl dgst -sha256 -sign; the island and the
# Updater verify it with their own signing certificate, see module-sdk SignedCatalog.kt).
set -euo pipefail
out="${1:?output folder}"
work="$(mktemp -d)"; trap 'rm -rf "$work"' EXIT
serial="$(git log -1 --format=%ct -- catalog/)"   # the time the catalogue last changed: never goes back
python3 tools/gen_catalog.py --live "$out/catalog.json" --serial "$serial"
keytool -importkeystore -noprompt -srckeystore "$KEYSTORE_FILE" -srcstorepass "$KEYSTORE_PASSWORD" -srcalias "$KEY_ALIAS" \
  -srckeypass "$KEY_PASSWORD" -destkeystore "$work/k.p12" -deststoretype PKCS12 -deststorepass "$KEYSTORE_PASSWORD" -destkeypass "$KEYSTORE_PASSWORD" >/dev/null
openssl pkcs12 -in "$work/k.p12" -nocerts -nodes -passin env:KEYSTORE_PASSWORD | openssl pkey -out "$work/key.pem"
openssl pkcs12 -in "$work/k.p12" -clcerts -nokeys -passin env:KEYSTORE_PASSWORD | openssl x509 -pubkey -noout > "$work/pub.pem"
openssl dgst -sha256 -sign "$work/key.pem" -out "$out/catalog.json.sig" "$out/catalog.json"
# Check it before publishing, with the certificate's public key.
openssl dgst -sha256 -verify "$work/pub.pem" -signature "$out/catalog.json.sig" "$out/catalog.json"
echo "Signed catalogue: serial $serial"
