# Runbook operacional

## Rotina

- Verificar `docker compose -f compose.yml ps` e o histórico de alertas.
- Rodar `spark healthreport` quando MSPT subir; não instalar plugins de limpeza automática às cegas.
- Conferir espaço antes de expandir o world border ou pregenerar chunks.
- Nunca atualizar Paper, Geyser ou WorldGuard diretamente em produção. Clonar os volumes e testar a matriz primeiro.

## Backup

`scripts/backup.sh` força flush do mundo, cria dump consistente do MariaDB, envia ambos ao restic e aplica retenção. `RCLONE_REMOTE` replica o repositório criptografado para um destino externo gratuito. Meta: RPO de seis horas e RTO de duas.

Teste mensal: pare uma cópia do Compose, restaure os dados em outro diretório, suba em portas alternativas e confirme login, claims, inventários e banco.

## Raid travada

Não edite blocos manualmente. Pare novas entradas, preserve `raid_block_changes` e reinicie o NemetonCore; ele detecta raids ativas e inicia recuperação. Se o journal falhar, use CoreProtect limitado ao período, jogadores e chunks registrados nos logs.

## Discord indisponível

O Minecraft continua operando. Não recrie cargos/canais manualmente durante uma interrupção curta. Confirme token/permissões (`Manage Roles`, `Manage Channels`, `Move Members`) e reinicie apenas DiscordSRV/Nemeton numa janela anunciada.

