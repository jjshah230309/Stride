#!/usr/bin/env bash
# Creates the key that signs Stride for Android and prints the four GitHub secrets to add.
#
# Run it once, on your own computer. The key stays in .signing/ (git-ignored). Android only installs an
# update over an existing install when both are signed with the same key, so KEEP A COPY of the .jks file
# and its password somewhere safe. If it is lost, the only way to install a newer build is to uninstall
# the old one first, which deletes the app's data (make a backup in the app before that).
set -euo pipefail

cd "$(dirname "$0")/.."
DIR=".signing"
FILE="$DIR/stride-release.jks"
ALIAS="stride"

command -v keytool >/dev/null || { echo "keytool not found. Install a JDK (17 or newer) first." >&2; exit 1; }
if [ -e "$FILE" ]; then
  echo "$FILE already exists. Delete it first if you really want a new key (this changes the signature)." >&2
  exit 1
fi

mkdir -p "$DIR"
chmod 700 "$DIR"

if [ -n "${STRIDE_KEYSTORE_PASSWORD:-}" ]; then
  PASSWORD="$STRIDE_KEYSTORE_PASSWORD"
else
  PASSWORD="$(head -c 48 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | cut -c1-24)"
fi

keytool -genkeypair -v \
  -keystore "$FILE" -storetype PKCS12 \
  -alias "$ALIAS" -keyalg RSA -keysize 4096 -validity 10000 \
  -storepass "$PASSWORD" -keypass "$PASSWORD" \
  -dname "CN=Stride, O=Stride, C=GB" >/dev/null 2>&1
chmod 600 "$FILE"

B64="$(base64 < "$FILE" | tr -d '\n')"

cat <<MSG

Created $FILE

Add these four secrets in GitHub (Settings -> Secrets and variables -> Actions -> New repository secret):

  ANDROID_KEYSTORE_PASSWORD = $PASSWORD
  ANDROID_KEY_ALIAS         = $ALIAS
  ANDROID_KEY_PASSWORD      = $PASSWORD
  ANDROID_KEYSTORE_BASE64   = (the long text below)

$B64

Then push to main (or run the Android workflow by hand). It builds, signs, and publishes the APK under
Releases: "Stride for Android (latest)" always holds the newest one.

Keep a copy of $FILE and the password somewhere safe. The repository is public, so never commit them.
MSG
