# CLAUDE.md — Paperless (Android document scanner + PDF toolkit)

Read this file at the start of every session, then read `tracker.md` to see where work stopped.

## What this project is
A personal, offline-only native Android app for one device (Pixel 8 Pro). It combines a CamScanner-style scanner, a local document library, and a lightweight PDF toolkit with a multi-tab reader. The full scope is in `docs/01-PRD.md`. The original brainstorms are in `docs/00-chatgpt-brainstorm-transcript.md` and `docs/00b-chatgpt-pdf-tools-transcript.md`; the PRD wins wherever the two differ.

## Hard constraints (never break these)
- Fully offline. No `INTERNET` permission in the app manifest. No backend, accounts, analytics, ads, or AI/LLM APIs.
- No watermark or branding of any kind on exported images or PDFs.
- Free, permissively licensed dependencies only (Apache-2.0, MIT, BSD). **No AGPL/GPL** (iText, MuPDF) and **no commercial SDKs** (Scanbot, Dynamsoft, Nutrient/PSPDFKit, Apryse). Every new dependency gets a row in `docs/03-dependencies-licensing.md` before it's added.
- Originals are immutable. Enhancement and cropping write processed copies plus parameters, so any page can be reprocessed later.
- Scanned documents are stored as page collections. A PDF is an export, never the source of truth.
- No heavy image work on the main thread. Use coroutines on `Dispatchers.Default`.
- Don't over-engineer. This is a single-user utility app: no microservices, no remote DB, no feature flags framework.

## Stack
Kotlin, Jetpack Compose, Material 3 (dynamic colour, edge-to-edge, predictive back), CameraX, OpenCV (official Maven AAR), Room, DataStore, Coil, Android `PdfRenderer` for viewing, PdfBox-Android for merge/split/compress/annotation flattening. Verify current stable versions at S1; don't trust versions remembered from training.

## Orchestration rules (the user's standing preferences; they apply to every session)
- **Opus 5.5 = orchestrator.** It plans, writes worker briefs, makes architecture decisions, designs and tunes the image-processing algorithms, integrates and merges, runs the build, verifies on the device, and updates the tracker. It keeps judgment-heavy work and hands off bulk work.
- **Autonomous mode (user's rule, 2026-11-22).** Work continuously through all phases until the app is done. Don't stop at milestones or wait for a "go". Make each decision yourself, pick the best option, and record it in `tracker.md`/docs. Only ask the user for things only they can do (real-phone photos and checks), via ntfy, without blocking other work.
- **Subagents (user's rule, 2026-11-22):** normally none, and bulk code goes to Codex workers. When Codex is exhausted or blocked, Opus does the work itself or uses **at most one Sonnet subagent at a time** (`model: "sonnet"`, effort medium, or high only when the task needs it). Never Opus subagents, and no workflows. Be frugal with tokens.
- **Codex workers** (`codex exec -m gpt-6.2-sol --dangerously-bypass-approvals-and-sandbox -C <worktree> ...`; use only this model): for boilerplate and low-risk code, such as Room entities/DAOs, Compose screens built from the spec, Gradle setup, Python fixture generators, and unit tests.
  - On this Windows machine Codex's sandbox always ends up read-only (via `codex exec` and the Codex plugin alike, tested 2026-11-22), so workers run with the sandbox off (the user approved this). Each worker's limits come only from its brief: stay inside its worktree, no git commands. Review every diff, and check `git status` in the main checkout after each batch for stray changes.
  - Before every batch, run `bash tools/codex-usage.sh`. Run **3 parallel workers while 5-hour usage < 40%, 2 while < 80%, 1 from 80% on.** At 5-hour usage ≥ 85%, dispatch nothing and wait for the reset. At weekly usage ≥ 85%, stop and ping the user. If the script fails or prints nothing, tell the user and don't assume there's headroom. Never infer limits by grepping logs.
- **Claude usage:** claude-nap (`%LOCALAPPDATA%\claude-nap`) pauses at the 5-hour limit, takes 30–50 min pace naps when usage burns too fast, and stops at **85% weekly**. Follow its `[claude-nap]` instructions when a tool call is denied.
  - Each worker gets its own **git worktree and branch** (`wt/<task>`) and its own module or package, so parallel edits never touch the same files. Put briefs on disk under `work/briefs/` (one shared `BRIEF_COMMON.md` plus one brief per task). In the command, the prompt goes before any variadic `-i` flags, and stdin comes from `/dev/null`.
  - Worker output is a first draft. Opus reviews the diff, then runs `./gradlew assembleDebug testDebugUnitTest lint` before merging. Run a **second pass** (a review or hardening pass by a different worker) on anything non-trivial.
- **Notifications:** ping ntfy after each milestone, at blockers, and whenever user input is needed (the user is often away), not just at the end: `curl -d "<msg>" -H "Title: Paperless" https://ntfy.sh/paperless-scanner-working-12103115`.
- **Session end:** commit, update `tracker.md` (what's done, what's next, any surprises), and ping ntfy.

## Verification rules
- "It compiles" doesn't mean "it works". Image features must pass the fixture tests in `test-data/` (see `docs/06-testing-strategy.md`) **and** be checked on the emulator.
- **No adb access to the real Pixel.** The user won't enable Developer options. Automated checks run on the emulator `Pixel_8_Pro_API37` (SDK at `G:\Android\Sdk`, Android 37, x86_64): start it with `emulator -avd Pixel_8_Pro_API37 -no-window -no-audio`, `adb install -r app/build/outputs/apk/debug/app-debug.apk`, drive it with `adb shell input ...`, then `adb exec-out screencap -p > shot.png` and actually look at the screenshot. Use `adb logcat` for crashes.
- The emulator has no webcam. Test camera flows with the virtual-scene camera (fixture images placed in the scene), import fixtures via `adb push`, or use a debug-only fake frame source. The user sideloads the APK on the Pixel for real-camera checks. Mark camera, lighting and performance features "emulator-verified, needs phone check" in the tracker until the user confirms them.
- Tune image algorithms in the Python harness (`tools/imaging-lab/`, opencv-python) on the fixture set, using contact sheets that Opus actually looks at. Port to Kotlin only after the results look right, then check that the Kotlin output matches the Python output on the same fixtures.
- Report honestly. If a feature only partly works (say, glare removal on large blown-out regions), write that in the tracker.

## Conventions
- Package: `dev.turjo.paperless` (it can change at the S1 gate).
- Multi-module Gradle with version catalog (`gradle/libs.versions.toml`). Module map in `docs/02-architecture.md`.
- `minSdk 31`, `targetSdk`/`compileSdk` = the latest stable version. ABIs: `arm64-v8a` (Pixel) + `x86_64` (emulator) only, to keep the OpenCV size down.
- Git: remote `origin` = private GitHub repo https://github.com/NawrizTurjo/Scanly.git (user request, 2026-11-22). Push `main` after each commit; never push gitignored secrets (keystore, real photos, app-testing, work/). Commit only on green builds. Each commit holds a meaningful, complete chunk of work (a feature slice, a module, a test suite); never commit a one- or two-line change on its own, and fold small fixes into the next real commit. Messages have a clear subject plus a body that says what changed and why. **No `Co-Authored-By` or other attribution lines.** The `main` branch always installs and runs.
