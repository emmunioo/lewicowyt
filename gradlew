#!/bin/sh
set -eu

APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
WRAPPER_DIR="$APP_HOME/gradle/wrapper"
WRAPPER_JAR="$WRAPPER_DIR/gradle-wrapper.jar"
WRAPPER_URL="https://raw.githubusercontent.com/gradle/gradle/v8.13.0/gradle/wrapper/gradle-wrapper.jar"
EXPECTED_WRAPPER_SHA256="81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f"

mkdir -p "$WRAPPER_DIR"
if [ ! -s "$WRAPPER_JAR" ]; then
  echo "Pobieranie oficjalnego Gradle Wrapper 8.13..."
  if command -v curl >/dev/null 2>&1; then
    curl -fL --retry 3 --connect-timeout 20 "$WRAPPER_URL" -o "$WRAPPER_JAR"
  elif command -v wget >/dev/null 2>&1; then
    wget -O "$WRAPPER_JAR" "$WRAPPER_URL"
  else
    echo "Brak curl/wget. Otwórz projekt w Android Studio albo zainstaluj curl." >&2
    exit 1
  fi

fi

if command -v sha256sum >/dev/null 2>&1; then
  ACTUAL=$(sha256sum "$WRAPPER_JAR" | awk '{print $1}')
elif command -v shasum >/dev/null 2>&1; then
  ACTUAL=$(shasum -a 256 "$WRAPPER_JAR" | awk '{print $1}')
else
  echo "Błąd: brak programu sha256sum lub shasum do weryfikacji Gradle Wrapper." >&2
  exit 1
fi
if [ "$ACTUAL" != "$EXPECTED_WRAPPER_SHA256" ]; then
  echo "Błąd: suma kontrolna Gradle Wrapper nie pasuje." >&2
  exit 1
fi

if [ -n "${JAVA_HOME:-}" ]; then
  JAVACMD="$JAVA_HOME/bin/java"
else
  JAVACMD=java
fi
if ! command -v "$JAVACMD" >/dev/null 2>&1 && [ ! -x "$JAVACMD" ]; then
  echo "Nie znaleziono Javy. Uruchom projekt z Android Studio (zawiera JBR/JDK)." >&2
  exit 1
fi

exec "$JAVACMD" -Dorg.gradle.appname=gradlew -classpath "$WRAPPER_JAR" org.gradle.wrapper.GradleWrapperMain "$@"
