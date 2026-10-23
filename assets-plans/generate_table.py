#!/usr/bin/env python3
"""Generate assets-plans/table.json for the sinuca-bluetooth table.

Every dimension is in metres and follows WPA/BCA equipment specs for a 9 ft
table: 2.54 x 1.27 m playing surface, 57.15 mm balls, corner pocket mouths of
114-117 mm, side pocket mouths of 127-130 mm and a cushion nose at 63.5% of
the ball diameter.  The model is built with the typed forge operations only.

Blender axes: X = length, Y = width, Z = up.  The exporter rotates this to the
Y-up frame consumed by GameRenderer, so Table.kt mirrors these numbers.
"""

from __future__ import annotations

import json
import math
from pathlib import Path

# --------------------------------------------------------------------------- #
# Reference dimensions (metres)
# --------------------------------------------------------------------------- #
HALF_L = 1.27                 # half of the 2.54 m playing length
HALF_W = 0.635                # half of the 1.27 m playing width
BALL_R = 0.028575             # 57.15 mm ball

CLOTH_TOP = 0.75
SLATE_BOTTOM = 0.70           # cloth + slate slab
RAIL_TOP = 0.800
RAIL_BOTTOM = 0.735
APRON_BOTTOM = 0.60
RAIL_WIDTH = 0.195            # from cushion line to outer frame
FRAME_HALF_L = HALF_L + RAIL_WIDTH     # 1.465
FRAME_HALF_W = HALF_W + RAIL_WIDTH     # 0.830

CUSHION_DEPTH = 0.048         # cushion body behind the nose line
CUSHION_TOP = CLOTH_TOP + 0.036        # 63.5% of the ball diameter

# Pocket geometry: the cloth hole is the slate opening, the rail hole is the
# casting and the mouth is the gap left between cushion tips.
CORNER_HOLE_R = 0.069          # 1 mm larger than the cup wall (avoids z-fighting)
CORNER_RAIL_R = 0.080          # casting opening in the wood
CORNER_MOUTH = 0.116           # diagonal between cushion tips
CORNER_TIP = CORNER_MOUTH / math.sqrt(2.0)   # setback along each rail

SIDE_HOLE_R = 0.071
SIDE_RAIL_R = 0.082
SIDE_MOUTH = 0.127
SIDE_TIP = SIDE_MOUTH / 2.0

WELL_OUTER = CORNER_RAIL_R
WELL_INNER = 0.068
SIDE_WELL_OUTER = SIDE_RAIL_R
SIDE_WELL_INNER = 0.070
WELL_BOTTOM = 0.585

CASTING_LIP = 0.014

LEG_TOP = APRON_BOTTOM
LEG_SIZE = 0.125
LEG_INSET_L = 1.33
LEG_INSET_W = 0.70

SIGHT_Z = RAIL_TOP + 0.0015

# Pockets: four corners plus the two long-rail side pockets.
POCKETS = [
    ("C0", HALF_L, HALF_W, True),
    ("C1", HALF_L, -HALF_W, True),
    ("C2", -HALF_L, HALF_W, True),
    ("C3", -HALF_L, -HALF_W, True),
    ("S0", 0.0, HALF_W, False),
    ("S1", 0.0, -HALF_W, False),
]

OPS: list[dict] = []


def op(op_type: str, **fields) -> None:
    OPS.append({"op": op_type, **fields})


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



def cut(target: str, cutter: str) -> None:
    op("boolean", object=target, operand=cutter, operation="difference", apply=True)
    op("delete", object=cutter)


def hole(target: str, name: str, x: float, y: float, radius: float,
         z0: float, z1: float) -> None:
    cylinder(name, (x, y, (z0 + z1) / 2.0), radius * 2.0, z1 - z0)
    cut(target, name)


def join(names: list[str], name: str) -> str:
    op("join", objects=names, name=name)
    return name


