# 01 — Product Requirements (v1)

## 1. Product definition
**Paperless** is a personal Android app for a single phone. It is primarily a **document scanner**. It also includes a **document library** and a **lightweight PDF toolkit with a multi-tab reader**. It is not a general PDF editor: it can't edit PDF text and doesn't try to be Acrobat.

Principles: offline-only, no watermark, no account, no subscription, no ads, no AI/cloud APIs, originals always preserved, and a polished native Android feel (Material 3 / Material You).

## 2. The two core workflows (the user's minimal version, which must be excellent)
**A. Batch scan:** open the camera → point it at a page → the edges are detected live → capture (auto or manual) → repeat for N pages → review the grid → apply enhancement to all pages ("white page" look) → export as **PDF, images, or both**.

**B. Single page / add to an existing document:** scan one page, or import from the gallery → save it as a new document **or append it to an existing document** (e.g. page 16 of an old lecture-notes scan) → reorder, rotate, or delete → re-export.

## 3. v1 scope (agreed)

### 3.1 Core scanner
| ID | Feature | Acceptance (summary) |
|---|---|---|
| SC-1 | Automatic edge detection (live preview overlay + on the captured still) | ≥ 90% of corners within 2% of the image diagonal on the `clean`/`skewed` fixtures; live overlay runs at ≥ 15 fps on the phone |
| SC-2 | Manual 4-corner adjustment | Draggable corners with a magnifier loupe; "reset to auto" and "full image" buttons |
| SC-3 | Perspective correction | Output rectangle aspect ratio estimated from the quad; it can snap to A4/Letter |
| SC-4 | Batch scanning | Unlimited pages per session, a page counter, a thumbnail strip, retake/delete inside the session |
| SC-5 | Single-page scanning | Same camera with a "single" toggle that returns after one capture |
| SC-6 | Enhancement filters | Presets: **Original, Auto, Document (white page), B&W, Grayscale, Whiteboard (colour-preserving)**; brightness/contrast sliders; apply to one page or all pages |
| SC-7 | Add pages to an existing document | Scan or import → pick a document → insert at the end or at a chosen position |
| SC-8 | Batch auto-capture | Captures when the quad is stable for about 0.7 s and the image is sharp; a cooldown stops it re-capturing the same page; adjustable delay and sensitivity; manual shutter always available |
| SC-9 | Shadow removal | Evens out the illumination while keeping strokes, handwriting, and diagrams (works best on the Document preset) |
| SC-10 | Glare removal | Reduces specular highlights. It **can't** recover fully blown-out text: the UI flags those pages and suggests a retake |
| SC-11 | Gallery import | Multi-select from the photo picker, then the same detect → crop → enhance pipeline |
| SC-12 | **Share to Paperless** | Images shared from any app (WhatsApp, Messenger, Gallery) go through the scan pipeline: detect → crop → enhance → new document or append to an existing one. A PDF shared to the app opens in the Reader |
| SC-13 | **Two-sided (duplex) batch** | Scan all the fronts, flip the stack, scan all the backs in the order they come off the stack. The app reverses the backs and interleaves them: F1 B1 F2 B2 … If the counts don't match, it warns and doesn't guess |
| SC-14 | **Quick Settings tile + launcher shortcuts** | A QS tile and long-press app shortcuts (Batch scan / Single / Open last document) go straight to the camera in < 1 s |
| SC-15 | **Ink removal** | Optional filter that drops coloured pen marks (blue/red/green, picked from the page or preset) and leaves printed black text. Use case: a clean copy of a question paper you've written on. It can't separate black pen from black print: the UI says so |
| SC-16 | **Auto-deskew** | After the crop, detect the dominant text-line angle and rotate by up to ±5° so lines are horizontal. On by default, toggle in the editor |
| SC-17 | **Auto-split a pile at blank sheets** | In a batch, put a blank sheet between different documents. Blank pages (almost no ink after enhancement, with a two-sided-aware check so the blank back of a one-sided page isn't treated as a separator) are detected and dropped. The review grid shows the proposed split as separate groups, each with its own name field, and you can move or undo a split before saving. Saves one document per group. Off by default, with a toggle in the camera's batch options. A manual \"Split here\" action between any two pages is always available |

### 3.2 Scan modes
| ID | Mode | v1 behaviour |
|---|---|---|
| MD-1 | ID card | Guided front, then back; card-sized crop (85.6×54 mm); both sides placed on one A4 page at true size; glare removal on by default |
| MD-2 | Book | Two-page spread detection → split at the gutter → perspective correction per page. **Curved-page dewarping is a stretch goal** (see risks) |
| MD-3 | Whiteboard | Colour-preserving background normalisation, saturation boost for marker strokes, glare reduction |

### 3.3 Document library and management
| ID | Feature |
|---|---|
| DM-1 | Library home: recent documents, grid/list toggle, sorting (date/name) |
| DM-2 | Folders (one level of nesting is enough), plus moving documents between folders |
| DM-3 | Favourites |
| DM-4 | Search by document name, folder, and date (content search: see DM-10) |
| DM-5 | Rename, duplicate, delete (with a 30-day recycle bin, which is cheap to add and saves accidents) |
| DM-6 | Page operations: reorder (drag-and-drop), rotate, delete, re-crop, re-filter |
| DM-7 | Auto-naming, e.g. `Scan 2026-11-13 14.05`, with an editable pattern in Settings |
| DM-8 | **Plain-file backup and restore.** Export the whole library (or chosen folders) to a folder you pick: normal PDFs + original images + one readable `library.json` (folders, names, favourites, page order, crop/filter params, bookmarks, annotations, reading positions). Restore rebuilds the library from that folder. Your data stays usable without the app, and an exported folder can be synced to the PC with Syncthing or copied to Drive by hand |
| DM-9 | **Biometric lock.** Fingerprint/face/PIN lock for the whole app (on open or after N minutes in the background), and/or per-folder "private" locks. Locked folders are hidden from recents, search and the share sheet. Uses `BiometricPrompt`; no custom crypto |
| DM-10 | **Content search across the library.** Full-text search inside every PDF that has a text layer (imported textbooks, slides), with results showing the document, page and a snippet; tap to open the Reader at that page. Uses a Room FTS index built in the background. **Scans have no text layer, so they aren't searchable. That's accepted: OCR is dropped** |

### 3.4 Export and sharing
| ID | Feature |
|---|---|
| EX-1 | Export as **PDF / JPG / PNG / PDF + images** |
| EX-2 | PDF page size: Auto (fits the image), A4, Letter; quality presets High / Medium / Small |
| EX-3 | Save to `Documents/Paperless` (PDFs) and `Pictures/Paperless` (images) via MediaStore, or to any folder via the system file picker |
| EX-4 | Android share sheet |
| EX-5 | "Same width for all pages" switch on the export sheet, **on by default**. Page size Auto, ID-card pages and appended pages can give one PDF mixed widths; this runs the PT-9 logic on the exported PDF |

### 3.5 PDF toolkit
| ID | Feature |
|---|---|
| PT-1 | **Multi-tab PDF reader** (core requirement, see 3.6) |
| PT-2 | Merge PDFs (pick several, reorder, merge) |
| PT-3 | Split PDF (by ranges, every N pages, or picked pages) |
| PT-4 | Compress PDF (re-encode or downsample images; show size before and after; fall back gracefully on vector-only PDFs) |
| PT-5 | Rotate and reorder PDF pages |
| PT-6 | Images → PDF; PDF → images (choose DPI) |
| PT-7 | Annotations: freehand ink, highlighter, underline/strike-through, text note. Saved as editable layers; exporting **flattens them into a new PDF** |
| PT-8 | Open external PDFs from other apps ("Open with Paperless") and from the system file picker |
| PT-9 | **Uniform page width (GoodNotes fix).** GoodNotes sizes every page to the widest page, so narrower pages open zoomed in and slide sideways. This tool scales every page whose width differs to one common width (default: the most common width; also max, min, A4, Letter, or a custom value). The aspect ratio is kept, nothing is cropped or rasterised, text stays searchable, and bookmarks, links and annotations survive. A PDF that is already uniform is reported and left alone. The working reference implementation is `tools/pdf-fixwidth/` (see `docs/02-architecture.md` §4) |
| PT-10 | **Page edits:** delete, extract to a new PDF, duplicate, insert a blank page, insert pages from another PDF (or a new scan) at any position |
| PT-11 | **Password:** add a password (AES-256) to a PDF, or remove one when you know it (e.g. locked portal PDFs). The Reader asks for the password when opening a protected PDF |
| PT-12 | **N-up / print layout.** Several pages per sheet for printing slides. See §3.5.1 for the layouts, including the duplex "cut" orders |
| PT-13 | **Batch on a folder or selection:** run compress, same-width (PT-9), grayscale, page numbers, sanitize or password on many PDFs at once, or merge a whole folder in name order. Shows progress, can be cancelled, and gives a per-file result list (skipped / done / failed with a reason) |
| PT-14 | **Page numbers:** stamp `n`, `n / N` or `Page n` at a chosen position, font size and starting number, with page ranges (e.g. skip the cover) |
| PT-15 | **Metadata:** view and edit title, author, subject and keywords; optionally clear all metadata |
| PT-16 | **Sanitize:** remove embedded JavaScript, auto-actions, attachments and hidden metadata, saved as a copy. The Reader never runs PDF JavaScript or opens attachments anyway; this is for files you pass on |
| PT-17 | **Grayscale PDF:** convert a PDF to grayscale for print-friendly output and smaller files (images re-encoded; vector text kept) |

#### 3.5.1 Print layouts for PT-12
Every layout pads with blank pages when the page count doesn't fill the last sheet. There is a "Print test sheet" button that makes a 2-sheet PDF with big page numbers, so you can check your printer's flip direction once.

| Layout | Use | Order |
|---|---|---|
| **Plain N-up** (2/4/6/9 per side) | Single-sided printing | Reading order: 1\|2, 3\|4 … (or 1 2 / 3 4 for 4-up) |
| **Duplex 2-up, cut each sheet** (default, D14) | Print double-sided, cut each sheet down the middle, put the left half on top of the right half, go to the next sheet | Sheet *k* (k = 0,1,2…): front **4k+1 \| 4k+3**, back **4k+4 \| 4k+2** |
| **Duplex 2-up, cut the whole stack** | Print double-sided, cut the whole stack at once, put the left-half stack on top of the right-half stack | With S = ⌈n/4⌉ sheets, sheet *k*: front **2k+1 \| 2S+2k+1**, back **2S+2k+2 \| 2k+2** |
| **Booklet (fold)** | Fold the stack in half like a book | Standard saddle-stitch order (for 4 pages: front 4\|1, back 2\|3) |

For 4 pages, both "cut" layouts give **front 1|3, back 4|2**, the order that worked for you before. The back is written **4|2, not 2|4**, because flipping the sheet swaps left and right: page 2 sits behind page 1 and page 4 behind page 3. A "back side flip" setting (long edge / short edge) mirrors the back order for printers that flip the other way. Options: margins, gap, a thin cut line, and scale-to-fit.

### 3.6 Multi-tab reader (core requirement)
- Several PDFs open at once as **compact, horizontally scrollable tabs**, with an overflow menu once more than about 5 are open.
- Each tab keeps its own **page, zoom, scroll offset, and view mode** (continuous vertical / single page). Switching tabs never resets another tab.
- Tabs can be closed one at a time. If a tab has unsaved annotations, closing it asks "Save copy / Discard / Cancel".
- Open tabs are **restored after the app restarts**; there's a "Recently opened" list.
- Memory: only the active tab holds an open renderer plus a bitmap cache. Inactive tabs keep only their state. Pages render on demand, and a 100+ page PDF must scroll without running out of memory.
- Nice to have: page jump, thumbnail sidebar sheet, night mode (inverted rendering), keep screen on.

### 3.6.1 Reader features (added 2026-11-15)
| ID | Feature | Acceptance (summary) |
|---|---|---|
| RD-1 | **Outline panel** | Shows the PDF's existing bookmarks/table of contents as a collapsible tree; tapping an entry jumps there; the entry for the current page is highlighted |
| RD-2 | **Bookmark editor** | Add "bookmark this page", rename, delete, drag to reorder or nest (any depth). **Save** writes a real PDF outline into the file, so the bookmarks show in Chrome, Drive, Acrobat and GoodNotes. The file save follows the save rule in `02-architecture.md` §4 |
| RD-3 | **Auto chapter detection** | "Suggest bookmarks" reads heading font sizes and patterns (`Chapter 3`, `2.4 …`) from the text layer and proposes a nested outline; you tick, edit, or drop entries before saving. Text PDFs only; on a scan it says why it can't |
| RD-4 | **Find in document + select and copy text** | Search box with match count and next/previous, matches highlighted on the page; long-press to select text and copy/share it. Text PDFs only |
| RD-5 | **Links** | Internal links (TOC entries, "see page 42") jump inside the PDF, and a floating "← back to p. 12" chip returns. External links ask before opening the browser (the app itself has no internet) |
| RD-6 | **Crop-margins mode** | Toggle that trims the white margins (auto-detected per page, or one uniform crop for the whole document) so text fills the phone screen. View-only; the file isn't changed |
| RD-7 | **Reading progress** | Per document: last page, % read, last opened. A "Continue reading" row on Library home |
| RD-8 | **Side-by-side** | In landscape, two panes: two different tabs, or two pages of the same PDF (e.g. a question and its answer key). Each pane scrolls on its own |

### 3.7 Look and feel
Material 3 with **dynamic colour** (Material You), light/dark modes, edge-to-edge, predictive back, haptics on capture and important actions, bottom sheets for filters and export, shared-element transitions (thumbnail → page), and smooth 120 Hz scrolling. Empty, loading, error, and permission states all need designs. Avoid web-dashboard aesthetics (stacks of cards, gradients).

## 4. Explicitly excluded from v1
Digital signature, cloud backup/sync, PDF → Word/Excel, collaboration, editing PDF text, tags, finger removal, document comparison, Windows desktop app, local-LLM summaries, Play Store publishing. (PDF password moved into v1 as PT-11.)

**Dropped for good (decided 2026-11-15):** OCR, searchable scans and Bangla OCR. They aren't needed, so the app stays free of OCR models.

## 5. Future (v2+ candidates, in rough order of value)
1. Digital signature (draw once, stamp anywhere).
2. Curved book-page dewarping, if v1's version isn't good enough.
3. PC ↔ phone sync via Syncthing on the DM-8 backup folder (no cloud).
4. Windows desktop app (Kotlin Multiplatform + PDFium). It's a second project of about 8–12 sessions.

## 6. Non-functional requirements
- Capture-to-thumbnail latency < 1 s; full enhancement of one 12 MP page < 1.5 s on the test phone (background thread).
- A 50-page batch shouldn't crash or exhaust memory. Process pages through a bounded queue and never hold more than about 3 full-resolution bitmaps at once.
- Storage: originals are JPEG q95 in app-private storage; processed pages are cached and can be regenerated.
- APK size target < 60 MB (arm64 only for release).
- Process death and rotation never lose a scan session (the session is persisted to Room as pages arrive).
- Any tool that writes a PDF must keep everything it doesn't touch. Bookmarks, links, annotations, text layer and metadata survive merge, split, page edits, numbering, password and same-width (checked by the tests in `06`).
- Batch jobs (PT-13) and the search index (DM-10) run in WorkManager, so they survive leaving the app, and they never block the UI.
