"""Rebuild the table frame, rails, cloth and pocket cups.

The old apron was an open frame on the two long sides only, with a 30 mm gap
under the rail, and the pocket cups were 1 mm smaller than the rail holes while
the corner centres were measured from the playing corner instead of from the
mouth line.  This script rebuilds all of that as one coherent shell:

* apron is a closed four-sided frame (outer rectangle minus inner rectangle),
  with circular notches only around the six pocket cups;
* short apron panels exist, each with a rounded corner notch for the corner cup;
* the apron top tucks 3 mm under the rail (no gap under the rail);
* rails are rebuilt with the holes on the corrected pocket centres;
* cups overlap the rail hole and the apron notch, have a closed bottom and a
  hidden shelf plate under the cloth, so nothing from the table interior is
  visible through a pocket;
* cloth and slate are cut only at the six pockets; the 25 mm shelf is measured
  from the mouth line, so the cloth keeps its full shelf;
* a low side camera reproduces the old leak.

Run inside Blender (Z up) with a theme .blend loaded:
    Blender -b theme_a.blend --python assets-plans/sculpt_apron.py -- out.blend
"""

from __future__ import annotations

import math
import sys

import bpy
from mathutils import Vector

D = bpy.data
SQRT2 = math.sqrt(2.0)

# Playing surface and frame (metres, Blender Z up).
HALF_L = 1.27
HALF_W = 0.635
CUSHION_DEPTH = 0.048
CLOTH_TOP = 0.750
CLOTH_THICK = 0.004
SLATE_TOP = CLOTH_TOP - CLOTH_THICK  # 0.746
SLATE_BOTTOM = 0.700
RAIL_TOP = 0.800
RAIL_BOTTOM = 0.735
APRON_TOP = 0.738          # 3 mm into the rail: no gap under the rail
APRON_BOTTOM = 0.560
FRAME_HALF_L = 1.465
FRAME_HALF_W = 0.830
APRON_OUT_L = 1.462         # 3 mm inset from the rail, as a real apron
APRON_OUT_W = 0.827
APRON_IN_L = 1.330
APRON_IN_W = 0.695

CORNER_MOUTH = 0.116
SIDE_MOUTH = 0.127
CORNER_TIP = CORNER_MOUTH / SQRT2
SIDE_TIP = SIDE_MOUTH / 2.0

# Pocket radii.  The cup is larger than the rail hole so it always covers the
# cut edge; the cloth hole is 1 mm larger than the inner radius so the cloth
# overhangs the liner instead of leaving a slit.
CORNER_R_IN = 0.068
SIDE_R_IN = 0.070
# The rail hole is only a little larger than the liner bore: the visible metal
# reveal is a thin ring, not a wide shelf.  The cup is still larger than the
# rail hole, so it covers the cut edge.
CORNER_R_RAIL = 0.072
SIDE_R_RAIL = 0.074
CORNER_R_CUP = 0.075
SIDE_R_CUP = 0.077
CORNER_R_RING = 0.074
SIDE_R_RING = 0.076
CORNER_R_RIM = 0.078
SIDE_R_RIM = 0.080
POCKET_SHELF = 0.025
CUP_BOTTOM = 0.540
CUP_BORE_BOTTOM = CUP_BOTTOM + 0.019
CUP_TOP = 0.7965
RING_BOTTOM = CUP_TOP - 0.001
RING_TOP = RAIL_TOP - 0.0005
RIM_BOTTOM = 0.738
RIM_TOP = 0.7455

# The mouth line of a corner pocket is CORNER_MOUTH / 2 from the playing
# corner along the diagonal.  The hole edge must be POCKET_SHELF behind that
# line, so the centre is (r + shelf - mouth/2) / sqrt(2) beyond the corner.
CORNER_OFF = ((CORNER_R_IN + POCKET_SHELF) - CORNER_MOUTH / 2.0) / SQRT2
SIDE_OFF = SIDE_R_IN + POCKET_SHELF

