"""Make every page of a PDF the same width (for GoodNotes and similar readers).

Each page that is not already at the target width is scaled uniformly, so its
aspect ratio is kept and nothing is cropped. The page is changed in place: the
content stream is wrapped in a scale matrix and the page boxes are replaced.
Text stays text, images are not re-encoded, and bookmarks, page labels, links,
annotations and metadata survive. Pages already at the target width are left
untouched.

Usage:
    python fixwidth.py IN.pdf [MORE.pdf | FOLDER ...] [options]

Options:
    -o, --outdir DIR     write results here (default: next to each input)
    --suffix TEXT        output name suffix (default: -fixed)
    --mode MODE          target width: common (default), max, min, letter, a4
    --width PT           explicit target width in points (overrides --mode)
    -r, --recursive      also look into sub-folders of FOLDER inputs
    --force              write output even if the PDF is already uniform
    --no-check           skip the visual verification
"""
import argparse, os, sys
from collections import Counter

import fitz  # PyMuPDF

BOX_KEYS = ('MediaBox', 'CropBox', 'BleedBox', 'TrimBox', 'ArtBox')
NAMED = {'letter': 612.0, 'a4': 595.0}
TOL = 0.5  # points; widths closer than this count as equal


def page_width(page):
    return page.rect.width  # displayed width (rotation and cropbox applied)


def pick_width(doc, mode, explicit):
    if explicit:
        return float(explicit)
    if mode in NAMED:
        return NAMED[mode]
    widths = [round(page_width(p), 1) for p in doc]
    if mode == 'max':
        return max(widths)
    if mode == 'min':
        return min(widths)
    return Counter(widths).most_common(1)[0][0]


def inherited(doc, xref, key):
    """Read a page key, walking up /Parent for inheritable entries."""
    seen = 0
    while xref and seen < 64:
        t, v = doc.xref_get_key(xref, key)
        if t != 'null':
            return t, v
        t, v = doc.xref_get_key(xref, 'Parent')
        if t != 'xref':
            break
        xref = int(v.split()[0]); seen += 1
    return 'null', 'null'


def read_box(doc, xref, key):
    t, v = inherited(doc, xref, key)
    if t == 'xref':
        v = doc.xref_object(int(v.split()[0]), compressed=True)
    if t == 'null' or '[' not in v:
        return None
    nums = [float(x) for x in v.strip().strip('[]').split()[:4]]
    x0, y0, x1, y1 = nums
    return (min(x0, x1), min(y0, y1), max(x0, x1), max(y0, y1))


def visible_box(doc, page):
    """The PDF-space rectangle a viewer shows: CropBox clipped to MediaBox."""
    mb = read_box(doc, page.xref, 'MediaBox') or (0, 0, 612, 792)
    cb = read_box(doc, page.xref, 'CropBox') or mb
    return (max(mb[0], cb[0]), max(mb[1], cb[1]), min(mb[2], cb[2]), min(mb[3], cb[3]))


def new_stream(doc, data):
    xref = doc.get_new_xref()
    doc.update_object(xref, '<<>>')
    doc.update_stream(xref, data)
    return xref


def fmt(x):
    return ('%.6f' % x).rstrip('0').rstrip('.') or '0'


def scale_array(text, s, ox, oy, pairs=True):
    """Scale a PDF number array of x,y pairs (or a rect) into the new page space."""
    nums = [float(x) for x in text.strip().strip('[]').split()]
    out = []
    for i, n in enumerate(nums):
        out.append((n - (ox if i % 2 == 0 else oy)) * s)
    return '[' + ' '.join(fmt(n) for n in out) + ']'


def scale_annots(doc, page, s, ox, oy):
    t, v = doc.xref_get_key(page.xref, 'Annots')
    if t == 'null':
        return 0
    if t == 'xref':
        v = doc.xref_object(int(v.split()[0]), compressed=True)
    refs = v.strip().strip('[]').split()
    xrefs = [int(refs[i]) for i in range(0, len(refs) - 2, 3) if refs[i + 2] == 'R']
    n = 0
    for ax in xrefs:
        for key in ('Rect', 'QuadPoints', 'Vertices', 'L', 'CL', 'RD'):
            kt, kv = doc.xref_get_key(ax, key)
            if kt == 'array':
                if key == 'RD':  # margins: scale only
                    nums = [float(x) * s for x in kv.strip('[]').split()]
                    doc.xref_set_key(ax, key, '[' + ' '.join(fmt(x) for x in nums) + ']')
                else:
                    doc.xref_set_key(ax, key, scale_array(kv, s, ox, oy))
        kt, kv = doc.xref_get_key(ax, 'InkList')
        if kt == 'array':
            inner = kv.strip()[1:-1]
            parts, depth, cur = [], 0, ''
            for ch in inner:
                if ch == '[':
                    depth += 1
                if depth:
                    cur += ch
                if ch == ']':
                    depth -= 1
                    if depth == 0:
                        parts.append(cur); cur = ''
            doc.xref_set_key(ax, 'InkList', '[' + ' '.join(scale_array(p, s, ox, oy) for p in parts) + ']')
        n += 1
    return n


