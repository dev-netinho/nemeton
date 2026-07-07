#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DESTINATION="$ROOT/runtime/datapacks/Vanilla+_structures.zip"
URL='https://cdn.modrinth.com/data/61OqWYQI/versions/MgDgu5tP/Vanilla%2B_structures.zip'
ORIGINAL_SHA256='48e0138970348e566f2ccea581d07afc956b7c3a2c2e1e0518402d2a4e72ded1'
METADATA="$ROOT/ops/datapacks/vanilla-plus/pack.mcmeta"

mkdir -p "$(dirname "$DESTINATION")"
if [[ ! -f "$DESTINATION" ]] || ! unzip -p "$DESTINATION" pack.mcmeta | cmp --silent - "$METADATA"; then
  curl --fail --location --silent --show-error "$URL" --output "$DESTINATION.tmp"
  echo "$ORIGINAL_SHA256  $DESTINATION.tmp" | sha256sum --check --status
  zip -d "$DESTINATION.tmp" pack.mcmeta >/dev/null
  (cd "$(dirname "$METADATA")" && zip -j "$DESTINATION.tmp" pack.mcmeta >/dev/null)
  unzip -p "$DESTINATION.tmp" pack.mcmeta | cmp --silent - "$METADATA"
  mv "$DESTINATION.tmp" "$DESTINATION"
fi

CITIZENS_DESTINATION="$ROOT/runtime/plugins/Citizens.jar"
CITIZENS_URL='https://ci.citizensnpcs.co/job/Citizens2/4211/artifact/dist/target/Citizens-2.0.43-b4211.jar'
CITIZENS_SHA256='aea786c361ce88bfc6c0a98214100d836321341a5e01e0281147cbd57a7683c6'
mkdir -p "$(dirname "$CITIZENS_DESTINATION")"
if [[ ! -f "$CITIZENS_DESTINATION" ]] || ! echo "$CITIZENS_SHA256  $CITIZENS_DESTINATION" | sha256sum --check --status; then
  curl --fail --location --silent --show-error "$CITIZENS_URL" --output "$CITIZENS_DESTINATION.tmp"
  echo "$CITIZENS_SHA256  $CITIZENS_DESTINATION.tmp" | sha256sum --check --status
  mv "$CITIZENS_DESTINATION.tmp" "$CITIZENS_DESTINATION"
fi