POCKETS = [
    ("C0", HALF_L + CORNER_OFF, HALF_W + CORNER_OFF, True),
    ("C1", HALF_L + CORNER_OFF, -HALF_W - CORNER_OFF, True),
    ("C2", -HALF_L - CORNER_OFF, HALF_W + CORNER_OFF, True),
    ("C3", -HALF_L - CORNER_OFF, -HALF_W - CORNER_OFF, True),
    ("S0", 0.0, HALF_W + SIDE_OFF, False),
    ("S1", 0.0, -HALF_W - SIDE_OFF, False),
]

REBUILT = (
    "SM_Apron",
    "SM_ApronReveal",
    "SM_PocketWells",
    "SM_PocketRings",
    "SM_PocketJaws",
    "SM_Rails",
    "SM_Cloth",
    "SM_Slate",
)


def activate(obj: bpy.types.Object) -> None:
    bpy.ops.object.select_all(action="DESELECT")
    obj.select_set(True)
    bpy.context.view_layer.objects.active = obj


def bake(obj: bpy.types.Object) -> None:
    activate(obj)
    bpy.ops.object.transform_apply(location=True, rotation=True, scale=True)


def material(names: tuple[str, ...], fallback) -> bpy.types.Material:
    for name in names:
        found = D.materials.get(name)
        if found:
            return found
    created = D.materials.new(names[-1])
    created.diffuse_color = (*fallback, 1.0)
    return created


def cube(name, location, size, rotation=(0.0, 0.0, 0.0)):
    bpy.ops.mesh.primitive_cube_add(size=1.0, location=location, rotation=rotation)
    obj = bpy.context.active_object
    obj.name = name
    obj.scale = size
    bake(obj)
    return obj


def cylinder(name, x, y, z0, z1, radius, vertices=64):
    bpy.ops.mesh.primitive_cylinder_add(
        vertices=vertices,
        radius=radius,
        depth=z1 - z0,
        location=(x, y, (z0 + z1) * 0.5),
    )
    obj = bpy.context.active_object
    obj.name = name
    bake(obj)
    return obj


def boolean(target, operand) -> None:
    modifier = target.modifiers.new("Boolean", "BOOLEAN")
    modifier.operation = "DIFFERENCE"
    modifier.solver = "EXACT"
    modifier.object = operand
    activate(target)
    bpy.ops.object.modifier_apply(modifier=modifier.name)
    D.objects.remove(operand, do_unlink=True)


def bevel(obj, width, segments) -> None:
    modifier = obj.modifiers.new("Bevel", "BEVEL")
    modifier.width = width
    modifier.segments = segments
    modifier.limit_method = "ANGLE"
    modifier.angle_limit = math.radians(35.0)
    activate(obj)
    bpy.ops.object.modifier_apply(modifier=modifier.name)


def join(objects, name):
    bpy.ops.object.select_all(action="DESELECT")
    for obj in objects:
        obj.select_set(True)
    bpy.context.view_layer.objects.active = objects[0]
    bpy.ops.object.join()
    joined = bpy.context.active_object
    joined.name = name
    bake(joined)
    return joined


def first_material(obj):
    if obj and obj.type == "MESH" and obj.data.materials:
        for slot in obj.data.materials:
            if slot:
                return slot
    return None


def assign_material(obj, mat) -> None:
    """Put the material in slot 0 and point every face at it.

    Cubes/cylinders created without a material leave an empty slot 0; appending
    later puts the real material in slot 1 while the faces still use slot 0 and
    render as default grey.  Clear the slots first.
    """
    obj.data.materials.clear()
    obj.data.materials.append(mat)
    for polygon in obj.data.polygons:
        polygon.material_index = 0


def remove_old() -> dict[str, bpy.types.Material | None]:
    saved = {name: first_material(D.objects.get(name)) for name in REBUILT}
    for name in REBUILT:
        obj = D.objects.get(name)
        if obj:
            D.objects.remove(obj, do_unlink=True)
    return saved


