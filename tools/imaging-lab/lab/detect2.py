"""Detector v2: contour + line candidates, one scorer.

Score of a quad (all on the <= 1000 px working copy):
  edge   soft support of each side (gradient across the side on text-suppressed L/a/b), frame-clipped sides free
         but penalised (prefer a fully visible page: left page of a spread whose right page leaves the frame);
  page   share of the interior close to its bright neutral mode (a quad that swallows desk/laptop scores low);
  rim    the strip just inside each side must be page too (rejects a side that sits on the desk beyond the page);
  cover  the quad should cover the whole page-toned region it sits on (rejects a strip between the page top and a
         ruled header line, or one page of a spread whose other page is fully visible).
Then a spread split: if the winner contains a notebook fold and exactly one half is cut by the frame, return the
other half (the page the user framed).
"""
import cv2
import numpy as np

from .geometry import order_quad
from . import detect as D
from . import lines as LN

WORK_MAX = 1000
MIN_SCORE = 0.45
TOP_LINE = 60
FRAME_SUPPORT = 0.7
PROMINENCE = 0.15
CENTRE = 0.10
RIM_FAIL = 0.6
BRIGHT = 0.15
ASPECT = 0.30
STATS_MAX = 500
LIVE_TOP_LINE = 30
LIVE_STATS_MAX = 240


def _side_soft(q, grads, gthresh, shape):
    """Per side: mean of min(1, |grad . n| / (SOFT * thresh)), max over a +-2 px band; None for frame sides."""
    gx, gy = grads
    h, w = shape
    margin = 0.012 * max(h, w)
    out = []
    for i in range(4):
        a, b = q[i], q[(i + 1) % 4]
        if D._border_side(a, b, (h, w), margin):
            out.append(None)
            continue
        d = b - a
        length = float(np.linalg.norm(d)) + 1e-9
        n = np.array([-d[1], d[0]]) / length
        t = np.linspace(0.05, 0.95, max(20, int(length)))
        pts = a[None, :] + t[:, None] * d[None, :]
        best = np.zeros(len(t), np.float32)
        for off in (-2, -1, 0, 1, 2):
            p = pts + off * n
            xi = np.clip(np.round(p[:, 0]).astype(int), 0, w - 1)
            yi = np.clip(np.round(p[:, 1]).astype(int), 0, h - 1)
            best = np.maximum(best, np.abs(gx[:, yi, xi] * n[0] + gy[:, yi, xi] * n[1]).max(axis=0))
        out.append(float(np.minimum(1.0, best / (LN.SOFT * gthresh)).mean()))
    return out


def _context(chans, mag, gthresh, stats_max=None):
    """Per-image maps for the page measures. Paper = neutral, locally even (lighting gradients pass, clutter and
    leftover text don't) and not on an edge, so page-toned regions are cut apart by their outlines."""
    L, A, B = chans
    Lf = L.astype(np.float32)
    chroma = np.hypot(A.astype(np.float32) - 128, B.astype(np.float32) - 128)
    local = cv2.blur(Lf, (21, 21))
    edge = cv2.dilate((mag >= gthresh).astype(np.uint8), np.ones((3, 3), np.uint8)).astype(bool)
    paperish = (chroma < 24) & (np.abs(Lf - local) < 12) & ~edge
    # page statistics run on a half-size copy: ~120 candidates each fill a mask and label components
    f = (stats_max or STATS_MAX) / float(max(L.shape))
    size = (int(round(L.shape[1] * f)), int(round(L.shape[0] * f)))
    Ls = cv2.resize(Lf, size, interpolation=cv2.INTER_AREA)
    ps = cv2.resize(paperish.astype(np.uint8), size, interpolation=cv2.INTER_NEAREST).astype(bool)
    return dict(L=Ls, paperish=ps, median=float(np.median(Lf)), f=f)


