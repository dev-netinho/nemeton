#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

if [[ ! -f .env ]]; then
  echo "Crie .env a partir de .env.example antes do deploy." >&2
  exit 1
fi

for key in MARIADB_ROOT_PASSWORD DB_PASSWORD RCON_PASSWORD; do
  value="$(grep -E "^${key}=" .env | tail -1 | cut -d= -f2- || true)"
  if [[ -z "$value" || "$value" == replace-with-* || "$value" == "change-me" ]]; then
    echo "Defina ${key} com um segredo real no .env antes do deploy." >&2
    exit 1
  fi
done

./mvnw clean package
./scripts/fetch-content.sh
mkdir -p runtime/plugins
cp target/nemeton-core-*-SNAPSHOT.jar runtime/plugins/NemetonCore.jar
docker compose -f compose.yml config --quiet
docker compose -f compose.yml up -d
docker compose -f compose.yml ps

if command -v cloudflared >/dev/null 2>&1; then
  ./scripts/start-map-tunnel.sh
fi
