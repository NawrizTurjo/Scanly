# 06 — Testing strategy

A green build proves nothing about edge detection or enhancement. Every image feature needs **fixtures with ground truth**, **automated metrics**, and **a check on the Pixel**.

## 1. Test dataset (`test-data/`, kept out of the APK)

```
test-data/
├── scanner/
│   ├── synthetic/        # generated: clean page rendered onto backgrounds with a known homography
│   │   ├── clean/  skewed/  shadows/  glare/  low-light/  busy-background/  handwriting/  tables/
│   │   └── truth.json    # per image: 4 ground-truth corners, source page id
│   ├── public/           # small, permissively licensed real photos (licence recorded per file)
│   └── real/             # YOUR Pixel captures (gitignored if personal; anonymised ones may be committed)
├── special/
│   ├── id-cards/         # synthetic cards only (fake name/photo); never real IDs
│   ├── books/            # spreads with curvature
│   └── whiteboards/
├── pdf/
│   ├── single-page.pdf  multipage-15.pdf  large-300.pdf  image-only-scan.pdf
│   ├── text-vector.pdf  mixed.pdf  rotated-pages.pdf  mixed-page-sizes.pdf
│   ├── textbook-outline.pdf   # 3-level existing outline + internal links (RD-1/2/5)
│   ├── textbook-no-outline.pdf # headings by font size, no outline: auto-chapter target (RD-3)
│   ├── encrypted-aes256.pdf    # known password (PT-11)
│   ├── with-js-attachment.pdf  # JavaScript OpenAction + embedded file (PT-16)
│   ├── wide-margins.pdf        # big white margins (RD-6)
│   ├── batch-folder/           # 10 mixed PDFs incl. 1 corrupt file (PT-13)
│   └── truth.json        # page counts, sizes, rotations, outline trees, search hits, margin boxes
└── README.md             # how each set was made, licences, regeneration command
```

**Synthetic generator** (`tools/fixtures/`, a Codex task in S1): render text, tables, and handwriting-font pages (reportlab/PIL) → warp them onto background photos or textures with random homographies (so the corners are known exactly) → add a shadow gradient, specular blobs, noise, blur, and low exposure. Every image comes with its true corners. That makes detection testing a number, not a guess.

**PDF fixtures:** generated with reportlab/pypdf (vector text, mixed sizes, rotation flags, 300 pages) plus an image-only scan PDF built from the synthetic pages.

**Real captures (Phase 2):** you shoot about 30 pages on the Pixel. Include lecture notes, a printed handout, a textbook spread, a glossy page, the desk lamp at a bad angle, a dark room, a page on a wooden or patterned table, and an ID-like laminated card. Opus marks the corners for about 10 of them (in a small labelling script) to get real-world ground truth.

## 2. Automated tests

