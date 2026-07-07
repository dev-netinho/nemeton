#!/usr/bin/env bash
set -u

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

for _ in $(seq 1 1440); do
  set -a
  # shellcheck disable=SC1091
  source .env
  set +a
  if ./scripts/provision-discord.py --root "$ROOT"; then
    while true; do
      online="$(docker compose exec -T minecraft rcon-cli list 2>/dev/null || true)"
      if [[ "$online" == *"There are 0 of"* ]]; then
        break
      fi
      docker compose exec -T minecraft rcon-cli \
        "say §eDiscord configurado. O Nemeton reiniciará quando todos desconectarem." >/dev/null 2>&1 || true
      sleep 30
    done
    docker compose restart minecraft
    exit 0
  fi
  sleep 15
done

echo "A autorização do bot não ocorreu dentro da janela de monitoramento." >&2
exit 1