def build_cloth_and_slate(wood_materials) -> None:
    cloth_mat = wood_materials.get("SM_Cloth") or material(("MAT_Cloth",), (0.01, 0.14, 0.058))
    slate_mat = wood_materials.get("SM_Slate") or material(("MAT_Slate",), (0.03, 0.032, 0.034))

    cloth = cube(
        "SM_Cloth",
        (0.0, 0.0, (CLOTH_TOP + SLATE_TOP) * 0.5),
        (HALF_L * 2.0, HALF_W * 2.0, CLOTH_THICK),
    )
    slate = cube(
        "SM_Slate",
        (0.0, 0.0, (SLATE_TOP + SLATE_BOTTOM) * 0.5),
        (HALF_L * 2.0, HALF_W * 2.0, SLATE_TOP - SLATE_BOTTOM),
    )

    for name, x, y, corner in POCKETS:
        radius = (CORNER_R_IN if corner else SIDE_R_IN) + 0.001
        cutter = cylinder(f"CUT_CLOTH_{name}", x, y, SLATE_BOTTOM - 0.02, CLOTH_TOP + 0.02, radius)
        boolean(cloth, cutter)
        cutter = cylinder(f"CUT_SLATE_{name}", x, y, SLATE_BOTTOM - 0.02, SLATE_TOP + 0.02, radius)
        boolean(slate, cutter)

    assign_material(cloth, cloth_mat)
    assign_material(slate, slate_mat)


def build_rails(rail_mat) -> None:
    rail_w = FRAME_HALF_W - (HALF_W + CUSHION_DEPTH)
    rail_l = FRAME_HALF_L - (HALF_L + CUSHION_DEPTH)
    y_mid = HALF_W + CUSHION_DEPTH + rail_w * 0.5
    x_mid = HALF_L + CUSHION_DEPTH + rail_l * 0.5
    z_mid = (RAIL_TOP + RAIL_BOTTOM) * 0.5
    height = RAIL_TOP - RAIL_BOTTOM

    def cut(block, pocket_names):
        for pocket_name in pocket_names:
            _, x, y, corner = next(p for p in POCKETS if p[0] == pocket_name)
            radius = CORNER_R_RAIL if corner else SIDE_R_RAIL
            cutter = cylinder(
                f"CUT_RAIL_{pocket_name}",
                x,
                y,
                RAIL_BOTTOM - 0.01,
                RAIL_TOP + 0.01,
                radius,
            )
            boolean(block, cutter)
        return block

    blocks = []
    for sign, pockets in ((1.0, ("C0", "S0", "C2")), (-1.0, ("C1", "S1", "C3"))):
        block = cube(
            f"RAIL_LONG_{'T' if sign > 0 else 'B'}",
            (0.0, sign * y_mid, z_mid),
            (FRAME_HALF_L * 2.0, rail_w, height),
        )
        blocks.append(cut(block, pockets))
    for sign, pockets in ((1.0, ("C0", "C1")), (-1.0, ("C2", "C3"))):
        block = cube(
            f"RAIL_SHORT_{'R' if sign > 0 else 'L'}",
            (sign * x_mid, 0.0, z_mid),
            (rail_l, HALF_W * 2.0 + CUSHION_DEPTH * 2.0, height),
        )
        blocks.append(cut(block, pockets))

    rails = join(blocks, "SM_Rails")
    bevel(rails, 0.002, 3)
    assign_material(rails, rail_mat or material(("MAT_Wood",), (0.17, 0.072, 0.034)))


def build_apron(apron_mat) -> None:
    outer = cube(
        "SM_Apron",
        (0.0, 0.0, (APRON_TOP + APRON_BOTTOM) * 0.5),
        (APRON_OUT_L * 2.0, APRON_OUT_W * 2.0, APRON_TOP - APRON_BOTTOM),
    )
    inner = cube(
        "_APRON_INNER",
        (0.0, 0.0, (APRON_TOP + APRON_BOTTOM) * 0.5),
        (APRON_IN_L * 2.0, APRON_IN_W * 2.0, APRON_TOP - APRON_BOTTOM + 0.04),
    )
    boolean(outer, inner)

    # Only the six pockets cut the frame.  The notch radius is slightly
    # smaller than the cup so the two solids overlap instead of leaving a slit.
    for name, x, y, corner in POCKETS:
        radius = (CORNER_R_CUP if corner else SIDE_R_CUP) - 0.002
        cutter = cylinder(
            f"CUT_APRON_{name}",
            x,
            y,
            APRON_BOTTOM - 0.02,
            APRON_TOP + 0.02,
            radius,
        )
        boolean(outer, cutter)

    bevel(outer, 0.003, 2)
    assign_material(outer, apron_mat or material(("MAT_Wood",), (0.17, 0.072, 0.034)))


