# 02 — Architecture

This is a proposal. Opus confirms or adjusts it at the S1 gate, and should keep it lean.

## 1. Module map (Gradle multi-module)

```
paperless/
├── app/                      # MainActivity, NavHost, DI wiring, theme bootstrap
├── core/
│   ├── designsystem/         # M3 theme, dynamic colour, typography, shared components
│   ├── model/                # Plain Kotlin data classes (Document, Page, Quad, FilterSpec, ...)
│   ├── data/                 # Room DB, DAOs, repositories, FileStore (app-private files), DataStore prefs
│   ├── imaging/              # OpenCV pipeline: detect, warp, enhance, shadow, glare, modes. No Android UI deps
│   └── pdf/                  # PdfRenderer wrapper, PdfBox ops (merge/split/compress/flatten), export writers
├── feature/
│   ├── library/              # Home, folders, favourites, search, recycle bin
│   ├── camera/               # CameraX preview, live detection overlay, auto-capture, batch session
│   ├── editor/               # Page review grid, crop/corner editor, filters, reorder, export sheet
│   ├── reader/               # Multi-tab PDF reader
│   ├── tools/                # Merge / split / compress / img↔pdf screens
│   └── annotate/             # Ink/highlight/note layers + flattening UI
├── tools/
│   ├── imaging-lab/          # Python + opencv-python: algorithm prototyping, contact sheets
│   ├── pdf-fixwidth/         # Working Python reference for PT-9/EX-5 (uniform page width) + tests; desktop-only
│   ├── fixtures/             # Python generators for synthetic test data
│   └── codex-usage.sh
├── test-data/                # Fixtures (NOT packaged into the APK); see 06-testing-strategy.md
└── work/briefs/              # Worker briefs (gitignored, or kept out of history if large)
```

Dependency direction: `feature/* → core/*`, `core/data → core/model`, and `core/imaging` depends only on `core/model` plus OpenCV. Feature modules never depend on each other; navigation is wired in `:app`.

DI: **Hilt**. It's well known and every worker model handles it reliably.
State: one ViewModel per screen, `StateFlow<UiState>`, unidirectional data flow. No MVI framework.

## 2. Data model (Room)

```
Folder(id, name, parentId?, createdAt)
Document(id, title, folderId?, isFavorite, createdAt, updatedAt, coverPageId?, deletedAt?)  # deletedAt → recycle bin
Page(id, documentId, index, mode[NORMAL|ID_FRONT|ID_BACK|BOOK_L|BOOK_R|WHITEBOARD],
     originalPath, quad(8 floats, nullable = full image), rotation(0/90/180/270),
     filter[ORIGINAL|AUTO|DOCUMENT|BW|GRAY|WHITEBOARD], brightness, contrast,
     shadowFix:Boolean, glareFix:Boolean, processedPath?, processedHash?, glareWarning:Boolean)
ScanSession(id, startedAt, targetDocumentId?, insertAt?)   # survives process death
ExternalPdf(id, uri, displayName, lastOpenedAt, persistedPermission:Boolean)
Annotation(id, pdfKey, pageIndex, type[INK|HIGHLIGHT|UNDERLINE|STRIKE|NOTE], payloadJson, createdAt)
PageText(pdfKey, pageIndex, text)                 # FTS4, DM-10
MarginBox(pdfKey, pageIndex, l, t, r, b)          # RD-6 cache
BatchJob(id, op, paramsJson, state, createdAt) + BatchItem(jobId, input, state, message)   # PT-13
Folder.isPrivate:Boolean (DM-9) · Page.inkRemoval, deskew (SC-15/16) · ScanSession.duplexPhase, autoSplit (SC-13/17)
ReaderTab(id, pdfKey, order, isActive, page, zoom, scrollX, scrollY, viewMode)
```
- `pdfKey` is either an `ExternalPdf.id` or a `Document.id` whose PDF was exported or rendered.
- `processedPath` is a cache. If the stored params hash differs from `processedHash`, the page is regenerated.

