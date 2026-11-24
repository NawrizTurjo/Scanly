"""Enhancement filters for warped pages (reference for core:imaging Enhancer).

All filters take and return 8-bit BGR images of the warped page. Kernel sizes scale with the
page width so results don't depend on resolution.
"""
import numpy as np
import cv2


def _odd(n):
    n = max(3, int(round(n)))
    return n if n % 2 else n + 1


def background(channel, page_w):
    """Paper illumination estimate: close (removes dark strokes) then a large median blur."""
    k = _odd(page_w / 60.0)
    closed = cv2.morphologyEx(channel, cv2.MORPH_CLOSE, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (k, k)))
    small = cv2.resize(closed, None, fx=0.25, fy=0.25, interpolation=cv2.INTER_AREA)
    bg = cv2.medianBlur(small, _odd(page_w / 4.0 / 18.0))
    return cv2.resize(bg, (channel.shape[1], channel.shape[0]), interpolation=cv2.INTER_LINEAR)


def paper_gain(img):
    """How much background division will brighten the paper (and its sensor noise): 255 / paper level."""
    v = cv2.cvtColor(img, cv2.COLOR_BGR2HSV)[:, :, 2]
    return 255.0 / max(float(np.percentile(v, 60)), 1.0)


def denoise_for_gain(img, gain):
    """Dark pages get boosted 2-3x by the division, so smooth them first in proportion to that boost."""
    if gain < 1.5:
        return img
    d = max(5, _odd(img.shape[1] / 250.0))
    out = cv2.bilateralFilter(img, d, 12.0 * gain, d)
    return cv2.medianBlur(out, 3) if gain >= 2.2 else out


def divide_background(img):
    """Per-channel division by the paper estimate: flat white paper, ink keeps its colour."""
    img = denoise_for_gain(img, paper_gain(img))
    w = img.shape[1]
    out = np.empty_like(img)
    for i in range(3):
        ch = img[:, :, i]
        bg = background(ch, w).astype(np.float32)
        out[:, :, i] = np.clip(ch.astype(np.float32) / np.maximum(bg, 1.0) * 255.0, 0, 255).astype(np.uint8)
    return out


def _stretch(img, lo_pct=1.0, hi_pct=60.0, gamma=1.0):
    """Map the lo percentile to 0 and the paper level (hi percentile) to 255."""
    g = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY) if img.ndim == 3 else img
    lo, hi = np.percentile(g, lo_pct), np.percentile(g, hi_pct)
    if hi - lo < 10:
        return img
    lut = np.clip((np.arange(256, dtype=np.float32) - lo) / (hi - lo), 0, 1) ** gamma * 255.0
    return cv2.LUT(img, lut.astype(np.uint8))


def _unsharp(img, sigma, amount):
    blur = cv2.GaussianBlur(img, (0, 0), sigma)
    return cv2.addWeighted(img, 1 + amount, blur, -amount, 0)


def document_v1(img):
    """White page, dark readable strokes, colour ink kept (the default 'white page' look)."""
    gain = paper_gain(img)
    flat = divide_background(img)
    out = _stretch(flat, lo_pct=0.5, hi_pct=50.0, gamma=1.25)
    # Desaturate near-white paper so tinted paper/lighting goes truly white.
    hsv = cv2.cvtColor(out, cv2.COLOR_BGR2HSV)
    paper = hsv[:, :, 2] > 215
    hsv[:, :, 1][paper] = (hsv[:, :, 1][paper] * 0.3).astype(np.uint8)
    out = cv2.cvtColor(hsv, cv2.COLOR_HSV2BGR)
    amount = 0.5 if gain < 1.5 else 0.2  # sharpening a noisy page just sharpens the noise
    return _unsharp(out, sigma=max(0.8, img.shape[1] / 1600.0), amount=amount)


def grayscale(img):
    g = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    g = _stretch(g, lo_pct=0.5, hi_pct=97.0)
    return cv2.cvtColor(g, cv2.COLOR_GRAY2BGR)