def build_reveal(reveal_mat) -> None:
    band = cube(
        "SM_ApronReveal",
        (0.0, 0.0, 0.7305),
        (APRON_OUT_L * 2.0 + 0.004, APRON_OUT_W * 2.0 + 0.004, 0.008),
    )
    inner = cube(
        "_REVEAL_INNER",
        (0.0, 0.0, 0.7305),
        (APRON_OUT_L * 2.0 - 0.016, APRON_OUT_W * 2.0 - 0.016, 0.020),
    )
    boolean(band, inner)
    bevel(band, 0.0012, 2)
    assign_material(
        band,
        reveal_mat
        or material(
            ("MAT_Brass", "MAT_Aluminum", "MAT_Titanium", "MAT_Sight", "MAT_Pocket"),
            (0.43, 0.26, 0.10),
        ),
    )


def liner_material():
    existing = D.materials.get("MAT_PocketLiner")
    if existing:
        return existing
    created = D.materials.new("MAT_PocketLiner")
    created.use_nodes = True
    node = created.node_tree.nodes.get("Principled BSDF")
    if node:
        node.inputs["Base Color"].default_value = (0.010, 0.009, 0.008, 1.0)
        node.inputs["Roughness"].default_value = 0.82
        node.inputs["Metallic"].default_value = 0.0
    created.diffuse_color = (0.010, 0.009, 0.008, 1.0)
    return created


def half_cutter(name, x, y, corner):
    if corner:
        sx = 1.0 if x > 0 else -1.0
        sy = 1.0 if y > 0 else -1.0
        ix, iy = -sx / SQRT2, -sy / SQRT2
    else:
        sy = 1.0 if y > 0 else -1.0
        ix, iy = 0.0, -sy
    angle = math.atan2(iy, ix)
    z0 = 0.7495
    z1 = CUP_TOP + 0.020
    return cube(
        name,
        (x + ix * 0.17, y + iy * 0.17, (z0 + z1) * 0.5),
        (0.36, 0.24, z1 - z0),
        rotation=(0.0, 0.0, angle),
    )


def build_cup(name, x, y, corner):
    r_in = CORNER_R_IN if corner else SIDE_R_IN
    r_cup = CORNER_R_CUP if corner else SIDE_R_CUP
    r_ring = CORNER_R_RING if corner else SIDE_R_RING
    r_rim = CORNER_R_RIM if corner else SIDE_R_RIM

    outer = cylinder(name, x, y, CUP_BOTTOM, CUP_TOP, r_cup, vertices=64)
    bore = cylinder(name + "_BORE", x, y, CUP_BORE_BOTTOM, CUP_TOP + 0.002, r_in, vertices=64)
    boolean(outer, bore)

    # Lower the table-facing half of the liner to just above the cloth.  The
    # other half stays at rail height and lines the rail hole.
    boolean(outer, half_cutter(name + "_HALF", x, y, corner))

    # Thin metal reveal around the top of the hole.  Its inner wall is 0.7 mm
    # outside the bore so it cannot fight with the liner wall; its bottom is
    # embedded 1 mm into the liner, so there are no coincident faces.
    ring = cylinder(name + "_RING", x, y, RING_BOTTOM, RING_TOP, r_ring, vertices=64)
    ring_bore = cylinder(
        name + "_RING_BORE",
        x,
        y,
        RING_BOTTOM - 0.002,
        RING_TOP + 0.002,
        r_in + 0.0007,
        vertices=64,
    )
    boolean(ring, ring_bore)
    boolean(ring, half_cutter(name + "_RING_HALF", x, y, corner))

    # Hidden plate under the cloth: closes the annular gap between the cloth
    # hole and the cup wall, and keeps slate/interior out of sight.
    plate = cylinder(name + "_RIM", x, y, RIM_BOTTOM, RIM_TOP, r_rim, vertices=64)
    plate_bore = cylinder(
        name + "_RIM_BORE", x, y, RIM_BOTTOM - 0.002, RIM_TOP + 0.002, r_in, vertices=64
    )
    boolean(plate, plate_bore)
    return outer, plate, ring


