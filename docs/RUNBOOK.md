# Runbook operacional

## Rotina

- Verificar `docker compose -f compose.yml ps` e o histórico de alertas.
- Testar Java em `100.123.99.34:25565` dentro da Tailnet e Bedrock público em `documents-voicing.gl.at.ply.gg:59460`.
- Confirmar que o `playit` está rodando: `ps -p "$(cat runtime/playit/state/playit.pid)" -o pid,etime,cmd`.
- Comandos da experiência alpha: `/menu`, `/guia`, `/kit`, `/nemeton`, `/spawn`, `/mapa`, `/mochila`, `/lapide`, `/troca`, `/mods`, `/mods itens`, `/santuario ajuda` e `/clan ajuda`.
- Em troca com Bedrock, evitar a interface Java: o plugin abre formulário nativo quando possível. Se o jogador fechar ou o Floodgate não responder, usar `/troca oferecer [qtd]`, `/troca ver`, `/troca aceitar` e `/troca cancelar`.
- Rodar `spark healthreport` quando MSPT subir; não instalar plugins de limpeza automática às cegas.
- Conferir espaço antes de expandir o world border ou pregenerar chunks.
- Nunca atualizar Paper, Geyser ou WorldGuard diretamente em produção. Clonar os volumes e testar a matriz primeiro.
- Para o beta na VPS compartilhada, manter `MC_MEMORY=4G`, `MC_MAX_PLAYERS=15`, `MC_VIEW_DISTANCE=7` e `MC_SIMULATION_DISTANCE=5` até haver medição real com jogadores.
- Se o MSPT p95 passar de 45 ms, reduzir primeiro `SIMULATION_DISTANCE`, depois `VIEW_DISTANCE`; aumentar heap só se houver GC/memória, não como reflexo automático.
- Manter `war.raids-enabled: false` em produção até duas simulações completas de raid passarem em ambiente de teste.

Para reconstruir a sinalização da clareira após restaurar o mundo, use `nemetonadmin construir` no console. O comando aplica os blocos em lotes e pode ser repetido; a árvore monumental atual passa de meio milhão de alterações, então espere cerca de um a dois minutos e monitore TPS. `nemetonadmin selar` preenche somente cavidades e fluidos sob toda a região, preservando blocos sólidos existentes. `nemetonadmin npcs` sincroniza os NPCs de jogador do Citizens e os textos flutuantes.

Citizens `2.0.43-b4211` é dependência obrigatória e fixada por SHA-256 em `scripts/fetch-content.sh`. Atualize apenas depois de testar Java, Bedrock, skins, clique, proteção e persistência numa cópia.

Na alpha atual, o Nemeton fica no mundo `world`, entrada em `16064.5 78.0 -32046.5`, centro protegido em `16064.5 -32064.5` e raio `42`. Antes de mover de novo, use `nemetonadmin avaliar <x> <z> [raio]` para medir água, relevo e biomas.

O datapack Vanilla+ é baixado por `scripts/fetch-content.sh` com versão e SHA-256 fixados. Ele afeta apenas chunks gerados depois de sua ativação.

O pack visual é gerado por `scripts/build-resource-packs.py`. Publique `resourcepacks/dist/Nemeton-Java.zip` antes de reiniciar o plugin, pois o Java baixa pela URL raw do GitHub. No Bedrock, copie `resourcepacks/dist/Nemeton-Bedrock.mcpack` para `plugins/Geyser-Spigot/packs/` e `resourcepacks/geyser/nemeton-items.json` para `plugins/Geyser-Spigot/custom_mappings/`, depois reinicie o Minecraft/Geyser.

O squaremap escuta apenas em `127.0.0.1:8100`. `scripts/start-map-tunnel.sh` cria um Quick Tunnel isolado (sem ler os túneis Cloudflare dos demais projetos) e grava a URL atual em `plugins/NemetonCore/map-url.txt`, lida dinamicamente por `/mapa`.

## Backup

`scripts/backup.sh` força flush do mundo, cria dump consistente do MariaDB e gera um pacote local do mundo. Se `restic` estiver instalado e `RESTIC_REPOSITORY`/`RESTIC_PASSWORD` estiverem configurados, envia ambos ao repositório criptografado e aplica retenção. `RCLONE_REMOTE` replica o repositório para um destino externo gratuito. Meta: RPO de seis horas e RTO de duas.

Teste mensal: pare uma cópia do Compose, restaure os dados em outro diretório, suba em portas alternativas e confirme login, claims, inventários e banco.

## Subindo capacidade

Não aumente tudo de uma vez. Sequência segura:

1. Rodar alpha com 5–10 jogadores e Chunky até 1.500 blocos.
2. Validar `spark tps`, MSPT, RAM disponível e tamanho do mundo.
3. Subir para 15 jogadores e pregeneration de 3.000 blocos.
4. Só considerar 20+ jogadores se a VPS mantiver TPS >= 19,5 sem swap crescente.

## Raid travada

Não edite blocos manualmente. Pare novas entradas, preserve `raid_block_changes` e reinicie o NemetonCore; ele detecta raids ativas e inicia recuperação. Se o journal falhar, use CoreProtect limitado ao período, jogadores e chunks registrados nos logs.

## Discord indisponível

O Minecraft continua operando. Não recrie cargos/canais manualmente durante uma interrupção curta. Confirme token/permissões (`Manage Roles`, `Manage Channels`, `Move Members`) e reinicie apenas DiscordSRV/Nemeton numa janela anunciada.
