#!/usr/bin/env bash
set -euo pipefail
echo "Nemeton VPS audit"
echo "CPU"
nproc
lscpu | sed -n '1,20p'
echo "Memory"
free -h
echo "Disk"
df -h .
echo "Ports"
ss -lntup | grep -E ':(25565|19132)\b' || true
echo "Docker"
docker version --format '{{.Server.Version}}' 2>/dev/null || echo "Docker não instalado"

