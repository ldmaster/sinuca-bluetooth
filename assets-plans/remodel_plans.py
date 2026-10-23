#!/usr/bin/env python3
"""Planos tipados do blender-forge para a remodelagem da mesa.

Não usa bpy: apenas emite JSON com as operações do harness. Duas bases:

* table_classic: pernas torneadas, painel/medalhão no apron, trilhos e apron
  com bevel maior, miras em losango, piso/backdrop e três câmeras.
* table_modern: pernas cilíndricas (monólito), apron liso, mesmos trilhos,
  miras e câmeras.

Os quatro temas (A Heritage, B Arena, C Foundry, D Neo) são aplicados por
planos de material/luz/render sobre a base correspondente. Os planos usam os
nomes de material existentes para atualizar a cor compartilhada de cada grupo.
"""

from __future__ import annotations

import json
import math
from pathlib import Path

OUT = Path(__file__).resolve().parent

OPS: list[dict] = []


def op(op_name: str, **fields) -> None:
    OPS.append({"op": op_name, **fields})


def reset() -> None:
    OPS.clear()


def save(path: str) -> None:
    op("save", path=path)


def linear(hex_color: str) -> tuple[float, float, float, float]:
    value = hex_color.lstrip("#")
    channels = [int(value[i : i + 2], 16) / 255.0 for i in (0, 2, 4)]

    def convert(channel: float) -> float:
        if channel <= 0.04045:
            return channel / 12.92
        return ((channel + 0.055) / 1.055) ** 2.4

    return tuple(round(convert(channel), 5) for channel in channels) + (1.0,)


def linear3(hex_color: str) -> list[float]:
    return list(linear(hex_color)[:3])


def cube(name: str, location, scale, rotation=(0.0, 0.0, 0.0)) -> str:
    op("primitive", kind="cube", name=name, size=1.0,
       location=list(location), scale=list(scale), rotation=list(rotation))
    return name


def cylinder(name: str, location, diameter: float, height: float,
             rotation=(0.0, 0.0, 0.0)) -> str:
    op("primitive", kind="cylinder", name=name, size=diameter,
       location=list(location), rotation=list(rotation),
       scale=[1.0, 1.0, height / diameter])
    return name


def torus(name: str, location, outer_diameter: float,
          rotation=(0.0, 0.0, 0.0), z_scale: float = 1.0) -> str:
    op("primitive", kind="torus", name=name, size=outer_diameter,
       location=list(location), rotation=list(rotation),
       scale=[1.0, 1.0, z_scale])
    return name


def material(target: str, name: str, color: str, roughness: float,
             metallic: float = 0.0) -> None:
    op("material", object=target, name=name, color=list(linear(color)),
       roughness=roughness, metallic=metallic)


def bevel(target: str, width: float, segments: int) -> None:
    op("modifier", object=target, kind="bevel",
       params={"width": width, "segments": segments})
    op("apply_modifier", object=target, kind="bevel")


def join(objects: list[str], name: str) -> None:
    op("join", objects=objects, name=name)


# --------------------------------------------------------------------------- #
# Shared geometry
# --------------------------------------------------------------------------- #
LEG_X = 1.33
LEG_Y = 0.70


def build_sights() -> None:
    sights: list[str] = []
    step = 1.27 / 4.0
    for index in range(-3, 4):
        x = index * step
        if abs(x) < 1e-6:
            continue
        for sign in (-1.0, 1.0):
            name = f"SIGHT_L{index}_{'T' if sign > 0 else 'B'}"
            sights.append(cube(
                name,
                (x, sign * (0.635 + 0.048 + 0.075), 0.8015),
                (0.014, 0.014, 0.003),
                rotation=(0.0, 0.0, math.pi / 4.0),
            ))
    for index in (-1, 0, 1):
        y = index * 0.635 / 2.0
        for sign in (-1.0, 1.0):
            name = f"SIGHT_S{index}_{'R' if sign > 0 else 'L'}"
            sights.append(cube(
                name,
                (sign * (1.27 + 0.048 + 0.075), y, 0.8015),
                (0.014, 0.014, 0.003),
                rotation=(0.0, 0.0, math.pi / 4.0),
            ))
    join(sights, "SM_Sights")
    material("SM_Sights", "MAT_Sight", "#E8DCC0", 0.3, 0.2)
    bevel("SM_Sights", 0.0005, 1)