| Area | Test | Pass criterion |
|---|---|---|
| Detection | Corner error vs truth.json | Mean error < 1.5% of the diagonal; ≥ 90% of images detected (synthetic); ≥ 80% (real labelled) |
| Warp | Rectified output vs the source page | SSIM ≥ 0.85 on `clean`/`skewed` |
| Shadow fix | Background uniformity (std dev of the paper region) | Drops ≥ 50% vs no fix; text stroke contrast is not reduced |
| Glare | Saturated-pixel area after the fix | Reduced; large blown regions → `glareWarning = true` |
| Filters | Golden-image comparison (Python reference vs Kotlin) | Per-pixel mean abs diff < 3/255 |
| Auto-capture | State machine unit tests with scripted quad sequences | Captures once per stable page; no duplicates; respects cooldown |
| Data | Room DAO tests (Robolectric/in-memory) | Insert, reorder, append-at-index, recycle bin restore |
| PDF | Merge/split/rotate/compress on fixtures | Page counts and order exact; rotation kept; compress shrinks image-only PDFs |
| PDF width (PT-9) | Kotlin `PageWidthNormalizer` vs the Python oracle `tools/pdf-fixwidth/fixwidth.py` on `mixed-page-sizes.pdf`, `rotated-pages.pdf` and the real `tools/pdf-fixwidth/examples/7_Production-before-fix.pdf` | All widths equal to the target; page widths and heights match the oracle within 0.5 pt; text unchanged; each scaled page renders within 2% pixel difference of the original rendered at the same scale; links, outline and annotation positions kept; an already-uniform file is a no-op |
| Preservation | After every PDF-writing tool, compare with the input: outline tree, link targets, annotations, page text, metadata | Everything the tool didn't intend to change is identical |
| Outline (RD-1/2) | Read → edit (add, nest, rename, delete) → save → re-read with PdfBox **and** pypdf | Same tree in both; opens in Chrome's viewer with the bookmarks shown |
| Auto chapters (RD-3) | Suggestion vs the truth outline of `textbook-no-outline.pdf` | ≥ 90% of true headings found, ≤ 10% false ones, levels correct for ≥ 80% |
| Search/select (RD-4, DM-10) | Known words → hit count and page list vs truth.json; FTS query results | Exact counts; results rank the right page first |
| Links (RD-5) | Every internal link in `textbook-outline.pdf` resolves to the truth page; back-stack round trip | 100% |
| Margins (RD-6) | Detected boxes vs truth boxes | IoU ≥ 0.9 |
| Print layout (PT-12) | `impose()` unit tests: 1–13 pages × every layout × both flips, vs the PRD §3.5.1 formulas; a simulated cut-and-stack of the produced sheets | The simulated cut/stack/fold gives pages 1…n in order, with blanks only at the end |
| Page edits (PT-10) | Delete/extract/insert/duplicate sequences on fixtures | Expected page sequence (checked by rendered page fingerprints) |
| Password (PT-11) | Encrypt → open without a password fails → open with it works → remove → opens freely | Pass; the encryption is AES-256 |
| Sanitize (PT-16) | `with-js-attachment.pdf` → output | No `/JavaScript`, `/OpenAction`, `/AA` or `/EmbeddedFiles`; page content unchanged |
| Page numbers / metadata / grayscale | Text extraction finds the stamps at the right pages; metadata round trip; output has no colour images | Pass |
| Batch (PT-13) | `batch-folder/` with compress and same-width | 9 done, 1 failed with a readable reason; survives killing the app mid-run |
| Duplex (SC-13) | `interleave()` with even/odd/mismatched counts | Correct order; a mismatch raises a warning, never a silent guess |
| Auto-split (SC-17) | Synthetic piles: 3 documents with blank separators, plus traps: a near-empty page with one line of text, a faint pencil page, a duplex pile with blank backs | Correct groups for every pile; zero real pages dropped (a missed split is acceptable, losing a page is not) |
| Ink removal (SC-15) | Synthetic printed page + blue/red strokes | ≥ 95% of ink pixels removed; black text SSIM ≥ 0.95 vs the clean page |
| Deskew (SC-16) | Pages rotated by known angles −5…+5° | Residual angle < 0.3°; no rotation applied when confidence is low |
| Backup (DM-8) | Back up → wipe the app data → restore | Library, page params, bookmarks, annotations and reading state identical |
| App lock (DM-9) | Instrumented test: locked folder hidden from search, recents, share | Pass |
| Reader | ViewModel tests: open 8 tabs, switch, close, restore | Each tab keeps page/zoom; ≤ 2 renderers open |
| Annotations | Coordinate mapping round trip at zoom 1×/2×/4× | Error < 1 PDF point |
| UI | Compose UI tests for critical flows (scan → save → export) | Pass on the emulator |

`tools/check.sh` runs `./gradlew assembleDebug testDebugUnitTest lint` plus the Python lab regression (`imaging-lab/run_regression.py`, which prints the metrics table). Opus runs it before every merge.

## 3. On-device checks (Pixel 8 Pro)
- Install via `adb install -r`, then capture screenshots with `adb exec-out screencap -p`. Opus *looks at* each screenshot.
- Live camera behaviour (overlay smoothness, auto-capture feel) can't be fully scripted. Opus writes a short manual checklist per phase, you run it, and you report back (or send screenshots or a screen recording via `adb shell screenrecord`).
- Performance: `adb shell dumpsys gfxinfo <pkg>` for jank, and the Android Studio profiler for memory during a 50-page batch and the 300-page reader test.

## 4. Release regression checklist (Phase 8)
One pass through every PRD acceptance row on the Pixel, including printing one real PT-12 duplex job and checking the page order on paper. The result is recorded in `tracker.md`.
