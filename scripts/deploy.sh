#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
./mvnw clean package
mkdir -p runtime/plugins
cp target/nemeton-core-*-SNAPSHOT.jar runtime/plugins/NemetonCore.jar
docker compose -f compose.yml up -d
docker compose -f compose.yml ps

