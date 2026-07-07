# Nemeton+ e mods opcionais

O servidor principal continua em Paper + Geyser/Floodgate para manter Java e Bedrock juntos.
Por isso, mods Forge/Fabric que exigem servidor modded não entram diretamente na instância principal.

## O que fica no servidor

- `NemetonCore`: plugin Java próprio com clãs, claims, trocas, mochila, lápide, mapa e Nemeton+.
- Datapacks Vanilla+: estruturas em chunks novos sem blocos customizados.
- Resource packs próprios: um pack Java e um pack Bedrock equivalente.
- Geyser custom content: mapeia itens customizados para Bedrock.

Não existe um único arquivo de textura que seja nativo para Java e Bedrock ao mesmo tempo. O caminho certo é manter dois packs equivalentes no repositório e testar os dois antes de obrigar o download no login.

## Nemeton+ alpha

- Essência do Nemeton: drop raro ao minerar minérios.
- Lâmina do Nemeton: essência + diamante + graveto.
- Machado do Guardião: duas essências + graveto.
- Peitoral Sentinela: peitoral de diamante cercado por essências.
- Wither e Ender Dragon deixam troféus especiais para eventos comunitários.

Comandos:

- `/mods`
- `/mods itens`
- `/mods give` para administração testar a vitrine.

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
- mapa web via squaremap
- mapa nativo preenchido

Custom items bonitos para Bedrock devem ser feitos com resource pack Bedrock + mapeamento do Geyser.

## Próximo passo de textura

1. Criar pack Java com modelos dos itens Nemeton+.
2. Gerar pack Bedrock equivalente.
3. Criar `custom_mappings` do Geyser.
4. Testar login Java e Bedrock numa cópia.
5. Só então ativar o pack como recomendado/obrigatório.
