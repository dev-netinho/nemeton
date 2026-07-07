#!/usr/bin/env python3
"""Build Nemeton Java and Bedrock resource packs with deterministic pixel-art assets."""

from __future__ import annotations

import hashlib
import json
import os
import shutil
import struct
import urllib.request
import zlib
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "resourcepacks"
JAVA = SRC / "nemeton-java"
BEDROCK = SRC / "nemeton-bedrock"
RUNTIME = ROOT / "runtime" / "resourcepacks"
DIST = SRC / "dist"
GEYSER = SRC / "geyser"
CACHE = ROOT / ".cache" / "resourcepacks"
JAVA_STAGING = CACHE / "java-full"
BEDROCK_STAGING = CACHE / "bedrock-full"

FAITHFUL_JAVA_SHA = "e3e7c8d99a30bc1a97f529a973f32d7b08833243"
FAITHFUL_BEDROCK_SHA = "0c02eb2c20930677f19f51d87294f30147db8bb0"
FAITHFUL_JAVA_URL = f"https://github.com/Faithful-Resource-Pack/Faithful-32x-Java/archive/{FAITHFUL_JAVA_SHA}.zip"
FAITHFUL_BEDROCK_URL = f"https://github.com/Faithful-Resource-Pack/Faithful-32x-Bedrock/archive/{FAITHFUL_BEDROCK_SHA}.zip"

ITEMS = {
    "root_essence": {"java_parent": "minecraft:item/generated", "bedrock_icon": "nemeton_root_essence", "base": "minecraft:amethyst_shard", "cmd": 7101, "handheld": False},
    "root_blade": {"java_parent": "minecraft:item/handheld", "bedrock_icon": "nemeton_root_blade", "base": "minecraft:diamond_sword", "cmd": 7102, "handheld": True},
    "warden_axe": {"java_parent": "minecraft:item/handheld", "bedrock_icon": "nemeton_warden_axe", "base": "minecraft:diamond_axe", "cmd": 7103, "handheld": True},
    "sentinel_chestplate": {"java_parent": "minecraft:item/generated", "bedrock_icon": "nemeton_sentinel_chestplate", "base": "minecraft:diamond_chestplate", "cmd": 7104, "handheld": False},
    "abyss_heart": {"java_parent": "minecraft:item/generated", "bedrock_icon": "nemeton_abyss_heart", "base": "minecraft:nether_star", "cmd": 7105, "handheld": False},
    "end_heart": {"java_parent": "minecraft:item/generated", "bedrock_icon": "nemeton_end_heart", "base": "minecraft:dragon_breath", "cmd": 7106, "handheld": False},
    "backpack": {"java_parent": "minecraft:item/generated", "bedrock_icon": "nemeton_backpack", "base": "minecraft:bundle", "cmd": 7110, "handheld": False},
}

NAMES = {
    "root_essence": "Essência do Nemeton",
    "root_blade": "Lâmina do Nemeton",
    "warden_axe": "Machado do Guardião",
    "sentinel_chestplate": "Peitoral Sentinela",
    "abyss_heart": "Coração Abissal",
    "end_heart": "Coração do Fim",
    "backpack": "Mochila do Nemeton",
}


def ensure_clean(path: Path) -> None:
    if path.exists():
        shutil.rmtree(path)
    path.mkdir(parents=True, exist_ok=True)


