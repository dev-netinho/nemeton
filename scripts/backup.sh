#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

if [[ ! -f .env ]]; then
  echo "Arquivo .env não encontrado." >&2
  exit 1
fi

set -a
source .env
set +a
mkdir -p backups/dumps
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
docker compose -f compose.yml exec -T minecraft rcon-cli save-all flush
docker compose -f compose.yml exec -T database mariadb-dump -u"$DB_USER" -p"$DB_PASSWORD" --single-transaction "$DB_NAME" > "backups/dumps/nemeton-$STAMP.sql"

tar -C data -czf "backups/dumps/nemeton-world-$STAMP.tgz" minecraft

if command -v restic >/dev/null 2>&1 && [[ -n "${RESTIC_REPOSITORY:-}" && -n "${RESTIC_PASSWORD:-}" ]]; then
  restic backup data/minecraft "backups/dumps/nemeton-$STAMP.sql" --tag nemeton
  restic forget --keep-hourly 4 --keep-daily 7 --keep-weekly 4 --prune
else
  echo "restic não configurado; backup local criado em backups/dumps/."
fi

find backups/dumps -type f -name '*.sql' -mtime +2 -delete
find backups/dumps -type f -name '*.tgz' -mtime +2 -delete
if [[ -n "${RCLONE_REMOTE:-}" ]]; then
  rclone sync backups "$RCLONE_REMOTE" --checksum
fi
