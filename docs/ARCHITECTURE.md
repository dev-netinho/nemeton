# Arquitetura

O Paper é uma única instância. MariaDB persiste o domínio; `ServerState` mantém índices de leitura para que movimento e proteção nunca consultem o banco. Escritas administrativas são transacionais e pouco frequentes.

WorldGuard continua sendo a autoridade de região. Nemeton usa prioridade 100, santuários 50, overlays temporários de raid 20 e claims de clã 10. O listener próprio acrescenta regras que flags genéricas não expressam: contêineres invioláveis, participantes da raid, cofre e journal.

No lobby, a defesa é deliberadamente redundante: flags WorldGuard bloqueiam build, mobs, explosões, pistões, fogo e fluidos; listeners removem qualquer bloco de uma explosão externa que alcance a região e eliminam criaturas, projéteis e TNT que atravessem o limite. NPCs visuais são entidades `PLAYER` persistentes do Citizens; o NemetonCore cuida de posição, equipamento e interfaces Java/Bedrock.

Raids usam uma máquina de estados `DECLARED → SCHEDULED → ACTIVE → RESTORING → COMPLETED`. Cada primeira mutação de coordenada guarda o `BlockData` original. Blocos com estado, entidades decorativas, animais e moradores não podem ser destruídos. Em reinício inesperado, estados ativos entram em recuperação, são restaurados e devolvem as apostas.

DiscordSRV continua responsável por vínculo, chat global e voz de proximidade. `DiscordBridge` usa o vínculo já validado e a API REST do Discord para recursos de clã. Falhas externas não cancelam alterações no Minecraft; a administração recebe logs e pode reconciliar o recurso.

O código do domínio não depende de Bukkit onde isso não é necessário, permitindo testes unitários. Integrações ficam nas bordas: comandos, listeners, WorldGuard, Discord e JDBC.
