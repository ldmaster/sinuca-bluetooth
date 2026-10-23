#!/usr/bin/env python3
"""Generate guarded Blender material-study plans for the five texture proposals.

This is the harness escape hatch: a small bpy script edits the existing theme
materials with procedural PBR nodes, then the harness renders the table. The
script only touches table materials; it never clears the scene or writes files.
"""

from __future__ import annotations

import json
from pathlib import Path

OUT = Path(__file__).resolve().parent
PLANS = OUT / "texture-plans"
PLANS.mkdir(exist_ok=True)

THEME_BLENDS = {
    "a": "var/runs/run_20261023T165657Z_1a173f/blender/theme_a.blend",
    "b": "var/runs/run_20261023T165838Z_554780/blender/theme_b.blend",
    "c": "var/runs/run_20261023T165801Z_81856b/blender/theme_c.blend",
    "d": "var/runs/run_20261023T165842Z_0bf7e6/blender/theme_d.blend",
}

DETAIL_THEMES = {"p1": "a", "p2": "c", "p3": "d", "p4": "b", "p5": "a"}

SCRIPT = r'''
import bpy
import math

MODE = "__MODE__"
P = __PARAMS__
D = bpy.data


def bsdf(mat):
    if not mat or not mat.use_nodes:
        return None
    return mat.node_tree.nodes.get("Principled BSDF")


def base_color(mat):
    b = bsdf(mat)
    if not b:
        return (0.4, 0.4, 0.4)
    c = b.inputs["Base Color"].default_value
    return (c[0], c[1], c[2])


def scale(c, k):
    return (max(0.0, min(1.0, c[0] * k)),
            max(0.0, min(1.0, c[1] * k)),
            max(0.0, min(1.0, c[2] * k)))


def link(nt, a, b):
    nt.links.new(a, b)


def coord(nt, sx, sy, sz):
    tc = nt.nodes.new("ShaderNodeTexCoord")
    mp = nt.nodes.new("ShaderNodeMapping")
    mp.inputs["Scale"].default_value = (sx, sy, sz)
    link(nt, tc.outputs["Generated"], mp.inputs["Vector"])
    return mp


def noise(nt, vector, scl, detail=4.0, rough=0.5, distortion=0.0):
    n = nt.nodes.new("ShaderNodeTexNoise")
    n.inputs["Scale"].default_value = scl
    n.inputs["Detail"].default_value = detail
    n.inputs["Roughness"].default_value = rough
    if "Distortion" in n.inputs:
        n.inputs["Distortion"].default_value = distortion
    link(nt, vector, n.inputs["Vector"])
    return n


def ramp(nt, c1, c2, p1=0.0, p2=1.0):
    r = nt.nodes.new("ShaderNodeValToRGB")
    r.color_ramp.elements[0].position = p1
    r.color_ramp.elements[1].position = p2
    r.color_ramp.elements[0].color = (c1[0], c1[1], c1[2], 1.0)
    r.color_ramp.elements[1].color = (c2[0], c2[1], c2[2], 1.0)
    return r


def bump(nt, height, strength, distance=0.015):
    b = nt.nodes.new("ShaderNodeBump")
    b.inputs["Strength"].default_value = strength
    b.inputs["Distance"].default_value = distance
    link(nt, height, b.inputs["Height"])
    return b


def map_range(nt, value, lo, hi):
    m = nt.nodes.new("ShaderNodeMapRange")
    m.inputs["From Min"].default_value = 0.0
    m.inputs["From Max"].default_value = 1.0
    m.inputs["To Min"].default_value = lo
    m.inputs["To Max"].default_value = hi
    link(nt, value, m.inputs["Value"])
    return m


def mix(nt, fac, a_color, b_color, blend="MIX"):
    m = nt.nodes.new("ShaderNodeMixRGB")
    m.blend_type = blend
    m.inputs[0].default_value = fac
    m.inputs[1].default_value = (a_color[0], a_color[1], a_color[2], 1.0)
    m.inputs[2].default_value = (b_color[0], b_color[1], b_color[2], 1.0)
    return m


def displace(name, tex_type, strength, scale, subdiv=0):
    obj = bpy.data.objects.get(name)
    if not obj:
        return
    if subdiv > 0:
        m = obj.modifiers.new("TexSubdiv", "SUBSURF")
        m.levels = subdiv
        m.render_levels = subdiv
    tex = bpy.data.textures.new("Tex_" + name, type=tex_type)
    if hasattr(tex, "noise_scale"):
        tex.noise_scale = scale
    if hasattr(tex, "noise_depth"):
        tex.noise_depth = 4
    if hasattr(tex, "turbulence"):
        tex.turbulence = 6.0
    mod = obj.modifiers.new("TexDisplace", "DISPLACE")
    mod.texture = tex
    mod.strength = strength
    mod.mid_level = 0.5
    mod.texture_coords = "GLOBAL"


def finish(mat, nt, color_node, normal_node, roughness_node, metallic=None):
    b = bsdf(mat)
    if not b:
        return
    if color_node is not None:
        link(nt, color_node, b.inputs["Base Color"])
    if normal_node is not None:
        link(nt, normal_node, b.inputs["Normal"])
    if roughness_node is not None:
        link(nt, roughness_node, b.inputs["Roughness"])
    if metallic is not None and "Metallic" in b.inputs:
        b.inputs["Metallic"].default_value = metallic


def wear_factor(nt, amount):
    geo = nt.nodes.new("ShaderNodeNewGeometry")
    r = ramp(nt, (0.42, 0.42, 0.42), (0.62, 0.62, 0.62), 0.35, 0.72)
    link(nt, geo.outputs["Pointiness"], r.inputs["Fac"])
    return r, amount


def wood(mat):
    b = bsdf(mat)
    if not b:
        return
    c = base_color(mat)
    nt = mat.node_tree
    mp = coord(nt, 5.0, 1.2, 1.0)
    wave = nt.nodes.new("ShaderNodeTexWave")
    wave.wave_type = "BANDS"
    wave.bands_direction = "X"
    wave.inputs["Scale"].default_value = P["wood_scale"]
    wave.inputs["Distortion"].default_value = P["wood_distortion"]
    wave.inputs["Detail"].default_value = P["wood_detail"]
    link(nt, mp.outputs["Vector"], wave.inputs["Vector"])
    grain = ramp(nt, scale(c, 0.42), scale(c, 1.25))
    link(nt, wave.outputs["Fac"], grain.inputs["Fac"])
    n = noise(nt, mp.outputs["Vector"], P["wood_noise"], 6.0, 0.62)
    micro = bump(nt, n.outputs["Fac"], P["bump_wood"], 0.012)
    rn = noise(nt, mp.outputs["Vector"], P["rough_noise"], 4.0, 0.55)
    rr = map_range(nt, rn.outputs["Fac"], P["rough_min"], P["rough_max"])
    color = grain.outputs["Color"]
    normal = micro.outputs["Normal"]
    if MODE == "p2":
        fac, amount = wear_factor(nt, P["wear"])
        worn = mix(nt, 0.5, (0, 0, 0), (0, 0, 0))
        # use the pointiness as factor on a lighter version of the grain
        m = nt.nodes.new("ShaderNodeMixRGB")
        m.blend_type = "MIX"
        link(nt, fac.outputs["Color"], m.inputs[0])
        link(nt, grain.outputs["Color"], m.inputs[1])
        m.inputs[2].default_value = (min(1.0, c[0] * 1.7 + 0.08),
                                     min(1.0, c[1] * 1.6 + 0.06),
                                     min(1.0, c[2] * 1.5 + 0.04), 1.0)
        color = m.outputs["Color"]
    if MODE == "p4":
        macro = noise(nt, mp.outputs["Vector"], P["macro"], 3.0, 0.5)
        mm = mix(nt, 0.55, (0, 0, 0), (0, 0, 0))
        link(nt, macro.outputs["Fac"], mm.inputs[0])
        link(nt, color, mm.inputs[1])
        mm.inputs[2].default_value = (scale(c, 0.75)[0], scale(c, 0.75)[1], scale(c, 0.75)[2], 1.0)
        color = mm.outputs["Color"]
    if MODE in ("p1", "p2", "p4"):
        scr = nt.nodes.new("ShaderNodeTexWave")
        scr.wave_type = "BANDS"
        scr.bands_direction = "DIAGONAL"
        scr.inputs["Scale"].default_value = P["scratch_scale"]
        scr.inputs["Distortion"].default_value = 13.0
        scr.inputs["Detail"].default_value = 3.0
        link(nt, mp.outputs["Vector"], scr.inputs["Vector"])
        sb = bump(nt, scr.outputs["Fac"], P["scratch"] * 0.5, 0.003)
        link(nt, normal, sb.inputs["Normal"])
        normal = sb.outputs["Normal"]
    finish(mat, nt, color, normal, rr.outputs["Result"], 0.0)


def cloth(mat):
    b = bsdf(mat)
    if not b:
        return
    c = base_color(mat)
    nt = mat.node_tree
    mp = coord(nt, 1.0, 1.0, 1.0)
    n = noise(nt, mp.outputs["Vector"], P["cloth_scale"], 5.0, 0.7)
    weave = nt.nodes.new("ShaderNodeTexWave")
    weave.wave_type = "BANDS"
    weave.bands_direction = "X"
    weave.inputs["Scale"].default_value = P["cloth_wave"]
    weave.inputs["Distortion"].default_value = 1.5
    link(nt, mp.outputs["Vector"], weave.inputs["Vector"])
    bmp = bump(nt, n.outputs["Fac"], P["bump_cloth"], 0.006)
    if "Sheen Weight" in b.inputs:
        b.inputs["Sheen Weight"].default_value = P["sheen"]
    if "Sheen Roughness" in b.inputs:
        b.inputs["Sheen Roughness"].default_value = 0.45
    grain = ramp(nt, scale(c, 0.72), scale(c, 1.18))
    link(nt, n.outputs["Fac"], grain.inputs["Fac"])
    color = grain.outputs["Color"]
    if MODE == "p2":
        chalk = noise(nt, mp.outputs["Vector"], 2.6, 4.0, 0.55)
        cr = ramp(nt, (0, 0, 0), (1, 1, 1), 0.58, 0.78)
        link(nt, chalk.outputs["Fac"], cr.inputs["Fac"])
        cm = nt.nodes.new("ShaderNodeMixRGB")
        link(nt, cr.outputs["Color"], cm.inputs[0])
        link(nt, color, cm.inputs[1])
        cm.inputs[2].default_value = (min(1.0, c[0] * 1.9 + 0.25),
                                      min(1.0, c[1] * 1.9 + 0.25),
                                      min(1.0, c[2] * 1.9 + 0.25), 1.0)
        color = cm.outputs["Color"]
    if MODE == "p4":
        macro = noise(nt, mp.outputs["Vector"], P["macro"], 3.0, 0.5)
        mm = nt.nodes.new("ShaderNodeMixRGB")
        link(nt, macro.outputs["Fac"], mm.inputs[0])
        link(nt, color, mm.inputs[1])
        mm.inputs[2].default_value = (scale(c, 0.82)[0], scale(c, 0.82)[1], scale(c, 0.82)[2], 1.0)
        color = mm.outputs["Color"]
    finish(mat, nt, color, bmp.outputs["Normal"], None, None)


def leather(mat):
    b = bsdf(mat)
    if not b:
        return
    c = base_color(mat)
    nt = mat.node_tree
    mp = coord(nt, 1.0, 1.0, 1.0)
    vor = nt.nodes.new("ShaderNodeTexVoronoi")
    vor.feature = "F1"
    vor.inputs["Scale"].default_value = P["leather_scale"]
    vor.inputs["Randomness"].default_value = 0.85
    link(nt, mp.outputs["Vector"], vor.inputs["Vector"])
    bmp = bump(nt, vor.outputs["Distance"], P["bump_leather"], 0.008)
    rn = noise(nt, mp.outputs["Vector"], P["leather_rough"], 4.0, 0.6)
    rr = map_range(nt, rn.outputs["Fac"], 0.38, 0.72)
    grain = ramp(nt, scale(c, 0.7), scale(c, 1.2))
    link(nt, vor.outputs["Distance"], grain.inputs["Fac"])
    color = grain.outputs["Color"]
    if MODE == "p2":
        fac, amount = wear_factor(nt, P["wear"])
        m = nt.nodes.new("ShaderNodeMixRGB")
        link(nt, fac.outputs["Color"], m.inputs[0])
        link(nt, color, m.inputs[1])
        m.inputs[2].default_value = (min(1.0, c[0] * 1.6 + 0.05),
                                     min(1.0, c[1] * 1.5 + 0.04),
                                     min(1.0, c[2] * 1.4 + 0.03), 1.0)
        color = m.outputs["Color"]
    if MODE in ("p1", "p2", "p4"):
        crease = nt.nodes.new("ShaderNodeTexWave")
        crease.wave_type = "BANDS"
        crease.bands_direction = "X"
        crease.inputs["Scale"].default_value = 12.0
        crease.inputs["Distortion"].default_value = 10.0
        link(nt, mp.outputs["Vector"], crease.inputs["Vector"])
        cb = bump(nt, crease.outputs["Fac"], P["crease"] * 0.28, 0.004)
        link(nt, bmp.outputs["Normal"], cb.inputs["Normal"])
        bmp = cb
    finish(mat, nt, color, bmp.outputs["Normal"], rr.outputs["Result"], 0.0)


def metal(mat, brushed=True):
    b = bsdf(mat)
    if not b:
        return
    nt = mat.node_tree
    mp = coord(nt, 1.0, 1.0, 1.0)
    n = noise(nt, mp.outputs["Vector"], P["metal_scale"], 4.0, 0.6)
    rn = map_range(nt, n.outputs["Fac"], P["metal_rough_min"], P["metal_rough_max"])
    bmp = bump(nt, n.outputs["Fac"], P["bump_metal"] if brushed else P["bump_metal"] * 0.4, 0.004)
    if "Anisotropic" in b.inputs:
        b.inputs["Anisotropic"].default_value = P["anisotropic"]
    if "Anisotropic Rotation" in b.inputs:
        b.inputs["Anisotropic Rotation"].default_value = 0.25
    r = rn.outputs["Result"]
    if MODE == "p2":
        scratch = noise(nt, mp.outputs["Vector"], P["scratch_scale"], 7.0, 0.72)
        sr = map_range(nt, scratch.outputs["Fac"], 0.22, 0.55)
        r = sr.outputs["Result"]
        sb = bump(nt, scratch.outputs["Fac"], 0.55, 0.0018)
        link(nt, bmp.outputs["Normal"], sb.inputs["Normal"])
        bmp = sb
    finish(mat, nt, None, bmp.outputs["Normal"], r, None)


def slate(mat):
    b = bsdf(mat)
    if not b:
        return
    nt = mat.node_tree
    mp = coord(nt, 1.0, 1.0, 1.0)
    n = noise(nt, mp.outputs["Vector"], 45.0, 4.0, 0.55)
    bmp = bump(nt, n.outputs["Fac"], P["bump_slate"] * 0.35, 0.004)
    finish(mat, nt, None, bmp.outputs["Normal"], None, 0.0)


def sight(mat):
    b = bsdf(mat)
    if not b:
        return
    nt = mat.node_tree
    mp = coord(nt, 1.0, 1.0, 1.0)
    n = noise(nt, mp.outputs["Vector"], 60.0, 3.0, 0.5)
    bmp = bump(nt, n.outputs["Fac"], 0.04, 0.002)
    finish(mat, nt, None, bmp.outputs["Normal"], None, None)


for name, fn in (("MAT_Wood", wood), ("MAT_Cloth", cloth), ("MAT_Cushion", cloth),
                 ("MAT_Facing", leather), ("MAT_Sight", sight),
                 ("MAT_Pocket", metal), ("MAT_Brass", metal), ("MAT_Slate", slate)):
    mat = D.materials.get(name)
    if mat:
        fn(mat)

for name in ("SM_Rails", "SM_Apron", "SM_Legs", "SM_Moldings", "SM_Medallion"):
    displace(name, "MUSGRAVE", P["disp_wood"], 0.28)
displace("SM_Facings", "VORONOI", P["disp_leather"], 0.18, P["subdiv_leather"])
displace("SM_Cushions", "CLOUDS", P["disp_cloth"] * 0.55, 0.40, P["subdiv_cloth"])
'''

