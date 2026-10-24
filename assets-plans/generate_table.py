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
CORNER_RAIL_R = 0.072          # hole through the rail wood
CORNER_MOUTH = 0.116           # diagonal between cushion tips
CORNER_TIP = CORNER_MOUTH / math.sqrt(2.0)   # setback along each rail

SIDE_RAIL_R = 0.074
SIDE_MOUTH = 0.127
SIDE_TIP = SIDE_MOUTH / 2.0

# The cup overlaps the rail hole, so the rail cut never shows a ring gap.
WELL_OUTER = 0.075
WELL_INNER = 0.068
SIDE_WELL_OUTER = 0.077
SIDE_WELL_INNER = 0.070
WELL_BOTTOM = 0.585

# The mouth stays on the cushion nose; the hole moves this far into the rail.
# The cloth keeps a green shelf between the mouth and the hole, so the hole is
# fully inside the wood instead of tangent to (and visually biting) the green.
SHELF = 0.025


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


CLOTH_THICKNESS = 0.004
SLATE_TOP = CLOTH_TOP - CLOTH_THICKNESS
CLOTH_Z = (CLOTH_TOP + SLATE_TOP) / 2.0
SLATE_Z = (SLATE_TOP + SLATE_BOTTOM) / 2.0


def hole_center(x: float, y: float, corner: bool) -> tuple[float, float]:
    """Visible pocket centre, one shelf behind the mouth line.

    For a corner the mouth line is CORNER_MOUTH / 2 inside the playing corner,
    so the centre only has to move (radius + shelf - mouth / 2) along the
    diagonal.  Measuring that distance from the corner instead pushed the hole
    outwards and left an 83 mm wood shelf between the mouth and the cup.
    """
    if corner:
        sx = 1.0 if x > 0 else -1.0
        sy = 1.0 if y > 0 else -1.0
        offset = ((WELL_INNER + SHELF) - CORNER_MOUTH / 2.0) / math.sqrt(2.0)
        return x + sx * offset, y + sy * offset
    sy = 1.0 if y > 0 else -1.0
    return 0.0, y + sy * (SIDE_WELL_INNER + SHELF)


# --------------------------------------------------------------------------- #
# 1. Cloth: the green stops at the mouth line, so the pocket opening sits
#    behind the cushion noses, never over the playing surface.
# --------------------------------------------------------------------------- #
def build_cloth() -> None:
    cube("SM_Cloth", (0.0, 0.0, CLOTH_Z),
         (HALF_L * 2.0, HALF_W * 2.0, CLOTH_THICKNESS))
    cube("SM_Slate", (0.0, 0.0, SLATE_Z),
         (HALF_L * 2.0, HALF_W * 2.0, SLATE_TOP - SLATE_BOTTOM))
    for name, x, y, corner in POCKETS:
        hx, hy = hole_center(x, y, corner)
        radius = (WELL_INNER if corner else SIDE_WELL_INNER) + 0.001
        cutter = cylinder(f"CUT_CLOTH_{name}", (hx, hy, SLATE_Z),
                          radius * 2.0, CLOTH_TOP + 0.01 - (SLATE_BOTTOM - 0.01))
        op("boolean", object="SM_Cloth", operand=cutter,
           operation="difference", apply=True)
        op("boolean", object="SM_Slate", operand=cutter,
           operation="difference", apply=True)
        op("delete", object=cutter)
    material("SM_Cloth", "MAT_Cloth", (0.010, 0.140, 0.058), 0.94)
    material("SM_Slate", "MAT_Slate", (0.030, 0.032, 0.034), 0.88)


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


