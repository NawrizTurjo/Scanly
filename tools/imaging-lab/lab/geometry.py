"""Quad ordering, output-size estimation and perspective warp (reference for core:imaging)."""
import numpy as np
import cv2

A4_RATIO = 297.0 / 210.0
LETTER_RATIO = 11.0 / 8.5


def order_quad(pts):
    """Return corners as TL, TR, BR, BL in image axes (float32, shape 4x2)."""
    pts = np.asarray(pts, dtype=np.float32).reshape(4, 2)
    c = pts.mean(axis=0)
    ang = np.arctan2(pts[:, 1] - c[1], pts[:, 0] - c[0])
    pts = pts[np.argsort(ang)]  # clockwise in image coords, starting near -pi (left)
    # rotate so the first point is the one with the smallest x+y (top-left-most)
    start = int(np.argmin(pts.sum(axis=1)))
    return np.roll(pts, -start, axis=0)


def output_size(quad, snap=True, snap_tol=0.03):
    """Width/height from the mean of opposite side lengths; optionally snap the aspect to A4/Letter."""
    tl, tr, br, bl = order_quad(quad)
    w = (np.linalg.norm(tr - tl) + np.linalg.norm(br - bl)) / 2.0
    h = (np.linalg.norm(bl - tl) + np.linalg.norm(br - tr)) / 2.0
    w, h = max(w, 1.0), max(h, 1.0)
    if snap:
        long_, short = max(w, h), min(w, h)
        r = long_ / short
        for target in (A4_RATIO, LETTER_RATIO):
            if abs(r - target) / target <= snap_tol:
                short = long_ / target
                break
        w, h = (long_, short) if w >= h else (short, long_)
    return int(round(w)), int(round(h))


def warp(image, quad, size=None, snap=True):
    """Perspective-correct the quad region of image into an upright rectangle."""
    q = order_quad(quad)
    w, h = size if size else output_size(q, snap=snap)
    dst = np.array([[0, 0], [w - 1, 0], [w - 1, h - 1], [0, h - 1]], dtype=np.float32)
    m = cv2.getPerspectiveTransform(q, dst)
    return cv2.warpPerspective(image, m, (w, h), flags=cv2.INTER_CUBIC, borderMode=cv2.BORDER_REPLICATE)


def corner_error(found, truth):
    """Mean corner distance, best over the 4 cyclic orderings (page orientation is not detectable)."""
    f = order_quad(found)
    t = np.asarray(truth, dtype=np.float32).reshape(4, 2)
    best = None
    for k in range(4):
        d = np.linalg.norm(np.roll(f, k, axis=0) - t, axis=1)
        if best is None or d.mean() < best.mean():
            best = d
    return float(best.mean()), float(best.max())
