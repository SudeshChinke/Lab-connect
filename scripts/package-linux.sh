#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

mvn --batch-mode -DskipTests clean package
mkdir -p dist/linux
rm -f dist/linux/*

jpackage \
  --type deb \
  --name LabConnect \
  --app-version 1.0.0 \
  --vendor LabConnect \
  --description "Local network messaging and file sharing" \
  --input target \
  --main-jar labconnect-core-1.0.0-SNAPSHOT.jar \
  --main-class com.labconnect.desktop.Launcher \
  --dest dist/linux \
  --linux-package-name labconnect \
  --linux-app-category Network \
  --linux-shortcut \
  --java-options "-Dlabconnect.packaged=true"

echo "Built Linux Mint installer in dist/linux/"
