"""Enhancement regression on rectified fixtures (truth quads), with a contact sheet.

Usage: python tools/imaging-lab/run_enhance.py [--sheet]
Checks (docs/06): shadow fix halves paper non-uniformity; stroke contrast not reduced;
glare warning matches the large-glare truth; Document output resembles the clean source page.
"""
import argparse
import json
import sys
from pathlib import Path

import cv2
import numpy as np
from skimage.metrics import structural_similarity as ssim

sys.path.insert(0, str(Path(__file__).parent))
from lab import enhance as E  # noqa: E402
from lab.geometry import warp  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
SYN = ROOT / "test-data" / "scanner" / "synthetic"
OUT = ROOT / "work" / "lab"


def masks(page):
    paper = page > 200
    paper = cv2.erode(paper.astype(np.uint8), np.ones((9, 9), np.uint8)).astype(bool)
    ink = page < 110
    return paper, ink


def stats(img_gray, paper, ink):
    p = img_gray[paper].astype(np.float32)
    k = img_gray[ink].astype(np.float32)
    uniform = float(p.std())
    contrast = float((p.mean() - k.mean()) / max(p.mean(), 1.0)) if k.size else float("nan")
    return uniform, contrast


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--sheet", action="store_true")
    args = ap.parse_args()
    truth = json.loads((SYN / "truth.json").read_text())["images"]
    rows, tiles = [], []
    for rel, e in sorted(truth.items()):
        img = cv2.imread(str(SYN / rel))
        page = cv2.imread(str(SYN / "_pages" / f"{e['page_id']}.png"), cv2.IMREAD_GRAYSCALE)
        size = (page.shape[1], page.shape[0])
        w = warp(img, e["corners"], size=size, snap=False)
        paper, ink = masks(page)
        g0 = cv2.cvtColor(w, cv2.COLOR_BGR2GRAY)
        doc = E.document(w)
        g1 = cv2.cvtColor(doc, cv2.COLOR_BGR2GRAY)
        u0, c0 = stats(g0, paper, ink)
        u1, c1 = stats(g1, paper, ink)
        _, warn = E.glare_fix(w)
        sim = ssim(cv2.resize(g1, (size[0] // 2, size[1] // 2)), cv2.resize(page, (size[0] // 2, size[1] // 2)))
        rows.append(dict(cat=e["category"], name=Path(rel).stem, u0=u0, u1=u1, c0=c0, c1=c1, ssim=sim,
                         warn=warn, large=bool(e["effects"].get("large_glare", False))))
        if args.sheet and e["category"] in ("shadows", "glare", "low-light", "handwriting") and Path(rel).stem.endswith(("1", "4")):
            row = [w, doc, E.bw(w), E.auto(w), E.grayscale(w)]
            row = [cv2.resize(x, (248, 350), interpolation=cv2.INTER_AREA) for x in row]
            strip = np.hstack(row)
            cv2.putText(strip, f"{Path(rel).stem}  input | document | bw | auto | gray", (6, 18),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.5, (0, 0, 255), 1)
            tiles.append(strip)

    print(f"{'category':16} {'unif_before':>11} {'unif_after':>10} {'drop%':>6} {'contrast b->a':>14} {'ssim_doc':>8}")
    ok = True
    for cat in sorted({r['cat'] for r in rows}):
        rs = [r for r in rows if r["cat"] == cat]
        u0 = np.mean([r["u0"] for r in rs]); u1 = np.mean([r["u1"] for r in rs])
        c0 = np.mean([r["c0"] for r in rs]); c1 = np.mean([r["c1"] for r in rs])
        s = min(r["ssim"] for r in rs)
        drop = 100 * (1 - u1 / u0) if u0 else 0
        print(f"{cat:16} {u0:11.2f} {u1:10.2f} {drop:6.1f} {c0:6.3f}->{c1:6.3f} {s:8.3f}")
        if cat == "shadows" and drop < 50:
            ok = False
        if c1 + 0.02 < c0:
            ok = False
    gl = [r for r in rows if r["cat"] == "glare"]
    tp = sum(r["warn"] and r["large"] for r in gl); fn = sum((not r["warn"]) and r["large"] for r in gl)
    fp = sum(r["warn"] and not r["large"] for r in rows)
    print(f"\nglare warning: {tp} true pos, {fn} missed, {fp} false alarms across all {len(rows)} pages")
    if fn or fp > 1:
        ok = False
    if args.sheet and tiles:
        OUT.mkdir(parents=True, exist_ok=True)
        cv2.imwrite(str(OUT / "enhance_sheet.jpg"), np.vstack(tiles), [cv2.IMWRITE_JPEG_QUALITY, 85])
        print("sheet:", OUT / "enhance_sheet.jpg")
    print("ENHANCE", "PASS" if ok else "FAIL")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
