"""Document quad detection (reference for core:imaging QuadDetector).

Pipeline (all on a <= 1000 px working copy):
  1. Text suppression: morphological close on the grayscale/colour maps so a page with text
     becomes a smooth bright region.
  2. Candidates from several maps (luma edges, per-channel colour edges, Otsu segmentation):
     contours -> approxPolyDP to 4 points, or a 4-point reduction of the convex hull.
  3. Score: area share, corner angles, and how much of each side lies on a strong gradient.
  4. Refine: fit a line to edge pixels near each side at a higher resolution, intersect neighbours.
"""
import numpy as np
import cv2

from .geometry import order_quad

WORK_MAX = 1000
REFINE_MAX = 2000


def _resize_max(img, max_side):
    h, w = img.shape[:2]
    s = min(1.0, max_side / float(max(h, w)))
    if s < 1.0:
        img = cv2.resize(img, (int(round(w * s)), int(round(h * s))), interpolation=cv2.INTER_AREA)
    return img, s


def _close(gray, k):
    kernel = cv2.getStructuringElement(cv2.MORPH_RECT, (k, k))
    return cv2.morphologyEx(gray, cv2.MORPH_CLOSE, kernel, iterations=2)


def _auto_canny(gray, sigma=0.33):
    v = float(np.median(gray))
    lo = int(max(5, (1.0 - sigma) * v * 0.5))
    hi = int(max(lo + 10, min(255, (1.0 + sigma) * v * 0.5)))
    return cv2.Canny(gray, lo, hi, L2gradient=True)


def _quad_from_contour(c):
    peri = cv2.arcLength(c, True)
    for eps in (0.01, 0.02, 0.03, 0.04, 0.05, 0.07):
        ap = cv2.approxPolyDP(c, eps * peri, True)
        if len(ap) == 4 and cv2.isContourConvex(ap):
            return ap.reshape(4, 2).astype(np.float32)
    hull = cv2.convexHull(c)
    return _hull_to_quad(hull.reshape(-1, 2).astype(np.float32))


def _hull_to_quad(hull):
    """Reduce a convex hull to 4 points by repeatedly dropping the vertex whose removal loses least area."""
    pts = [p for p in hull]
    if len(pts) < 4:
        return None
    while len(pts) > 4:
        best_i, best_loss = 0, None
        for i in range(len(pts)):
            a, b, c = pts[i - 1], pts[i], pts[(i + 1) % len(pts)]
            loss = abs((b[0] - a[0]) * (c[1] - a[1]) - (c[0] - a[0]) * (b[1] - a[1])) / 2.0
            if best_loss is None or loss < best_loss:
                best_i, best_loss = i, loss
        pts.pop(best_i)
    q = np.array(pts, dtype=np.float32)
    return q if cv2.isContourConvex(q.reshape(-1, 1, 2)) else None


def _angles_ok(q, lo=40.0, hi=140.0):
    q = order_quad(q)
    for i in range(4):
        a, b, c = q[i - 1], q[i], q[(i + 1) % 4]
        v1, v2 = a - b, c - b
        cosang = np.dot(v1, v2) / (np.linalg.norm(v1) * np.linalg.norm(v2) + 1e-9)
        ang = np.degrees(np.arccos(np.clip(cosang, -1, 1)))
        if ang < lo or ang > hi:
            return False
    return True


def _side_support(q, grad, thresh):
    """Fraction of sample points along each side with a strong gradient; returns the 4 fractions."""
    q = order_quad(q)
    h, w = grad.shape
    out = []
    for i in range(4):
        a, b = q[i], q[(i + 1) % 4]
        n = max(20, int(np.linalg.norm(b - a) / 3))
        t = np.linspace(0.05, 0.95, n)
        pts = a[None, :] * (1 - t[:, None]) + b[None, :] * t[:, None]
        hits = 0
        for x, y in pts:
            xi, yi = int(round(x)), int(round(y))
            x0, x1 = max(0, xi - 2), min(w, xi + 3)
            y0, y1 = max(0, yi - 2), min(h, yi + 3)
            if x1 > x0 and y1 > y0 and grad[y0:y1, x0:x1].max() >= thresh:
                hits += 1
        out.append(hits / float(n))
    return out


