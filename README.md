# Nemeton

SMP comunitário Java + Bedrock: um mundo persistente, um centro seguro no próprio Overworld, clãs, santuários pessoais e raids com restauração automática. O projeto não inclui monetização nem depende de serviços pagos.

## O que já existe

- `NemetonCore` em Java 21, com clãs, cargos, convites, claims contíguos, santuários, alianças/tréguas, cofre físico e máquina de estados de guerra.
- Raids agendadas com três horários, custódia de diamantes, times limitados, captura do cofre, inventário preservado e journal de blocos restaurado em lotes.
- Regiões WorldGuard com prioridades separadas para Nemeton, santuários, claims e overlays de raid.
- Integração Discord tolerante a falhas: cria cargo/canais, sincroniza membros vinculados pelo DiscordSRV e envia alertas.
- Chat privado de clã nos dois sentidos e comandos slash `/clan status`, `/clan recrutar`, `/raid agenda`, `/raid escolher` e `/online`, usando a sessão JDA já aberta pelo DiscordSRV.
- Paper/Geyser/Floodgate, MariaDB, plugins gratuitos, backups restic/rclone e CI organizados em arquivos reproduzíveis.

## Desenvolvimento

Requisitos: Java 21 ou mais recente, `curl` e `tar`. Maven é baixado localmente pelo wrapper:

```bash
./mvnw clean verify
```

O JAR sombreado é gerado em `target/`. Para subir na VPS, instale Docker Engine + Compose, copie `.env.example` para `.env`, troque todos os segredos e rode:

```bash
./scripts/audit-vps.sh
./scripts/deploy.sh
```

Após o primeiro boot, copie os modelos de `ops/DiscordSRV/` para `data/minecraft/plugins/DiscordSRV/`, substitua todos os IDs e confirme que o console remoto continua desabilitado. Configure o token tanto no DiscordSRV quanto em `.env`; ele nunca deve ser commitado.
Convide o bot com os escopos `bot` e `applications.commands` e conceda apenas `Manage Roles`, `Manage Channels`, `Move Members` e as permissões de leitura/escrita dos canais administrados.

## Primeiro lançamento

1. Edite a posição real do Nemeton em `plugins/NemetonCore/config.yml` e reinicie.
2. Configure o cargo `Aprovado`, o vínculo obrigatório e os canais/voz do DiscordSRV.
3. Restrinja criação de Shopkeepers aos 24 lotes construídos no Nemeton usando LuckPerms/WorldGuard.
4. Execute `chunky radius 5000`, verifique espaço projetado e só então `chunky start`.
5. Agende `scripts/backup.sh` a cada seis horas e restaure um snapshot em diretório limpo antes do alpha.
6. Mantenha guerra desativada na comunidade até duas raids completas passarem no servidor de teste.

Detalhes de arquitetura e operação estão em [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) e [docs/RUNBOOK.md](docs/RUNBOOK.md).