PARAMS = {
    "p1": {
        "wood_scale": 13.0, "wood_distortion": 7.0, "wood_detail": 5.0,
        "wood_noise": 55.0, "bump_wood": 0.42, "rough_noise": 22.0,
        "rough_min": 0.28, "rough_max": 0.55, "macro": 1.2, "wear": 0.6,
        "cloth_scale": 110.0, "cloth_wave": 190.0, "bump_cloth": 0.22, "sheen": 0.35,
        "leather_scale": 32.0, "bump_leather": 0.38, "leather_rough": 22.0,
        "metal_scale": 80.0, "metal_rough_min": 0.18, "metal_rough_max": 0.42,
        "bump_metal": 0.18, "anisotropic": 0.65, "scratch_scale": 140.0,
        "bump_slate": 0.2, "disp_wood": 0.0024, "disp_leather": 0.0016,
        "disp_cloth": 0.0015, "subdiv_cloth": 2, "subdiv_leather": 1,
        "scratch": 0.45, "crease": 0.6
    },
    "p2": {
        "wood_scale": 12.0, "wood_distortion": 8.0, "wood_detail": 5.0,
        "wood_noise": 52.0, "bump_wood": 0.5, "rough_noise": 20.0,
        "rough_min": 0.25, "rough_max": 0.62, "macro": 1.2, "wear": 0.9,
        "cloth_scale": 115.0, "cloth_wave": 180.0, "bump_cloth": 0.3, "sheen": 0.3,
        "leather_scale": 30.0, "bump_leather": 0.5, "leather_rough": 20.0,
        "metal_scale": 75.0, "metal_rough_min": 0.18, "metal_rough_max": 0.55,
        "bump_metal": 0.28, "anisotropic": 0.45, "scratch_scale": 120.0,
        "bump_slate": 0.2, "disp_wood": 0.0035, "disp_leather": 0.0027,
        "disp_cloth": 0.0023, "subdiv_cloth": 3, "subdiv_leather": 1,
        "scratch": 1.0, "crease": 1.0
    },
    "p3": {
        "wood_scale": 7.0, "wood_distortion": 2.5, "wood_detail": 3.0,
        "wood_noise": 35.0, "bump_wood": 0.12, "rough_noise": 10.0,
        "rough_min": 0.3, "rough_max": 0.45, "macro": 1.0, "wear": 0.0,
        "cloth_scale": 55.0, "cloth_wave": 90.0, "bump_cloth": 0.08, "sheen": 0.2,
        "leather_scale": 26.0, "bump_leather": 0.12, "leather_rough": 12.0,
        "metal_scale": 50.0, "metal_rough_min": 0.22, "metal_rough_max": 0.34,
        "bump_metal": 0.06, "anisotropic": 0.55, "scratch_scale": 90.0,
        "bump_slate": 0.05, "disp_wood": 0.0007, "disp_leather": 0.0006,
        "disp_cloth": 0.0006, "subdiv_cloth": 1, "subdiv_leather": 0,
        "scratch": 0.05, "crease": 0.15
    },
    "p4": {
        "wood_scale": 10.0, "wood_distortion": 5.0, "wood_detail": 4.0,
        "wood_noise": 40.0, "bump_wood": 0.28, "rough_noise": 14.0,
        "rough_min": 0.3, "rough_max": 0.5, "macro": 1.4, "wear": 0.25,
        "cloth_scale": 70.0, "cloth_wave": 120.0, "bump_cloth": 0.12, "sheen": 0.28,
        "leather_scale": 28.0, "bump_leather": 0.2, "leather_rough": 16.0,
        "metal_scale": 42.0, "metal_rough_min": 0.2, "metal_rough_max": 0.4,
        "bump_metal": 0.08, "anisotropic": 0.7, "scratch_scale": 80.0,
        "bump_slate": 0.08, "disp_wood": 0.0018, "disp_leather": 0.0013,
        "disp_cloth": 0.0012, "subdiv_cloth": 2, "subdiv_leather": 1,
        "scratch": 0.3, "crease": 0.45
    },
    "p5": {
        "wood_scale": 5.0, "wood_distortion": 2.0, "wood_detail": 2.0,
        "wood_noise": 22.0, "bump_wood": 0.1, "rough_noise": 8.0,
        "rough_min": 0.3, "rough_max": 0.48, "macro": 1.0, "wear": 0.0,
        "cloth_scale": 45.0, "cloth_wave": 70.0, "bump_cloth": 0.06, "sheen": 0.18,
        "leather_scale": 20.0, "bump_leather": 0.08, "leather_rough": 10.0,
        "metal_scale": 38.0, "metal_rough_min": 0.22, "metal_rough_max": 0.36,
        "bump_metal": 0.04, "anisotropic": 0.5, "scratch_scale": 60.0,
        "bump_slate": 0.04, "disp_wood": 0.0006, "disp_leather": 0.0005,
        "disp_cloth": 0.0005, "subdiv_cloth": 1, "subdiv_leather": 0,
        "scratch": 0.02, "crease": 0.1
    }
}

for mode, params in PARAMS.items():
    script = SCRIPT.replace("__MODE__", mode).replace("__PARAMS__", repr(params))
    for theme, source in THEME_BLENDS.items():
        plan = {
            "schema_version": "1.0.0",
            "notes": f"texture study {mode} on theme {theme}",
            "ops": [
                {"op": "script", "source": script},
                {"op": "render", "path": f"renders/texture-{mode}-{theme}.png",
                 "resolution_x": 1200, "resolution_y": 900,
                 "engine": "eevee", "samples": 64, "camera": "CAM-hero"}
            ]
        }
        if DETAIL_THEMES.get(mode) == theme:
            plan["ops"].append({
                "op": "render",
                "path": f"renders/texture-{mode}-{theme}-detail.png",
                "resolution_x": 1200,
                "resolution_y": 900,
                "engine": "eevee",
                "samples": 64,
                "camera": "CAM-pocket"
            })
        path = PLANS / f"{mode}_{theme}.json"
        path.write_text(json.dumps(plan, indent=2) + "\n")
        print(f"{mode}/{theme}: {len(script)} chars")
