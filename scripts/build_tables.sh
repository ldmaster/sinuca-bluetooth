#!/usr/bin/env bash
# Rebuild the four table GLBs with every gate armed.
#
# Order matters and so does failure handling: sculpt -> check stage -> smooth ->
# check stage -> export (rubric) -> audit GLB -> install. If any step fails the
# script stops and nothing is copied into assets/models.
#
#   ./scripts/build_tables.sh            # all four themes
#   THEMES="a" ./scripts/build_tables.sh # one theme
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"

BLENDER=${BLENDER:-/Applications/Blender.app/Contents/MacOS/Blender}
FORGE=${FORGE:-/Users/lucasdavi/IA/blender-forge/.venv/bin/forge}
PYTHON=${PYTHON:-python3}
THEMES=${THEMES:-a b c d}

# Source .blend per theme, captured from the generate_table.py runs.
# (macOS ships bash 3.2, so no associative arrays here.)
source_for() {
  case "$1" in
    a) echo "var/runs/run_20261023T165657Z_1a173f/blender/theme_a.blend" ;;
    b) echo "var/runs/run_20261023T165838Z_554780/blender/theme_b.blend" ;;
    c) echo "var/runs/run_20261023T165801Z_81856b/blender/theme_c.blend" ;;
    d) echo "var/runs/run_20261023T165842Z_0bf7e6/blender/theme_d.blend" ;;
    *) echo "" ;;
  esac
}

mkdir -p var/rebuild

for theme in $THEMES; do
  source=$(source_for "$theme")
  if [[ -z "$source" || ! -f "$source" ]]; then
    echo "missing source blend for theme $theme: ${source:-unset}" >&2
    exit 2
  fi

  sculpt="var/rebuild/theme_${theme}_sculpt.blend"
  smooth="var/rebuild/theme_${theme}.blend"

  echo "== theme $theme: sculpt"
  "$BLENDER" -b "$source" --python assets-plans/sculpt_apron.py -- "$sculpt"
  "$PYTHON" scripts/pipeline.py check-stage "$sculpt" sculpt

  echo "== theme $theme: smooth"
  "$BLENDER" -b "$sculpt" --python assets-plans/smooth_table.py -- "$smooth"
  "$PYTHON" scripts/pipeline.py check-stage "$smooth" smooth

  echo "== theme $theme: export"
  artifact=$("$PYTHON" scripts/forge_export.py \
    --theme "$theme" \
    --source "$smooth" \
    --forge "$FORGE" \
    --blender "$BLENDER")

  echo "== theme $theme: audit"
  "$PYTHON" scripts/glb_audit.py "$artifact"

  cp "$artifact" "app/src/main/assets/models/table_${theme}.glb"
done

cp app/src/main/assets/models/table_a.glb app/src/main/assets/models/table.glb
"$PYTHON" scripts/pipeline.py manifest
echo "tables rebuilt and installed"