def build_classic_legs() -> None:
    parts: list[str] = []
    for sx in (-1.0, 1.0):
        for sy in (-1.0, 1.0):
            x, y = sx * LEG_X, sy * LEG_Y
            tag = f"{'R' if sx > 0 else 'L'}{'F' if sy > 0 else 'B'}"
            parts.append(cube(f"LEG_TOP_{tag}", (x, y, 0.565),
                              (0.12, 0.12, 0.07)))
            parts.append(cylinder(f"LEG_SHAFT_{tag}", (x, y, 0.295),
                                  0.11, 0.47))
            parts.append(torus(f"LEG_COLLAR_{tag}", (x, y, 0.16),
                               0.16, z_scale=0.5))
            parts.append(cylinder(f"LEG_FOOT_{tag}", (x, y, 0.03),
                                  0.16, 0.06))
            parts.append(cylinder(f"LEG_BASE_{tag}", (x, y, 0.01),
                                  0.18, 0.02))
    join(parts, "SM_Legs")
    material("SM_Legs", "MAT_Wood", "#4A2C1B", 0.4)
    bevel("SM_Legs", 0.0015, 2)


def build_modern_legs() -> None:
    parts: list[str] = []
    for sx in (-1.0, 1.0):
        for sy in (-1.0, 1.0):
            x, y = sx * LEG_X, sy * LEG_Y
            tag = f"{'R' if sx > 0 else 'L'}{'F' if sy > 0 else 'B'}"
            parts.append(cube(f"LEG_TOP_{tag}", (x, y, 0.585),
                              (0.15, 0.15, 0.03)))
            parts.append(cylinder(f"LEG_SHAFT_{tag}", (x, y, 0.3),
                                  0.14, 0.54))
            parts.append(cylinder(f"LEG_FOOT_{tag}", (x, y, 0.015),
                                  0.16, 0.03))
    join(parts, "SM_Legs")
    material("SM_Legs", "MAT_Wood", "#1A1F24", 0.38, 0.55)
    bevel("SM_Legs", 0.008, 3)


def build_apron_detail() -> None:
    # recessed panel on the long sides
    front = cube("APRON_CUT_F", (0.0, -0.826, 0.675),
                 (2.2, 0.014, 0.10))
    op("boolean", object="SM_Apron", operand=front,
       operation="difference", apply=True)
    op("delete", object=front)
    back = cube("APRON_CUT_B", (0.0, 0.826, 0.675),
                (2.2, 0.014, 0.10))
    op("boolean", object="SM_Apron", operand=back,
       operation="difference", apply=True)
    op("delete", object=back)

    # molding frame around the front panel
    moldings = [
        cube("MOLD_T", (0.0, -0.834, 0.728), (2.22, 0.012, 0.012)),
        cube("MOLD_B", (0.0, -0.834, 0.622), (2.22, 0.012, 0.012)),
        cube("MOLD_L", (-1.107, -0.834, 0.675), (0.012, 0.012, 0.118)),
        cube("MOLD_R", (1.107, -0.834, 0.675), (0.012, 0.012, 0.118)),
    ]
    join(moldings, "SM_Moldings")
    material("SM_Moldings", "MAT_Wood", "#4A2C1B", 0.4)
    bevel("SM_Moldings", 0.0012, 2)

    # brass medallion
    cylinder("SM_Medallion", (0.0, -0.826, 0.675), 0.12, 0.016,
             rotation=(math.pi / 2.0, 0.0, 0.0))
    material("SM_Medallion", "MAT_Brass", "#B08D57", 0.3, 0.9)
    bevel("SM_Medallion", 0.0008, 1)


