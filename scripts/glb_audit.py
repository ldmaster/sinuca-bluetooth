#!/usr/bin/env python3
"""Audit an exported table GLB before it reaches the app.

The Blender evaluator looks at the scene; this looks at the file the game will
actually load. It catches the failures that scene statistics cannot see:

* a required node missing (exporting from the wrong .blend stage);
* a material missing (a theme contract broken);
* a mesh that lost its smooth shading (vertex/triangle ratio);
* 16-bit indices on a mesh with more than 65535 vertices;
* a bounding box that drifted from the reference;
* missing normals or UVs.

Usage:
    python3 scripts/glb_audit.py app/src/main/assets/models/table_a.glb
    python3 scripts/glb_audit.py --all --json

Exit code is 0 only when every check passes.
"""

from __future__ import annotations

import argparse
import json
import struct
import sys
from pathlib import Path

REQUIRED_NODES = (
    "SM_Apron",
    "SM_Rails",
    "SM_Legs",
    "SM_PocketWells",
    "SM_PocketJaws",
    "SM_PocketRings",
    "SM_Cloth",
    "SM_Slate",
    "SM_Sights",
    "SM_Cushions",
    "SM_Facings",
)

REQUIRED_MATERIALS = ("MAT_Wood", "MAT_Cloth", "MAT_Sight", "MAT_Slate")

# Table outer size in metres (9 ft frame, glTF Y-up: width, height, depth).
EXPECTED_SIZE = (2.93, 0.803, 1.66)
SIZE_TOLERANCE = 0.04

MIN_TRIANGLES = 30_000
MAX_TRIANGLES = 90_000
MAX_VERTICES_PER_TRIANGLE = 1.0
MAX_MATERIALS = 16

COMPONENT = {5120: ("b", 1), 5121: ("B", 1), 5122: ("h", 2), 5123: ("H", 2), 5125: ("I", 4), 5126: ("f", 4)}
NCOMP = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4, "MAT2": 4, "MAT3": 9, "MAT4": 16}


def load_glb(path: Path) -> tuple[dict, bytes]:
    data = path.read_bytes()
    magic, version, length = struct.unpack_from("<III", data, 0)
    if magic != 0x46546C67:
        raise ValueError(f"{path} is not a GLB file")
    offset = 12
    document: dict | None = None
    binary = b""
    while offset < length:
        chunk_length, chunk_type = struct.unpack_from("<II", data, offset)
        chunk = data[offset + 8 : offset + 8 + chunk_length]
        if chunk_type == 0x4E4F534A:
            document = json.loads(chunk.decode("utf-8"))
        elif chunk_type == 0x004E4942:
            binary = chunk
        offset += 8 + chunk_length + ((4 - chunk_length % 4) % 4 if chunk_length % 4 else 0)
    if document is None:
        raise ValueError(f"{path} has no JSON chunk")
    return document, binary


def accessor_values(document: dict, binary: bytes, index: int) -> list[tuple]:
    accessor = document["accessors"][index]
    view = document["bufferViews"][accessor["bufferView"]]
    fmt, size = COMPONENT[accessor["componentType"]]
    components = NCOMP[accessor["type"]]
    start = view.get("byteOffset", 0) + accessor.get("byteOffset", 0)
    stride = view.get("byteStride", size * components)
    values = []
    for item in range(accessor["count"]):
        values.append(struct.unpack_from("<" + fmt * components, binary, start + item * stride))
    return values


def matrix(node: dict) -> list[float]:
    if "matrix" in node:
        return list(node["matrix"])
    tx, ty, tz = node.get("translation", (0.0, 0.0, 0.0))
    qx, qy, qz, qw = node.get("rotation", (0.0, 0.0, 0.0, 1.0))
    sx, sy, sz = node.get("scale", (1.0, 1.0, 1.0))
    xx, yy, zz = qx * qx, qy * qy, qz * qz
    xy, xz, yz = qx * qy, qx * qz, qy * qz
    wx, wy, wz = qw * qx, qw * qy, qw * qz
    return [
        (1 - 2 * (yy + zz)) * sx, 2 * (xy + wz) * sx, 2 * (xz - wy) * sx, 0.0,
        2 * (xy - wz) * sy, (1 - 2 * (xx + zz)) * sy, 2 * (yz + wx) * sy, 0.0,
        2 * (xz + wy) * sz, 2 * (yz - wx) * sz, (1 - 2 * (xx + yy)) * sz, 0.0,
        tx, ty, tz, 1.0,
    ]


def multiply(a: list[float], b: list[float]) -> list[float]:
    result = [0.0] * 16
    for column in range(4):
        for row in range(4):
            result[column * 4 + row] = sum(a[k * 4 + row] * b[column * 4 + k] for k in range(4))
    return result


def transform(matrix_: list[float], point: tuple[float, float, float]) -> tuple[float, float, float]:
    x, y, z = point
    return (
        matrix_[0] * x + matrix_[4] * y + matrix_[8] * z + matrix_[12],
        matrix_[1] * x + matrix_[5] * y + matrix_[9] * z + matrix_[13],
        matrix_[2] * x + matrix_[6] * y + matrix_[10] * z + matrix_[14],
    )


