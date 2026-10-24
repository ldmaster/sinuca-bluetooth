"""Replace SM_Apron with a notched tapered/curved frame apron (option 4).

The apron stays just below the rail; rectangular notches are cut where the six
pockets are, so the pocket mouths stay open while the apron keeps its height.
"""

import math

import bpy

D = bpy.data
OUTER_X = 1.465
OUTER_Z = 0.830
INNER_X = 1.270
INNER_Z = 0.635
APRON_INNER_X = 1.300
APRON_INNER_Z = 0.660
TOP = 0.705
BOTTOM = 0.560
NOTCH = 0.090


def apply_material(obj, *names):
    for name in names:
        material = D.materials.get(name)
        if material:
            obj.data.materials.append(material)
            return


def bevel(obj, width=0.014, segments=3):
    modifier = obj.modifiers.new("ApronBevel", "BEVEL")
    modifier.width = width
    modifier.segments = segments
    modifier.limit_method = "ANGLE"
    modifier.angle_limit = math.radians(35.0)
    bpy.context.view_layer.objects.active = obj
    bpy.ops.object.modifier_apply(modifier=modifier.name)


def panel(name, center, size):
    bpy.ops.mesh.primitive_cube_add(size=1.0, location=center)
    obj = bpy.context.active_object
    obj.name = name
    obj.scale = size
    bpy.ops.object.transform_apply(location=False, rotation=False, scale=True)
    bevel(obj)
    return obj


def spans(total, notches):
    result = []
    start = -total
    for center in notches:
        left = center - NOTCH
        right = center + NOTCH
        if left > start:
            result.append((start, left))
        start = right
    if start < total:
        result.append((start, total))
    return result


old = D.objects.get("SM_Apron")
if old:
    bpy.data.objects.remove(old, do_unlink=True)

height = TOP - BOTTOM
mid_y = (TOP + BOTTOM) * 0.5
thickness_x = OUTER_X - APRON_INNER_X
thickness_z = OUTER_Z - APRON_INNER_Z
parts = []

for y_side in (-1.0, 1.0):
    y = y_side * (APRON_INNER_Z + OUTER_Z) * 0.5
    for a, b in spans(OUTER_X, (-INNER_X, 0.0, INNER_X)):
        parts.append(panel(
            "APRON_LONG",
            ((a + b) * 0.5, y, mid_y),
            (b - a, thickness_z, height),
        ))

bpy.ops.object.select_all(action="DESELECT")
for obj in parts:
    obj.select_set(True)
bpy.context.view_layer.objects.active = parts[0]
bpy.ops.object.join()
apron = bpy.context.active_object
apron.name = "SM_Apron"
apply_material(apron, "MAT_Wood", "MAT_Frame", "MAT_Steel")

reveal_y = TOP + 0.004
reveal_parts = []
for y_side in (-1.0, 1.0):
    y = y_side * OUTER_Z
    for a, b in spans(OUTER_X, (-INNER_X, 0.0, INNER_X)):
        bpy.ops.mesh.primitive_cube_add(
            size=1.0, location=((a + b) * 0.5, y, reveal_y)
        )
        obj = bpy.context.active_object
        obj.scale = (b - a, 0.010, 0.008)
        bpy.ops.object.transform_apply(location=False, rotation=False, scale=True)
        reveal_parts.append(obj)
bpy.ops.object.select_all(action="DESELECT")
for obj in reveal_parts:
    obj.select_set(True)
bpy.context.view_layer.objects.active = reveal_parts[0]
bpy.ops.object.join()
reveal = bpy.context.active_object
reveal.name = "SM_ApronReveal"
apply_material(reveal, "MAT_Brass", "MAT_Aluminum", "MAT_Titanium", "MAT_Sight")


print("notched apron rebuilt")
