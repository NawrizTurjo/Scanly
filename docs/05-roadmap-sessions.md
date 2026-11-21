# 05 — Roadmap with session estimates

## What a "session" means here
**1 session = one Opus 5.5 orchestrator session in Claude Code, about one 5-hour usage window, roughly 2–4 hours of your wall-clock time.** It ends with a green build, a commit, a tracker update, and an ntfy ping. Codex workers (gpt-6.2-sol) run *inside* the session, so they shorten wall-clock time and save Opus usage, but they don't add sessions. No Claude subagents (user rule, 2026-11-22).

Estimates are ranges because image-algorithm tuning and real-phone testing are unpredictable. If a session hits the Claude limit early, the claude-nap tool pauses it and resumes after the reset.

Legend for the worker split: **O** = Opus (judgment, algorithms, integration, verification) · **S** = Sonnet subagent (research, review, debugging, tests) · **C** = Codex worker (boilerplate, screens from spec, generators, unit tests).

---

## S0 — Desktop setup (you, about 1–2 h; Claude only verifies) · 0.5 session
Follow `08-desktop-setup.md`. Claude checks that `adb devices` shows the Pixel, that `java`, `python`, `codex`, and `git` are on PATH, and that a blank Compose template app builds and installs.

## S1 — Planning gate + skeleton + test data · 1 session
- O: re-read every doc, resolve `07` decisions with you, **wait for approval**.
- S: research the latest stable versions of every dependency and update `03`.
- C1: Gradle multi-module skeleton, version catalog, Hilt, an empty NavHost with 4 bottom tabs, and the design system (dynamic colour theme).
- C2: `tools/fixtures/` Python generators → synthetic scanner fixtures with ground-truth corners, plus the PDF fixture set (see `06`).
- O: CI-style local check script `tools/check.sh` (assemble + unit tests + lint).
- **Exit:** the app installs on the Pixel with a themed empty shell; `test-data/` is populated with a `README` and ground-truth JSON.

## Phase 1 — Functional scanner (manual crop) · 2 sessions
- C: Room schema and repositories, FileStore, Library screen with real data.
- C: CameraX preview + manual capture + photo-picker import + ScanSession persistence.
- O: crop editor with draggable corners and loupe; perspective warp (OpenCV) wired through `core:imaging`.
- C: review grid with drag-reorder/delete; export of PDF (PdfBox JPEG passthrough) and images to MediaStore and the share sheet.
- O: on-device run through screenshots; fix lifecycle and rotation issues.
- **Exit:** scan 5 pages by hand → crop → export PDF + JPGs → open them in Files and Drive. No watermark, of course.

## Phase 2 — Detection + enhancement engine (the hardest part) · 2–3 sessions
- O: `imaging-lab` prototypes for quad detection, the Document/BW/Auto/Gray filters, and shadow removal, tuned on fixtures with contact sheets.
- O: port to Kotlin `core:imaging`; C writes parity tests (Kotlin vs the Python reference).
- O + C: live detection overlay in the camera (ImageAnalysis), quad smoothing, auto-detected quads applied to captures and gallery imports.
- C: filter bottom sheet with live thumbnails and sliders; "apply to all".
- O: **real-world test round.** You scan about 30 real pages (notes, handouts, a textbook, under room lights) on the Pixel and Opus tunes against them. Add the hard cases to `test-data/real/` (private, gitignored if personal).
- S: (optional) a one-time benchmark against the ML Kit Document Scanner on the same pages.
- **Exit:** SC-1, SC-3, SC-6, and SC-9 acceptance pass, and the "white page" Document look is good on your real lecture notes.

### ★ Milestone: DAILY DRIVER (after about 6 sessions: S0 + S1 + P1 + P2 + the first half of P4)
The minimal version you described works end to end: batch scan → auto edges → white enhancement → PDF/images, plus single scans and appending to old documents. **Start using it daily from here.** Real use finds the bugs that matter.

## Phase 3 — Auto-capture, glare, scan modes, scanner extras · 3.5 sessions
- O: auto-capture state machine (stability + sharpness + cooldown/duplicate guard), settings for delay and sensitivity, haptics.
- O: glare detection and inpainting plus the warning flag; lab first, then port.
- O + C: ID card mode (guided front/back → A4 composite at true size).
- O + C: Whiteboard filter; Book mode spread split (no dewarp).
- O: ink removal (SC-15) and auto-deskew (SC-16), both prototyped in the lab first, then ported with parity tests.
- C: duplex batch (SC-13: phase toggle, interleave, F/B badges, swap pair) and the manual "Split here" action in the review grid.
- O: auto-split at blank sheets (SC-17): the blank detector is tuned on fixture piles in the lab; C does the grouped review-grid UI.
- **Exit:** a 20-page batch auto-captures with no duplicate or missed pages at a desk; ID card glare is visibly reduced; a book spread splits into 2 straight pages; a 5-sheet two-sided handout comes out in the right order; a pile of 3 documents with blank separators saves as 3 documents; pen marks come off a printed sheet.

## Phase 4 — Document management + quick entry points · 2 sessions
- C: folders, favourites, search (name/folder/date), rename/duplicate, recycle bin, auto-naming.
- O: **append pages to an existing document** at a chosen position (SC-7); document detail with reorder/rotate/re-filter; export sheet with a live size estimate.
- C: share target (SC-12: images → scan pipeline, PDF → Reader), Quick Settings tile + launcher shortcuts (SC-14), biometric app/folder lock (DM-9).
- **Exit:** DM-1…DM-7, DM-9, EX-1…EX-4, SC-12 and SC-14 pass. A WhatsApp photo shared to Paperless becomes a clean page in under 5 taps. (Do SC-7 and the export sheet early. They belong to the daily-driver milestone.)