def _page_stats(ctx, q, shape, frame_sides=()):
    """(page-likeness, min inner-rim page share, paper coverage, brightness). For frame sides the rim is
    measured right at the frame: the page has to actually run off the photo there."""
    Lf, paperish = ctx["L"], ctx["paperish"]
    q = q * ctx["f"]
    h, w = Lf.shape
    mask = np.zeros((h, w), np.uint8)
    cv2.fillConvexPoly(mask, np.int32(np.round(q)), 1)
    mask = cv2.erode(mask, np.ones((3, 3), np.uint8))
    sel = mask.astype(bool)
    if sel.sum() < 100:
        return 0.0, 0.0, 0.0, 0.0, 0.0
    ref = float(np.percentile(Lf[sel], 70))
    good_map = paperish & (Lf > ref - 45) & (Lf < ref + 30)
    page = float(good_map[sel].mean())
    diag = float(np.hypot(w, h))
    c = q.mean(axis=0)
    rims = []
    for i in range(4):
        a, b = q[i], q[(i + 1) % 4]
        d = b - a
        length = float(np.linalg.norm(d)) + 1e-9
        n = np.array([-d[1], d[0]]) / length
        if n @ (c - (a + b) / 2) < 0:
            n = -n
        t = np.linspace(0.1, 0.9, 40)
        base = a[None, :] + t[:, None] * d[None, :]
        deep = base + 0.3 * (c[None, :] - base)
        xd = np.clip(np.round(deep[:, 0]).astype(int), 0, w - 1)
        yd = np.clip(np.round(deep[:, 1]).astype(int), 0, h - 1)
        hits = []
        for off in ((0.004, 0.008, 0.012) if i in frame_sides else (0.015, 0.025, 0.035)):
            p = base + off * diag * n
            xi = np.clip(np.round(p[:, 0]).astype(int), 0, w - 1)
            yi = np.clip(np.round(p[:, 1]).astype(int), 0, h - 1)
            # page-like here, and the same paper as further inside (a side on the desk beyond the page fails)
            hits.append(good_map[yi, xi] & (np.abs(Lf[yi, xi] - Lf[yd, xd]) < 25))
        rims.append(float(np.mean(hits)))
    # coverage: page-toned components that the quad overlaps, how much of them is inside the quad
    gm = cv2.morphologyEx(good_map.astype(np.uint8), cv2.MORPH_OPEN, np.ones((3, 3), np.uint8))
    qm = sel
    n, lbl = cv2.connectedComponents(gm, connectivity=4)
    inside = np.bincount(lbl[qm], minlength=n)
    total = np.bincount(lbl.ravel(), minlength=n)
    keep = [i for i in range(1, n) if inside[i] >= 0.05 * qm.sum()]
    cover = float(inside[keep].sum() / max(1, total[keep].sum())) if keep else 0.0
    bright = float(np.clip((ref - ctx["median"]) / 25.0, 0.0, 1.0))
    return page, min(rims), cover, bright, ref


def _total(q, grads, gthresh, ctx, shape, area_img):
    sup = _side_soft(q, grads, gthresh, shape)
    real = [v for v in sup if v is not None]
    if len(real) < 2:
        return None
    frac = cv2.contourArea(q.reshape(-1, 1, 2)) / area_img
    if frac < 0.06 or frac > 0.985 or not LN._angles_ok(q):
        return None
    # A frame side means "the page goes on beyond the photo": neutral, not free support.
    filled = [FRAME_SUPPORT if v is None else v for v in sup]
    edge = 0.55 * min(real) + 0.25 * float(np.mean(filled)) + 0.20 * min(1.0, frac / 0.5) - 0.04 * (4 - len(real))
    # The document is usually the big thing near the middle of the photo.
    h, w = shape
    off = np.linalg.norm(q.mean(axis=0) - np.array([w / 2.0, h / 2.0])) / (0.5 * np.hypot(w, h))
    edge += PROMINENCE * (min(1.0, frac / 0.3) - 0.5) - CENTRE * off
    page, rim, cover, bright, _ = _page_stats(ctx, q, shape, [i for i, v in enumerate(sup) if v is None])
    total = (edge + 0.35 * (page - 0.7) + 0.25 * (rim - 0.8) + 0.30 * (cover - 0.8)
             + RIM_FAIL * min(0.0, rim - 0.85) + BRIGHT * (bright - 1.0))
    return total, dict(edge=edge, page=page, rim=rim, cover=cover, bright=bright, sup=sup, frac=frac)


