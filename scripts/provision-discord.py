#!/usr/bin/env python3
"""Build the Nemeton Discord as an idempotent community hub."""

from __future__ import annotations

import argparse
import base64
import json
import mimetypes
import os
import pathlib
import re
import sys
import urllib.error
import urllib.request

API = "https://discord.com/api/v10"
MARKER = "NEMETON_SETUP_V2"

ADMINISTRATOR = 1 << 3
VIEW_CHANNEL = 1 << 10
SEND_MESSAGES = 1 << 11
READ_MESSAGE_HISTORY = 1 << 16
CONNECT = 1 << 20
SPEAK = 1 << 21
USE_APPLICATION_COMMANDS = 1 << 31


def request(token: str, method: str, path: str, body: object | None = None) -> object:
    payload = None if body is None else json.dumps(body).encode()
    headers = {"Authorization": f"Bot {token}", "User-Agent": "Nemeton/2.0"}
    if payload is not None:
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(API + path, data=payload, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=25) as response:
            raw = response.read()
            return None if not raw else json.loads(raw)
    except urllib.error.HTTPError as error:
        detail = error.read().decode(errors="replace")
        raise RuntimeError(f"Discord {method} {path} respondeu {error.code}: {detail}") from error


def overwrite(target_id: str, allow: int = 0, deny: int = 0, target_type: int = 0) -> dict:
    return {"id": target_id, "type": target_type, "allow": str(allow), "deny": str(deny)}


def bot_access(bot_id: str, voice: bool = False) -> dict:
    allow = VIEW_CHANNEL | READ_MESSAGE_HISTORY | SEND_MESSAGES | USE_APPLICATION_COMMANDS
    if voice:
        allow |= CONNECT | SPEAK
    return overwrite(bot_id, allow=allow, target_type=1)


def find_or_create_role(token: str, guild_id: str, name: str, color: int,
                        aliases: tuple[str, ...] = (), hoist: bool = False) -> dict:
    roles = request(token, "GET", f"/guilds/{guild_id}/roles")
    existing = next((role for role in roles if role["name"] in (name, *aliases)), None)
    body = {"name": name, "color": color, "hoist": hoist, "mentionable": False}
    if existing:
        return request(token, "PATCH", f"/guilds/{guild_id}/roles/{existing['id']}", body)
    return request(token, "POST", f"/guilds/{guild_id}/roles", body)


def find_or_create_channel(token: str, guild_id: str, name: str, channel_type: int,
                           aliases: tuple[str, ...] = (), parent_id: str | None = None,
                           permissions: list[dict] | None = None, topic: str | None = None,
                           position: int | None = None) -> dict:
    channels = request(token, "GET", f"/guilds/{guild_id}/channels")
    existing = next((channel for channel in channels
                     if channel["name"] in (name, *aliases) and channel["type"] == channel_type), None)
    body: dict[str, object] = {"name": name, "type": channel_type}
    if parent_id is not None:
        body["parent_id"] = parent_id
    if permissions is not None:
        body["permission_overwrites"] = permissions
    if topic is not None and channel_type == 0:
        body["topic"] = topic
    if position is not None:
        body["position"] = position
    if existing:
        return request(token, "PATCH", f"/channels/{existing['id']}", body)
    return request(token, "POST", f"/guilds/{guild_id}/channels", body)


def post_once(token: str, channel_id: str, key: str, embeds: list[dict], content: str = "") -> None:
    recent = request(token, "GET", f"/channels/{channel_id}/messages?limit=50")
    marker = f"{MARKER}:{key}"
    if any(any(embed.get("footer", {}).get("text") == marker for embed in message.get("embeds", [])) for message in recent):
        return
    for embed in embeds:
        embed["footer"] = {"text": marker}
    body: dict[str, object] = {"embeds": embeds, "allowed_mentions": {"parse": []}}
    if content:
        body["content"] = content
    request(token, "POST", f"/channels/{channel_id}/messages", body)


def embed(title: str, description: str, color: int, fields: list[tuple[str, str, bool]] = (),
          image: str | None = None) -> dict:
    result: dict[str, object] = {
        "title": title,
        "description": description,
        "color": color,
        "fields": [{"name": name, "value": value, "inline": inline} for name, value, inline in fields],
    }
    if image:
        result["image"] = {"url": image}
    return result