## Phase 5 — Multi-tab PDF reader · 2 sessions
- O: ReaderViewModel and session LRU design, the PdfRenderer serialisation dispatcher, and the bitmap cache.
- C: tab strip UI, overflow, recent files, "Open with" intent filter, SAF persistable URIs.
- C: continuous scroll, zoom, page jump, thumbnails sheet, night mode.
- O: memory test on a 300-page PDF with 8 tabs open; tab state restore after process death.
- **Exit:** PT-1/3.6 acceptance pass; no out-of-memory or jank on the large fixture.

## Phase 5b — Reader features · 3.5–4 sessions
- S: at the start, check what the Pixel's `PdfRenderer` exposes (text search, selection, links) and decide native vs the PdfBox fallback (02 §7.2).
- O: shared page-space ↔ screen overlay (used by search highlights, selection, links, annotations later); save rule (02 §7.1).
- C: outline panel (RD-1) and the bookmark editor UI with drag-nest (RD-2); O does the PdfBox outline writer and preservation tests.
- O: auto chapter detection (RD-3), lab-tuned on the textbook fixtures and then ported.
- C: find in document + selection/copy (RD-4); links + back chip (RD-5); reading progress + the Continue-reading row (RD-7).
- O + C: crop-margins mode (RD-6); side-by-side panes in landscape (RD-8).
- **Exit:** RD-1…RD-8 pass; bookmarks made in Paperless show up in Chrome, Drive and GoodNotes; a textbook with no outline gets a usable suggested one.

## Phase 6 — PDF tools · 3.5 sessions
- C: merge, split, rotate/reorder, images→PDF, PDF→images (PdfBox and PdfRenderer), each as a 2–3 step flow.
- O: compression strategy (scanned vs generic PDFs) with honest reporting of the size change.
- C: unit tests on PDF fixtures (page counts, order, rotation, size reduction).
- O: port `tools/pdf-fixwidth` (PT-9) to `core:pdf` on PdfBox, plus the Tools tile, share target and EX-5 export switch. Run the Python tool on the same inputs as the oracle.
- C: page edits (PT-10), password add/remove (PT-11), page numbers (PT-14), metadata (PT-15), sanitize (PT-16), grayscale (PT-17), each with fixture tests.
- O: print layout (PT-12): the pure `impose()` function first, with exhaustive tests against PRD §3.5.1, then the layout preview UI (C) and the test-sheet generator. **You print one real duplex job** to confirm the order on paper.
- O + C: batch runner (PT-13) on WorkManager: job model, progress notification, per-file result list, resume after the app is killed.
- **Exit:** PT-2…PT-17 pass on the fixture set; a fixed PDF imported into GoodNotes on the iPad no longer slides sideways; the printed N-up stack cuts into the right page order.

## Phase 7 — Annotations · 1.5–2 sessions
- O: annotation data model and coordinate mapping (page space ↔ screen at any zoom), plus the flattening writer.
- C: annotate toolbar and drawing canvas (pen, highlighter, underline/strike via text-less line tools, note pins, eraser, undo/redo).
- O: on-device test of drawing accuracy at several zoom levels; flatten → reopen → verify.
- **Exit:** PT-7 pass; a flattened PDF shows the annotations correctly in Drive/Chrome's PDF viewer.

## Phase 7b — Backup + content search · 1 session
- O: `library.json` schema (versioned) and the restore merge logic (DM-8); C: the backup/restore WorkManager jobs and Settings UI.
- C: FTS indexer + the "Inside documents" search results (DM-10); O reviews ranking and snippet quality.
- **Exit:** back up → clear app data → restore gives an identical library; a word from a textbook page is found in under a second.

## Phase 8 — Polish + release build · 1–1.5 sessions
- O + C: motion (shared-element transitions, FAB/sheet animations), predictive back, haptics review, empty/error states, OSS licences screen, app icon.
- S: a performance pass with Baseline Profiles, checking recompositions and memory with 50-page batches.
- O: signed release APK (a local keystore, **backed up**), R8 shrinking, arm64-only.
- **Exit:** the release APK is installed as the daily app; a full regression checklist from `06` has been run on the Pixel.

---

## Totals

| Block | Sessions |
|---|---|
| S0 setup | 0.5 |
| S1 gate + skeleton + data | 1 |
| P1 functional scanner | 2 |
| P2 detection + enhancement | 2–3 |
| P3 auto-capture, glare, modes, scanner extras | 3.5 |
| P4 document management + quick entry points | 2 |
| P5 multi-tab reader | 2 |
| P5b reader features (RD-1…8) | 3.5–4 |
| P6 PDF tools (PT-2…17) | 3.5 |
| P7 annotations | 1.5–2 |
| P7b backup + content search | 1 |
| P8 polish + release | 1–1.5 |
| Buffer (bugs found by daily use, tuning) | 1.5–2 |
| **Total** | **≈ 26–29 sessions** |

Daily-driver milestone ≈ **session 6** (unchanged). Scanner-complete (P1–P4) ≈ **session 12–13**. Reader complete (P5 + P5b) ≈ **session 18–19**. Full v1 ≈ **26–29**.

The 2026-11-15 additions cost about +9.5 sessions. They all come after the daily driver, so they can be reordered or cut at any point without blocking real use.

## Pacing tips
- Two sessions a week → about 3–3.5 months for the full v1, and still about 3 weeks to the daily driver.
- If Codex usage is low (< 40%), run 3 workers on independent modules. Phases 4, 5b, 6 and 7b parallelise well (different modules and tools). Phases 2 and 3 don't: they're algorithm work that Opus should keep.
- Never start a phase on a red build. Each phase starts with `tools/check.sh` green on `main`.
