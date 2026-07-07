# Nemeton+ e mods opcionais

O servidor principal continua em Paper + Geyser/Floodgate para manter Java e Bedrock juntos.
Por isso, mods Forge/Fabric que exigem servidor modded não entram diretamente na instância principal.

## O que fica no servidor

- `NemetonCore`: plugin Java próprio com clãs, claims, trocas, mochila, lápide, mapa e Nemeton+.
- Datapacks Vanilla+: estruturas em chunks novos sem blocos customizados.
- Visual vanilla-safe: armaduras com trims, brilho, raridade e `customModelData` já marcado para o pack futuro.
- Forms Bedrock via Floodgate/Cumulus para NPCs, `/mods` e trocas, com chat como fallback.
- Resource packs próprios: `resourcepacks/nemeton-java` e `resourcepacks/nemeton-bedrock`.
- Zips públicos em `resourcepacks/dist/`: `Nemeton-Java.zip` e `Nemeton-Bedrock.mcpack`.
- Geyser custom content: `resourcepacks/geyser/nemeton-items.json` mapeia os itens customizados para Bedrock.

Os packs finais usam Faithful 32x como base completa do Minecraft e aplicam o overlay autoral Nemeton por cima. Versões fixadas:

- Java: `Faithful-32x-Java@e3e7c8d99a30bc1a97f529a973f32d7b08833243`
- Bedrock: `Faithful-32x-Bedrock@0c02eb2c20930677f19f51d87294f30147db8bb0`

Faithful 32x é creditado dentro dos dois packs por `LICENSE.txt` e `CREDITS-NEMETON.txt`. O servidor é gratuito e não monetizado, conforme as condições de uso do pack para resource packs de servidor.

Não existe um único arquivo de textura que seja nativo para Java e Bedrock ao mesmo tempo. O caminho certo é manter dois packs equivalentes no repositório: Java recebe o zip por URL e Bedrock recebe o `.mcpack` pela pasta `plugins/Geyser-Spigot/packs`.

Para reconstruir:

```bash
scripts/build-resource-packs.py
```

O SHA-1 atual do pack Java é registrado em `resourcepacks/dist/Nemeton-Java.sha1` e em `resource-pack.java-sha1`. O gerador baixa os commits fixados, monta os packs completos e aplica os modelos Nemeton.

## Nemeton+ alpha

- Essência do Nemeton: drop raro ao minerar minérios.
- Lâmina do Nemeton: essência + diamante + graveto.
- Machado do Guardião: duas essências + graveto.
- Peitoral Sentinela: peitoral de diamante cercado por essências, com trim e brilho nativos.
- Wither e Ender Dragon deixam troféus especiais para eventos comunitários.

Comandos:

- `/mods`
- `/mods itens`
- `/mods give` para administração testar a vitrine.

No Bedrock, `/mods` e `/mods itens` abrem uma tela nativa. No Java, continuam como chat clicável/legível.

## Java

No Lunar Client, o minimap pode ser ativado pelo próprio cliente:

1. `Right Shift`
2. `Mods`
3. `Minimap`
4. Ativar e ajustar posição/tamanho.

Jogadores Java sem Lunar podem usar mods client-side de minimap, desde que não adicionem vantagem proibida ou automação.

## Bedrock

Bedrock não carrega mods Java. Para orientação, o servidor oferece:

- `/mapa`
- formulários nativos em NPCs, `/mods` e `/troca`
- mapa web via squaremap
- mapa nativo preenchido

Custom items bonitos para Bedrock usam resource pack Bedrock + mapeamento do Geyser. O primeiro pack já cobre Essência do Nemeton, Lâmina, Machado, Peitoral Sentinela, Coração Abissal, Coração do Fim e Mochila.

## Próximo passo de textura

1. Evoluir os ícones para modelos 3D/armaduras completas.
2. Criar variações de armadura por trim ou por item model quando Bedrock suportar bem.
3. Testar login Java e Bedrock numa cópia antes de forçar o pack Java.