def _aspect(q):
    q = order_quad(np.asarray(q, np.float32))
    wd = 0.5 * (np.linalg.norm(q[1] - q[0]) + np.linalg.norm(q[2] - q[3]))
    ht = 0.5 * (np.linalg.norm(q[3] - q[0]) + np.linalg.norm(q[2] - q[1]))
    return max(wd, ht) / max(1e-6, min(wd, ht))


def _prefer_enclosing(best, scored, shape, ctx):
    """A card's white body under a coloured header (or a form's inner box) wins on page-likeness; the real outline
    encloses it with equally strong edges. Take the enclosing quad when its own edges are at least as strong,
    it's at most twice the area, it stays clear of the frame (else it's the desk mat or the photo), and the band it
    adds is partly page-toned (a card's photo column, not a dark mat border)."""
    h, w = ctx["L"].shape
    f = ctx["f"]
    mb = np.zeros((h, w), np.uint8)
    cv2.fillConvexPoly(mb, np.int32(np.round(order_quad(best.astype(np.float32)) * f)), 1)
    ref = float(np.percentile(ctx["L"][mb.astype(bool)], 70))
    good = ctx["paperish"] & (ctx["L"] > ref - 45) & (ctx["L"] < ref + 30)
    bq = order_quad(best.astype(np.float32))
    info_b = next(i for _, q, i in scored if q is best)
    area_b = cv2.contourArea(bq.reshape(-1, 1, 2))
    chosen = best
    for _, q, info in scored:
        if any(v is None for v in info["sup"]) or min(info["sup"]) < 0.9 or info["edge"] < info_b["edge"]:
            continue
        qq = order_quad(q.astype(np.float32))
        a = cv2.contourArea(qq.reshape(-1, 1, 2))
        if not 1.08 * area_b < a <= 2.0 * area_b or D._touches_border(qq, shape, 0.02):
            continue
        if all(cv2.pointPolygonTest(qq.reshape(-1, 1, 2), (float(x), float(y)), True) >= -3.0 for x, y in bq):
            mq = np.zeros((h, w), np.uint8)
            cv2.fillConvexPoly(mq, np.int32(np.round(qq * f)), 1)
            ring = mq.astype(bool) & ~mb.astype(bool)
            if ring.sum() == 0 or good[ring].mean() < 0.3:
                continue
            if chosen is best or a > cv2.contourArea(order_quad(chosen.astype(np.float32)).reshape(-1, 1, 2)):
                chosen = q
    return chosen


def _side_line_dist(p, a, b):
    d = b - a
    return float(abs(d[0] * (p[1] - a[1]) - d[1] * (p[0] - a[0])) / (np.linalg.norm(d) + 1e-9))


def _fold(q, lines, lo=0.12, hi=0.88):
    """A notebook fold inside q: a long line parallel to two opposite sides crossing the other two at a relative
    position in (lo, hi). Returns (axis, halves) or None; axis 0 = the fold runs top->bottom."""
    tl, tr, br, bl = q
    best = None
    for axis in (0, 1):
        if axis == 0:
            s1, s2 = (tl, bl), (tr, br)        # sides parallel to the fold
            c1, c2 = (tl, tr), (bl, br)        # sides the fold crosses
        else:
            s1, s2 = (tl, tr), (bl, br)
            c1, c2 = (tl, bl), (tr, br)
        fdir = (s1[1] - s1[0]) / np.linalg.norm(s1[1] - s1[0]) + (s2[1] - s2[0]) / np.linalg.norm(s2[1] - s2[0])
        fdir /= np.linalg.norm(fdir)
        span = 0.5 * (np.linalg.norm(s1[1] - s1[0]) + np.linalg.norm(s2[1] - s2[0]))
        for ln in lines:
            if abs(float(ln.d @ fdir)) < np.cos(np.radians(10)) or ln.length < 0.6 * span:
                continue
            pts = []
            for a, b in (c1, c2):
                x = LN._intersect(ln, LN.Line(a, b - a, 0))
                if x is None:
                    break
                pts.append((float((x - a) @ (b - a) / ((b - a) @ (b - a))), x))
            if len(pts) < 2:
                continue
            (u1, x1), (u2, x2) = pts
            if not (lo < u1 < hi and lo < u2 < hi and abs(u1 - u2) < 0.15):
                continue
            centre = abs(0.5 * (u1 + u2) - 0.5)
            if best is None or centre < best[0]:
                best = (centre, axis, x1, x2)
    if best is None:
        return None
    _, axis, x1, x2 = best
    if axis == 0:
        return axis, (np.array([tl, x1, x2, bl]), np.array([x1, tr, br, x2]))
    return axis, (np.array([tl, tr, x2, x1]), np.array([x1, x2, br, bl]))


