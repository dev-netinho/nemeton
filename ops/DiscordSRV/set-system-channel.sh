#!/bin/sh
set -eu

# Run inside the Minecraft container; the compose environment supplies the bot token.
curl -fsS -o /dev/null -w 'HTTP %{http_code}\n' \
  -X PATCH \
  -H "Authorization: Bot ${DISCORD_BOT_TOKEN}" \
  -H 'Content-Type: application/json' \
  -H 'User-Agent: NemetonOps/1.0' \
  --data '{"system_channel_id":"1548728080661745796","system_channel_flags":0}' \
  'https://discord.com/api/v10/guilds/1523887197978234980'
