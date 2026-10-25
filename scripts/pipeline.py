#!/usr/bin/env python3
"""Pipeline bookkeeping: stage gates and the rebuild manifest.

Two jobs:

* ``check-stage <blend> <stage>`` — refuse to consume a .blend whose sidecar
  says it came from a different stage, or that changed after the marker.
* ``manifest`` — write ``var/rebuild/manifest.json`` with the provenance chain
  of every theme plus the numbers of the installed GLB.

Usage:
    python3 scripts/pipeline.py check-stage var/rebuild/theme_a.blend smooth
    python3 scripts/pipeline.py manifest
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

import glb_audit

MODELS = Path("app/src/main/assets/models")
REBUILD = Path("var/rebuild")
THEMES = ("a", "b", "c", "d")


def digest(path: Path) -> str:
    hasher = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            hasher.update(block)
    return hasher.hexdigest()


def read_sidecar(blend: Path) -> dict:
    path = Path(str(blend) + ".stage.json")
    if not path.is_file():
        raise SystemExit(f"missing stage sidecar: {path}")
    return json.loads(path.read_text())


def check_stage(blend: Path, stage: str) -> int:
    if not blend.is_file():
        print(f"missing blend: {blend}", file=sys.stderr)
        return 2
    info = read_sidecar(blend)
    if info.get("stage") != stage:
        print(
            f"{blend} is stage {info.get('stage')!r}, expected {stage!r}",
            file=sys.stderr,
        )
        return 1
    if info.get("blend_sha256") != digest(blend):
        print(f"{blend} changed after its stage marker was written", file=sys.stderr)
        return 1
    print(f"{blend}: stage={stage} ok")
    return 0


def manifest() -> int:
    entries = {}
    for theme in THEMES:
        blend = REBUILD / f"theme_{theme}.blend"
        glb = MODELS / f"table_{theme}.glb"
        entry: dict = {"blend": str(blend), "glb": str(glb)}
        if blend.is_file():
            try:
                info = read_sidecar(blend)
            except SystemExit:
                info = None
            if info:
                entry["stages"] = {
                    "sculpt": str(REBUILD / f"theme_{theme}_sculpt.blend"),
                    "smooth": {
                        "blend_sha256": info.get("blend_sha256"),
                        "source_sha256": info.get("source_sha256"),
                        "script_sha256": info.get("script_sha256"),
                        "blender": info.get("blender"),
                    },
                }
        if glb.is_file():
            document, _ = glb_audit.load_glb(glb)
            triangles = 0
            vertices = 0
            for mesh in document.get("meshes", []):
                for primitive in mesh.get("primitives", []):
                    attributes = primitive.get("attributes", {})
                    vertices += document["accessors"][attributes["POSITION"]]["count"]
                    if "indices" in primitive:
                        triangles += document["accessors"][primitive["indices"]]["count"] // 3
            entry.update(
                {
                    "glb_sha256": digest(glb),
                    "vertices": vertices,
                    "triangles": triangles,
                    "materials": len(document.get("materials", [])),
                    "nodes": len(document.get("nodes", [])),
                }
            )
        entries[theme] = entry

    payload = {
        "generated_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "themes": entries,
    }
    REBUILD.mkdir(parents=True, exist_ok=True)
    output = REBUILD / "manifest.json"
    output.write_text(json.dumps(payload, indent=2) + "\n")
    for theme, entry in entries.items():
        print(
            f"{theme}: {entry.get('triangles', '?')} triangles, "
            f"{entry.get('materials', '?')} materials, "
            f"glb={entry.get('glb_sha256', 'missing')[:12]}"
        )
    print(f"wrote {output}")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    check = subparsers.add_parser("check-stage")
    check.add_argument("blend", type=Path)
    check.add_argument("stage")
    subparsers.add_parser("manifest")
    args = parser.parse_args()
    if args.command == "check-stage":
        return check_stage(args.blend, args.stage)
    return manifest()


if __name__ == "__main__":
    sys.exit(main())