def bw(img):
    """Adaptive threshold on the shadow-corrected page, then remove isolated specks."""
    gain = paper_gain(img)
    g = cv2.cvtColor(divide_background(img), cv2.COLOR_BGR2GRAY)
    block = _odd(img.shape[1] / 40.0)
    c = 12 + 6 * max(0.0, gain - 1.5)
    th = cv2.adaptiveThreshold(g, 255, cv2.ADAPTIVE_THRESH_GAUSSIAN_C, cv2.THRESH_BINARY, block, c)
    inv = 255 - th
    n, labels, stats, _ = cv2.connectedComponentsWithStats(inv, connectivity=8)
    min_area = max(2, int((img.shape[1] / 1240.0) ** 2 * (6 if gain < 1.5 else 14)))
    keep = np.zeros(n, bool)
    keep[1:] = stats[1:, cv2.CC_STAT_AREA] >= min_area
    inv = np.where(keep[labels], 255, 0).astype(np.uint8)
    return cv2.cvtColor(255 - inv, cv2.COLOR_GRAY2BGR)


def auto(img):
    """Gentle: gray-world white balance + CLAHE on L. Keeps the photo look."""
    f = img.astype(np.float32)
    means = f.reshape(-1, 3).mean(axis=0)
    f *= (means.mean() / np.maximum(means, 1.0)) ** 0.6
    img = np.clip(f, 0, 255).astype(np.uint8)
    lab = cv2.cvtColor(img, cv2.COLOR_BGR2LAB)
    clahe = cv2.createCLAHE(clipLimit=2.0, tileGridSize=(8, 8))
    lab[:, :, 0] = clahe.apply(lab[:, :, 0])
    return cv2.cvtColor(lab, cv2.COLOR_LAB2BGR)


def whiteboard(img):
    """Board goes white, marker colours get more saturated."""
    flat = divide_background(img)
    flat = _stretch(flat, lo_pct=0.5, hi_pct=60.0, gamma=1.1)
    hsv = cv2.cvtColor(flat, cv2.COLOR_BGR2HSV).astype(np.float32)
    hsv[:, :, 1] = np.clip(hsv[:, :, 1] * 1.6, 0, 255)
    return cv2.cvtColor(hsv.astype(np.uint8), cv2.COLOR_HSV2BGR)


def adjust(img, brightness=0.0, contrast=0.0):
    """Final sliders, both in -1..1: contrast scales around mid-grey, brightness shifts."""
    a = 1.0 + contrast
    b = 128.0 * (1.0 - a) + brightness * 100.0
    return cv2.convertScaleAbs(img, alpha=a, beta=b)


def glare_mask(img):
    """Specular highlights: very bright and nearly colourless, minus plain white paper (needs local contrast)."""
    hsv = cv2.cvtColor(img, cv2.COLOR_BGR2HSV)
    v, s = hsv[:, :, 2], hsv[:, :, 1]
    paper = np.percentile(v, 60)
    thresh = max(245, int(paper + 0.6 * (255 - paper)))
    m = ((v >= thresh) & (s < 40)).astype(np.uint8) * 255
    return cv2.morphologyEx(m, cv2.MORPH_OPEN, np.ones((3, 3), np.uint8))