def build_environment() -> None:
    op("primitive", kind="plane", name="FLOOR", size=9.0,
       location=[0.0, 0.0, -0.002])
    material("FLOOR", "MAT_Floor", "#0A0A0B", 0.6)
    op("primitive", kind="plane", name="BACKDROP", size=9.0,
       location=[-4.0, 0.0, 1.6], rotation=[0.0, math.pi / 2.0, 0.0])
    material("BACKDROP", "MAT_Backdrop", "#08090A", 0.85)


def build_cameras() -> None:
    op("camera", name="CAM-hero", location=(3.6, -3.9, 2.6),
       look_at=(0.0, 0.0, 0.66), lens=52.0)
    op("camera", name="CAM-top", location=(0.0, 0.0, 6.0),
       look_at=(0.0, 0.0, 0.60), lens=50.0)
    op("camera", name="CAM-pocket", location=(2.25, -2.05, 1.50),
       look_at=(1.16, -0.57, 0.78), lens=80.0)


def common_edits() -> None:
    for name in ("SM_Legs", "SM_Sights", "LGT-key", "LGT-fill"):
        op("delete", object=name)


def base_plan(classic: bool) -> dict:
    reset()
    common_edits()
    if classic:
        build_classic_legs()
        build_apron_detail()
    else:
        build_modern_legs()
    bevel("SM_Rails", 0.005, 3)
    bevel("SM_Apron", 0.004, 3)
    build_sights()
    build_environment()
    build_cameras()
    op("render", path=f"renders/base-{'classic' if classic else 'modern'}.png",
       resolution_x=1200, resolution_y=900, engine="eevee",
       samples=64, camera="CAM-hero")
    save("table_classic.blend" if classic else "table_modern.blend")
    return {"schema_version": "1.0.0", "ops": OPS.copy(),
            "notes": "classic base" if classic else "modern base"}


# --------------------------------------------------------------------------- #
# Themes
# --------------------------------------------------------------------------- #
def theme_plan(key: str, classic: bool, render_extra: bool) -> dict:
    reset()
    theme = THEMES[key]
    material("SM_Rails", "MAT_Wood", theme["wood"], theme["wood_rough"], theme["wood_metal"])
    material("SM_Cloth", "MAT_Cloth", theme["cloth"], 0.9)
    material("SM_Cushions", "MAT_Cushion", theme["cloth"], 0.92)
    material("SM_Slate", "MAT_Slate", theme["slate"], 0.86)
    material("SM_Facings", "MAT_Facing", theme["facing"], 0.62)
    material("SM_Sights", "MAT_Sight", theme["sight"], theme["sight_rough"],
             theme["sight_metal"])
    material("SM_PocketWells", "MAT_Pocket", theme["pocket"], theme["pocket_rough"],
             theme["pocket_metal"])
    if classic:
        material("SM_Medallion", "MAT_Brass", theme["brass"], theme["brass_rough"],
                 theme["brass_metal"])
    material("FLOOR", "MAT_Floor", theme["floor"], theme["floor_rough"])
    material("BACKDROP", "MAT_Backdrop", theme["backdrop"], 0.85)

    op("delete", object="LGT-auto")
    for suffix, kind, loc, energy, color in (
        ("KEY", "sun", (2.8, -2.8, 5.5), theme["key_energy"], theme["key_color"]),
        ("FILL", "area", (-2.8, 2.6, 2.6), theme["fill_energy"], theme["fill_color"]),
        ("RIM", "area", (2.6, 2.8, 2.6), theme["rim_energy"], theme["rim_color"]),
    ):
        op("light", kind=kind, name=suffix, location=list(loc),
           energy=energy, color=linear3(color))

    op("render", path=f"renders/theme-{key}-hero.png",
       resolution_x=1600, resolution_y=1200, engine="eevee",
       samples=128, camera="CAM-hero")
    if render_extra:
        op("render", path=f"renders/theme-{key}-top.png",
           resolution_x=1200, resolution_y=1200, engine="eevee",
           samples=128, camera="CAM-top")
        op("render", path=f"renders/theme-{key}-detail.png",
           resolution_x=1200, resolution_y=900, engine="eevee",
           samples=128, camera="CAM-pocket")
    save(f"theme_{key}.blend")
    return {"schema_version": "1.0.0", "ops": OPS.copy(),
            "notes": f"theme {key} on {'classic' if classic else 'modern'} base"}