def _across(hq, axis):
    """Mean width of a half measured across the fold."""
    if axis == 0:
        return 0.5 * (np.linalg.norm(hq[1] - hq[0]) + np.linalg.norm(hq[2] - hq[3]))
    return 0.5 * (np.linalg.norm(hq[3] - hq[0]) + np.linalg.norm(hq[2] - hq[1]))


def _page_shaped(hq):
    sides = [np.linalg.norm(hq[i] - hq[(i + 1) % 4]) for i in range(4)]
    a, b = 0.5 * (sides[0] + sides[2]), 0.5 * (sides[1] + sides[3])
    return 1.1 <= max(a, b) / max(1e-6, min(a, b)) <= 2.0


def _split_spread(q, lines, shape):
    """The winner is a spread whose one page is clearly cut by the frame: return the fully visible page."""
    h, w = shape
    margin = 0.012 * max(h, w)
    found = _fold(q, lines)
    if found is None:
        return None
    axis, halves = found

    def clipped(hq):
        # side 0 of the first half / side 2 of the second... simpler: any side except the fold on the frame
        fold_pts = {tuple(np.round(p, 3)) for p in (halves[0][1], halves[0][2])} if axis == 0 else             {tuple(np.round(p, 3)) for p in (halves[0][2], halves[0][3])}
        for i in range(4):
            a, b = hq[i], hq[(i + 1) % 4]
            if {tuple(np.round(a, 3)), tuple(np.round(b, 3))} == fold_pts:
                continue
            if D._border_side(a, b, (h, w), margin):
                return True
        return False

    c = [clipped(hq) for hq in halves]
    if c[0] == c[1]:
        return None
    keep, cut = (halves[1], halves[0]) if c[0] else (halves[0], halves[1])
    # Only a clearly cut page counts: a spread whose outer edge merely touches the frame stays whole.
    if _across(cut, axis) > 0.8 * _across(keep, axis):
        return None
    # the kept half must be page-shaped (a single sheet with a table rule is not a spread)
    return keep if _page_shaped(keep) else None


def _grow_to_spread(best, scored, lines, ctx, shape):
    """The user's rule: when both pages of an open notebook are fully in the photo, crop the whole spread. The
    fold often separates the pages, so one page can win on its own; if a candidate holds the winner as one half
    of a fold and its other half is a similar, full page, take that spread."""
    bq = order_quad(best.astype(np.float32))
    area_b = cv2.contourArea(bq.reshape(-1, 1, 2))
    ref_mine = _page_stats(ctx, bq, shape)[4]
    for _, q, info in scored:
        real = [v for v in info["sup"] if v is not None]
        # spread edges bow (the pages curve up towards the fold), so judge them on average
        if not real or float(np.mean(real)) < 0.6 or min(real) < 0.45:
            continue
        qq = order_quad(q.astype(np.float32)).astype(np.float64)
        a = cv2.contourArea(qq.astype(np.float32).reshape(-1, 1, 2))
        if not 1.6 * area_b <= a <= 2.6 * area_b:
            continue
        found = _fold(qq, lines, 0.3, 0.7)
        if found is None:
            continue
        axis, halves = found
        ious = [D._iou_convex(order_quad(hq.astype(np.float32)), bq) for hq in halves]
        if max(ious) < 0.8:
            continue
        other = halves[1] if ious[0] >= ious[1] else halves[0]
        mine = halves[0] if ious[0] >= ious[1] else halves[1]
        ratio = _across(other, axis) / max(1e-6, _across(mine, axis))
        if not (0.8 <= ratio <= 1.25 and _page_shaped(other)):
            continue
        # the other half must be the same paper (not desk or floor beyond a straight page edge)
        page, _, _, _, ref = _page_stats(ctx, order_quad(other.astype(np.float32)), shape)
        if page >= 0.6 and ref >= ref_mine - 25:
            return q
    return best


