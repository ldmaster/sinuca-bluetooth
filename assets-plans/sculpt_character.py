"""Build a rigged pool player with Blender.

Two variants share the same skeleton and clips:

    human  dark skin, black afro, shirt and trousers, holds the cue arm forward
    robot  brushed metal, glowing eyes, segmented limbs

The mesh is a handful of primitives joined into one object so the game can draw
it in a single call, then bound to a 17-bone armature with automatic weights.
The cue hand has an IK target that is keyframed for the aim and strike clips and
baked back to FK before export (glTF only carries FK tracks).

Usage:
    Blender -b --python assets-plans/sculpt_character.py -- human var/rebuild/player_human.blend
    Blender -b --python assets-plans/sculpt_character.py -- robot var/rebuild/player_robot.blend

The character faces -Y, feet on Z=0, 1.78 m (human) / 1.90 m (robot) tall.
"""

import math
import sys

import bpy
from mathutils import Euler, Matrix, Quaternion, Vector

KIND = "human"
OUTPUT = None


def reset_scene() -> None:
    bpy.ops.wm.read_factory_settings(use_empty=True)
    scene = bpy.context.scene
    scene.render.fps = 24
    scene.unit_settings.system = "METRIC"
    scene.unit_settings.scale_length = 1.0


def material(name, color, roughness=0.55, metallic=0.0, emission=None):
    mat = bpy.data.materials.get(name)
    if mat:
        return mat
    mat = bpy.data.materials.new(name)
    mat.use_nodes = True
    bsdf = mat.node_tree.nodes.get("Principled BSDF")
    bsdf.inputs["Base Color"].default_value = (*color, 1.0)
    bsdf.inputs["Roughness"].default_value = roughness
    bsdf.inputs["Metallic"].default_value = metallic
    if emission is not None:
        bsdf.inputs["Emission Color"].default_value = (*emission, 1.0)
        bsdf.inputs["Emission Strength"].default_value = 3.0
    mat.diffuse_color = (*color, 1.0)
    return mat


def activate(obj):
    bpy.ops.object.select_all(action="DESELECT")
    obj.select_set(True)
    bpy.context.view_layer.objects.active = obj


def sphere(name, location, radius, scale=(1, 1, 1), segments=24, rings=16):
    bpy.ops.mesh.primitive_uv_sphere_add(
        segments=segments, ring_count=rings, radius=radius, location=location
    )
    obj = bpy.context.active_object
    obj.name = name
    obj.scale = scale
    bpy.ops.object.transform_apply(location=False, rotation=False, scale=True)
    return obj


def capsule(name, start, end, radius, segments=20):
    """A cylinder with round caps, built from a cylinder plus two spheres."""
    start = Vector(start)
    end = Vector(end)
    direction = end - start
    length = direction.length
    middle = (start + end) * 0.5
    bpy.ops.mesh.primitive_cylinder_add(
        vertices=segments, radius=radius, depth=length, location=middle
    )
    body = bpy.context.active_object
    body.name = name
    body.rotation_mode = "QUATERNION"
    body.rotation_quaternion = direction.to_track_quat("Z", "Y")
    bpy.ops.object.transform_apply(location=True, rotation=True, scale=True)
    cap_a = sphere(f"{name}_cap_a", start, radius, segments=segments, rings=12)
    cap_b = sphere(f"{name}_cap_b", end, radius, segments=segments, rings=12)
    return [body, cap_a, cap_b]


def box(name, location, size):
    bpy.ops.mesh.primitive_cube_add(size=1.0, location=location)
    obj = bpy.context.active_object
    obj.name = name
    obj.scale = size
    bpy.ops.object.transform_apply(location=False, rotation=False, scale=True)
    return obj


PARTS = []


def part(obj, bone, mat):
    """Assign a material and bind every vertex of the shell to one bone.

    Rigid weights are intentional: the character is a set of overlapping shells
    (like an articulated figure). Automatic heat weights spread the joint
    spheres across two bones and tear them away from the limbs when posed.
    """
    obj.data.materials.clear()
    obj.data.materials.append(mat)
    group = obj.vertex_groups.new(name=bone)
    group.add(list(range(len(obj.data.vertices))), 1.0, "REPLACE")
    PARTS.append(obj)
    return obj


