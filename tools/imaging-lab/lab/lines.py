"""Line-based document quad candidates for cluttered scenes (open notebooks, desks, other paper in view).

Contour candidates break when the page touches other bright things (a laptop, a second page, white desk): the
outline merges with them. Straight edges survive clutter, so this module builds candidates from line segments:

  1. LSD segments on text-suppressed (closed) L, a, b channels and on plain luma (catches the notebook fold).
  2. Merge collinear segments into lines; keep the strongest; add the four frame borders (a page cut by the frame).
  3. Every pair of "horizontal" x pair of "vertical" lines makes a quad. Each line carries a support profile (gradient
     across the line, sampled once) so a side's support is a prefix-sum lookup.
  4. Rank by edge support, then re-rank the best by how page-like the inside is (bright, even, low chroma).
"""
import itertools

import cv2
import numpy as np

from .geometry import order_quad

MIN_SEG = 0.05     # segment length, fraction of the diagonal
MAX_LINES = 14     # per orientation group
MERGE_DEG = 2.5
MERGE_RHO = 0.010  # fraction of the diagonal
SOFT = 1.6        # support saturates at SOFT x the gradient threshold


class Line:
    __slots__ = ("p", "d", "length", "frame", "prof", "t0")

    def __init__(self, p, d, length, frame=False):
        self.p = np.asarray(p, np.float64)
        d = np.asarray(d, np.float64)
        self.d = d / np.linalg.norm(d)
        self.length = length
        self.frame = frame
        self.prof = None
        self.t0 = 0.0

    @property
    def normal(self):
        return np.array([-self.d[1], self.d[0]])

    def horizontal(self):
        return abs(self.d[0]) >= abs(self.d[1])


def _segments(maps, diag):
    lsd = cv2.createLineSegmentDetector()
    out = []
    for m in maps:
        segs = lsd.detect(m)[0]
        if segs is None:
            continue
        segs = segs.reshape(-1, 4)
        ln = np.hypot(segs[:, 2] - segs[:, 0], segs[:, 3] - segs[:, 1])
        out.append(segs[ln >= MIN_SEG * diag])
    return np.vstack(out) if out else np.zeros((0, 4), np.float32)


def _merge(segs, diag):
    """Greedy clustering of segments by angle and offset, longest first; each cluster is refit as one line."""
    if len(segs) == 0:
        return []
    ln = np.hypot(segs[:, 2] - segs[:, 0], segs[:, 3] - segs[:, 1])
    order = np.argsort(-ln)
    clusters = []  # [theta, rho, [segment indexes], total length]
    for i in order:
        x0, y0, x1, y1 = segs[i]
        th = np.arctan2(y1 - y0, x1 - x0) % np.pi
        n = np.array([-np.sin(th), np.cos(th)])
        rho = n @ np.array([(x0 + x1) / 2, (y0 + y1) / 2])
        for c in clusters:
            dth = abs(th - c[0])
            dth = min(dth, np.pi - dth)
            if dth > np.radians(MERGE_DEG):
                continue
            # compare offsets in the cluster's own normal frame (handles the 0/pi wrap)
            cn = np.array([-np.sin(c[0]), np.cos(c[0])])
            if abs(cn @ np.array([(x0 + x1) / 2, (y0 + y1) / 2]) - c[1]) < MERGE_RHO * diag:
                c[2].append(i)
                c[3] += ln[i]
                break
        else:
            clusters.append([th, rho, [i], ln[i]])
    lines = []
    for th, rho, idx, total in clusters:
        pts = segs[idx].reshape(-1, 2)
        w = np.repeat(ln[idx], 2)
        # weighted line fit through all endpoints (PCA)
        mu = (pts * w[:, None]).sum(0) / w.sum()
        cov = ((pts - mu) * w[:, None]).T @ (pts - mu)
        evals, evecs = np.linalg.eigh(cov)
        d = evecs[:, 1]
        lines.append(Line(mu, d, float(total)))
    return lines


