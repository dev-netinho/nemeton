#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PID_FILE="$ROOT/runtime/map-tunnel.pid"
LOG_FILE="$ROOT/runtime/map-tunnel.log"
URL_FILE="$ROOT/data/minecraft/plugins/NemetonCore/map-url.txt"
CLOUDFLARED="${CLOUDFLARED_BIN:-/usr/bin/cloudflared}"

mkdir -p "$ROOT/runtime" "$(dirname "$URL_FILE")"
if [[ -f "$PID_FILE" ]] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
  url="$(grep -Eo 'https://[a-z0-9-]+\.trycloudflare\.com' "$LOG_FILE" | head -1 || true)"
  if [[ -n "$url" ]]; then
    printf '%s\n' "$url" > "$URL_FILE"
    printf '%s\n' "$url"
    exit 0
  fi
  kill "$(cat "$PID_FILE")" || true
fi

: > "$LOG_FILE"
nohup "$CLOUDFLARED" --config /dev/null tunnel --no-autoupdate \
  --url http://127.0.0.1:8100 \
  --http-host-header 127.0.0.1:8100 \
  --logfile "$LOG_FILE" >/dev/null 2>&1 &
echo $! > "$PID_FILE"

for _ in $(seq 1 30); do
  url="$(grep -Eo 'https://[a-z0-9-]+\.trycloudflare\.com' "$LOG_FILE" | head -1 || true)"
  if [[ -n "$url" ]]; then
    printf '%s\n' "$url" > "$URL_FILE"
    printf '%s\n' "$url"
    exit 0
  fi
  sleep 1
done

echo "O túnel do mapa não forneceu uma URL em 30 segundos." >&2
exit 1
