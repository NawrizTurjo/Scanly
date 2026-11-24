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

import numpy as np
from pathlib import Path

import cv2

sys.path.insert(0, str(Path(__file__).parent))
from lab import enhance as E  # noqa: E402
from lab.detect import detect_quad, detect_quad_live  # noqa: E402
from lab.geometry import output_size, warp  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
SYN = ROOT / "test-data" / "scanner" / "synthetic"
SPECIAL = ROOT / "test-data" / "special"
DESK = ROOT / "test-data" / "scanner" / "desk"
OUT = ROOT / "test-data" / "imaging-goldens"

PICKS = [
    SYN / "clean" / "clean_01.jpg", SYN / "clean" / "clean_05.jpg", SYN / "skewed" / "skewed_02.jpg",
    SYN / "skewed" / "skewed_04.jpg", SYN / "shadows" / "shadows_01.jpg", SYN / "glare" / "glare_04.jpg",
    SYN / "low-light" / "low-light_02.jpg", SYN / "busy-background" / "busy-background_03.jpg",
    SYN / "handwriting" / "handwriting_04.jpg", SYN / "tables" / "tables_05.jpg",
    SPECIAL / "id-cards" / "id-cards_03.jpg", SPECIAL / "whiteboards" / "whiteboards_02.jpg",
    DESK / "desk-sheet" / "desk-sheet_05.jpg", DESK / "dim-desk" / "dim-desk_02.jpg",
    DESK / "clipped-sheet" / "clipped-sheet_03.jpg", DESK / "notebook-left" / "notebook-left_02.jpg",
    DESK / "notebook-right" / "notebook-right_01.jpg", DESK / "notebook-spread" / "notebook-spread_02.jpg",
    DESK / "occluded" / "occluded_02.jpg",
]
# id-cards are also checked with the ID-mode aspect prior (1.586)
ASPECT_PICKS = {"id-cards_03": 1.586}
FILTER_INPUTS = {"shadows_01", "low-light_02", "handwriting_04", "id-cards_03"}
WARP_LONG = 700  # keep goldens small; the Kotlin test warps to the same explicit size


def main():
    if OUT.exists():
        shutil.rmtree(OUT)
    for d in ("inputs", "warped", "filters", "live"):
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
        # Live overlay input: a 640 px camera analysis frame, luma only (the Y plane).
        ls = 640.0 / max(img.shape[:2])
        luma = cv2.cvtColor(cv2.resize(img, (round(img.shape[1] * ls), round(img.shape[0] * ls)), interpolation=cv2.INTER_AREA), cv2.COLOR_BGR2GRAY)
        cv2.imwrite(str(OUT / "live" / f"{name}.png"), luma)
        lq, lconf = detect_quad_live(luma)
        goldens[name] = {
            "input": f"inputs/{p.name}", "quad": [[round(float(x), 2), round(float(y), 2)] for x, y in quad],
            "confidence": round(conf, 3), "output_size_snapped": [w, h], "warp_size": list(size),
            "glare_warning": warn,
            **({"aspect_prior": {"value": ASPECT_PICKS[name], "quad": [[round(float(x), 2), round(float(y), 2)]
                                                                     for x, y in detect_quad(img, aspect_prior=ASPECT_PICKS[name])[0]]}}
               if name in ASPECT_PICKS else {}),
            "live": {"frame": f"live/{name}.png", "size": [luma.shape[1], luma.shape[0]],
                     "quad": None if lq is None else [[round(float(x), 2), round(float(y), 2)] for x, y in lq],
                     "confidence": round(lconf, 3)},
        }
        if name in FILTER_INPUTS:
            for f, fn in E.FILTERS.items():
                if f == "ORIGINAL":
                    continue
                cv2.imwrite(str(OUT / "filters" / f"{name}_{f}.png"), fn(warped))
    # Printed fills (user report 2026-11-25): the Document filter's block path, at half size.
    from run_enhance import printed_blocks_page  # noqa: E402
    (OUT / "blocks").mkdir()
    blocks = cv2.resize(printed_blocks_page(), (1240, 1754), interpolation=cv2.INTER_AREA)
    cv2.imwrite(str(OUT / "blocks" / "printed_blocks.png"), blocks)
    cv2.imwrite(str(OUT / "blocks" / "printed_blocks_DOCUMENT.png"), E.document(blocks))
    meta = {"version": 1, "generator": "tools/imaging-lab/make_goldens.py", "opencv": cv2.__version__,
            "tolerances": {"quad_px": 3.0, "live_quad_px": 1.5, "filter_mean_abs": 3.0, "filter_max_share_over_32": 0.01},
            "items": goldens}
    (OUT / "goldens.json").write_text(json.dumps(meta, indent=2))
    total = sum(f.stat().st_size for f in OUT.rglob("*") if f.is_file())
    print(f"{len(goldens)} goldens, {total / 1e6:.1f} MB -> {OUT}")


