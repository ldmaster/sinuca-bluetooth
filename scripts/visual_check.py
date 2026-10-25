#!/usr/bin/env python3
"""Visual regression for the table previews.

Two independent checks, both deterministic and model-free:

* ``--styles`` — every pair of the five style previews of a theme must differ by
  more than ``--min-separation`` in the rail band. This is the check that catches
  five menu options rendering the same wood.
* ``--baseline DIR`` — each preview must stay close to a stored baseline
  (SSIM and changed-pixel ratio).

Run it with an environment that has Pillow (``pip install pillow`` or the
harnesskit venv).

    python3 scripts/visual_check.py --styles
    python3 scripts/visual_check.py --baseline var/visual-baseline
    python3 scripts/visual_check.py --write-stamp
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path

PREVIEWS = Path("app/src/main/assets/previews")
THEMES = ("a", "b", "c", "d")
STYLES = ("scan", "wear", "clean", "procedural", "atlas")
# Rail band of the preview crop, as fractions of the image.
RAIL_REGION = (0.15, 0.64, 0.85, 0.76)
STAMP = PREVIEWS / ".stamp"


def digest(path: Path) -> str:
    hasher = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            hasher.update(block)
    return hasher.hexdigest()


def sources() -> list[Path]:
    paths = [
        Path("app/src/main/java/com/ldmaster/sinuca/render/Shaders.kt"),
        Path("app/src/main/java/com/ldmaster/sinuca/render/GlbLoader.kt"),
        Path("app/src/main/java/com/ldmaster/sinuca/game/TableSurface.kt"),
        Path("app/src/main/java/com/ldmaster/sinuca/game/TableTexture.kt"),
    ]
    models = Path("app/src/main/assets/models")
    paths.extend(sorted(models.glob("table*.glb")))
    return [path for path in paths if path.is_file()]


def model_digest(path: Path) -> str:
    """Hash what the renderer consumes, not the file bytes.

    Re-exporting the same geometry from Blender produces different bytes (vertex
    order, float formatting) but the same picture. Hashing the structure keeps
    the preview stamp honest without forcing a recapture on every export.
    """
    import glb_audit

    document, _ = glb_audit.load_glb(path)
    audit = glb_audit.Audit(path)
    size, centre = audit.world_bounds()
    materials = sorted(
        (
            material.get("name", ""),
            tuple(material.get("pbrMetallicRoughness", {}).get("baseColorFactor", [])),
            material.get("pbrMetallicRoughness", {}).get("metallicFactor"),
            material.get("pbrMetallicRoughness", {}).get("roughnessFactor"),
        )
        for material in document.get("materials", [])
    )
    nodes = sorted(node.get("name", "") for node in document.get("nodes", []))
    primitives = sorted(
        (
            document["accessors"][primitive["attributes"]["POSITION"]]["count"],
            document["accessors"][primitive["indices"]]["count"]
            if "indices" in primitive
            else 0,
        )
        for mesh in document.get("meshes", [])
        for primitive in mesh.get("primitives", [])
    )
    payload = json.dumps(
        {
            "nodes": nodes,
            "materials": materials,
            "primitives": primitives,
            "size": [round(value, 4) for value in size],
            "centre": [round(value, 4) for value in centre],
        },
        sort_keys=True,
        default=str,
    ).encode("utf-8")
    return hashlib.sha256(payload).hexdigest()


def compute_stamp() -> dict:
    files = {
        str(path): (model_digest(path) if path.suffix == ".glb" else digest(path))
        for path in sources()
    }
    previews = {
        str(path): digest(path) for path in sorted(PREVIEWS.glob("p*.jpg"))
    }
    payload = {"files": files, "previews": previews}
    canonical = json.dumps(payload, sort_keys=True).encode("utf-8")
    payload["digest"] = hashlib.sha256(canonical).hexdigest()
    return payload


def write_stamp() -> int:
    STAMP.write_text(json.dumps(compute_stamp(), indent=2, sort_keys=True) + "\n")
    print(f"wrote {STAMP}")
    return 0


def check_stamp() -> int:
    if not STAMP.is_file():
        print(f"missing {STAMP}; run visual_check.py --write-stamp after capturing", file=sys.stderr)
        return 1
    stored = json.loads(STAMP.read_text())
    current = compute_stamp()
    if stored.get("digest") == current.get("digest"):
        print("previews match the current shaders and models")
        return 0
    changed = sorted(
        path
        for path in set(stored.get("files", {})) | set(current.get("files", {}))
        if stored.get("files", {}).get(path) != current.get("files", {}).get(path)
    )
    print("previews are stale; recapture them and run --write-stamp", file=sys.stderr)
    for path in changed:
        print(f"  changed: {path}", file=sys.stderr)
    missing = sorted(
        path
        for path in set(stored.get("previews", {})) ^ set(current.get("previews", {}))
    )
    for path in missing:
        print(f"  preview changed: {path}", file=sys.stderr)
    return 1


def load_vision():
    """Use harnesskit when it is importable, otherwise fall back to Pillow.

    CI has no checkout of harnesskit, and the project should not depend on a
    sibling repo to run its own gate.
    """
    try:
        from harnesskit.vision import pairwise_separation, ssim, visual_diff
    except ImportError:
        return _local_pairwise, _local_ssim, _local_visual_diff
    return pairwise_separation, ssim, visual_diff


def _local_visual_diff(baseline, current, *, threshold: float = 0.0, pixel_tolerance: int = 8):
    return _Diff(_changed_ratio(baseline, current, pixel_tolerance, None), threshold)


def _local_pairwise(images, *, pixel_tolerance: int = 8, region=None):
    paths = [Path(item) for item in images]
    if len(paths) < 2:
        raise SystemExit("pairwise separation needs at least two images")
    smallest = float("inf")
    closest = None
    for index, first in enumerate(paths):
        for second in paths[index + 1 :]:
            ratio = _changed_ratio(first, second, pixel_tolerance, region)
            if ratio < smallest:
                smallest = ratio
                closest = (str(first), str(second))
    return _Separation(
        len(paths),
        len(paths) * (len(paths) - 1) // 2,
        0.0 if smallest == float("inf") else smallest,
        closest,
    )


def _local_ssim(baseline, current, *, max_width: int = 512, window: int = 8) -> float:
    from PIL import Image

    left = _grayscale(baseline, max_width, Image)
    right = _grayscale(current, max_width, Image)
    width = min(left.width, right.width)
    height = min(left.height, right.height)
    if width < window or height < window:
        raise SystemExit("image smaller than the SSIM window")
    a = list(left.crop((0, 0, width, height)).getdata())
    b = list(right.crop((0, 0, width, height)).getdata())
    c1 = (0.01 * 255) ** 2
    c2 = (0.03 * 255) ** 2
    total = 0.0
    count = 0
    for top in range(0, height - window + 1, window):
        for left_x in range(0, width - window + 1, window):
            values_a: list[float] = []
            values_b: list[float] = []
            for row in range(top, top + window):
                base = row * width + left_x
                values_a.extend(a[base : base + window])
                values_b.extend(b[base : base + window])
            mean_a = sum(values_a) / len(values_a)
            mean_b = sum(values_b) / len(values_b)
            var_a = sum((value - mean_a) ** 2 for value in values_a) / len(values_a)
            var_b = sum((value - mean_b) ** 2 for value in values_b) / len(values_b)
            cov = sum(
                (value_a - mean_a) * (value_b - mean_b)
                for value_a, value_b in zip(values_a, values_b)
            ) / len(values_a)
            numerator = (2 * mean_a * mean_b + c1) * (2 * cov + c2)
            denominator = (mean_a**2 + mean_b**2 + c1) * (var_a + var_b + c2)
            total += numerator / denominator if denominator else 1.0
            count += 1
    return max(0.0, min(1.0, total / max(count, 1)))


def _grayscale(path, max_width: int, image_module):
    image = image_module.open(path).convert("L")
    if image.width > max_width:
        height = max(1, round(image.height * max_width / image.width))
        image = image.resize((max_width, height))
    return image


def _changed_ratio(baseline, current, pixel_tolerance: int, region) -> float:
    from PIL import Image, ImageChops

    base = Image.open(baseline).convert("RGB")
    curr = Image.open(current).convert("RGB")
    width = min(base.width, curr.width)
    height = min(base.height, curr.height)
    box = (0, 0, width, height)
    if region is not None:
        left, top, right, bottom = region
        box = (
            max(0, round(left * width)),
            max(0, round(top * height)),
            min(width, round(right * width)),
            min(height, round(bottom * height)),
        )
    base = base.crop(box)
    curr = curr.crop(box)
    diff = ImageChops.difference(base, curr)
    red, green, blue = diff.split()
    worst = ImageChops.lighter(ImageChops.lighter(red, green), blue)
    mask = worst.point(lambda value: 255 if value > pixel_tolerance else 0)
    return min(mask.histogram()[255] / max(base.width * base.height, 1), 1.0)


class _Diff:
    def __init__(self, changed: float, threshold: float) -> None:
        self.changed_ratio = changed
        self.passed = changed <= threshold


class _Separation:
    def __init__(self, images: int, pairs: int, minimum: float, closest) -> None:
        self.images = images
        self.pairs = pairs
        self.min_changed_ratio = minimum
        self.closest_pair = closest


def check_styles(min_separation: float) -> int:
    pairwise_separation, _, _ = load_vision()
    failed = False
    for theme in THEMES:
        images = [PREVIEWS / f"p{index}_{theme}.jpg" for index in range(1, 6)]
        missing = [str(path) for path in images if not path.is_file()]
        if missing:
            print(f"{theme}: missing previews {missing}", file=sys.stderr)
            failed = True
            continue
        report = pairwise_separation(
            [str(path) for path in images], region=RAIL_REGION, pixel_tolerance=6
        )
        passed = report.min_changed_ratio >= min_separation
        failed = failed or not passed
        closest = (
            f"{Path(report.closest_pair[0]).name} x {Path(report.closest_pair[1]).name}"
            if report.closest_pair
            else "-"
        )
        print(
            f"{'PASS' if passed else 'FAIL'} theme {theme}: min separation "
            f"{report.min_changed_ratio:.4f} (>= {min_separation}), closest {closest}"
        )
    return 0 if not failed else 1


def check_baseline(directory: Path, max_changed: float, min_ssim: float) -> int:
    _, ssim, visual_diff = load_vision()
    failed = False
    for theme in THEMES:
        for index in range(1, 6):
            current = PREVIEWS / f"p{index}_{theme}.jpg"
            baseline = directory / f"p{index}_{theme}.jpg"
            if not baseline.is_file():
                print(f"no baseline for {current.name}", file=sys.stderr)
                failed = True
                continue
            diff = visual_diff(baseline, current, threshold=max_changed)
            similarity = ssim(baseline, current)
            passed = diff.passed and similarity >= min_ssim
            failed = failed or not passed
            print(
                f"{'PASS' if passed else 'FAIL'} {current.name}: "
                f"changed={diff.changed_ratio:.4f} (<= {max_changed}), "
                f"ssim={similarity:.4f} (>= {min_ssim})"
            )
    return 0 if not failed else 1


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--styles", action="store_true", help="check style separation")
    parser.add_argument("--baseline", type=Path, help="compare previews to this directory")
    parser.add_argument("--write-stamp", action="store_true")
    parser.add_argument("--check-stamp", action="store_true")
    parser.add_argument("--min-separation", type=float, default=0.010)
    parser.add_argument("--max-changed", type=float, default=0.05)
    parser.add_argument("--min-ssim", type=float, default=0.90)
    args = parser.parse_args()

    if not any((args.styles, args.baseline, args.write_stamp, args.check_stamp)):
        parser.error("choose --styles, --baseline, --write-stamp or --check-stamp")

    status = 0
    if args.write_stamp:
        status |= write_stamp()
    if args.check_stamp:
        status |= check_stamp()
    if args.styles:
        status |= check_styles(args.min_separation)
    if args.baseline:
        status |= check_baseline(args.baseline, args.max_changed, args.min_ssim)
    return status


if __name__ == "__main__":
    sys.exit(main())
