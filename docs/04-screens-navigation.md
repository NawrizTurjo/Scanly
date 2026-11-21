# 04 — Screens and navigation

## Navigation map
```
                 ┌────────────── Bottom nav ──────────────┐
                 │  Library    Reader    Tools    Settings │
                 └────────────────────────────────────────┘
Library ──FAB "Scan"──► Camera ──done──► Review grid ──► Page editor (crop/filter)
   │                       ▲                 │
   │                       │                 └──► Export sheet ──► share / save
   ├─► Folder ─► Document detail ─► Page viewer / Page editor
   │                 └── "Add pages" ──► Camera (target = this doc, insertAt)
   ├─► Search
   └─► Recycle bin
Reader (tabs) ◄── open PDF from: Library export, Tools, file picker, "Open with" intent
Tools ─► Merge │ Split │ Page edits │ Compress │ Same width │ Print layout │ Page numbers
         Password │ Metadata │ Sanitize │ Grayscale │ Images→PDF │ PDF→Images │ Batch │ Same page width
Reader ─► Annotate mode (toolbar overlay) ─► Save copy (flattened)
Reader ─► Outline sheet (view / edit / suggest) · Search bar · Side-by-side (landscape)
Share from any app ─► images → Camera review pipeline · PDF → Reader
QS tile / launcher shortcut ─► Camera (batch or single) · last document
```

## Screens

### 1. Library (home)
- A large top app bar with a search field (collapses on scroll). Chips: All · Favourites · Folders.
- Grid of document cards: cover thumbnail, title, page count, date. Long-press for multi-select → move / favourite / delete / merge-export.
- **Extended FAB "Scan"**. Long-press it for a quick menu: Batch, Single, ID card, Book, Whiteboard, Import from gallery.
- Empty state: an illustration-free, simple prompt to scan the first document.
- **"Continue reading" row** (RD-7) above the grid: the last 5 opened PDFs, each with a progress bar.
- The search field searches names (DM-4) and, in a second results section "Inside documents", page text (DM-10), with snippets; tap one to open the Reader at that page.
- Locked folders (DM-9) show a lock icon and need a biometric unlock to open.

### 2. Camera
- Full-bleed preview. The detected quad overlay is a filled translucent polygon with corner dots, and it turns from blue to green as the stability countdown progresses.
- Top bar: flash (off/on/auto/torch), mode selector (Batch · Single · ID · Book · Whiteboard), auto-capture toggle (A).
- Bottom: gallery import · shutter (big, one-handed reach) · last-capture thumbnail with a page-count badge.
- Haptic tick plus a brief shutter flash on capture. The thumbnail flies into the counter.
- ID mode shows a card-shaped guide and a "Front → Back" step indicator. Book mode shows a spread guide with a centre line.
- **Duplex toggle** (SC-13) in batch mode: a banner reads "Fronts: 7". The "Now backs" button flips the phase, and the banner then reads "Backs: 3 / 7 · scan in the order they come off the stack".

### 3. Review grid (after a batch)
- Thumbnails of all pages, enhanced with the default filter. Drag to reorder; tap to open the editor; swipe or use a menu to delete.
- With auto-split on (SC-17), pages appear in **groups** with a header per group ("Document 1 · 4 pages", editable name) and the dropped blank separators shown faded, so you can restore one. A "Split here" / "Join" action sits between any two pages.
- Bottom bar: "Filter all" (bottom sheet of presets with live previews), Add more (back to the camera), **Save** → name, folder, export options.

### 4. Page editor
- Tabs: **Crop** (4 draggable corners + edge midpoints, magnifier loupe, Auto / Full buttons) · **Filter** (preset carousel with real thumbnails, brightness/contrast sliders, shadow/glare toggles) · **Rotate**.
- Hold-to-compare shows the original. Undo/redo within the editor.
- Glare warning chip when the page is flagged.
- Filter tab extras: **Ink removal** (SC-15) with colour chips (Blue / Red / Green / Pick from page) and **Auto-deskew** (SC-16) toggle with the detected angle shown.

### 5. Document detail
- Pages shown as a reorderable grid. Top actions: rename, favourite, folder, share.
- Bottom bar: Add pages (camera / gallery) · Export (sheet) · Open in Reader · More (duplicate, delete).

