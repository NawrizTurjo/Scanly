# Tracker

Update at the end of every session: tick boxes, add a log row, and note surprises and the next step.

## Phase checklist
- [x] **S0** Desktop setup verified (0.5) · done 2026-11-22; emulator replaces the Pixel for adb checks (no USB debugging, user's choice)
- [x] **S1** Gate decisions recorded · skeleton + design system · test-data with ground truth · check.sh (1) · done 2026-11-22
- [x] **P1** Functional scanner: Room/FileStore · camera + import · manual crop + warp · review grid · PDF/image export (2) · done 2026-11-22 on the emulator; real-camera check on the Pixel pending (user)
- [x] **P2** Detection + enhancement: lab prototypes · Kotlin port + parity tests · live overlay · filters · shadow fix · real-photo tuning (2–3) · done 2026-11-22; live-overlay fps on the Pixel pending (user)
- [x] ★ **Daily-driver milestone** (needs P4: append pages + export sheet) · reached 2026-11-22, pending the user's first Pixel run
- [x] **P3** Auto-capture · glare · ID card · whiteboard · book split · duplex · ink removal · deskew · manual split · auto-split at blank sheets (3.5) · done 2026-11-22 (emulator/tests); camera-based behaviour pending Pixel check
- [x] **P4** Folders · favourites · search · rename/duplicate · recycle bin · append pages · export sheet · share-to · QS tile/shortcuts · app lock (2) · done 2026-11-22 (tests + emulator UI check); share/tile/biometric pending Pixel check
- [x] **P5** Multi-tab reader: tabs + restore · LRU sessions · zoom/scroll · open-with · 300-page test (2) · done 2026-11-22 (emulator: Open in Reader renders; 300-page render test on emulator)
- [ ] **P5b** Reader features: outline · bookmark editor · auto chapters · find/select/copy · links · crop margins · progress · side-by-side (3.5–4)
- [x] **P6** Merge · split · compress · rotate/reorder · img↔pdf · same width · page edits · password · N-up print layout · page numbers · metadata · sanitize · grayscale · batch (3.5) · done 2026-11-22 (fixture tests; same width matches the PyMuPDF oracle; real duplex print check pending user)
- [ ] **P7** Annotations + flattening (1.5–2)
- [x] **P7b** Plain-file backup/restore · content search (1) · done 2026-11-22 (Robolectric round trip + fixture search; SAF/notifications pending device check)
- [ ] **P8** Polish · performance · signed release APK · full regression (1–1.5)

## Gate decisions (fill in at S1)
| # | Decision | Final |
|---|---|---|
| D1 | App name | **Scanly** (launcher label; project and package keep "paperless") (2026-11-22) |
| D2 | Package | **`dev.turjo.paperless`** (2026-11-22) |
| D3 | minSdk | **31** (default) |
| D4 | ML Kit benchmark | **No ML Kit in the app**; optional benchmark stays optional (default) |
| D5 | Navigation lib | **Navigation 3** 1.2.2 stable (S1 research, 2026-11-22) |
| D6 | Git hosting | **Local git only** for now (2026-11-22) |
| D7 | Annotations scope | **Any PDF** (default) |
| D8 | Default filter after a scan | **Document** (default) |
| D9 | Default export | **PDF, A4 auto-fit, Medium** (default) |
| D10 | Recycle bin | **Yes, 30 days** (default) |
| D11 | Export location | **`Documents/Paperless` + `Pictures/Paperless`** (default) |
| D12 | Release signing | **Local keystore**, backed up privately; set up in P8 (default) |
| D13 | Auto-split pile | **Yes, SC-17 in P3** (decided 2026-11-15) |
| D14 | Print layout default (cut each sheet / whole stack) | **Cut each sheet** (decided 2026-11-15) |
| D15 | External-file save behaviour | **Ask Save / Save as copy**, with an "always overwrite" setting (default) |

## Session log
| # | Date | Phase | Done | Partial / issues | Next |
|---|---|---|---|---|---|
| 0 | 2026-11-13 | Planning (laptop) | Plan folder created from the ChatGPT brainstorm | — | Copy to desktop → S0 |
| 0b | 2026-11-15 | Planning (laptop) | Added PT-9/EX-5 "same page width" (GoodNotes fix). Working Python reference with tests in `tools/pdf-fixwidth/` | Python version uses PyMuPDF (AGPL): desktop tool only, port to PdfBox in P6 | — |
| 0c | 2026-11-15 | Planning (laptop) | Added features from chat 2 (`docs/00b`): RD-1…8, PT-10…17, SC-12…16, DM-8…10. Estimate is now 26–29 sessions D13 = auto-split added (SC-17). D14 = cut each sheet. OCR dropped for good | — |
| 1 | 2026-11-22 | S0 (desktop) | Installed PowerShell 7.7, Codex CLI 0.171 (already logged in, Plus), Android Studio, scrcpy 5.0.1, Android SDK at `G:\Android\Sdk` (API 37.0, build-tools 37.0.0, platform-tools, emulator, x86_64 image), Python lab venv `.venv` (OpenCV 5.1). User env vars: ANDROID_HOME, JAVA_HOME (Studio JBR 25), GRADLE_USER_HOME=`G:\.gradle`, ANDROID_AVD_HOME. AVD `Pixel_8_Pro_API37` boots in ~39 s (WHPX). Defender exclusions added by user. claude-nap 0.4.0 (pace naps, 85% weekly stop, ntfy) built in `G:\claude-nap` (branch feat/pace-naps, local) and installed with enforce on. Gate decisions D1–D15 recorded. Git initialised (local) | **Surprises:** Claude CLI 2.1.195 sent no usage % (needs ≥ 2.1.234, updated to 2.1.330). `sdkmanager` now uses `platforms/android-37.0` package names. User won't enable USB debugging → emulator-only adb checks; no webcam either | S1 |
| 2 | 2026-11-22 | S1 | 10-module Compose skeleton (AGP 9.5.2, Kotlin 2.4.21, Nav3, Hilt, DataStore settings, dynamic-colour theme, 4 tabs) verified on the emulator; test-data generators (48 scanner + 10 special photos, 23 PDFs, exact truth, deterministic); imaging lab: detection 58/58 within 2% diag (mean 0.026%), warp SSIM ≥ 0.955, shadow fix −83% non-uniformity, glare warning 2/2 with 0 false alarms; P1 parity goldens; check.sh green | Low-light Document output readable but slightly grainy (SSIM 0.39). Synthetic fixtures are easier than real photos: real-photo tuning still due in P2 | **Surprises:** Codex sandbox read-only on Windows → workers run sandbox-off (user OK). Compose BOM 2026.11.01 needs compileSdk 37.1. Studio JBR 25 crashes Gradle C2 → JDK 21. DataStore double-write test fails on Windows (rename over open file) | P1 |
| 3 | 2026-11-22 | P1 | Data layer (Room v1, page files by id, drafts = scan sessions), Kotlin imaging port (parity with Python: corners < 0.01 px, 14/15 filters byte-identical), page processing queue (auto-crop + Document filter in the background), camera (CameraX, batch/single, flash, gallery import), review grid, crop editor with loupe, filter editor with live preview, document detail, export sheet (PDF/JPG/PNG, A4/Letter/Auto, quality + size estimate, share, MediaStore). Full flow verified on the emulator: import 3 fixtures + 2 captures → auto-crop → edit → save → export 5-page A4 PDF to Documents/Paperless (no watermark, metadata title/producer only) | Real camera untested (emulator fake camera only). Page whose processing failed exports raw original. Orphan page files possible if killed mid-delete. Debug APK 151 MB (x86_64 OpenCV unstripped; arm64 lib 32.7 MB → release est. 45–50 MB) | **Surprises:** a finished Codex worker hung (child Gradle daemon) → added tools/watch-workers.sh polling. Background `&` launches died → one background call per worker | P2 |
| 4 | 2026-11-22 | P2 | Live overlay: luma-only detector (Kotlin parity 0.005 px, 11 ms/frame on emulator) + median smoother + CameraX ImageAnalysis + animated quad (blue→green on stability), bound in :app. Release build arm64-only, NDK pinned, 44.8 MB. Real-photo tuning on 63 user photos (Pixel originals + friend's handwritten notebooks): directional text-suppressed edge support, frame-clipped sides, paper-hull IoU → wrong quads 12 → 3 of 63; the rest correct or full-image fallback. Page-tool prototypes (deskew 0.025° mean, blank detection safe on one-line/pencil pages, ink removal 99.6%, book gutter 0.04%) + goldens | Live overlay not visually verified (emulator camera can't show a document; image-file mode zooms). 3/63 real photos still get a slanted side; frame-filling pages fall back to full image. Telegram copies vs originals: originals used | **Surprises:** handwriting in blue ink is (correctly) removed by blue ink removal; emulator virtual-scene camera needs a GPU (black when headless) | P3 |
| 5 | 2026-11-22 | P3 + P4 | P3: detector synced with real-photo tuning, PageTools (deskew/blank/ink/gutter) ported with exact parity, auto-capture controller (stability + relative sharpness + fingerprint duplicate guard), duplex interleave, splitGroups + review grouping/split/join/save-per-group, deskew + ink-removal editor controls, ID card (aspect prior + true-size A4 composite), Book split (gutter → two pages), Whiteboard defaults, Room v2 (deskew/inkRemoval) with migration test. P4: folders, filtered search with date shortcuts, multi-select, duplicate, recycle bin + 30-day purge, share-to (images → review), QS tile, launcher + dynamic shortcuts, app lock (platform BiometricPrompt), auto-name pattern + auto-capture settings | Deskew 0.6–1.2 s/page on emulator (background). Orphan page files possible after a failed delete. Camera-driven features (auto-capture, ID/book capture, duplex) verified by unit tests only | **Surprises:** claude-nap took its first pace nap (30 min at 40% / 16 pts ahead) and resumed cleanly. Room MigrationTestHelper breaks on Windows paths under Robolectric → hand-built v1 DB test | P5 |