Files: `filesDir/docs/<docId>/orig/<pageId>.jpg`, `.../proc/<pageId>.jpg`, `.../thumb/<pageId>.webp`.

## 3. Image-processing pipeline (`core:imaging`)

One shared pipeline, with mode-specific parameters:

```
Original (immutable, full res)
  │
  ├─► detectQuad(downscaled ~1000px)  ── Canny + morph close + contours + approxPolyDP
  │        fallback: HoughLinesP / LSD line grouping; score by area, convexity, angle sanity
  ├─► warpPerspective(full res, quad) → target size from quad side lengths (or snap to A4)
  ├─► [glareFix]  detect specular (high V, low S) → mask → inpaint small regions; flag large ones
  ├─► [shadowFix] background estimate (dilate + large median blur) → divide → normalise
  ├─► filter preset
  │      AUTO      : CLAHE on L channel + gentle white balance
  │      DOCUMENT  : shadowFix result → contrast stretch → slight unsharp mask (white page, grey-level text)
  │      BW        : adaptive threshold (Gaussian, block ~ page_width/40) + despeckle
  │      GRAY      : grayscale + contrast stretch
  │      WHITEBOARD: per-channel background division + saturation boost
  ├─► brightness/contrast sliders (final linear adjust)
  └─► encode JPEG (export quality preset) → processedPath
```

Live detection (camera): CameraX `ImageAnalysis` at about 640×480 YUV with `STRATEGY_KEEP_ONLY_LATEST`. Use only the Y plane (grayscale): detect → map the quad to preview coordinates → draw an animated overlay. Smooth the quad over the last 3–5 frames.

Auto-capture state machine:
```
SEARCHING ─(quad found)→ TRACKING ─(corners moved < 1.5% diag for 700ms AND sharpness ok)→ CAPTURING
CAPTURING ─(ImageCapture full-res done)→ COOLDOWN ─(quad lost OR content changed by > threshold)→ SEARCHING
```
Sharpness = variance of the Laplacian inside the quad. "Content changed" = a 16×16 grayscale thumbnail difference, which stops the same page being captured twice.

Book mode: detect the spread quad → find the gutter (the vertical projection minimum of darkness/shading near the centre) → split → warp each half. Dewarping is v2.

ID card mode: card-aspect quad prior (1.586), two guided captures, composited onto A4 at 300 dpi, true size.

**Development method:** every algorithm is prototyped first in `tools/imaging-lab` (Python/opencv-python) against `test-data/`. It produces contact sheets (input | detected quad | output) that Opus inspects. Only then is it ported to Kotlin. A Kotlin unit test then compares its output against the Python reference on the same fixtures (corner error and SSIM within tolerance). For JVM-side tests without a device, use the `org.openpnp:opencv` desktop jar (it bundles the Windows natives), or run as an instrumented test on the Pixel. Opus picks one at Phase 2.

## 4. PDF layer (`core:pdf`)
- **Viewing:** `android.graphics.pdf.PdfRenderer` (built in, fast, read-only). One renderer per *active* tab. Page bitmaps go in an LRU cache sized to about 1/8 of the app memory class. Render tiles at the current zoom when zoomed past 2× (a v1.1 polish item if it takes too long).
- **Writing scanned docs:** `android.graphics.pdf.PdfDocument` (draw processed bitmaps) or PdfBox (better JPEG passthrough, so no re-encode bloat). Prefer **PdfBox with `JPEGFactory`**, which embeds the JPEG bytes directly and keeps files small.
- **Merge/split/rotate/reorder:** PdfBox-Android (`PDFMergerUtility`, `Splitter`, page import).
- **Compress:** for scanned PDFs, re-encode the embedded images at the target DPI and quality. For other PDFs, try image re-compression; if the gain is under 5%, say so instead of producing a bigger file.
- **Annotation flattening:** draw ink paths and highlights into a page content stream (PdfBox `PDPageContentStream`, append mode) and save as a *new* file. The original stays untouched.
- **Uniform page width (PT-9, EX-5):** port `tools/pdf-fixwidth/fixwidth.py` to `core:pdf` as `PageWidthNormalizer` on PdfBox-Android. The algorithm, already proven on the desktop:
  1. Displayed width = CropBox (clipped to MediaBox) with `/Rotate` applied. The target is the most common width unless the user picks another.
  2. For each page whose width differs by more than 0.5 pt: s = target / width. Prepend the content stream `q s 0 0 s -x0·s -y0·s cm` and append `Q` (PdfBox `PDPageContentStream` with `AppendMode.PREPEND`/`APPEND`; wrap the old content in q/Q first). Set MediaBox = `[0 0 w·s h·s]` and remove CropBox, BleedBox, TrimBox and ArtBox.
  3. Map every annotation on that page into the new space: `Rect`, `QuadPoints`, `InkList`, `Vertices`, `L`, `CL` → (v − origin)·s; `RD` → v·s.
  4. Leave pages already at the target width untouched. Write a **new** file. Outline, page labels and named destinations survive because the page objects are reused.
  The Python version is a desktop dev tool only (PyMuPDF is AGPL and must never ship). It doubles as the test oracle: the Kotlin output must match it in page widths, and in rendered pixels within tolerance.

