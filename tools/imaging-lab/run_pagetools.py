"""Regression for deskew (SC-16), blank detection (SC-17) and ink removal (SC-15) on synthetic cases.

Cases are built from the flat source pages in test-data/scanner/synthetic/_pages so the truth is exact.
Usage: python tools/imaging-lab/run_pagetools.py
"""
import sys
from pathlib import Path

import cv2
import numpy as np
from skimage.metrics import structural_similarity as ssim

sys.path.insert(0, str(Path(__file__).parent))
import json  # noqa: E402

from lab.geometry import order_quad, output_size, warp  # noqa: E402
from lab.pagetools import deskew, estimate_skew, find_gutter, ink_fraction, is_blank, remove_ink  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
PAGES = sorted((ROOT / "test-data" / "scanner" / "synthetic" / "_pages").glob("*.png"))
rng = np.random.default_rng(7)


def rotate(img, angle):
    h, w = img.shape[:2]
    m = cv2.getRotationMatrix2D((w / 2.0, h / 2.0), angle, 1.0)
    return cv2.warpAffine(img, m, (w, h), flags=cv2.INTER_CUBIC, borderValue=(255, 255, 255))


def photo_like(img, seed):
    """Mild paper tone, shading and noise so the tests aren't on perfect renders."""
    r = np.random.default_rng(seed)
    f = img.astype(np.float32) * np.array([0.97, 0.98, 1.0])
    h, w = img.shape[:2]
    yy, xx = np.mgrid[0:h, 0:w]
    f *= (0.85 + 0.15 * (xx / w))[..., None]
    f += r.normal(0, 3, f.shape)
    return np.clip(f, 0, 255).astype(np.uint8)


