"""Round the table's hard corners and smooth the shading.

The table is authored as boxes with bevels baked at object scale. This pass
does three things, in this order:

1. makes every table object single-user and applies the object scale, so a
   bevel width is a real-world millimetre instead of a per-axis stretch;
2. adds a small bevel only to the objects that never received one (legs,
   mouldings, medallion, pocket wells) -- rails, apron and jaws already carry
   theirs and re-bevelling them would eat the profile;
3. shades smooth by angle, which is what actually removes the faceted look:
   the existing bevels have 3 flat segments each and read as hard bands.

Cloth, cushions, facings, sights and slate keep their exact shape: the cushion
nose is the physics reference and the sights are 3 mm thick.

Usage:
    Blender -b theme_a.blend --python smooth_table.py -- out.blend
"""

import bpy
import math
import sys


EXCLUDED = {
    "FLOOR",
    "BACKDROP",
    "SM_Cloth",
    "SM_Cushions",
    "SM_Facings",
    "SM_Sights",
    "SM_Slate",
}

BEVEL_WIDTHS = {
    "SM_Legs": 0.0014,
    "SM_Moldings": 0.0006,
    "SM_Medallion": 0.0008,
}

SMOOTH_ANGLE = math.radians(35.0)


def activate(obj):
    bpy.ops.object.select_all(action="DESELECT")
    obj.select_set(True)
    bpy.context.view_layer.objects.active = obj


def make_single_user(obj):
    activate(obj)
    bpy.ops.object.make_single_user(object=True, obdata=True)


def apply_scale(obj):
    activate(obj)
    if any(abs(value - 1.0) > 1e-6 for value in obj.scale):
        bpy.ops.object.transform_apply(location=False, rotation=False, scale=True)


def add_bevel(obj, width):
    activate(obj)
    modifier = obj.modifiers.new("Round", "BEVEL")
    modifier.width = width
    modifier.segments = 3
    modifier.limit_method = "ANGLE"
    modifier.angle_limit = math.radians(40.0)
    modifier.miter_outer = "MITER_ARC"
    modifier.use_clamp_overlap = True
    modifier.harden_normals = False
    bpy.ops.object.modifier_apply(modifier=modifier.name)


def shade_smooth(obj):
    activate(obj)
    try:
        bpy.ops.object.shade_smooth_by_angle(angle=SMOOTH_ANGLE)
        return "by_angle"
    except (AttributeError, RuntimeError, TypeError):
        pass
    try:
        bpy.ops.object.shade_smooth()
        mesh = obj.data
        mesh.use_auto_smooth = True
        mesh.auto_smooth_angle = SMOOTH_ANGLE
        return "auto_smooth"
    except (AttributeError, RuntimeError, TypeError):
        for polygon in obj.data.polygons:
            polygon.use_smooth = True
        return "smooth"


def smooth_fraction(obj):
    polygons = obj.data.polygons
    if not polygons:
        return 0.0
    smooth = sum(1 for polygon in polygons if polygon.use_smooth)
    return 100.0 * smooth / len(polygons)


def write_stage(path: str, stage: str, source: str) -> None:
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


def main():
    source = bpy.data.filepath
    output = sys.argv[sys.argv.index("--") + 1] if "--" in sys.argv else None
    print("=== SMOOTH PASS ===")
    for obj in list(bpy.data.objects):
        if obj.type != "MESH" or obj.name in EXCLUDED:
            continue
        before_verts = len(obj.data.vertices)
        before_tris = len(obj.data.loop_triangles) or 0
        before_smooth = smooth_fraction(obj)
        make_single_user(obj)
        apply_scale(obj)
        width = BEVEL_WIDTHS.get(obj.name)
        if width:
            add_bevel(obj, width)
        method = shade_smooth(obj)
        obj.data.calc_loop_triangles()
        print(
            f"{obj.name:20s} verts {before_verts:6d} -> {len(obj.data.vertices):6d} "
            f"tris {before_tris:6d} -> {len(obj.data.loop_triangles):6d} "
            f"smooth {before_smooth:5.1f}% -> {smooth_fraction(obj):5.1f}% "
            f"({method})"
        )
    if output:
        bpy.ops.wm.save_as_mainfile(filepath=output)
        write_stage(output, "smooth", source)
        print("saved", output)


main()