def bevel(obj: str, width: float, segments: int) -> None:
    op("modifier", object=obj, kind="bevel",
       params={"width": width, "segments": segments})
    op("apply_modifier", object=obj, kind="bevel")


def material(obj: str, name: str, color: tuple[float, float, float],
             roughness: float, metallic: float = 0.0) -> None:
    op("material", object=obj, name=name, color=[*color, 1.0],
       roughness=roughness, metallic=metallic)


# --------------------------------------------------------------------------- #
# 1. Cloth + slate with the six slate holes
# --------------------------------------------------------------------------- #
def build_cloth() -> None:
    cube("SM_Cloth", (0.0, 0.0, (CLOTH_TOP + SLATE_BOTTOM) / 2.0),
         (HALF_L * 2.0, HALF_W * 2.0, CLOTH_TOP - SLATE_BOTTOM))
    for name, x, y, corner in POCKETS:
        radius = CORNER_HOLE_R if corner else SIDE_HOLE_R
        hole("SM_Cloth", f"CUT_CLOTH_{name}", x, y, radius,
             SLATE_BOTTOM - 0.01, CLOTH_TOP + 0.01)
    material("SM_Cloth", "MAT_Cloth", (0.010, 0.140, 0.058), 0.94)


# --------------------------------------------------------------------------- #
# 2. Cushions: six segments, noses on the playing boundary, cut back at the
#    pocket mouths so the jaws have the correct mouth width.
# --------------------------------------------------------------------------- #
def build_cushions() -> None:
    span_l = HALF_L - CORNER_TIP
    span_w = HALF_W - CORNER_TIP
    long_len = span_l - SIDE_TIP
    long_center = (span_l + SIDE_TIP) / 2.0
    short_len = span_w * 2.0
    short_center = 0.0

    y_inner = HALF_W
    y_mid = HALF_W + CUSHION_DEPTH / 2.0
    x_inner = HALF_L
    x_mid = HALF_L + CUSHION_DEPTH / 2.0
    z_mid = (CLOTH_TOP + CUSHION_TOP) / 2.0
    height = CUSHION_TOP - CLOTH_TOP

    names = []
    for side, sign in (("TOP", 1.0), ("BOTTOM", -1.0)):
        for half in ("L", "R"):
            cx = -long_center if half == "L" else long_center
            names.append(cube(
                f"CUSH_{side}_{half}",
                (cx, sign * y_mid, z_mid),
                (long_len, CUSHION_DEPTH, height),
            ))
    for side, sign in (("RIGHT", 1.0), ("LEFT", -1.0)):
        names.append(cube(
            f"CUSH_{side}",
            (sign * x_mid, short_center, z_mid),
            (CUSHION_DEPTH, short_len, height),
        ))
    join(names, "SM_Cushions")
    bevel("SM_Cushions", 0.006, 3)
    material("SM_Cushions", "MAT_Cushion", (0.010, 0.120, 0.050), 0.90)