def _candidates(small):
    h, w = small.shape[:2]
    k = max(5, int(round(max(h, w) / 90.0)) | 1)
    lab = cv2.cvtColor(small, cv2.COLOR_BGR2LAB)
    L = lab[:, :, 0]
    maps = [_close(L, k)] + [_close(small[:, :, i], k) for i in range(3)]
    hsv = cv2.cvtColor(small, cv2.COLOR_BGR2HSV)
    maps.append(_close(255 - hsv[:, :, 1], k))  # paper is low-saturation

    cands = []
    edges_all = np.zeros((h, w), np.uint8)
    for m in maps:
        m = cv2.GaussianBlur(m, (5, 5), 0)
        e = _auto_canny(m)
        e = cv2.dilate(e, np.ones((3, 3), np.uint8), iterations=1)
        edges_all |= e
        cs, _ = cv2.findContours(e, cv2.RETR_LIST, cv2.CHAIN_APPROX_SIMPLE)
        cs = sorted(cs, key=cv2.contourArea, reverse=True)[:6]
        for c in cs:
            q = _quad_from_contour(c)
            if q is not None:
                cands.append(q)
        _, th = cv2.threshold(m, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)
        for mask in (th, 255 - th):
            mask = cv2.morphologyEx(mask, cv2.MORPH_OPEN, np.ones((5, 5), np.uint8))
            cs, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
            cs = sorted(cs, key=cv2.contourArea, reverse=True)[:3]
            for c in cs:
                q = _quad_from_contour(c)
                if q is not None:
                    cands.append(q)
    return cands, edges_all


def _score(q, grad, gthresh, area_img):
    area = cv2.contourArea(q.reshape(-1, 1, 2))
    frac = area / area_img
    if frac < 0.08 or frac > 0.985 or not _angles_ok(q):
        return -1.0, None
    sup = _side_support(q, grad, gthresh)
    # A quad hugging the image border is usually the frame, not the page.
    return 0.55 * min(sup) + 0.25 * float(np.mean(sup)) + 0.20 * min(1.0, frac / 0.5), sup


def _colour_gradient(small):
    """Per-pixel max of the Sobel magnitude over L, a, b: hue changes (green card on brown desk) count as edges."""
    lab = cv2.cvtColor(cv2.GaussianBlur(small, (3, 3), 0), cv2.COLOR_BGR2LAB)
    out = None
    for i, gain in ((0, 1.0), (1, 2.0), (2, 2.0)):
        ch = lab[:, :, i]
        m = cv2.magnitude(cv2.Sobel(ch, cv2.CV_32F, 1, 0, ksize=3), cv2.Sobel(ch, cv2.CV_32F, 0, 1, ksize=3)) * gain
        out = m if out is None else np.maximum(out, m)
    return out


def _touches_border(q, shape, margin=0.01):
    h, w = shape
    m = margin * max(h, w)
    return bool(np.any(q[:, 0] < m) or np.any(q[:, 1] < m) or np.any(q[:, 0] > w - 1 - m) or np.any(q[:, 1] > h - 1 - m))


def _prefer_enclosing(best, best_score, scored, shape, min_gain=1.08, rel_score=0.7):
    """Inner rectangles (a card's light body under its coloured header, a table border on a page) often
    score as well as the real outline. Prefer the largest well-supported candidate that contains the
    winner, unless it hugs the image border (then it's usually the frame, not the document)."""
    area_b = cv2.contourArea(best.reshape(-1, 1, 2))
    chosen, chosen_area = best, area_b
    for sc, q in scored:
        if sc < rel_score * best_score:
            continue
        a = cv2.contourArea(q.reshape(-1, 1, 2))
        if a < min_gain * chosen_area or _touches_border(q, shape):
            continue
        qc = q.reshape(-1, 1, 2)
        inside = all(cv2.pointPolygonTest(qc, (float(x), float(y)), True) >= -3.0 for x, y in best)
        if inside:
            chosen, chosen_area = q, a
    return chosen


