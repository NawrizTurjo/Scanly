"""Page-level tools on warped pages: deskew (SC-16), blank detection (SC-17), ink removal (SC-15)."""
import numpy as np
import cv2

from .enhance import background, document


def _binary_ink(gray):
    """Ink = 1. Otsu on the background-divided page so shading doesn't count as ink."""
    w = gray.shape[1]
    bg = background(gray, w).astype(np.float32)
    flat = np.clip(gray.astype(np.float32) / np.maximum(bg, 1.0) * 255.0, 0, 255).astype(np.uint8)
    _, th = cv2.threshold(flat, 0, 255, cv2.THRESH_BINARY_INV + cv2.THRESH_OTSU)
    return th


def _profile_score(ink, angle):
    h, w = ink.shape
    m = cv2.getRotationMatrix2D((w / 2.0, h / 2.0), angle, 1.0)
    r = cv2.warpAffine(ink, m, (w, h), flags=cv2.INTER_NEAREST, borderValue=0)
    rows = r.sum(axis=1, dtype=np.float64)
    return float(np.var(rows))


def estimate_skew(img, max_angle=5.0, work_width=900):
    """Return (angle_degrees, confidence). Rotating the page by +angle (OpenCV convention, CCW) straightens it.
    Coarse 0.5 deg search then 0.05 deg refinement on the projection-profile variance."""
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY) if img.ndim == 3 else img
    s = work_width / float(gray.shape[1])
    gray = cv2.resize(gray, (work_width, int(round(gray.shape[0] * s))), interpolation=cv2.INTER_AREA)
    ink = (_binary_ink(gray) > 0).astype(np.uint8)
    if ink.mean() < 0.002:  # nearly blank: nothing to align
        return 0.0, 0.0
    coarse = np.arange(-max_angle, max_angle + 1e-6, 0.5)
    scores = np.array([_profile_score(ink, a) for a in coarse])
    best = coarse[int(np.argmax(scores))]
    fine = np.arange(best - 0.5, best + 0.5 + 1e-6, 0.025)
    fscores = np.array([_profile_score(ink, a) for a in fine])
    angle = float(fine[int(np.argmax(fscores))])
    # Confidence: how much the best alignment stands out from the typical one.
    conf = float(1.0 - np.median(scores) / max(scores.max(), 1e-9))
    return angle, conf


def deskew(img, min_conf=0.25, min_angle=0.3, max_angle=5.0):
    """Rotate by the estimated skew when confident; returns (image, applied_angle)."""
    angle, conf = estimate_skew(img, max_angle)
    if conf < min_conf or abs(angle) < min_angle:
        return img, 0.0
    h, w = img.shape[:2]
    m = cv2.getRotationMatrix2D((w / 2.0, h / 2.0), angle, 1.0)
    return cv2.warpAffine(img, m, (w, h), flags=cv2.INTER_CUBIC, borderMode=cv2.BORDER_REPLICATE), angle


def ink_fraction(img):
    """Share of ink pixels after the Document filter, ignoring a 4% border (edge shadows, crop slop)."""
    d = cv2.cvtColor(document(img), cv2.COLOR_BGR2GRAY)
    h, w = d.shape
    my, mx = int(h * 0.04), int(w * 0.04)
    core = d[my:h - my, mx:w - mx]
    ink = core < 140
    ink = cv2.morphologyEx(ink.astype(np.uint8), cv2.MORPH_OPEN, np.ones((2, 2), np.uint8))
    return float(ink.mean())


def is_blank(img, threshold=0.001):
    return ink_fraction(img) < threshold


HUES = {"blue": 110, "red": 0, "green": 60}  # OpenCV hue units (0..180)


def remove_ink(img, colour="blue", hue_tol=15, min_chroma=45):
    """Replace coloured pen strokes with the local paper colour. Black print stays (it has no saturation)."""
    hsv = cv2.cvtColor(img, cv2.COLOR_BGR2HSV)
    h = hsv[:, :, 0].astype(np.int16)
    # Absolute chroma, not HSV saturation: near-black print plus sensor noise looks "saturated" in HSV.
    chroma = img.max(axis=2).astype(np.int16) - img.min(axis=2).astype(np.int16)
    target = HUES[colour]
    dh = np.abs(h - target)
    dh = np.minimum(dh, 180 - dh)
    mask = ((dh <= hue_tol) & (chroma >= min_chroma)).astype(np.uint8) * 255
    mask = cv2.dilate(mask, np.ones((5, 5), np.uint8))  # anti-aliased stroke edges are barely saturated
    w = img.shape[1]
    out = img.copy()
    for i in range(3):
        bg = background(img[:, :, i], w)
        out[:, :, i] = np.where(mask > 0, bg, img[:, :, i])
    return out, mask


def find_gutter(spread, lo=0.3, hi=0.7):
    """Gutter column of a warped two-page spread: the deepest darkness valley in the central band.
    Returns (x, confidence). Text columns are suppressed by closing first, so the valley is the binding shadow."""
    gray = cv2.cvtColor(spread, cv2.COLOR_BGR2GRAY) if spread.ndim == 3 else spread
    w = gray.shape[1]
    k = max(5, (w // 60) | 1)
    paper = cv2.morphologyEx(gray, cv2.MORPH_CLOSE, cv2.getStructuringElement(cv2.MORPH_RECT, (k, k)))
    prof = paper.mean(axis=0).astype(np.float32)
    prof = cv2.GaussianBlur(prof.reshape(1, -1), (0, 0), sigmaX=max(2.0, w / 200.0)).ravel()
    a, b = int(w * lo), int(w * hi)
    x = a + int(np.argmin(prof[a:b]))
    depth = float(np.median(prof[a:b]) - prof[x])
    return x, float(min(1.0, depth / 25.0))


def split_spread(spread):
    x, conf = find_gutter(spread)
    return spread[:, :x], spread[:, x:], x, conf
