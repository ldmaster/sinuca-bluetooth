#!/usr/bin/env python3
"""Export a table through forge and refuse to continue when the gate fails.

`forge apply` already returns a non-zero exit code on a failed evaluation, but a
pipe (`forge apply ... | grep`) hides it. This wrapper captures the JSON, reads
`passed`, prints every failed check, and only then prints the artifact path on
stdout. Nothing else is allowed on stdout, so callers can do:

    artifact=$(python3 scripts/forge_export.py --theme a --source ...)

Usage:
    python3 scripts/forge_export.py --theme a --source var/rebuild/theme_a.blend
"""

from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
import sys
from pathlib import Path

EXPECTED_STAGE = "smooth"


def sidecar(blend: Path) -> dict:
    path = Path(str(blend) + ".stage.json")
    if not path.is_file():
        raise SystemExit(
            f"no stage sidecar for {blend}; run sculpt_apron.py and smooth_table.py first"
        )
    return json.loads(path.read_text())


def digest(path: Path) -> str:
    hasher = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            hasher.update(block)
    return hasher.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--theme", required=True, choices=("a", "b", "c", "d"))
    parser.add_argument("--source", required=True, type=Path)
    parser.add_argument("--plan", type=Path)
    parser.add_argument("--workspace", type=Path, default=Path("."))
    parser.add_argument("--rubric", type=Path, default=Path("specs/rubric.toml"))
    parser.add_argument("--forge", default="/Users/lucasdavi/IA/blender-forge/.venv/bin/forge")
    parser.add_argument(
        "--blender",
        default="/Applications/Blender.app/Contents/MacOS/Blender",
    )
    args = parser.parse_args()

    source = args.source
    if not source.is_file():
        print(f"source blend not found: {source}", file=sys.stderr)
        return 2

    info = sidecar(source)
    if info.get("stage") != EXPECTED_STAGE:
        print(
            f"{source} is stage {info.get('stage')!r}, expected {EXPECTED_STAGE!r}; "
            "exporting it would ship a table without the sculpted pockets",
            file=sys.stderr,
        )
        return 2
    if info.get("blend_sha256") != digest(source):
        print(f"{source} changed after the stage marker was written", file=sys.stderr)
        return 2

    plan = args.plan or Path(f"assets-plans/export_apron_{args.theme}.json")
    command = [
        args.forge,
        "apply",
        str(plan),
        "--workspace",
        str(args.workspace),
        "--source",
        str(source),
        "--rubric",
        str(args.rubric),
        "--blender",
        args.blender,
    ]
    completed = subprocess.run(command, capture_output=True, text=True, cwd=args.workspace)
    if completed.returncode != 0:
        print(completed.stdout[-4000:], file=sys.stderr)
        print(completed.stderr[-4000:], file=sys.stderr)
        return completed.returncode

    try:
        result = json.loads(completed.stdout)
    except json.JSONDecodeError:
        print("forge did not return JSON on stdout", file=sys.stderr)
        print(completed.stdout[-4000:], file=sys.stderr)
        return 2

    evaluation = result.get("evaluation") or {}
    if not result.get("passed") or not evaluation.get("passed"):
        print(f"export of theme {args.theme} failed the rubric", file=sys.stderr)
        for check in evaluation.get("checks", []):
            if not check.get("passed"):
                print(f"  FAIL {check['check_id']}: {check['detail']}", file=sys.stderr)
        return 1

    blender = result.get("blender") or {}
    artifacts = blender.get("artifacts") or {}
    artifact = artifacts.get(f"export_{len(artifacts) - 1}") or next(
        (value for key, value in artifacts.items() if key.startswith("export")),
        None,
    )
    if not artifact or not Path(artifact).is_file():
        print(f"forge reported success but produced no GLB: {artifacts}", file=sys.stderr)
        return 2

    stats = evaluation.get("stats") or blender.get("stats") or {}
    print(
        f"theme {args.theme}: passed, {stats.get('total_triangles', '?')} triangles, "
        f"{stats.get('material_count', '?')} materials, run {result.get('run_id')}",
        file=sys.stderr,
    )
    print(artifact)
    return 0


if __name__ == "__main__":
    sys.exit(main())