### 6. Export sheet (bottom sheet)
- Format: PDF · Images · Both. Page size: Auto/A4/Letter. Quality: High/Medium/Small, with the estimated size shown live.
- "Same width for all pages" switch (EX-5, on by default; hidden for image-only export).
- Destination: Share · Save to Documents/Pictures · Choose folder… · File name field (auto-name pattern).

### 7. Reader (multi-tab)
- A **tab strip under the top bar**: compact chips (title truncated, close ×), horizontally scrollable; "+" opens the file picker or recent files; an overflow ▾ lists all tabs once there are more than about 5.
- Content: continuous vertical scroll, pinch zoom, double-tap zoom. A page indicator pill sits bottom right; tap it to jump to a page.
- Top actions: **search** (RD-4), **outline** (RD-1), annotate, more (view mode, crop margins RD-6, night mode, side-by-side RD-8, share, thumbnails sheet, page tools, password/metadata).
- **Outline sheet** (RD-1/2): a tree with expand/collapse and the current chapter highlighted. Edit mode adds drag handles, nesting by dragging right/left, rename, delete, "+ This page", and **"Suggest from headings"** (RD-3), which opens a review list with checkboxes and level controls. Save follows the save rule.
- **Search bar** (RD-4) replaces the top bar: "3 / 41" with ↑↓ arrows; matches are highlighted on the pages. Long-press on text starts selection with Copy / Share / Highlight.
- **Link back chip** (RD-5): after you follow an internal link, a small "← p. 12" chip floats bottom left for 10 s or until used.
- **Side-by-side** (RD-8, landscape only): two panes with a draggable divider; each pane has a small tab picker.
- Swipe-down on the tab strip shows a "Recently opened" sheet.

### 8. Annotate mode
- A floating toolbar replaces the top actions: Pen · Highlighter · Underline · Strike · Text note · Eraser · colour · width · undo/redo · Done.
- Done → "Save as new PDF" (flattened) or "Keep as editable layer".

### 9. Tools
- A list of tool tiles. Each tool is a simple 2–3 step flow: pick input(s) → options → run with progress → result (open in Reader / share / save).
- Tool list: Merge · Split · **Page edits** (PT-10) · Compress · Same width · **Print layout / N-up** (PT-12) · **Page numbers** (PT-14) · **Password** (PT-11) · **Metadata** (PT-15) · **Sanitize** (PT-16) · **Grayscale** (PT-17) · Images→PDF · PDF→Images · **Batch** (PT-13).
- **Page edits** (PT-10): a thumbnail grid of the PDF. Select pages → Delete / Extract / Duplicate / Rotate. "+" between thumbnails inserts a blank page, pages from a PDF, or a scan.
- **Print layout** (PT-12): a layout picker (Plain N-up / Duplex: cut each sheet / Duplex: cut whole stack / Booklet), pages per side, back-side flip (long/short edge), margin, gap, cut line. A **live preview** of sheet 1 front and back with page numbers, plus a "Print test sheet" button.
- **Batch** (PT-13): pick a folder or several files → pick an operation and its options → run with a progress notification → a result list with ✓ / skipped / ✗ reason per file.
- **Same page width (PT-9):** pick one or more PDFs, shown with a page-width summary ("13 pages at 595 pt, 1 at 842 pt"). Choose the target width: Most common (default) / Widest / Narrowest / A4 / Letter / Custom. Run, then the result reads "1 of 14 pages scaled" or "Already uniform". Saves `<name>-fixed.pdf`. Also offered as a share target, so you can share a PDF from GoodNotes, Drive or Files → Paperless → fixed copy → share back.

### 10. Settings
App lock (DM-9: off / on open / after N min) and private folders, **Backup & restore** (DM-8: choose folder, back up now, restore), search index status (DM-10: pages indexed, rebuild), default filter, auto-capture delay and sensitivity, default export format, quality, and page size, auto-name pattern, theme (system/light/dark, dynamic colour on/off), storage usage plus clear processed cache, recycle bin retention, about/licences (auto-generated OSS licence list).

## Global UX rules
Edge-to-edge with correct insets. Predictive back everywhere. Every long operation shows progress and can be cancelled. Errors go in snackbars with an action. Permissions are explained once with a clear rationale. Every screen supports dark mode. Touch targets are ≥ 48 dp.
