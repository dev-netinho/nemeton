# Nemeton

SMP comunitário Java + Bedrock: um mundo persistente, um centro seguro no próprio Overworld, clãs, santuários pessoais e raids com restauração automática. O projeto não inclui monetização nem depende de serviços pagos.

## O que já existe

- `NemetonCore` em Java 21, com clãs, cargos, convites, claims contíguos, santuários, alianças/tréguas, cofre físico e máquina de estados de guerra.
- Raids agendadas com três horários, custódia de diamantes, times limitados, captura do cofre, inventário preservado e journal de blocos restaurado em lotes.
- Regiões WorldGuard com prioridades separadas para Nemeton, santuários, claims e overlays de raid.
- Integração Discord tolerante a falhas: cria cargo/canais, sincroniza membros vinculados pelo DiscordSRV e envia alertas.
- Chat privado de clã nos dois sentidos e comandos slash `/clan status`, `/clan recrutar`, `/raid agenda`, `/raid escolher` e `/online`, usando a sessão JDA já aberta pelo DiscordSRV.
- Paper/Geyser/Floodgate, MariaDB, plugins gratuitos, backups restic/rclone e CI organizados em arquivos reproduzíveis.
- Experiência alpha: Nemeton físico como árvore ancestral em uma planície natural, beacon máximo no núcleo, região segura com WorldGuard, boas-vindas, livro em `/guia`, kit inicial único em `/kit` e retorno por `/nemeton` ou `/spawn`.
- Lápides privadas preservam os itens da morte sem anúncios globais; `/lapide` mostra a posição e entrega uma bússola de recuperação.
- `/troca` e `/comercio` abrem uma negociação direta com confirmação dos dois jogadores. A tela pode ser fechada e retomada sem perder a oferta.
- `/mapa` entrega um mapa nativo Java/Bedrock; o squaremap oferece uma visão ao vivo no navegador sem mod de cliente.
- A mochila pessoal é fabricável, vinculada ao dono e guarda 27 espaços sem aceitar mochilas aninhadas.
- A clareira possui NPCs interativos, caminhos cardeais, quatro portais e uma fronteira física alinhada à proteção circular.
- `Vanilla+ Structures` 0.1.3 adiciona sete estruturas somente em chunks novos, sem blocos ou itens customizados.

## Desenvolvimento

Requisitos: Java 21 ou mais recente, `curl` e `tar`. Maven é baixado localmente pelo wrapper:

```bash
./mvnw clean verify
```

O JAR sombreado é gerado em `target/`.

## Perfil beta da VPS

A primeira versão assume a VPS compartilhada atual, então começa conservadora:

- Paper `1.21.11`; o container usa Java 25 no beta porque os builds atuais de WorldEdit/WorldGuard baixados para teste já exigem Java 25. O plugin `NemetonCore` continua compilado com alvo Java 21.
- Heap do Minecraft em `4G`, limite do container em `5G`.
- `MAX_PLAYERS=15`, `VIEW_DISTANCE=7` e `SIMULATION_DISTANCE=5`.
- Raids e ativação de guerra desligadas por padrão em `war.raids-enabled: false`.
- Sem proxy, painel pesado ou modpack obrigatório de cliente; apenas Paper, plugins/datapacks gratuitos e o `NemetonCore`.

Se a VPS ficar folgada após testes com `spark`, suba aos poucos: primeiro `MC_MAX_PLAYERS`, depois distâncias. Não pule direto para heap de `8G` enquanto K-OSS, portfólio e outros serviços dividirem a máquina.

Para subir na VPS, instale Docker Engine + Compose, copie `.env.example` para `.env`, troque pelo menos `MARIADB_ROOT_PASSWORD`, `DB_PASSWORD` e `RCON_PASSWORD`, e rode:

```bash
./scripts/audit-vps.sh
./scripts/deploy.sh
```

Após o primeiro boot, copie os modelos de `ops/DiscordSRV/` para `data/minecraft/plugins/DiscordSRV/`, substitua todos os IDs e confirme que o console remoto continua desabilitado. Configure o token tanto no DiscordSRV quanto em `.env`; ele nunca deve ser commitado.
Convide o bot com os escopos `bot` e `applications.commands` e conceda apenas `Manage Roles`, `Manage Channels`, `Move Members` e as permissões de leitura/escrita dos canais administrados.

## Primeiro lançamento

1. Confirme a posição real do Nemeton em `plugins/NemetonCore/config.yml`; na alpha atual ela é `world 22032.5 67.0 -12375.5`.
2. Configure o cargo `Aprovado`, o vínculo obrigatório e os canais/voz do DiscordSRV.
3. Teste `/troca` entre Java e Bedrock, incluindo fechar a interface, usar `/troca abrir`, confirmar e cancelar.
4. Teste `/mapa`, a receita da `/mochila` e os quatro NPCs tanto no Java quanto no Bedrock.
5. Comece com pregeneration pequena: `chunky radius 1500`, valide TPS/espaço, depois aumente para `3000` e só então considere `5000`.
6. Agende `scripts/backup.sh` a cada seis horas e restaure um snapshot em diretório limpo antes do alpha.
7. Mantenha `war.raids-enabled: false` até duas raids completas passarem no servidor de teste; só então altere para `true`.

Detalhes de arquitetura e operação estão em [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) e [docs/RUNBOOK.md](docs/RUNBOOK.md).
