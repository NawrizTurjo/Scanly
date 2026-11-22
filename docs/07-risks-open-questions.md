# 07 — Risks and open questions

## A. Technical risks

| # | Risk | Likelihood / impact | Mitigation |
|---|---|---|---|
| R1 | **Edge detection fails** on low-contrast backgrounds (white page on a white desk), busy textures, or a hand on the page | High / High | Several detectors (Canny contours + line-based fallback), candidate scoring, a confidence score → if it's low, show "adjust corners" instead of a wrong crop. Advice in the UI: dark background helps. Real-photo tuning round in Phase 2 |
| R2 | **Auto-capture double or missed shots** | Medium / Medium | Content-change guard (thumbnail diff) + cooldown; adjustable sensitivity; manual shutter always works |
| R3 | **Shadow removal** washes out pencil or light handwriting | Medium / High (your lecture notes) | Background division instead of hard thresholding for the Document preset; a separate "pencil" strength slider; test on real handwritten notes |
| R4 | **Glare can't be fully removed**; laminated IDs are the worst case | Certain for big highlights / Medium | Inpaint only small regions, flag big ones and suggest a retake at a different angle. Stretch: 2-shot fusion (v2) |
| R5 | **Book curvature**: true dewarping is research-grade | High / Low-Medium | v1 = spread split + perspective only; dewarp deferred to v2. Tip: press the book flat |
| R6 | OpenCV APK size and native load time | Medium / Low | arm64-only release, R8, load OpenCV once at app start in the background |
| R7 | Large PDFs / many tabs → out of memory | Medium / High | Renderer LRU (≤ 2 open), bitmap cache cap, render on demand, test with 300 pages × 8 tabs |
| R8 | PdfBox-Android maintenance or performance | Medium / Medium | Verify activity at S1; keep PDF ops behind a `core:pdf` interface so the implementation can be swapped |
| R9 | **Compression** of non-scan PDFs gives a small gain or a bigger file | Medium / Low | Only re-encode images; skip and report if the gain is < 5% |
| R10 | Annotation coordinates drift across zoom levels or rotated pages | Medium / Medium | Store in PDF page space; unit-test the transforms; handle `/Rotate` explicitly |
| R11 | Live camera UX can't be fully verified by Claude | Certain / Medium | Manual checklists + screenshots/recordings from you every phase |
| R12 | Windows build pitfalls (long paths, spaces, antivirus scanning the Gradle cache) | Medium / Low | Short path (`D:\dev\paperless`), enable long paths, add a Defender exclusion for the project + `.gradle` |
| R13 | Parallel workers conflict or several Gradle daemons use too much RAM | Low / Low | One worktree per worker on separate modules; `org.gradle.jvmargs=-Xmx4g` per daemon |
| R14 | Android 15+ `PdfRenderer` text/search/link APIs not available as expected | Low on a recent phone / Medium | Check at S1; PdfBox text-position fallback is designed in (02 §7.2) |
| R15 | Auto chapter detection is noisy on badly made PDFs | Medium / Low | It only suggests; you review before saving. Text PDFs only |
| R16 | Writing outlines, numbers or passwords damages an unusual PDF | Medium / High | New file first, verify it opens and the page count matches, old version kept in the recycle bin (02 §7.1); preservation tests in 06 |
| R17 | Ink removal also eats coloured diagrams or highlighted print | Medium / Low | Off by default, preview before applying; black pen vs black print is impossible and the UI says so |
| R18 | The scope grew from about 16–19 to about 26–29 sessions | Certain / Medium | The daily-driver milestone doesn't move (about session 6). Reader and tool extras come after it and can be cut or reordered at any time |
| R19 | Auto-split drops a real but nearly empty page (one line of text, faint pencil) | Medium / High | Conservative threshold; dropped separators stay visible and restorable in the review grid; nothing is deleted until you save |

## B. Decisions for the S1 gate (Claude asks you; each has a recommended default)

| # | Question | Recommended default |
|---|---|---|
| D1 | App name | **Paperless** (working name) |
| D2 | Package name | `dev.turjo.paperless` |
| D3 | minSdk | **31** (Android 12+: dynamic colour, modern APIs; only one phone matters) |
| D4 | Use ML Kit Document Scanner? | **No in the app**; optional one-time quality benchmark in Phase 2 |
| D5 | Navigation library | Navigation Compose stable, unless S1 research shows Navigation 3 is stable and mature |
| D6 | Git hosting | **Private** GitHub repo (backup + lets you view code from another machine). Real personal scans never committed |
| D7 | Annotations: on any PDF, or only exported scans? | **Any PDF** (it's in the reader) |
| D8 | Default filter after a scan | **Document** (white page) |
| D9 | Default export | **PDF**, A4 auto-fit, Medium quality |
| D10 | Recycle bin | **Yes, 30 days** |
| D11 | Where exports go | `Documents/Paperless` + `Pictures/Paperless` |
| D12 | Release signing | Local keystore, backed up to a private place (losing it means you can't update in place, only reinstall) |
| D13 | Auto-split a scanned pile at blank separator sheets? | **Decided: yes, in v1 as SC-17** (Phase 3, off by default), plus the manual "Split here" |
| D14 | Print layout default (PT-12) | **Decided: Duplex 2-up, cut each sheet** (front 4k+1\|4k+3, back 4k+4\|4k+2). Confirmed by your earlier print: 1 sheet → 4 pages in the right order |
| D15 | Bookmark/page-edit saves on external files | Ask "Save / Save as copy" each time, with an "always overwrite" option in Settings |

## C. Things the brainstorm got slightly wrong or left vague (already fixed in the PRD)
- The early ChatGPT stack listed ML Kit OCR. OCR is **dropped** (decided 2026-11-15), so no ML Kit is needed.
- "Search documents" covers name/folder/date (DM-4) plus page text of PDFs that have a text layer (DM-10). Scans aren't searchable, and that's accepted.
- "Annotations and highlighting" applies to PDFs in the reader and is saved by **flattening to a new copy**. It doesn't edit the original in place.

## D. From the second ChatGPT chat (2026-11-15): not adopted, and why
- **Windows desktop app / Kotlin Multiplatform:** `PdfRenderer` is Android-only, so this needs a second rendering engine (PDFium) and a second UI. It's about 8–12 more sessions, a separate project after v1. Desktop scripts like `tools/pdf-fixwidth/` cover the PC for now.
- **MuPDF as the engine:** AGPL, so it's out (03).
- **Local-LLM document summaries:** this breaks the no-AI rule and is heavy. Not planned.
- **Document comparison:** costly (1+ session) and rarely needed. Skipped.

## E. Notes for later decisions
**Auto-split (D13), explained:** you put a pile of *different* documents through one batch scan, for example 3 assignments of 4, 2 and 5 pages, with a blank sheet of paper between them. The app spots the blank pages (almost no ink after enhancement), drops them, and saves 3 separate documents instead of one 13-page PDF. Without it, you scan each document as its own batch, or use the manual "Split here" action in the review grid.

**OCR:** dropped on 2026-11-15 (not needed). For the record: it would have added about 10–25 MB and several seconds per page of CPU time.
- "PDF reader" doesn't include text editing. The multi-tab reader is a core v1 feature (added late in the chat).
