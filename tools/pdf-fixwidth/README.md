# fixwidth: make every PDF page the same width

GoodNotes sizes every page to the widest page in a PDF, so narrower pages open zoomed in and can be dragged sideways. `fixwidth.py` scales each page that differs to a common width, keeping its aspect ratio. Nothing is cropped or turned into an image. Heights may still differ, which GoodNotes handles fine.

## Use it

- **Drag and drop:** drop PDFs or folders onto `fix-width.bat`. Each input gets a `<name>-fixed.pdf` next to it.
- **Command line:**

```
python fixwidth.py file.pdf                 # -> file-fixed.pdf
python fixwidth.py folder -r -o out         # every PDF under folder, results in out/
python fixwidth.py file.pdf --mode letter   # force 612 pt (iPad export width)
python fixwidth.py file.pdf --width 600     # any width in points
```

## Target width (`--mode`)

| Mode | Width |
|---|---|
| `common` (default) | The most common width. Most pages stay untouched. |
| `max` | The widest page. |
| `min` | The narrowest page. |
| `letter` | 612 pt |
| `a4` | 595 pt |

A PDF that already has uniform widths is skipped. Add `--force` to write it anyway.

## What is kept

- Text stays text, so search and selection still work.
- Images are copied as they are, not re-encoded.
- Bookmarks, page labels and internal links are kept.
- Highlights, ink and other annotations, and form fields, move with the page content.
- Rotated pages and pages with a CropBox are handled.

## Safety check

After writing each file, the script verifies the result:

- Every page has the target width, and the page count is unchanged.
- Each scaled page still has the same text.
- Each scaled page is rendered and compared with the original page rendered at the same scale. A pixel difference near 0% proves nothing was cut or shifted.

A failure prints `CHECK FAILED`. Use `--no-check` to skip the check.

Tests: `python tests/test_fixwidth.py` builds a PDF mixing portrait, landscape, rotated and cropped pages with links, a table of contents, labels and annotations, then runs every mode.

Requires Python 3 with PyMuPDF (`pip install pymupdf`).