def detect_quad(image, refine=True, debug=False, aspect_prior=None, work_max=WORK_MAX, live=False):
    small, s = D._resize_max(image, work_max)
    h, w = small.shape[:2]
    k = max(5, int(round(max(h, w) / 90.0)) | 1)
    chans = LN.suppressed_lab(small, k)
    grads = LN.gradients(chans)
    mag = np.sqrt(grads[0] ** 2 + grads[1] ** 2).max(axis=0)
    gthresh = max(12.0, float(np.percentile(mag, 85)))
    gray = cv2.cvtColor(small, cv2.COLOR_BGR2GRAY)
    ctx = _context(chans, mag, gthresh, LIVE_STATS_MAX if live else None)

    lines = []
    if live:
        # Camera frames are luma only (a/b flat) and must be fast: lines from closed and plain luma only.
        cands = []
        lc = LN.candidates(small, grads, gthresh, [chans[0], gray], lines_out=lines)
    else:
        cands = [order_quad(q) for q in D._candidates(small)[0]]
        clahe = cv2.createCLAHE(clipLimit=3.0, tileGridSize=(8, 8)).apply(chans[0])
        lc = LN.candidates(small, grads, gthresh, chans + [gray, clahe], lines_out=lines)
    lc.sort(key=lambda t: -t[0])
    cands += [q for _, q, _, _ in lc[:LIVE_TOP_LINE if live else TOP_LINE]]

    area_img = float(h * w)
    scored = []
    for q in cands:
        r = _total(q.astype(np.float32), grads, gthresh, ctx, (h, w), area_img)
        if r is not None:
            total, info = r
            if aspect_prior is not None:
                # cards carry coloured bands and photos: the paper-rim test doesn't apply to them
                rim = info["rim"]
                total -= 0.25 * (rim - 0.8) + RIM_FAIL * min(0.0, rim - 0.85)
                total += ASPECT * (1.0 - min(1.0, abs(np.log(_aspect(q) / aspect_prior)) / 0.25))
            scored.append((total, q, info))
    if not scored:
        return (None, 0.0, []) if debug else (None, 0.0)
    scored.sort(key=lambda t: -t[0])
    best_score, best, _ = scored[0]
    if best_score < MIN_SCORE:
        return (None, best_score, scored) if debug else (None, best_score)
    best = _prefer_enclosing(best, scored, (h, w), ctx)
    if aspect_prior is None:
        best = _grow_to_spread(best, scored, lines, ctx, (h, w))
    half = _split_spread(order_quad(best).astype(np.float64), lines, (h, w))
    if half is not None:
        half = order_quad(half.astype(np.float32))
        # the half inherits the spread's outer sides; prefer a scored candidate that traces that page itself
        match = max(scored, key=lambda t: D._iou_convex(order_quad(t[1].astype(np.float32)), half))
        if D._iou_convex(order_quad(match[1].astype(np.float32)), half) >= 0.85:
            half = match[1].astype(np.float32)
        best = half
    quad = order_quad(best) / s
    if refine:
        big, s2 = D._resize_max(image, D.REFINE_MAX)
        quad = D._refine(big, quad * s2) / s2
    conf = float(min(1.0, best_score))
    return (order_quad(quad), conf, scored) if debug else (order_quad(quad), conf)


LIVE_MAX = 480


def detect_quad_live(gray):
    """Camera overlay: the same pipeline on the analysis frame's luma (Y plane) at 480 px, no refinement. With no
    chroma the a/b channels are flat, so colour terms drop out. Returns (quad in input px or None, confidence)."""
    bgr = cv2.cvtColor(gray, cv2.COLOR_GRAY2BGR)
    return detect_quad(bgr, refine=False, work_max=LIVE_MAX, live=True)