def _frame_lines(w, h):
    return [Line((0, 0), (1, 0), 0, True), Line((0, h - 1), (1, 0), 0, True),
            Line((0, 0), (0, 1), 0, True), Line((w - 1, 0), (0, 1), 0, True)]


def _profile(line, gx, gy, shape):
    """Max over a +-2 px band of |gradient . normal| at 1 px steps along the line inside the image."""
    h, w = shape
    p, d = line.p, line.d
    lo, hi = -1e9, 1e9
    for k, size in ((0, w), (1, h)):
        if abs(d[k]) < 1e-9:
            if not (0 <= p[k] <= size - 1):
                lo, hi = 1, 0
            continue
        a, b = (0 - p[k]) / d[k], (size - 1 - p[k]) / d[k]
        lo, hi = max(lo, min(a, b)), min(hi, max(a, b))
    if hi <= lo:
        line.prof, line.t0 = np.zeros(1), 0.0
        return
    t = np.arange(lo, hi, 1.0)
    pts = p[None, :] + t[:, None] * d[None, :]
    n = line.normal
    best = np.zeros(len(t), np.float32)
    for off in (-2, -1, 0, 1, 2):
        q = pts + off * n
        xi = np.clip(np.round(q[:, 0]).astype(int), 0, w - 1)
        yi = np.clip(np.round(q[:, 1]).astype(int), 0, h - 1)
        v = np.abs(gx[:, yi, xi] * n[0] + gy[:, yi, xi] * n[1]).max(axis=0)
        best = np.maximum(best, v)
    line.prof, line.t0 = best, lo


def _intersect(a, b):
    A = np.array([[a.d[0], -b.d[0]], [a.d[1], -b.d[1]]])
    det = np.linalg.det(A)
    if abs(det) < 1e-9:
        return None
    t = np.linalg.solve(A, b.p - a.p)
    return a.p + a.d * t[0]


def _angles_ok(q, lo=45.0, hi=135.0):
    for i in range(4):
        a, b, c = q[i - 1], q[i], q[(i + 1) % 4]
        v1, v2 = a - b, c - b
        cosang = np.dot(v1, v2) / (np.linalg.norm(v1) * np.linalg.norm(v2) + 1e-9)
        ang = np.degrees(np.arccos(np.clip(cosang, -1, 1)))
        if ang < lo or ang > hi:
            return False
    return True


def _side_support(line, a, b, cum, thresh):
    ta, tb = sorted(((a - line.p) @ line.d - line.t0, (b - line.p) @ line.d - line.t0))
    span = tb - ta
    ia = int(np.clip(ta + 0.05 * span, 0, len(line.prof) - 1))
    ib = int(np.clip(tb - 0.05 * span, 0, len(line.prof) - 1))
    if ib <= ia:
        return 0.0
    return (cum[ib] - cum[ia]) / float(ib - ia)


