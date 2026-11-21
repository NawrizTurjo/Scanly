"""Contact sheet of the detector on the private real photos (no ground truth: judged by eye).

Usage: python tools/imaging-lab/real_sheet.py [--live]   -> work/lab/real_sheet.jpg
"""
import argparse
import glob
import sys
import time
from pathlib import Path

import cv2
import numpy as np

sys.path.insert(0, str(Path(__file__).parent))
from lab.detect import detect_quad, detect_quad_live  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--live", action="store_true", help="use the camera-overlay detector on 640 px luma frames")
    ap.add_argument("--cols", type=int, default=8)
    args = ap.parse_args()
    files = sorted(glob.glob(str(ROOT / "test-data/scanner/real/**/*.jpg"), recursive=True))
    # "Turjo" holds Telegram-compressed copies of "Turjo-Real"; use the originals when both exist.
    if any("Turjo-Real" in f for f in files):
        files = [f for f in files if Path(f).parent.name != "Turjo"]
    tiles, misses, times = [], [], []
    for i, f in enumerate(files):
        im = cv2.imread(f)
        h, w = im.shape[:2]
        t = time.perf_counter()
        if args.live:
            s = 640.0 / max(h, w)
            luma = cv2.cvtColor(cv2.resize(im, (round(w * s), round(h * s)), interpolation=cv2.INTER_AREA), cv2.COLOR_BGR2GRAY)
            q, c = detect_quad_live(luma)
            q = None if q is None else q / s
        else:
            q, c = detect_quad(im)
        times.append(time.perf_counter() - t)
        v = im.copy()
        if q is not None:
            cv2.polylines(v, [np.int32(q)], True, (0, 0, 255), max(3, w // 150))
        else:
            misses.append(i)
        v = cv2.resize(v, (200, int(200 * h / w)))
        label = f"{i} {'MISS' if q is None else f'{c:.2f}'}"
        cv2.putText(v, label, (4, 18), cv2.FONT_HERSHEY_SIMPLEX, 0.55, (0, 0, 0), 3)
        cv2.putText(v, label, (4, 18), cv2.FONT_HERSHEY_SIMPLEX, 0.55, (255, 255, 0), 1)
        tiles.append(v)
    hmax = max(t.shape[0] for t in tiles)
    tiles = [cv2.copyMakeBorder(t, 0, hmax - t.shape[0], 0, 0, cv2.BORDER_CONSTANT, value=(40, 40, 40)) for t in tiles]
    while len(tiles) % args.cols:
        tiles.append(np.full_like(tiles[0], 40))
    sheet = np.vstack([np.hstack(tiles[k:k + args.cols]) for k in range(0, len(tiles), args.cols)])
    out = ROOT / "work" / "lab" / ("real_sheet_live.jpg" if args.live else "real_sheet.jpg")
    out.parent.mkdir(parents=True, exist_ok=True)
    cv2.imwrite(str(out), sheet, [cv2.IMWRITE_JPEG_QUALITY, 80])
    print(f"{len(files)} photos, {len(misses)} misses {misses}, median {1000 * np.median(times):.0f} ms -> {out}")


if __name__ == "__main__":
    main()