def write_json(path: Path, data: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def write_text(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def download(url: str, destination: Path) -> Path:
    destination.parent.mkdir(parents=True, exist_ok=True)
    if not destination.is_file():
        print(f"Downloading {url}")
        urllib.request.urlretrieve(url, destination)
    return destination


def stage_archive(archive: Path, destination: Path) -> None:
    ensure_clean(destination)
    extracted = destination.parent / f"{destination.name}-extract"
    ensure_clean(extracted)
    with zipfile.ZipFile(archive) as source:
        source.extractall(extracted)
    roots = [path for path in extracted.iterdir() if path.is_dir()]
    if len(roots) != 1:
        raise RuntimeError(f"Unexpected archive structure in {archive}")
    shutil.copytree(roots[0], destination, dirs_exist_ok=True)
    shutil.rmtree(extracted)
    for unwanted in (".github", ".gitignore", "README.md"):
        path = destination / unwanted
        if path.is_dir():
            shutil.rmtree(path)
        elif path.exists():
            path.unlink()


def merge_overlay(overlay: Path, destination: Path) -> None:
    shutil.copytree(overlay, destination, dirs_exist_ok=True)


class Canvas:
    def __init__(self, size: int = 32):
        self.size = size
        self.pixels = [[(0, 0, 0, 0) for _ in range(size)] for _ in range(size)]

    def set(self, x: int, y: int, color: tuple[int, int, int, int]) -> None:
        if 0 <= x < self.size and 0 <= y < self.size:
            self.pixels[y][x] = color

    def rect(self, x0: int, y0: int, x1: int, y1: int, color: tuple[int, int, int, int]) -> None:
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                self.set(x, y, color)

    def line(self, x0: int, y0: int, x1: int, y1: int, color: tuple[int, int, int, int], width: int = 1) -> None:
        dx = abs(x1 - x0)
        dy = -abs(y1 - y0)
        sx = 1 if x0 < x1 else -1
        sy = 1 if y0 < y1 else -1
        err = dx + dy
        x, y = x0, y0
        while True:
            r = width // 2
            self.rect(x - r, y - r, x + r, y + r, color)
            if x == x1 and y == y1:
                break
            e2 = 2 * err
            if e2 >= dy:
                err += dy
                x += sx
            if e2 <= dx:
                err += dx
                y += sy

    def diamond(self, cx: int, cy: int, radius: int, color: tuple[int, int, int, int]) -> None:
        for y in range(cy - radius, cy + radius + 1):
            for x in range(cx - radius, cx + radius + 1):
                if abs(x - cx) + abs(y - cy) <= radius:
                    self.set(x, y, color)

    def save(self, path: Path) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        raw = bytearray()
        for row in self.pixels:
            raw.append(0)
            for r, g, b, a in row:
                raw.extend((r, g, b, a))
        def chunk(kind: bytes, data: bytes) -> bytes:
            return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)
        png = b"\x89PNG\r\n\x1a\n"
        png += chunk(b"IHDR", struct.pack(">IIBBBBB", self.size, self.size, 8, 6, 0, 0, 0))
        png += chunk(b"IDAT", zlib.compress(bytes(raw), 9))
        png += chunk(b"IEND", b"")
        path.write_bytes(png)


def icon(name: str) -> Canvas:
    c = Canvas()
    dark = (34, 23, 31, 255)
    wood = (96, 57, 38, 255)
    gold = (245, 198, 78, 255)
    purple = (184, 85, 255, 255)
    violet = (105, 58, 181, 255)
    cyan = (118, 242, 255, 255)
    green = (76, 184, 112, 255)
    black = (14, 10, 18, 255)
    red = (165, 26, 64, 255)
    if name == "root_essence":
        c.diamond(16, 15, 10, violet)
        c.diamond(16, 14, 6, purple)
        c.line(12, 7, 19, 23, cyan, 1)
        c.set(16, 14, (255, 255, 255, 255))
    elif name == "root_blade":
        c.line(8, 25, 24, 7, dark, 5)
        c.line(10, 23, 23, 8, purple, 3)
        c.line(12, 21, 22, 9, cyan, 1)
        c.line(6, 26, 12, 20, wood, 4)
        c.line(6, 21, 11, 26, gold, 2)
    elif name == "warden_axe":
        c.line(10, 27, 20, 6, wood, 4)
        c.rect(17, 5, 25, 12, dark)
        c.rect(19, 6, 27, 14, violet)
        c.rect(20, 7, 24, 10, cyan)
        c.line(13, 19, 25, 7, gold, 1)
    elif name == "sentinel_chestplate":
        c.rect(9, 9, 22, 25, dark)
        c.rect(11, 10, 20, 23, green)
        c.rect(7, 11, 11, 17, violet)
        c.rect(20, 11, 24, 17, violet)
        c.line(10, 14, 21, 14, gold, 1)
        c.diamond(16, 18, 3, cyan)
    elif name == "abyss_heart":
        c.diamond(16, 16, 11, black)
        c.diamond(16, 16, 7, red)
        c.line(9, 16, 23, 16, purple, 2)
        c.line(16, 8, 16, 24, violet, 2)
    elif name == "end_heart":
        c.diamond(16, 16, 11, violet)
        c.diamond(16, 16, 7, purple)
        c.line(8, 18, 24, 12, cyan, 2)
        c.line(10, 9, 22, 24, gold, 1)
    elif name == "backpack":
        c.rect(8, 10, 23, 25, wood)
        c.rect(10, 8, 21, 14, (128, 77, 43, 255))
        c.rect(11, 15, 20, 23, (72, 43, 32, 255))
        c.line(8, 12, 4, 20, dark, 2)
        c.line(23, 12, 27, 20, dark, 2)
        c.rect(14, 16, 17, 19, gold)
        c.line(9, 11, 22, 24, purple, 1)
    return c


def build_java() -> None:
    ensure_clean(JAVA)
    write_json(JAVA / "pack.mcmeta", {"pack": {"pack_format": 88, "description": "Nemeton + Faithful 32x — Vanilla+ crossplay"}})
    icon("root_essence").save(JAVA / "pack.png")
    for name, data in ITEMS.items():
        icon(name).save(JAVA / "assets" / "nemeton" / "textures" / "item" / f"{name}.png")
        write_json(JAVA / "assets" / "nemeton" / "models" / "item" / f"{name}.json", {
            "parent": data["java_parent"],
            "textures": {"layer0": f"nemeton:item/{name}"}
        })
        write_json(JAVA / "assets" / "nemeton" / "items" / f"{name}.json", {
            "model": {"type": "minecraft:model", "model": f"nemeton:item/{name}"}
        })
    write_json(JAVA / "assets" / "minecraft" / "lang" / "pt_br.json", {
        "resourcePack.nemeton.name": "Nemeton Visual Pack",
        "resourcePack.nemeton.description": "Texturas Vanilla+ do Nemeton"
    })
    write_text(JAVA / "CREDITS-NEMETON.txt",
               "Nemeton Visual Pack overlay by Nemeton contributors.\n"
               "Base textures: Faithful 32x by Vattic & Faithful Team.\n"
               "https://faithfulpack.net/\nhttps://faithfulpack.net/license\n")


def build_bedrock() -> None:
    ensure_clean(BEDROCK)
    icon("root_essence").save(BEDROCK / "pack_icon.png")
    write_json(BEDROCK / "manifest.json", {
        "format_version": 2,
        "header": {
            "name": "Nemeton + Faithful 32x",
            "description": "Texturas completas Vanilla+ e itens Nemeton para Bedrock via Geyser.",
            "uuid": "8953d48d-9bd4-4c62-bf43-1b186aa73f76",
            "version": [1, 1, 0],
            "min_engine_version": [1, 21, 130]
        },
        "modules": [{
            "type": "resources",
            "uuid": "cde0d758-04f3-44ac-9a55-8aed325c930c",
            "version": [1, 1, 0]
        }],
        "metadata": {
            "authors": ["Nemeton contributors", "Vattic", "Faithful Team"],
            "license": "https://faithfulpack.net/license",
            "url": "https://faithfulpack.net/"
        },
        "capabilities": ["pbr"]
    })
    texture_data = {}
    for name, data in ITEMS.items():
        icon(name).save(BEDROCK / "textures" / "items" / f"{name}.png")
        texture_data[data["bedrock_icon"]] = {"textures": f"textures/items/{name}"}
    write_json(BEDROCK / "textures" / "item_texture.json", {
        "resource_pack_name": "nemeton",
        "texture_name": "atlas.items",
        "texture_data": texture_data
    })
    write_text(BEDROCK / "texts" / "en_US.lang", "\n".join(f"item.nemeton:{name}.name={label}" for name, label in NAMES.items()) + "\n")
    write_text(BEDROCK / "CREDITS-NEMETON.txt",
               "Nemeton Visual Pack overlay by Nemeton contributors.\n"
               "Base textures: Faithful 32x by Vattic & Faithful Team.\n"
               "https://faithfulpack.net/\nhttps://faithfulpack.net/license\n")


def build_geyser_mapping() -> None:
    GEYSER.mkdir(parents=True, exist_ok=True)
    items: dict[str, list[dict[str, object]]] = {}
    for name, data in ITEMS.items():
        items.setdefault(data["base"], []).append({
            "type": "legacy",
            "custom_model_data": data["cmd"],
            "bedrock_identifier": f"nemeton:{name}",
            "bedrock_options": {
                "icon": data["bedrock_icon"],
                "display_handheld": data["handheld"]
            },
            "display_name": NAMES[name]
        })
    write_json(GEYSER / "nemeton-items.json", {"format_version": 2, "items": items})


def zip_dir(source: Path, destination: Path) -> str:
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.exists():
        destination.unlink()
    fixed = (2026, 7, 7, 12, 0, 0)
    with zipfile.ZipFile(destination, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as pack:
        for path in sorted(source.rglob("*")):
            if not path.is_file():
                continue
            info = zipfile.ZipInfo(path.relative_to(source).as_posix(), fixed)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            pack.writestr(info, path.read_bytes())
    return hashlib.sha1(destination.read_bytes()).hexdigest()


def main() -> None:
    build_java()
    build_bedrock()
    build_geyser_mapping()
    java_archive = download(FAITHFUL_JAVA_URL, CACHE / f"faithful-java-{FAITHFUL_JAVA_SHA}.zip")
    bedrock_archive = download(FAITHFUL_BEDROCK_URL, CACHE / f"faithful-bedrock-{FAITHFUL_BEDROCK_SHA}.zip")
    stage_archive(java_archive, JAVA_STAGING)
    stage_archive(bedrock_archive, BEDROCK_STAGING)
    merge_overlay(JAVA, JAVA_STAGING)
    merge_overlay(BEDROCK, BEDROCK_STAGING)
    DIST.mkdir(parents=True, exist_ok=True)
    RUNTIME.mkdir(parents=True, exist_ok=True)
    java_hash = zip_dir(JAVA_STAGING, DIST / "Nemeton-Java.zip")
    zip_dir(BEDROCK_STAGING, DIST / "Nemeton-Bedrock.mcpack")
    shutil.copy2(DIST / "Nemeton-Java.zip", RUNTIME / "Nemeton-Java.zip")
    shutil.copy2(DIST / "Nemeton-Bedrock.mcpack", RUNTIME / "Nemeton-Bedrock.mcpack")
    (DIST / "Nemeton-Java.sha1").write_text(java_hash + "\n", encoding="utf-8")
    (RUNTIME / "Nemeton-Java.sha1").write_text(java_hash + "\n", encoding="utf-8")
    print(java_hash)


if __name__ == "__main__":
    main()
