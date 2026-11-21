# Tracker

Update at the end of every session: tick boxes, add a log row, and note surprises and the next step.

## Phase checklist
- [x] **S0** Desktop setup verified (0.5) · done 2026-11-22; emulator replaces the Pixel for adb checks (no USB debugging, user's choice)
- [ ] **S1** Gate decisions recorded · skeleton + design system · test-data with ground truth · check.sh (1)
- [ ] **P1** Functional scanner: Room/FileStore · camera + import · manual crop + warp · review grid · PDF/image export (2)
- [ ] **P2** Detection + enhancement: lab prototypes · Kotlin port + parity tests · live overlay · filters · shadow fix · real-photo tuning (2–3)
- [ ] ★ **Daily-driver milestone** (needs P4: append pages + export sheet)
- [ ] **P3** Auto-capture · glare · ID card · whiteboard · book split · duplex · ink removal · deskew · manual split · auto-split at blank sheets (3.5)
- [ ] **P4** Folders · favourites · search · rename/duplicate · recycle bin · append pages · export sheet · share-to · QS tile/shortcuts · app lock (2)
- [ ] **P5** Multi-tab reader: tabs + restore · LRU sessions · zoom/scroll · open-with · 300-page test (2)
- [ ] **P5b** Reader features: outline · bookmark editor · auto chapters · find/select/copy · links · crop margins · progress · side-by-side (3.5–4)
- [ ] **P6** Merge · split · compress · rotate/reorder · img↔pdf · same width · page edits · password · N-up print layout · page numbers · metadata · sanitize · grayscale · batch (3.5)
- [ ] **P7** Annotations + flattening (1.5–2)
- [ ] **P7b** Plain-file backup/restore · content search (1)
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