THEMES = {
    "a": {
        "wood": "#4A2C1B", "wood_rough": 0.40, "wood_metal": 0.0,
        "cloth": "#0E5A38", "slate": "#1F2225", "facing": "#2B1A12",
        "sight": "#E8DCC0", "sight_rough": 0.25, "sight_metal": 0.2,
        "pocket": "#B08D57", "pocket_rough": 0.33, "pocket_metal": 0.85,
        "brass": "#B08D57", "brass_rough": 0.28, "brass_metal": 0.9,
        "floor": "#0A0A0B", "floor_rough": 0.6, "backdrop": "#08090A",
        "key_color": "#FFC98A", "key_energy": 5.2,
        "fill_color": "#7FA8C9", "fill_energy": 380.0,
        "rim_color": "#FFE2B0", "rim_energy": 480.0,
    },
    "b": {
        "wood": "#3C454C", "wood_rough": 0.32, "wood_metal": 0.85,
        "cloth": "#0B5F6B", "slate": "#16191C", "facing": "#0D1114",
        "sight": "#C9D1D6", "sight_rough": 0.2, "sight_metal": 0.9,
        "pocket": "#2A2F34", "pocket_rough": 0.35, "pocket_metal": 0.8,
        "brass": "#2A2F34", "brass_rough": 0.35, "brass_metal": 0.8,
        "floor": "#070809", "floor_rough": 0.55, "backdrop": "#050607",
        "key_color": "#F2F7FF", "key_energy": 4.5,
        "fill_color": "#5D7C92", "fill_energy": 300.0,
        "rim_color": "#35D0E8", "rim_energy": 500.0,
    },
    "c": {
        "wood": "#2A2C2D", "wood_rough": 0.45, "wood_metal": 0.7,
        "cloth": "#4A1A1F", "slate": "#202225", "facing": "#3A2418",
        "sight": "#C98A4A", "sight_rough": 0.35, "sight_metal": 0.85,
        "pocket": "#8C4A2F", "pocket_rough": 0.65, "pocket_metal": 0.35,
        "brass": "#B05A33", "brass_rough": 0.4, "brass_metal": 0.6,
        "floor": "#0B0908", "floor_rough": 0.65, "backdrop": "#080706",
        "key_color": "#FFB46A", "key_energy": 4.8,
        "fill_color": "#7FB4C9", "fill_energy": 280.0,
        "rim_color": "#A9D3E0", "rim_energy": 420.0,
    },
    "d": {
        "wood": "#D9D8D3", "wood_rough": 0.30, "wood_metal": 0.1,
        "cloth": "#0B4FA8", "slate": "#1A1D20", "facing": "#0A0E12",
        "sight": "#2BD9FF", "sight_rough": 0.25, "sight_metal": 0.4,
        "pocket": "#A7ADB2", "pocket_rough": 0.3, "pocket_metal": 0.85,
        "brass": "#A7ADB2", "brass_rough": 0.3, "brass_metal": 0.85,
        "floor": "#8A8D90", "floor_rough": 0.35, "backdrop": "#C7CACE",
        "key_color": "#FFFFFF", "key_energy": 4.5,
        "fill_color": "#9BC7E6", "fill_energy": 320.0,
        "rim_color": "#6FE3FF", "rim_energy": 450.0,
    },
}


def main() -> None:
    outputs = {
        "remodel_classic.json": base_plan(True),
        "remodel_modern.json": base_plan(False),
        "theme_a.json": theme_plan("a", True, True),
        "theme_b.json": theme_plan("b", False, False),
        "theme_c.json": theme_plan("c", True, False),
        "theme_d.json": theme_plan("d", False, False),
    }
    for name, plan in outputs.items():
        path = OUT / name
        path.write_text(json.dumps(plan, indent=2) + "\n")
        print(f"{name}: {len(plan['ops'])} ops")


if __name__ == "__main__":
    main()
