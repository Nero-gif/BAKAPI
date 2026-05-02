#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

APP_NAME="BAKAPI"
APP_VERSION="${1:-1.0.0}"
MAIN_JAR="target/bakapi-1.0-SNAPSHOT-all.jar"
DIST_DIR="dist"

mvn -q -DskipTests clean package

if [[ ! -f "$MAIN_JAR" ]]; then
  echo "Nenalezen shaded JAR: $MAIN_JAR"
  exit 1
fi

mkdir -p "$DIST_DIR"

jpackage \
  --name "$APP_NAME" \
  --app-version "$APP_VERSION" \
  --input target \
  --main-jar "$(basename "$MAIN_JAR")" \
  --main-class cz.nero.bakapi.Main \
  --type deb \
  --dest "$DIST_DIR" \
  --linux-shortcut \
  --linux-package-name bakapi \
  --linux-menu-group Education

echo "Hotovo: balíček je v $DIST_DIR/"