def build_facings() -> None:
    """Dark rubber caps on the cushion ends so the jaws read as facings."""
    z_mid = (CLOTH_TOP + CUSHION_TOP) / 2.0
    height = CUSHION_TOP - CLOTH_TOP
    caps = []
    for sign in (1.0, -1.0):
        y = sign * (HALF_W + CUSHION_DEPTH / 2.0)
        for x_end in (-HALF_L + CORNER_TIP, -SIDE_TIP, SIDE_TIP, HALF_L - CORNER_TIP):
            is_corner = abs(x_end) > SIDE_TIP + 0.001
            inward = -1.0 if is_corner else 1.0
            inward *= 1.0 if x_end > 0 else -1.0
            tag = f"{'T' if sign > 0 else 'B'}{'L' if x_end < 0 else 'R'}{abs(x_end):.3f}"
            caps.append(cube(
                f"FACING_{tag}",
                (x_end + inward * 0.009, y, z_mid),
                (0.022, CUSHION_DEPTH + 0.004, height + 0.001),
            ))
    short = HALF_W - CORNER_TIP
    for sign in (1.0, -1.0):
        x = sign * (HALF_L + CUSHION_DEPTH / 2.0)
        for z_end in (-short, short):
            inward = -1.0 if z_end > 0 else 1.0
            tag = f"{'R' if sign > 0 else 'L'}{'F' if z_end > 0 else 'B'}"
            caps.append(cube(
                f"FACING_{tag}",
                (x, z_end + inward * 0.009, z_mid),
                (CUSHION_DEPTH + 0.004, 0.022, height + 0.001),
            ))
    join(caps, "SM_Facings")
    material("SM_Facings", "MAT_Facing", (0.020, 0.017, 0.014), 0.92)


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
            hx, hy = hole_center(x, y, corner)
            radius = CORNER_RAIL_R if corner else SIDE_RAIL_R
            hole(rail, f"CUT_RAIL_{name}", hx, hy, radius,
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
    bevel("SM_Rails", 0.002, 3)
    material("SM_Rails", "MAT_Wood", (0.170, 0.072, 0.034), 0.48)


# --------------------------------------------------------------------------- #
# 4. Pockets: one liner cup per pocket, clipped to the pocket side.
# --------------------------------------------------------------------------- #
def build_pockets() -> None:
    wells = []
    for name, x, y, corner in POCKETS:
        hx, hy = hole_center(x, y, corner)
        outer = WELL_OUTER if corner else SIDE_WELL_OUTER
        inner = WELL_INNER if corner else SIDE_WELL_INNER
        well_top = RAIL_TOP
        well = cylinder(f"WELL_{name}", (hx, hy, (WELL_BOTTOM + well_top) / 2.0),
                         outer * 2.0, well_top - WELL_BOTTOM)
        cylinder(f"WELLCUT_{name}", (hx, hy, (WELL_BOTTOM + 0.025 + well_top + 0.01) / 2.0),
                 inner * 2.0, well_top + 0.01 - (WELL_BOTTOM + 0.025))
        cut(well, f"WELLCUT_{name}")

        # Only the half of the rim facing the table is lowered: the ball rolls
        # over it into a full circular hole, while the outer half keeps the
        # dark casting at rail height.
        if corner:
            sx = 1.0 if x > 0 else -1.0
            sy = 1.0 if y > 0 else -1.0
            ox, oy = sx / math.sqrt(2.0), sy / math.sqrt(2.0)
            angle = math.atan2(sy, sx)
            cx = hx - ox * 0.15
            cy = hy - oy * 0.15
            upper = cube(
                f"CUT_WELLTOP_{name}", (cx, cy, (SLATE_TOP + 0.810) / 2.0),
                (0.30, outer * 2.0 + 0.01, 0.810 - SLATE_TOP),
                rotation=(0.0, 0.0, angle),
            )
        else:
            sy = 1.0 if y > 0 else -1.0
            upper = cube(
                f"CUT_WELLTOP_{name}", (0.0, hy - sy * 0.15, (SLATE_TOP + 0.810) / 2.0),
                (0.30, 0.30, 0.810 - SLATE_TOP),
            )
        cut(well, upper)
        wells.append(well)

    join(wells, "SM_PocketWells")
    material("SM_PocketWells", "MAT_Pocket", (0.008, 0.007, 0.006), 0.94)


# --------------------------------------------------------------------------- #
# 5. Apron, legs and rail sights
# --------------------------------------------------------------------------- #
def build_apron_and_legs() -> None:
    # Closed four-sided frame, not a solid box.  The top tucks 3 mm into the
    # rail; the only cuts are the six pocket cups.
    apron_top = RAIL_BOTTOM + 0.003
    z_mid = (APRON_BOTTOM + apron_top) / 2.0
    height = apron_top - APRON_BOTTOM
    cube("SM_Apron", (0.0, 0.0, z_mid),
         (FRAME_HALF_L * 2.0 - 0.006, FRAME_HALF_W * 2.0 - 0.006, height))
    cube("APRON_INNER", (0.0, 0.0, z_mid),
         (2.660, 1.390, height + 0.04))
    op("boolean", object="SM_Apron", operand="APRON_INNER",
       operation="difference", apply=True)
    op("delete", object="APRON_INNER")
    for name, x, y, corner in POCKETS:
        hx, hy = hole_center(x, y, corner)
        radius = (WELL_OUTER if corner else SIDE_WELL_OUTER) - 0.002
        hole("SM_Apron", f"CUT_APRON_{name}", hx, hy, radius,
             APRON_BOTTOM - 0.01, apron_top + 0.01)
    bevel("SM_Apron", 0.003, 2)
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
    op("light", kind="sun", name="LGT-key", location=(2.5, -2.5, 6.0), energy=4.0)
    op("light", kind="area", name="LGT-fill", location=(-2.5, 2.0, 3.0), energy=400.0)
    op("render", path="renders/preview.png", resolution_x=1100, resolution_y=850,
       engine="eevee", samples=64, camera="CAM-main")
    op("render", path="renders/preview_top.png", resolution_x=1000, resolution_y=1000,
       engine="eevee", samples=64, camera="CAM-top")
    op("export", path="artifacts/table.glb", format="glb")
    op("save", path="output.blend")


def main() -> None:
    op("clear")
    build_cloth()
    build_cushions()
    build_facings()
    build_rails()
    build_pockets()
    build_apron_and_legs()
    build_sights()
    build_scene()

    plan = {
        "schema_version": "1.0.0",
        "notes": (
            "Regulation 9 ft 8-ball table, 2.54 x 1.27 m playing surface. "
            "The pocket hole is a full circle set 25 mm behind the mouth line "
            "inside the rail; the cloth keeps the shelf green, so the hole never "
            "bites the playing surface. Cushion tips are set back for 116 mm "
            "corner and 127 mm side mouths, with dark facings and a lined cup. "
            "Generated by assets-plans/generate_table.py."
        ),
        "ops": OPS,
    }
    out = Path(__file__).with_name("table.json")
    out.write_text(json.dumps(plan, indent=2) + "\n")
    print(f"wrote {out} ({len(OPS)} ops)")


if __name__ == "__main__":
    main()