# --------------------------------------------------------------------------- #
# 3. Rail ring: full frame around the pockets, holes cut through the wood.
# --------------------------------------------------------------------------- #
def build_rails() -> None:
    rail_top_z = (RAIL_TOP + RAIL_BOTTOM) / 2.0
    rail_h = RAIL_TOP - RAIL_BOTTOM
    rail_w = FRAME_HALF_W - (HALF_W + CUSHION_DEPTH)
    rail_l = FRAME_HALF_L - (HALF_L + CUSHION_DEPTH)
    y_mid = HALF_W + CUSHION_DEPTH + rail_w / 2.0
    x_mid = HALF_L + CUSHION_DEPTH + rail_l / 2.0

    def rail_cut(rail: str, pocket_names: list[str]) -> None:
        for name in pocket_names:
            x, y, corner = next(
                (p[1], p[2], p[3]) for p in POCKETS if p[0] == name
            )
            radius = CORNER_RAIL_R if corner else SIDE_RAIL_R
            hole(rail, f"CUT_RAIL_{name}", x, y, radius,
                 RAIL_BOTTOM - 0.01, RAIL_TOP + 0.01)

    segments = []
    for side, sign, pockets in (
        ("TOP", 1.0, ["C0", "S0", "C2"]),
        ("BOTTOM", -1.0, ["C1", "S1", "C3"]),
    ):
        rail = cube(
            f"RAIL_{side}",
            (0.0, sign * y_mid, rail_top_z),
            (FRAME_HALF_L * 2.0, rail_w, rail_h),
        )
        rail_cut(rail, pockets)
        segments.append(rail)
    for side, sign, pockets in (
        ("RIGHT", 1.0, ["C0", "C1"]),
        ("LEFT", -1.0, ["C2", "C3"]),
    ):
        rail = cube(
            f"RAIL_{side}",
            (sign * x_mid, 0.0, rail_top_z),
            (rail_l, HALF_W * 2.0 + CUSHION_DEPTH * 2.0, rail_h),
        )
        rail_cut(rail, pockets)
        segments.append(rail)

    join(segments, "SM_Rails")
    bevel("SM_Rails", 0.002, 2)
    material("SM_Rails", "MAT_Wood", (0.170, 0.072, 0.034), 0.48)


# --------------------------------------------------------------------------- #
# 4. Pockets: one liner cup per pocket, through the rail and the apron.
# --------------------------------------------------------------------------- #
def build_pockets() -> None:
    wells = []
    castings = []
    for name, x, y, corner in POCKETS:
        outer = WELL_OUTER if corner else SIDE_WELL_OUTER
        inner = WELL_INNER if corner else SIDE_WELL_INNER
        well_top = RAIL_TOP
        well = cylinder(f"WELL_{name}", (x, y, (WELL_BOTTOM + well_top) / 2.0),
                         outer * 2.0, well_top - WELL_BOTTOM)
        cylinder(f"WELLCUT_{name}", (x, y, (WELL_BOTTOM + 0.025 + well_top + 0.01) / 2.0),
                 inner * 2.0, well_top + 0.01 - (WELL_BOTTOM + 0.025))
        cut(well, f"WELLCUT_{name}")
        wells.append(well)

        rail_r = CORNER_RAIL_R if corner else SIDE_RAIL_R
        casting = cylinder(f"CAST_{name}", (x, y, RAIL_TOP + 0.002),
                           (rail_r + CASTING_LIP) * 2.0, 0.003)
        cylinder(f"CASTCUT_{name}", (x, y, RAIL_TOP + 0.002),
                 rail_r * 2.0, 0.02)
        cut(casting, f"CASTCUT_{name}")
        castings.append(casting)

    join(wells, "SM_PocketWells")
    join(castings, "SM_PocketCastings")
    material("SM_PocketWells", "MAT_Pocket", (0.008, 0.007, 0.006), 0.94)
    material("SM_PocketCastings", "MAT_Casting", (0.020, 0.016, 0.012), 0.84)


# --------------------------------------------------------------------------- #
# 5. Apron, legs and rail sights
# --------------------------------------------------------------------------- #
def build_apron_and_legs() -> None:
    apron_top = RAIL_BOTTOM - 0.001
    z_mid = (APRON_BOTTOM + apron_top) / 2.0
    cube("SM_Apron", (0.0, 0.0, z_mid),
         (FRAME_HALF_L * 2.0, FRAME_HALF_W * 2.0, apron_top - APRON_BOTTOM))
    for name, x, y, corner in POCKETS:
        radius = (WELL_OUTER if corner else SIDE_WELL_OUTER) + 0.002
        hole("SM_Apron", f"CUT_APRON_{name}", x, y, radius,
             APRON_BOTTOM - 0.01, apron_top + 0.01)
    bevel("SM_Apron", 0.0025, 2)
    material("SM_Apron", "MAT_Wood", (0.170, 0.072, 0.034), 0.48)

    legs = []
    for sx in (-1.0, 1.0):
        for sy in (-1.0, 1.0):
            tag = f"{'R' if sx > 0 else 'L'}{'F' if sy > 0 else 'B'}"
            legs.append(cube(
                f"LEG_{tag}",
                (sx * LEG_INSET_L, sy * LEG_INSET_W, LEG_TOP / 2.0),
                (LEG_SIZE, LEG_SIZE, LEG_TOP),
            ))
            legs.append(cube(
                f"FOOT_{tag}",
                (sx * LEG_INSET_L, sy * LEG_INSET_W, 0.03),
                (LEG_SIZE + 0.03, LEG_SIZE + 0.03, 0.06),
            ))
    join(legs, "SM_Legs")
    bevel("SM_Legs", 0.003, 2)
    material("SM_Legs", "MAT_Wood", (0.155, 0.064, 0.030), 0.46)


