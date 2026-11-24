#!/usr/bin/env bash
# The merge gate: build, unit tests, lint, plus the Python fixture/lab checks when present.
# Usage: bash tools/check.sh            (from the repo root or any subfolder)
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

: "${ANDROID_HOME:=${LOCALAPPDATA:-$HOME}/Android/Sdk}"
: "${JAVA_HOME:=C:/Program Files/Java/jdk-21}"
: "${GRADLE_USER_HOME:=$HOME/.gradle}"
export ANDROID_HOME JAVA_HOME GRADLE_USER_HOME

# Worktrees don't carry the gitignored venv, so fall back to the main checkout's one.
PY=""
for cand in ".venv/Scripts/python.exe" ".venv/bin/python"; do
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
  step "Imaging lab regression: desk scenes (clutter, notebooks, clipped pages)"
  "$PY" tools/imaging-lab/run_regression.py --set desk
  step "Imaging lab regression: enhancement"
  "$PY" tools/imaging-lab/run_enhance.py
  step "Imaging lab regression: page tools (deskew, blank, ink removal, book gutter)"
  "$PY" tools/imaging-lab/run_pagetools.py
fi

if [ -d tools/pdf-fixwidth/tests ] && [ -n "$PY" ] && "$PY" -c "import fitz" 2>/dev/null; then
  step "pdf-fixwidth reference tests"
  # A standalone script (exits 0 on success), not a unittest module.
  "$PY" tools/pdf-fixwidth/tests/test_fixwidth.py
fi

step "Network guard: INTERNET only for the updater (user decision 2026-11-25)"
merged=$(find app/build/intermediates -name AndroidManifest.xml -path "*merged_manifest*" 2>/dev/null | head -1)
if [ -z "$merged" ]; then echo "FAIL: merged manifest not found"; exit 1; fi
grep -q "android.permission.INTERNET" "$merged" || { echo "FAIL: the updater needs INTERNET in $merged"; exit 1; }
# Network code may live only in the updater; anything else that opens a connection fails the check.
net=$(grep -rlE 'HttpURLConnection|openConnection\(|java\.net\.Socket|okhttp3|io\.ktor' --include=*.kt app core feature \
  | grep -v "/build/" | grep -v "feature/settings/src/main/kotlin/dev/turjo/paperless/feature/settings/UpdatesViewModel.kt" || true)
if [ -n "$net" ]; then echo "FAIL: network code outside the updater:"; echo "$net"; exit 1; fi
echo "ok ($merged)"

printf '\nCHECK PASSED\n'