def part_all(objects, bone, mat):
    for obj in objects:
        part(obj, bone, mat)
    return objects


def side_name(side):
    return "L" if side < 0 else "R"


def join(objects, name):
    bpy.ops.object.select_all(action="DESELECT")
    for obj in objects:
        obj.select_set(True)
    bpy.context.view_layer.objects.active = objects[0]
    bpy.ops.object.join()
    joined = bpy.context.active_object
    joined.name = name
    bpy.ops.object.transform_apply(location=True, rotation=True, scale=True)
    return joined


def build_human_mesh():
    PARTS.clear()
    skin = material("CHAR_Skin", (0.085, 0.045, 0.028), 0.52)
    hair = material("CHAR_Hair", (0.012, 0.010, 0.010), 0.30)
    shirt = material("CHAR_Shirt", (0.045, 0.075, 0.11), 0.62)
    pants = material("CHAR_Pants", (0.028, 0.030, 0.038), 0.72)
    shoes = material("CHAR_Shoes", (0.015, 0.015, 0.018), 0.45)

    part(sphere("hips", (0, 0, 0.95), 0.15, (1.0, 0.72, 0.72)), "hips", pants)
    part(
        sphere("abdomen", (0, -0.005, 1.12), 0.16, (1.0, 0.72, 1.05)),
        "spine", shirt
    )
    part(
        sphere("chest", (0, -0.005, 1.34), 0.19, (1.0, 0.70, 0.95)),
        "chest", shirt
    )
    part_all(capsule("neck", (0, 0, 1.44), (0, 0, 1.53), 0.052), "neck", skin)
    part(sphere("head", (0, -0.005, 1.62), 0.105, (1.0, 0.92, 1.12)), "head", skin)
    for index, (dx, dy, dz, radius) in enumerate([
        (0.00, 0.010, 1.700, 0.088),
        (0.060, 0.020, 1.680, 0.070),
        (-0.060, 0.020, 1.680, 0.070),
        (0.000, -0.045, 1.700, 0.070),
        (0.050, -0.040, 1.690, 0.062),
        (-0.050, -0.040, 1.690, 0.062),
        (0.000, 0.050, 1.650, 0.068),
        (0.070, 0.035, 1.620, 0.055),
        (-0.070, 0.035, 1.620, 0.055),
    ]):
        part(sphere(f"hair_{index}", (dx, dy, dz), radius), "head", hair)

    for side in (-1, 1):
        suffix = side_name(side)
        shoulder = (side * 0.20, 0, 1.41)
        elbow = (side * 0.24, -0.08, 1.12)
        wrist = (side * 0.22, -0.36, 0.94)
        hand = (side * 0.21, -0.45, 0.90)
        part(sphere(f"shoulder_{side}", shoulder, 0.078), f"shoulder.{suffix}", shirt)
        part_all(
            capsule(f"upper_arm_{side}", shoulder, elbow, 0.052),
            f"upper_arm.{suffix}", skin
        )
        part(sphere(f"elbow_{side}", elbow, 0.052), f"upper_arm.{suffix}", skin)
        part_all(
            capsule(f"forearm_{side}", elbow, wrist, 0.044),
            f"forearm.{suffix}", skin
        )
        # A palm plus a thumb reads as a hand; a sphere reads as a ball.
        part(
            sphere(f"hand_{side}_palm", hand, 0.045, (0.80, 1.25, 0.55)),
            f"hand.{suffix}", skin
        )
        part_all(
            capsule(
                f"hand_{side}_thumb",
                (hand[0] + side * 0.026, hand[1] + 0.030, hand[2] + 0.008),
                (hand[0] + side * 0.055, hand[1] - 0.005, hand[2] + 0.022),
                0.013
            ),
            f"hand.{suffix}", skin
        )

    for side in (-1, 1):
        suffix = side_name(side)
        hip = (side * 0.095, 0, 0.94)
        knee = (side * 0.105, 0, 0.52)
        ankle = (side * 0.105, 0, 0.075)
        part_all(
            capsule(f"thigh_{side}", hip, knee, 0.070),
            f"thigh.{suffix}", pants
        )
        part(sphere(f"knee_{side}", knee, 0.070), f"thigh.{suffix}", pants)
        part_all(
            capsule(f"shin_{side}", knee, ankle, 0.058),
            f"shin.{suffix}", pants
        )
        part(
            box(f"shoe_{side}", (side * 0.105, -0.055, 0.035), (0.10, 0.24, 0.07)),
            f"foot.{suffix}", shoes
        )
    return join(PARTS, "PLAYER_HUMAN")