def upsert_env(path: pathlib.Path, key: str, value: str) -> None:
    text = path.read_text()
    pattern = rf"(?m)^{re.escape(key)}=.*$"
    line = f"{key}={value}"
    text = re.sub(pattern, lambda _: line, text) if re.search(pattern, text) else text.rstrip() + "\n" + line + "\n"
    path.write_text(text)


def replace(path: pathlib.Path, pattern: str, replacement: str) -> None:
    text = path.read_text()
    updated, count = re.subn(pattern, lambda _: replacement, text, flags=re.MULTILINE)
    if count != 1:
        raise RuntimeError(f"Esperava uma ocorrência de {pattern!r} em {path}, encontrei {count}")
    path.write_text(updated)


def upsert_yaml_key(path: pathlib.Path, section: str, key: str, value: str) -> None:
    text = path.read_text()
    key_pattern = rf"(?m)^  {re.escape(key)}:.*$"
    section_match = re.search(rf"(?m)^{re.escape(section)}:\s*$", text)
    if not section_match:
        raise RuntimeError(f"Seção {section!r} ausente em {path}")
    next_section = re.search(r"(?m)^\S[^\n]*:\s*$", text[section_match.end():])
    end = section_match.end() + (next_section.start() if next_section else len(text[section_match.end():]))
    block = text[section_match.start():end]
    line = f"  {key}: {value}"
    if re.search(key_pattern, block):
        block = re.sub(key_pattern, lambda _: line, block)
    else:
        block = block.rstrip() + "\n" + line + "\n\n"
    path.write_text(text[:section_match.start()] + block + text[end:])


def apply_server_config(root: pathlib.Path, result: dict, invite_url: str) -> None:
    env = root / ".env"
    core = root / "data/minecraft/plugins/NemetonCore/config.yml"
    discord = root / "data/minecraft/plugins/DiscordSRV/config.yml"
    linking = root / "data/minecraft/plugins/DiscordSRV/linking.yml"
    voice = root / "data/minecraft/plugins/DiscordSRV/voice.yml"

    upsert_env(env, "DISCORD_GUILD_ID", result["guild_id"])
    values = {
        "enabled": "true",
        "guild-id": repr(result["guild_id"]),
        "clans-category-id": repr(result["clans_category_id"]),
        "alerts-channel-id": repr(result["alerts_channel_id"]),
        "approved-role-id": repr(result["approved_role_id"]),
        "clan-leader-role-id": repr(result["clan_leader_role_id"]),
        "clan-officer-role-id": repr(result["clan_officer_role_id"]),
        "clan-member-role-id": repr(result["clan_member_role_id"]),
        "leaders-channel-id": repr(result["leaders_channel_id"]),
        "recruitment-channel-id": repr(result["recruitment_channel_id"]),
        "bot-user-id": repr(result["bot_user_id"]),
    }
    for key, value in values.items():
        upsert_yaml_key(core, "discord", key, value)
    upsert_yaml_key(core, "war", "raids-enabled", "true")

    replace(discord, r"^Channels:.*$", "Channels: " + json.dumps({"global": result["global_chat_channel_id"]}))
    replace(discord, r"^DiscordConsoleChannelId:.*$", 'DiscordConsoleChannelId: ""')
    replace(discord, r"^DiscordInviteLink:.*$", "DiscordInviteLink: " + json.dumps(invite_url))
    replace(discord, r"^Experiment_WebhookChatMessageDelivery:.*$", "Experiment_WebhookChatMessageDelivery: true")
    replace(discord, r"^MinecraftDiscordAccountLinkedRoleNameToAddUserTo:.*$",
            'MinecraftDiscordAccountLinkedRoleNameToAddUserTo: "🔗 Vinculado"')

    replace(linking, r"^  Enabled:.*$", "  Enabled: true")
    replace(linking, r"^  Bypass names:.*$", "  Bypass names: [oLuaLascado]")
    replace(linking, r"^  Must be in Discord server:.*$", f"  Must be in Discord server: {result['guild_id']}")
    replace(linking, r"^    Require subscriber role to join:.*$", "    Require subscriber role to join: true")
    replace(linking, r"^    Subscriber roles:.*$", f'    Subscriber roles: ["{result["approved_role_id"]}"]')
    replace(linking, r"^    Require all of the listed roles:.*$", "    Require all of the listed roles: true")

    replace(voice, r"^Voice enabled:.*$", "Voice enabled: true")
    replace(voice, r"^Voice category:.*$", f"Voice category: {result['voice_category_id']}")
    replace(voice, r"^Lobby channel:.*$", f"Lobby channel: {result['voice_lobby_id']}")
    replace(voice, r"^  Vertical Strength:.*$", "  Vertical Strength: 24")
    replace(voice, r"^  Horizontal Strength:.*$", "  Horizontal Strength: 48")
    replace(voice, r"^  Falloff:.*$", "  Falloff: 8")

    output = root / "data/discord-provision.json"
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    os.chmod(env, 0o600)
    os.chmod(discord, 0o600)
    os.chmod(output, 0o600)