if __name__ == "__main__" and "--pagetools" not in sys.argv:
    main()


def page_tool_goldens():
    """Deskew / blank / ink-removal / gutter references for the Kotlin port (written into the same folder)."""
    from lab.pagetools import estimate_skew, find_gutter, ink_fraction, remove_ink
    out = OUT / "pagetools"
    out.mkdir(exist_ok=True)
    items = {}
    pages = sorted((SYN / "_pages").glob("print_0[1-3].png"))
    for p in pages:
        page = cv2.imread(str(p))
        page = cv2.resize(page, (620, 877), interpolation=cv2.INTER_AREA)
        for angle in (-3.2, 1.7):
            m = cv2.getRotationMatrix2D((310, 438.5), angle, 1.0)
            rot = cv2.warpAffine(page, m, (620, 877), flags=cv2.INTER_CUBIC, borderValue=(255, 255, 255))
            name = f"skew_{p.stem}_{angle:+.1f}.png"
            cv2.imwrite(str(out / name), rot)
            est, conf = estimate_skew(rot)
            items[name] = {"tool": "skew", "applied": angle, "angle": round(est, 3), "confidence": round(conf, 3)}
        name = f"ink_{p.stem}.png"
        cv2.imwrite(str(out / name), page)
        items[name] = {"tool": "ink_fraction", "value": round(ink_fraction(page), 6)}
    blank = np.full((877, 620, 3), 246, np.uint8)
    cv2.imwrite(str(out / "ink_blank.png"), blank)
    items["ink_blank.png"] = {"tool": "ink_fraction", "value": round(ink_fraction(blank), 6)}
    marked = cv2.resize(cv2.imread(str(pages[0])), (620, 877), interpolation=cv2.INTER_AREA)
    rng = np.random.default_rng(3)
    for k in range(8):
        pts = rng.integers([40, 80], [580, 820], size=(4, 2)).reshape(-1, 1, 2)
        cv2.polylines(marked, [pts], False, (200, 60, 30) if k % 2 else (40, 40, 210), 3, cv2.LINE_AA)
    cv2.imwrite(str(out / "pen_marked.png"), marked)
    for colour in ("blue", "red"):
        res, _ = remove_ink(marked, colour)
        cv2.imwrite(str(out / f"pen_removed_{colour}.png"), res)
        items[f"pen_removed_{colour}.png"] = {"tool": "remove_ink", "input": "pen_marked.png", "colour": colour}
    special = ROOT / "test-data" / "special"
    truth = json.loads((special / "truth.json").read_text())["images"]
    for rel, e in sorted(truth.items()):
        if e["category"] != "books":
            continue
        img = cv2.imread(str(special / rel))
        q = np.float32(e["corners"])
        w, h = output_size(q, snap=False)
        s = 900.0 / max(w, h)
        spread = warp(img, q, size=(round(w * s), round(h * s)), snap=False)
        name = f"spread_{Path(rel).stem}.png"
        cv2.imwrite(str(out / name), spread)
        x, conf = find_gutter(spread)
        items[name] = {"tool": "gutter", "x": x, "confidence": round(conf, 3)}
    (out / "pagetools.json").write_text(json.dumps({
        "tolerances": {"angle_deg": 0.05, "ink_fraction_abs": 0.0005, "pixel_mean_abs": 3.0, "gutter_px": 2},
        "items": items}, indent=2))
    print(f"page tools: {len(items)} items -> {out}")


if __name__ == "__main__" and "--pagetools" in sys.argv:
    page_tool_goldens()
