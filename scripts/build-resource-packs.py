#!/usr/bin/env python3
"""Build Nemeton Java and Bedrock resource packs with deterministic pixel-art assets."""

from __future__ import annotations

import hashlib
import json
import shutil
import urllib.request
import zipfile
from pathlib import Path

from PIL import Image, ImageEnhance

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

BASE_TEXTURES = {
    "root_essence": "amethyst_shard",
    "root_blade": "diamond_sword",
    "warden_axe": "diamond_axe",
    "sentinel_chestplate": "diamond_chestplate",
    "abyss_heart": "nether_star",
    "end_heart": "dragon_breath",
    "backpack": "bundle",
}

PALETTES = {
    "root_essence": [(25, 17, 42), (72, 43, 116), (145, 79, 205), (218, 146, 255), (194, 248, 255)],
    "root_blade": [(19, 18, 28), (50, 38, 82), (111, 57, 164), (187, 94, 235), (144, 243, 255)],
    "warden_axe": [(18, 24, 29), (35, 63, 69), (55, 125, 127), (100, 205, 190), (210, 250, 214)],
    "sentinel_chestplate": [(17, 24, 27), (29, 69, 66), (55, 132, 111), (109, 215, 163), (225, 252, 207)],
    "abyss_heart": [(14, 7, 18), (49, 13, 55), (104, 18, 78), (190, 38, 99), (255, 121, 170)],
    "end_heart": [(24, 13, 45), (67, 35, 111), (122, 64, 182), (195, 111, 234), (148, 236, 255)],
    "backpack": [(38, 23, 22), (78, 44, 34), (131, 76, 47), (194, 127, 70), (245, 205, 113)],
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


def remove_overlong_paths(root: Path, maximum: int = 79) -> None:
    """Remove optional assets that Bedrock rejects because their path is too long."""
    for path in sorted(root.rglob("*"), reverse=True):
        if path.is_file() and len(path.relative_to(root).as_posix()) > maximum:
            path.unlink()


def styled_icon(name: str, base_root: Path) -> Image.Image:
    """Recolor a detailed Faithful sprite while preserving its shape and alpha."""
    source = base_root / "assets" / "minecraft" / "textures" / "item" / f"{BASE_TEXTURES[name]}.png"
    image = Image.open(source).convert("RGBA")
    pixels = image.load()
    palette = PALETTES[name]
    for y in range(image.height):
        for x in range(image.width):
            red, green, blue, alpha = pixels[x, y]
            if alpha == 0:
                continue
            luminance = (red * 299 + green * 587 + blue * 114) // 1000
            position = luminance * (len(palette) - 1) / 255
            lower = int(position)
            upper = min(lower + 1, len(palette) - 1)
            mix = position - lower
            color = tuple(round(palette[lower][channel] * (1 - mix) + palette[upper][channel] * mix) for channel in range(3))
            pixels[x, y] = (*color, alpha)
    return ImageEnhance.Contrast(image).enhance(1.08)


def save_icon(image: Image.Image, destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    image.save(destination)


def build_java(base_root: Path) -> None:
    ensure_clean(JAVA)
    write_json(JAVA / "pack.mcmeta", {"pack": {"pack_format": 88, "description": "Nemeton + Faithful 32x — Vanilla+ crossplay"}})
    save_icon(styled_icon("root_essence", base_root), JAVA / "pack.png")
    for name, data in ITEMS.items():
        save_icon(styled_icon(name, base_root), JAVA / "assets" / "nemeton" / "textures" / "item" / f"{name}.png")
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
    shutil.copy2(JAVA / "pack.png", BEDROCK / "pack_icon.png")
    write_json(BEDROCK / "manifest.json", {
        "format_version": 2,
        "header": {
            "name": "Nemeton + Faithful 32x",
            "description": "Texturas completas Vanilla+ e itens Nemeton para Bedrock via Geyser.",
            "uuid": "8953d48d-9bd4-4c62-bf43-1b186aa73f76",
            "version": [1, 1, 1],
            "min_engine_version": [1, 21, 130]
        },
        "modules": [{
            "type": "resources",
            "uuid": "cde0d758-04f3-44ac-9a55-8aed325c930c",
            "version": [1, 1, 1]
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
        destination = BEDROCK / "textures" / "items" / f"{name}.png"
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(JAVA / "assets" / "nemeton" / "textures" / "item" / f"{name}.png", destination)
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
    java_archive = download(FAITHFUL_JAVA_URL, CACHE / f"faithful-java-{FAITHFUL_JAVA_SHA}.zip")
    bedrock_archive = download(FAITHFUL_BEDROCK_URL, CACHE / f"faithful-bedrock-{FAITHFUL_BEDROCK_SHA}.zip")
    stage_archive(java_archive, JAVA_STAGING)
    stage_archive(bedrock_archive, BEDROCK_STAGING)
    build_java(JAVA_STAGING)
    build_bedrock()
    build_geyser_mapping()
    merge_overlay(JAVA, JAVA_STAGING)
    merge_overlay(BEDROCK, BEDROCK_STAGING)
    remove_overlong_paths(BEDROCK_STAGING)
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
