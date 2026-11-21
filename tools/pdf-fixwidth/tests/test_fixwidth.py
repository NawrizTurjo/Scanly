"""Edge-case tests for fixwidth.py. Run: python tests/test_fixwidth.py"""
import os, sys, tempfile
import fitz
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..'))
import fixwidth

tmp = tempfile.mkdtemp()


def make():
    d = fitz.open()
    # p1 portrait A4
    p = d.new_page(width=595, height=842)
    p.insert_text((72, 100), 'Page one portrait text', fontsize=20)
    p.draw_rect(fitz.Rect(20, 20, 575, 822), color=(1, 0, 0), width=2)
    # p2 landscape with highlight, ink, and a link to page 1
    p = d.new_page(width=842, height=595)
    p.insert_text((72, 100), 'Landscape slide text here', fontsize=20)
    p.draw_rect(fitz.Rect(10, 10, 832, 585), color=(0, 0, 1), width=2)
    hl = p.add_highlight_annot(p.search_for('Landscape slide')[0])
    p.add_ink_annot([[(100, 300), (200, 350), (300, 300)]])
    p.insert_link({'kind': fitz.LINK_GOTO, 'from': fitz.Rect(72, 80, 300, 105), 'page': 0, 'to': fitz.Point(0, 0)})
    # p3 portrait page with /Rotate 90 (displays landscape)
    p = d.new_page(width=595, height=842)
    p.insert_text((72, 100), 'Rotated page text', fontsize=20)
    p.draw_rect(fitz.Rect(30, 30, 565, 812), color=(0, 0.6, 0), width=2)
    p.set_rotation(90)
    # p4 wide page with a cropbox offset (only middle part visible)
    p = d.new_page(width=1000, height=842)
    p.insert_text((300, 200), 'Cropped visible text', fontsize=20)
    p.draw_rect(fitz.Rect(250, 50, 950, 800), color=(0.5, 0, 0.5), width=2)
    p.set_cropbox(fitz.Rect(200, 0, 1000, 842))
    d.set_toc([[1, 'One', 1], [1, 'Two', 2], [2, 'Rotated', 3], [1, 'Cropped', 4]])
    d.set_page_labels([{'startpage': 0, 'prefix': 'A-', 'style': 'D', 'firstpagenum': 1}])
    src = os.path.join(tmp, 'mixed.pdf'); d.save(src); return src


src = make()
fails = 0
for mode in ['common', 'max', 'min', 'letter']:
    dst = os.path.join(tmp, 'out_%s.pdf' % mode)
    msg = fixwidth.fix_pdf(src, dst, mode=mode, force=True)
    o = fitz.open(dst); a = fitz.open(src)
    w = {round(p.rect.width, 1) for p in o}
    ok = len(w) == 1 and 'FAILED' not in msg and 'WARNING' not in msg
    ok &= len(o.get_toc()) == 4 and o[1].get_label() == 'A-2'
    links = o[1].get_links(); ok &= len(links) == 1 and links[0]['page'] == 0
    # annotations still sit on the same words
    pg = o[1]
    an = [(x.type[1], fitz.Rect(x.rect)) for x in pg.annots()]; ok &= len(an) == 2
    hlr = [r for t, r in an if t == 'Highlight'][0]
    words = pg.get_text('words', clip=hlr)
    ok &= any('Landscape' in x[4] for x in words)
    # aspect ratios preserved
    for i in range(a.page_count):
        ok &= abs(a[i].rect.width / a[i].rect.height - o[i].rect.width / o[i].rect.height) < 0.01
    print(mode, 'OK ' if ok else 'BAD', msg, sorted(w))
    fails += not ok

# already-uniform file is skipped
d = fitz.open(); [d.new_page(width=595, height=842) for _ in range(3)]
u = os.path.join(tmp, 'u.pdf'); d.save(u)
m = fixwidth.fix_pdf(u, os.path.join(tmp, 'u-fixed.pdf'))
print('uniform', 'OK ' if 'already uniform' in m else 'BAD', m); fails += 'already uniform' not in m

# render the max-mode result so a human can look at it
o = fitz.open(os.path.join(tmp, 'out_common.pdf'))
for i, p in enumerate(o):
    p.get_pixmap(dpi=30).save(os.path.join(tmp, 'p%d.png' % (i + 1)))
print('renders in', tmp)
print('FAILURES:', fails)
sys.exit(1 if fails else 0)