## 5. Multi-tab reader design
```
ReaderViewModel
  tabs: StateFlow<List<TabState>>     # persisted to Room (ReaderTab) on change, debounced
  activeTabId
  sessions: LRU(maxOpen = 2) of PdfSession(pdfKey → ParcelFileDescriptor + PdfRenderer)
TabState(pdfKey, title, page, zoom, scrollX, scrollY, viewMode, dirtyAnnotations)
```
- Switching tabs saves the outgoing tab's scroll and zoom, opens (or reuses) a session for the incoming tab, and restores its state.
- The LRU closes the renderer and file descriptor of the least recently used session, so 10 tabs ≠ 10 open renderers.
- `PdfRenderer` allows only one page open at a time, so all rendering is serialised per session through a single-thread dispatcher.

## 6. Storage and permissions
- Camera permission only. The photo picker (`PickMultipleVisualMedia`) needs no storage permission. SAF handles PDFs, with `takePersistableUriPermission` for reopening.
- Export uses MediaStore (`Documents/Paperless`, `Pictures/Paperless`) or SAF `CreateDocument`. No `MANAGE_EXTERNAL_STORAGE`.
- No `INTERNET` permission at all. External links (RD-5) are handed to the browser with an intent; the app itself never connects.
- Share target (SC-12): an `ACTION_SEND` / `ACTION_SEND_MULTIPLE` intent filter for `image/*` and `application/pdf`. The Quick Settings tile is a `TileService`, and launcher shortcuts are static `shortcuts.xml` plus one dynamic "Open last document" shortcut (SC-14).
- Biometric (DM-9): `androidx.biometric` `BiometricPrompt` with `BIOMETRIC_STRONG or DEVICE_CREDENTIAL`. This is a UI gate only. Files of private folders are excluded from MediaStore exports, the FTS index results, and recents while locked.

## 7. Additions from 2026-11-15 (reader, tools, scanner, library)

### 7.1 Save rule for tools that change a PDF
Bookmarks (RD-2), page edits (PT-10), password (PT-11), page numbers (PT-14), metadata (PT-15) and sanitize (PT-16) change a PDF. Every one of them **writes a new file first**. Then:
- **Library document or app-owned copy:** replace it. The previous version goes to the recycle bin (DM-5), so a bad save can be undone for 30 days.
- **External file (SAF URI with write permission):** ask "Save (overwrite)" or "Save as copy". Overwrite writes to a temp file, verifies that it opens and has the same page count, and only then replaces the original.