def glare_fix(img, large_share=0.05):
    """Inpaint small specular regions. Returns (image, warning): warning when one blown region is too large to fix."""
    m = glare_mask(img)
    n, labels, stats, _ = cv2.connectedComponentsWithStats(m, connectivity=8)
    biggest = stats[1:, cv2.CC_STAT_AREA].max() / m.size if n > 1 else 0.0
    warning = biggest > large_share
    if n > 1:
        small = np.zeros_like(m)
        for i in range(1, n):
            if stats[i, cv2.CC_STAT_AREA] / m.size <= large_share:
                small[labels == i] = 255
        if np.any(small):
            small = cv2.dilate(small, np.ones((5, 5), np.uint8))
            img = cv2.inpaint(img, small, max(3, img.shape[1] // 300), cv2.INPAINT_TELEA)
    return img, bool(warning)


FILTERS = {"ORIGINAL": lambda x: x, "AUTO": auto, "DOCUMENT": document_v1, "BW": bw, "GRAY": grayscale, "WHITEBOARD": whiteboard}


# ---- v2 (2026-11-25): keep printed fills, flatten only real shadows ----

WORK_W = 512


def _solid_components(mask, min_area):
    """Keep components of at least min_area, with their holes filled (letters inside a coloured box)."""
    out = np.zeros_like(mask)
    contours, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    big = [c for c in contours if cv2.contourArea(c) >= min_area]
    cv2.drawContours(out, big, -1, 1, -1)
    return out


def _printed_mask(closed_small):
    """Printed blocks on the closed (text-free) page: coloured fills, or darker areas with step edges that don't
    behave like a shadow. Returns a uint8 mask at the small scale. Shadows have soft edges and usually run off the
    page; a printed fill is sharp, compact and often coloured."""
    lab = cv2.cvtColor(closed_small, cv2.COLOR_BGR2LAB)
    L = lab[:, :, 0].astype(np.float32)
    chroma = np.hypot(lab[:, :, 1].astype(np.float32) - 128, lab[:, :, 2].astype(np.float32) - 128)
    h, w = L.shape
    k = _odd(w / 2.5)
    env = cv2.dilate(L, cv2.getStructuringElement(cv2.MORPH_RECT, (k, k)))
    env = cv2.GaussianBlur(env, (0, 0), w / 16.0)
    coloured = (chroma > 22).astype(np.uint8)
    dark = ((L < env * 0.94) & (chroma <= 22)).astype(np.uint8)
    dark = cv2.morphologyEx(dark, cv2.MORPH_OPEN, np.ones((3, 3), np.uint8))
    out = _solid_components(cv2.morphologyEx(coloured, cv2.MORPH_OPEN, np.ones((3, 3), np.uint8)), (w * h) * 0.0005)
    # Solid dark blocks (a dark-green label, a black box) are print: no shadow on paper is this deep with a sharp
    # edge away from the page border.
    deep = cv2.morphologyEx((L < env * 0.5).astype(np.uint8), cv2.MORPH_OPEN, np.ones((3, 3), np.uint8))
    nd, dl, ds, _ = cv2.connectedComponentsWithStats(deep, connectivity=8)
    for i in range(1, nd):
        x, y, bw_, bh, area = ds[i]
        if area >= (w * h) * 0.0005 and x > 0 and y > 0 and x + bw_ < w and y + bh < h:
            out |= _solid_components((dl == i).astype(np.uint8), 0)
    gx = cv2.Sobel(L, cv2.CV_32F, 1, 0, ksize=3)
    gy = cv2.Sobel(L, cv2.CV_32F, 0, 1, ksize=3)
    grad = np.hypot(gx, gy) / 8.0
    n, labels, stats, _ = cv2.connectedComponentsWithStats(dark, connectivity=8)
    for i in range(1, n):
        x, y, bw_, bh, area = stats[i]
        if area < (w * h) * 0.0005:
            continue
        x0, y0, x1, y1 = max(0, x - 2), max(0, y - 2), min(w, x + bw_ + 2), min(h, y + bh + 2)
        comp = (labels[y0:y1, x0:x1] == i).astype(np.uint8)
        cb = comp.astype(bool)
        edge_band = cv2.morphologyEx(comp, cv2.MORPH_GRADIENT, np.ones((3, 3), np.uint8)).astype(bool)
        contrast = float(np.mean(env[y0:y1, x0:x1][cb] - L[y0:y1, x0:x1][cb]))
        edge = float(np.mean(grad[y0:y1, x0:x1][edge_band])) if np.any(edge_band) else 0.0
        sharp = contrast > 1 and edge / contrast > 0.22
        on_border = (x == 0) + (y == 0) + (x + bw_ == w) + (y + bh == h)
        fill = area / float(bw_ * bh)
        printed = sharp and on_border == 0 and (fill > 0.6 or area < w * h * 0.08)
        if printed:
            contours, _ = cv2.findContours(comp, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
            solid = np.zeros_like(comp)
            cv2.drawContours(solid, contours, -1, 1, -1)
            out[y0:y1, x0:x1] |= solid
    return cv2.dilate(out, np.ones((3, 3), np.uint8))


def _fill_from_paper(bg, printed, sw):
    """Replace the estimate under printed blocks with a weighted average of the paper around them (normalised
    convolution). Smooth across lighting gradients, unlike inpainting, which streaks inside large blocks."""
    valid = (1 - printed).astype(np.float32)
    sigma = sw / 10.0
    den = cv2.GaussianBlur(valid, (0, 0), sigma)
    out = bg.astype(np.float32)
    for c in range(3):
        num = cv2.GaussianBlur(out[:, :, c] * valid, (0, 0), sigma)
        fill = num / np.maximum(den, 1e-4)
        out[:, :, c] = np.where(printed > 0, fill, out[:, :, c])
    out = cv2.GaussianBlur(out, (0, 0), sw / 200.0)
    return np.clip(out + 0.5, 0, 255).astype(np.uint8)


def background_v2(img):
    """Per-channel paper estimate that skips printed fills: today's close+median estimate, with printed blocks cut out
    and filled from the paper around them."""
    h, w = img.shape[:2]
    s = WORK_W / float(w) if w > WORK_W else 1.0
    small = cv2.resize(img, None, fx=s, fy=s, interpolation=cv2.INTER_AREA)
    sw = small.shape[1]
    k = _odd(sw / 60.0)
    closed = cv2.morphologyEx(small, cv2.MORPH_CLOSE, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (k, k)))
    printed = _printed_mask(closed)
    bg = cv2.medianBlur(closed, _odd(sw / 18.0))
    if np.any(printed):
        bg = _fill_from_paper(bg, printed, sw)
    return cv2.resize(bg, (w, h), interpolation=cv2.INTER_LINEAR), printed


def divide_background_v2(img, with_mask=False):
    img = denoise_for_gain(img, paper_gain(img))
    bg, printed = background_v2(img)
    flat = np.clip(img.astype(np.float32) / np.maximum(bg.astype(np.float32), 1.0) * 255.0, 0, 255).astype(np.uint8)
    return (flat, printed) if with_mask else flat


def _block_weight(printed, shape):
    """Full-size 0..1 weight of printed blocks, feathered so block edges don't show a seam."""
    h, w = shape[:2]
    m = cv2.resize(printed.astype(np.float32), (w, h), interpolation=cv2.INTER_LINEAR)
    return cv2.GaussianBlur(m, (0, 0), max(1.0, w / 600.0))


def _tone(img, lo_pct=0.5, hi_pct=50.0):
    """Paper to white and the darkest ink to black, mid-tones nearly linear (fills keep their shade)."""
    g = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    lo, hi = np.percentile(g, lo_pct), np.percentile(g, hi_pct)
    if hi - lo < 10:
        return img
    x = np.clip((np.arange(256, dtype=np.float32) - lo) / (hi - lo), 0, 1)
    y = np.where(x < 0.35, 0.35 * (x / 0.35) ** 1.6, x)  # deepen only the ink end
    return cv2.LUT(img, (y * 255.0).astype(np.uint8))


def document_v2(img):
    gain = paper_gain(img)
    flat, printed = divide_background_v2(img, with_mask=True)
    out = _tone(flat)
    hsv = cv2.cvtColor(out, cv2.COLOR_BGR2HSV)
    paper = hsv[:, :, 2] > 215
    hsv[:, :, 1][paper] = (hsv[:, :, 1][paper] * 0.3).astype(np.uint8)
    out = cv2.cvtColor(hsv, cv2.COLOR_HSV2BGR)
    if np.any(printed):
        # Printed blocks keep their shade and colour relative to the paper: the black-point stretch is for ink (a dark
        # label is usually the darkest thing on the page and would go black) and the paper desaturation would bleach
        # a bright sticker.
        wgt = _block_weight(printed, img.shape)[..., None]
        out = np.clip(out.astype(np.float32) * (1 - wgt) + flat.astype(np.float32) * wgt + 0.5, 0, 255).astype(np.uint8)
    amount = 0.5 if gain < 1.5 else 0.2
    return _unsharp(out, sigma=max(0.8, img.shape[1] / 1600.0), amount=amount)


document = document_v2
FILTERS["DOCUMENT"] = document_v2
