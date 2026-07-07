#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DESTINATION="$ROOT/runtime/datapacks/Vanilla+_structures.zip"
URL='https://cdn.modrinth.com/data/61OqWYQI/versions/MgDgu5tP/Vanilla%2B_structures.zip'
ORIGINAL_SHA256='48e0138970348e566f2ccea581d07afc956b7c3a2c2e1e0518402d2a4e72ded1'
METADATA="$ROOT/ops/datapacks/vanilla-plus/pack.mcmeta"

mkdir -p "$(dirname "$DESTINATION")"
if [[ -f "$DESTINATION" ]] && unzip -p "$DESTINATION" pack.mcmeta | cmp --silent - "$METADATA"; then
  exit 0
fi

curl --fail --location --silent --show-error "$URL" --output "$DESTINATION.tmp"
echo "$ORIGINAL_SHA256  $DESTINATION.tmp" | sha256sum --check --status
zip -d "$DESTINATION.tmp" pack.mcmeta >/dev/null
(cd "$(dirname "$METADATA")" && zip -j "$DESTINATION.tmp" pack.mcmeta >/dev/null)
unzip -p "$DESTINATION.tmp" pack.mcmeta | cmp --silent - "$METADATA"
mv "$DESTINATION.tmp" "$DESTINATION"
