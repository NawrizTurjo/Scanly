"""Write Python reference outputs that the Kotlin core:imaging port must reproduce.

Output: test-data/imaging-goldens/
  inputs/<name>.jpg            copies of the chosen fixture photos
  goldens.json                 per input: detected quad (TL,TR,BR,BL), confidence, warp size, glare warning
  warped/<name>.png            warp of the detected quad at the golden size (snap on)
  filters/<name>_<FILTER>.png  each filter applied to warped/<name>.png
Usage: python tools/imaging-lab/make_goldens.py
"""
import json
import shutil
import sys
from pathlib import Path

import cv2

sys.path.insert(0, str(Path(__file__).parent))
from lab import enhance as E  # noqa: E402
from lab.detect import detect_quad  # noqa: E402
from lab.geometry import output_size, warp  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
SYN = ROOT / "test-data" / "scanner" / "synthetic"
SPECIAL = ROOT / "test-data" / "special"
OUT = ROOT / "test-data" / "imaging-goldens"

PICKS = [
    SYN / "clean" / "clean_01.jpg", SYN / "clean" / "clean_05.jpg", SYN / "skewed" / "skewed_02.jpg",
    SYN / "skewed" / "skewed_04.jpg", SYN / "shadows" / "shadows_01.jpg", SYN / "glare" / "glare_04.jpg",
    SYN / "low-light" / "low-light_02.jpg", SYN / "busy-background" / "busy-background_03.jpg",
    SYN / "handwriting" / "handwriting_04.jpg", SYN / "tables" / "tables_05.jpg",
    SPECIAL / "id-cards" / "id-cards_03.jpg", SPECIAL / "whiteboards" / "whiteboards_02.jpg",
]
FILTER_INPUTS = {"shadows_01", "low-light_02", "handwriting_04"}
WARP_LONG = 700  # keep goldens small; the Kotlin test warps to the same explicit size


def main():
    if OUT.exists():
        shutil.rmtree(OUT)
    for d in ("inputs", "warped", "filters"):
        (OUT / d).mkdir(parents=True)
    goldens = {}
    for p in PICKS:
        name = p.stem
        shutil.copy(p, OUT / "inputs" / p.name)
        img = cv2.imread(str(p))
        quad, conf = detect_quad(img)
        w, h = output_size(quad, snap=True)
        s = WARP_LONG / float(max(w, h))
        size = (int(round(w * s)), int(round(h * s)))
        warped = warp(img, quad, size=size)
        cv2.imwrite(str(OUT / "warped" / f"{name}.png"), warped)
        _, warn = E.glare_fix(warped)
        goldens[name] = {
            "input": f"inputs/{p.name}", "quad": [[round(float(x), 2), round(float(y), 2)] for x, y in quad],
            "confidence": round(conf, 3), "output_size_snapped": [w, h], "warp_size": list(size),
            "glare_warning": warn,
        }
        if name in FILTER_INPUTS:
            for f, fn in E.FILTERS.items():
                if f == "ORIGINAL":
                    continue
                cv2.imwrite(str(OUT / "filters" / f"{name}_{f}.png"), fn(warped))
    meta = {"version": 1, "generator": "tools/imaging-lab/make_goldens.py", "opencv": cv2.__version__,
            "tolerances": {"quad_px": 3.0, "filter_mean_abs": 3.0, "filter_max_share_over_32": 0.01},
            "items": goldens}
    (OUT / "goldens.json").write_text(json.dumps(meta, indent=2))
    total = sum(f.stat().st_size for f in OUT.rglob("*") if f.is_file())
    print(f"{len(goldens)} goldens, {total / 1e6:.1f} MB -> {OUT}")


if __name__ == "__main__":
    main()
