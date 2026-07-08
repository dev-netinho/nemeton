#!/usr/bin/env python3
"""Configure DNS records for the Nemeton Minecraft server on Cloudflare.

The script intentionally reads secrets only from environment variables.

Required:
  CLOUDFLARE_API_TOKEN  token with Zone:Read and DNS:Edit for olua.me

Optional:
  NEMETON_ZONE          defaults to olua.me
  NEMETON_BEDROCK_HOST defaults to documents-voicing.gl.at.ply.gg
  NEMETON_BEDROCK_PORT defaults to 59460
  NEMETON_JAVA_HOST    Playit/free tunnel host for Java, if available
  NEMETON_JAVA_PORT    Playit/free tunnel port for Java, if available
"""

from __future__ import annotations

import json
import os
import sys
import time
import urllib.error
import urllib.request


API = "https://api.cloudflare.com/client/v4"


def env(name: str, default: str | None = None) -> str:
    value = os.environ.get(name, default)
    if value is None or not value.strip():
        raise SystemExit(f"Missing required environment variable: {name}")
    return value.strip()


def request(method: str, path: str, token: str, payload: dict | None = None) -> dict:
    data = None if payload is None else json.dumps(payload).encode()
    req = urllib.request.Request(
        API + path,
        method=method,
        data=data,
        headers={
            "Authorization": f"Bearer {token}",
            "Content-Type": "application/json",
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=20) as response:
            result = json.loads(response.read().decode())
    except urllib.error.HTTPError as error:
        body = error.read().decode(errors="replace")
        raise SystemExit(f"Cloudflare API error {error.code}: {body}") from error
    if not result.get("success"):
        raise SystemExit("Cloudflare API failure: " + json.dumps(result, ensure_ascii=False))
    return result


def zone_id(token: str, zone: str) -> str:
    result = request("GET", f"/zones?name={zone}", token)
    zones = result.get("result", [])
    if not zones:
        raise SystemExit(f"Zone not found in Cloudflare account: {zone}")
    return zones[0]["id"]


def existing_records(token: str, zid: str, name: str, record_type: str) -> list[dict]:
    result = request(
        "GET",
        f"/zones/{zid}/dns_records?type={record_type}&name={name}",
        token,
    )
    return result.get("result", [])


def delete_conflicting(token: str, zid: str, name: str, keep_type: str) -> None:
    for record_type in ("A", "AAAA", "CNAME", "SRV"):
        if record_type == keep_type:
            continue
        for record in existing_records(token, zid, name, record_type):
            request("DELETE", f"/zones/{zid}/dns_records/{record['id']}", token)
            print(f"deleted {record_type} {name}")


def delete_records(token: str, zid: str, name: str, record_types: tuple[str, ...]) -> None:
    for record_type in record_types:
        for record in existing_records(token, zid, name, record_type):
            request("DELETE", f"/zones/{zid}/dns_records/{record['id']}", token)
            print(f"deleted {record_type} {name}")


def upsert(token: str, zid: str, payload: dict) -> None:
    name = payload["name"]
    record_type = payload["type"]
    delete_conflicting(token, zid, name, record_type)
    matches = existing_records(token, zid, name, record_type)
    if matches:
        request("PATCH", f"/zones/{zid}/dns_records/{matches[0]['id']}", token, payload)
        print(f"updated {record_type} {name}")
        for extra in matches[1:]:
            request("DELETE", f"/zones/{zid}/dns_records/{extra['id']}", token)
            print(f"deleted duplicate {record_type} {name}")
    else:
        request("POST", f"/zones/{zid}/dns_records", token, payload)
        print(f"created {record_type} {name}")


def wait_dns(name: str, expected: str | None = None) -> None:
    # Keep this intentionally lightweight; final validation uses dig/RakNet outside.
    deadline = time.time() + 120
    while time.time() < deadline:
        try:
            result = urllib.request.urlopen(f"https://cloudflare-dns.com/dns-query?name={name}&type=CNAME", timeout=10)
            if result.status in (200, 204):
                return
        except Exception:
            pass
        time.sleep(5)


def main() -> int:
    token = env("CLOUDFLARE_API_TOKEN")
    zone = env("NEMETON_ZONE", "olua.me")
    bedrock_host = env("NEMETON_BEDROCK_HOST", "documents-voicing.gl.at.ply.gg").rstrip(".")
    bedrock_port = int(env("NEMETON_BEDROCK_PORT", "59460"))
    java_host = os.environ.get("NEMETON_JAVA_HOST", "").strip().rstrip(".")
    java_port = os.environ.get("NEMETON_JAVA_PORT", "").strip()

    zid = zone_id(token, zone)

    upsert(
        token,
        zid,
        {
            "type": "CNAME",
            "name": f"b.nemeton.{zone}",
            "content": bedrock_host,
            "ttl": 300,
            "proxied": False,
            "comment": f"Nemeton Bedrock free Playit tunnel, port {bedrock_port}",
        },
    )

    if java_host and java_port:
        upsert(
            token,
            zid,
            {
                "type": "SRV",
                "name": f"_minecraft._tcp.nemeton.{zone}",
                "data": {
                    "priority": 0,
                    "weight": 5,
                    "port": int(java_port),
                    "target": java_host,
                },
                "ttl": 300,
                "proxied": False,
                "comment": "Nemeton Java Minecraft SRV record",
            },
        )
        # Do not publish a Java CNAME shortcut: Playit's free Minecraft Java
        # gateway validates the hostname in the Minecraft handshake. The safe
        # friendly address is the SRV record on nemeton.<zone>.
        delete_records(token, zid, f"j.nemeton.{zone}", ("A", "AAAA", "CNAME", "SRV"))
        print(f"java_ready=nemeton.{zone} via SRV -> {java_host}:{java_port}")
    else:
        print("java_ready=no; set NEMETON_JAVA_HOST and NEMETON_JAVA_PORT after creating the Java TCP tunnel")

    print(f"bedrock_ready=b.nemeton.{zone}:{bedrock_port} -> {bedrock_host}:{bedrock_port}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