def build_robot_mesh():
    PARTS.clear()
    metal = material("CHAR_Metal", (0.30, 0.32, 0.35), 0.35, 0.9)
    dark = material("CHAR_Joint", (0.02, 0.02, 0.025), 0.55, 0.4)
    glow = material(
        "CHAR_Glow", (0.05, 0.65, 0.85), 0.3, 0.0, emission=(0.05, 0.75, 1.0)
    )
    part(sphere("hips", (0, 0, 0.98), 0.16, (1.0, 0.75, 0.7)), "hips", dark)
    part(box("abdomen", (0, 0, 1.14), (0.30, 0.20, 0.22)), "spine", metal)
    part(box("chest", (0, -0.01, 1.36), (0.40, 0.24, 0.30)), "chest", metal)
    part(sphere("core", (0, -0.13, 1.36), 0.045), "chest", glow)
    part_all(capsule("neck", (0, 0, 1.48), (0, 0, 1.57), 0.05), "neck", dark)
    part(box("head", (0, -0.005, 1.66), (0.18, 0.18, 0.20)), "head", metal)
    part(sphere("eye_l", (-0.045, -0.095, 1.68), 0.018), "head", glow)
    part(sphere("eye_r", (0.045, -0.095, 1.68), 0.018), "head", glow)
    part(box("visor", (0, -0.095, 1.69), (0.11, 0.012, 0.045)), "head", dark)

    for side in (-1, 1):
        suffix = side_name(side)
        shoulder = (side * 0.22, 0, 1.43)
        elbow = (side * 0.26, -0.08, 1.13)
        wrist = (side * 0.24, -0.37, 0.94)
        hand = (side * 0.23, -0.46, 0.90)
        part(sphere(f"shoulder_{side}", shoulder, 0.085), f"shoulder.{suffix}", dark)
        part_all(
            capsule(f"upper_arm_{side}", shoulder, elbow, 0.055),
            f"upper_arm.{suffix}", metal
        )
        part(sphere(f"elbow_{side}", elbow, 0.058), f"upper_arm.{suffix}", dark)
        part_all(
            capsule(f"forearm_{side}", elbow, wrist, 0.047),
            f"forearm.{suffix}", metal
        )
        part(box(f"hand_{side}", hand, (0.09, 0.11, 0.08)), f"hand.{suffix}", dark)

    for side in (-1, 1):
        suffix = side_name(side)
        hip = (side * 0.10, 0, 0.97)
        knee = (side * 0.11, 0, 0.53)
        ankle = (side * 0.11, 0, 0.08)
        part_all(
            capsule(f"thigh_{side}", hip, knee, 0.072),
            f"thigh.{suffix}", metal
        )
        part(sphere(f"knee_{side}", knee, 0.062), f"thigh.{suffix}", dark)
        part_all(
            capsule(f"shin_{side}", knee, ankle, 0.060),
            f"shin.{suffix}", metal
        )
        part(
            box(f"foot_{side}", (side * 0.11, -0.06, 0.04), (0.12, 0.26, 0.08)),
            f"foot.{suffix}", dark
        )
    return join(PARTS, "PLAYER_ROBOT")


