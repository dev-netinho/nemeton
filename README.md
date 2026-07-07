# Nemeton

SMP comunitário Java + Bedrock: um mundo persistente, um centro seguro no próprio Overworld, clãs, santuários pessoais e raids com restauração automática. O projeto não inclui monetização nem depende de serviços pagos.

## O que já existe

- `NemetonCore` em Java 21, com clãs, cargos, convites, claims contíguos, santuários, alianças/tréguas, cofre físico e máquina de estados de guerra.
- Raids agendadas com três horários, custódia de diamantes, times limitados, captura do cofre, inventário preservado e journal de blocos restaurado em lotes.
- Regiões WorldGuard com prioridades separadas para Nemeton, santuários, claims e overlays de raid.
- Integração Discord tolerante a falhas: cria cargo/canais, sincroniza membros vinculados pelo DiscordSRV e envia alertas.
- Chat privado de clã nos dois sentidos e comandos slash `/clan status`, `/clan recrutar`, `/raid agenda`, `/raid escolher` e `/online`, usando a sessão JDA já aberta pelo DiscordSRV.
- Paper/Geyser/Floodgate, MariaDB, plugins gratuitos, backups restic/rclone e CI organizados em arquivos reproduzíveis.
- Experiência alpha: Nemeton físico como árvore ancestral em uma planície natural, beacon máximo no núcleo, região segura compacta com WorldGuard, boas-vindas, livro em `/guia`, kit inicial único em `/kit` e retorno por `/nemeton` ou `/spawn`.
- Lápides privadas preservam os itens da morte sem anúncios globais; `/lapide` mostra a posição e entrega uma bússola de recuperação.
- `/troca` e `/comercio` abrem uma negociação direta com confirmação dos dois jogadores. Java usa inventário; Bedrock recebe formulário nativo e comandos seguros. A tela pode ser fechada e retomada sem perder a oferta.
- `/mapa` entrega um mapa nativo Java/Bedrock; o squaremap oferece uma visão ao vivo no navegador sem mod de cliente.
- A mochila pessoal é fabricável, vinculada ao dono e guarda 27 espaços sem aceitar mochilas aninhadas.
- A clareira possui NPCs de jogador gerenciados pelo Citizens, com skins, equipamento, olhar responsivo e formulários Bedrock, além de textos flutuantes maiores, caminhos cardeais, quatro portais e uma fronteira física alinhada à proteção circular.
- O Nemeton bloqueia criaturas e perigos em toda a coluna da região, sela cavernas sob o lobby e combina WorldGuard com listeners próprios contra explosões externas, pistões, fogo e fluidos.
- `Nemeton+` adiciona drops raros de mineração, recompensas de boss, armas/armadura craftáveis, trims/brilho vanilla-safe e guia de mods opcionais em `/mods`.
- `/menu`/`/painel` abre uma interface principal. No Bedrock, clãs, santuário, raids, guia, Nemeton+ e trocas usam Forms nativas com botões/campos.
- `resourcepacks/` contém o Nemeton + Faithful 32x: textura completa equivalente para Java/Bedrock, overlay autoral dos itens Nemeton e mapeamento Geyser. Créditos e licença do Faithful seguem dentro dos dois packs.
- `Vanilla+ Structures` 0.1.3 adiciona sete estruturas somente em chunks novos, sem blocos ou itens customizados.

## Desenvolvimento

Requisitos: Java 21 ou mais recente, `curl` e `tar`. Maven é baixado localmente pelo wrapper:

```bash
./mvnw clean verify
```

O JAR sombreado é gerado em `target/`.

Para reconstruir os packs visuais completos, instale a dependência de imagem e execute o gerador:

```bash
python3 -m pip install -r requirements-resourcepacks.txt
scripts/build-resource-packs.py
```

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

1. Confirme a posição real do Nemeton em `plugins/NemetonCore/config.yml`; na alpha atual ela é `world 16064.5 78.0 -32046.5`, com centro protegido em `16064.5, -32064.5` e raio `42`.
2. Configure o cargo `Aprovado`, o vínculo obrigatório e os canais/voz do DiscordSRV.
3. Teste `/troca` entre Java e Bedrock, incluindo fechar a interface, usar `/troca abrir`, confirmar e cancelar.
4. Teste `/menu`, `/mapa`, a receita da `/mochila`, `/mods`, `/mods itens` e interação com os NPCs tanto no Java quanto no Bedrock.
5. Teste `/troca` Java↔Java pela interface e Java↔Bedrock pelo formulário Bedrock; mantenha os comandos seguros como fallback: `/troca oferecer`, `/troca ver`, `/troca aceitar`.
6. Como OP, use `/mods give` para validar a vitrine Nemeton+ e confirmar que o pack visual mudou os ícones no Java e no Bedrock.
7. Comece com pregeneration pequena: `chunky radius 1500`, valide TPS/espaço, depois aumente para `3000` e só então considere `5000`.
8. Agende `scripts/backup.sh` a cada seis horas e restaure um snapshot em diretório limpo antes do alpha.
9. Clãs nascem combatentes e aceitam raids agendadas; santuários pessoais permanecem fora de qualquer raid. Para pausar emergencialmente novas declarações, use `war.raids-enabled: false`.

Detalhes de arquitetura e operação estão em [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) e [docs/RUNBOOK.md](docs/RUNBOOK.md).
