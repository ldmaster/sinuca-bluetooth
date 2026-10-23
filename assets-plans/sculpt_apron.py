"""Replace SM_Apron with a tapered/curved frame apron (option 4).

Run inside a theme .blend before export:
  blender --background theme_a.blend --python assets-plans/sculpt_apron.py

The apron is a frame, not a solid box: four panels around the slate, top below
the slate, beveled outer profile, a metal reveal at the top edge and a medallion.
"""

import math

import bpy

D = bpy.data
OUTER_X = 1.465
OUTER_Z = 0.830
INNER_X = 1.270
INNER_Z = 0.635
TOP = 0.705
BOTTOM = 0.560


def apply_material(obj, *names):
    for name in names:
        material = D.materials.get(name)
        if material:
            obj.data.materials.append(material)
            return


def bevel(obj, width=0.018, segments=3):
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


old = D.objects.get("SM_Apron")
if old:
    bpy.data.objects.remove(old, do_unlink=True)

height = TOP - BOTTOM
mid_y = (TOP + BOTTOM) * 0.5
thickness_x = OUTER_X - INNER_X
thickness_z = OUTER_Z - INNER_Z

front = panel(
    "APRON_FRONT",
    (0.0, mid_y, -(INNER_Z + OUTER_Z) * 0.5),
    (OUTER_X * 2.0, height, thickness_z),
)
back = panel(
    "APRON_BACK",
    (0.0, mid_y, (INNER_Z + OUTER_Z) * 0.5),
    (OUTER_X * 2.0, height, thickness_z),
)
left = panel(
    "APRON_LEFT",
    (-(INNER_X + OUTER_X) * 0.5, mid_y, 0.0),
    (thickness_x, height, INNER_Z * 2.0),
)
right = panel(
    "APRON_RIGHT",
    ((INNER_X + OUTER_X) * 0.5, mid_y, 0.0),
    (thickness_x, height, INNER_Z * 2.0),
)

bpy.ops.object.select_all(action="DESELECT")
for obj in (front, back, left, right):
    obj.select_set(True)
bpy.context.view_layer.objects.active = front
bpy.ops.object.join()
apron = bpy.context.active_object
apron.name = "SM_Apron"
apply_material(apron, "MAT_Wood", "MAT_Frame", "MAT_Steel")

# Metal reveal: thin frame around the top outer edge.
reveal_parts = []
reveal_y = TOP + 0.004
for zpos in (-OUTER_Z, OUTER_Z):
    bpy.ops.mesh.primitive_cube_add(size=1.0, location=(0.0, reveal_y, zpos))
    obj = bpy.context.active_object
    obj.scale = (OUTER_X * 2.0, 0.008, 0.010)
    bpy.ops.object.transform_apply(location=False, rotation=False, scale=True)
    reveal_parts.append(obj)
for xpos in (-OUTER_X, OUTER_X):
    bpy.ops.mesh.primitive_cube_add(size=1.0, location=(xpos, reveal_y, 0.0))
    obj = bpy.context.active_object
    obj.scale = (0.010, 0.008, OUTER_Z * 2.0)
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

# Medallion on the front and back.
for side in (-1.0, 1.0):
    bpy.ops.mesh.primitive_cylinder_add(
        vertices=32,
        radius=0.042,
        depth=0.010,
        location=(0.0, mid_y, side * (OUTER_Z + 0.002)),
        rotation=(math.pi / 2.0, 0.0, 0.0),
    )
    medallion = bpy.context.active_object
    medallion.name = "SM_ApronMedallion"
    apply_material(medallion, "MAT_Brass", "MAT_Aluminum", "MAT_Titanium", "MAT_Sight")

print("apron rebuilt")