def build_armature(name):
    """17 bones: root, hips, spine, chest, neck, head, arms and legs."""
    bpy.ops.object.armature_add(enter_editmode=True, location=(0, 0, 0))
    armature = bpy.context.active_object
    armature.name = name
    armature.data.name = f"{name}_rig"
    bpy.ops.armature.select_all(action="SELECT")
    bpy.ops.armature.delete()

    bones = [
        ("root", (0, 0, 0.0), (0, 0, 0.10), None),
        ("hips", (0, 0, 0.95), (0, 0, 1.06), "root"),
        ("spine", (0, 0, 1.06), (0, 0, 1.30), "hips"),
        ("chest", (0, 0, 1.30), (0, 0, 1.46), "spine"),
        ("neck", (0, 0, 1.46), (0, 0, 1.55), "chest"),
        ("head", (0, 0, 1.55), (0, 0, 1.76), "neck"),
    ]
    for side, suffix in ((-1, "L"), (1, "R")):
        bones += [
            (f"shoulder.{suffix}", (0, 0, 1.42), (side * 0.20, 0, 1.40), "chest"),
            (f"upper_arm.{suffix}", (side * 0.20, 0, 1.41),
             (side * 0.24, -0.08, 1.12), f"shoulder.{suffix}"),
            (f"forearm.{suffix}", (side * 0.24, -0.08, 1.12),
             (side * 0.22, -0.36, 0.94), f"upper_arm.{suffix}"),
            (f"hand.{suffix}", (side * 0.22, -0.36, 0.94),
             (side * 0.21, -0.45, 0.90), f"forearm.{suffix}"),
            (f"thigh.{suffix}", (side * 0.10, 0, 0.95),
             (side * 0.11, 0, 0.52), "hips"),
            (f"shin.{suffix}", (side * 0.11, 0, 0.52),
             (side * 0.11, 0, 0.075), f"thigh.{suffix}"),
            (f"foot.{suffix}", (side * 0.11, 0, 0.075),
             (side * 0.11, -0.20, 0.03), f"shin.{suffix}"),
        ]

    edit_bones = {}
    for bone_name, head, tail, parent in bones:
        bone = armature.data.edit_bones.new(bone_name)
        bone.head = head
        bone.tail = tail
        if parent:
            bone.parent = edit_bones[parent]
            bone.use_connect = False
        edit_bones[bone_name] = bone
    bpy.ops.object.mode_set(mode="OBJECT")
    return armature


def bind(mesh, armature):
    bpy.ops.object.select_all(action="DESELECT")
    mesh.select_set(True)
    armature.select_set(True)
    bpy.context.view_layer.objects.active = armature
    # The shells already carry their bone groups; ARMATURE_NAME keeps them
    # instead of running heat weights that would tear the joints apart.
    bpy.ops.object.parent_set(type="ARMATURE_NAME")


def aim_bone(bone, head, direction, roll_up=None):
    """Rotate a bone so its Y axis points along ``direction``.

    The rotation is the minimal one between the current and target directions,
    so the bone's roll (and therefore the orientation of its children) is kept.
    Rebuilding the matrix from scratch twists the torso and mirrors the arms.
    """
    current = bone.matrix.copy()
    current_direction = bone.tail - bone.head
    if current_direction.length < 1e-6:
        return
    current_direction.normalize()
    target = Vector(direction)
    if target.length < 1e-6:
        return
    target.normalize()
    rotation = current_direction.rotation_difference(target).to_matrix().to_4x4()
    matrix = rotation @ current
    matrix.translation = head
    bone.matrix = matrix


def solve_elbow(shoulder, wrist, l1, l2, pole):
    """Two-bone IK: returns the elbow position and the reachable wrist."""
    to_wrist = wrist - shoulder
    distance = to_wrist.length
    max_reach = l1 + l2 - 1e-4
    if distance > max_reach:
        to_wrist = to_wrist.normalized() * max_reach
        distance = max_reach
        wrist = shoulder + to_wrist
    if distance < 1e-4:
        distance = 1e-4
    direction = to_wrist / distance
    along = (l1 * l1 - l2 * l2 + distance * distance) / (2.0 * distance)
    height = math.sqrt(max(0.0, l1 * l1 - along * along))
    perpendicular = pole - direction * pole.dot(direction)
    if perpendicular.length < 1e-5:
        perpendicular = direction.cross(Vector((0.0, 1.0, 0.0)))
    perpendicular.normalize()
    elbow = shoulder + direction * along + perpendicular * height
    return elbow, wrist


