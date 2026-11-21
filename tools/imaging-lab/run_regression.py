"""Imaging lab regression: detection accuracy and warp quality against test-data ground truth.

Usage:  python tools/imaging-lab/run_regression.py [--sheet] [--only CATEGORY]
Exit code 1 when an acceptance threshold from docs/06 is missed.
"""
import argparse
import json
import sys
import time
from pathlib import Path

import cv2
import numpy as np
from skimage.metrics import structural_similarity as ssim

sys.path.insert(0, str(Path(__file__).parent))
from lab.detect import detect_quad  # noqa: E402
from lab.geometry import corner_error, warp  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
SYN = ROOT / "test-data" / "scanner" / "synthetic"
SPECIAL = ROOT / "test-data" / "special"
OUT = ROOT / "work" / "lab"

HIT_DIAG = 0.02        # SC-1: corner within 2% of the diagonal
MEAN_DIAG = 0.015      # docs/06: mean error < 1.5% of the diagonal
DETECT_RATE = 0.90     # docs/06: >= 90% detected (synthetic)
WARP_SSIM = 0.85       # docs/06: SSIM >= 0.85 on clean/skewed


def load_sets(only):
    items = []
    t = json.loads((SYN / "truth.json").read_text())
    for rel, e in sorted(t["images"].items()):
        items.append(("synthetic", e["category"], SYN / rel, e["corners"], e.get("page_id")))
    st = json.loads((SPECIAL / "truth.json").read_text())
    for rel, e in sorted(st["images"].items()):
        corners = e.get("corners") or e.get("outer_corners")
        items.append(("special", e.get("category", rel.split("/")[0]), SPECIAL / rel, corners, None))
    if only:
        items = [i for i in items if i[1] == only]
    return items


def warp_ssim(img, quad, page_id):
    page = cv2.imread(str(SYN / "_pages" / f"{page_id}.png"), cv2.IMREAD_GRAYSCALE)
    if page is None:
        return None
    out = warp(img, quad, size=(page.shape[1], page.shape[0]), snap=False)
    g = cv2.cvtColor(out, cv2.COLOR_BGR2GRAY)
    best = -1.0
    for k in range(4):  # orientation is not known to the detector
        r = np.rot90(g, k)
        if r.shape != page.shape:
            r = cv2.resize(r, (page.shape[1], page.shape[0]), interpolation=cv2.INTER_AREA)
        a = cv2.resize(r, (page.shape[1] // 2, page.shape[0] // 2), interpolation=cv2.INTER_AREA)
        b = cv2.resize(page, (page.shape[1] // 2, page.shape[0] // 2), interpolation=cv2.INTER_AREA)
        best = max(best, ssim(a, b))
    return best


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--sheet", action="store_true", help="write work/lab/detect_sheet.jpg")
    ap.add_argument("--only")
    args = ap.parse_args()
    items = load_sets(args.only)
    rows, thumbs = [], []
    t0 = time.time()
    for group, cat, path, truth, page_id in items:
        img = cv2.imread(str(path))
        h, w = img.shape[:2]
        diag = float(np.hypot(w, h))
        quad, conf = detect_quad(img)
        if quad is None:
            rows.append(dict(group=group, cat=cat, name=path.name, found=False, mean=None, max=None, conf=conf, ssim=None))
        else:
            mean, mx = corner_error(quad, truth)
            s = warp_ssim(img, quad, page_id) if (group == "synthetic" and cat in ("clean", "skewed")) else None
            rows.append(dict(group=group, cat=cat, name=path.name, found=True, mean=mean / diag, max=mx / diag, conf=conf, ssim=s))
        if args.sheet:
            vis = img.copy()
            cv2.polylines(vis, [np.int32(truth)], True, (0, 200, 0), 3)
            if quad is not None:
                cv2.polylines(vis, [np.int32(quad)], True, (0, 0, 255), 2)
            r = rows[-1]
            label = f"{path.stem} " + ("MISS" if not r["found"] else f"{100 * r['max']:.2f}%")
            vis = cv2.resize(vis, (240, int(240 * h / w)))
            cv2.putText(vis, label, (4, 16), cv2.FONT_HERSHEY_SIMPLEX, 0.45, (0, 0, 0), 3)
            cv2.putText(vis, label, (4, 16), cv2.FONT_HERSHEY_SIMPLEX, 0.45, (255, 255, 255), 1)
            thumbs.append(vis)
    dt = time.time() - t0

    cats = sorted({(r["group"], r["cat"]) for r in rows})
    print(f"{'set':10} {'category':16} {'n':>3} {'hit%':>6} {'mean%diag':>10} {'max%diag':>9} {'ssim_min':>8}")
    for g, c in cats:
        rs = [r for r in rows if r["group"] == g and r["cat"] == c]
        hits = [r for r in rs if r["found"] and r["max"] <= HIT_DIAG]
        found = [r for r in rs if r["found"]]
        mean = np.mean([r["mean"] for r in found]) * 100 if found else float("nan")
        mx = np.max([r["max"] for r in found]) * 100 if found else float("nan")
        ss = [r["ssim"] for r in rs if r["ssim"] is not None]
        print(f"{g:10} {c:16} {len(rs):3d} {100 * len(hits) / len(rs):6.1f} {mean:10.3f} {mx:9.3f} {min(ss) if ss else float('nan'):8.3f}")
    syn = [r for r in rows if r["group"] == "synthetic"]
    syn_hit = sum(1 for r in syn if r["found"] and r["max"] <= HIT_DIAG) / max(1, len(syn))
    syn_mean = np.mean([r["mean"] for r in syn if r["found"]]) if syn else 0
    ss = [r["ssim"] for r in syn if r["ssim"] is not None]
    misses = [f"{r['cat']}/{r['name']}" + ("" if not r["found"] else f" ({100 * r['max']:.2f}%)") for r in rows if not (r["found"] and r["max"] <= HIT_DIAG)]
    print(f"\nsynthetic: hit {100 * syn_hit:.1f}% (need {100 * DETECT_RATE:.0f}%), mean error {100 * syn_mean:.3f}% diag "
          f"(need < {100 * MEAN_DIAG}%), warp SSIM min {min(ss) if ss else float('nan'):.3f} (need {WARP_SSIM}); "
          f"{len(rows)} images in {dt:.1f}s ({1000 * dt / max(1, len(rows)):.0f} ms each)")
    if misses:
        print("misses / >2%:", ", ".join(misses))
    if args.sheet and thumbs:
        OUT.mkdir(parents=True, exist_ok=True)
        cols = 8
        hmax = max(t.shape[0] for t in thumbs)
        tiles = [cv2.copyMakeBorder(t, 0, hmax - t.shape[0], 0, 0, cv2.BORDER_CONSTANT, value=(40, 40, 40)) for t in thumbs]
        while len(tiles) % cols:
            tiles.append(np.full_like(tiles[0], 40))
        sheet = np.vstack([np.hstack(tiles[i:i + cols]) for i in range(0, len(tiles), cols)])
        cv2.imwrite(str(OUT / "detect_sheet.jpg"), sheet, [cv2.IMWRITE_JPEG_QUALITY, 85])
        print("sheet:", OUT / "detect_sheet.jpg")
    ok = syn_hit >= DETECT_RATE and syn_mean < MEAN_DIAG and (not ss or min(ss) >= WARP_SSIM)
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
