import json
from pathlib import Path

import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFont

SEED = 1234
PAGE_SIZE = (1240, 1754)
FONT_DIR = Path("C:/Windows/Fonts")
PRINT = ("georgia.ttf", "times.ttf", "arial.ttf")
HAND = ("Inkfree.ttf", "segoepr.ttf", "segoesc.ttf", "Gabriola.ttf")
WORDS = ("velocity momentum tension radius pressure density energy "
         "temperature distance frequency amplitude displacement mass").split()


def rng_for(*keys):
    return np.random.default_rng(np.random.SeedSequence([SEED, *keys]))


def font(name, size):
    for candidate in (name, *PRINT, *HAND):
        try:
            return ImageFont.truetype(str(FONT_DIR / candidate), size)
        except OSError:
            continue
    return ImageFont.load_default(size=size)


def page_image(kind, index):
    rng = rng_for(0, index)
    page = Image.new("RGB", PAGE_SIZE, (250, 249, 246))
    draw = ImageDraw.Draw(page)
    fonts = HAND if kind == "hand" else PRINT
    face = fonts[index % len(fonts)]
    body = font(face, 32)
    ink = "#203b72" if kind == "hand" and index % 2 else "#262522"
    draw.text((90, 100), "Applied mechanics | Workshop " + str(index + 1),
              font=font(face, 46), fill=ink)
    if kind == "table":
        columns = ("Trial", "Distance", "Time", "Velocity", "Residual")
        for row in range(23):
            for col, heading in enumerate(columns):
                x, y = 90 + col * 212, 245 + row * 55
                draw.rectangle((x, y, x + 212, y + 55), outline=ink,
                               fill="#e3e7eb" if row == 0 else None, width=2)
                value = heading if row == 0 else f"{rng.uniform(1, 99):.2f}"
                draw.text((x + 12, y + 7), value, fill=ink, font=body)
    else:
        for row in range(26):
            y = 225 + row * 51 + (row // 7) * 22
            a, b = rng.choice(WORDS, size=2, replace=False)
            lines = (f"Compare the measured {a} with the baseline.",
                     f"Record the change in {b} for each trial.",
                     f"• Estimate {a} from the slope of the graph.",
                     "   F = m·a       Δt = t₂ − t₁")
            line = lines[row % len(lines)]
            if kind == "hand":
                if index % 2:
                    draw.line((80, y + 36, 1160, y + 36), fill="#dce0e5", width=2)
                strip = Image.new("RGBA", (1050, 54))
                ImageDraw.Draw(strip).text((14, 5), line, fill=ink, font=body)
                strip = strip.rotate(float(rng.uniform(-1.2, 1.2)), resample=Image.Resampling.BICUBIC)
                page.paste(strip, (84, y + int(rng.integers(-4, 5))), strip)
            else:
                draw.text((98, y), line, fill=ink, font=body)
    draw.text((580, 1650), str(index + 1), fill=ink, font=body)
    return page


def background(size, kind, rng):
    w, h = size
    y, x = np.ogrid[:h, :w]
    noise = rng.normal(0, 1, (h, w)).astype(np.float32)
    if kind == "wood":
        grain = cv2.resize(rng.normal(0, 9, (h, 12)).astype(np.float32), size)
        grain += 8 * np.sin(y / 4 + 0.6 * np.sin(x / 160))
        base = (92, 62, 43)
    elif kind == "checks":
        grain = 30 * ((x // 64 + y // 64) % 2) + 9 * np.sin(x / 3)
        base = (99, 119, 132)
    elif kind == "carpet":
        grain = 9 * cv2.GaussianBlur(noise, (3, 3), 0.6) + 4 * np.sin((x + y) / 5)
        base = (88, 80, 73)
    else:
        grain = noise * 2
        base = (239, 238, 235) if kind == "white" else (145, 147, 148)
    result = np.clip(np.array(base) + grain[..., None] + noise[..., None], 0, 255).astype("uint8")
    if kind == "clutter":
        for _ in range(52):
            x0, y0 = rng.integers((0, 0), size)
            x1, y1 = rng.integers((0, 0), (310, 250)) + (x0, y0)
            color = tuple(map(int, rng.integers(25, 235, 3)))
            shape = int(rng.integers(3))
            if shape == 0:
                cv2.rectangle(result, (x0, y0), (x1, y1), color, -1, cv2.LINE_AA)
            elif shape == 1:
                cv2.line(result, (x0, y0), (x1, y1), color, 6, cv2.LINE_AA)
            else:
                cv2.circle(result, (x0, y0), (x1 - x0) // 4, color, -1, cv2.LINE_AA)
    return result


def pixel_corners(size):
    w, h = size
    return np.array([[0, 0], [w - 1, 0], [w - 1, h - 1], [0, h - 1]], np.float32)


def placement(source_size, size, rng, strong=False):
    w, h = size
    sw, sh = source_size
    unit = (pixel_corners(source_size) + 0.5 - np.array(source_size) / 2) / sh
    for _ in range(5000):
        angle = np.deg2rad(rng.uniform(-35 if strong else -8, 35 if strong else 8))
        c, s = np.cos(angle), np.sin(angle)
        skew = rng.uniform(-0.19 if strong else -0.055, 0.19 if strong else 0.055, (4, 2))
        offset = ((unit + skew) @ np.array([[c, s], [-s, c]]))
        scale = min(0.96 * w / np.ptp(offset[:, 0]), 0.96 * h / np.ptp(offset[:, 1]))
        offset *= scale * rng.uniform(0.81, 0.995)
        low = np.array(size) * .02 - offset.min(axis=0)
        high = np.array(size) * .98 - offset.max(axis=0)
        quad = np.round(offset + rng.uniform(low, high), 2).astype(np.float32)
        area = cv2.contourArea(quad) / (w * h)
        if not 0.40 <= area <= 0.85 or not cv2.isContourConvex(quad):
            continue
        if strong:
            sides = np.roll(quad, -1, axis=0) - quad
            sides /= np.linalg.norm(sides, axis=1, keepdims=True)
            turns = np.sum(sides * np.roll(sides, 1, axis=0), axis=1)
            if np.max(np.abs(turns)) > .5 or np.max(np.abs(turns)) < .22:
                continue
        return quad, cv2.getPerspectiveTransform(pixel_corners(source_size), quad)
    raise ValueError(f"No valid placement for {source_size} on {size}")


def composite(page, scene, matrix, drop_shadow):
    size = (scene.shape[1], scene.shape[0])
    alpha = cv2.warpPerspective(np.ones(page.size[::-1], np.float32), matrix, size)
    warped = cv2.warpPerspective(np.asarray(page), matrix, size).astype(np.float32)
    if drop_shadow:
        shifted = cv2.warpAffine(alpha, np.float32([[1, 0, 9], [0, 1, 13]]), size)
        scene = scene * (1 - .32 * cv2.GaussianBlur(shifted, (0, 0), 10)[..., None])
    return warped + scene * (1 - alpha[..., None])


def gaussian(size, cx, cy, rx, ry):
    y, x = np.ogrid[:size[1], :size[0]]
    return np.exp(-0.5 * (((x - cx) / rx) ** 2 + ((y - cy) / ry) ** 2)).astype(np.float32)


def glare(image, quad, rng, large=False):
    size = (image.shape[1], image.shape[0])
    center = quad.mean(axis=0)
    blobs = []
    for _ in range(1 if large else int(rng.integers(1, 4))):
        cx, cy = center + rng.uniform(-.16, .16, 2) * np.ptp(quad, axis=0)
        radius = float(min(size) * (.24 if large else rng.uniform(.025, .07)))
        ry = radius * .67
        strength = 2.3 if large else 1.25
        glow = np.clip(strength * gaussian(size, cx, cy, radius, ry), 0, 1)
        image = image * (1 - glow[..., None]) + 255 * glow[..., None]
        blobs.append(dict(cx=float(cx), cy=float(cy), r=radius, ry=ry, strength=strength))
    return image, blobs


def jpeg(path, image):
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path, quality=90, subsampling=2, optimize=True)


def record(category, size, page_size, quad, matrix, texture, effects):
    return dict(category=category, size=list(size), page_size=list(page_size),
                corners=np.round(quad.astype(float), 2).tolist(),
                homography=matrix.tolist(), background=texture, effects=effects)


def write_truth(directory, generator, entries, key="images"):
    data = dict(version=1, generator=f"tools/fixtures/{generator}.py", seed=SEED)
    data[key] = entries
    (directory / "truth.json").write_text(json.dumps(data, indent=2, sort_keys=True) + "\n",
                                         encoding="utf-8", newline="\n")


def contact_sheet(directory, entries):
    columns = 6 if len(entries) > 12 else 4
    cell_w, cell_h = 194, 225
    sheet = Image.new("RGB", (columns * cell_w, ((len(entries) + columns - 1) // columns) * cell_h),
                      "#252725")
    draw = ImageDraw.Draw(sheet)
    for i, (name, info) in enumerate(entries.items()):
        with Image.open(directory / name) as photo:
            photo.thumbnail((182, 197), Image.Resampling.LANCZOS)
            x = (i % columns) * cell_w + (cell_w - photo.width) // 2
            y = (i // columns) * cell_h + (197 - photo.height) // 2
            sheet.paste(photo, (x, y))
            quad = [(x + a * photo.width / info["size"][0], y + b * photo.height / info["size"][1])
                    for a, b in info["corners"]]
        draw.line(quad + quad[:1], fill="#42ff68", width=2)
        draw.text(((i % columns) * cell_w + 6, (i // columns) * cell_h + 201),
                  Path(name).stem, fill="white", font=font("arial.ttf", 14))
    jpeg(directory / "_contact_sheet.jpg", sheet)