def build_pockets(pocket_mat) -> None:
    bodies = []
    rings = []
    for name, x, y, corner in POCKETS:
        outer, plate, ring = build_cup(f"WELL_{name}", x, y, corner)
        bodies.extend((outer, plate))
        rings.append(ring)
    wells = join(bodies, "SM_PocketWells")
    assign_material(wells, liner_material())
    ring_object = join(rings, "SM_PocketRings")
    assign_material(ring_object, pocket_mat or material(("MAT_Pocket",), (0.008, 0.007, 0.006)))


def build_jaws(facing_mat) -> None:
    """Dark jaw caps over the rail teeth beside each pocket.

    The corrected corner centre leaves a pointed rail tooth on each side of
    the mouth.  Each tooth gets a thin dark plate on the rail top and a thin
    dark strip on its vertical inner face; the cloth shelf and the pocket bore
    are untouched.
    """
    parts = []

    def cap(name, x0, x1, y0, y1, x, y, radius):
        """Top plate shaped like the rail tooth: box minus the pocket circle."""
        plate = cube(
            name,
            ((x0 + x1) * 0.5, (y0 + y1) * 0.5,
             (RAIL_TOP + 0.0005 + RAIL_TOP + 0.0020) * 0.5),
            (abs(x1 - x0), abs(y1 - y0), 0.0015),
        )
        cutter = cylinder(
            name + "_CUT", x, y, RAIL_TOP - 0.002, RAIL_TOP + 0.004, radius, vertices=96
        )
        boolean(plate, cutter)
        parts.append(plate)

    def face(name, x0, x1, y0, y1):
        parts.append(cube(
            name,
            ((x0 + x1) * 0.5, (y0 + y1) * 0.5,
             (RAIL_BOTTOM + RAIL_TOP + 0.0005) * 0.5),
            (abs(x1 - x0), abs(y1 - y0), RAIL_TOP + 0.0005 - RAIL_BOTTOM),
        ))

    for _, x, y, corner in POCKETS:
        sx = 1.0 if x > 0 else -1.0
        sy = 1.0 if y > 0 else -1.0
        y_inner = sy * (HALF_W + CUSHION_DEPTH)
        x_inner = sx * (HALF_L + CUSHION_DEPTH)

        if corner:
            # Long-rail tooth: from the cushion tip to the rail inner corner.
            x_tip = sx * (HALF_L - CORNER_TIP)
            dx = math.sqrt(max(CORNER_R_RAIL ** 2 - (y_inner - y) ** 2, 0.0))
            x_hole = x - sx * dx
            x0, x1 = sorted((x_tip, x_inner))
            y0, y1 = sorted((y_inner, y_inner + sy * 0.052))
            cap("JAW_LONG", x0, x1, y0, y1, x, y, CORNER_R_RAIL)
            face("JAW_LONG_FACE", min(x_tip, x_hole), max(x_tip, x_hole),
                 y_inner - sy * 0.001, y_inner + sy * 0.001)

            # Short-rail tooth.
            y_tip = sy * (HALF_W - CORNER_TIP)
            dy = math.sqrt(max(CORNER_R_RAIL ** 2 - (x_inner - x) ** 2, 0.0))
            y_hole = y - sy * dy
            x0, x1 = sorted((x_inner, x_inner + sx * 0.052))
            y0, y1 = sorted((y_tip, y_inner))
            cap("JAW_SHORT", x0, x1, y0, y1, x, y, CORNER_R_RAIL)
            face("JAW_SHORT_FACE", x_inner - sx * 0.001, x_inner + sx * 0.001,
                 min(y_tip, y_hole), max(y_tip, y_hole))
        else:
            # Side pocket: one cap spanning both teeth; the circle bite leaves
            # the two small rail slivers.
            x0, x1 = sorted((-SIDE_TIP - 0.011, SIDE_TIP + 0.011))
            y0, y1 = sorted((y_inner, y_inner + sy * 0.052))
            cap("JAW_SIDE", x0, x1, y0, y1, x, y, SIDE_R_RAIL)
            dx = math.sqrt(max(SIDE_R_RAIL ** 2 - (y_inner - y) ** 2, 0.0))
            for sign in (-1.0, 1.0):
                face(
                    f"JAW_SIDE_FACE_{sign:+.0f}",
                    min(sign * dx, sign * (SIDE_TIP + 0.011)),
                    max(sign * dx, sign * (SIDE_TIP + 0.011)),
                    y_inner - sy * 0.001, y_inner + sy * 0.001,
                )

    jaws = join(parts, "SM_PocketJaws")
    bevel(jaws, 0.0012, 2)
    assign_material(jaws, facing_mat or material(("MAT_Facing",), (0.024, 0.010, 0.006)))


