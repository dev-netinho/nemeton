#!/usr/bin/env bash
set -euo pipefail
VERSION="3.9.11"
ROOT="$(cd "$(dirname "$0")" && pwd)"
HOME_DIR="$ROOT/.cache/apache-maven-$VERSION"
ARCHIVE="$ROOT/.cache/apache-maven-$VERSION-bin.tar.gz"
if [[ ! -x "$HOME_DIR/bin/mvn" ]]; then
  mkdir -p "$ROOT/.cache"
  curl -fsSL "https://archive.apache.org/dist/maven/maven-3/$VERSION/binaries/apache-maven-$VERSION-bin.tar.gz" -o "$ARCHIVE"
  tar -xzf "$ARCHIVE" -C "$ROOT/.cache"
fi
exec "$HOME_DIR/bin/mvn" "$@"