def build_sights() -> None:
    sights = []
    step = HALF_L / 4.0
    for index in range(-3, 4):
        x = index * step
        if abs(x) < 1e-6:
            continue
        for sign in (-1.0, 1.0):
            sights.append(cube(
                f"SIGHT_L{index}_{'T' if sign > 0 else 'B'}",
                (x, sign * (HALF_W + CUSHION_DEPTH + 0.075), SIGHT_Z),
                (0.014, 0.014, 0.003),
            ))
    for index in (-1, 0, 1):
        y = index * HALF_W / 2.0
        for sign in (-1.0, 1.0):
            sights.append(cube(
                f"SIGHT_S{index}_{'R' if sign > 0 else 'L'}",
                (sign * (HALF_L + CUSHION_DEPTH + 0.075), y, SIGHT_Z),
                (0.014, 0.014, 0.003),
            ))
    join(sights, "SM_Sights")
    material("SM_Sights", "MAT_Sight", (0.88, 0.84, 0.68), 0.35)


# --------------------------------------------------------------------------- #
# 6. Cameras, lights, renders, export
# --------------------------------------------------------------------------- #
def build_scene() -> None:
    op("camera", name="CAM-main", location=(3.4, -3.7, 2.5),
       look_at=(0.0, 0.0, 0.62), lens=50)
    op("camera", name="CAM-top", location=(0.0, -0.01, 5.8),
       look_at=(0.0, 0.0, 0.60), lens=50)
    op("camera", name="CAM-pocket", location=(2.35, -2.25, 1.60),
       look_at=(1.08, -0.56, 0.76), lens=75)
    op("light", kind="sun", name="LGT-key", location=(2.5, -2.5, 6.0), energy=4.0)
    op("light", kind="area", name="LGT-fill", location=(-2.5, 2.0, 3.0), energy=400.0)
    op("render", path="renders/preview.png", resolution_x=1100, resolution_y=850,
       engine="eevee", samples=64, camera="CAM-main")
    op("render", path="renders/preview_top.png", resolution_x=1000, resolution_y=1000,
       engine="eevee", samples=64, camera="CAM-top")
    op("render", path="renders/preview_pocket.png", resolution_x=1000, resolution_y=800,
       engine="eevee", samples=64, camera="CAM-pocket")
    op("export", path="artifacts/table.glb", format="glb")
    op("save", path="output.blend")


def main() -> None:
    op("clear")
    build_cloth()
    build_cushions()
    build_rails()
    build_pockets()
    build_apron_and_legs()
    build_sights()
    build_scene()

    plan = {
        "schema_version": "1.0.0",
        "notes": (
            "Regulation 9 ft 8-ball table, 2.54 x 1.27 m playing surface. "
            "Pockets are castings cut into the rail ring with the wood wrapping "
            "around them; cushion tips set back for 116 mm corner and 127 mm "
            "side mouths; wells and nets contained inside the frame. "
            "Generated by assets-plans/generate_table.py."
        ),
        "ops": OPS,
    }
    out = Path(__file__).with_name("table.json")
    out.write_text(json.dumps(plan, indent=2) + "\n")
    print(f"wrote {out} ({len(OPS)} ops)")


if __name__ == "__main__":
    main()
