#!/usr/bin/env bash
set -euo pipefail
echo "Nemeton VPS audit"
echo "CPU"
nproc
lscpu | sed -n '1,20p'
echo "Memory"
free -h
available_kib="$(awk '/MemAvailable/ {print $2}' /proc/meminfo)"
available_mib="$((available_kib / 1024))"
if (( available_mib < 4608 )); then
  echo "WARN: MemAvailable abaixo de 4.5 GiB; use MC_MEMORY=3G ou reduza outros serviços antes do alpha."
else
  echo "OK: MemAvailable >= 4.5 GiB para beta conservador com MC_MEMORY=4G."
fi
echo "Disk"
df -h .
echo "Ports"
ss -lntup | grep -E ':(25565|19132)\b' || true
echo "Docker"
docker version --format '{{.Server.Version}}' 2>/dev/null || echo "Docker não instalado"
docker compose version 2>/dev/null || echo "Docker Compose não instalado"