def build_low_camera() -> None:
    old = D.objects.get("CAM-low")
    if old:
        D.objects.remove(old, do_unlink=True)
    data = D.cameras.new("CAM-low")
    camera = D.objects.new("CAM-low", data)
    bpy.context.collection.objects.link(camera)
    camera.location = (3.2, -3.4, 0.35)
    direction = Vector((0.0, 0.0, 0.62)) - camera.location
    camera.rotation_euler = direction.to_track_quat("-Z", "Y").to_euler()
    data.lens = 45.0


def write_stage(path: str, stage: str, source: str) -> None:
    """Record which pipeline stage produced this .blend and from what.

    The export step is only allowed to consume a file whose sidecar says it is
    the stage the caller expects; exporting a pre-sculpt file silently drops the
    pocket wells and rings.
    """
    import hashlib
    import json
    from pathlib import Path

    def digest(value: str) -> str | None:
        file_path = Path(value)
        if not file_path.is_file():
            return None
        hasher = hashlib.sha256()
        with file_path.open("rb") as handle:
            for block in iter(lambda: handle.read(1 << 20), b""):
                hasher.update(block)
        return hasher.hexdigest()

    info = {
        "stage": stage,
        "blend": str(path),
        "blend_sha256": digest(path),
        "source": str(source),
        "source_sha256": digest(source),
        "script_sha256": digest(__file__),
        "blender": bpy.app.version_string,
    }
    Path(str(path) + ".stage.json").write_text(json.dumps(info, indent=2) + "\n")


def main() -> None:
    source = bpy.data.filepath
    saved = remove_old()
    cloth_mat = saved.get("SM_Cloth")
    slate_mat = saved.get("SM_Slate")
    if cloth_mat or slate_mat:
        saved["SM_Cloth"] = cloth_mat
        saved["SM_Slate"] = slate_mat
    build_cloth_and_slate(saved)
    build_rails(saved.get("SM_Rails"))
    build_apron(saved.get("SM_Apron"))
    build_reveal(saved.get("SM_ApronReveal"))
    build_jaws(first_material(D.objects.get("SM_Facings")))
    build_pockets(saved.get("SM_PocketWells"))
    build_low_camera()

    args = sys.argv[sys.argv.index("--") + 1 :] if "--" in sys.argv else []
    if args:
        bpy.ops.wm.save_as_mainfile(filepath=args[0])
        write_stage(args[0], "sculpt", source)
        print(f"rebuilt table saved to {args[0]}")
    else:
        bpy.ops.wm.save_mainfile()
        write_stage(bpy.data.filepath, "sculpt", source)
        print("rebuilt table saved in place")


if __name__ == "__main__":
    main()