def pose_aim(
    armature,
    wrist_left=None,
    wrist_right=None,
    spine_lean=38.0,
    chest_lean=26.0
):
    """Analytic IK: lean the torso, then place both hands on the cue."""
    bones = armature.pose.bones
    for bone in bones:
        bone.rotation_mode = "QUATERNION"

    # Lean the torso so the arms can actually reach cue height.
    for name, angle in (("spine", spine_lean), ("chest", chest_lean)):
        bone = bones[name]
        direction = Vector((0.0, 0.0, 1.0))
        direction.rotate(Euler((math.radians(angle), 0.0, 0.0)))
        aim_bone(bone, bone.head.copy(), direction)
        bpy.context.view_layer.update()

    left_wrist = Vector(wrist_left) if wrist_left else Vector((-0.06, -0.62, 0.84))
    right_wrist = Vector(wrist_right) if wrist_right else Vector((0.02, -0.34, 0.86))

    for suffix, wrist, pole in (
        ("L", left_wrist, Vector((-0.8, 0.2, -0.4))),
        ("R", right_wrist, Vector((0.8, 0.2, -0.4))),
    ):
        upper = bones[f"upper_arm.{suffix}"]
        forearm = bones[f"forearm.{suffix}"]
        hand = bones[f"hand.{suffix}"]
        shoulder = upper.head.copy()
        l1 = (forearm.head - upper.head).length
        l2 = (hand.head - forearm.head).length
        elbow, wrist = solve_elbow(shoulder, wrist, l1, l2, pole)
        aim_bone(upper, shoulder, elbow - shoulder)
        bpy.context.view_layer.update()
        current_elbow = forearm.head.copy()
        aim_bone(forearm, current_elbow, wrist - current_elbow)
        bpy.context.view_layer.update()
        aim_bone(
            hand,
            hand.head.copy(),
            (wrist - current_elbow).normalized(),
            roll_up=(0.0, 1.0, 0.0)
        )
        bpy.context.view_layer.update()

    # Eyes on the shot: tilt the head down along the cue line.
    head = bones["head"]
    aim_bone(head, head.head.copy(), Vector((0.0, -0.42, 0.91)))
    bpy.context.view_layer.update()


def make_action(armature, name, frames):
    """frames: list of (frame, pose function)"""
    armature.animation_data_create()
    action = bpy.data.actions.new(name)
    action.use_fake_user = True
    armature.animation_data.action = action
    for frame, apply_pose in frames:
        bpy.context.scene.frame_set(frame)
        for bone in armature.pose.bones:
            bone.rotation_mode = "QUATERNION"
            bone.rotation_quaternion = (1.0, 0.0, 0.0, 0.0)
            bone.location = (0.0, 0.0, 0.0)
        bpy.context.view_layer.update()
        apply_pose(armature, frame)
        bpy.context.view_layer.update()
        for bone in armature.pose.bones:
            bone.keyframe_insert("rotation_quaternion", frame=frame)
            bone.keyframe_insert("location", frame=frame)
    track = armature.animation_data.nla_tracks.new()
    track.name = name
    strip = track.strips.new(name, int(action.frame_range[0]), action)
    strip.name = name
    armature.animation_data.action = None
    return action


def idle_pose(armature, frame):
    """Relaxed stance: the rest pose is already a natural standing figure."""
    return


def aim_pose(armature, frame):
    pose_aim(armature)


def strike_pose(armature, frame):
    # The bridge hand (L) stays planted; only the grip hand (R) strokes.
    bridge = (-0.06, -0.62, 0.84)
    if frame < 6:
        pose_aim(armature, wrist_left=bridge, wrist_right=(0.02, -0.34, 0.86))
    elif frame < 10:
        pose_aim(armature, wrist_left=bridge, wrist_right=(0.02, -0.20, 0.89))
    elif frame < 14:
        pose_aim(armature, wrist_left=bridge, wrist_right=(0.02, -0.44, 0.85))
    else:
        pose_aim(armature, wrist_left=bridge, wrist_right=(0.02, -0.50, 0.84))


