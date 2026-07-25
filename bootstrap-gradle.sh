#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
DIR="$ROOT/gradle/wrapper"
JAR="$DIR/gradle-wrapper.jar"
URL="https://raw.githubusercontent.com/gradle/gradle/v8.13.0/gradle/wrapper/gradle-wrapper.jar"
EXPECTED="81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f"
mkdir -p "$DIR"
if [ ! -s "$JAR" ]; then
  if command -v curl >/dev/null 2>&1; then
    curl -fL --retry 3 "$URL" -o "$JAR"
  elif command -v wget >/dev/null 2>&1; then
    wget -O "$JAR" "$URL"
  else
    echo "Brak curl lub wget." >&2
    exit 1
  fi
fi
if command -v sha256sum >/dev/null 2>&1; then
  ACTUAL=$(sha256sum "$JAR" | awk '{print $1}')
elif command -v shasum >/dev/null 2>&1; then
  ACTUAL=$(shasum -a 256 "$JAR" | awk '{print $1}')
else
  echo "Brak programu sha256sum lub shasum." >&2
  exit 1
fi
[ "$EXPECTED" = "$ACTUAL" ] || { rm -f "$JAR"; echo "Błędna suma SHA-256." >&2; exit 1; }
echo "Gotowe. Możesz teraz otworzyć projekt w Android Studio."