def scale_page(doc, page, s):
    x0, y0, x1, y1 = visible_box(doc, page)
    page.wrap_contents()  # balance q/Q so our matrix cleanly applies
    t, v = doc.xref_get_key(page.xref, 'Contents')
    if t == 'xref':
        old = [v]
    elif t == 'array':
        r = v.strip().strip('[]').split()
        old = [' '.join(r[i:i + 3]) for i in range(0, len(r), 3)]
    else:
        old = []
    pre = new_stream(doc, ('q %s 0 0 %s %s %s cm\n' % (fmt(s), fmt(s), fmt(-x0 * s), fmt(-y0 * s))).encode())
    post = new_stream(doc, b'\nQ\n')
    doc.xref_set_key(page.xref, 'Contents', '[%d 0 R %s %d 0 R]' % (pre, ' '.join(old), post))
    nb = '[0 0 %s %s]' % (fmt((x1 - x0) * s), fmt((y1 - y0) * s))
    for k in BOX_KEYS:
        doc.xref_set_key(page.xref, k, nb if k == 'MediaBox' else 'null')
    return scale_annots(doc, page, s, x0, y0)


def fix_pdf(src, dst, mode='common', width=None, force=False, check=True):
    doc = fitz.open(src)
    if doc.needs_pass:
        return 'skipped (password protected)'
    if doc.is_form_pdf:
        pass  # widget rectangles are scaled like any other annotation
    target = pick_width(doc, mode, width)
    todo = [p.number for p in doc if abs(page_width(p) - target) > TOL]
    if not todo and not force:
        return 'already uniform (%.1f pt), nothing written' % target
    npages = doc.page_count
    nannot = 0
    for i in todo:
        p = doc[i]
        s = target / page_width(p)
        nannot += scale_page(doc, p, s)
    os.makedirs(os.path.dirname(os.path.abspath(dst)), exist_ok=True)
    doc.save(dst, garbage=3, deflate=True)
    doc.close()
    msg = '%d/%d pages scaled to width %.1f pt' % (len(todo), npages, target)
    if nannot:
        msg += ', %d annotation(s) moved' % nannot
    if check:
        msg += '; ' + verify(src, dst, target, todo)
    return msg


def verify(src, dst, target, todo, dpi=40):
    a, b = fitz.open(src), fitz.open(dst)
    if a.page_count != b.page_count:
        return 'CHECK FAILED: page count %d -> %d' % (a.page_count, b.page_count)
    bad = [i + 1 for i, p in enumerate(b) if abs(p.rect.width - target) > TOL]
    if bad:
        return 'CHECK FAILED: pages %s not at target width' % bad[:10]
    worst = 0.0
    for i in todo:
        pa, pb = a[i], b[i]
        s = target / pa.rect.width
        ia = pa.get_pixmap(matrix=fitz.Matrix(dpi / 72 * s, dpi / 72 * s), alpha=False, annots=False)
        ib = pb.get_pixmap(matrix=fitz.Matrix(dpi / 72, dpi / 72), alpha=False, annots=False)
        w, h = min(ia.width, ib.width), min(ia.height, ib.height)
        if abs(ia.width - ib.width) > 2 or abs(ia.height - ib.height) > 2:
            return 'CHECK FAILED: page %d renders %dx%d vs %dx%d' % (i + 1, ia.width, ia.height, ib.width, ib.height)
        sa, sb = ia.samples, ib.samples
        diff = tot = 0
        for y in range(0, h, 2):
            ra = sa[y * ia.stride: y * ia.stride + w * ia.n]
            rb = sb[y * ib.stride: y * ib.stride + w * ib.n]
            diff += sum(abs(x - y2) > 60 for x, y2 in zip(ra[::3], rb[::3])); tot += w
        worst = max(worst, diff / max(tot, 1))
        if a[i].get_text().split() != b[i].get_text().split():
            return 'CHECK FAILED: text changed on page %d' % (i + 1)
    if worst > 0.02:
        return 'CHECK WARNING: up to %.1f%% pixels differ, look at the output' % (worst * 100)
    return 'check ok (widths equal, text same, max pixel diff %.2f%%)' % (worst * 100)


def collect(inputs, recursive):
    for x in inputs:
        if os.path.isdir(x):
            for root, dirs, files in os.walk(x):
                for f in sorted(files):
                    if f.lower().endswith('.pdf'):
                        yield os.path.join(root, f)
                if not recursive:
                    break
        elif x.lower().endswith('.pdf'):
            yield x
        else:
            print('not a PDF:', x)


def main(argv=None):
    ap = argparse.ArgumentParser(description='Make all PDF pages the same width.')
    ap.add_argument('inputs', nargs='+')
    ap.add_argument('-o', '--outdir')
    ap.add_argument('--suffix', default='-fixed')
    ap.add_argument('--mode', default='common', choices=['common', 'max', 'min', 'letter', 'a4'])
    ap.add_argument('--width', type=float)
    ap.add_argument('-r', '--recursive', action='store_true')
    ap.add_argument('--force', action='store_true')
    ap.add_argument('--no-check', action='store_true')
    a = ap.parse_args(argv)
    ok = True
    for src in collect(a.inputs, a.recursive):
        base = os.path.splitext(os.path.basename(src))[0]
        if base.endswith(a.suffix):
            continue  # do not re-fix our own output
        outdir = a.outdir or os.path.dirname(os.path.abspath(src))
        dst = os.path.join(outdir, base + a.suffix + '.pdf')
        try:
            msg = fix_pdf(src, dst, a.mode, a.width, a.force, not a.no_check)
        except Exception as e:  # keep going with the other files
            msg = 'ERROR: %s' % e; ok = False
        if 'FAILED' in msg:
            ok = False
        print('%s: %s' % (os.path.basename(src), msg))
    return 0 if ok else 1


if __name__ == '__main__':
    sys.exit(main())