def _refine(img, quad, band=6):
    """Fit lines to strong edge points near each side; intersect adjacent lines."""
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY) if img.ndim == 3 else img
    gray = cv2.GaussianBlur(gray, (3, 3), 0)
    gx = cv2.Sobel(gray, cv2.CV_32F, 1, 0, ksize=3)
    gy = cv2.Sobel(gray, cv2.CV_32F, 0, 1, ksize=3)
    mag = cv2.magnitude(gx, gy)
    q = order_quad(quad)
    h, w = gray.shape
    lines = []
    for i in range(4):
        a, b = q[i], q[(i + 1) % 4]
        d = b - a
        length = np.linalg.norm(d)
        if length < 10:
            return quad
        d /= length
        nrm = np.array([-d[1], d[0]], np.float32)
        pts = []
        for t in np.linspace(0.08, 0.92, max(30, int(length / 4))):
            p = a + (b - a) * t
            offs = np.arange(-band, band + 1, dtype=np.float32)
            xs = np.clip(np.round(p[0] + nrm[0] * offs).astype(int), 0, w - 1)
            ys = np.clip(np.round(p[1] + nrm[1] * offs).astype(int), 0, h - 1)
            vals = mag[ys, xs]
            j = int(np.argmax(vals))
            if vals[j] <= 0:
                continue
            # parabolic sub-pixel peak along the normal
            o = offs[j]
            if 0 < j < len(vals) - 1:
                den = vals[j - 1] - 2 * vals[j] + vals[j + 1]
                if abs(den) > 1e-6:
                    o += 0.5 * (vals[j - 1] - vals[j + 1]) / den
            pts.append(p + nrm * o)
        if len(pts) < 8:
            return quad
        pts = np.array(pts, np.float32)
        vx, vy, x0, y0 = cv2.fitLine(pts, cv2.DIST_HUBER, 0, 0.01, 0.01).ravel()
        lines.append((np.array([x0, y0]), np.array([vx, vy])))
    out = []
    for i in range(4):
        (p1, d1), (p2, d2) = lines[i - 1], lines[i]
        A = np.array([[d1[0], -d2[0]], [d1[1], -d2[1]]])
        if abs(np.linalg.det(A)) < 1e-6:
            return quad
        t = np.linalg.solve(A, p2 - p1)
        out.append(p1 + d1 * t[0])
    out = np.array(out, np.float32)
    # Reject refinements that moved a corner implausibly far.
    if np.max(np.linalg.norm(out - q, axis=1)) > 0.03 * np.hypot(w, h):
        return q
    return out


LIVE_MAX = 480


def detect_quad_live(gray):
    """Fast variant for the camera overlay: luma only (the camera's Y plane), 480 px, one map, no refinement.
    Returns (quad in the input's pixel coords or None, confidence)."""
    small, s = _resize_max(gray, LIVE_MAX)
    h, w = small.shape[:2]
    k = max(5, int(round(max(h, w) / 90.0)) | 1)
    m = cv2.GaussianBlur(_close(small, k), (5, 5), 0)
    grad = cv2.magnitude(cv2.Sobel(m, cv2.CV_32F, 1, 0, ksize=3), cv2.Sobel(m, cv2.CV_32F, 0, 1, ksize=3))
    gthresh = max(12.0, float(np.percentile(grad, 85)))
    cands = []
    e = cv2.dilate(_auto_canny(m), np.ones((3, 3), np.uint8), iterations=1)
    cs, _ = cv2.findContours(e, cv2.RETR_LIST, cv2.CHAIN_APPROX_SIMPLE)
    for c in sorted(cs, key=cv2.contourArea, reverse=True)[:6]:
        q = _quad_from_contour(c)
        if q is not None:
            cands.append(q)
    _, th = cv2.threshold(m, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)
    for mask in (th, 255 - th):
        cs, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
        for c in sorted(cs, key=cv2.contourArea, reverse=True)[:2]:
            q = _quad_from_contour(c)
            if q is not None:
                cands.append(q)
    scored = [(sc, q) for q in cands for sc, _ in [_score(q, grad, gthresh, float(h * w))] if sc >= 0]
    if not scored:
        return None, 0.0
    best_score, best = max(scored, key=lambda t: t[0])
    if best_score < 0.35:
        return None, best_score
    best = _prefer_enclosing(best, best_score, scored, (h, w))
    return order_quad(best) / s, float(min(1.0, best_score))


def detect_quad(image, refine=True):
    """Return (quad TL,TR,BR,BL in image px or None, confidence 0..1)."""
    small, s = _resize_max(image, WORK_MAX)
    h, w = small.shape[:2]
    grad = _colour_gradient(small)
    gthresh = max(12.0, float(np.percentile(grad, 85)))
    cands, _ = _candidates(small)
    scored = []
    for q in cands:
        sc, _ = _score(q, grad, gthresh, float(h * w))
        if sc >= 0:
            scored.append((sc, q))
    if not scored:
        return None, 0.0
    best_score, best = max(scored, key=lambda t: t[0])
    if best_score < 0.35:
        return None, best_score
    best = _prefer_enclosing(best, best_score, scored, (h, w))
    quad = order_quad(best) / s
    if refine:
        big, s2 = _resize_max(image, REFINE_MAX)
        quad = _refine(big, quad * s2) / s2
    return order_quad(quad), float(min(1.0, best_score))
