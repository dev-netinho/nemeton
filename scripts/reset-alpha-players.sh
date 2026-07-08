#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

PROJECT="${COMPOSE_PROJECT_NAME:-nemeton}"
COMPOSE=(docker compose -p "$PROJECT" -f compose.yml)

if [[ ! -f .env ]]; then
  echo "Arquivo .env não encontrado." >&2
  exit 1
fi

set -a
source .env
set +a

DB_NAME="${DB_NAME:-nemeton}"
DB_USER="${DB_USER:-nemeton}"

FORCE="${1:-}"

online="$("${COMPOSE[@]}" exec -T minecraft rcon-cli list 2>/dev/null || true)"
echo "$online"
if [[ "$FORCE" != "--force" && "$online" =~ There[[:space:]]are[[:space:]]([1-9][0-9]*)[[:space:]]of ]]; then
  echo "Há jogadores online. Rode novamente com --force se realmente quiser resetar agora." >&2
  exit 1
fi

STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
BACKUP="backups/manual/reset-alpha-$STAMP"
mkdir -p "$BACKUP"

echo "Criando snapshot local em $BACKUP..."
"${COMPOSE[@]}" exec -T minecraft rcon-cli save-all flush >/dev/null 2>&1 || true
"${COMPOSE[@]}" exec -T database mariadb-dump \
  -u"$DB_USER" -p"$DB_PASSWORD" --single-transaction "$DB_NAME" > "$BACKUP/nemeton.sql"

tar_paths=()
for path in \
  data/minecraft/usercache.json \
  data/minecraft/world/playerdata \
  data/minecraft/world/stats \
  data/minecraft/world/advancements \
  data/minecraft/plugins/NemetonCore/backpacks.yml \
  data/minecraft/plugins/NemetonCore/graves.yml \
  data/minecraft/plugins/WorldGuard/worlds
do
  [[ -e "$path" ]] && tar_paths+=("$path")
done

if (( ${#tar_paths[@]} > 0 )); then
  tar -czf "$BACKUP/player-reset-files.tgz" "${tar_paths[@]}"
fi

echo "Parando apenas o Minecraft do projeto $PROJECT..."
"${COMPOSE[@]}" stop minecraft

echo "Limpando dados vanilla dos jogadores..."
find data/minecraft/world/playerdata -type f \( -name '*.dat' -o -name '*.dat_old' \) -delete 2>/dev/null || true
find data/minecraft/world/stats -type f -name '*.json' -delete 2>/dev/null || true
find data/minecraft/world/advancements -type f -name '*.json' -delete 2>/dev/null || true
printf '[]\n' > data/minecraft/usercache.json

echo "Limpando dados pessoais do NemetonCore..."
rm -f data/minecraft/plugins/NemetonCore/backpacks.yml
rm -f data/minecraft/plugins/NemetonCore/graves.yml

echo "Limpando domínio alpha no MariaDB..."
mapfile -t existing_tables < <("${COMPOSE[@]}" exec -T database mariadb \
  -u"$DB_USER" -p"$DB_PASSWORD" "$DB_NAME" -N \
  -e "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = '$DB_NAME'")

tables_to_reset=(
  raid_block_changes
  raid_participants
  raids
  pending_rewards
  alliances
  clan_trust
  sanctuary_trust
  sanctuaries
  clan_claims
  clan_members
  clans
)

sql='SET FOREIGN_KEY_CHECKS = 0;'
for table in "${tables_to_reset[@]}"; do
  if printf '%s\n' "${existing_tables[@]}" | grep -qx "$table"; then
    sql+="TRUNCATE TABLE \`$table\`;"
  fi
done
sql+='SET FOREIGN_KEY_CHECKS = 1;'

printf '%s\n' "$sql" | "${COMPOSE[@]}" exec -T database mariadb -u"$DB_USER" -p"$DB_PASSWORD" "$DB_NAME"

echo "Removendo regiões dinâmicas antigas do WorldGuard..."
python3 - <<'PY'
from __future__ import annotations

from pathlib import Path
import re

prefixes = ("clan_", "sanctuary_", "raid_")
region_files = list(Path("data/minecraft/plugins/WorldGuard/worlds").glob("*/regions.yml"))

for path in region_files:
    original = path.read_text(encoding="utf-8", errors="replace").splitlines(keepends=True)
    output: list[str] = []
    skip_indent: int | None = None
    removed = 0

    for line in original:
        if skip_indent is not None:
            stripped = line.strip()
            indent = len(line) - len(line.lstrip(" "))
            if stripped and indent <= skip_indent:
                skip_indent = None
            else:
                continue

        match = re.match(r"^(\s*)([A-Za-z0-9_-]+):\s*(?:#.*)?$", line)
        if match and match.group(2).startswith(prefixes) and len(match.group(1)) >= 2:
            skip_indent = len(match.group(1))
            removed += 1
            continue

        output.append(line)

    if removed:
        path.write_text("".join(output), encoding="utf-8")
        print(f"{path}: {removed} regiões removidas")
PY

echo "Subindo Minecraft novamente..."
"${COMPOSE[@]}" up -d minecraft

echo "Aguardando healthcheck..."
for _ in {1..60}; do
  status="$("${COMPOSE[@]}" ps --format json minecraft 2>/dev/null | grep -o '"Health":"[^"]*"' | head -1 || true)"
  [[ "$status" == '"Health":"healthy"' ]] && break
  sleep 5
done

"${COMPOSE[@]}" ps
"${COMPOSE[@]}" exec -T minecraft rcon-cli list 2>/dev/null || true

echo "Reset alpha concluído. Snapshot: $BACKUP"
