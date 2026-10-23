import bpy
import math

D = bpy.data


def cube(name, loc, scale, rot=(0.0, 0.0, 0.0)):
    bpy.ops.mesh.primitive_cube_add(size=1.0, location=loc)
    o = bpy.context.active_object
    o.name = name
    o.scale = scale
    o.rotation_euler = rot
    return o


def sphere(name, loc, scale):
    bpy.ops.mesh.primitive_uv_sphere_add(segments=16, ring_count=8, radius=1.0, location=loc)
    o = bpy.context.active_object
    o.name = name
    o.scale = scale
    return o


def cyl(name, loc, radius, depth, rot=(0.0, 0.0, 0.0)):
    bpy.ops.mesh.primitive_cylinder_add(vertices=20, radius=radius, depth=depth, location=loc, rotation=rot)
    o = bpy.context.active_object
    o.name = name
    return o


def join(objs, name):
    bpy.ops.object.select_all(action="DESELECT")
    for o in objs:
        o.select_set(True)
    bpy.context.view_layer.objects.active = objs[0]
    bpy.ops.object.join()
    objs[0].name = name
    return objs[0]


def mat(o, name):
    m = D.materials.get(name)
    if m and o.data:
        o.data.materials.append(m)


# --- flutes on the leg shafts (real grooves) ---------------------------------
legs = D.objects.get("SM_Legs")
if legs:
    for sx in (-1.0, 1.0):
        for sy in (-1.0, 1.0):
            cx, cy = sx * 1.33, sy * 0.70
            for i in range(16):
                a = 2.0 * math.pi * i / 16.0
                c = cube(
                    "FLUTE_CUT",
                    (cx + math.cos(a) * 0.058, cy + math.sin(a) * 0.058, 0.34),
                    (0.0055, 0.018, 0.34),
                    (0.0, 0.0, -a),
                )
                mod = legs.modifiers.new("FL", "BOOLEAN")
                mod.object = c
                mod.operation = "DIFFERENCE"
                mod.solver = "EXACT"
                bpy.context.view_layer.objects.active = legs
                bpy.ops.object.modifier_apply(modifier=mod.name)
                bpy.ops.object.select_all(action="DESELECT")
                c.select_set(True)
                bpy.context.view_layer.objects.active = c
                bpy.ops.object.delete()

# --- dentil row under the rail ----------------------------------------------
dents = []
for i in range(-14, 15):
    x = i * 0.092
    dents.append(cube("DENT", (x, -0.844, 0.627), (0.020, 0.014, 0.024)))
    dents.append(cube("DENT", (x, 0.844, 0.627), (0.020, 0.014, 0.024)))
for i in range(-7, 8):
    y = i * 0.092
    dents.append(cube("DENT", (-1.458, y, 0.627), (0.014, 0.020, 0.024)))
    dents.append(cube("DENT", (1.458, y, 0.627), (0.014, 0.020, 0.024)))
dentils = join(dents, "SM_CarvedDentils")
mat(dentils, "MAT_Wood")

# --- carved rosette on the apron (front and back) ----------------------------
for side in (-1.0, 1.0):
    y = side * 0.846
    parts = [
        cyl("ROSE_DISC", (0.0, y, 0.675), 0.035, 0.012, (math.pi / 2.0, 0.0, 0.0)),
        cyl("ROSE_RING", (0.0, y - side * 0.003, 0.675), 0.049, 0.006, (math.pi / 2.0, 0.0, 0.0)),
    ]
    for i in range(14):
        a = 2.0 * math.pi * i / 14.0
        parts.append(sphere(
            "ROSE_PETAL",
            (math.cos(a) * 0.043, y - side * 0.004, 0.675 + math.sin(a) * 0.043),
            (0.010, 0.007, 0.024),
        ))
    rose = join(parts, "SM_CarvedRosette")
    mat(rose, "MAT_Wood")

# --- beaded rail inlay (a thin brass/ivory line) -----------------------------
for side in (-1.0, 1.0):
    strip = cube("INLAY", (0.0, side * 0.842, 0.7375), (2.22, 0.003, 0.003))
    mat(strip, "MAT_Brass" if D.materials.get("MAT_Brass") else "MAT_Sight")
for side in (-1.0, 1.0):
    strip = cube("INLAY", (side * 1.452, 0.0, 0.7375), (0.003, 1.42, 0.003))
    mat(strip, "MAT_Brass" if D.materials.get("MAT_Brass") else "MAT_Sight")

# --- carved corner brackets -------------------------------------------------
for sx in (-1.0, 1.0):
    for sy in (-1.0, 1.0):
        bracket = cube(
            "CORNER_BRACKET",
            (sx * 1.40, sy * 0.79, 0.655),
            (0.035, 0.035, 0.09),
            (0.0, 0.0, math.pi / 4.0),
        )
        mat(bracket, "MAT_Wood")
