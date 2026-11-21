#!/usr/bin/env bash
# The merge gate: build, unit tests, lint, plus the Python fixture/lab checks when present.
# Usage: bash tools/check.sh            (from the repo root or any subfolder)
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

: "${ANDROID_HOME:=G:/Android/Sdk}"
: "${JAVA_HOME:=C:/Program Files/Java/jdk-21}"
: "${GRADLE_USER_HOME:=G:/.gradle}"
export ANDROID_HOME JAVA_HOME GRADLE_USER_HOME

# Worktrees don't carry the gitignored venv, so fall back to the main checkout's one.
PY=""
for cand in ".venv/Scripts/python.exe" ".venv/bin/python" "G:/Paperless-Scanner/.venv/Scripts/python.exe"; do
  if [ -x "$cand" ]; then PY="$cand"; break; fi
done

step() { printf '\n=== %s ===\n' "$1"; }

step "Gradle: assembleDebug testDebugUnitTest lint"
./gradlew --console=plain assembleDebug testDebugUnitTest lint

if [ -d tools/fixtures/tests ]; then
  step "Fixture generator tests"
  if [ -z "$PY" ]; then echo "No Python venv found (.venv)."; exit 1; fi
  "$PY" -m unittest discover -s tools/fixtures/tests -v
fi

if [ -f tools/imaging-lab/run_regression.py ]; then
  step "Imaging lab regression: detection + warp"
  "$PY" tools/imaging-lab/run_regression.py
  step "Imaging lab regression: enhancement"
  "$PY" tools/imaging-lab/run_enhance.py
fi

if [ -d tools/pdf-fixwidth/tests ] && [ -n "$PY" ] && "$PY" -c "import fitz" 2>/dev/null; then
  step "pdf-fixwidth reference tests"
  "$PY" -m unittest discover -s tools/pdf-fixwidth/tests
fi

step "Manifest guard: no INTERNET permission (merged manifest, libraries included)"
merged=$(find app/build/intermediates -name AndroidManifest.xml -path "*merged_manifest*" 2>/dev/null | head -1)
if [ -z "$merged" ]; then echo "FAIL: merged manifest not found"; exit 1; fi
if grep -n "android.permission.INTERNET" "$merged"; then
  echo "FAIL: INTERNET permission in $merged"; exit 1
fi
echo "ok ($merged)"

printf '\nCHECK PASSED\n'