def set_identity(token: str, guild_id: str, root: pathlib.Path | None) -> None:
    body: dict[str, object] = {"name": "Nemeton • Survival Comunitário"}
    if root:
        image = root / "assets/discord/nemeton-emblem.png"
        if image.exists():
            mime = mimetypes.guess_type(image.name)[0] or "image/png"
            body["icon"] = f"data:{mime};base64," + base64.b64encode(image.read_bytes()).decode()
    request(token, "PATCH", f"/guilds/{guild_id}", body)


def cleanup_defaults(token: str, guild_id: str, keep: set[str]) -> None:
    channels = request(token, "GET", f"/guilds/{guild_id}/channels")
    for channel in channels:
        if channel["id"] in keep or channel["name"] not in {"geral", "Geral"}:
            continue
        if channel["type"] == 0 and request(token, "GET", f"/channels/{channel['id']}/messages?limit=1"):
            continue
        try:
            request(token, "DELETE", f"/channels/{channel['id']}")
        except RuntimeError:
            pass
    channels = request(token, "GET", f"/guilds/{guild_id}/channels")
    for channel in channels:
        if channel["id"] in keep or channel["type"] != 4 or channel["name"] not in {"Canais de Texto", "Canais de Voz"}:
            continue
        if not any(child.get("parent_id") == channel["id"] for child in channels):
            try:
                request(token, "DELETE", f"/channels/{channel['id']}")
            except RuntimeError:
                pass


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=pathlib.Path, help="Raiz local/remota do projeto Nemeton")
    args = parser.parse_args()
    root = args.root.resolve() if args.root else None
    token = os.environ.get("DISCORD_BOT_TOKEN", "").strip()
    if not token:
        raise SystemExit("DISCORD_BOT_TOKEN ausente")

    bot = request(token, "GET", "/users/@me")
    guilds = request(token, "GET", "/users/@me/guilds")
    requested = os.environ.get("DISCORD_GUILD_ID", "").strip()
    guild = next((item for item in guilds if item["id"] == requested), None) if requested else (guilds[0] if len(guilds) == 1 else None)
    if guild is None:
        raise SystemExit("O bot não encontrou o servidor Discord configurado")
    guild_id = guild["id"]
    administrator = bool(int(guild.get("permissions", "0")) & ADMINISTRATOR)

    guild_info = request(token, "GET", f"/guilds/{guild_id}")
    if administrator:
        set_identity(token, guild_id, root)
    approved = find_or_create_role(token, guild_id, "🌿 Aprovado", 0x65B891, ("Aprovado",), True)
    linked = find_or_create_role(token, guild_id, "🔗 Vinculado", 0x8F7BD8, ("Vinculado",))
    leader = find_or_create_role(token, guild_id, "👑 Líder de Clã", 0xF1C75B, hoist=True)
    officer = find_or_create_role(token, guild_id, "⚔️ Vice-Líder", 0xE87A5D, hoist=True)
    member = find_or_create_role(token, guild_id, "🛡️ Membro de Clã", 0x4D91C6)

    public_read = [overwrite(guild_id, VIEW_CHANNEL | READ_MESSAGE_HISTORY), bot_access(bot["id"])]
    public_read_only = [overwrite(guild_id, VIEW_CHANNEL | READ_MESSAGE_HISTORY, SEND_MESSAGES), bot_access(bot["id"])]
    approved_only = [
        overwrite(guild_id, deny=VIEW_CHANNEL),
        overwrite(approved["id"], VIEW_CHANNEL | SEND_MESSAGES | READ_MESSAGE_HISTORY | USE_APPLICATION_COMMANDS),
        bot_access(bot["id"]),
    ]
    clans_private = [overwrite(guild_id, deny=VIEW_CHANNEL), bot_access(bot["id"])]
    council_only = [
        overwrite(guild_id, deny=VIEW_CHANNEL),
        overwrite(leader["id"], VIEW_CHANNEL | SEND_MESSAGES | READ_MESSAGE_HISTORY),
        overwrite(officer["id"], VIEW_CHANNEL | SEND_MESSAGES | READ_MESSAGE_HISTORY),
        bot_access(bot["id"]),
    ]
    voice_only = [
        overwrite(guild_id, deny=VIEW_CHANNEL | CONNECT),
        overwrite(approved["id"], VIEW_CHANNEL | CONNECT | SPEAK),
        bot_access(bot["id"], voice=True),
    ]

    general = find_or_create_channel(token, guild_id, "🌳 NEMETON • COMECE AQUI", 4, ("NEMETON",), permissions=public_read, position=0)
    # Categories created by the first provisioner locked the bot out. Without
    # Administrator we create clean replacements instead of touching them.
    clans = find_or_create_channel(token, guild_id, "⚔️ CLÃS • POLÍTICA E GUERRA", 4, ("CLÃS",) if administrator else (), permissions=clans_private, position=1)
    voice = find_or_create_channel(token, guild_id, "🔊 VOZ • PROXIMIDADE", 4, ("VOZ POR PROXIMIDADE",) if administrator else (), permissions=voice_only, position=2)

    welcome = find_or_create_channel(token, guild_id, "🌿・boas-vindas", 0, ("boas-vindas",), general["id"], public_read_only,
                                     "Seu primeiro passo: aprovação, vínculo e entrada no Nemeton.", 0)
    rules = find_or_create_channel(token, guild_id, "📜・regras-e-territórios", 0, ("regras",), general["id"], public_read_only,
                                   "Regras curtas, proteção de santuários e risco dos clãs.", 1)
    alerts = find_or_create_channel(token, guild_id, "📢・avisos-e-eventos", 0, ("avisos",), general["id"], public_read_only,
                                    "Dragon, Wither, expedições, manutenções e alertas de território.", 2)
    global_chat = find_or_create_channel(token, guild_id, "💬・chat-global", 0, ("chat-global",) if administrator else (), general["id"], approved_only,
                                         "Conversa integrada em tempo real com o Minecraft.", 3)
    recruitment = find_or_create_channel(token, guild_id, "🛡️・recrutamento-de-clãs", 0, ("recrutamento",) if administrator else (), general["id"], approved_only,
                                          "Apresente seu clã, procure companheiros e aceite o risco da guerra.", 4)
    commands = find_or_create_channel(token, guild_id, "🤖・central-de-comandos", 0, ("comandos",) if administrator else (), general["id"], approved_only,
                                      "Guias dos comandos Minecraft e atalhos slash do Discord.", 5)
    council = find_or_create_channel(token, guild_id, "👑・conselho-dos-líderes", 0, (), clans["id"], council_only,
                                     "Diplomacia, alianças, agendas e acordos entre líderes e vice-líderes.", 0)
    voice_lobby = find_or_create_channel(token, guild_id, "🌳 Lobby do Nemeton", 2, ("Lobby de Proximidade",), voice["id"], voice_only, position=0)

    try:
        request(token, "PUT", f"/guilds/{guild_id}/members/{guild_info['owner_id']}/roles/{approved['id']}")
    except RuntimeError:
        pass

    invite_url = ""
    try:
        invite = request(token, "POST", f"/channels/{welcome['id']}/invites", {"max_age": 0, "max_uses": 0, "temporary": False, "unique": False})
        invite_url = "https://discord.gg/" + invite["code"]
    except RuntimeError:
        pass

    art = "https://raw.githubusercontent.com/dev-netinho/nemeton/main/assets/discord/nemeton-welcome.png"
    post_once(token, welcome["id"], "welcome", [embed(
        "🌳 O Nemeton despertou",
        "Um survival comunitário Java + Bedrock: próximo do vanilla, com territórios, clãs, trocas e aventuras que deixam história.",
        0x5DBB8A,
        [("① Seja aprovado", "A administração libera o cargo **🌿 Aprovado**.", True),
         ("② Vincule a conta", "Entre no servidor e use `/discord link`; envie o código ao bot.", True),
         ("③ Comece a jornada", "Use `/menu` no Bedrock ou `/guia` no Java. O Nemeton é sua área segura.", True)],
        art)])
    post_once(token, rules["id"], "rules", [embed(
        "📜 O pacto do Nemeton",
        "A regra é simples: conflito existe, perda injusta não. Respeite pessoas, construções e os limites do sistema.",
        0xD4A85A,
        [("🏡 Jogador sem clã", "Seu **santuário pessoal** é inviolável: não pode ser atacado, roubado nem depredado.", False),
         ("⚔️ Jogador em clã", "Entrar ou criar um clã significa aceitar **atacar e ser atacado** pelas regras de raid. As invasões são agendadas e reversíveis.", False),
         ("🔑 Pessoas de confiança", "Use `/santuario confiar <jogador>` no pessoal ou `/clan confiar <jogador>` no território do clã. Remova com `desconfiar`.", False),
         ("🛡️ Limites", "Nada de grief, roubo, trapaça, assédio ou tentativa de destruir o Nemeton. Conflito só dentro das mecânicas oficiais.", False)])])
    post_once(token, commands["id"], "commands", [
        embed("🎮 Primeiros passos", "`/menu` painel Bedrock • `/guia` manual • `/kit` itens iniciais • `/nemeton` retorno ao farol • `/troca` negociação segura", 0x65B891),
        embed("🏡 Santuário pessoal", "`/santuario marcar` • `expandir` • `remover` • `confiar <jogador>` • `desconfiar <jogador>`\nAté quatro chunks conectados e sempre fora de raids.", 0x6DA9D2),
        embed("⚔️ Clãs e territórios", "`/clan criar <nome> <tag>` • `convidar` • `aceitar` • `promover` • `claim` • `unclaim` • `confiar` • `desconfiar` • `aliar` • `chat`\nNo Discord: `/clan status`, `/clan recrutar`, `/raid agenda`, `/online`.", 0xD86C5B),
    ])
    post_once(token, recruitment["id"], "recruitment", [embed(
        "🛡️ Encontre sua bandeira",
        "Líderes: apresentem **nome, tag, estilo de jogo, horários e objetivo**. Jogadores: contem o que gostam de construir ou explorar.",
        0x8B73D1,
        [("⚠️ Antes de aceitar", "Participar de um clã libera territórios e diplomacia, mas também torna você parte das guerras agendadas.", False),
         ("🤝 Recrutamento", "O líder ou vice pode usar `/clan recrutar @pessoa`; no Minecraft, o convidado confirma com `/clan aceitar`.", False)])])
    post_once(token, council["id"], "council", [embed(
        "👑 Conselho das bandeiras",
        "Canal reservado a líderes e vice-líderes. Organizem alianças, eventos, horários de raid e resolvam disputas antes que virem confusão.",
        0xF0B85B,
        [("🏰 Diplomacia", "Acordos não substituem os comandos de aliança e acesso dentro do jogo.", False),
         ("📅 Agenda", "Use `/raid agenda`; o defensor escolhe uma das três janelas propostas.", False)])])
    post_once(token, alerts["id"], "alerts", [embed(
        "📢 O sino da clareira",
        "Este canal anuncia eventos de **Ender Dragon**, **Wither**, expedições, manutenções, raids e invasões de território sem encher o chat de spam.",
        0x4FAFC1)])

    result = {
        "guild_id": guild_id,
        "guild_name": "Nemeton • Survival Comunitário" if administrator else guild_info["name"],
        "administrator": administrator,
        "owner_id": guild_info["owner_id"],
        "bot_user_id": bot["id"],
        "approved_role_id": approved["id"],
        "linked_role_id": linked["id"],
        "clan_leader_role_id": leader["id"],
        "clan_officer_role_id": officer["id"],
        "clan_member_role_id": member["id"],
        "general_category_id": general["id"],
        "clans_category_id": clans["id"],
        "voice_category_id": voice["id"],
        "welcome_channel_id": welcome["id"],
        "rules_channel_id": rules["id"],
        "alerts_channel_id": alerts["id"],
        "global_chat_channel_id": global_chat["id"],
        "recruitment_channel_id": recruitment["id"],
        "commands_channel_id": commands["id"],
        "leaders_channel_id": council["id"],
        "voice_lobby_id": voice_lobby["id"],
        "invite_url": invite_url,
    }
    cleanup_defaults(token, guild_id, set(result.values()))
    if root:
        apply_server_config(root, result, invite_url)
    json.dump(result, sys.stdout, ensure_ascii=False, indent=2)
    print()


if __name__ == "__main__":
    main()