### 7.2 Reader (RD-1…RD-8)
- **Text, search, links:** check at S1 whether the Pixel's `PdfRenderer` exposes the Android 15+ APIs (text search, selection, link contents, goto links), directly or through an SDK extension. If it does, use them: they're fast and native. If not, the fallback is PdfBox: `PDFTextStripper` with `TextPosition` coordinates for search and selection, and `PDAnnotationLink` for links. Either way, results come back in PDF page space and the overlay maps them to the screen with the same transform the annotations use.
- **Outline (RD-1/2):** read and write it with PdfBox `PDDocumentOutline` / `PDOutlineItem` (destination = `PDPageFitWidthDestination`). Keep an in-memory tree model, and the editor works on that model. Save rebuilds the outline.
- **Auto chapters (RD-3):** PdfBox text extraction with font sizes → cluster the line font sizes (the largest two or three clusters above body size are heading levels) → keep lines that also match heading patterns or start a page → build the tree → show it for review. Tune it in a Python lab copy against textbook fixtures first, the same method as the image pipeline.
- **Crop margins (RD-6):** render each page at a low resolution, find the bounding box of non-background pixels (threshold + small dilation), add 2% padding, and cache the boxes per document. Uniform mode uses the union of the boxes. The viewer renders the cropped region scaled to the screen width.
- **Side-by-side (RD-8):** two `ReaderPane`s, each bound to its own `TabState`. The renderer LRU grows to 3 while split view is active.
- **Reading progress (RD-7):** it already lives in `ReaderTab`/`ExternalPdf`. Add `lastPage`, `pageCount`, and `lastOpenedAt` to `Document` and `ExternalPdf`.

### 7.3 Tools (PT-10…PT-17)
- N-up default layout: **Duplex 2-up, cut each sheet** (D14).
- Page edits and N-up use PdfBox `LayerUtility.importPageAsForm` to place source pages as Form XObjects on new sheets, so they stay vector with no rasterising. N-up ordering lives in a pure function `impose(n, layout, flip) → List<Sheet(front: [pageIdx?], back: [pageIdx?])>`, unit-tested against the tables in PRD §3.5.1.
- Password: PdfBox `StandardProtectionPolicy` (AES-256). Remove a password = load with the password, `setAllSecurityToBeRemoved(true)`, save.
- Sanitize: remove `/OpenAction`, `/AA`, `/Names/JavaScript`, `/EmbeddedFiles`, and file attachment and JavaScript-action annotations; clear XMP if asked.
- Grayscale: re-encode image XObjects as gray JPEGs. Text and vector colours are left alone in v1, and the UI says so.
- Batch (PT-13): a `BatchJob(op, params, inputs)` runs in a WorkManager `CoroutineWorker`, one file at a time, with progress notifications. Each per-file result is stored so the result screen survives the app being killed.

### 7.4 Scanner additions
- Duplex (SC-13): a session flag `duplexPhase = FRONTS | BACKS`. At save, `interleave(fronts, backs.reversed())`. The review grid shows F/B badges and lets you swap a pair.
- Ink removal (SC-15): convert to HSV/Lab and mask pixels whose hue is within ±15° of the chosen ink and whose saturation is above a threshold. Set them to the local background (from the shadow-fix background estimate), so there's no white blotch on grey paper. Prototype it in the imaging lab first.
- Auto-split (SC-17): blank score per page = fraction of ink pixels after the Document filter, plus edge density. A page counts as blank when it's below a threshold tuned on fixtures (default about 0.3% ink). In a duplex session, a separator must be blank on both sides. `splitGroups(pages) → List<List<Page>>` is a pure, unit-tested function, and the UI only edits its result.
- Deskew (SC-16): run on the warped, binarised page. Use the dominant angle from a Hough transform on text-line blobs or a projection profile over ±5°, and skip if confidence is low.

### 7.5 Library additions
- Backup (DM-8): write `library.json` (schema version, folders, documents, page params, bookmarks, annotations, reading state) plus files laid out as `<folder>/<doc title>/` with `<doc>.pdf`, `orig/`, and `proc/`. Restore validates the schema and merges by document id. It runs as a WorkManager job.
- Content search (DM-10): a Room FTS4 table `PageText(pdfKey, pageIndex, text)`. An indexer job extracts text with PdfBox when a PDF is imported or changed. A page with no text layer is skipped (scans aren't searchable; OCR is dropped). Results show highlighted snippets.