def main():
    ok = True
    # --- deskew ---------------------------------------------------------------
    errs, skipped_straight = [], 0
    # Printed/table pages: their text lines are exactly level, so the applied angle is the truth.
    # (Handwriting fixtures carry their own line slant, which deskew rightly follows.)
    for i, p in enumerate([q for q in PAGES if q.name.startswith(("print", "table"))][:10]):
        page = cv2.imread(str(p))
        for angle in (-4.6, -2.3, -0.8, 1.2, 3.1, 4.9):
            img = photo_like(rotate(page, angle), i)
            est, conf = estimate_skew(img)
            errs.append(abs(est + angle))
            if abs(est + angle) > 0.3:
                print(f"  deskew miss: {p.name} applied {angle} est {est:.3f} conf {conf:.2f}")  # straightening angle is the negative of the applied one
        _, applied = deskew(photo_like(page, 99 + i))
        skipped_straight += applied == 0.0
    errs = np.array(errs)
    print(f"deskew: {len(errs)} cases, residual mean {errs.mean():.3f} deg, max {errs.max():.3f} deg (need < 0.3); "
          f"straight pages left alone {skipped_straight}/10")
    ok &= errs.max() < 0.3 and skipped_straight == 10

    # --- blank detection --------------------------------------------------------
    h, w = 1754, 1240
    blanks = []
    for k in range(6):
        b = np.full((h, w, 3), 255, np.uint8)
        b = photo_like(b, 200 + k)
        if k % 2:  # crop slop: dark edge strip
            b[:, :25] = (b[:, :25] * 0.4).astype(np.uint8)
        blanks.append(("blank", b))
    traps = []
    one_line = np.full((h, w, 3), 255, np.uint8)
    cv2.putText(one_line, "Page intentionally left blank except this line", (120, 900),
                cv2.FONT_HERSHEY_SIMPLEX, 1.1, (20, 20, 20), 2, cv2.LINE_AA)
    traps.append(("one-line", photo_like(one_line, 300)))
    pencil = photo_like(cv2.imread(str(PAGES[0])), 301).astype(np.float32)
    pencil = 255 - (255 - pencil) * 0.35  # faint pencil: strokes at ~35% darkness
    traps.append(("faint-pencil", np.clip(pencil, 0, 255).astype(np.uint8)))
    for p in PAGES[:4]:
        traps.append(("text", photo_like(cv2.imread(str(p)), 302)))
    print("blank ink fractions:", [round(ink_fraction(b), 5) for _, b in blanks])
    print("non-blank ink fractions:", [(n, round(ink_fraction(t), 5)) for n, t in traps])
    false_blank = [n for n, t in traps if is_blank(t)]
    missed_blank = sum(not is_blank(b) for _, b in blanks)
    print(f"blank: {len(blanks) - missed_blank}/{len(blanks)} blanks found, real pages dropped: {false_blank or 'none'}")
    ok &= not false_blank  # losing a real page is never acceptable; a missed split is
    ok &= missed_blank == 0

    # --- ink removal ------------------------------------------------------------
    rates, sims = [], []
    # Printed (black) pages only: the handwriting fixtures are in blue ink, which this tool removes by design.
    for i, p in enumerate([q for q in PAGES if q.name.startswith(("print", "table"))][:6]):
        clean = photo_like(cv2.imread(str(p)), 400 + i)
        marked = clean.copy()
        truth = np.zeros(clean.shape[:2], np.uint8)
        for k in range(14):
            colour = (200, 60, 30) if k % 2 else (40, 40, 210)  # blue / red pen (BGR)
            pts = rng.integers([80, 150], [1160, 1650], size=(4, 2)).reshape(-1, 1, 2)
            cv2.polylines(marked, [pts], False, colour, 4, cv2.LINE_AA)
            cv2.polylines(truth, [pts], False, 255, 4, cv2.LINE_AA)
        out = marked
        for c in ("blue", "red"):
            out, _ = remove_ink(out, c)
        # Only pen-over-paper pixels can be restored; where pen crossed print the print is gone by design.
        ink_px = (truth > 0) & (cv2.cvtColor(clean, cv2.COLOR_BGR2GRAY) > 150)
        removed = np.abs(out.astype(np.int16) - clean.astype(np.int16)).max(axis=2)[ink_px] < 60
        rates.append(removed.mean())
        g0 = cv2.cvtColor(clean, cv2.COLOR_BGR2GRAY)
        g1 = cv2.cvtColor(out, cv2.COLOR_BGR2GRAY)
        pen_zone = cv2.dilate(truth, np.ones((9, 9), np.uint8)) > 0
        g1[pen_zone] = g0[pen_zone]  # where pen crossed print the print is gone by design; score everything else
        sims.append(ssim(g0, g1, data_range=255))
    print(f"ink removal: {100 * np.mean(rates):.1f}% of pen pixels removed (need 95), "
          f"black-text SSIM {np.min(sims):.3f} (need 0.95)")
    ok &= np.mean(rates) >= 0.95 and np.min(sims) >= 0.95

    # --- book gutter (MD-2) -----------------------------------------------------
    special = ROOT / "test-data" / "special"
    truth = json.loads((special / "truth.json").read_text())["images"]
    gerr = []
    for rel, e in sorted(truth.items()):
        if e["category"] != "books":
            continue
        img = cv2.imread(str(special / rel))
        q = order_quad(e["corners"])
        w, h = output_size(q, snap=False)
        spread = warp(img, q, size=(w, h), snap=False)
        m = cv2.getPerspectiveTransform(q, np.float32([[0, 0], [w - 1, 0], [w - 1, h - 1], [0, h - 1]]))
        gx = cv2.perspectiveTransform(np.float32(e["gutter"]).reshape(-1, 1, 2), m).reshape(-1, 2)[:, 0].mean()
        x, _ = find_gutter(spread)
        gerr.append(abs(x - gx) / w)
    print(f"book gutter: {len(gerr)} spreads, max error {100 * max(gerr):.2f}% of width (need < 1%)")
    ok &= max(gerr) < 0.01

    print("PAGETOOLS", "PASS" if ok else "FAIL")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