def celebrate_pose(armature, frame):
    """Both arms up, chest open: one ball."""
    pose_aim(
        armature,
        wrist_left=(-0.19, -0.04, 1.99),
        wrist_right=(0.19, -0.04, 1.99),
        spine_lean=0.0,
        chest_lean=0.0
    )


def celebrate_big_pose(armature, frame):
    """Arms higher plus a small hop: two or more balls."""
    pose_aim(
        armature,
        wrist_left=(-0.24, -0.02, 2.06),
        wrist_right=(0.24, -0.02, 2.06),
        spine_lean=-4.0,
        chest_lean=-3.0
    )
    armature.pose.bones["root"].location.z = 0.10


def walk_pose(armature, frame):
    """Simple walk cycle: legs alternate, arms counter-swing, slight bob."""
    phase = math.sin(frame / 24.0 * math.pi * 2.0)
    for suffix, sign in (("L", 1.0), ("R", -1.0)):
        thigh = armature.pose.bones[f"thigh.{suffix}"]
        shin = armature.pose.bones[f"shin.{suffix}"]
        thigh.rotation_mode = "QUATERNION"
        shin.rotation_mode = "QUATERNION"
        thigh.rotation_quaternion = Quaternion(
            (1.0, 0.0, 0.0), math.radians(28.0) * phase * sign
        )
        shin.rotation_quaternion = Quaternion(
            (1.0, 0.0, 0.0), math.radians(-32.0) * max(0.0, phase * sign)
        )
    for suffix, sign in (("L", -1.0), ("R", 1.0)):
        upper = armature.pose.bones[f"upper_arm.{suffix}"]
        upper.rotation_mode = "QUATERNION"
        upper.rotation_quaternion = Quaternion(
            (1.0, 0.0, 0.0), math.radians(22.0) * phase * sign
        )
    armature.pose.bones["root"].location.z = abs(phase) * 0.025


def build_animations(armature):
    make_action(armature, "idle", [(1, idle_pose), (24, idle_pose), (48, idle_pose)])
    make_action(armature, "aim", [(1, aim_pose), (18, aim_pose)])
    make_action(
        armature,
        "strike",
        [(1, aim_pose), (6, strike_pose), (10, strike_pose),
         (14, strike_pose), (24, strike_pose)]
    )
    make_action(
        armature,
        "walk",
        [(1, walk_pose), (7, walk_pose), (13, walk_pose), (19, walk_pose),
         (25, walk_pose)]
    )
    make_action(
        armature,
        "celebrate",
        [(1, idle_pose), (6, celebrate_pose), (18, celebrate_pose), (28, idle_pose)]
    )
    make_action(
        armature,
        "celebrate_big",
        [(1, idle_pose), (6, celebrate_big_pose), (12, idle_pose),
         (18, celebrate_big_pose), (34, idle_pose)]
    )
    bpy.context.scene.frame_set(1)


def level_camera(kind):
    bpy.ops.object.camera_add(location=(2.4, -3.0, 1.55))
    camera = bpy.context.active_object
    camera.name = "CAM-character"
    direction = Vector((0, 0, 0.95)) - camera.location
    camera.rotation_euler = direction.to_track_quat("-Z", "Y").to_euler()
    camera.data.lens = 50
    bpy.context.scene.camera = camera


def main():
    global KIND, OUTPUT
    args = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    if args:
        KIND = args[0]
    if len(args) > 1:
        OUTPUT = args[1]

    reset_scene()
    mesh = build_human_mesh() if KIND == "human" else build_robot_mesh()
    armature = build_armature("RIG_" + KIND.upper())
    bind(mesh, armature)
    build_animations(armature)
    level_camera(KIND)

    if OUTPUT:
        bpy.ops.wm.save_as_mainfile(filepath=OUTPUT)
        print(f"saved character ({KIND}) to {OUTPUT}")
    else:
        bpy.ops.wm.save_mainfile()


main()
