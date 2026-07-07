#!/usr/bin/env python3
"""Provision the Nemeton Discord guild without storing the bot token in Git."""

from __future__ import annotations

import json
import os
import sys
import urllib.error
import urllib.request

API = "https://discord.com/api/v10"

VIEW_CHANNEL = 1 << 10
SEND_MESSAGES = 1 << 11
READ_MESSAGE_HISTORY = 1 << 16
CONNECT = 1 << 20
SPEAK = 1 << 21
USE_APPLICATION_COMMANDS = 1 << 31


def request(token: str, method: str, path: str, body: object | None = None) -> object:
    payload = None if body is None else json.dumps(body).encode()
    headers = {"Authorization": f"Bot {token}", "User-Agent": "Nemeton/1.0"}
    if payload is not None:
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(API + path, data=payload, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=20) as response:
            raw = response.read()
            return None if not raw else json.loads(raw)
    except urllib.error.HTTPError as error:
        detail = error.read().decode(errors="replace")
        raise RuntimeError(f"Discord {method} {path} respondeu {error.code}: {detail}") from error


def find_or_create_role(token: str, guild_id: str, name: str) -> dict:
    roles = request(token, "GET", f"/guilds/{guild_id}/roles")
    existing = next((role for role in roles if role["name"] == name), None)
    if existing:
        return existing
    return request(token, "POST", f"/guilds/{guild_id}/roles", {
        "name": name,
        "color": 0x78B892 if name == "Aprovado" else 0x8B7AB8,
        "hoist": name == "Aprovado",
        "mentionable": False,
    })


def overwrite(role_id: str, allow: int = 0, deny: int = 0) -> dict:
    return {"id": role_id, "type": 0, "allow": str(allow), "deny": str(deny)}


def find_or_create_channel(token: str, guild_id: str, name: str, channel_type: int,
                           parent_id: str | None = None, permissions: list[dict] | None = None,
                           topic: str | None = None) -> dict:
    channels = request(token, "GET", f"/guilds/{guild_id}/channels")
    existing = next((channel for channel in channels
                     if channel["name"] == name and channel["type"] == channel_type
                     and (parent_id is None or channel.get("parent_id") == parent_id)), None)
    body: dict[str, object] = {"name": name, "type": channel_type}
    if parent_id is not None:
        body["parent_id"] = parent_id
    if permissions is not None:
        body["permission_overwrites"] = permissions
    if topic is not None and channel_type == 0:
        body["topic"] = topic
    if existing:
        return request(token, "PATCH", f"/channels/{existing['id']}", body)
    return request(token, "POST", f"/guilds/{guild_id}/channels", body)


def main() -> None:
    token = os.environ.get("DISCORD_BOT_TOKEN", "").strip()
    if not token:
        raise SystemExit("DISCORD_BOT_TOKEN ausente")
    guilds = request(token, "GET", "/users/@me/guilds")
    requested = os.environ.get("DISCORD_GUILD_ID", "").strip()
    if requested:
        guild = next((item for item in guilds if item["id"] == requested), None)
        if guild is None:
            raise SystemExit("O bot ainda não pertence ao DISCORD_GUILD_ID informado")
    elif len(guilds) == 1:
        guild = guilds[0]
    else:
        raise SystemExit(f"Esperava exatamente um servidor Discord, encontrei {len(guilds)}")

    guild_id = guild["id"]
    guild_info = request(token, "GET", f"/guilds/{guild_id}")
    approved = find_or_create_role(token, guild_id, "Aprovado")
    linked = find_or_create_role(token, guild_id, "Vinculado")

    public = [overwrite(guild_id, VIEW_CHANNEL | READ_MESSAGE_HISTORY, 0)]
    approved_only = [
        overwrite(guild_id, 0, VIEW_CHANNEL),
        overwrite(approved["id"], VIEW_CHANNEL | SEND_MESSAGES | READ_MESSAGE_HISTORY | USE_APPLICATION_COMMANDS, 0),
    ]
    voice_only = [
        overwrite(guild_id, 0, VIEW_CHANNEL | CONNECT),
        overwrite(approved["id"], VIEW_CHANNEL | CONNECT | SPEAK, 0),
    ]

    general_category = find_or_create_channel(token, guild_id, "NEMETON", 4)
    clans_category = find_or_create_channel(token, guild_id, "CLÃS", 4, permissions=approved_only)
    voice_category = find_or_create_channel(token, guild_id, "VOZ POR PROXIMIDADE", 4, permissions=voice_only)
    welcome = find_or_create_channel(token, guild_id, "boas-vindas", 0, general_category["id"],
                                     [overwrite(guild_id, VIEW_CHANNEL | READ_MESSAGE_HISTORY, SEND_MESSAGES)],
                                     "Como entrar, vincular sua conta e jogar no Nemeton.")
    alerts = find_or_create_channel(token, guild_id, "avisos", 0, general_category["id"],
                                    [overwrite(guild_id, VIEW_CHANNEL | READ_MESSAGE_HISTORY, SEND_MESSAGES)],
                                    "Eventos, bosses, raids e avisos oficiais do Nemeton.")
    global_chat = find_or_create_channel(token, guild_id, "chat-global", 0, general_category["id"], approved_only,
                                         "Chat integrado com o servidor Minecraft.")
    recruitment = find_or_create_channel(token, guild_id, "recrutamento", 0, general_category["id"], approved_only,
                                          "Recrutamento e apresentação dos clãs.")
    commands = find_or_create_channel(token, guild_id, "comandos", 0, general_category["id"], approved_only,
                                      "Use /online, /clan e /raid sem poluir o chat global.")
    voice_lobby = find_or_create_channel(token, guild_id, "Lobby de Proximidade", 2, voice_category["id"], voice_only)

    try:
        request(token, "PUT", f"/guilds/{guild_id}/members/{guild_info['owner_id']}/roles/{approved['id']}")
    except RuntimeError:
        pass

    result = {
        "guild_id": guild_id,
        "guild_name": guild["name"],
        "owner_id": guild_info["owner_id"],
        "approved_role_id": approved["id"],
        "linked_role_id": linked["id"],
        "general_category_id": general_category["id"],
        "clans_category_id": clans_category["id"],
        "voice_category_id": voice_category["id"],
        "welcome_channel_id": welcome["id"],
        "alerts_channel_id": alerts["id"],
        "global_chat_channel_id": global_chat["id"],
        "recruitment_channel_id": recruitment["id"],
        "commands_channel_id": commands["id"],
        "voice_lobby_id": voice_lobby["id"],
    }
    json.dump(result, sys.stdout, ensure_ascii=False, indent=2)
    print()


if __name__ == "__main__":
    main()
