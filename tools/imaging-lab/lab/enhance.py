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


def document(img):
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


FILTERS = {"ORIGINAL": lambda x: x, "AUTO": auto, "DOCUMENT": document, "BW": bw, "GRAY": grayscale, "WHITEBOARD": whiteboard}