class Audit:
    def __init__(self, path: Path) -> None:
        self.path = path
        self.document, self.binary = load_glb(path)
        self.checks: list[dict] = []

    def add(self, check_id: str, passed: bool, detail: str) -> None:
        self.checks.append({"id": check_id, "passed": passed, "detail": detail})

    def run(self) -> bool:
        document = self.document
        nodes = document.get("nodes", [])
        meshes = document.get("meshes", [])
        materials = [item.get("name", "") for item in document.get("materials", [])]

        node_names = {node.get("name", "") for node in nodes}
        missing_nodes = sorted(set(REQUIRED_NODES) - node_names)
        self.add(
            "required_nodes",
            not missing_nodes,
            "all present" if not missing_nodes else f"missing: {missing_nodes}",
        )

        missing_materials = sorted(set(REQUIRED_MATERIALS) - set(materials))
        self.add(
            "required_materials",
            not missing_materials,
            f"{len(materials)} materials" if not missing_materials
            else f"missing: {missing_materials}",
        )
        self.add(
            "material_budget",
            len(materials) <= MAX_MATERIALS,
            f"materials={len(materials)} <= {MAX_MATERIALS}",
        )

        vertices = 0
        triangles = 0
        index_problems: list[str] = []
        attribute_problems: list[str] = []
        for mesh in meshes:
            name = mesh.get("name", "?")
            for primitive in mesh.get("primitives", []):
                attributes = primitive.get("attributes", {})
                count = document["accessors"][attributes["POSITION"]]["count"]
                vertices += count
                if "indices" in primitive:
                    triangles += document["accessors"][primitive["indices"]]["count"] // 3
                    index_type = document["accessors"][primitive["indices"]]["componentType"]
                    if count > 65535 and index_type != 5125:
                        index_problems.append(f"{name}: {count} vertices with 16-bit indices")
                else:
                    triangles += count // 3
                if "NORMAL" not in attributes:
                    attribute_problems.append(f"{name}: no NORMAL")
                if "TEXCOORD_0" not in attributes:
                    attribute_problems.append(f"{name}: no TEXCOORD_0")

        ratio = vertices / max(triangles, 1)
        self.add(
            "triangle_range",
            MIN_TRIANGLES <= triangles <= MAX_TRIANGLES,
            f"triangles={triangles} in [{MIN_TRIANGLES}, {MAX_TRIANGLES}]",
        )
        self.add(
            "smooth_shading",
            ratio <= MAX_VERTICES_PER_TRIANGLE,
            f"vertices={vertices}, ratio={ratio:.3f} <= {MAX_VERTICES_PER_TRIANGLE}",
        )
        self.add(
            "index_width",
            not index_problems,
            "32-bit where needed" if not index_problems else "; ".join(index_problems),
        )
        self.add(
            "vertex_attributes",
            not attribute_problems,
            "normals and UVs on every primitive" if not attribute_problems
            else "; ".join(attribute_problems),
        )

        size, centre = self.world_bounds()
        tolerance = SIZE_TOLERANCE
        size_ok = all(
            abs(actual - expected) <= tolerance
            for actual, expected in zip(sorted(size), sorted(EXPECTED_SIZE))
        )
        self.add(
            "world_size",
            size_ok,
            f"size=({size[0]:.3f}, {size[1]:.3f}, {size[2]:.3f}) "
            f"expected={EXPECTED_SIZE} +-{tolerance}",
        )
        self.add(
            "centred",
            abs(centre[0]) <= 0.02 and abs(centre[2]) <= 0.02,
            f"centre=({centre[0]:.3f}, {centre[1]:.3f}, {centre[2]:.3f})",
        )
        return all(check["passed"] for check in self.checks)

    def world_bounds(self) -> tuple[tuple[float, float, float], tuple[float, float, float]]:
        document = self.document
        nodes = document.get("nodes", [])
        minimum = [float("inf")] * 3
        maximum = [float("-inf")] * 3

        def walk(index: int, parent: list[float]) -> None:
            node = nodes[index]
            world = multiply(parent, matrix(node))
            mesh_index = node.get("mesh", -1)
            if mesh_index >= 0:
                for primitive in document["meshes"][mesh_index].get("primitives", []):
                    positions = accessor_values(
                        document, self.binary, primitive["attributes"]["POSITION"]
                    )
                    for point in positions:
                        x, y, z = transform(world, point)
                        for axis, value in enumerate((x, y, z)):
                            minimum[axis] = min(minimum[axis], value)
                            maximum[axis] = max(maximum[axis], value)
            for child in node.get("children", []):
                walk(child, world)

        identity = [1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0]
        scene = document.get("scenes", [{}])[document.get("scene", 0)]
        for root in scene.get("nodes", range(len(nodes))):
            walk(root, identity)
        size = tuple(maximum[axis] - minimum[axis] for axis in range(3))
        centre = tuple((maximum[axis] + minimum[axis]) / 2 for axis in range(3))
        return size, centre


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("paths", nargs="*", type=Path)
    parser.add_argument("--json", action="store_true", help="emit machine-readable output")
    parser.add_argument(
        "--all",
        action="store_true",
        help="audit every table*.glb under app/src/main/assets/models",
    )
    args = parser.parse_args()

    paths = list(args.paths)
    if args.all or not paths:
        paths = sorted(Path("app/src/main/assets/models").glob("table*.glb"))
        paths = [path for path in paths if path.name != "table.glb"]

    results = []
    failed = False
    for path in paths:
        audit = Audit(path)
        passed = audit.run()
        failed = failed or not passed
        results.append(
            {
                "path": str(path),
                "passed": passed,
                "checks": audit.checks,
            }
        )

    if args.json:
        print(json.dumps(results, indent=2))
    else:
        for result in results:
            print(f"{'PASS' if result['passed'] else 'FAIL'}  {result['path']}")
            for check in result["checks"]:
                mark = "  ok " if check["passed"] else "  FAIL"
                print(f"{mark} {check['id']:24s} {check['detail']}")
    return 0 if not failed else 1


if __name__ == "__main__":
    sys.exit(main())
