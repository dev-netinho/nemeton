# Mensagens do DiscordSRV em português

O arquivo `messages.yml` é gerado pelo DiscordSRV dentro do volume do servidor e, por isso, não é versionado inteiro. O patch `messages-pt.patch` mantém as mensagens de vínculo, erros e ciclo de vida do bot em português, sem expor UUID nas DMs.

Para aplicar em uma instalação nova, a partir da raiz do projeto:

```sh
patch -p0 < ops/DiscordSRV/messages-pt.patch
```

Depois, reinicie somente o serviço `minecraft` para o DiscordSRV recarregar as mensagens. A exigência de cargo Twitch/subscriber continua desativada na configuração de linking do Nemeton; o único requisito é a entrada no Discord e o vínculo da conta.