def candidates(small, grads, gthresh, maps, lines_out=None):
    """Return a list of (edge_score, quad, n_clipped, supports) for line-built quads on `small`.
    If lines_out is a list, the merged (non-frame) lines are appended to it."""
    h, w = small.shape[:2]
    diag = float(np.hypot(w, h))
    lines = _merge(_segments(maps, diag), diag)
    if lines_out is not None:
        lines_out.extend(lines)
    hs = sorted([l for l in lines if l.horizontal()], key=lambda l: -l.length)[:MAX_LINES]
    vs = sorted([l for l in lines if not l.horizontal()], key=lambda l: -l.length)[:MAX_LINES]
    fr = _frame_lines(w, h)
    hs += fr[:2]
    vs += fr[2:]
    gx, gy = grads
    cums = {}
    for l in hs + vs:
        if not l.frame:
            _profile(l, gx, gy, (h, w))
            cums[id(l)] = np.concatenate([[0.0], np.cumsum(np.minimum(1.0, l.prof / (SOFT * gthresh)))])
    margin = 0.02 * max(w, h)
    area_img = float(w * h)
    out = []
    for h1, h2 in itertools.combinations(hs, 2):
        for v1, v2 in itertools.combinations(vs, 2):
            if h1.frame and h2.frame and v1.frame and v2.frame:
                continue
            corners = []
            ok = True
            for hl, vl in ((h1, v1), (h1, v2), (h2, v2), (h2, v1)):
                c = _intersect(hl, vl)
                if c is None or not (-margin <= c[0] <= w - 1 + margin and -margin <= c[1] <= h - 1 + margin):
                    ok = False
                    break
                corners.append(c)
            if not ok:
                continue
            q = np.clip(np.array(corners), [0, 0], [w - 1, h - 1])
            if not cv2.isContourConvex(q.astype(np.float32).reshape(-1, 1, 2)):
                continue
            frac = cv2.contourArea(q.astype(np.float32).reshape(-1, 1, 2)) / area_img
            if frac < 0.06 or frac > 0.985 or not _angles_ok(q):
                continue
            sides = ((h1, q[0], q[1]), (v2, q[1], q[2]), (h2, q[2], q[3]), (v1, q[3], q[0]))
            sup = [None if l.frame else _side_support(l, a, b, cums[id(l)], gthresh) for l, a, b in sides]
            real = [s for s in sup if s is not None]
            if len(real) < 2:
                continue
            filled = [1.0 if s is None else s for s in sup]
            clipped = 4 - len(real)
            score = 0.55 * min(filled) + 0.25 * float(np.mean(filled)) + 0.20 * min(1.0, frac / 0.5) - 0.04 * clipped
            out.append((score, order_quad(q.astype(np.float32)), clipped, sup))
    return out


def page_likeness(small_lab_closed, q):
    """How page-like the inside of q is: share of interior pixels close to the interior's bright, neutral mode.
    A quad spanning page + laptop + desk mat mixes tones and scores low; a page (or a spread) scores high."""
    L, A, B = small_lab_closed
    h, w = L.shape
    mask = np.zeros((h, w), np.uint8)
    cv2.fillConvexPoly(mask, np.int32(np.round(q)), 1)
    # shrink a little so edge pixels don't count
    mask = cv2.erode(mask, np.ones((7, 7), np.uint8))
    sel = mask.astype(bool)
    if sel.sum() < 100:
        return 0.0
    l = L[sel].astype(np.float32)
    chroma = np.hypot(A[sel].astype(np.float32) - 128, B[sel].astype(np.float32) - 128)
    ref = np.percentile(l, 70)
    good = (np.abs(l - ref) < 22) & (chroma < 22)
    return float(good.mean())


def suppressed_lab(small, k):
    """L closed (removes dark text), a/b median-filtered (removes thin coloured strokes of either sign: red
    margin lines, blue ruling, ink) so only broad regions and their boundaries remain."""
    lab = cv2.cvtColor(cv2.GaussianBlur(small, (3, 3), 0), cv2.COLOR_BGR2LAB)
    kernel = cv2.getStructuringElement(cv2.MORPH_RECT, (k, k))
    L = cv2.morphologyEx(lab[:, :, 0], cv2.MORPH_CLOSE, kernel, iterations=2)
    a = cv2.medianBlur(lab[:, :, 1], k)
    b = cv2.medianBlur(lab[:, :, 2], k)
    return [L, a, b]


def gradients(chans, gains=(1.0, 2.0, 2.0)):
    gx = np.stack([cv2.Sobel(c, cv2.CV_32F, 1, 0, ksize=3) * g for c, g in zip(chans, gains)])
    gy = np.stack([cv2.Sobel(c, cv2.CV_32F, 0, 1, ksize=3) * g for c, g in zip(chans, gains)])
    return gx, gy
